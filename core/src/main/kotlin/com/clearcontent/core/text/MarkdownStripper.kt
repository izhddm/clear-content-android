package com.clearcontent.core.text

/** Converts chatbot Markdown into plain text that reads well where Markdown is not rendered (Instagram, SMS). */
internal object MarkdownStripper {

    private val FENCE = Regex("^\\s{0,3}(```|~~~).*$")
    private val HORIZONTAL_RULE = Regex("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)(?:\\s+#+)?\\s*$")
    private val SETEXT_UNDERLINE = Regex("^\\s{0,3}(=+|-+)\\s*$")
    private val BLOCKQUOTE = Regex("^\\s{0,3}>\\s?")
    private val TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])\\]\\s+")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
    private val TABLE_ROW = Regex("^\\s*\\|(.*)\\|\\s*$")

    private val INLINE: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)") to { _ -> "" },
        Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)") to { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (label == url || url.startsWith("#")) label else "$label ($url)"
        },
        Regex("<(https?://[^>\\s]+)>") to { m -> m.groupValues[1] },
        Regex("``\\s?([^`\\n]+?)\\s?``") to { m -> m.groupValues[1] },
        Regex("`([^`\\n]+)`") to { m -> m.groupValues[1] },
        Regex("(\\*\\*\\*|___)(?=\\S)(.+?)(?<=\\S)\\1") to { m -> m.groupValues[2] },
        Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1") to { m -> m.groupValues[2] },
        Regex("(?<![\\w*])\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*(?![\\w*])") to { m -> m.groupValues[1] },
        Regex("(?<![\\w_])_(?=[^\\s_])(.+?)(?<=[^\\s_])_(?![\\w_])") to { m -> m.groupValues[1] },
        Regex("~~(?=\\S)(.+?)(?<=\\S)~~") to { m -> m.groupValues[1] },
        Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~|])") to { m -> m.groupValues[1] },
    )

    fun strip(text: String, counter: TextSanitizer.Counter): String {
        var changes = 0
        val out = ArrayList<String>()
        var inFence = false
        val lines = text.split('\n')
        for ((index, raw) in lines.withIndex()) {
            if (FENCE.matches(raw)) {
                inFence = !inFence
                changes++
                continue
            }
            if (inFence) {
                out += raw
                continue
            }
            if (HORIZONTAL_RULE.matches(raw) || TABLE_SEPARATOR.matches(raw)) {
                changes++
                continue
            }
            // "Title\n=====" style headings: drop the underline when the previous line has text.
            if (SETEXT_UNDERLINE.matches(raw) && index > 0 && lines[index - 1].isNotBlank() && raw.trim().length >= 3) {
                changes++
                continue
            }
            var line = raw
            HEADING.matchEntire(line)?.let { line = it.groupValues[1]; changes++ }
            if (BLOCKQUOTE.containsMatchIn(line)) {
                line = BLOCKQUOTE.replaceFirst(line, ""); changes++
            }
            TASK.find(line)?.let { m ->
                val mark = if (m.groupValues[2].isBlank()) "☐ " else "☑ "
                line = m.groupValues[1] + mark + line.substring(m.range.last + 1); changes++
            } ?: BULLET.find(line)?.let { m ->
                line = m.groupValues[1] + "• " + line.substring(m.range.last + 1); changes++
            }
            TABLE_ROW.matchEntire(line)?.let { m ->
                line = m.groupValues[1].split('|').joinToString(" | ") { it.trim() }; changes++
            }
            for ((re, transform) in INLINE) {
                line = re.replace(line) { changes++; transform(it) }
            }
            out += line
        }
        counter.add(TextIssue.MARKDOWN, changes)
        return out.joinToString("\n")
    }
}
