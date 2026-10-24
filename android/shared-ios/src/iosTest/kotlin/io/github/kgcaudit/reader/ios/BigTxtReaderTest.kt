@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import platform.posix.usleep
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 큰 TXT 를 조각으로 짠다(안드로이드 0.60.0): 첫 쪽이 바로 뜨고, 저장 자리 · 쪽 번호 · 칠은 한 권 그대로다. */
class BigTxtReaderTest {
    private val root = NSTemporaryDirectory() + "bigtxt-" + NSUUID().UUIDString
    private val files = NSFileManager.defaultManager

    init {
        files.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)
    }

    @AfterTest
    fun cleanUp() {
        files.removeItemAtPath(root, null)
    }

    /** 빈 줄로 장면을 가른 약 7만 글자 — 조각 넷 남짓. */
    private val text = (1..900).joinToString("\n\n") { "장면 $it. 등대지기는 날마다 같은 시각에 계단을 올라 불을 밝혔고 배들은 그 빛으로 길을 찾았다." }
    private val id = "app:드래곤라자/14.txt"

    private fun write(): String {
        val path = "$root/14.txt"
        @Suppress("CAST_NEVER_SUCCEEDS")
        (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        return path
    }

    private fun spec() = ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f, 18f)

    private fun open(store: ReadingStore) = IosBookReader.open(write(), id, "14.txt", store, "$root/pages", "1")

    @Test
    fun `the place is saved as one book and the header counts one chapter`() = runBlocking {
        val store = ReadingStore("$root/r.tsv")
        val reader = open(store)
        var f = reader.layOut(spec(), 0xFFFFFF, 0, true)
        assertEquals(1, f.chapterCount, "a split TXT is one chapter on screen, not 'n / 5장'")
        assertEquals(0, f.chapterIndex)
        // 첫 조각을 넘어갈 때까지.
        while (f.spineIndex == 0) f = reader.next() ?: break
        assertTrue(f.spineIndex >= 1, "reached the second piece")
        assertEquals(0, f.chapterIndex)
        val saved = store.progressOf(id)!!.locator as Locator.Reflow
        // 저장은 나누기 전 한 장의 글자 번호(r:0:N) — 옛 기록 · 다른 기기와 맞는다.
        assertEquals(0, saved.spine)
        assertTrue(saved.charOffset > 10_000, "${saved.charOffset}")
        reader.close()
    }

    @Test
    fun `pages are counted in the background and the number keeps going across pieces`() = runBlocking {
        val reader = open(ReadingStore("$root/r.tsv"))
        val first = reader.layOut(spec(), 0xFFFFFF, 0, true)
        var n = reader.pageNumberNow()
        var waited = 0
        while (!n.done && waited < 600) { usleep(100_000u); waited++; n = reader.pageNumberNow() }
        assertTrue(n.done, "counting finished")
        assertEquals(1, n.number)
        assertTrue(n.total > first.pageCount, "total ${n.total} spans all pieces, first piece has ${first.pageCount}")
        var f = first
        while (f.spineIndex == 0) f = reader.next()!!
        assertEquals(first.pageCount + 1, f.pageNumber, "the first page of the second piece follows the first piece")
        assertEquals(n.total, f.pageTotal)
        reader.close()
    }

    @Test
    fun `an old place and a place from another device land in the right piece`() = runBlocking {
        val store = ReadingStore("$root/r.tsv")
        val far = text.indexOf("장면 700.")
        store.save(ReadingProgress(BookId(id), Locator.Reflow(0, far), 77f, 1))
        val reader = open(store)
        val f = reader.layOut(spec(), 0xFFFFFF, 0, true)
        assertTrue(f.spineIndex > 0, "opened in a later piece, not the first")
        val place = reader.layoutPlace(0, far)
        assertEquals(f.spineIndex, place[0])
        // 다른 기기의 자리(책 자리)로 가기.
        val back = reader.goToPlace(0, text.indexOf("장면 3."))
        assertEquals(0, back.spineIndex)
        // 일부러 깨진 자리: 글 끝을 넘는 번호는 마지막 조각으로(열리지 않으면 안 된다).
        val broken = reader.goToPlace(0, 99_999_999)
        assertTrue(broken.spineIndex == place[0] || broken.spineIndex > 0)
        reader.close()
    }

    @Test
    fun `a highlight across a piece boundary keeps its whole range when recolored`() = runBlocking {
        val store = ReadingStore("$root/r.tsv")
        val first = open(store)
        first.layOut(spec(), 0xFFFFFF, 0, true)
        // 0.59 까지 한 장이던 책에 칠한 것: 둘째 조각 끝에서 셋째 조각 처음까지(첫 조각이면 바꾸지 않은 자리와 우연히 같다).
        val boundary = (1..text.length).first { first.layoutPlace(0, it)[0] == 2 }
        val start = boundary - 20
        val end = boundary + 20
        store.annotations.add(Annotation(Annotation.NO_ID, BookId(id), Locator.Reflow(0, start), Locator.Reflow(0, end), HighlightColor.Yellow, null, "경계", 1))
        first.close()
        // 칠은 책을 열 때 읽는다 — 다시 연다.
        val reader = open(store)
        reader.layOut(spec(), 0xFFFFFF, 0, true)
        val row = reader.noteRows().single()
        reader.recolor(row.id, 2)
        val saved = store.annotations.forBook(BookId(id)).single()
        assertEquals(Locator.Reflow(0, start), saved.start, "the range is not cut to one piece")
        assertEquals(Locator.Reflow(0, end), saved.end)
        assertEquals(HighlightColor.entries[2], saved.color)
        // 독서노트에서 누르면 칠의 시작(둘째 조각)으로.
        assertEquals(1, reader.goToNote(row.id)!!.spineIndex)
        assertEquals(1, row.spine, "the notes list places it in its own piece")
        reader.close()
    }
}
