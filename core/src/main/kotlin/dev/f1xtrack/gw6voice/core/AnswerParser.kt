package dev.f1xtrack.gw6voice.core

/** Delimiters are scanned outside code; SVG is only accepted in a dedicated fenced block. */
object AnswerParser {
    fun parse(source: String): AnswerDocument {
        require(source.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_REPLY) { "Ответ слишком большой" }
        val blocks = mutableListOf<AnswerBlock>()
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { blocks += AnswerBlock("text", text.toString()); text.clear() } }
        var at = 0
        while (at < source.length) {
            if ((at == 0 || source[at - 1] == '\n') && source.startsWith("```", at)) {
                val endLine = source.indexOf('\n', at)
                if (endLine < 0) { text.append(source.substring(at)); break }
                val language = source.substring(at + 3, endLine).trim().lowercase()
                var endFence = source.indexOf("\n```", endLine)
                while (endFence >= 0 && source.substring(endFence + 4).takeWhile { it != '\n' }.isNotBlank()) endFence = source.indexOf("\n```", endFence + 4)
                if (endFence < 0) { text.append(source.substring(at)); break }
                val content = source.substring(endLine + 1, endFence).trim()
                val end = endFence + 4
                when (language) {
                    "svg", "image/svg+xml" -> { flush(); blocks += svg(content) }
                    "math", "latex", "tex" -> { flush(); blocks += math(content, true) }
                    else -> text.append(source.substring(at, end))
                }
                at = end; continue
            }
            if (source[at] == '`') {
                val end = source.indexOf('`', at + 1)
                if (end >= 0) { text.append(source.substring(at, end + 1)); at = end + 1; continue }
            }
            val delimiter = when {
                source.startsWith("\\[", at) -> "\\[" to "\\]"
                source.startsWith("\\(", at) -> "\\(" to "\\)"
                source.startsWith("$$", at) -> "$$" to "$$"
                source[at] == '$' && (at == 0 || source[at - 1] != '\\') -> "$" to "$"
                else -> null
            }
            if (delimiter != null) {
                val start = at + delimiter.first.length
                var end = source.indexOf(delimiter.second, start)
                while (end > 0 && source[end - 1] == '\\' && delimiter.second.startsWith('$')) end = source.indexOf(delimiter.second, end + delimiter.second.length)
                if (end > start) {
                    val content = source.substring(start, end)
                    if (delimiter.first != "$" || !content.contains('\n')) {
                        flush(); blocks += math(content, delimiter.first in setOf("$$", "\\[")); at = end + delimiter.second.length; continue
                    }
                }
                if (end < 0 && delimiter.first != "$") {
                    flush(); blocks += AnswerBlock("error", "У формулы нет закрывающего разделителя"); break
                }
            }
            // Some compatible models forget math delimiters for sqrt(...). Parse only
            // this unambiguous function outside code, preserving nested parentheses.
            if (source.startsWith("sqrt(", at) && (at == 0 || !source[at - 1].isLetterOrDigit())) {
                var depth = 0; var end = at + 4
                while (end < source.length) {
                    if (source[end] == '(') depth++
                    if (source[end] == ')' && --depth == 0) break
                    end++
                }
                if (end < source.length) { flush(); blocks += math(source.substring(at, end + 1), false); at = end + 1; continue }
            }
            text.append(source[at++])
        }
        flush()
        require(blocks.size <= 256) { "Слишком много блоков ответа" }
        return AnswerDocument(blocks = blocks)
    }
    private fun math(source: String, display: Boolean) = runCatching {
        AnswerBlock("math", MathExpressions.normalize(source), display)
    }.getOrElse { AnswerBlock("error", "Формула не отображена: ${it.message}") }
    private fun svg(source: String) = runCatching { AnswerBlock("svg", SafeSvg.validate(source)) }
        .getOrElse { AnswerBlock("error", "Рисунок не отображён: ${it.message}") }
}
