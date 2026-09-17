package com.clearcontent.core.media

/**
 * Container-agnostic heuristics for formats we do not parse structurally (HEIF, AVIF, TIFF, JXL, BMP)
 * and for broken files: locates XMP packets, EXIF blocks, JUMBF/C2PA and strong provenance tokens.
 */
internal object RawScanner {
    private val XMP_START = "<x:xmpmeta".latin1()
    private val XMP_END = "</x:xmpmeta>".latin1()
    private val EXIF_HEADER = "Exif\u0000\u0000".latin1()
    private val JUMB = "jumb".latin1()
    private const val WINDOW = 256 * 1024

    fun scanInto(b: ByteArray, collector: FindingCollector) {
        var from = 0
        while (true) {
            val start = b.indexOf(XMP_START, from)
            if (start < 0) break
            val endTag = b.indexOf(XMP_END, start, minOf(b.size, start + (8 shl 20)))
            val end = if (endTag < 0) minOf(b.size, start + WINDOW) else endTag + XMP_END.size
            collector.analyze(MetaBlock(BlockType.XMP, "XMP", b.copyOfRange(start, end)))
            from = end
        }

        from = 0
        while (true) {
            val start = b.indexOf(EXIF_HEADER, from)
            if (start < 0) break
            val tiff = start + EXIF_HEADER.size
            val header = b.ascii(tiff, 2)
            if (header == "II" || header == "MM") {
                collector.analyze(MetaBlock(BlockType.EXIF, "EXIF", b.copyOfRange(start, minOf(b.size, start + WINDOW))))
            }
            from = tiff
        }

        val jumb = b.indexOf(JUMB)
        if (jumb >= 0) {
            val window = b.copyOfRange(maxOf(0, jumb - 16), minOf(b.size, jumb + WINDOW))
            if (containsJumbf(window)) collector.analyze(MetaBlock(BlockType.C2PA, "JUMBF", window))
        }

        scanStrongTokens(b, collector)
    }

    fun scanStrongTokens(b: ByteArray, collector: FindingCollector, location: String = "Файл") {
        for (token in AiSignatures.STRONG_TOKENS) {
            val pos = b.indexOf(token.latin1())
            if (pos < 0) continue
            val window = b.copyOfRange(maxOf(0, pos - 512), minOf(b.size, pos + 4096))
            if (token.startsWith("c2pa") || token.contains("c2pa") || token.startsWith("Content") || token == "contentauth") {
                collector.add(FindingKind.C2PA, location, token)
            }
            collector.analyzeText(extractStrings(window), location)
        }
    }

    /** Structural scan for TIFF files: the whole file is an EXIF structure. */
    fun scanTiff(b: ByteArray, collector: FindingCollector) {
        collector.analyze(MetaBlock(BlockType.EXIF, "TIFF IFD", b.copyOfRange(0, minOf(b.size, 4 shl 20))))
        scanInto(b, collector)
    }
}
