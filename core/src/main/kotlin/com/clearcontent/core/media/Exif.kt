package com.clearcontent.core.media

import java.io.ByteArrayOutputStream

/** The few EXIF facts we care about: orientation (to preserve), and identifying fields (to report). */
data class ExifSummary(
    val orientation: Int = 1,
    val make: String? = null,
    val model: String? = null,
    val software: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val userComment: String? = null,
    val dateTimeOriginal: String? = null,
    val hasGps: Boolean = false,
    val hasThumbnail: Boolean = false,
    val tagCount: Int = 0,
    val tags: Set<Int> = emptySet(),
) {
    val textFields: List<String> get() = listOfNotNull(make, model, software, artist, description, userComment)
}

/** Minimal, bounds-checked TIFF/EXIF reader. Never throws: broken EXIF yields a partial summary. */
object ExifReader {
    private const val TAG_DESCRIPTION = 0x010E
    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_SOFTWARE = 0x0131
    private const val TAG_ARTIST = 0x013B
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val TAG_DATE_ORIGINAL = 0x9003
    private const val TAG_USER_COMMENT = 0x9286
    private const val TAG_XP_COMMENT = 0x9C9C

    /** [tiff] starts at the TIFF header ("II*\0" / "MM\0*"). */
    fun read(tiff: ByteArray, offset: Int = 0, length: Int = tiff.size - offset): ExifSummary {
        val data = if (offset == 0 && length == tiff.size) tiff else tiff.copyOfRange(offset, offset + length)
        return try {
            Parser(data).parse()
        } catch (_: RuntimeException) {
            ExifSummary()
        } catch (_: MalformedMediaException) {
            ExifSummary()
        }
    }

    /** Accepts a JPEG APP1 payload ("Exif\0\0" + TIFF) or a bare TIFF block (PNG eXIf, WebP EXIF). */
    fun readPayload(payload: ByteArray): ExifSummary {
        val start = if (payload.startsWithAt(0, "Exif\u0000\u0000".latin1())) 6 else 0
        return if (start >= payload.size) ExifSummary() else read(payload, start, payload.size - start)
    }

    private class Parser(val d: ByteArray) {
        val little = when {
            d.ascii(0, 2) == "II" -> true
            d.ascii(0, 2) == "MM" -> false
            else -> throw MalformedMediaException("Not a TIFF header")
        }
        var tags = 0
        val tagIds = HashSet<Int>()
        var summary = ExifSummary()
        val visited = HashSet<Int>()

        fun u16(p: Int) = if (little) d.u16le(p) else d.u16be(p)
        fun u32(p: Int) = if (little) d.u32le(p) else d.u32be(p)

        fun parse(): ExifSummary {
            val ifd0 = u32(4).toInt()
            val next = readIfd(ifd0, root = true)
            if (next > 0 && next < d.size) summary = summary.copy(hasThumbnail = true)
            return summary.copy(tagCount = tags, tags = tagIds)
        }

        /** Returns the offset of the next IFD (0 if none). */
        fun readIfd(pos: Int, root: Boolean): Int {
            if (pos <= 0 || pos + 2 > d.size || !visited.add(pos)) return 0
            val count = u16(pos)
            if (count > 1000) return 0
            for (i in 0 until count) {
                val e = pos + 2 + i * 12
                if (e + 12 > d.size) return 0
                tags++
                val tag = u16(e)
                tagIds += tag
                val type = u16(e + 2)
                val n = u32(e + 4)
                when (tag) {
                    TAG_ORIENTATION -> if (root && type == 3) summary = summary.copy(orientation = u16(e + 8))
                    TAG_MAKE -> summary = summary.copy(make = ascii(e, type, n))
                    TAG_MODEL -> summary = summary.copy(model = ascii(e, type, n))
                    TAG_SOFTWARE -> summary = summary.copy(software = ascii(e, type, n))
                    TAG_ARTIST -> summary = summary.copy(artist = ascii(e, type, n))
                    TAG_DESCRIPTION -> summary = summary.copy(description = ascii(e, type, n))
                    TAG_DATE_ORIGINAL -> summary = summary.copy(dateTimeOriginal = ascii(e, type, n))
                    TAG_USER_COMMENT -> summary = summary.copy(userComment = undefinedText(e, n))
                    TAG_XP_COMMENT -> summary = summary.copy(userComment = summary.userComment ?: utf16(e, n))
                    TAG_GPS_IFD -> summary = summary.copy(hasGps = true)
                    TAG_EXIF_IFD -> if (root) readIfd(u32(e + 8).toInt(), root = false)
                }
            }
            val nextPos = pos + 2 + count * 12
            return if (nextPos + 4 <= d.size) u32(nextPos).toInt() else 0
        }

        fun valuePos(e: Int, byteLen: Long): Int? {
            if (byteLen <= 4) return e + 8
            val off = u32(e + 8)
            return if (off + byteLen <= d.size) off.toInt() else null
        }

        fun ascii(e: Int, type: Int, n: Long): String? {
            if (type != 2 || n <= 0 || n > 4096) return null
            val p = valuePos(e, n) ?: return null
            return String(d, p, n.toInt(), Charsets.UTF_8).trimEnd('\u0000', ' ').ifBlank { null }
        }

        fun undefinedText(e: Int, n: Long): String? {
            if (n <= 8 || n > 65536) return null
            val p = valuePos(e, n) ?: return null
            val charset = when (d.ascii(p, 8).trimEnd('?', ' ')) {
                "UNICODE" -> if (little) Charsets.UTF_16LE else Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            return String(d, p + 8, n.toInt() - 8, charset).trimEnd('\u0000', ' ').ifBlank { null }
        }

        fun utf16(e: Int, n: Long): String? {
            if (n <= 0 || n > 65536) return null
            val p = valuePos(e, n) ?: return null
            return String(d, p, n.toInt(), Charsets.UTF_16LE).trimEnd('\u0000', ' ').ifBlank { null }
        }
    }
}

/**
 * Builds the smallest valid TIFF block holding only what we deliberately keep:
 * Orientation (so the photo is not shown sideways) and, optionally, the capture date.
 */
object MinimalExif {
    const val TAG_ORIENTATION = 0x0112
    const val TAG_EXIF_IFD = 0x8769
    const val TAG_DATE_ORIGINAL = 0x9003
    const val TAG_OFFSET_ORIGINAL = 0x9011

    /** Tags this builder may emit; an EXIF block with nothing else is not treated as metadata. */
    val ALLOWED_TAGS = setOf(TAG_ORIENTATION, TAG_EXIF_IFD, TAG_DATE_ORIGINAL, TAG_OFFSET_ORIGINAL)

    fun tiff(orientation: Int, date: CaptureDate? = null): ByteArray {
        val keepOrientation = orientation in 2..8
        val ifd0Entries = (if (keepOrientation) 1 else 0) + (if (date != null) 1 else 0)
        val ifd0Size = 2 + ifd0Entries * 12 + 4
        val exifIfdOffset = 8 + ifd0Size
        val exifIfdSize = 2 + 2 * 12 + 4
        val dateOffset = exifIfdOffset + exifIfdSize
        val offsetValueOffset = dateOffset + 20

        val out = ByteArrayOutputStream()
        out.write("MM\u0000*".latin1())
        out.u32be(8)
        out.u16be(ifd0Entries)
        if (keepOrientation) entry(out, TAG_ORIENTATION, 3, 1) { out.u16be(orientation); out.u16be(0) }
        if (date != null) entry(out, TAG_EXIF_IFD, 4, 1) { out.u32be(exifIfdOffset.toLong()) }
        out.u32be(0)
        if (date != null) {
            out.u16be(2)
            entry(out, TAG_DATE_ORIGINAL, 2, 20) { out.u32be(dateOffset.toLong()) }
            entry(out, TAG_OFFSET_ORIGINAL, 2, 7) { out.u32be(offsetValueOffset.toLong()) }
            out.u32be(0)
            out.write(date.local.latin1()); out.write(0)
            out.write(date.offset.latin1()); out.write(0)
        }
        return out.toByteArray()
    }

    private inline fun entry(out: ByteArrayOutputStream, tag: Int, type: Int, count: Long, value: () -> Unit) {
        out.u16be(tag); out.u16be(type); out.u32be(count); value()
    }

    fun needed(orientation: Int, options: StripOptions): Boolean =
        (options.keepOrientation && orientation in 2..8) || options.captureDate != null

    /** JPEG APP1 payload: "Exif\0\0" + TIFF. */
    fun jpegPayload(orientation: Int, date: CaptureDate? = null): ByteArray =
        "Exif\u0000\u0000".latin1() + tiff(orientation, date)
}
