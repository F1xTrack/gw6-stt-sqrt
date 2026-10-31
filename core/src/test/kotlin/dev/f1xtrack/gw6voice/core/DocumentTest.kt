package dev.f1xtrack.gw6voice.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class DocumentTest {
    @Test fun nestedRootsPreserveScope() { assertEquals("\\sqrt{1+\\sqrt{4}}", MathExpressions.normalize("sqrt(1+sqrt(4))")) }
    @Test fun divisionAndPowerAreStructured() { assertEquals("\\sqrt{\\frac{1}{2}+{x}^{2}}", MathExpressions.normalize("sqrt(1/2+x^2)")) }
    @Test fun negativePowerPreservesPrecedence() { assertEquals("\\sqrt{-{x}^{2}}", MathExpressions.normalize("sqrt(-x^2)")) }
    @Test fun rootAndFractionFunctions() { assertEquals("\\sqrt[3]{\\frac{1+\\sqrt{4}}{2}}", MathExpressions.normalize("root(3,frac(1+sqrt(4),2))")) }
    @Test fun nativeLatexIsPreserved() { val formula = "\\frac{a}{\\sqrt{b}}"; assertEquals(formula, MathExpressions.normalize(formula)) }
    @Test fun malformedRootFails() { assertThrows(IllegalArgumentException::class.java) { MathExpressions.normalize("sqrt(1+)") } }
    @Test fun expansionCommandsAreRejected() { assertThrows(IllegalArgumentException::class.java) { MathExpressions.normalize("\\newcommand{\\x}{x}") } }
    @Test fun textAndInlineMathAreSeparate() {
        val result = AnswerParser.parse("Ответ \\(\\sqrt{2}\\), затем ${'$'}x^2${'$'}.")
        assertEquals(listOf("text", "math", "text", "math", "text"), result.blocks.map { it.type })
        assertFalse(result.blocks[1].display)
    }
    @Test fun displayMathIsMarked() { assertTrue(AnswerParser.parse("\\[x=1\\]").blocks.single().display) }
    @Test fun codeIsNeverConverted() {
        val code = "`sqrt(4)`\n```python\nx = sqrt(2)\n```"
        assertEquals(code, AnswerParser.parse(code).blocks.single().content)
    }
    @Test fun bareRootGetsTypesetting() { assertEquals("\\sqrt{1+\\sqrt{4}}", AnswerParser.parse("sqrt(1+sqrt(4))").blocks.single().content) }
    @Test fun badMathDoesNotDestroyText() { assertEquals(listOf("text", "error", "text"), AnswerParser.parse("До ${'$'}sqrt()${'$'} после").blocks.map { it.type }) }
    @Test fun svgIsASeparateBlock() {
        val result = AnswerParser.parse("График\n```svg\n$SVG\n```\nКонец")
        assertEquals(listOf("text", "svg", "text"), result.blocks.map { it.type })
    }
    @Test fun badSvgDoesNotDestroyAnswer() { assertTrue(AnswerParser.parse("Да\n```svg\n<svg/>\n```").blocks.any { it.type == "error" }) }
    @Test fun hugeReplyIsRejected() { assertThrows(IllegalArgumentException::class.java) { AnswerParser.parse("x".repeat(Protocol.MAX_REPLY + 1)) } }
    @Test fun demoContainsValidFormulaAndSvg() { val document = Demo.document; assertFalse(document.blocks.any { it.type == "error" }); assertTrue(document.blocks.any { it.type == "svg" }); assertTrue(document.blocks.count { it.type == "math" } >= 5) }
    @Test fun documentRoundTrip() { val doc = Demo.document; assertEquals(doc, WireJson.decodeFromString<AnswerDocument>(WireJson.encodeToString(doc))) }
    @Test fun requestRejectsPathTraversal() { assertThrows(IllegalArgumentException::class.java) { VoiceRequest(id = "../secret", conversationId = Protocol.id()).validate() } }
    @Test fun protocolRejectsFutureVersion() { assertThrows(IllegalArgumentException::class.java) { VoiceRequest(version = 99, conversationId = Protocol.id()).validate() } }
    @Test fun validRequestSurvivesSerialization() { val request = VoiceRequest(conversationId = Protocol.id()); request.validate(); assertEquals(request, WireJson.decodeFromString<VoiceRequest>(WireJson.encodeToString(request))) }
    @Test fun contextKeepsOnlyWholeRecentTurns() {
        val turns = (1..20).map { Turn("question $it", "x".repeat(100)) }
        val result = Conversation.context(turns, 1000)
        assertEquals(turns.last(), result.last()); assertTrue(result.sumOf { it.question.length + it.answer.length } <= 1000)
    }
    @Test fun contextCapsTurns() { assertEquals(12, Conversation.context((1..40).map { Turn("q", "a") }, 24000).size) }
    @Test fun lineBreaksNeverSplitRootsOrPowers() { assertEquals(listOf("x", "=\\sqrt{a+b}", "+y^{-2}"), MathLines.pieces("x=\\sqrt{a+b}+y^{-2}")) }
    @Test fun unmatchedLatexDelimiterIsARecoverableBlock() { assertTrue(AnswerParser.parse("До \\(x^2").blocks.any { it.type == "error" }) }
    companion object { const val SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 320 260\"><path d=\"M0 0L40 40\" fill=\"none\" stroke=\"#80e7cd\"/></svg>" }
}
