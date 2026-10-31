package dev.f1xtrack.gw6voice.core

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

object SafeSvg {
    private val tags = setOf("svg", "g", "defs", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "tspan", "title", "desc", "marker", "clipPath", "linearGradient", "radialGradient", "stop", "use")
    private val attrs = setOf("xmlns", "xmlns:xlink", "viewBox", "width", "height", "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry", "d", "points", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "stroke-dasharray", "stroke-dashoffset", "stroke-miterlimit", "fill-rule", "clip-rule", "fill-opacity", "stroke-opacity", "opacity", "transform", "id", "font-family", "font-size", "font-weight", "font-style", "text-anchor", "dominant-baseline", "dx", "dy", "marker-start", "marker-mid", "marker-end", "markerWidth", "markerHeight", "refX", "refY", "orient", "markerUnits", "clip-path", "clipPathUnits", "gradientUnits", "gradientTransform", "offset", "stop-color", "stop-opacity", "fx", "fy", "href", "xlink:href", "preserveAspectRatio", "color")

    fun validate(svg: String): String {
        require(svg.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "SVG больше 128 КБ" }
        require(!Regex("<!DOCTYPE|<!ENTITY|<\\?", RegexOption.IGNORE_CASE).containsMatchIn(svg)) { "Недопустимый XML" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true; isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("Внешние ресурсы запрещены") }
        val root = builder.parse(InputSource(StringReader(svg))).documentElement
        require(root.localName == "svg") { "Ожидается SVG" }
        val box = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+"))
        require(box.size == 4 && box.all { it.toDoubleOrNull()?.isFinite() == true } && box[2].toDouble() > 0 && box[3].toDouble() > 0) { "Нужен корректный viewBox" }
        var count = 0
        val ids = mutableSetOf<String>()
        val references = mutableListOf<String>()
        val referenceTags = setOf("marker", "clipPath", "linearGradient", "radialGradient")
        fun visit(element: Element, depth: Int) {
            require(++count <= 1000 && depth <= 32) { "Слишком сложный SVG" }
            require(element.localName in tags && element.namespaceURI in setOf(null, "http://www.w3.org/2000/svg")) { "Неподдерживаемый элемент SVG" }
            for (i in 0 until element.attributes.length) {
                val attr = element.attributes.item(i)
                require(attr.nodeName in attrs) { "Неподдерживаемый атрибут SVG: ${attr.nodeName}" }
                val value = attr.nodeValue
                require(!value.contains('<') && !value.contains('>') && !value.contains('\\')) { "Некорректный атрибут SVG" }
                if (attr.nodeName == "id") { require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,80}"))); require(ids.add(value)) }
                if (attr.nodeName.endsWith("href")) { require(value.startsWith('#') && value.length > 1); references += value.drop(1) }
                if (value.contains("url", ignoreCase = true)) {
                    require(value.matches(Regex("url\\(#[A-Za-z_][A-Za-z0-9_.-]{0,80}\\)"))) { "Внешние ресурсы SVG запрещены" }
                    references += value.substring(5, value.length - 1)
                }
                if (attr.nodeName.endsWith("href") || value.contains("url", ignoreCase = true)) {
                    var ancestor: Node? = element
                    while (ancestor is Element) {
                        require(ancestor.localName !in referenceTags) { "Вложенные ссылки SVG запрещены" }
                        ancestor = ancestor.parentNode
                    }
                }
                if (attr.nodeName !in setOf("xmlns", "xmlns:xlink")) require(!value.contains(Regex("https?:|file:|data:|javascript:", RegexOption.IGNORE_CASE)))
            }
            val children = element.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element) visit(child, depth + 1)
                else require(child.nodeType in setOf(Node.TEXT_NODE, Node.CDATA_SECTION_NODE, Node.COMMENT_NODE))
            }
        }
        visit(root, 0)
        require(references.all { it in ids }) { "Не найдена ссылка SVG" }
        // <use> recursion can exhaust a renderer even in a small static document.
        require(root.getElementsByTagName("use").length == 0) { "Используйте явные фигуры вместо use" }
        return svg
    }
}
