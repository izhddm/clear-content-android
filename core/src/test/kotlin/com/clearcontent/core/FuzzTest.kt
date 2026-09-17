package com.clearcontent.core

import com.clearcontent.core.media.ByteArraySource
import com.clearcontent.core.media.MediaFormat
import com.clearcontent.core.media.MetadataScanner
import com.clearcontent.core.media.MetadataStripper
import com.clearcontent.core.media.StripOutcome
import com.clearcontent.core.media.UnsupportedMediaException
import com.clearcontent.core.text.TextCleanOptions
import com.clearcontent.core.text.TextSanitizer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Hostile-input robustness: truncated and bit-flipped media must never crash the parsers,
 * and whatever the stripper reports as success must re-scan clean or be clearly rejected.
 */
class FuzzTest {
    private val random = Random(20260917)

    private fun seeds(): List<ByteArray> {
        val dir = File("src/test/resources/fixtures")
        val real = dir.listFiles().orEmpty()
            .filter { it.isFile && it.length() < 400_000 && it.extension.lowercase() !in setOf("md", "jsonl") }
            .map { it.readBytes() }
        return real + listOf(TestMedia.jpeg(), TestMedia.png(), TestMedia.gif())
    }

    private fun mutate(src: ByteArray): ByteArray {
        val b = src.copyOf()
        when (random.nextInt(4)) {
            0 -> return b.copyOf(random.nextInt(0, b.size + 1))
            1 -> repeat(random.nextInt(1, 16)) { if (b.isNotEmpty()) b[random.nextInt(b.size)] = random.nextInt(256).toByte() }
            2 -> repeat(4) {
                // Corrupt a likely length field near the start of a structure.
                if (b.size > 64) {
                    val p = random.nextInt(0, minOf(b.size - 4, 4096))
                    b[p] = 0xFF.toByte(); b[p + 1] = 0xFF.toByte(); b[p + 2] = random.nextInt(256).toByte()
                }
            }
            else -> {
                val cut = random.nextInt(0, b.size + 1)
                return b.copyOf(cut) + ByteArray(random.nextInt(0, 64)) { random.nextInt(256).toByte() }
            }
        }
        return b
    }

    @Test
    fun parsersSurviveCorruptedInput() {
        var cases = 0
        for (seed in seeds()) {
            repeat(60) {
                val data = mutate(seed)
                cases++
                val report = MetadataScanner.scan(data)
                MetadataScanner.scan(ByteArraySource(data))
                when (val outcome = MetadataStripper.stripImage(data)) {
                    is StripOutcome.Stripped -> {
                        val after = MetadataScanner.scan(outcome.data)
                        // A stripped result must never re-introduce findings beyond ICC/orientation.
                        assertTrue("residual ${after.residual} for ${report.format}", after.parseError != null || after.isClean())
                    }
                    is StripOutcome.Unsupported -> Unit
                }
                if (report.format == MediaFormat.MP4 || report.format == MediaFormat.MOV) {
                    try {
                        MetadataStripper.stripVideo(ByteArraySource(data), ByteArrayOutputStream())
                    } catch (_: UnsupportedMediaException) {
                        // expected for broken containers
                    }
                }
            }
        }
        assertTrue(cases > 500)
    }

    @Test
    fun textSanitizerSurvivesRandomCodePoints() {
        val o = TextCleanOptions(stripMarkdown = true, normalizeDashes = true, normalizeQuotes = true, normalizeEllipsis = true, normalizeFancyLetters = true, removeCitationNumbers = true, collapseBlankLines = true)
        repeat(300) {
            val cps = IntArray(random.nextInt(0, 200)) {
                when (random.nextInt(6)) {
                    0 -> random.nextInt(0, 0x80)
                    1 -> random.nextInt(0x200B, 0x2070)
                    2 -> random.nextInt(0xE0000, 0xE0080)
                    3 -> random.nextInt(0x1F300, 0x1FAFF)
                    4 -> listOf(0x200D, 0xFE0F, 0x1F3F4, 0xE007F, 0x2A, 0x5F, 0x0A, 0xD800).random(random)
                    else -> random.nextInt(0, 0x110000)
                }
            }
            val text = String(cps, 0, cps.size)
            val r = TextSanitizer.clean(text, o)
            TextSanitizer.findHidden(text)
            // Idempotent for hidden characters: cleaning twice removes nothing more.
            assertTrue(TextSanitizer.clean(r.text, o).hiddenCount == 0)
        }
    }
}
