package dev.f1xtrack.gw6voice.core

import org.junit.Assert.*
import org.junit.Test

class SvgTest {
    private fun reject(content: String) = assertThrows(Exception::class.java) { SafeSvg.validate(content) }
    private fun wrap(content: String) = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 320 260\">$content</svg>"
    @Test fun basicStaticDrawingWorks() { assertEquals(DocumentTest.SVG, SafeSvg.validate(DocumentTest.SVG)) }
    @Test fun externalEntityIsRejected() { reject("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private'>]>" + wrap("<text>&x;</text>")) }
    @Test fun scriptsAreRejected() { reject(wrap("<script>alert(1)</script>")) }
    @Test fun htmlIsRejected() { reject(wrap("<foreignObject><div>test</div></foreignObject>")) }
    @Test fun eventHandlersAreRejected() { reject(wrap("<rect width='4' height='4' onload='x()'/>")) }
    @Test fun externalImageIsRejected() { reject(wrap("<image href='https://example.org/private'/>")) }
    @Test fun cssImportsAreRejected() { reject(wrap("<style>@import 'https://example.org';</style>")) }
    @Test fun externalPaintIsRejected() { reject(wrap("<path d='M0 0' fill='url(https://example.org)'/>")) }
    @Test fun useRecursionIsRejected() { reject(wrap("<g id='a'><use href='#a'/></g>")) }
    @Test fun markerRecursionIsRejected() { reject(wrap("<defs><marker id='a'><path d='M0 0' marker-end='url(#a)'/></marker></defs>")) }
    @Test fun localMarkerWorks() { SafeSvg.validate(wrap("<defs><marker id='arrow' markerWidth='8' markerHeight='8'><path d='M0 0L8 4L0 8'/></marker></defs><line x1='1' y1='1' x2='10' y2='10' marker-end='url(#arrow)'/>")) }
    @Test fun danglingReferenceIsRejected() { reject(wrap("<path d='M0 0' fill='url(#absent)'/>")) }
    @Test fun missingViewBoxIsRejected() { reject("<svg/>") }
    @Test fun infiniteViewBoxIsRejected() { reject("<svg viewBox='0 0 Infinity 1'/>") }
    @Test fun tooManyNodesAreRejected() { reject(wrap("<g/>".repeat(1001))) }
    @Test fun deepTreesAreRejected() { reject(wrap("<g>".repeat(34) + "</g>".repeat(34))) }
}
