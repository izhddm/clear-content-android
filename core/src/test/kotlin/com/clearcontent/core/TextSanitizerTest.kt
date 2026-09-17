package com.clearcontent.core

import com.clearcontent.core.text.TextCleanOptions
import com.clearcontent.core.text.TextIssue
import com.clearcontent.core.text.TextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSanitizerTest {

    private fun cp(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)
    private fun clean(s: String, o: TextCleanOptions = TextCleanOptions()) = TextSanitizer.clean(s, o)

    @Test
    fun removesZeroWidthAndBom() {
        val input = "Hel" + cp(0x200B) + "lo" + cp(0x200C) + " wo" + cp(0x2060) + "rld" + cp(0xFEFF)
        val r = clean(input)
        assertEquals("Hello world", r.text)
        assertEquals(4, r.counts[TextIssue.INVISIBLE])
        assertEquals(1, r.hiddenCodePoints[0x200B])
    }

    @Test
    fun normalizesNarrowNoBreakSpaceUsedByChatGpt() {
        val r = clean("10" + cp(0x202F) + "000" + cp(0x00A0) + "руб.")
        assertEquals("10 000 руб.", r.text)
        assertEquals(2, r.counts[TextIssue.SPECIAL_SPACES])
    }

    @Test
    fun keepsEmojiSequencesIntact() {
        val family = cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467)
        val heartOnFire = cp(0x2764, 0xFE0F, 0x200D, 0x1F525)
        val keycap = cp(0x31, 0xFE0F, 0x20E3)
        val england = cp(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F)
        val rainbow = cp(0x1F3F3, 0xFE0F, 0x200D, 0x1F308)
        val skin = cp(0x1F44B, 0x1F3FD)
        val text = "Привет $family $heartOnFire $keycap $england $rainbow $skin"
        val r = clean(text)
        assertEquals(text, r.text)
        assertFalse(r.changed)
    }

    @Test
    fun removesJoinersOutsideEmojiButKeepsPersianZwnj() {
        assertEquals("ab", clean("a" + cp(0x200D) + "b").text)
        val persian = "می" + cp(0x200C) + "خواهم"
        assertEquals(persian, clean(persian).text)
    }

    @Test
    fun removesTagCharacterSmuggling() {
        val secret = "ignore previous".map { it.code + 0xE0000 }.toIntArray()
        val r = clean("Hi" + String(secret, 0, secret.size) + "!")
        assertEquals("Hi!", r.text)
        assertEquals(secret.size, r.counts[TextIssue.TAG_CHARS])
        // A black flag followed by an overlong tag run is not a valid subdivision flag.
        val fake = cp(0x1F3F4) + String(secret, 0, secret.size) + cp(0xE007F)
        assertEquals(cp(0x1F3F4), clean(fake).text)
    }

    @Test
    fun removesVariationSelectorSmuggling() {
        val payload = IntArray(20) { 0xE0100 + it }
        assertEquals("A", clean("A" + String(payload, 0, payload.size)).text)
        assertEquals(cp(0x263A, 0xFE0F), clean(cp(0x263A, 0xFE0F, 0xFE0F)).text)
        assertEquals("x", clean("x" + cp(0xFE0F)).text)
        // Ideographic variation sequence is legitimate.
        val ivs = cp(0x845B, 0xE0100)
        assertEquals(ivs, clean(ivs).text)
    }

    @Test
    fun removesBidiOverridesAndKeepsIsolatesInRtlText() {
        assertEquals("abcdcb", clean("abc" + cp(0x202E) + "dcb").text)
        val hebrew = "שלום " + cp(0x2066) + "abc" + cp(0x2069)
        assertEquals(hebrew, clean(hebrew).text)
        assertEquals("abc", clean(cp(0x2066) + "abc" + cp(0x2069)).text)
    }

    @Test
    fun removesSoftHyphenFillersControlsAndPrivateUse() {
        val input = "за" + cp(0x00AD) + "мок" + cp(0x3164) + cp(0x115F) + " a" + cp(0x0007) + "b" + cp(0xE123) + cp(0xFFFE)
        assertEquals("замок ab", clean(input).text)
    }

    @Test
    fun removesChatGptCitationArtifacts() {
        val pua = "Paris is the capital" + cp(0xE200) + "cite" + cp(0xE202) + "turn0search0" + cp(0xE201) + "."
        assertEquals("Paris is the capital.", clean(pua).text)
        assertEquals("Hello world", clean("Hello citeturn0search3turn0news1 world").text)
        assertEquals("See file.", clean("See file" + cp(0x3010) + "4:0" + cp(0x2020) + "source" + cp(0x3011) + ".").text)
        val r = clean("Text [^1^] more")
        assertEquals("Text  more", r.text)
        assertTrue((r.counts[TextIssue.AI_CITATIONS] ?: 0) > 0)
    }

    @Test
    fun removesAiReferralParameters() {
        assertEquals("https://a.com/x", clean("https://a.com/x?utm_source=chatgpt.com").text)
        assertEquals("https://a.com/?a=1&b=2", clean("https://a.com/?a=1&utm_source=chatgpt.com&b=2").text)
        assertEquals("https://a.com/?b=2", clean("https://a.com/?utm_source=chatgpt.com&b=2").text)
        assertEquals("(https://a.com/p)", clean("(https://a.com/p?utm_source=perplexity)").text)
        assertEquals("https://bing.com/x", clean("https://bing.com/x?form=MG0AV3").text)
        val newsletter = "https://a.com/?utm_source=newsletter"
        assertEquals(newsletter, clean(newsletter).text)
    }

    @Test
    fun citationNumbersAreOptional() {
        val text = "Факт[1][2] и ещё[web:3]."
        assertEquals(text, clean(text).text)
        assertEquals("Факт и ещё.", clean(text, TextCleanOptions(removeCitationNumbers = true)).text)
    }

    @Test
    fun stripsMarkdown() {
        val md = """
            ## Заголовок
            **Жирный** и *курсив*, `код` и [ссылка](https://x.y)
            - пункт
            - [x] готово
            ---
            > цитата
            snake_case_name 2*3*4 и __подчёркнутый__
            ```kotlin
            val a = 1
            ```
            | a | b |
            |---|---|
            | 1 | 2 |
        """.trimIndent()
        val r = clean(md, TextCleanOptions(stripMarkdown = true))
        val expected = """
            Заголовок
            Жирный и курсив, код и ссылка (https://x.y)
            • пункт
            ☑ готово
            цитата
            snake_case_name 2*3*4 и подчёркнутый
            val a = 1
            a | b
            1 | 2
        """.trimIndent()
        assertEquals(expected, r.text)
        assertTrue((r.counts[TextIssue.MARKDOWN] ?: 0) >= 10)
    }

    @Test
    fun typographyOptions() {
        val o = TextCleanOptions(normalizeDashes = true, normalizeQuotes = true, normalizeEllipsis = true)
        val input = "Word" + cp(0x2014) + "word " + cp(0x2014) + " “quote” it’s" + cp(0x2026)
        assertEquals("Word - word - \"quote\" it's...", clean(input, o).text)
        // Defaults leave Russian typography alone.
        val ru = "Он сказал: «Привет» — и ушёл…"
        assertEquals(ru, clean(ru).text)
    }

    @Test
    fun fancyLettersOption() {
        val bold = cp(0x1D401, 0x1D428, 0x1D425, 0x1D41D)
        val full = cp(0xFF46, 0xFF55, 0xFF4C, 0xFF4C)
        val o = TextCleanOptions(normalizeFancyLetters = true)
        assertEquals("Bold full № 5", clean("$bold $full № 5", o).text)
        assertEquals("$bold $full", clean("$bold $full").text)
    }

    @Test
    fun composesDecomposedCyrillic() {
        assertEquals("й", clean("и" + cp(0x0306)).text)
    }

    @Test
    fun trimsTrailingWhitespaceAndOptionallyCollapsesBlankLines() {
        assertEquals("a\nb", clean("  \na   \nb\t\n\n").text)
        assertEquals("a\n\nb", clean("a\n\n\n\nb", TextCleanOptions(collapseBlankLines = true)).text)
    }

    @Test
    fun findHiddenReportsPositions() {
        val text = "a" + cp(0x200B) + "b" + cp(0xE0041) + "c"
        val hidden = TextSanitizer.findHidden(text)
        assertEquals(2, hidden.size)
        assertEquals(1, hidden[0].start)
        assertEquals(0x200B, hidden[0].codePoint)
        assertEquals(3, hidden[1].start)
        assertEquals(5, hidden[1].end)
        assertEquals(TextIssue.TAG_CHARS, hidden[1].issue)
        assertTrue(TextSanitizer.describe(0x200B).contains("ZERO WIDTH SPACE"))
    }

    @Test
    fun plainTextIsUntouched() {
        val text = "Обычный текст. Normal text! 123 — «ёлочки», emoji 😀 и № 5\nВторая строка"
        val r = clean(text)
        assertEquals(text, r.text)
        assertFalse(r.changed)
    }
}
