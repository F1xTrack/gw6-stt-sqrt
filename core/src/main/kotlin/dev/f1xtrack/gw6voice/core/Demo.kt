package dev.f1xtrack.gw6voice.core

object Demo {
    val source = """
        **Результат:** корень из 18 равен \\(3\\sqrt{2}\\).

        Короткие шаги:
        \\[\\sqrt{18}=\\sqrt{9\\cdot2}=3\\sqrt{2}\\]
        \\[x=\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}\\]
        \\[\\int_0^1 x^2\\,dx=\\frac{1}{3}\\]
        \\[A=\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}\\]

        Вложенные корни: ${'$'}sqrt(1+sqrt(4))${'$'}.

        График квадратичной функции:
        ```svg
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 260"><rect width="320" height="260" fill="#101820"/><path d="M24 220H300 M160 236V18" fill="none" stroke="#dbe8ef" stroke-width="2"/><path d="M40 40 Q160 400 280 40" fill="none" stroke="#80e7cd" stroke-width="4"/><text x="276" y="246" fill="#dbe8ef" font-size="20">x</text><text x="174" y="28" fill="#dbe8ef" font-size="20">y</text><text x="196" y="74" fill="#80e7cd" font-size="22">y = x²</text></svg>
        ```
    """.trimIndent().replace("\\\\", "\\")
    val document get() = AnswerParser.parse(source)
}
