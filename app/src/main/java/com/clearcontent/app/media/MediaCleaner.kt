package com.clearcontent.app.media

import android.content.Context
import android.net.Uri
import com.clearcontent.app.settings.AppSettings
import com.clearcontent.app.settings.ImageMode
import com.clearcontent.app.settings.OutputFormat
import com.clearcontent.core.media.BlockType
import com.clearcontent.core.media.CaptureDate
import com.clearcontent.core.media.FileChannelSource
import com.clearcontent.core.media.MediaFormat
import com.clearcontent.core.media.MediaKind
import com.clearcontent.core.media.MetadataScanner
import com.clearcontent.core.media.MetadataStripper
import com.clearcontent.core.media.RemovedItem
import com.clearcontent.core.media.ScanReport
import com.clearcontent.core.media.StripOptions
import com.clearcontent.core.media.StripOutcome
import com.clearcontent.core.media.UnsupportedMediaException
import java.io.File
import java.io.IOException

enum class CleanMethod { LOSSLESS, REENCODED, REMUXED }

data class CleanOutput(
    val file: File,
    val shareUri: Uri,
    val galleryUri: Uri?,
    val mimeType: String,
    val format: MediaFormat,
    val sizeBytes: Long,
    val galleryError: String? = null,
    /** Name to give the gallery copy once the original is deleted (keeps e.g. "20260917_142300.jpg"). */
    val finalName: String? = null,
) {
    val isVideo: Boolean get() = format.kind == MediaKind.VIDEO
}

data class CleanResult(
    val before: ScanReport,
    val after: ScanReport,
    val method: CleanMethod,
    val removed: List<RemovedItem>,
    val notes: List<String>,
    val output: CleanOutput,
)

class CleanFailedException(message: String) : IOException(message)

/**
 * Cleans one item: scan → lossless strip (or re-encode / remux) → re-scan to verify → publish.
 * A result is only published when the verification scan finds no provenance or identifying metadata.
 */
class MediaCleaner(
    private val context: Context,
    private val reader: SourceReader,
    private val store: OutputStore,
) {

    fun scan(uri: Uri): ScanReport = reader.openSource(uri).use { MetadataScanner.scan(it) }

    fun clean(info: SourceInfo, settings: AppSettings, onStage: (String) -> Unit = {}): CleanResult {
        val head = reader.readHead(info.uri)
        val sniffed = MediaFormat.detect(head)
        val format = if (sniffed != MediaFormat.UNKNOWN) sniffed else MediaFormat.fromMime(info.mimeType) ?: MediaFormat.UNKNOWN
        val isVideo = format.kind == MediaKind.VIDEO || (format == MediaFormat.UNKNOWN && info.mimeType?.startsWith("video/") == true)
        return if (isVideo) cleanVideo(info, format, settings, onStage) else cleanImage(info, settings, onStage)
    }

    // ------------------------------------------------------------------ images

    private fun cleanImage(info: SourceInfo, settings: AppSettings, onStage: (String) -> Unit): CleanResult {
        onStage("Чтение")
        val bytes = reader.readBytes(info.uri, MetadataScanner.MAX_IMAGE_BYTES)
        onStage("Анализ")
        val before = MetadataScanner.scan(bytes)
        if (before.format == MediaFormat.UNKNOWN) throw CleanFailedException("Неподдерживаемый формат файла")

        val requested = when (settings.outputFormat) {
            OutputFormat.ORIGINAL -> null
            OutputFormat.JPEG -> MediaFormat.JPEG
            OutputFormat.PNG -> MediaFormat.PNG
            OutputFormat.WEBP -> MediaFormat.WEBP
        }?.takeIf { it != before.format }
        val resize = settings.maxDimension > 0
        val captureDate = captureDateFor(info, settings)
        val stripOptions = StripOptions(settings.keepColorProfile, settings.keepOrientation, captureDate)
        val notes = mutableListOf<String>()
        var removed: List<RemovedItem> = emptyList()

        var data: ByteArray? = null
        var method = CleanMethod.LOSSLESS
        var outFormat = before.format
        val losslessAllowed = requested == null && !resize &&
            (settings.imageMode == ImageMode.SMART || before.traits.animated)
        if (losslessAllowed && before.format.losslessStrip && before.parseError == null) {
            onStage("Удаление метаданных")
            when (val outcome = MetadataStripper.stripImage(bytes, stripOptions)) {
                is StripOutcome.Stripped -> {
                    if (MetadataScanner.scan(outcome.data).isClean(settings.keepColorProfile)) {
                        data = outcome.data
                        removed = outcome.removed
                        notes += outcome.notes
                        if (settings.imageMode == ImageMode.REENCODE) notes += "Анимация: перекодирование пропущено, метаданные удалены без потерь"
                    } else {
                        notes += "Очистка без потерь оставила следы — выполнено перекодирование"
                    }
                }
                is StripOutcome.Unsupported -> notes += outcome.reason
            }
        }
        if (data == null) {
            onStage("Перекодирование")
            val encoded = try {
                ImageReencoder.encode(bytes, before.format, before.traits, requested, settings.jpegQuality, settings.maxDimension, settings.keepColorProfile)
            } catch (e: OutOfMemoryError) {
                throw CleanFailedException("Недостаточно памяти для перекодирования. Уменьшите «Макс. размер» в настройках")
            } catch (e: Exception) {
                throw CleanFailedException("Не удалось декодировать изображение: ${e.message ?: e.javaClass.simpleName}")
            }
            data = encoded.bytes
            if (captureDate != null) {
                // Re-encoded pixels already carry the rotation; only the capture date is written back.
                val dated = MetadataStripper.stripImage(data, StripOptions(keepColorProfile = true, keepOrientation = false, captureDate = captureDate))
                if (dated is StripOutcome.Stripped) data = dated.data
            }
            outFormat = encoded.format
            method = CleanMethod.REENCODED
            notes += encoded.notes
            removed = before.findings.filter { it.kind.severity != com.clearcontent.core.media.Severity.INFO }
                .map { RemovedItem(BlockType.UNKNOWN, it.location, 0) }
                .distinctBy { it.location }
        }

        onStage("Проверка")
        val after = MetadataScanner.scan(data)
        if (!after.isClean(settings.keepColorProfile)) {
            throw CleanFailedException("После очистки остались данные: " + after.residual.joinToString { it.location })
        }
        val file = store.newFile(info.displayName, outFormat, settings.neutralFileNames)
        file.writeBytes(data)
        val output = publish(file, outFormat, settings, info)
        return CleanResult(before, after, method, removed, notes, output)
    }

    // ------------------------------------------------------------------ video

    private fun cleanVideo(info: SourceInfo, format: MediaFormat, settings: AppSettings, onStage: (String) -> Unit): CleanResult {
        onStage("Анализ")
        val before = reader.openSource(info.uri).use { MetadataScanner.scan(it) }
        val notes = mutableListOf<String>()
        var removed: List<RemovedItem> = emptyList()
        var method = CleanMethod.LOSSLESS
        var outFormat = if (format == MediaFormat.MOV) MediaFormat.MOV else MediaFormat.MP4
        var result: File? = null

        val canStrip = !settings.deepVideoClean && (format == MediaFormat.MP4 || format == MediaFormat.MOV)
        if (canStrip) {
            onStage("Удаление метаданных")
            val candidate = store.workFile(".${outFormat.extension}")
            try {
                val r = reader.openSource(info.uri).use { src ->
                    candidate.outputStream().buffered(1 shl 20).use { MetadataStripper.stripVideo(src, it) }
                }
                if (verify(candidate)) {
                    result = candidate
                    removed = r.removed
                    notes += r.notes
                } else {
                    notes += "Очистка без потерь оставила следы — выполнено перемуксирование"
                    candidate.delete()
                }
            } catch (e: UnsupportedMediaException) {
                notes += "${e.message} — выполнено перемуксирование"
                candidate.delete()
            }
        }

        if (result == null) {
            onStage("Перемуксирование")
            val remuxed = store.workFile(".tmp")
            val r = try {
                VideoRemuxer.remux(context, info.uri, remuxed)
            } catch (e: Exception) {
                remuxed.delete()
                throw CleanFailedException("Не удалось перемуксировать видео: ${e.message ?: e.javaClass.simpleName}")
            }
            notes += r.notes
            method = CleanMethod.REMUXED
            outFormat = if (r.webm) MediaFormat.MATROSKA else MediaFormat.MP4
            removed = before.findings.filter { it.kind.severity != com.clearcontent.core.media.Severity.INFO }
                .map { RemovedItem(BlockType.UNKNOWN, it.location, 0) }
                .distinctBy { it.location }
            result = if (r.webm) {
                remuxed
            } else {
                // MediaMuxer writes its own moov/udta/meta (e.g. "com.android.version"); strip that too.
                val stripped = store.workFile(".mp4")
                try {
                    FileChannelSource.open(remuxed).use { src ->
                        stripped.outputStream().buffered(1 shl 20).use { MetadataStripper.stripVideo(src, it) }
                    }
                } finally {
                    remuxed.delete()
                }
                stripped
            }
        }

        onStage("Проверка")
        val after = FileChannelSource.open(result).use { MetadataScanner.scan(it) }
        if (!after.isClean()) {
            result.delete()
            throw CleanFailedException("После очистки остались данные: " + after.residual.joinToString { it.location })
        }
        val file = store.newFile(info.displayName, outFormat, settings.neutralFileNames)
        if (!result.renameTo(file)) {
            result.copyTo(file, overwrite = true)
            result.delete()
        }
        val output = publish(file, outFormat, settings, info)
        return CleanResult(before, after, method, removed, notes, output)
    }

    /** In replace mode the capture date is kept so the clean copy stays at the original's place in the timeline. */
    private fun captureDateFor(info: SourceInfo, settings: AppSettings): CaptureDate? {
        if (!settings.replaceOriginals || info.galleryItem == null || !GalleryOriginals.supported) return null
        val millis = info.dateTaken ?: return null
        val time = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        val offset = time.offset.totalSeconds.let { s ->
            "%s%02d:%02d".format(if (s < 0) "-" else "+", kotlin.math.abs(s) / 3600, kotlin.math.abs(s) % 3600 / 60)
        }
        return runCatching {
            CaptureDate(time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)), offset)
        }.getOrNull()
    }

    private fun verify(file: File): Boolean = FileChannelSource.open(file).use { MetadataScanner.scan(it).isClean() }

    private fun publish(file: File, format: MediaFormat, settings: AppSettings, info: SourceInfo): CleanOutput {
        val mime = format.mimeType
        val replace = settings.replaceOriginals && info.galleryItem != null && GalleryOriginals.supported
        var galleryUri: Uri? = null
        var galleryError: String? = null
        if (settings.autoSaveToGallery) {
            try {
                galleryUri = if (replace) {
                    store.saveToGallery(file, mime, format.kind == MediaKind.VIDEO, info.relativePath)
                } else {
                    store.saveToGallery(file, mime, format.kind == MediaKind.VIDEO)
                }
            } catch (e: Exception) {
                galleryError = e.message ?: "Не удалось сохранить в галерею"
            }
        }
        val finalName = if (replace && info.nameKnown && !(settings.neutralFileNames && OutputStore.looksGenerated(info.displayName))) {
            info.displayName.substringBeforeLast('.').ifBlank { null }?.let { "$it.${format.extension}" }
        } else {
            null
        }
        return CleanOutput(file, store.shareUri(file), galleryUri, mime, format, file.length(), galleryError, finalName)
    }
}
