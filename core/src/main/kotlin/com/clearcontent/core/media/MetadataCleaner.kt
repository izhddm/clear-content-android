package com.clearcontent.core.media

import java.io.OutputStream

/** Entry point for reporting what a file carries. */
object MetadataScanner {

    fun scan(bytes: ByteArray): ScanReport {
        val format = MediaFormat.detect(bytes.copyOf(minOf(bytes.size, 256)))
        return when (format) {
            MediaFormat.JPEG -> JpegCodec.scan(bytes)
            MediaFormat.PNG -> PngCodec.scan(bytes)
            MediaFormat.WEBP -> WebpCodec.scan(bytes)
            MediaFormat.GIF -> GifCodec.scan(bytes)
            MediaFormat.HEIF, MediaFormat.AVIF, MediaFormat.MP4, MediaFormat.MOV ->
                BmffCodec.scan(ByteArraySource(bytes), format)
            MediaFormat.TIFF -> FindingCollector().also { RawScanner.scanTiff(bytes, it) }.build(format)
            else -> FindingCollector().also { RawScanner.scanInto(bytes, it) }.build(format)
        }
    }

    /** Streaming scan for large files (video). Images are read fully. */
    fun scan(src: ByteSource): ScanReport {
        val head = src.read(0, minOf(src.size, 256L).toInt())
        val format = MediaFormat.detect(head)
        return when {
            format == MediaFormat.MP4 || format == MediaFormat.MOV -> BmffCodec.scan(src, format)
            format == MediaFormat.MATROSKA -> scanTail(src, format)
            src.size <= MAX_IMAGE_BYTES -> scan(src.read(0, src.size.toInt()))
            else -> scanTail(src, format)
        }
    }

    /** For containers we do not parse (Matroska) search the head and tail, where tags usually live. */
    private fun scanTail(src: ByteSource, format: MediaFormat): ScanReport {
        val c = FindingCollector()
        val window = 8L shl 20
        val head = src.read(0, minOf(src.size, window).toInt())
        RawScanner.scanStrongTokens(head, c)
        c.analyzeText(extractStrings(head.copyOf(minOf(head.size, 1 shl 20))), "Заголовок")
        if (src.size > window) {
            val tail = src.read(src.size - window, window.toInt())
            RawScanner.scanStrongTokens(tail, c)
        }
        return c.build(format)
    }

    const val MAX_IMAGE_BYTES = 256L shl 20
}

/** Entry point for lossless metadata removal. */
object MetadataStripper {

    /** Removes metadata from an in-memory image. Returns [StripOutcome.Unsupported] when re-encoding is required. */
    fun stripImage(bytes: ByteArray, options: StripOptions = StripOptions()): StripOutcome {
        val format = MediaFormat.detect(bytes.copyOf(minOf(bytes.size, 256)))
        return try {
            when (format) {
                MediaFormat.JPEG -> JpegCodec.strip(bytes, options)
                MediaFormat.PNG -> PngCodec.strip(bytes, options)
                MediaFormat.WEBP -> WebpCodec.strip(bytes, options)
                MediaFormat.GIF -> GifCodec.strip(bytes, options)
                else -> StripOutcome.Unsupported("${format.name}: требуется перекодирование")
            }
        } catch (e: MalformedMediaException) {
            StripOutcome.Unsupported("${format.name}: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            StripOutcome.Unsupported("${format.name}: повреждённая структура")
        }
    }

    /**
     * Streams a cleaned MP4/MOV into [out].
     * @throws UnsupportedMediaException when the file needs a full remux instead.
     */
    fun stripVideo(src: ByteSource, out: OutputStream): VideoStripResult {
        val format = MediaFormat.detect(src.read(0, minOf(src.size, 256L).toInt()))
        if (format != MediaFormat.MP4 && format != MediaFormat.MOV) {
            throw UnsupportedMediaException("${format.name}: требуется перемуксирование")
        }
        return BmffCodec.stripVideo(src, out)
    }
}
