package dev.f1xtrack.gw6voice.core

/** A small expression grammar; never evaluates model code or replaces characters blindly. */
object MathExpressions {
    fun normalize(source: String): String {
        val value = source.trim().removePrefix("$$").removeSuffix("$$").trim()
        require(value.length <= 8192) { "Слишком большая формула" }
        require(!Regex("\\\\(newcommand|renewcommand|def|input|include|href|url|require|loop|csname)\\b").containsMatchIn(value)) { "Недопустимая команда формулы" }
        require(value.count { it == '{' || it == '(' } < 128) { "Слишком сложная формула" }
        return if (!value.contains('\\') && Regex("\\b(sqrt|root|frac)\\s*\\(").containsMatchIn(value)) {
            runCatching { Parser(value).parse() }.getOrElse { throw IllegalArgumentException("Не удалось разобрать математическое выражение") }
        } else value
    }

    private class Parser(private val input: String) {
        private var at = 0
        fun parse(): String { val result = expression(); skip(); require(at == input.length); return result }
        private fun skip() { while (at < input.length && input[at].isWhitespace()) at++ }
        private fun take(c: Char): Boolean { skip(); return if (input.getOrNull(at) == c) { at++; true } else false }
        private fun expression(): String {
            var out = product()
            while (true) out = when { take('+') -> "$out+${product()}"; take('-') -> "$out-${product()}"; take('=') -> "$out=${product()}"; else -> return out }
        }
        private fun product(): String {
            var out = unary()
            while (true) out = when { take('*') -> "$out\\cdot ${unary()}"; take('/') -> "\\frac{$out}{${unary()}}"; else -> return out }
        }
        private fun unary(): String = when { take('-') -> "-${unary()}"; take('+') -> "+${unary()}"; else -> power() }
        private fun power(): String {
            val left = atom()
            return if (take('^')) "{$left}^{${unary()}}" else left
        }
        private fun atom(): String {
            if (take('(')) { val inner = expression(); require(take(')')); return "\\left($inner\\right)" }
            skip()
            val start = at
            while (input.getOrNull(at)?.let { it.isLetterOrDigit() || it == '.' || it == '_' } == true) at++
            require(at > start)
            val word = input.substring(start, at)
            if (word in setOf("sqrt", "root", "frac")) {
                require(take('(')); val first = expression()
                return when (word) {
                    "sqrt" -> { require(take(')')); "\\sqrt{$first}" }
                    "root" -> { require(take(',')); val second = expression(); require(take(')')); "\\sqrt[$first]{$second}" }
                    else -> { require(take(',')); val second = expression(); require(take(')')); "\\frac{$first}{$second}" }
                }
            }
            require(word.all { it.isLetterOrDigit() || it == '.' || it == '_' })
            return when (word) { "pi" -> "\\pi"; "theta" -> "\\theta"; else -> word }
        }
    }
}
