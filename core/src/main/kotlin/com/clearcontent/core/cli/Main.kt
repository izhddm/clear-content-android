package com.clearcontent.core.cli

import com.clearcontent.core.media.FileChannelSource
import com.clearcontent.core.media.MediaKind
import com.clearcontent.core.media.MetadataScanner
import com.clearcontent.core.media.MetadataStripper
import com.clearcontent.core.media.ScanReport
import com.clearcontent.core.media.StripOptions
import com.clearcontent.core.media.StripOutcome
import com.clearcontent.core.media.UnsupportedMediaException
import com.clearcontent.core.text.TextCleanOptions
import com.clearcontent.core.text.TextSanitizer
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Clear Content CLI

  scan  FILE...                         report provenance / AI / private metadata
  clean [-o DIR] [--no-icc] [--no-orientation] FILE...
                                        lossless metadata removal (JPEG, PNG, WebP, GIF, MP4, MOV)
  text  [--markdown] < input > output   remove hidden characters from text on stdin
"""

fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: run { print(USAGE); exitProcess(2) }
    val rest = args.drop(1)
    val code = when (command) {
        "scan" -> scan(rest)
        "clean" -> clean(rest)
        "text" -> text(rest)
        else -> { print(USAGE); 2 }
    }
    exitProcess(code)
}

private fun report(file: File): ScanReport = FileChannelSource.open(file).use { MetadataScanner.scan(it) }

private fun printReport(r: ScanReport) {
    println("  format: ${r.format}" + (r.parseError?.let { "  (parse error: $it)" } ?: ""))
    if (r.findings.isEmpty()) println("  no metadata found")
    for (f in r.findings) println("  [${f.kind.severity}] ${f.kind} @ ${f.location}" + (f.detail?.let { " — $it" } ?: ""))
}

private fun scan(files: List<String>): Int {
    var ai = 0
    for (path in files) {
        val file = File(path)
        println(file.name)
        val r = report(file)
        printReport(r)
        if (r.hasAiMarkers) ai++
    }
    return if (ai > 0) 1 else 0
}

private fun clean(args: List<String>): Int {
    var outDir: File? = null
    var options = StripOptions()
    val files = mutableListOf<File>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-o" -> outDir = File(args[++i])
            "--no-icc" -> options = options.copy(keepColorProfile = false)
            "--no-orientation" -> options = options.copy(keepOrientation = false)
            else -> files += File(a)
        }
        i++
    }
    var failures = 0
    for (file in files) {
        val dir = outDir ?: file.absoluteFile.parentFile
        dir.mkdirs()
        val before = report(file)
        val target = File(dir, file.nameWithoutExtension + "_clean." + before.format.extension)
        try {
            val removed = if (before.format.kind == MediaKind.VIDEO) {
                FileChannelSource.open(file).use { src -> target.outputStream().buffered().use { MetadataStripper.stripVideo(src, it) } }.removed
            } else {
                when (val o = MetadataStripper.stripImage(file.readBytes(), options)) {
                    is StripOutcome.Stripped -> o.removed.also { target.writeBytes(o.data) }
                    is StripOutcome.Unsupported -> throw UnsupportedMediaException(o.reason)
                }
            }
            val after = report(target)
            val ok = after.isClean(options.keepColorProfile)
            println("${file.name} -> ${target.name}: ${if (ok) "CLEAN" else "RESIDUAL ${after.residual}"}")
            println("  removed: " + removed.joinToString { "${it.location} (${it.bytes} B)" })
            if (!ok) failures++
        } catch (e: UnsupportedMediaException) {
            println("${file.name}: needs re-encoding on the phone (${e.message})")
            target.delete()
            failures++
        }
    }
    return if (failures > 0) 1 else 0
}

private fun text(args: List<String>): Int {
    val input = generateSequence(::readlnOrNull).joinToString("\n")
    val result = TextSanitizer.clean(input, TextCleanOptions(stripMarkdown = "--markdown" in args))
    print(result.text)
    System.err.println("changes: ${result.counts}")
    return 0
}
