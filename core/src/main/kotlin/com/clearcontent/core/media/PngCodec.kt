package com.clearcontent.core.media

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** PNG chunk surgery with a whitelist of rendering-relevant chunks. */
internal object PngCodec {
    private val SIGNATURE = "\u0089PNG\r\n\u001A\n".latin1()

    /** Chunks that affect decoding or display and carry no free-form data. */
    private val KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "sBIT", "sRGB", "bKGD", "hIST", "pHYs",
        "acTL", "fcTL", "fdAT", "cICP", "mDCV", "cLLI", "mDCv", "cLLi",
    )

    private class Chunk(val type: String, val start: Int, val dataStart: Int, val dataEnd: Int) {
        val end get() = dataEnd + 4
    }

    private class Parsed(val chunks: List<Chunk>, val imageEnd: Int)

    private fun parse(b: ByteArray): Parsed {
        if (!b.startsWithAt(0, SIGNATURE)) throw MalformedMediaException("Not a PNG")
        val chunks = mutableListOf<Chunk>()
        var pos = 8
        while (true) {
            if (pos + 12 > b.size) throw MalformedMediaException("Truncated PNG (no IEND)")
            val len = b.u32be(pos)
            if (len > Int.MAX_VALUE - 12) throw MalformedMediaException("Chunk too large")
            val type = b.ascii(pos + 4, 4)
            if (!type.all { it.isLetter() }) throw MalformedMediaException("Bad chunk type")
            val chunk = Chunk(type, pos, pos + 8, pos + 8 + len.toInt())
            if (chunk.end > b.size) throw MalformedMediaException("Truncated chunk $type")
            chunks += chunk
            pos = chunk.end
            if (type == "IEND") return Parsed(chunks, pos)
        }
    }

    private fun Chunk.isAncillary() = type[0].isLowerCase()

    fun scan(b: ByteArray): ScanReport {
        val collector = FindingCollector()
        val parsed = try {
            parse(b)
        } catch (e: MalformedMediaException) {
            RawScanner.scanInto(b, collector)
            return collector.build(MediaFormat.PNG, parseError = e.message)
        }
        var animated = false
        var alpha = false
        for (c in parsed.chunks) {
            val data = b.copyOfRange(c.dataStart, c.dataEnd)
            when (c.type) {
                "IHDR" -> if (data.size >= 10) alpha = data[9].toInt() == 4 || data[9].toInt() == 6
                "tRNS" -> alpha = true
                "acTL" -> animated = true
                "tEXt", "zTXt", "iTXt" -> collector.analyze(textBlock(c.type, data))
                "eXIf" -> collector.analyze(MetaBlock(BlockType.EXIF, "eXIf", data))
                "iCCP" -> collector.analyze(MetaBlock(BlockType.ICC, "iCCP", data))
                "caBX" -> collector.analyze(MetaBlock(BlockType.C2PA, "caBX (C2PA)", data))
                "tIME" -> collector.analyze(MetaBlock(BlockType.TIMESTAMP, "tIME", data))
                in KEEP -> Unit
                else -> collector.analyze(MetaBlock(BlockType.UNKNOWN, c.type, data))
            }
        }
        if (parsed.imageEnd < b.size) {
            collector.analyze(MetaBlock(BlockType.TRAILER, "После IEND", b.copyOfRange(parsed.imageEnd, b.size)))
        }
        return collector.build(MediaFormat.PNG, ImageTraits(animated = animated, hasAlpha = alpha, lossless = true))
    }

    /** Decodes tEXt / zTXt / iTXt into a text block; XMP stored in iTXt is reported as XMP. */
    private fun textBlock(type: String, data: ByteArray): MetaBlock {
        val nul = data.indexOf(byteArrayOf(0))
        if (nul < 0) return MetaBlock(BlockType.TEXT, type, data)
        val key = String(data, 0, nul, Charsets.ISO_8859_1)
        val text: ByteArray = when (type) {
            "tEXt" -> data.copyOfRange(nul + 1, data.size)
            "zTXt" -> inflate(data, nul + 2) ?: ByteArray(0)
            else -> {
                // keyword \0 compressionFlag compressionMethod language \0 translatedKeyword \0 text
                val flag = data.getOrNull(nul + 1)?.toInt() ?: 0
                var p = nul + 3
                repeat(2) {
                    val z = data.indexOf(byteArrayOf(0), p)
                    p = if (z < 0) data.size else z + 1
                }
                if (flag == 1) inflate(data, p) ?: ByteArray(0) else data.copyOfRange(minOf(p, data.size), data.size)
            }
        }
        val isXmp = key == "XML:com.adobe.xmp"
        return MetaBlock(if (isXmp) BlockType.XMP else BlockType.TEXT, "$type:$key", text, key)
    }

    fun strip(b: ByteArray, options: StripOptions): StripOutcome {
        val parsed = try {
            parse(b)
        } catch (e: MalformedMediaException) {
            return StripOutcome.Unsupported("PNG: ${e.message}")
        }
        val unknownCritical = parsed.chunks.firstOrNull { !it.isAncillary() && it.type !in KEEP }
        if (unknownCritical != null) return StripOutcome.Unsupported("PNG: неизвестный критичный блок ${unknownCritical.type}")

        val orientation = parsed.chunks.firstOrNull { it.type == "eXIf" }
            ?.let { ExifReader.readPayload(b.copyOfRange(it.dataStart, it.dataEnd)).orientation } ?: 1
        val removed = mutableListOf<RemovedItem>()
        val notes = mutableListOf<String>()
        val out = ByteArrayOutputStream(b.size)
        out.write(SIGNATURE)
        for (c in parsed.chunks) {
            val keep = c.type in KEEP || (c.type == "iCCP" && options.keepColorProfile)
            if (keep) {
                out.writeRange(b, c.start, c.end)
            } else {
                removed += RemovedItem(chunkType(c.type), c.type, (c.end - c.start).toLong())
            }
            if (c.type == "IHDR" && MinimalExif.needed(orientation, options)) {
                val kept = if (options.keepOrientation) orientation else 1
                writeChunk(out, "eXIf", MinimalExif.tiff(kept, options.captureDate))
                if (kept in 2..8) notes += "Сохранена ориентация (EXIF Orientation=$orientation)"
            }
        }
        if (parsed.imageEnd < b.size) {
            removed += RemovedItem(BlockType.TRAILER, "После IEND", (b.size - parsed.imageEnd).toLong())
        }
        return StripOutcome.Stripped(out.toByteArray(), removed, notes)
    }

    private fun chunkType(type: String) = when (type) {
        "tEXt", "zTXt" -> BlockType.TEXT
        "iTXt" -> BlockType.TEXT
        "eXIf" -> BlockType.EXIF
        "iCCP" -> BlockType.ICC
        "caBX" -> BlockType.C2PA
        "tIME" -> BlockType.TIMESTAMP
        else -> BlockType.UNKNOWN
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.latin1()
        out.u32be(data.size.toLong())
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.u32be(crc.value)
    }
}
