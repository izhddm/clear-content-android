package com.clearcontent.core.media

import java.io.ByteArrayOutputStream

/** RIFF/WebP chunk surgery. VP8X flags and the RIFF size are rewritten to match the kept chunks. */
internal object WebpCodec {
    private val KEEP = setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ANIM", "ANMF")

    private const val FLAG_ICC = 0x20
    private const val FLAG_ALPHA = 0x10
    private const val FLAG_EXIF = 0x08
    private const val FLAG_XMP = 0x04
    private const val FLAG_ANIM = 0x02

    private class Chunk(val fourcc: String, val start: Int, val dataStart: Int, val dataEnd: Int, val end: Int)

    private class Parsed(val chunks: List<Chunk>, val riffEnd: Int)

    private fun parse(b: ByteArray): Parsed {
        if (b.size < 20 || b.ascii(0, 4) != "RIFF" || b.ascii(8, 4) != "WEBP") throw MalformedMediaException("Not a WebP")
        val declared = b.u32le(4) + 8
        val riffEnd = if (declared in 20..b.size.toLong()) declared.toInt() else b.size
        val chunks = mutableListOf<Chunk>()
        var pos = 12
        while (pos + 8 <= riffEnd) {
            val fourcc = b.ascii(pos, 4)
            val len = b.u32le(pos + 4)
            val dataStart = pos + 8
            val dataEnd = dataStart + len
            if (dataEnd > b.size) throw MalformedMediaException("Truncated chunk $fourcc")
            val end = minOf(dataEnd + (len and 1L), b.size.toLong()).toInt()
            chunks += Chunk(fourcc, pos, dataStart, dataEnd.toInt(), end)
            pos = end
        }
        if (chunks.none { it.fourcc == "VP8 " || it.fourcc == "VP8L" || it.fourcc == "ANMF" }) {
            throw MalformedMediaException("WebP without image data")
        }
        return Parsed(chunks, maxOf(pos, riffEnd).coerceAtMost(b.size))
    }

    fun scan(b: ByteArray): ScanReport {
        val collector = FindingCollector()
        val parsed = try {
            parse(b)
        } catch (e: MalformedMediaException) {
            RawScanner.scanInto(b, collector)
            return collector.build(MediaFormat.WEBP, parseError = e.message)
        }
        var flags = 0
        for (c in parsed.chunks) {
            val data = b.copyOfRange(c.dataStart, c.dataEnd)
            when (c.fourcc) {
                "VP8X" -> flags = data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                "EXIF" -> collector.analyze(MetaBlock(BlockType.EXIF, "EXIF", data))
                "XMP " -> collector.analyze(MetaBlock(BlockType.XMP, "XMP", data))
                "ICCP" -> collector.analyze(MetaBlock(BlockType.ICC, "ICCP", data))
                "C2PA" -> collector.analyze(MetaBlock(BlockType.C2PA, "C2PA", data))
                in KEEP -> Unit
                else -> collector.analyze(MetaBlock(BlockType.UNKNOWN, c.fourcc.trim(), data))
            }
        }
        if (parsed.riffEnd < b.size) {
            collector.analyze(MetaBlock(BlockType.TRAILER, "После RIFF", b.copyOfRange(parsed.riffEnd, b.size)))
        }
        val traits = ImageTraits(
            animated = flags and FLAG_ANIM != 0 || parsed.chunks.any { it.fourcc == "ANMF" },
            hasAlpha = flags and FLAG_ALPHA != 0 || parsed.chunks.any { it.fourcc == "ALPH" } || isVp8lWithAlpha(b, parsed),
            lossless = parsed.chunks.any { it.fourcc == "VP8L" } && parsed.chunks.none { it.fourcc == "VP8 " },
        )
        return collector.build(MediaFormat.WEBP, traits)
    }

    private fun isVp8lWithAlpha(b: ByteArray, parsed: Parsed): Boolean {
        val c = parsed.chunks.firstOrNull { it.fourcc == "VP8L" } ?: return false
        if (c.dataEnd - c.dataStart < 5) return false
        // Byte 0 is the 0x2F signature; bit 28 of the following 32-bit field is alpha_is_used.
        val bits = b.u32le(c.dataStart + 1)
        return (bits ushr 28) and 1L == 1L
    }

    fun strip(b: ByteArray, options: StripOptions): StripOutcome {
        val parsed = try {
            parse(b)
        } catch (e: MalformedMediaException) {
            return StripOutcome.Unsupported("WebP: ${e.message}")
        }
        val removed = mutableListOf<RemovedItem>()
        val notes = mutableListOf<String>()
        val hasVp8x = parsed.chunks.firstOrNull()?.fourcc == "VP8X"
        val orientation = parsed.chunks.firstOrNull { it.fourcc == "EXIF" }
            ?.let { ExifReader.readPayload(b.copyOfRange(it.dataStart, it.dataEnd)).orientation } ?: 1
        val writeExif = hasVp8x && MinimalExif.needed(orientation, options)
        val keptOrientation = if (options.keepOrientation) orientation else 1
        val keepIcc = options.keepColorProfile && hasVp8x

        val body = ByteArrayOutputStream(b.size)
        for (c in parsed.chunks) {
            val keep = when (c.fourcc) {
                in KEEP -> hasVp8x || c.fourcc != "VP8X"
                "ICCP" -> keepIcc
                else -> false
            }
            if (!keep) {
                removed += RemovedItem(chunkType(c.fourcc), c.fourcc.trim(), (c.end - c.start).toLong())
                continue
            }
            if (c.fourcc == "VP8X") {
                val chunk = b.copyOfRange(c.start, c.end)
                var flags = chunk[8].toInt() and 0xFF
                flags = flags and (FLAG_ICC or FLAG_EXIF or FLAG_XMP).inv()
                if (keepIcc && parsed.chunks.any { it.fourcc == "ICCP" }) flags = flags or FLAG_ICC
                if (writeExif) flags = flags or FLAG_EXIF
                chunk[8] = flags.toByte()
                body.write(chunk)
            } else {
                body.writeRange(b, c.start, c.end)
                // A chunk whose odd length reached EOF without padding gets its pad byte back.
                if ((c.end - c.dataStart) % 2 == 1) body.write(0)
            }
        }
        if (writeExif) {
            val tiff = MinimalExif.tiff(keptOrientation, options.captureDate)
            body.write("EXIF".latin1())
            body.u32le(tiff.size.toLong())
            body.write(tiff)
            if (tiff.size % 2 == 1) body.write(0)
            if (keptOrientation in 2..8) notes += "Сохранена ориентация (EXIF Orientation=$orientation)"
        }
        if (parsed.riffEnd < b.size) {
            removed += RemovedItem(BlockType.TRAILER, "После RIFF", (b.size - parsed.riffEnd).toLong())
        }
        val out = ByteArrayOutputStream(body.size() + 12)
        out.write("RIFF".latin1())
        out.u32le((body.size() + 4).toLong())
        out.write("WEBP".latin1())
        body.writeTo(out)
        return StripOutcome.Stripped(out.toByteArray(), removed, notes)
    }

    private fun chunkType(fourcc: String) = when (fourcc) {
        "EXIF" -> BlockType.EXIF
        "XMP " -> BlockType.XMP
        "ICCP" -> BlockType.ICC
        "C2PA" -> BlockType.C2PA
        else -> BlockType.UNKNOWN
    }
}
