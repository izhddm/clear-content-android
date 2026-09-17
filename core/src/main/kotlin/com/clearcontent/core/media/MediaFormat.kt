package com.clearcontent.core.media

enum class MediaKind { IMAGE, VIDEO, UNKNOWN }

enum class MediaFormat(
    val kind: MediaKind,
    val mimeType: String,
    val extension: String,
    /** Metadata can be removed without touching the encoded pixels/samples. */
    val losslessStrip: Boolean,
) {
    JPEG(MediaKind.IMAGE, "image/jpeg", "jpg", true),
    PNG(MediaKind.IMAGE, "image/png", "png", true),
    WEBP(MediaKind.IMAGE, "image/webp", "webp", true),
    GIF(MediaKind.IMAGE, "image/gif", "gif", true),
    HEIF(MediaKind.IMAGE, "image/heif", "heic", false),
    AVIF(MediaKind.IMAGE, "image/avif", "avif", false),
    TIFF(MediaKind.IMAGE, "image/tiff", "tif", false),
    BMP(MediaKind.IMAGE, "image/bmp", "bmp", false),
    JXL(MediaKind.IMAGE, "image/jxl", "jxl", false),
    MP4(MediaKind.VIDEO, "video/mp4", "mp4", true),
    MOV(MediaKind.VIDEO, "video/quicktime", "mov", true),
    MATROSKA(MediaKind.VIDEO, "video/webm", "webm", false),
    UNKNOWN(MediaKind.UNKNOWN, "application/octet-stream", "bin", false);

    companion object {
        private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "mif2")
        private val AVIF_BRANDS = setOf("avif", "avis")
        private val QT_BRANDS = setOf("qt  ")

        /** Sniffs the container from the first bytes (at least 64 bytes recommended). */
        fun detect(head: ByteArray): MediaFormat {
            if (head.size < 12) return UNKNOWN
            fun at(pos: Int, s: String) = head.startsWithAt(pos, s.latin1())
            return when {
                head.u8(0) == 0xFF && head.u8(1) == 0xD8 && head.u8(2) == 0xFF -> JPEG
                at(0, "\u0089PNG\r\n\u001A\n") -> PNG
                at(0, "RIFF") && at(8, "WEBP") -> WEBP
                at(0, "GIF87a") || at(0, "GIF89a") -> GIF
                at(0, "II*\u0000") || at(0, "MM\u0000*") -> TIFF
                at(0, "BM") && head.size > 26 -> BMP
                (head.u8(0) == 0xFF && head.u8(1) == 0x0A) || at(4, "JXL ") -> JXL
                head.u8(0) == 0x1A && head.u8(1) == 0x45 && head.u8(2) == 0xDF && head.u8(3) == 0xA3 -> MATROSKA
                at(4, "ftyp") -> detectBmff(head)
                at(4, "moov") || at(4, "mdat") || at(4, "wide") || at(4, "free") -> MOV
                else -> UNKNOWN
            }
        }

        private fun detectBmff(head: ByteArray): MediaFormat {
            val boxSize = head.u32be(0).toInt().coerceIn(8, head.size)
            val major = head.ascii(8, 4)
            val brands = buildSet {
                add(major)
                var p = 16
                while (p + 4 <= boxSize) { add(head.ascii(p, 4)); p += 4 }
            }
            return when {
                major in AVIF_BRANDS -> AVIF
                major in HEIF_BRANDS && brands.none { it in AVIF_BRANDS } -> HEIF
                major in HEIF_BRANDS -> AVIF
                major in QT_BRANDS -> MOV
                else -> MP4
            }
        }

        fun fromMime(mime: String?): MediaFormat? = entries.firstOrNull { it.mimeType.equals(mime, ignoreCase = true) }
    }
}
