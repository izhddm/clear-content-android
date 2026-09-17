package com.clearcontent.core

import com.clearcontent.core.TestMedia.contains
import com.clearcontent.core.TestMedia.u32le
import com.clearcontent.core.media.FindingKind
import com.clearcontent.core.media.MediaFormat
import com.clearcontent.core.media.MetadataScanner
import com.clearcontent.core.media.MetadataStripper
import com.clearcontent.core.media.StripOptions
import com.clearcontent.core.media.StripOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ImageStripTest {

    private fun stripped(bytes: ByteArray, options: StripOptions = StripOptions()): StripOutcome.Stripped {
        val outcome = MetadataStripper.stripImage(bytes, options)
        assertTrue("expected Stripped but was $outcome", outcome is StripOutcome.Stripped)
        return outcome as StripOutcome.Stripped
    }

    private fun aiJpeg(orientation: Int = 6, base: ByteArray = TestMedia.jpeg()): ByteArray {
        val exif = "Exif".toByteArray() + byteArrayOf(0, 0) + TestMedia.exifTiff(orientation, "Gemini 2.5 Flash Image (Nano Banana)")
        val xmp = "http://ns.adobe.com/xap/1.0/".toByteArray() + 0.toByte() + TestMedia.XMP.toByteArray()
        val jumbfPayload = byteArrayOf(0x4A, 0x50, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01) + TestMedia.jumbf()
        val iptc = "Photoshop 3.0".toByteArray() + 0.toByte() + "8BIM".toByteArray() + byteArrayOf(0x04, 0x04, 0, 0, 0, 0, 0, 12) +
            byteArrayOf(0x1C, 0x02, 0x78, 0x00, 0x07) + "caption".toByteArray()
        val icc = "ICC_PROFILE".toByteArray() + byteArrayOf(0, 1, 1) + ByteArray(128) { 7 }
        return TestMedia.injectJpeg(
            base,
            listOf(
                TestMedia.jpegSegment(0xE1, exif),
                TestMedia.jpegSegment(0xE1, xmp),
                TestMedia.jpegSegment(0xE2, icc),
                TestMedia.jpegSegment(0xEB, jumbfPayload),
                TestMedia.jpegSegment(0xED, iptc),
                TestMedia.jpegSegment(0xFE, "Made with Midjourney".toByteArray()),
            ),
            trailing = "c2pa-trailer jumb c2pa".toByteArray(),
        )
    }

    @Test
    fun jpegScanFindsAllMarkers() {
        val report = MetadataScanner.scan(aiJpeg())
        val kinds = report.findings.map { it.kind }.toSet()
        assertEquals(MediaFormat.JPEG, report.format)
        for (k in listOf(FindingKind.C2PA, FindingKind.AI_SOURCE_TYPE, FindingKind.AI_GENERATOR, FindingKind.GPS,
            FindingKind.EXIF, FindingKind.XMP, FindingKind.IPTC, FindingKind.COMMENT, FindingKind.TRAILING_DATA, FindingKind.ICC_PROFILE)) {
            assertTrue("missing $k in ${report.findings}", k in kinds)
        }
        assertTrue(report.generators.toString(), "Google Gemini / Imagen" in report.generators)
        assertTrue(report.generators.toString(), "Midjourney" in report.generators)
        assertTrue(report.generators.toString(), "OpenAI / ChatGPT" in report.generators)
        assertEquals(6, report.orientation)
        assertTrue(report.hasAiMarkers)
        assertFalse(report.isClean())
    }

    @Test
    fun jpegStripIsLosslessAndKeepsOrientation() {
        val original = aiJpeg()
        val result = stripped(original)
        val after = MetadataScanner.scan(result.data)
        assertTrue("residual: ${after.residual}", after.isClean())
        assertEquals(6, after.orientation)
        assertTrue(after.findings.any { it.kind == FindingKind.ORIENTATION })
        for (marker in listOf("c2pa", "Gemini", "Midjourney", "trainedAlgorithmicMedia", "Photoshop", "jumb")) {
            assertFalse("still contains $marker", result.data.contains(marker))
        }
        // Pixels are identical because the entropy-coded data is copied verbatim.
        assertArrayEquals(TestMedia.pixels(TestMedia.decode(original)), TestMedia.pixels(TestMedia.decode(result.data)))
        val sos = TestMedia.jpeg().let { base -> base.copyOfRange(indexOfSos(base), base.size) }
        assertArrayEquals(sos, result.data.copyOfRange(indexOfSos(result.data), result.data.size))
        assertTrue(result.removed.size >= 6)
    }

    @Test
    fun jpegStripCanDropColorProfileAndOrientation() {
        val result = stripped(aiJpeg(), StripOptions(keepColorProfile = false, keepOrientation = false))
        val after = MetadataScanner.scan(result.data)
        assertTrue(after.findings.isEmpty())
        assertTrue(after.isClean(allowColorProfile = false))
    }

    @Test
    fun captureDateIsTheOnlyThingKeptBesidesOrientation() {
        val date = com.clearcontent.core.media.CaptureDate("2026:09:17 16:33:45", "+04:00")
        val options = StripOptions(captureDate = date)
        for ((source, expectedOrientation) in listOf(aiJpeg(orientation = 6) to 6, aiJpeg(orientation = 1) to 1)) {
            val result = stripped(source, options)
            val after = MetadataScanner.scan(result.data)
            assertTrue("residual: ${after.residual}", after.isClean())
            val kinds = after.findings.map { it.kind }
            assertTrue(kinds.toString(), FindingKind.CAPTURE_DATE in kinds)
            assertFalse(result.data.contains("Gemini"))
            val exif = com.clearcontent.core.media.ExifReader.readPayload(exifPayload(result.data))
            assertEquals("2026:09:17 16:33:45", exif.dateTimeOriginal)
            assertEquals(expectedOrientation, exif.orientation)
            assertArrayEquals(TestMedia.pixels(TestMedia.decode(source)), TestMedia.pixels(TestMedia.decode(result.data)))
        }
        val png = TestMedia.injectPng(TestMedia.png(), listOf(TestMedia.pngChunk("tEXt", "parameters\u0000Steps: 20, Sampler: Euler".toByteArray())))
        val pngOut = stripped(png, options).data
        val pngAfter = MetadataScanner.scan(pngOut)
        assertTrue(pngAfter.isClean())
        assertTrue(pngAfter.findings.any { it.kind == FindingKind.CAPTURE_DATE })
        // A full camera EXIF is still reported as metadata, not as a bare capture date.
        assertTrue(MetadataScanner.scan(aiJpeg()).findings.any { it.kind == FindingKind.EXIF })
    }

    private fun exifPayload(jpeg: ByteArray): ByteArray {
        var p = 2
        while (p + 4 < jpeg.size) {
            val marker = jpeg[p + 1].toInt() and 0xFF
            val len = ((jpeg[p + 2].toInt() and 0xFF) shl 8) or (jpeg[p + 3].toInt() and 0xFF)
            if (marker == 0xE1) return jpeg.copyOfRange(p + 4, p + 2 + len)
            p += 2 + len
        }
        error("no APP1")
    }

    @Test
    fun progressiveJpegSurvives() {
        val original = aiJpeg(orientation = 1, base = TestMedia.jpeg(progressive = true))
        val result = stripped(original)
        assertTrue(MetadataScanner.scan(result.data).findings.none { it.kind == FindingKind.ORIENTATION })
        assertArrayEquals(TestMedia.pixels(TestMedia.decode(original)), TestMedia.pixels(TestMedia.decode(result.data)))
    }

    @Test
    fun truncatedJpegGetsEoi() {
        val base = aiJpeg(orientation = 1)
        val truncated = base.copyOf(base.size - 40) // cuts EOI and trailer
        val result = stripped(truncated)
        assertEquals(0xFF.toByte(), result.data[result.data.size - 2])
        assertEquals(0xD9.toByte(), result.data[result.data.size - 1])
    }

    @Test
    fun jpegRestartMarkersInsideScanAreNotSegments() {
        // SOI, SOF0-less minimal stream: SOS header followed by entropy data containing RST0 and stuffed FF00.
        val sosHeader = TestMedia.jpegSegment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
        val entropy = byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56, 0xFF.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + TestMedia.jpegSegment(0xFE, "Made with AI".toByteArray()) + sosHeader + entropy
        val result = stripped(jpeg)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + sosHeader + entropy, result.data)
    }

    @Test
    fun pngStripRemovesTextXmpExifAndC2pa() {
        val xmpItxt = "XML:com.adobe.xmp".toByteArray() + byteArrayOf(0, 0, 0, 0, 0) + TestMedia.XMP.toByteArray()
        val compressedItxt = "Description".toByteArray() + byteArrayOf(0, 1, 0, 0, 0) +
            TestMedia.deflate("Created with ComfyUI KSampler".toByteArray())
        val params = "parameters".toByteArray() + 0.toByte() +
            "a cat\nNegative prompt: blurry\nSteps: 20, Sampler: Euler a, Model: sdxl".toByteArray()
        val ztxt = "Comment".toByteArray() + byteArrayOf(0, 0) + TestMedia.deflate("generated by novelai".toByteArray())
        val base = TestMedia.png()
        val png = TestMedia.injectPng(
            base,
            listOf(
                TestMedia.pngChunk("tEXt", params),
                TestMedia.pngChunk("iTXt", xmpItxt),
                TestMedia.pngChunk("iTXt", compressedItxt),
                TestMedia.pngChunk("zTXt", ztxt),
                TestMedia.pngChunk("eXIf", TestMedia.exifTiff(6, "OpenAI")),
                TestMedia.pngChunk("caBX", TestMedia.jumbf()),
                TestMedia.pngChunk("tIME", byteArrayOf(7, 0xEA.toByte(), 9, 17, 12, 0, 0)),
                TestMedia.pngChunk("vpAg", ByteArray(9)),
            ),
            trailing = "junk after iend".toByteArray(),
        )
        val before = MetadataScanner.scan(png)
        val kinds = before.findings.map { it.kind }.toSet()
        for (k in listOf(FindingKind.AI_PARAMETERS, FindingKind.XMP, FindingKind.AI_SOURCE_TYPE, FindingKind.C2PA,
            FindingKind.EXIF, FindingKind.GPS, FindingKind.TRAILING_DATA, FindingKind.UNKNOWN_BLOCK)) {
            assertTrue("missing $k in ${before.findings}", k in kinds)
        }
        assertTrue(before.generators.toString(), before.generators.containsAll(listOf("Stable Diffusion", "ComfyUI", "OpenAI / ChatGPT")))
        assertTrue(before.traits.hasAlpha)

        val result = stripped(png)
        val after = MetadataScanner.scan(result.data)
        assertTrue("residual: ${after.residual}", after.isClean())
        assertEquals(6, after.orientation)
        assertArrayEquals(TestMedia.pixels(TestMedia.decode(base)), TestMedia.pixels(TestMedia.decode(result.data)))
    }

    @Test
    fun pngWithUnknownCriticalChunkNeedsReencode() {
        val png = TestMedia.injectPng(TestMedia.png(), listOf(TestMedia.pngChunk("CgBI", ByteArray(4))))
        assertTrue(MetadataStripper.stripImage(png) is StripOutcome.Unsupported)
    }

    @Test
    fun gifStripKeepsFramesAndDropsComments() {
        val base = TestMedia.gif()
        val trailerAt = base.size - 1
        val comment = byteArrayOf(0x21, 0xFE.toByte(), 20) + "Made with Midjourney".toByteArray() + 0.toByte()
        val xmpExt = byteArrayOf(0x21, 0xFF.toByte(), 11) + "XMP DataXMP".toByteArray() +
            byteArrayOf(40) + "trainedAlgorithmicMedia Adobe Firefly!!!".toByteArray() + 0.toByte()
        val gif = base.copyOfRange(0, trailerAt) + comment + xmpExt + base.copyOfRange(trailerAt, base.size) + "tail".toByteArray()
        val before = MetadataScanner.scan(gif)
        assertTrue(before.findings.toString(), before.findings.any { it.kind == FindingKind.COMMENT })
        assertTrue(before.generators.toString(), before.generators.containsAll(listOf("Midjourney", "Adobe Firefly")))

        val result = stripped(gif)
        assertArrayEquals(base, result.data)
        assertTrue(MetadataScanner.scan(result.data).isClean())
    }

    @Test
    fun webpStripRewritesFlagsAndRiffSize() {
        val vp8l = byteArrayOf(0x2F, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11) // fake bitstream, odd length
        val exif = TestMedia.exifTiff(8, "OpenAI")
        val xmp = TestMedia.XMP.toByteArray()
        val c2pa = TestMedia.jumbf()
        val icc = ByteArray(10) { 1 }
        val body = ByteArrayOutputStream()
        fun chunk(fourcc: String, data: ByteArray) {
            body.write(fourcc.toByteArray()); body.u32le(data.size.toLong()); body.write(data)
            if (data.size % 2 == 1) body.write(0)
        }
        chunk("VP8X", byteArrayOf((0x20 or 0x10 or 0x08 or 0x04).toByte(), 0, 0, 0, 63, 0, 0, 47, 0, 0))
        chunk("ICCP", icc)
        chunk("VP8L", vp8l)
        chunk("EXIF", exif)
        chunk("XMP ", xmp)
        chunk("C2PA", c2pa)
        val file = ByteArrayOutputStream()
        file.write("RIFF".toByteArray()); file.u32le((body.size() + 4).toLong()); file.write("WEBP".toByteArray()); body.writeTo(file)
        val webp = file.toByteArray()

        val before = MetadataScanner.scan(webp)
        assertTrue(before.findings.toString(), before.findings.any { it.kind == FindingKind.C2PA })
        assertTrue(before.findings.any { it.kind == FindingKind.AI_SOURCE_TYPE })
        assertTrue(before.traits.lossless)

        val result = stripped(webp)
        val out = result.data
        assertEquals(out.size - 8, le32(out, 4))
        val chunks = listChunks(out)
        assertEquals(listOf("VP8X", "ICCP", "VP8L", "EXIF"), chunks.map { it.first })
        val flags = out[20].toInt() and 0xFF
        assertEquals(0x20 or 0x10 or 0x08, flags)
        val after = MetadataScanner.scan(out)
        assertTrue("residual: ${after.residual}", after.isClean())
        assertEquals(8, after.orientation)

        val noIcc = stripped(webp, StripOptions(keepColorProfile = false, keepOrientation = false)).data
        assertEquals(listOf("VP8X", "VP8L"), listChunks(noIcc).map { it.first })
        assertEquals(0x10, noIcc[20].toInt() and 0xFF)
    }

    private fun le32(b: ByteArray, p: Int) =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    private fun listChunks(b: ByteArray): List<Pair<String, Int>> {
        val list = mutableListOf<Pair<String, Int>>()
        var p = 12
        while (p + 8 <= b.size) {
            val len = le32(b, p + 4)
            list += String(b, p, 4) to len
            p += 8 + len + (len and 1)
        }
        assertEquals(b.size, p)
        return list
    }

    private fun indexOfSos(b: ByteArray): Int {
        for (i in 0 until b.size - 1) if (b[i] == 0xFF.toByte() && b[i + 1] == 0xDA.toByte()) return i
        error("no SOS")
    }
}
