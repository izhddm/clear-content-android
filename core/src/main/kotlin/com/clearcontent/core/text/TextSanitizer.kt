package com.clearcontent.core.text

import java.text.Normalizer

data class TextCleanOptions(
    /** Zero-width, bidi, tag, variation-selector abuse, private-use and other non-rendering characters. */
    val removeInvisible: Boolean = true,
    /** NBSP, narrow NBSP, thin/hair/ideographic spaces → regular space; line/paragraph separators → newline. */
    val normalizeSpaces: Boolean = true,
    /** Leftover chatbot citation tokens: "citeturn0search0", "【4:0†source】", private-use cite markers. */
    val removeAiArtifacts: Boolean = true,
    /** utm_source=chatgpt.com and similar referral parameters in links. */
    val removeTrackingParams: Boolean = true,
    /** Numeric reference markers such as "[1]" or "[web:2]" glued to words. */
    val removeCitationNumbers: Boolean = false,
    /** **bold**, # headings, `code`, [links](url), bullets → plain text suitable for Instagram. */
    val stripMarkdown: Boolean = false,
    val normalizeDashes: Boolean = false,
    val normalizeQuotes: Boolean = false,
    val normalizeEllipsis: Boolean = false,
    /** 𝐁𝐨𝐥𝐝 / ｆｕｌｌｗｉｄｔｈ / ⓒⓘⓡⓒⓛⓔⓓ letters → plain letters. */
    val normalizeFancyLetters: Boolean = false,
    val trimLines: Boolean = true,
    val collapseBlankLines: Boolean = false,
)

enum class TextIssue {
    INVISIBLE, BIDI, TAG_CHARS, VARIATION_SELECTORS, SPECIAL_SPACES, CONTROL, PRIVATE_USE,
    AI_CITATIONS, TRACKING, CITATION_NUMBERS, MARKDOWN, DASHES, QUOTES, ELLIPSIS, FANCY_LETTERS, WHITESPACE,
}

data class TextCleanResult(
    val text: String,
    val counts: Map<TextIssue, Int>,
    /** Removed or replaced hidden code points with their counts. */
    val hiddenCodePoints: Map<Int, Int>,
) {
    val totalChanges: Int get() = counts.values.sum()
    val changed: Boolean get() = totalChanges > 0
    val hiddenCount: Int get() = HIDDEN_ISSUES.sumOf { counts[it] ?: 0 }

    companion object {
        val HIDDEN_ISSUES = setOf(
            TextIssue.INVISIBLE, TextIssue.BIDI, TextIssue.TAG_CHARS, TextIssue.VARIATION_SELECTORS,
            TextIssue.SPECIAL_SPACES, TextIssue.CONTROL, TextIssue.PRIVATE_USE,
        )
    }
}

/** A hidden character located in the original text (UTF-16 indices), for highlighting. */
data class HiddenChar(val start: Int, val end: Int, val codePoint: Int, val issue: TextIssue)

object TextSanitizer {

    fun clean(input: String, options: TextCleanOptions = TextCleanOptions()): TextCleanResult {
        val counter = Counter()
        var text = input.replace("\r\n", "\n").replace('\r', '\n')
        if (options.removeAiArtifacts) text = AiArtifacts.removeCitations(text, counter)
        if (options.removeTrackingParams) text = AiArtifacts.removeTracking(text, counter)
        if (options.removeCitationNumbers) text = AiArtifacts.removeCitationNumbers(text, counter)
        if (options.stripMarkdown) text = MarkdownStripper.strip(text, counter)
        // Removing one character can change the context of its neighbours (e.g. a ZWJ that joined
        // two emoji only through a now-removed selector), so repeat until nothing hidden is left.
        var passes = 0
        do {
            val before = counter.hiddenTotal
            text = charPass(text, options, counter, null)
            passes++
        } while (counter.hiddenTotal != before && passes < MAX_PASSES)
        text = Normalizer.normalize(text, Normalizer.Form.NFC)
        text = whitespacePass(text, options, counter)
        return TextCleanResult(text, counter.issues.toMap(), counter.codePoints.toMap())
    }

    /** Hidden characters that [clean] with default options would remove or replace. */
    fun findHidden(input: String): List<HiddenChar> {
        val found = mutableListOf<HiddenChar>()
        charPass(input, TextCleanOptions(), Counter(), found)
        return found
    }

    fun describe(codePoint: Int): String {
        val name = runCatching { Character.getName(codePoint) }.getOrNull() ?: "UNASSIGNED"
        return "U+%04X %s".format(codePoint, name)
    }

    internal class Counter {
        val issues = LinkedHashMap<TextIssue, Int>()
        val codePoints = LinkedHashMap<Int, Int>()

        fun add(issue: TextIssue, n: Int = 1) {
            if (n > 0) issues[issue] = (issues[issue] ?: 0) + n
        }

        var hiddenTotal = 0
            private set

        fun hidden(issue: TextIssue, cp: Int) {
            add(issue)
            hiddenTotal++
            codePoints[cp] = (codePoints[cp] ?: 0) + 1
        }
    }

    // ------------------------------------------------------------------ character pass

    private fun charPass(text: String, o: TextCleanOptions, counter: Counter, found: MutableList<HiddenChar>?): String {
        val cps = text.codePoints().toArray()
        val offsets = IntArray(cps.size + 1)
        for (i in cps.indices) offsets[i + 1] = offsets[i] + Character.charCount(cps[i])
        val hasRtl = cps.any { isRtl(it) }
        val out = StringBuilder(text.length)
        var prev = -1 // last code point written to the output
        var inTagSequence = false

        for (i in cps.indices) {
            val cp = cps[i]
            val next = if (i + 1 < cps.size) cps[i + 1] else -1

            fun remove(issue: TextIssue) {
                counter.hidden(issue, cp)
                found?.add(HiddenChar(offsets[i], offsets[i + 1], cp, issue))
            }

            fun replace(with: String, issue: TextIssue, hidden: Boolean) {
                if (hidden) {
                    counter.hidden(issue, cp)
                    found?.add(HiddenChar(offsets[i], offsets[i + 1], cp, issue))
                } else {
                    counter.add(issue)
                }
                out.append(with)
                prev = with.codePointBefore(with.length)
            }

            fun keep() {
                out.appendCodePoint(cp)
                prev = cp
            }

            if (cp != TAG_CANCEL && cp !in TAG_RANGE) inTagSequence = cp == BLACK_FLAG && isValidFlagTagSequence(cps, i + 1)

            if (o.removeInvisible) {
                val issue = invisibleIssue(cp, prev, next, hasRtl, inTagSequence)
                if (issue != null) {
                    if (issue == TextIssue.TAG_CHARS && cp == TAG_CANCEL) inTagSequence = false
                    remove(issue)
                    continue
                }
                if (cp == TAG_CANCEL) inTagSequence = false
            }
            if (o.normalizeSpaces) {
                if (cp in SPECIAL_SPACES) { replace(" ", TextIssue.SPECIAL_SPACES, hidden = true); continue }
                if (cp == 0x2028 || cp == 0x2029) { replace("\n", TextIssue.SPECIAL_SPACES, hidden = true); continue }
            }
            if (o.normalizeDashes) {
                when (cp) {
                    0x2010, 0x2011, 0x2012, 0x2013, 0x2212, 0xFE58, 0xFE63, 0xFF0D -> { replace("-", TextIssue.DASHES, false); continue }
                    0x2014, 0x2015, 0x2E3A, 0x2E3B -> {
                        val tight = prev > 0 && Character.isLetterOrDigit(prev) && next > 0 && Character.isLetterOrDigit(next)
                        replace(if (tight) " - " else "-", TextIssue.DASHES, false); continue
                    }
                }
            }
            if (o.normalizeQuotes) {
                when (cp) {
                    0x201C, 0x201D, 0x201E, 0x201F, 0x2033, 0x301D, 0x301E, 0xFF02 -> { replace("\"", TextIssue.QUOTES, false); continue }
                    0x2018, 0x2019, 0x201A, 0x201B, 0x2032, 0xFF07 -> { replace("'", TextIssue.QUOTES, false); continue }
                }
            }
            if (o.normalizeEllipsis && cp == 0x2026) { replace("...", TextIssue.ELLIPSIS, false); continue }
            if (o.normalizeFancyLetters && isFancy(cp) && next != 0xFE0F) {
                val plain = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC)
                if (plain.isNotEmpty() && plain != String(Character.toChars(cp))) {
                    replace(plain, TextIssue.FANCY_LETTERS, false); continue
                }
            }
            keep()
        }
        return out.toString()
    }

    /** Decides whether [cp] is a hidden character that must go; returns null to keep it. */
    private fun invisibleIssue(cp: Int, prev: Int, next: Int, hasRtl: Boolean, inTagSequence: Boolean): TextIssue? {
        if (cp == 0x0A || cp == 0x09) return null
        val type = Character.getType(cp)
        return when {
            type == Character.CONTROL.toInt() -> TextIssue.CONTROL
            cp in TAG_RANGE || cp == TAG_CANCEL || cp == 0xE0001 ->
                if (inTagSequence && cp != 0xE0001) null else TextIssue.TAG_CHARS
            isVariationSelector(cp) -> if (variationAllowed(cp, prev)) null else TextIssue.VARIATION_SELECTORS
            cp == ZWJ -> if ((isEmojiLike(prev) && isEmojiBase(next)) || joinsComplexScript(prev, next)) null else TextIssue.INVISIBLE
            cp == ZWNJ -> if (joinsComplexScript(prev, next)) null else TextIssue.INVISIBLE
            cp == 0x200E || cp == 0x200F || cp == 0x061C -> if (hasRtl) null else TextIssue.BIDI
            cp in 0x202A..0x202E -> TextIssue.BIDI
            cp in 0x2066..0x2069 -> if (hasRtl) null else TextIssue.BIDI
            cp in ARABIC_PREPENDED -> if (hasRtl) null else TextIssue.INVISIBLE
            cp in 0x180B..0x180D || cp == 0x180F -> if (scriptOf(prev) == Character.UnicodeScript.MONGOLIAN) null else TextIssue.INVISIBLE
            cp in 0x13430..0x1343F -> if (scriptOf(prev) == Character.UnicodeScript.EGYPTIAN_HIEROGLYPHS) null else TextIssue.INVISIBLE
            cp in 0x1BCA0..0x1BCA3 -> if (scriptOf(prev) == Character.UnicodeScript.DUPLOYAN) null else TextIssue.INVISIBLE
            cp in ALWAYS_INVISIBLE -> TextIssue.INVISIBLE
            isDefaultIgnorableReserved(cp) -> TextIssue.INVISIBLE
            isNonCharacter(cp) -> TextIssue.INVISIBLE
            type == Character.PRIVATE_USE.toInt() -> TextIssue.PRIVATE_USE
            type == Character.SURROGATE.toInt() -> TextIssue.INVISIBLE
            type == Character.FORMAT.toInt() -> TextIssue.INVISIBLE
            else -> null
        }
    }

    private const val MAX_PASSES = 4
    private const val ZWJ = 0x200D
    private const val ZWNJ = 0x200C
    private const val BLACK_FLAG = 0x1F3F4
    private const val TAG_CANCEL = 0xE007F
    private val TAG_RANGE = 0xE0020..0xE007E

    /** Subdivision flags (🏴 + 1..7 tag letters/digits + CANCEL TAG) are the only legitimate use of tag characters. */
    private fun isValidFlagTagSequence(cps: IntArray, from: Int): Boolean {
        var i = from
        while (i < cps.size && (cps[i] in 0xE0030..0xE0039 || cps[i] in 0xE0061..0xE007A)) i++
        val tags = i - from
        return tags in 1..7 && i < cps.size && cps[i] == TAG_CANCEL
    }

    private val SPECIAL_SPACES = setOf(0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x202F, 0x205F, 0x3000)

    private val ALWAYS_INVISIBLE: Set<Int> = buildSet {
        addAll(listOf(0x00AD, 0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180E, 0x200B, 0x2065, 0x3164, 0xFEFF, 0xFFA0, 0xFFFC))
        addAll(0x2060..0x2064)
        addAll(0x206A..0x206F)
        addAll(0xFFF0..0xFFFB)
        addAll(0x1D173..0x1D17A)
    }

    private val ARABIC_PREPENDED = setOf(0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605, 0x06DD, 0x070F, 0x0890, 0x0891, 0x08E2, 0x110BD, 0x110CD)

    /** Reserved Default_Ignorable_Code_Point ranges that render as nothing and can smuggle data. */
    private fun isDefaultIgnorableReserved(cp: Int) =
        cp == 0xE0000 || cp in 0xE0002..0xE001F || cp in 0xE0080..0xE00FF || cp in 0xE01F0..0xE0FFF

    private fun isNonCharacter(cp: Int) = cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE

    private fun isVariationSelector(cp: Int) = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    private fun variationAllowed(cp: Int, prev: Int): Boolean {
        if (prev < 0 || isVariationSelector(prev) || prev == ZWJ) return false
        return when (cp) {
            0xFE0E, 0xFE0F -> isEmojiLike(prev) || prev == '#'.code || prev == '*'.code || prev in '0'.code..'9'.code ||
                Character.getType(prev) == Character.OTHER_SYMBOL.toInt() || Character.getType(prev) == Character.MATH_SYMBOL.toInt()
            in 0xE0100..0xE01EF -> scriptOf(prev) == Character.UnicodeScript.HAN
            else -> scriptOf(prev) in STANDARDIZED_VS_SCRIPTS || Character.getType(prev) == Character.MATH_SYMBOL.toInt()
        }
    }

    private val STANDARDIZED_VS_SCRIPTS = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.PHAGS_PA,
        Character.UnicodeScript.MANICHAEAN, Character.UnicodeScript.MONGOLIAN, Character.UnicodeScript.EGYPTIAN_HIEROGLYPHS,
    )

    internal fun isEmojiLike(cp: Int): Boolean = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
        cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF || cp in 0x2190..0x21FF || cp in 0x2900..0x297F ||
        cp in 0x25A0..0x25FF || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F ||
        cp == 0xFE0F || cp == 0x20E3 || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 ||
        cp == 0x2122 || cp == 0x2139 || cp == 0x24C2 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

    /** A code point that can start the next element of a ZWJ sequence (not a modifier or selector). */
    private fun isEmojiBase(cp: Int): Boolean = isEmojiLike(cp) && cp != 0xFE0F && cp != 0x20E3 &&
        cp !in 0x1F3FB..0x1F3FF && cp !in 0xE0020..0xE007F

    private val SIMPLE_SCRIPTS = setOf(
        Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK,
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.ARMENIAN, Character.UnicodeScript.GEORGIAN, Character.UnicodeScript.UNKNOWN,
    )

    /** ZWJ/ZWNJ are meaningful between letters of cursive and Indic scripts (Persian, Hindi, …). */
    private fun joinsComplexScript(prev: Int, next: Int): Boolean {
        if (prev < 0 || next < 0) return false
        if (!isLetterOrMark(prev) || !isLetterOrMark(next)) return false
        return scriptOf(prev) !in SIMPLE_SCRIPTS && scriptOf(next) !in SIMPLE_SCRIPTS
    }

    private fun isLetterOrMark(cp: Int): Boolean {
        val t = Character.getType(cp)
        return Character.isLetter(cp) || t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun scriptOf(cp: Int): Character.UnicodeScript? =
        if (cp < 0) null else runCatching { Character.UnicodeScript.of(cp) }.getOrNull()

    private fun isRtl(cp: Int): Boolean {
        val d = Character.getDirectionality(cp)
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    private fun isFancy(cp: Int): Boolean = cp in 0x1D400..0x1D7FF || cp in 0xFF01..0xFF5E ||
        cp in 0x2460..0x24FF || cp in 0x1F130..0x1F169 || cp in 0xFB00..0xFB06

    // ------------------------------------------------------------------ whitespace

    private val TRAILING_WS = Regex("[ \\t]+$", RegexOption.MULTILINE)
    private val MANY_BLANK_LINES = Regex("\\n{3,}")

    private fun whitespacePass(text: String, o: TextCleanOptions, counter: Counter): String {
        var t = text
        if (o.trimLines) {
            var n = 0
            t = TRAILING_WS.replace(t) { n++; "" }
            val trimmed = t.trim('\n', ' ', '\t')
            if (trimmed != t) n++
            t = trimmed
            counter.add(TextIssue.WHITESPACE, n)
        }
        if (o.collapseBlankLines) {
            var n = 0
            t = MANY_BLANK_LINES.replace(t) { n++; "\n\n" }
            counter.add(TextIssue.WHITESPACE, n)
        }
        return t
    }
}
