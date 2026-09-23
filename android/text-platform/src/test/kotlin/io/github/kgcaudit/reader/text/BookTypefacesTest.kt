package io.github.kgcaudit.reader.text

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.layout.book.BookFontTable
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 책에 든 글꼴을 꺼내 실제 Paint 로 재 본다. 책의 CSS 는 퀴즈 책의 모양(굵기별로 가족을 나눔)과
 * 삼체의 모양(없는 파일, 웹 폰트)을 섞었다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BookTypefacesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val css = """
        @font-face { font-family: "바탕"; src: url(../Fonts/Regular.ttf); }
        @font-face { font-family: "바탕B"; src: url(../Fonts/Bold.ttf); }
        @font-face { font-family: "짝"; src: url(../Fonts/Regular.ttf); font-weight: normal; }
        @font-face { font-family: "짝"; src: url(../Fonts/Bold.ttf); font-weight: bold; }
        @font-face { font-family: "없음"; src: url(../Fonts/Missing.ttf); }
        @font-face { font-family: "웹"; src: url(../Fonts/Web.woff); }
        p { font-family: "바탕" } h1 { font-family: "바탕B" } .pair { font-family: "짝" }
        .gone { font-family: "없음" } .web { font-family: "웹" }
    """.trimIndent()

    private fun book(): EpubDocument {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            put(
                "OEBPS/content.opf",
                """<package version="3.0"><metadata><dc:title>t</dc:title></metadata><manifest>
                   <item id="c" href="Text/c.xhtml" media-type="application/xhtml+xml"/>
                   <item id="s" href="Styles/s.css" media-type="text/css"/></manifest>
                   <spine><itemref idref="c"/></spine></package>""".toByteArray(),
            )
            put("OEBPS/Text/c.xhtml", "<html><body><p>x</p></body></html>".toByteArray())
            put("OEBPS/Styles/s.css", css.toByteArray())
            put("OEBPS/Fonts/Regular.ttf", TestFonts.file("olo-test-regular.ttf").readBytes())
            put("OEBPS/Fonts/Bold.ttf", TestFonts.file("olo-test-bold.ttf").readBytes())
            put("OEBPS/Fonts/Web.woff", "wOFF".toByteArray() + ByteArray(100))
        }
        return EpubDocument.open(BookId("t"), "b.epub", SeekableSource.of(out.toByteArray()))
    }

    private val dir by lazy { temp.newFolder("book-fonts") }

    private fun prepared(): Pair<BookFontTable, BookTypefaces> = runBlocking {
        val document = book()
        val table = BookFontTable.load(document)
        table to BookTypefaces.prepare(document, table, dir)
    }

    private val spec = LayoutSpec(viewportWidthPx = 1080f, viewportHeightPx = 1600f, margin = Insets.all(48f), baseSizePx = 42f, fontId = FontCatalog.SANS)

    private fun width(measurer: AndroidTextMeasurer, style: TextStyle) = measurer.advance(SAMPLE, 0, SAMPLE.length, style)

    @Test
    fun `text the book assigns a font to is measured with that font`() {
        val (table, faces) = prepared()
        val on = AndroidTextMeasurer.forSpec(FontCatalog(), spec.copy(useBookFonts = true), faces)
        val body = TextStyle(face = table.faceFor(listOf("바탕")))
        // 책 글꼴 번호가 붙은 글자는 책 글꼴 폭, 번호 없는 글자는 본문(휴대폰) 글꼴 폭.
        assertNotEquals(width(on, TextStyle.Default), width(on, body))
        // 출판사 글꼴을 끄면 같은 번호라도 본문 글꼴로 잰다. 캐시 키(useBookFonts)와 잰 글꼴이 맞아야 한다.
        val off = AndroidTextMeasurer.forSpec(FontCatalog(), spec, faces)
        assertEquals(width(off, TextStyle.Default), width(off, body))
    }

    @Test
    fun `a family that is only a bold file is not thickened again`() {
        // 퀴즈 책: 제목 = "바탕B"(굵은 파일 하나), 그리고 h1 은 원래 굵다. 합성 굵게를 또 하면 획이 뭉개진다.
        val (table, faces) = prepared()
        val (typeface, fake) = assertNotNull(faces.select(table.faceFor(listOf("바탕b")), bold = true))
        assertFalse(fake)
        assertNotNull(typeface)
        // 보통 파일 하나뿐인 가족을 굵게 쓰면 합성한다(굵은 글자가 사라지지 않게).
        assertTrue(faces.select(table.faceFor(listOf("바탕")), bold = true)!!.second)
        // 보통·굵게를 짝지은 가족은 굵게에 굵은 파일을 쓴다.
        val pair = table.faceFor(listOf("짝"))
        assertFalse(faces.select(pair, bold = true)!!.second)
        assertNotEquals(faces.select(pair, bold = true)!!.first, faces.select(pair, bold = false)!!.first)
    }

    @Test
    fun `missing files and web fonts fall back to the reader's font`() {
        val (table, faces) = prepared()
        assertNull(faces.select(table.faceFor(listOf("없음")), bold = false))
        assertNull(faces.select(table.faceFor(listOf("웹")), bold = false))
        // 측정기도 본문 글꼴로 잰다 — 책은 열리고, 그 부분만 기본 모양이다.
        val m = AndroidTextMeasurer.forSpec(FontCatalog(), spec.copy(useBookFonts = true), faces)
        assertEquals(width(m, TextStyle.Default), width(m, TextStyle(face = table.faceFor(listOf("웹")))))
        // 읽지 못한 파일은 캐시에 남기지 않는다(다음에 "꺼내 뒀다" 고 믿고 또 실패한다).
        assertEquals(2, dir.listFiles()!!.size, "남은 파일: ${dir.listFiles()!!.map { it.name }}")
        assertFalse(faces.isEmpty)
    }

    @Test
    fun `fonts are extracted once and a damaged copy is replaced`() {
        prepared()
        val files = dir.listFiles()!!.sortedBy { it.name }
        val stamps = files.map { it.lastModified() }
        Thread.sleep(20)
        prepared()
        assertEquals(stamps, dir.listFiles()!!.sortedBy { it.name }.map { it.lastModified() }, "다시 꺼내지 않는다")

        // 캐시의 파일이 망가졌으면(저장소 오류, 덜 쓰인 파일) 지우고, 다음에 다시 꺼낸다.
        for (file in files) file.writeBytes(ByteArray(10))
        prepared()
        val (table, again) = prepared()
        assertNotNull(again.select(table.faceFor(listOf("바탕")), bold = false), "망가진 사본을 버리고 다시 꺼내야 한다")
        assertTrue(dir.listFiles()!!.all { it.length() > 10 })
    }

    private companion object {
        const val SAMPLE = "어린 왕자는 사막에서 조종사를 만났다. Chapter 1"
    }
}
