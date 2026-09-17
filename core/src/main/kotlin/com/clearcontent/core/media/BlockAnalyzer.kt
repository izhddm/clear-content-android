package com.clearcontent.core.media

import java.util.zip.Inflater

/** Turns extracted metadata blocks into user-facing findings. */
internal class FindingCollector {
    private val findings = LinkedHashSet<Finding>()
    private val generators = HashSet<String>()
    private val sourceTypes = HashSet<String>()
    var orientation = 1

    fun add(kind: FindingKind, location: String, detail: String? = null) {
        findings += Finding(kind, location, detail?.let(::shorten))
    }

    fun analyzeText(text: String, location: String) {
        if (text.isEmpty()) return
        for (type in AiSignatures.matchSourceTypes(text)) {
            if (sourceTypes.add(type)) add(FindingKind.AI_SOURCE_TYPE, location, type)
        }
        for (name in AiSignatures.matchGenerators(text)) {
            if (generators.add(name)) add(FindingKind.AI_GENERATOR, location, name)
        }
    }

    fun analyze(block: MetaBlock) {
        val loc = block.location
        when (block.type) {
            BlockType.C2PA -> {
                add(FindingKind.C2PA, loc, "Content Credentials (${block.data.size / 1024 + 1} КБ)")
                analyzeText(extractStrings(block.data), loc)
            }
            BlockType.EXIF -> analyzeExif(block, loc)
            BlockType.XMP -> {
                val xmp = String(block.data, Charsets.UTF_8)
                val tool = XMP_CREATOR_TOOL.find(xmp)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                add(FindingKind.XMP, loc, tool?.let { "CreatorTool: $it" })
                if (XMP_GPS.containsMatchIn(xmp)) add(FindingKind.GPS, loc, "XMP")
                XMP_PROVENANCE.find(xmp)?.let { add(FindingKind.C2PA, loc, "Ссылка на удалённый манифест: ${it.groupValues[1]}") }
                analyzeText(xmp, loc)
            }
            BlockType.IPTC -> {
                add(FindingKind.IPTC, loc)
                analyzeText(extractStrings(block.data), loc)
            }
            BlockType.ICC -> add(FindingKind.ICC_PROFILE, loc)
            BlockType.COMMENT -> {
                val text = decodeLoose(block.data)
                add(FindingKind.COMMENT, loc, text)
                analyzeText(text, loc)
            }
            BlockType.TEXT -> {
                val text = decodeLoose(block.data)
                val key = block.key.orEmpty()
                val kind = if (key.lowercase() in AI_TEXT_KEYS || AI_PARAM_TEXT.containsMatchIn(text)) {
                    FindingKind.AI_PARAMETERS
                } else {
                    FindingKind.TEXT
                }
                add(kind, loc, if (key.isNotEmpty()) "$key: $text" else text)
                analyzeText(text, loc)
                if (XMP_GPS.containsMatchIn(text)) add(FindingKind.GPS, loc)
            }
            BlockType.EXTRA_IMAGE -> {
                add(FindingKind.EXTRA_IMAGE, loc, "${block.data.size / 1024 + 1} КБ")
                analyzeText(extractStrings(block.data), loc)
            }
            BlockType.THUMBNAIL -> add(FindingKind.THUMBNAIL, loc)
            BlockType.TRAILER -> {
                add(FindingKind.TRAILING_DATA, loc, "${block.data.size} байт после конца изображения")
                analyzeText(extractStrings(block.data), loc)
                if (containsJumbf(block.data)) add(FindingKind.C2PA, loc, "JUMBF в хвосте файла")
            }
            BlockType.CONTAINER_META -> {
                val text = extractStrings(block.data)
                add(FindingKind.CONTAINER_METADATA, loc, text.lineSequence().filter { it.length > 2 }.take(4).joinToString(" · "))
                if (QT_GPS.containsMatchIn(text)) add(FindingKind.GPS, loc)
                analyzeText(text, loc)
            }
            BlockType.TIMESTAMP -> add(FindingKind.TIMESTAMP, loc)
            BlockType.UNKNOWN -> {
                add(FindingKind.UNKNOWN_BLOCK, loc, "${block.data.size} байт")
                analyzeText(extractStrings(block.data), loc)
                if (containsJumbf(block.data)) add(FindingKind.C2PA, loc, "JUMBF")
            }
        }
    }

    private fun analyzeExif(block: MetaBlock, loc: String) {
        val exif = ExifReader.readPayload(block.data)
        if (exif.orientation in 2..8) orientation = exif.orientation
        val minimal = exif.tags.all { it in MinimalExif.ALLOWED_TAGS } && !exif.hasGps && exif.textFields.isEmpty() &&
            !exif.hasThumbnail && exif.tagCount == exif.tags.size
        if (minimal) {
            if (exif.orientation in 2..8) add(FindingKind.ORIENTATION, loc, "Поворот: ${exif.orientation}")
            exif.dateTimeOriginal?.let { add(FindingKind.CAPTURE_DATE, loc, it) }
            return
        }
        add(FindingKind.EXIF, loc, "${exif.tagCount} тегов" + (exif.software?.let { ", Software: $it" } ?: ""))
        if (exif.hasGps) add(FindingKind.GPS, loc, "Координаты съёмки")
        if (exif.make != null || exif.model != null) {
            add(FindingKind.CAMERA, loc, listOfNotNull(exif.make, exif.model).joinToString(" "))
        }
        if (exif.hasThumbnail) add(FindingKind.THUMBNAIL, loc, "Миниатюра в EXIF")
        exif.dateTimeOriginal?.let { add(FindingKind.TIMESTAMP, loc, it) }
        analyzeText(exif.textFields.joinToString("\n") + "\n" + extractStrings(block.data), loc)
    }

    fun build(format: MediaFormat, traits: ImageTraits = ImageTraits(), parseError: String? = null) =
        ScanReport(format, findings.toList(), orientation, traits, parseError)

    companion object {
        private val XMP_CREATOR_TOOL = Regex("""CreatorTool(?:="([^"]*)"|>([^<]*)<)""")
        private val XMP_PROVENANCE = Regex("""dcterms:provenance(?:="|>)([^"<]*)""")
        private val XMP_GPS = Regex("""exif:GPS(?:Latitude|Longitude)""")
        private val QT_GPS = Regex("""(?:[\u00A9\uFFFD]xyz|com\.apple\.quicktime\.location\.ISO6709|[+-]\d{1,2}\.\d+[+-]\d{1,3}\.\d+)""")
        private val AI_TEXT_KEYS = setOf(
            "parameters", "prompt", "workflow", "dream", "sd-metadata", "invokeai_metadata", "invokeai_graph",
            "generation_data", "negative_prompt", "aigc", "fooocus_scheme", "ai_metadata",
        )
        private val AI_PARAM_TEXT = Regex("""(?i)(negative prompt:|steps:\s*\d+.*sampler:|"class_type"|"sampler_name"|\baigc\b)""")

        private fun shorten(s: String): String {
            val oneLine = s.replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length > 140) oneLine.take(137) + "…" else oneLine
        }
    }
}

/**
 * Printable runs (valid UTF-8 or UTF-16LE) from a binary payload, joined by newlines.
 * Short runs are ignored so random bytes inside thumbnails do not look like brand names.
 */
internal fun extractStrings(data: ByteArray, minLen: Int = 6, limit: Int = 4 shl 20): String {
    val sb = StringBuilder()
    val n = minOf(data.size, limit)
    var runStart = -1
    fun flush(end: Int) {
        if (runStart >= 0 && end - runStart >= minLen) {
            val s = String(data, runStart, end - runStart, Charsets.UTF_8)
            sb.append(s).append('\n')
            // CBOR (C2PA manifests) prefixes short text strings with a header byte in 'a'..'w'.
            if ((data[runStart].toInt() and 0xFF) in 0x61..0x77) sb.append(s, 1, s.length).append('\n')
        }
        runStart = -1
    }
    var i = 0
    while (i < n) {
        val b = data[i].toInt() and 0xFF
        val len = when {
            b in 0x20..0x7E || b == 0x09 || b == 0x0A || b == 0x0D -> 1
            b in 0xC2..0xDF -> 2
            b in 0xE0..0xEF -> 3
            b in 0xF0..0xF4 -> 4
            else -> 0
        }
        val valid = len > 0 && i + len <= n && (1 until len).all { (data[i + it].toInt() and 0xC0) == 0x80 }
        if (valid) {
            if (runStart < 0) runStart = i
            i += len
        } else {
            flush(i)
            i++
        }
    }
    flush(n)
    // UTF-16LE runs (EXIF XP* tags, some QuickTime atoms).
    var j = 0
    var run = StringBuilder()
    while (j + 1 < n) {
        val c = data[j].toInt() and 0xFF
        if (data[j + 1].toInt() == 0 && c in 0x20..0x7E) {
            run.append(c.toChar()); j += 2
        } else {
            if (run.length >= minLen) sb.append(run).append('\n')
            run = StringBuilder(); j++
        }
    }
    if (run.length >= minLen) sb.append(run)
    return sb.toString()
}

/** UTF-8 if valid, otherwise Latin-1; trailing NULs trimmed. */
internal fun decodeLoose(data: ByteArray): String {
    val utf8 = String(data, Charsets.UTF_8)
    val text = if ('�' in utf8) String(data, Charsets.ISO_8859_1) else utf8
    return text.trimEnd('\u0000')
}

internal fun containsJumbf(data: ByteArray): Boolean {
    val jumb = data.indexOf("jumb".latin1())
    return jumb >= 0 && data.indexOf("c2pa".latin1(), jumb) >= 0
}

internal fun inflate(data: ByteArray, offset: Int, limit: Int = 16 shl 20): ByteArray? {
    val inflater = Inflater()
    return try {
        inflater.setInput(data, offset, data.size - offset)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > limit) break
        }
        out.toByteArray()
    } catch (_: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}
