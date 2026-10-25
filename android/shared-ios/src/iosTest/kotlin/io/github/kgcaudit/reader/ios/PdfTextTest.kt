@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.layout.pdf.PageText
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PDF 찾기 · 형광펜 · 듣기(구상안 34A)의 iOS 쪽 묶음. 층의 셈 자체는 공통 `PageTextTest` 가 지킨다. */
class PdfTextTest {
    private val root = NSTemporaryDirectory() + "olo-pdftext-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    /** 두 줄짜리 쪽: 글자마다 폭 0.02 의 네모, 줄 높이 0.03. 줄바꿈은 자리가 없다(NaN). */
    private fun page(lines: List<String>): Pair<String, FloatArray> {
        val text = lines.joinToString("\n")
        val boxes = FloatArray(text.length * 4) { Float.NaN }
        var i = 0
        lines.forEachIndexed { row, line ->
            line.forEachIndexed { col, _ ->
                val left = 0.1f + col * 0.02f
                val top = 0.2f + row * 0.05f
                boxes[i * 4] = left; boxes[i * 4 + 1] = top; boxes[i * 4 + 2] = left + 0.02f; boxes[i * 4 + 3] = top + 0.03f
                i++
            }
            i++ // 줄바꿈
        }
        return text to boxes
    }

    private val two = page(listOf("오래된 등대에 불이 켜졌다. 바다는 고요했다.", "등대지기는 창가에 앉아 등대 불빛을 보았다."))

    @Test
    fun `a page layer keeps every char and knows where each one is`() {
        val layer = IosPdfText.layer(two.first, two.second)
        assertEquals(two.first.length, layer.length)
        val at = IosPdfText.charAt(layer, 0.1f + 4 * 0.02f + 0.005f, 0.21f, false)
        assertEquals('등', layer.text[at])
        assertEquals("등대에", layer.text.substring(IosPdfText.wordAt(layer, at)[0], IosPdfText.wordAt(layer, at)[1]))
        assertEquals(-1, IosPdfText.charAt(layer, 0.5f, 0.9f, false), "a tap far below the text is no char")
    }

    @Test
    fun `a line stored out of visual order is read in the order it is seen`() {
        // 자간을 맞추느라 글자를 쓴 순서가 보이는 순서와 다른 글꼴: 층에 "번은역" 으로 오지만 화면은 "번역은"(안드로이드와 같은 다듬기).
        val boxes = floatArrayOf(0.10f, 0.2f, 0.12f, 0.23f, 0.14f, 0.2f, 0.16f, 0.23f, 0.12f, 0.2f, 0.14f, 0.23f)
        assertEquals("번역은", IosPdfText.layer("번은역", boxes).text)
    }

    @Test
    fun `boxes that do not match the text leave a layer without places but with text`() {
        val layer = IosPdfText.layer("깨진 상자", FloatArray(3))
        assertEquals("깨진 상자", layer.text)
        assertEquals(-1, IosPdfText.charAt(layer, 0.1f, 0.2f, false))
        assertTrue(IosPdfText.rects(layer, 0, 2).isEmpty())
        assertEquals(0, IosPdfText.layer("", FloatArray(0)).length)
    }

    @Test
    fun `search finds every hit on a page and a scanned page finds nothing`() {
        val hits = IosPdfText.search(two.first, 4, "등대")
        assertEquals(3, hits.size)
        assertTrue(hits.all { it.spine == 4 })
        assertTrue(hits[0].context.contains("등대"))
        assertTrue(IosPdfText.search("\u0001\u0002 ##", 0, "등대").isEmpty(), "a page with no readable text")
        assertTrue(IosPdfText.search(two.first, 0, "  ").isEmpty())
        // 글자 모양만 있는 스캔본: 몇 글자가 맞아도 깨진 글자가 대부분이면 찾지 않는다(맞지 않는 결과만 나온다).
        assertTrue(IosPdfText.search("등대" + "\uE000".repeat(12), 0, "등대").isEmpty())
        assertEquals(false, IosPdfText.readable("■■■ ▲▲"))
        assertEquals(true, IosPdfText.readable(two.first))
    }

    @Test
    fun `the n-th hit on a page is marked on the layer`() {
        val layer = IosPdfText.layer(two.first, two.second)
        val second = IosPdfText.hit(layer, "등대", 1)!!
        assertEquals("등대", layer.text.substring(second[0], second[1]))
        assertTrue(second[0] > layer.text.indexOf("등대"), "not the first one")
        val tooFar = IosPdfText.hit(layer, "등대", 9)!!
        assertEquals(layer.text.lastIndexOf("등대"), tooFar[0], "past the end: the last hit")
        assertNull(IosPdfText.hit(layer, "없는말", 0))
    }

    @Test
    fun `tints are painted under the search mark and a memo leaves a note glyph`() {
        val layer = IosPdfText.layer(two.first, two.second)
        val tints = listOf(PdfTint(0, 3, 1, hasNote = true))
        val boxes = IosPdfText.paint(layer, tints, intArrayOf(4, 6), intArrayOf(0, 15), dark = false, accent = 0xB95B3B)
        val kinds = boxes.map { if (it.memo) "memo" else if (it.alpha == 0.18) "speak" else if (it.alpha == 0.45) "tint" else "mark" }
        assertEquals(listOf("speak", "tint", "mark", "memo"), kinds.distinct(), "speaking under tints, the mark on top")
        assertEquals(IosBookReader.PEN_BASE[1], boxes.first { !it.memo && it.alpha == 0.45 }.rgb)
        val dark = IosPdfText.paint(layer, tints, null, null, dark = true, accent = 0)
        assertEquals(0.38, dark.first().alpha, "lighter tints on dark paper")
        assertEquals(0, IosPdfText.tintAt(layer, tints, 0.11f, 0.21f))
        assertEquals(-1, IosPdfText.tintAt(layer, tints, 0.6f, 0.21f))
    }

    @Test
    fun `speech sentences point into the layer`() {
        val layer = IosPdfText.layer(two.first, two.second)
        val s = IosPdfText.sentences(layer)
        assertTrue(s.size >= 4 && s.size % 2 == 0)
        assertEquals("오래된 등대에 불이 켜졌다.", IosPdfText.spoken(layer, s[0], s[1]))
    }

    @Test
    fun `a pdf highlight is saved as page and char and found again`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val book = IosPdfBook.open("$root/없음.pdf", "app:a.pdf", "a.pdf", 10, store)
        book.highlight(4, 3, 6, 2, null, "등대에")
        book.highlight(4, 3, 6, 0, "메모", "등대에")
        val a = store.annotations.forBook(BookId("app:a.pdf")).single()
        assertEquals(Locator.Reflow(4, 3), a.start, "the same span is updated, not added twice")
        assertEquals("메모", a.note)
        val row = book.noteRows().single()
        assertEquals(IosPdfText.encode(4, 3), row.start)
        assertEquals(4, IosPdfText.pageOf(row.start))
        assertEquals(3, IosPdfText.charOf(row.start))
        assertTrue(book.tintsOn(4, null).single().hasNote)
        assertTrue(book.tintsOn(5, null).isEmpty())
        book.highlight(4, 6, 6, 0, null, "")
        book.highlight(99, 0, 2, 0, null, "")
        assertEquals(1, book.noteRows().size, "an empty span or a page outside the book is not saved")
        book.removeNote(row.id)
        assertTrue(book.noteRows().isEmpty())
    }

    @Test
    fun `a highlight made on another device sits on the same words here`() = runTest {
        val store = ReadingStore("$root/r.tsv")
        val book = IosPdfBook.open("$root/없음.pdf", "app:a.pdf", "a.pdf", 10, store)
        // 다른 기기의 엔진은 "오래된등대에" 로 냈다 — 거기서 "등대에" 는 3..6. 이 기기의 엔진은 공백을 하나 넣어 "오래된 등대에".
        book.highlight(4, 3, 6, 1, null, "등대에")
        val here = pageText("오래된 등대에 불이")
        val tint = book.tintsOn(4, here).single()
        assertEquals("등대에", here.text.substring(tint.start, tint.end), "the tint sits on the words it was made on")
        val row = book.notesOn(4, here).single()
        assertEquals(tint.start, IosPdfText.charOf(row.start), "the note menu opens over the same words")
        assertEquals(row.id, tint.id, "a tapped tint finds its note by id, not by the saved numbers")
        // 저장된 번호는 그대로 — 원래 기기에서는 그 번호가 맞다.
        assertEquals(Locator.Reflow(4, 3), store.annotations.forBook(BookId("app:a.pdf")).single().start)
        // 쪽에 그 글이 없으면(다른 판의 PDF) 저장된 번호 그대로 — 칠이 엉뚱한 곳으로 옮겨 가지 않는다.
        val other = book.tintsOn(4, pageText("전혀 다른 쪽의 글")).single()
        assertEquals(3 to 6, other.start to other.end)
    }

    /** 글자마다 가로로 늘어선 네모를 가진 층(자리는 이 시험에서 보지 않는다). */
    private fun pageText(text: String) =
        PageText(text, FloatArray(text.length * 4) { i -> if (i % 4 == 0) (i / 4) * 0.01f else if (i % 4 == 2) (i / 4) * 0.01f + 0.01f else if (i % 4 == 1) 0.1f else 0.12f })

    @Test
    fun `a spoken sentence already on screen does not move the page`() {
        assertNull(IosPdfText.follow(top = 300.0, bottom = 340.0, viewTop = 200.0, viewHeight = 600.0, maxTop = 2000.0))
    }

    @Test
    fun `a spoken sentence below the screen comes up to a third from the top`() {
        assertEquals(1000.0 - 600.0 * 0.3, IosPdfText.follow(1000.0, 1040.0, 200.0, 600.0, 2000.0)!!, 1e-9)
        // 위로 벗어난 문장(앞으로 돌아갔다)도 같은 자리로 — 쪽 머리 위로는 가지 않는다.
        assertEquals(400.0 - 180.0, IosPdfText.follow(400.0, 440.0, 500.0, 600.0, 2000.0)!!, 1e-9)
        assertEquals(0.0, IosPdfText.follow(100.0, 140.0, 500.0, 600.0, 2000.0)!!, 1e-9)
    }

    @Test
    fun `following stops at the end of the page`() {
        // 쪽 끝 문장: 끝을 넘겨 옮기면 쪽 아래 빈 바탕이 보인다.
        assertEquals(1400.0, IosPdfText.follow(1950.0, 1990.0, 200.0, 600.0, 1400.0)!!, 1e-9)
        // 쪽 끝의 긴 문장: 머리는 보이고 아래가 화면 밖이어도 이미 끝이면 거꾸로 올리지 않는다.
        assertNull(IosPdfText.follow(1500.0, 2100.0, 1400.0, 600.0, 1400.0))
    }

    @Test
    fun `a box on a page turned clockwise lands where the turned picture shows it`() {
        // 돌리기 전 쪽의 왼쪽 위 구석 글자(가로 0.1 ~ 0.2, 세로 0.0 ~ 0.05).
        val box = floatArrayOf(0.1f, 0f, 0.2f, 0.05f)
        fun at(rotation: Int) = IosPdfText.rotated(box[0], box[1], box[2], box[3], rotation).toList()
        assertEquals(box.toList(), at(0))
        // 시계 방향 90도: 왼쪽 위는 오른쪽 위로 간다.
        assertEquals(listOf(0.95f, 0.1f, 1f, 0.2f), at(90))
        // 180도: 오른쪽 아래.
        assertEquals(listOf(0.8f, 0.95f, 0.9f, 1f), at(180))
        // 270도: 왼쪽 아래.
        assertEquals(listOf(0f, 0.8f, 0.05f, 0.9f), at(270))
        // 음수 · 360 넘는 값도 같은 방향(PDF 의 /Rotate 는 -90 · 450 도 쓴다).
        assertEquals(at(270), at(-90))
        assertEquals(at(90), at(450))
    }

    @Test
    fun `a turned box keeps its corners in order`() {
        // 왼쪽 < 오른쪽, 위 < 아래 — 뒤집힌 네모는 칠도, 누른 자리 찾기도 비어 버린다.
        for (r in listOf(0, 90, 180, 270)) {
            val (l, t, rr, b) = IosPdfText.rotated(0.3f, 0.4f, 0.5f, 0.45f, r).toList()
            assertTrue(l < rr && t < b, "rotation $r")
        }
    }
}
