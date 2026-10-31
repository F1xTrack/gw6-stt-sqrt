package dev.f1xtrack.gw6voice.render

import android.graphics.Canvas
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import com.caverock.androidsvg.SVG
import dev.f1xtrack.gw6voice.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.*
import org.commonmark.parser.Parser
import ru.noties.jlatexmath.JLatexMathDrawable

object FormulaRenderer {
    private val cache = android.util.LruCache<String, JLatexMathDrawable>(64)
    @Synchronized fun drawable(latex: String, size: Float): JLatexMathDrawable {
        val normalized = MathExpressions.normalize(latex)
        val key = "$size:$normalized"
        return cache.get(key) ?: JLatexMathDrawable.builder(normalized)
            .textSize(size).color(Color.rgb(236, 243, 248)).padding(2).build().also { cache.put(key, it) }
    }

    fun validate(document: AnswerDocument): AnswerDocument = document.copy(blocks = document.blocks.map { block ->
        if (block.type == "math") runCatching { drawable(block.content, 20f); block }
            .getOrElse { AnswerBlock("error", "Формула не отображена. Откройте исходный ответ на телефоне.") }
        else block
    })
    fun fitted(latex: String, size: Float, width: Float): JLatexMathDrawable {
        val original = drawable(latex, size)
        if (original.intrinsicWidth <= width) return original
        val parts = MathLines.pieces(latex)
        if (parts.size < 2) return original
        val rows = mutableListOf<String>(); var line = parts.first()
        for (part in parts.drop(1)) {
            if (drawable(line + part, size).intrinsicWidth <= width) line += part
            else { rows += line; line = part }
        }
        rows += line
        return runCatching { drawable("\\begin{aligned}" + rows.joinToString("\\\\") { "&$it" } + "\\end{aligned}", size) }.getOrDefault(original)
    }
}

@Composable
fun AnswerView(document: AnswerDocument, modifier: Modifier = Modifier, compact: Boolean = false, onOverflow: () -> Unit = {}) {
    var prepared by remember(document, compact) { mutableStateOf<AnswerDocument?>(null) }
    var measuredWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(document, compact) { prepared = withContext(Dispatchers.Default) { FormulaRenderer.validate(document) } }
    AndroidView(modifier = modifier.onSizeChanged { measuredWidth = it.width }, factory = { context -> LinearLayout(context).apply { orientation = LinearLayout.VERTICAL } }, update = { layout ->
        layout.removeAllViews()
        val context = layout.context
        val density = context.resources.displayMetrics.density
        val scaled = context.resources.displayMetrics.scaledDensity
        val bodySize = if (compact) 15f else 18f
        val available = if (measuredWidth > 0) measuredWidth.toFloat() else ((context.resources.displayMetrics.widthPixels / density) - if (compact) 56 else 40).coerceAtLeast(120f) * density
        val inline = mutableListOf<AnswerBlock>()
        fun textView() = TextView(context).apply {
            textSize = bodySize; setTextColor(Color.rgb(236, 243, 248)); setLineSpacing(3 * density, 1.05f)
            setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
        }
        fun flush() {
            if (inline.isEmpty()) return
            val spans = SpannableStringBuilder()
            inline.forEach { block ->
                if (block.type == "text") spans.append(MarkdownSpans.render(block.content))
                else {
                    val d = FormulaRenderer.drawable(block.content, bodySize * scaled)
                    if (d.intrinsicWidth > available * .85f) {
                        if (spans.isNotEmpty()) { layout.addView(textView().apply { text = SpannableStringBuilder(spans) }); spans.clear() }
                        val view = FormulaView(context, block.content, available, compact, onOverflow)
                        layout.addView(view)
                    } else {
                        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
                        val at = spans.length; spans.append("\uFFFC")
                        spans.setSpan(ImageSpan(d, ImageSpan.ALIGN_BASELINE), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            if (spans.isNotEmpty()) layout.addView(textView().apply { text = spans }); inline.clear()
        }
        (prepared ?: AnswerDocument(blocks = listOf(AnswerBlock("text", "Подготовка формул…")))).blocks.forEach { block ->
            when {
                block.type == "text" || block.type == "math" && !block.display -> inline += block
                block.type == "math" -> { flush(); layout.addView(FormulaView(context, block.content, available, compact, onOverflow)) }
                block.type == "svg" -> { flush(); layout.addView(SvgView(context, block.content)); if (block.caption.isNotBlank()) layout.addView(textView().apply { text = block.caption }) }
                else -> { flush(); layout.addView(textView().apply { text = block.content; setTextColor(Color.rgb(255, 193, 135)) }) }
            }
        }
        flush()
    })
}

private class FormulaView(context: android.content.Context, latex: String, width: Float, compact: Boolean, overflow: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.scaledDensity
    private val drawable = FormulaRenderer.fitted(latex, (if (compact) 19f else 23f) * density, width)
    private val fit = minOf(1f, width / drawable.intrinsicWidth.coerceAtLeast(1))
    private val readable = fit * (if (compact) 19 else 23) >= 13
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 135); textSize = 13 * density }
    init { contentDescription = "Формула: $latex"; if (!readable) setOnClickListener { overflow() } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((drawable.intrinsicWidth * fit).toInt(), widthMeasureSpec), ((if (readable) drawable.intrinsicHeight * fit else 40 * density) + 12 * density).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        if (!readable) { canvas.drawText("Формула целиком — на телефоне", 0f, 22 * density, paint); return }
        val saved = canvas.save(); canvas.translate(0f, 5 * density); canvas.scale(fit, fit)
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight); drawable.draw(canvas); canvas.restoreToCount(saved)
    }
}

private class SvgView(context: android.content.Context, source: String) : View(context) {
    private val svg = runCatching { SVG.getFromString(SafeSvg.validate(source)) }.getOrNull()
    init { contentDescription = "Векторный рисунок" }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val box = svg?.documentViewBox
        val ratio = if (box != null) box.height() / box.width() else .8f
        setMeasuredDimension(width, (width * ratio).toInt().coerceIn(64, width * 2))
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(16, 24, 32))
        svg?.renderToCanvas(canvas, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()))
    }
}

private object MarkdownSpans {
    private val parser = Parser.builder().build()
    fun render(markdown: String): CharSequence {
        val text = SpannableStringBuilder()
        fun children(node: Node) { var child = node.firstChild; while (child != null) { visit(child, text); child = child.next } }
        // This nested visitor handles inline styles without loading links or HTML.
        fun renderNode(node: Node) { children(node) }
        renderNode(parser.parse(markdown))
        return text
    }
    private fun visit(node: Node, text: SpannableStringBuilder) {
        val start = text.length
        fun children() { var c = node.firstChild; while (c != null) { visit(c, text); c = c.next } }
        when (node) {
            is Text -> text.append(node.literal)
            is Code -> { text.append(node.literal); text.setSpan(TypefaceSpan("monospace"), start, text.length, 33) }
            is SoftLineBreak, is HardLineBreak -> text.append('\n')
            is FencedCodeBlock -> { text.append(node.literal); text.setSpan(TypefaceSpan("monospace"), start, text.length, 33) }
            is ListItem -> { text.append("• "); children(); text.append('\n') }
            is Paragraph, is Heading -> { children(); if (node.next != null) text.append("\n\n") }
            is HtmlInline, is HtmlBlock -> Unit
            else -> children()
        }
        if (text.length > start) when (node) {
            is StrongEmphasis, is Heading -> text.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, text.length, 33)
            is Emphasis -> text.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, text.length, 33)
            else -> Unit
        }
    }
}
