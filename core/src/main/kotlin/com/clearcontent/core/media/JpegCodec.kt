package com.clearcontent.core.media

import java.io.ByteArrayOutputStream

/**
 * JPEG segment surgery. Entropy-coded scan data is copied byte-for-byte, so pixels are untouched.
 */
internal object JpegCodec {

    private enum class Kind { SOI, JFIF, EXIF, XMP, ICC, MPF, JUMBF, PHOTOSHOP, ADOBE, COMMENT, THUMBNAIL, OTHER_APP, CORE, EOI }

    private class Segment(val marker: Int, val start: Int, val end: Int, val payloadStart: Int, val payloadEnd: Int) {
        lateinit var kind: Kind
    }

    private class Parsed(val segments: List<Segment>, val imageEnd: Int, val hasEoi: Boolean)

    private val EXIF_ID = "Exif\u0000".latin1()
    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".latin1()
    private val XMP_EXT_ID = "http://ns.adobe.com/xmp/extension/\u0000".latin1()
    private val ICC_ID = "ICC_PROFILE\u0000".latin1()
    private val MPF_ID = "MPF\u0000".latin1()
    private val JFIF_ID = "JFIF\u0000".latin1()
    private val JFXX_ID = "JFXX\u0000".latin1()
    private val PHOTOSHOP_ID = "Photoshop 3.0\u0000".latin1()
    private val ADOBE_ID = "Adobe".latin1()

    /** "Adobe" + version + flags0 + flags1 + color transform: the part decoders need. */
    private const val ADOBE_LEN = 12

    private fun parse(b: ByteArray): Parsed {
        if (b.size < 4 || b.u8(0) != 0xFF || b.u8(1) != 0xD8) throw MalformedMediaException("Not a JPEG")
        val segments = mutableListOf(Segment(0xD8, 0, 2, 2, 2).also { it.kind = Kind.SOI })
        var pos = 2
        while (pos < b.size) {
            if (b.u8(pos) != 0xFF) throw MalformedMediaException("Expected marker at $pos")
            var m = pos + 1
            while (m < b.size && b.u8(m) == 0xFF) m++
            if (m >= b.size) break
            val marker = b.u8(m)
            val afterMarker = m + 1
            when {
                marker == 0xD9 -> {
                    segments += Segment(marker, pos, afterMarker, afterMarker, afterMarker).also { it.kind = Kind.EOI }
                    return Parsed(segments, afterMarker, hasEoi = true)
                }
                marker in 0xD0..0xD7 || marker == 0x01 -> {
                    segments += Segment(marker, pos, afterMarker, afterMarker, afterMarker).also { it.kind = Kind.CORE }
                    pos = afterMarker
                }
                marker == 0x00 || marker == 0xD8 -> throw MalformedMediaException("Unexpected marker ${marker.toString(16)}")
                else -> {
                    val len = b.u16be(afterMarker)
                    if (len < 2) throw MalformedMediaException("Bad segment length")
                    val payloadStart = afterMarker + 2
                    val payloadEnd = afterMarker + len
                    if (payloadEnd > b.size) throw MalformedMediaException("Truncated segment")
                    var end = payloadEnd
                    if (marker == 0xDA) {
                        end = scanEntropyData(b, payloadEnd)
                    }
                    val seg = Segment(marker, pos, end, payloadStart, payloadEnd)
                    seg.kind = classify(b, seg)
                    segments += seg
                    pos = end
                    if (end >= b.size) break
                }
            }
        }
        return Parsed(segments, b.size, hasEoi = false)
    }

    /** Returns the index of the first marker (0xFF followed by a non-stuffing, non-RST byte) after [from]. */
    private fun scanEntropyData(b: ByteArray, from: Int): Int {
        var p = from
        val last = b.size - 1
        while (p < last) {
            if (b[p] == 0xFF.toByte()) {
                val n = b[p + 1].toInt() and 0xFF
                if (n == 0x00 || n in 0xD0..0xD7) { p += 2; continue }
                if (n == 0xFF) { p += 1; continue }
                return p
            }
            p++
        }
        return b.size
    }

    private fun classify(b: ByteArray, s: Segment): Kind {
        val p = s.payloadStart
        return when (s.marker) {
            0xE0 -> when {
                b.startsWithAt(p, JFIF_ID) -> Kind.JFIF
                b.startsWithAt(p, JFXX_ID) -> Kind.THUMBNAIL
                else -> Kind.OTHER_APP
            }
            0xE1 -> when {
                b.startsWithAt(p, EXIF_ID) -> Kind.EXIF
                b.startsWithAt(p, XMP_ID) || b.startsWithAt(p, XMP_EXT_ID) -> Kind.XMP
                else -> Kind.OTHER_APP
            }
            0xE2 -> when {
                b.startsWithAt(p, ICC_ID) -> Kind.ICC
                b.startsWithAt(p, MPF_ID) -> Kind.MPF
                else -> Kind.OTHER_APP
            }
            0xEB -> Kind.JUMBF
            0xED -> if (b.startsWithAt(p, PHOTOSHOP_ID)) Kind.PHOTOSHOP else Kind.OTHER_APP
            0xEE -> if (b.startsWithAt(p, ADOBE_ID) && s.payloadEnd - p >= ADOBE_LEN) Kind.ADOBE else Kind.OTHER_APP
            in 0xE3..0xEF -> Kind.OTHER_APP
            0xFE -> Kind.COMMENT
            else -> Kind.CORE
        }
    }

    private fun Segment.payload(b: ByteArray) = b.copyOfRange(payloadStart, payloadEnd)

    private fun Segment.location(): String = when (kind) {
        Kind.COMMENT -> "COM"
        else -> if (marker in 0xE0..0xEF) "APP${marker - 0xE0}" else "0x${marker.toString(16)}"
    }

    fun scan(b: ByteArray): ScanReport {
        val collector = FindingCollector()
        val parsed = try {
            parse(b)
        } catch (e: MalformedMediaException) {
            RawScanner.scanInto(b, collector)
            return collector.build(MediaFormat.JPEG, parseError = e.message)
        }
        for (s in parsed.segments) {
            val type = when (s.kind) {
                Kind.EXIF -> BlockType.EXIF
                Kind.XMP -> BlockType.XMP
                Kind.ICC -> BlockType.ICC
                Kind.MPF -> BlockType.EXTRA_IMAGE
                Kind.JUMBF -> BlockType.C2PA
                Kind.PHOTOSHOP -> BlockType.IPTC
                Kind.COMMENT -> BlockType.COMMENT
                Kind.THUMBNAIL -> BlockType.THUMBNAIL
                Kind.OTHER_APP -> BlockType.UNKNOWN
                else -> null
            } ?: continue
            val payload = s.payload(b)
            val location = s.location() + when (s.kind) {
                Kind.EXIF -> " (EXIF)"
                Kind.XMP -> " (XMP)"
                Kind.JUMBF -> " (JUMBF)"
                Kind.PHOTOSHOP -> " (IPTC)"
                Kind.MPF -> " (MPF)"
                else -> ""
            }
            collector.analyze(MetaBlock(type, location, payload))
        }
        if (parsed.segments.any { it.kind == Kind.JFIF && jfifHasThumbnail(b, it) }) {
            collector.add(FindingKind.THUMBNAIL, "APP0 (JFIF)")
        }
        if (parsed.imageEnd < b.size) {
            collector.analyze(MetaBlock(BlockType.TRAILER, "После EOI", b.copyOfRange(parsed.imageEnd, b.size)))
        }
        return collector.build(MediaFormat.JPEG)
    }

    private fun jfifHasThumbnail(b: ByteArray, s: Segment): Boolean =
        s.payloadEnd - s.payloadStart >= 14 && (b.u8(s.payloadStart + 12) != 0 || b.u8(s.payloadStart + 13) != 0)

    fun strip(b: ByteArray, options: StripOptions): StripOutcome {
        val parsed = try {
            parse(b)
        } catch (e: MalformedMediaException) {
            return StripOutcome.Unsupported("JPEG: ${e.message}")
        }
        if (parsed.segments.none { it.marker == 0xDA }) return StripOutcome.Unsupported("JPEG без данных изображения")

        val removed = mutableListOf<RemovedItem>()
        val notes = mutableListOf<String>()
        val orientation = parsed.segments.firstOrNull { it.kind == Kind.EXIF }
            ?.let { ExifReader.readPayload(it.payload(b)).orientation } ?: 1

        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var jfifWritten = false
        var exifWritten = !MinimalExif.needed(orientation, options)
        fun writeExifIfNeeded() {
            if (exifWritten) return
            val payload = MinimalExif.jpegPayload(if (options.keepOrientation) orientation else 1, options.captureDate)
            out.write(0xFF); out.write(0xE1); out.u16be(payload.size + 2); out.write(payload)
            exifWritten = true
        }

        for (s in parsed.segments.drop(1)) {
            when (s.kind) {
                Kind.JFIF -> {
                    if (jfifWritten) {
                        removed += RemovedItem(BlockType.UNKNOWN, "APP0 (JFIF, дубликат)", (s.end - s.start).toLong())
                        continue
                    }
                    jfifWritten = true
                    val len = s.payloadEnd - s.payloadStart
                    if (jfifHasThumbnail(b, s)) {
                        // Rewrite as a thumbnail-less JFIF header (also fixes headers that declare a missing thumbnail).
                        out.write(0xFF); out.write(0xE0); out.u16be(16)
                        out.writeRange(b, s.payloadStart, s.payloadStart + 12)
                        out.write(0); out.write(0)
                        removed += RemovedItem(BlockType.THUMBNAIL, "APP0 (JFIF)", (len - 14).toLong())
                    } else {
                        out.writeRange(b, s.start, s.end)
                    }
                    writeExifIfNeeded()
                }
                Kind.ICC -> if (options.keepColorProfile) {
                    writeExifIfNeeded(); out.writeRange(b, s.start, s.end)
                } else {
                    removed += RemovedItem(BlockType.ICC, "APP2 (ICC)", (s.end - s.start).toLong())
                }
                Kind.ADOBE -> {
                    writeExifIfNeeded()
                    out.write(0xFF); out.write(0xEE); out.u16be(ADOBE_LEN + 2)
                    out.writeRange(b, s.payloadStart, s.payloadStart + ADOBE_LEN)
                    val extra = s.payloadEnd - s.payloadStart - ADOBE_LEN
                    if (extra > 0) removed += RemovedItem(BlockType.UNKNOWN, "APP14 (Adobe, доп. данные)", extra.toLong())
                }
                Kind.CORE, Kind.EOI -> {
                    writeExifIfNeeded(); out.writeRange(b, s.start, s.end)
                }
                Kind.SOI -> Unit
                else -> removed += RemovedItem(s.kind.toBlockType(), s.location(), (s.end - s.start).toLong())
            }
        }
        if (!parsed.hasEoi) {
            out.write(0xFF); out.write(0xD9)
            notes += "Файл был обрезан — добавлен маркер конца изображения"
        }
        if (parsed.imageEnd < b.size) {
            removed += RemovedItem(BlockType.TRAILER, "После EOI", (b.size - parsed.imageEnd).toLong())
            if (parsed.segments.any { it.kind == Kind.MPF }) {
                notes += "Удалены дополнительные кадры (HDR gain map / глубина) — фото станет SDR"
            }
        }
        if (orientation in 2..8 && options.keepOrientation) notes += "Сохранена ориентация (EXIF Orientation=$orientation)"
        options.captureDate?.let { notes += "Сохранена дата съёмки ${it.local}" }
        return StripOutcome.Stripped(out.toByteArray(), removed, notes)
    }

    private fun Kind.toBlockType() = when (this) {
        Kind.EXIF -> BlockType.EXIF
        Kind.XMP -> BlockType.XMP
        Kind.ICC -> BlockType.ICC
        Kind.MPF -> BlockType.EXTRA_IMAGE
        Kind.JUMBF -> BlockType.C2PA
        Kind.PHOTOSHOP -> BlockType.IPTC
        Kind.COMMENT -> BlockType.COMMENT
        Kind.THUMBNAIL -> BlockType.THUMBNAIL
        else -> BlockType.UNKNOWN
    }
}
