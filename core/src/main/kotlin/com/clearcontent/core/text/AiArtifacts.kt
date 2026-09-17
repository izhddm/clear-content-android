package com.clearcontent.core.text

/** Chatbot-specific leftovers that survive copy/paste. */
internal object AiArtifacts {

    private const val TURN_KINDS = "search|news|view|fetch|file|image|product|forecast|finance|sports|time|calc|academia|reddit|youtube|video|map|place"

    private val CITATIONS = listOf(
        // ChatGPT: private-use delimited tokens, e.g. U+E200 "cite" U+E202 "turn0search0" U+E201.
        Regex("[ \\t]*\\uE200[^\\uE201\\n]{0,400}\\uE201"),
        // ChatGPT when the private-use delimiters were already dropped by the target app.
        Regex("[ \\t]*(?:cite|filecite|navlist|image_group|entity|product_entity|genui|video_group|forecast|finance|sports|schedule)?(?:turn\\d+(?:$TURN_KINDS)\\d+(?:L\\d+-L\\d+)?)+"),
        // OpenAI Assistants file_search: "【4:0†source】".
        Regex("[ \\t]*\\u3010\\d+(?::\\d+)?\\u2020[^\\u3011\\n]{0,200}\\u3011"),
        // Copilot / Bing chat footnote markers: "[^1^]".
        Regex("\\[\\^\\d{1,3}\\^\\]"),
    )

    private val CITATION_NUMBERS = Regex("(?<=\\S)(?:\\[(?:\\d{1,3}|(?:web|x_post|post|source|news|cite|ref):\\s?\\d{1,3})\\])+")

    private const val AI_REFERRERS = "chatgpt\\.com|chat\\.openai\\.com|openai(?:\\.com)?|perplexity(?:\\.ai)?|copilot\\.com|copilot|" +
        "bing\\.com/chat|chat\\.deepseek\\.com|deepseek|gemini(?:\\.google\\.com)?|bard|claude(?:\\.ai)?|anthropic|" +
        "grok(?:\\.com)?|x\\.ai|you\\.com|phind(?:\\.com)?|mistral(?:\\.ai)?|chat\\.mistral\\.ai|meta\\.ai|poe(?:\\.com)?|kimi(?:\\.ai)?|chat\\.qwen\\.ai|qwen"

    // Group 1: leading "?" or "&"; group 2: trailing "&" when more parameters follow.
    private val TRACKING = Regex(
        "([?&])(?:utm_source=(?:$AI_REFERRERS)(?:%2F|/)?|form=MG0AV3|ref=(?:chatgpt|perplexity|copilot|gemini|claude))(?=[&#\\s)\\]>\"'.,;!]|$)(&?)",
        RegexOption.IGNORE_CASE,
    )

    fun removeCitations(text: String, counter: TextSanitizer.Counter): String {
        var t = text
        for (re in CITATIONS) {
            var n = 0
            t = re.replace(t) { n++; "" }
            counter.add(TextIssue.AI_CITATIONS, n)
        }
        return t
    }

    fun removeTracking(text: String, counter: TextSanitizer.Counter): String {
        var n = 0
        val t = TRACKING.replace(text) { m ->
            n++
            if (m.groupValues[2] == "&") m.groupValues[1] else ""
        }
        counter.add(TextIssue.TRACKING, n)
        return t
    }

    fun removeCitationNumbers(text: String, counter: TextSanitizer.Counter): String {
        var n = 0
        val t = CITATION_NUMBERS.replace(text) { n++; "" }
        counter.add(TextIssue.CITATION_NUMBERS, n)
        return t
    }
}
