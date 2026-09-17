package com.clearcontent.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.clearcontent.core.media.MediaFormat
import com.clearcontent.core.media.MediaKind
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Where cleaned files live: an app-private cache (for sharing) and optionally the public gallery. */
class OutputStore(private val context: Context) {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    private val counter = AtomicInteger()
    private val authority = "${context.packageName}.files"

    fun newFile(originalName: String, format: MediaFormat, neutral: Boolean): File {
        val base = if (neutral || looksGenerated(originalName)) {
            val prefix = if (format.kind == MediaKind.VIDEO) "VID" else "IMG"
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            "${prefix}_${stamp}_${"%02d".format(counter.incrementAndGet() % 100)}"
        } else {
            originalName.substringBeforeLast('.').replace(UNSAFE, "_").take(80).ifBlank { "file" } + "_clean"
        }
        var file = File(sharedDir, "$base.${format.extension}")
        var n = 1
        while (file.exists()) file = File(sharedDir, "${base}_${n++}.${format.extension}")
        return file
    }

    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    /** Copies [file] into Pictures/ClearContent or Movies/ClearContent without any storage permission. */
    fun saveToGallery(
        file: File,
        mimeType: String,
        isVideo: Boolean,
        relativePath: String? = null,
    ): Uri {
        if (relativePath != null && isAllowedFolder(relativePath, isVideo)) {
            try {
                return insert(file, mimeType, isVideo, relativePath)
            } catch (_: IllegalArgumentException) {
                // Some folders are rejected by MediaStore; fall back to our own album.
            }
        }
        return insert(file, mimeType, isVideo, null)
    }

    /** Renames a gallery item this app created (used after the original was deleted). */
    fun rename(uri: Uri, displayName: String) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) }, null, null)
    }

    private fun isAllowedFolder(path: String, isVideo: Boolean): Boolean {
        val top = path.substringBefore('/')
        val allowed = if (isVideo) setOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_PICTURES)
        else setOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES)
        return top in allowed && !path.contains("..")
    }

    /** DATE_TAKEN is read-only in MediaStore: the gallery date comes from the capture date inside the file. */
    private fun insert(file: File, mimeType: String, isVideo: Boolean, relativePath: String?): Uri {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath ?: "$dir/ClearContent")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Галерея недоступна")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
                ?: throw IOException("Не удалось записать в галерею")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun deleteQuietly(file: File?) {
        file?.delete()
    }

    /** Removes shared copies older than [maxAgeMs]; recent ones may still be read by the target app. */
    fun cleanup(maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        listOf(sharedDir, File(context.cacheDir, "incoming"), File(context.cacheDir, "work")).forEach { dir ->
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    fun workFile(suffix: String): File {
        val dir = File(context.cacheDir, "work").apply { mkdirs() }
        return File.createTempFile("work", suffix, dir)
    }

    companion object {
        private val UNSAFE = Regex("[^\\p{L}\\p{N}._ -]")

        /** File names that reveal an AI origin, e.g. "ChatGPT Image 1 июн. 2025 г.png", "Gemini_Generated_Image_x.png". */
        private val GENERATED_NAME = Regex(
            "(?i)(chatgpt|dall[-·_ ]?e|openai|gemini|imagen|nano[-_ ]?banana|firefly|midjourney|mj_|stable[-_ ]?diffusion|sdxl|" +
                "comfyui|flux|grok|aurora|sora|veo|kling|hailuo|runway|luma|pika|leonardo|ideogram|copilot|designer|" +
                "bing|dreamina|jimeng|doubao|seedream|qwen|recraft|krea|upscayl|generated|\\bai[-_ ])",
        )

        fun looksGenerated(name: String): Boolean = GENERATED_NAME.containsMatchIn(name)
    }
}
