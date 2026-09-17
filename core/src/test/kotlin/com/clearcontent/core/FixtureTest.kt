package com.clearcontent.core

import com.clearcontent.core.media.FileChannelSource
import com.clearcontent.core.media.MediaFormat
import com.clearcontent.core.media.MediaKind
import com.clearcontent.core.media.MetadataScanner
import com.clearcontent.core.media.MetadataStripper
import com.clearcontent.core.media.StripOutcome
import com.clearcontent.core.media.UnsupportedMediaException
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the cleaner over real-world samples in src/test/resources/fixtures (C2PA-signed files and
 * synthetic generator output). Cleaned copies land in build/fixture-out for external verification
 * with exiftool / c2patool / ffprobe.
 */
class FixtureTest {
    private val dir = File("src/test/resources/fixtures")
    private val outDir = File("build/fixture-out").apply { mkdirs() }

    private fun fixtures(): List<File> {
        assumeTrue("no fixtures", dir.isDirectory)
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() !in setOf("md", "jsonl", "json", "txt") }
            .sortedBy { it.name }
    }

    @Test
    fun cleanAllFixtures() {
        val files = fixtures()
        assumeTrue(files.isNotEmpty())
        val failures = mutableListOf<String>()
        for (f in files) {
            val report = FileChannelSource.open(f).use { MetadataScanner.scan(it) }
            val line = StringBuilder("${f.name} [${report.format}] ai=${report.aiFindings.map { it.kind }.distinct()} gen=${report.generators}")
            when {
                report.format.kind == MediaKind.VIDEO && report.format != MediaFormat.MATROSKA -> {
                    val out = File(outDir, f.name)
                    try {
                        FileChannelSource.open(f).use { src -> out.outputStream().buffered().use { MetadataStripper.stripVideo(src, it) } }
                        val after = FileChannelSource.open(out).use { MetadataScanner.scan(it) }
                        line.append(" -> video clean=${after.isClean()}")
                        if (!after.isClean()) failures += "${f.name}: ${after.residual}"
                    } catch (e: UnsupportedMediaException) {
                        out.delete()
                        line.append(" -> needs remux (${e.message})")
                    }
                }
                report.format.kind == MediaKind.IMAGE -> {
                    when (val outcome = MetadataStripper.stripImage(f.readBytes())) {
                        is StripOutcome.Stripped -> {
                            File(outDir, f.name).writeBytes(outcome.data)
                            val after = MetadataScanner.scan(outcome.data)
                            line.append(" -> lossless clean=${after.isClean()} removed=${outcome.removed.map { it.location }}")
                            if (!after.isClean()) failures += "${f.name}: ${after.residual}"
                        }
                        is StripOutcome.Unsupported -> line.append(" -> needs re-encode (${outcome.reason})")
                    }
                }
                else -> line.append(" -> skipped")
            }
            println(line)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
