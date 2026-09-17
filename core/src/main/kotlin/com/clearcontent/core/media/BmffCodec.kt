package com.clearcontent.core.media

import java.io.ByteArrayOutputStream
import java.io.OutputStream

class UnsupportedMediaException(message: String) : Exception(message)

internal class Box(val type: String, val start: Long, val headerSize: Int, val size: Long, val userType: ByteArray?) {
    val end: Long get() = start + size
    val dataStart: Long get() = start + headerSize
    val dataSize: Long get() = size - headerSize
}

/** ISO Base Media File Format (MP4, MOV, HEIF, AVIF) reading, scanning and lossless metadata removal. */
internal object BmffCodec {
    private const val MAX_MOOV = 128L shl 20
    private const val MAX_BLOCK = 4 shl 20

    /** Real files nest moov/trak/mdia/minf/stbl five levels deep; anything far deeper is hostile. */
    private const val MAX_DEPTH = 12

    private val TOP_LEVEL_KEEP = setOf("ftyp", "moov", "mdat")
    private val TOP_LEVEL_SILENT = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "pdin", "styp", "sidx", "moof", "mfra", "emsg", "prft")
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")
    private val MOOV_DROP = setOf("udta", "meta", "free", "skip", "wide", "Xtra", "pnot")
    private val FRAGMENT_MARKERS = setOf("moof", "mvex", "mfra", "sidx", "styp")

    /** Google Spherical Video V1 metadata, which players need to render 360° video. */
    private val SPHERICAL_UUID = byteArrayOf(
        0xFF.toByte(), 0xCC.toByte(), 0x82.toByte(), 0x63, 0xF8.toByte(), 0x55, 0x4A, 0x93.toByte(),
        0x88.toByte(), 0x14, 0x58, 0x7A, 0x02, 0x52, 0x1F, 0xDD.toByte(),
    )

    fun readBoxes(src: ByteSource, from: Long, to: Long, tolerateTruncatedTail: Boolean = false): List<Box> {
        val boxes = mutableListOf<Box>()
        val hdr = ByteArray(16)
        var pos = from
        while (pos + 8 <= to) {
            src.readFully(pos, hdr, 0, 8)
            var size = hdr.u32be(0)
            val type = hdr.ascii(4, 4)
            var header = 8
            when (size) {
                1L -> {
                    if (pos + 16 > to) throw MalformedMediaException("Truncated largesize header")
                    src.readFully(pos + 8, hdr, 8, 8)
                    size = hdr.u64be(8)
                    header = 16
                }
                0L -> size = to - pos
            }
            var userType: ByteArray? = null
            if (type == "uuid") {
                if (pos + header + 16 > to) throw MalformedMediaException("Truncated uuid box")
                userType = src.read(pos + header, 16)
                header += 16
            }
            if (size < header) throw MalformedMediaException("Bad box '$type' at $pos")
            if (pos + size > to) {
                if (tolerateTruncatedTail && type == "mdat") size = to - pos
                else throw MalformedMediaException("Box '$type' at $pos exceeds its parent")
            }
            boxes += Box(type, pos, header, size, userType)
            pos += size
        }
        return boxes
    }

    private fun children(buf: ByteArray, from: Int, to: Int): List<Box> =
        readBoxes(ByteArraySource(buf), from.toLong(), to.toLong())

    // ---------------------------------------------------------------- scanning

    fun scan(src: ByteSource, format: MediaFormat): ScanReport {
        val c = FindingCollector()
        val top = try {
            readBoxes(src, 0, src.size, tolerateTruncatedTail = true)
        } catch (e: MalformedMediaException) {
            if (src.size <= MAX_MOOV) RawScanner.scanInto(src.read(0, src.size.toInt()), c)
            return c.build(format, parseError = e.message)
        }
        for (box in top) {
            when (box.type) {
                "uuid" -> {
                    val payload = src.read(box.dataStart, minOf(box.dataSize, MAX_BLOCK.toLong()).toInt())
                    val ut = box.userType!!
                    when {
                        ut.contentEquals(AiSignatures.C2PA_UUID) -> c.analyze(MetaBlock(BlockType.C2PA, "uuid (C2PA)", payload))
                        ut.contentEquals(AiSignatures.XMP_UUID) -> c.analyze(MetaBlock(BlockType.XMP, "uuid (XMP)", payload))
                        else -> c.analyze(MetaBlock(BlockType.UNKNOWN, "uuid", payload))
                    }
                }
                "moov" -> if (box.size <= MAX_MOOV) scanMoov(src.read(box.start, box.size.toInt()), c)
                "meta" -> {
                    val data = src.read(box.start, minOf(box.size, MAX_MOOV).toInt())
                    if (format.kind == MediaKind.VIDEO) {
                        c.analyze(MetaBlock(BlockType.CONTAINER_META, "meta", data))
                    } else {
                        c.analyzeText(extractStrings(data), "meta")
                        if (containsJumbf(data)) c.add(FindingKind.C2PA, "meta", "JUMBF-элемент")
                    }
                }
                "free", "skip" -> if (box.dataSize in 1..MAX_BLOCK.toLong()) {
                    val data = src.read(box.dataStart, box.dataSize.toInt())
                    if (data.any { it.toInt() != 0 }) {
                        val text = extractStrings(data)
                        if (text.isNotBlank()) c.analyze(MetaBlock(BlockType.UNKNOWN, box.type, data))
                    }
                }
                in TOP_LEVEL_SILENT -> Unit
                else -> {
                    val data = src.read(box.dataStart, minOf(box.dataSize, 64L * 1024).toInt())
                    c.analyze(MetaBlock(BlockType.UNKNOWN, box.type, data))
                }
            }
        }
        if (format.kind == MediaKind.IMAGE && src.size <= (256L shl 20)) {
            // HEIF/AVIF keep Exif/XMP as items inside mdat/idat: locate them heuristically.
            RawScanner.scanInto(src.read(0, src.size.toInt()), c)
        }
        return c.build(format)
    }

    private fun scanMoov(moov: ByteArray, c: FindingCollector) {
        val root = try {
            children(moov, 0, moov.size).firstOrNull() ?: return
        } catch (_: MalformedMediaException) {
            c.analyzeText(extractStrings(moov), "moov")
            return
        }
        walk(moov, root, "moov", c, 0)
    }

    private fun walk(buf: ByteArray, box: Box, path: String, c: FindingCollector, depth: Int) {
        if (depth > MAX_DEPTH) {
            c.add(FindingKind.UNKNOWN_BLOCK, path, "Слишком глубокая вложенность")
            return
        }
        val kids = try {
            children(buf, box.dataStart.toInt(), box.end.toInt())
        } catch (_: MalformedMediaException) {
            return
        }
        if (box.type == "trak") {
            val handler = handlerType(buf, kids)
            if (handler == "meta" || handler == "mdta") c.add(FindingKind.METADATA_TRACK, path, "Дорожка метаданных")
        }
        for (k in kids) {
            val childPath = "$path/${k.type}"
            val payload = { buf.copyOfRange(k.dataStart.toInt(), minOf(k.end, k.dataStart + MAX_BLOCK).toInt()) }
            when {
                k.type == "udta" || k.type == "meta" -> c.analyze(MetaBlock(BlockType.CONTAINER_META, childPath, payload()))
                k.type == "uuid" -> {
                    val ut = k.userType!!
                    when {
                        ut.contentEquals(SPHERICAL_UUID) -> Unit
                        ut.contentEquals(AiSignatures.C2PA_UUID) -> c.analyze(MetaBlock(BlockType.C2PA, "$childPath (C2PA)", payload()))
                        ut.contentEquals(AiSignatures.XMP_UUID) -> c.analyze(MetaBlock(BlockType.XMP, "$childPath (XMP)", payload()))
                        else -> c.analyze(MetaBlock(BlockType.UNKNOWN, childPath, payload()))
                    }
                }
                k.type in CONTAINERS -> walk(buf, k, childPath, c, depth + 1)
                k.type[0] == '?' -> c.analyze(MetaBlock(BlockType.CONTAINER_META, childPath, payload()))
            }
        }
    }

    private fun handlerType(buf: ByteArray, trakKids: List<Box>): String? {
        val mdia = trakKids.firstOrNull { it.type == "mdia" } ?: return null
        val hdlr = try {
            children(buf, mdia.dataStart.toInt(), mdia.end.toInt()).firstOrNull { it.type == "hdlr" }
        } catch (_: MalformedMediaException) {
            null
        } ?: return null
        return buf.ascii(hdlr.dataStart.toInt() + 8, 4)
    }

    // ---------------------------------------------------------------- stripping

    private class Patch(val position: Int, val wide: Boolean, val count: Int)

    private class PatchableOutput(size: Int) : ByteArrayOutputStream(size) {
        fun setU32(pos: Int, v: Long) = buf.putU32be(pos, v)
        fun rawBuffer(): ByteArray = buf
    }

    /**
     * Writes [src] without top-level uuid/meta/free boxes and without moov-level udta/meta,
     * re-pointing chunk offsets so the media samples are copied untouched.
     */
    fun stripVideo(src: ByteSource, out: OutputStream): VideoStripResult {
        val top = try {
            readBoxes(src, 0, src.size, tolerateTruncatedTail = true)
        } catch (e: MalformedMediaException) {
            throw UnsupportedMediaException("Повреждённый контейнер: ${e.message}")
        }
        if (top.any { it.type in FRAGMENT_MARKERS }) throw UnsupportedMediaException("Фрагментированный MP4")
        val moovBox = top.singleOrNull { it.type == "moov" } ?: throw UnsupportedMediaException("Нет блока moov")
        if (moovBox.size > MAX_MOOV) throw UnsupportedMediaException("Слишком большой moov")
        val mdats = top.filter { it.type == "mdat" }
        if (mdats.isEmpty()) throw UnsupportedMediaException("Нет данных (mdat)")

        val removed = mutableListOf<RemovedItem>()
        for (b in top) {
            if (b.type in TOP_LEVEL_KEEP) continue
            val type = when {
                b.userType?.contentEquals(AiSignatures.C2PA_UUID) == true -> BlockType.C2PA
                b.userType?.contentEquals(AiSignatures.XMP_UUID) == true -> BlockType.XMP
                b.type == "meta" || b.type == "udta" -> BlockType.CONTAINER_META
                else -> BlockType.UNKNOWN
            }
            removed += RemovedItem(type, b.type, b.size)
        }
        val kept = top.filter { it.type in TOP_LEVEL_KEEP }

        val moov = src.read(moovBox.start, moovBox.size.toInt())
        val newMoov = PatchableOutput(moov.size)
        val patches = mutableListOf<Patch>()
        try {
            val root = children(moov, 0, moov.size).single()
            rewriteContainer(moov, root, "moov", newMoov, patches, removed, 0)
        } catch (e: MalformedMediaException) {
            throw UnsupportedMediaException("Повреждённый moov: ${e.message}")
        }

        val newStart = HashMap<Box, Long>()
        var cursor = 0L
        for (b in kept) {
            newStart[b] = cursor
            cursor += if (b === moovBox) newMoov.size().toLong() else b.size
        }
        val moovBuf = newMoov.rawBuffer()
        for (p in patches) {
            for (i in 0 until p.count) {
                val at = p.position + i * (if (p.wide) 8 else 4)
                val old = if (p.wide) moovBuf.u64be(at) else moovBuf.u32be(at)
                val mdat = mdats.firstOrNull { old >= it.dataStart && old <= it.end }
                    ?: throw UnsupportedMediaException("Смещение данных вне mdat")
                val updated = old - mdat.start + newStart.getValue(mdat)
                if (p.wide) moovBuf.putU64be(at, updated) else moovBuf.putU32be(at, updated)
            }
        }

        var written = 0L
        val buffer = ByteArray(1 shl 20)
        for (b in kept) {
            if (b === moovBox) {
                newMoov.writeTo(out)
                written += newMoov.size()
                continue
            }
            var pos = b.start
            while (pos < b.end) {
                val n = minOf(buffer.size.toLong(), b.end - pos).toInt()
                src.readFully(pos, buffer, 0, n)
                out.write(buffer, 0, n)
                pos += n
            }
            written += b.size
        }
        val notes = listOf("Метаданные удалены без перекодирования — видео и звук не изменены")
        return VideoStripResult(removed, notes, written)
    }

    private fun rewriteContainer(
        buf: ByteArray,
        box: Box,
        path: String,
        out: PatchableOutput,
        patches: MutableList<Patch>,
        removed: MutableList<RemovedItem>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) throw UnsupportedMediaException("Слишком глубокая вложенность блоков")
        val headerPos = out.size()
        out.u32be(0)
        out.writeRange(buf, box.start.toInt() + 4, box.start.toInt() + 8)
        for (k in children(buf, box.dataStart.toInt(), box.end.toInt())) {
            val childPath = "$path/${k.type}"
            val drop = when {
                k.type in MOOV_DROP -> true
                k.type[0] == '?' -> true
                k.type == "uuid" -> !(box.type == "trak" && k.userType!!.contentEquals(SPHERICAL_UUID))
                else -> false
            }
            if (k.type == "mvex") throw UnsupportedMediaException("Фрагментированный MP4")
            when {
                drop -> {
                    val type = when {
                        k.userType?.contentEquals(AiSignatures.C2PA_UUID) == true -> BlockType.C2PA
                        k.type == "uuid" -> BlockType.UNKNOWN
                        else -> BlockType.CONTAINER_META
                    }
                    removed += RemovedItem(type, childPath, k.size)
                }
                k.type in CONTAINERS -> rewriteContainer(buf, k, childPath, out, patches, removed, depth + 1)
                k.type == "stco" || k.type == "co64" -> {
                    val wide = k.type == "co64"
                    val count = buf.u32be(k.dataStart.toInt() + 4)
                    val entryBytes = if (wide) 8 else 4
                    if (count * entryBytes > k.dataSize - 8) throw MalformedMediaException("Bad ${k.type} count")
                    patches += Patch(out.size() + k.headerSize + 8, wide, count.toInt())
                    out.writeRange(buf, k.start.toInt(), k.end.toInt())
                }
                else -> out.writeRange(buf, k.start.toInt(), k.end.toInt())
            }
        }
        out.setU32(headerPos, (out.size() - headerPos).toLong())
    }
}
