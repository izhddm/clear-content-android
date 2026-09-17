package com.clearcontent.app.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.clearcontent.app.BuildConfig
import com.clearcontent.core.media.ByteSource
import com.clearcontent.core.media.FileChannelSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class SourceInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val mimeType: String?,
    /** The gallery item this content came from, if any (see [GalleryOriginals]). */
    val galleryItem: Uri? = null,
    /** Album folder of the original, e.g. "DCIM/Camera/", when the provider exposes it. */
    val relativePath: String? = null,
    val dateTaken: Long? = null,
    /** False when [displayName] is only a placeholder derived from the URI. */
    val nameKnown: Boolean = true,
)

class TooLargeException(val limit: Long) : IOException("Файл больше ${limit / (1024 * 1024)} МБ")

/** Reads shared/picked content without requiring any storage permission. */
class SourceReader(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val tempDir = File(context.cacheDir, "incoming").apply { mkdirs() }

    fun describe(uri: Uri): SourceInfo {
        val name = queryString(uri, OpenableColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() }
        val size = queryLong(uri, OpenableColumns.SIZE)
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        // Providers differ in the columns they accept, so each optional column is queried on its own.
        val dateTaken = queryLong(uri, MediaStore.MediaColumns.DATE_TAKEN)?.takeIf { it > 0 }
        val relativePath = queryString(uri, MediaStore.MediaColumns.RELATIVE_PATH)
        val gallery = GalleryOriginals.resolve(context, uri, mime)
        if (BuildConfig.DEBUG) Log.d("SourceReader", "describe $uri → name=$name gallery=$gallery path=$relativePath taken=$dateTaken")
        // The system photo picker hides real file names and reports "<id>.<ext>" instead.
        val redacted = name != null && name.substringBeforeLast('.') == uri.lastPathSegment
        return SourceInfo(uri, name ?: fallbackName, size, mime, gallery, relativePath, dateTaken, nameKnown = name != null && !redacted)
    }

    private fun queryString(uri: Uri, column: String): String? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    private fun queryLong(uri: Uri, column: String): Long? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    fun readHead(uri: Uri, bytes: Int = 256): ByteArray =
        (resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")).use { input ->
            val buf = ByteArray(bytes)
            var total = 0
            while (total < bytes) {
                val n = input.read(buf, total, bytes - total)
                if (n < 0) break
                total += n
            }
            buf.copyOf(total)
        }

    fun readBytes(uri: Uri, limit: Long): ByteArray =
        (resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw TooLargeException(limit)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }

    /**
     * Random access to the content. Seekable descriptors are used directly; pipes and
     * other streaming providers are first spooled into the cache.
     */
    fun openSource(uri: Uri): ByteSource {
        val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            val stream = FileInputStream(pfd.fileDescriptor)
            val channel = stream.channel
            val seekable = runCatching { channel.size() > 0 && channel.position(0) != null }.getOrDefault(false)
            if (seekable) {
                return FileChannelSource(channel) {
                    stream.close()
                    pfd.close()
                }
            }
            stream.close()
            pfd.close()
        }
        val temp = File.createTempFile("src", ".bin", tempDir)
        (resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")).use { input ->
            temp.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        val source = FileChannelSource.open(temp)
        return object : ByteSource by source {
            override fun close() {
                source.close()
                temp.delete()
            }
        }
    }

    /**
     * Copies shared content into the app cache and returns a file-backed [SourceInfo] with the
     * original name and type, so processing no longer depends on the sender's temporary grant.
     */
    fun spool(info: SourceInfo): SourceInfo {
        if (info.uri.scheme == "file") return info
        val free = tempDir.usableSpace
        if (info.sizeBytes != null && info.sizeBytes > free - (64L shl 20)) return info
        val target = File.createTempFile("share", ".bin", tempDir)
        try {
            (resolver.openInputStream(info.uri) ?: throw IOException("Не удалось открыть файл")).use { input ->
                target.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return info.copy(uri = Uri.fromFile(target), sizeBytes = info.sizeBytes ?: target.length())
    }

    fun releaseSpool(info: SourceInfo) {
        val path = info.uri.path ?: return
        if (info.uri.scheme == "file" && path.startsWith(tempDir.path)) File(path).delete()
    }

    fun readText(uri: Uri, limit: Long = 2L shl 20): String = String(readBytes(uri, limit), Charsets.UTF_8)
}
