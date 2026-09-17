package com.clearcontent.core

import com.clearcontent.core.TestMedia.box
import com.clearcontent.core.TestMedia.u16
import com.clearcontent.core.TestMedia.u32
import com.clearcontent.core.TestMedia.u64
import com.clearcontent.core.media.BlockType
import com.clearcontent.core.media.ByteArraySource
import com.clearcontent.core.media.FindingKind
import com.clearcontent.core.media.MetadataScanner
import com.clearcontent.core.media.MetadataStripper
import com.clearcontent.core.media.UnsupportedMediaException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class VideoStripTest {

    private val samples = listOf("SAMPLE-ONE-video".toByteArray(), "SAMPLE-TWO-audio!".toByteArray(), "SAMPLE-3".toByteArray())

    /** Builds a tiny MP4 whose stco/co64 entries point at [samples] inside mdat. */
    private fun mp4(moovFirst: Boolean, wide: Boolean = false, fragmented: Boolean = false, extraMdat: Boolean = false): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() + byteArrayOf(0, 0, 2, 0) + "isomiso2mp41".toByteArray())
        val c2pa = TestMedia.uuidBox(TestMedia.C2PA_UUID, TestMedia.jumbf())
        val free = box("free", "Lavf61 encoder junk".toByteArray())
        val mdatPayload = samples.fold(ByteArray(0)) { acc, s -> acc + s }
        val mdat = box("mdat", mdatPayload)
        val extra = if (extraMdat) box("mdat", "SECOND".toByteArray()) else ByteArray(0)

        fun moov(offsets: List<Long>): ByteArray {
            val table = ByteArrayOutputStream()
            table.write(byteArrayOf(0, 0, 0, 0)); table.u32(offsets.size.toLong())
            offsets.forEach { if (wide) table.u64(it) else table.u32(it) }
            val stco = box(if (wide) "co64" else "stco", table.toByteArray())
            val stsd = box("stsd", ByteArray(8))
            val stbl = box("stbl", stsd + stco)
            val minf = box("minf", box("vmhd", ByteArray(12)) + box("dinf", box("dref", ByteArray(8))) + stbl)
            val hdlr = box("hdlr", ByteArray(8) + "vide".toByteArray() + ByteArray(12) + "VideoHandler".toByteArray() + 0.toByte())
            val mdia = box("mdia", box("mdhd", ByteArray(24)) + hdlr + minf)
            val trakUdta = box("udta", box("name", "Sora track".toByteArray()))
            val trak = box("trak", box("tkhd", ByteArray(84)) + mdia + trakUdta)
            val keys = box("keys", ByteArray(8) + box("mdta", "com.apple.quicktime.location.ISO6709".toByteArray()))
            val ilst = box("ilst", box("data", "+59.9386+030.3141/".toByteArray()))
            val meta = box("meta", ByteArray(4) + box("hdlr", ByteArray(24)) + keys + ilst)
            val tool = ByteArrayOutputStream().apply {
                u32(8 + 16L); write(byteArrayOf(0xA9.toByte()) + "too".toByteArray())
                u16(12); u16(0); write("Google Veo 3".toByteArray())
            }.toByteArray()
            val aigc = box("AIGC", "{\"Label\":\"1\",\"ContentProducer\":\"x\"}".toByteArray())
            val udta = box("udta", tool + aigc)
            val mvex = if (fragmented) box("mvex", box("trex", ByteArray(24))) else ByteArray(0)
            return box("moov", box("mvhd", ByteArray(100)) + trak + udta + meta + mvex)
        }

        // Layout with placeholder offsets first, then patch with real ones.
        fun assemble(offsets: List<Long>): ByteArray =
            if (moovFirst) ftyp + c2pa + moov(offsets) + free + mdat + extra
            else ftyp + free + mdat + extra + moov(offsets) + c2pa

        val placeholder = assemble(List(samples.size) { 0L })
        val mdatStart = indexOf(placeholder, "mdat") - 4
        var pos = mdatStart + 8L
        val offsets = samples.map { s -> pos.also { pos += s.size } }
        return assemble(offsets)
    }

    private fun indexOf(b: ByteArray, s: String): Int = String(b, Charsets.ISO_8859_1).indexOf(s)

    private fun strip(bytes: ByteArray): Pair<ByteArray, com.clearcontent.core.media.VideoStripResult> {
        val out = ByteArrayOutputStream()
        val result = MetadataStripper.stripVideo(ByteArraySource(bytes), out)
        return out.toByteArray() to result
    }

    /** Reads chunk offsets from the (single) stco/co64 table. */
    private fun offsets(b: ByteArray): List<Long> {
        val s = String(b, Charsets.ISO_8859_1)
        val wide = s.contains("co64")
        val at = s.indexOf(if (wide) "co64" else "stco") + 4 + 4
        val count = be(b, at, 4).toInt()
        return List(count) { i -> if (wide) be(b, at + 4 + i * 8, 8) else be(b, at + 4 + i * 4, 4) }
    }

    private fun be(b: ByteArray, p: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
        return v
    }

    private fun assertSamplesIntact(out: ByteArray) {
        val offs = offsets(out)
        samples.forEachIndexed { i, s ->
            assertArrayEquals("sample $i", s, out.copyOfRange(offs[i].toInt(), offs[i].toInt() + s.size))
        }
    }

    private fun topLevel(b: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var p = 0
        while (p + 8 <= b.size) {
            types += String(b, p + 4, 4, Charsets.ISO_8859_1)
            p += be(b, p, 4).toInt()
        }
        assertEquals(b.size, p)
        return types
    }

    @Test
    fun scanFindsC2paUdtaMetaAndGps() {
        val report = MetadataScanner.scan(ByteArraySource(mp4(moovFirst = true)))
        val kinds = report.findings.map { it.kind }.toSet()
        assertTrue(report.findings.toString(), FindingKind.C2PA in kinds)
        assertTrue(FindingKind.CONTAINER_METADATA in kinds)
        assertTrue(report.findings.toString(), FindingKind.GPS in kinds)
        assertTrue(report.generators.toString(), report.generators.containsAll(listOf("Google Gemini / Imagen", "OpenAI / ChatGPT", "Kling / Hailuo / метка AIGC (КНР)")))
    }

    @Test
    fun faststartFileIsRepointed() {
        val original = mp4(moovFirst = true)
        assertSamplesIntact(original)
        val (out, result) = strip(original)
        assertEquals(listOf("ftyp", "moov", "mdat"), topLevel(out))
        assertSamplesIntact(out)
        assertTrue(offsets(out).first() < offsets(original).first())
        for (marker in listOf("c2pa", "Sora", "Veo", "AIGC", "ISO6709", "udta", "Lavf")) {
            assertFalse("still contains $marker", String(out, Charsets.ISO_8859_1).contains(marker))
        }
        assertTrue(result.removed.any { it.type == BlockType.C2PA })
        assertEquals(out.size.toLong(), result.bytesWritten)
        val after = MetadataScanner.scan(ByteArraySource(out))
        assertTrue("residual: ${after.residual}", after.isClean())
    }

    @Test
    fun moovAtEndWithCo64AndTwoMdats() {
        val original = mp4(moovFirst = false, wide = true, extraMdat = true)
        assertSamplesIntact(original)
        val (out, _) = strip(original)
        assertEquals(listOf("ftyp", "mdat", "mdat", "moov"), topLevel(out))
        assertSamplesIntact(out)
        assertTrue(MetadataScanner.scan(ByteArraySource(out)).isClean())
    }

    @Test(expected = UnsupportedMediaException::class)
    fun fragmentedFilesAreRejected() {
        strip(mp4(moovFirst = true, fragmented = true))
    }

    @Test
    fun deeplyNestedBoxesDoNotOverflowTheStack() {
        var inner = box("stco", ByteArray(8))
        repeat(20_000) { inner = box("trak", inner) }
        val file = box("ftyp", "isom".toByteArray() + ByteArray(4)) + box("moov", inner) + box("mdat", ByteArray(16))
        MetadataScanner.scan(ByteArraySource(file))
        try {
            strip(file)
            error("expected rejection")
        } catch (_: UnsupportedMediaException) {
        }
    }

    @Test(expected = UnsupportedMediaException::class)
    fun offsetsOutsideMdatAreRejected() {
        val original = mp4(moovFirst = true)
        val s = String(original, Charsets.ISO_8859_1)
        val at = s.indexOf("stco") + 4 + 4 + 4
        original[at] = 0; original[at + 1] = 0; original[at + 2] = 0; original[at + 3] = 10 // points into ftyp
        strip(original)
    }
}
