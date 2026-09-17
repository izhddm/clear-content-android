package com.clearcontent.core.media

import java.io.ByteArrayOutputStream

/** GIF block surgery: keeps frames, graphic control and looping; drops comments and foreign app extensions. */
internal object GifCodec {
    private val KEEP_APPS = setOf("NETSCAPE2.0", "ANIMEXTS1.0")

    private enum class Kind { HEADER, IMAGE, GRAPHIC_CONTROL, PLAIN_TEXT, LOOP, COMMENT, XMP, ICC, APP, UNKNOWN_EXT, TRAILER }

    private class Block(val kind: Kind, val start: Int, val end: Int, val label: String)

    private class Parsed(val blocks: List<Block>, val end: Int, val hasTrailer: Boolean, val frames: Int)

    private fun skipSubBlocks(b: ByteArray, from: Int): Int {
        var p = from
        while (true) {
            val len = b.u8(p)
            p += 1
            if (len == 0) return p
            p += len
            if (p > b.size) throw MalformedMediaException("Truncated GIF sub-block")
        }
    }

    private fun parse(b: ByteArray): Parsed {
        if (b.size < 13 || !(b.ascii(0, 6) == "GIF87a" || b.ascii(0, 6) == "GIF89a")) throw MalformedMediaException("Not a GIF")
        val packed = b.u8(10)
        var pos = 13
        if (packed and 0x80 != 0) pos += 3 * (1 shl ((packed and 7) + 1))
        if (pos > b.size) throw MalformedMediaException("Truncated color table")
        val blocks = mutableListOf(Block(Kind.HEADER, 0, pos, "Header"))
        var frames = 0
        while (pos < b.size) {
            val start = pos
            when (b.u8(pos)) {
                0x2C -> {
                    val localPacked = b.u8(pos + 9)
                    pos += 10
                    if (localPacked and 0x80 != 0) pos += 3 * (1 shl ((localPacked and 7) + 1))
                    pos += 1 // LZW minimum code size
                    pos = skipSubBlocks(b, pos)
                    blocks += Block(Kind.IMAGE, start, pos, "Image")
                    frames++
                }
                0x21 -> {
                    val label = b.u8(pos + 1)
                    pos += 2
                    var name = "0x" + label.toString(16)
                    val kind = when (label) {
                        0xF9 -> Kind.GRAPHIC_CONTROL
                        0x01 -> Kind.PLAIN_TEXT
                        0xFE -> Kind.COMMENT.also { name = "Comment" }
                        0xFF -> {
                            val len = b.u8(pos)
                            name = b.ascii(pos + 1, minOf(len, 11))
                            when {
                                name in KEEP_APPS -> Kind.LOOP
                                name.startsWith("XMP Data") -> Kind.XMP
                                name.startsWith("ICCRGBG1") -> Kind.ICC
                                else -> Kind.APP
                            }
                        }
                        else -> Kind.UNKNOWN_EXT
                    }
                    pos = skipSubBlocks(b, pos)
                    blocks += Block(kind, start, pos, name)
                }
                0x3B -> {
                    blocks += Block(Kind.TRAILER, start, pos + 1, "Trailer")
                    return Parsed(blocks, pos + 1, true, frames)
                }
                else -> throw MalformedMediaException("Unknown GIF block at $pos")
            }
        }
        return Parsed(blocks, b.size, false, frames)
    }

    fun scan(b: ByteArray): ScanReport {
        val collector = FindingCollector()
        val parsed = try {
            parse(b)
        } catch (e: MalformedMediaException) {
            RawScanner.scanInto(b, collector)
            return collector.build(MediaFormat.GIF, parseError = e.message)
        }
        for (blk in parsed.blocks) {
            val raw = b.copyOfRange(blk.start, blk.end)
            val type = when (blk.kind) {
                Kind.COMMENT -> BlockType.COMMENT
                Kind.XMP -> BlockType.XMP
                Kind.ICC -> BlockType.ICC
                Kind.APP, Kind.UNKNOWN_EXT -> if (containsJumbf(raw)) BlockType.C2PA else BlockType.UNKNOWN
                else -> null
            } ?: continue
            val payload = if (blk.kind == Kind.COMMENT) subBlockData(raw, 2) else raw
            collector.analyze(MetaBlock(type, "Ext:${blk.label}", payload))
        }
        if (parsed.end < b.size) {
            collector.analyze(MetaBlock(BlockType.TRAILER, "После трейлера", b.copyOfRange(parsed.end, b.size)))
        }
        return collector.build(MediaFormat.GIF, ImageTraits(animated = parsed.frames > 1, lossless = true))
    }

    private fun subBlockData(raw: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var p = from
        while (p < raw.size) {
            val len = raw[p].toInt() and 0xFF
            if (len == 0) break
            out.write(raw, p + 1, minOf(len, raw.size - p - 1))
            p += len + 1
        }
        return out.toByteArray()
    }

    fun strip(b: ByteArray, options: StripOptions): StripOutcome {
        val parsed = try {
            parse(b)
        } catch (e: MalformedMediaException) {
            return StripOutcome.Unsupported("GIF: ${e.message}")
        }
        if (parsed.frames == 0) return StripOutcome.Unsupported("GIF без кадров")
        val removed = mutableListOf<RemovedItem>()
        val notes = mutableListOf<String>()
        val out = ByteArrayOutputStream(b.size)
        for (blk in parsed.blocks) {
            val keep = when (blk.kind) {
                Kind.HEADER, Kind.IMAGE, Kind.GRAPHIC_CONTROL, Kind.PLAIN_TEXT, Kind.LOOP, Kind.TRAILER -> true
                Kind.ICC -> options.keepColorProfile
                else -> false
            }
            if (keep) {
                out.writeRange(b, blk.start, blk.end)
            } else {
                val type = when (blk.kind) {
                    Kind.COMMENT -> BlockType.COMMENT
                    Kind.XMP -> BlockType.XMP
                    Kind.ICC -> BlockType.ICC
                    else -> BlockType.UNKNOWN
                }
                removed += RemovedItem(type, "Ext:${blk.label}", (blk.end - blk.start).toLong())
            }
        }
        if (!parsed.hasTrailer) {
            out.write(0x3B)
            notes += "Файл был обрезан — добавлен завершающий блок"
        }
        if (parsed.end < b.size) {
            removed += RemovedItem(BlockType.TRAILER, "После трейлера", (b.size - parsed.end).toLong())
        }
        return StripOutcome.Stripped(out.toByteArray(), removed, notes)
    }
}
