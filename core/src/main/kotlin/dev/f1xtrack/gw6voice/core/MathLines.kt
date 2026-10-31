package dev.f1xtrack.gw6voice.core

object MathLines {
    /** Breaks only at visible top-level relations/operators; never inside roots or fractions. */
    fun pieces(latex: String): List<String> {
        if (latex.contains("\\begin") || latex.contains("\\left") || latex.contains("\\right")) return listOf(latex)
        var braces = 0; var parentheses = 0; var start = 0
        val result = mutableListOf<String>()
        latex.forEachIndexed { at, char ->
            when (char) { '{' -> braces++; '}' -> braces--; '(' , '[' -> parentheses++; ')', ']' -> parentheses-- }
            if (at > start && braces == 0 && parentheses == 0 && char in "=+-" && latex.getOrNull(at - 1) != '\\' && latex.getOrNull(at - 1) !in listOf('^', '_', '+', '-', '=')) {
                result += latex.substring(start, at); start = at
            }
        }
        result += latex.substring(start)
        return result.filter { it.isNotBlank() }
    }
}
