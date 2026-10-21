@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 형광펜 · 메모(구상안 19A · 20). 낱말을 실제로 "길게 누르고" 칠한다 — 쪽 위 자리에서 글자를 찾는 길까지 본다. */
class HighlightsTest {
    private val root = NSTemporaryDirectory() + "olo-pens-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private val text = (1..12).joinToString("\n\n") { "창밖의 빗소리가 책장 넘기는 소리와 섞였다. 문단 $it 의 끝." }

    @Test
    fun `highlights and memos survive closing the app and a damaged line drops only itself`() = runTest {
        val path = "$root/r.tsv"
        val store = ReadingStore(path)
        val book = BookId("app:b.txt")
        // 메모 속 탭 · 줄바꿈 · % 가 줄을 깨면 다음 줄들이 엉뚱한 칸으로 읽힌다.
        store.annotations.add(
            Annotation(0, book, Locator.Reflow(0, 3), Locator.Reflow(0, 9), HighlightColor.Blue, "첫 줄\n둘째\t탭 100%", "빗소리가", 1000),
        )
        store.annotations.add(Annotation(0, book, Locator.Reflow(1, 0), Locator.Reflow(1, 4), HighlightColor.Pink, null, "책장", 2000))
        val file = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)!!
        @Suppress("CAST_NEVER_SUCCEEDS")
        ((file + "A\tx\t깨진 줄\n" + "A\t9\tapp:b.txt\tr:0:9\tr:0:3\tYellow\t1\t뒤집힌 구간\n") as NSString)
            .writeToFile(path, true, NSUTF8StringEncoding, null)
        val again = ReadingStore(path).annotations.forBook(book)
        assertEquals(2, again.size)
        assertEquals("첫 줄\n둘째\t탭 100%", again[0].note)
        assertEquals(HighlightColor.Blue, again[0].color)
        assertNull(again[1].note, "no memo stays no memo")
    }

    @Test
    fun `a long-pressed word is highlighted once and painting it again only changes the colour`() = runTest {
        val reader = open()
        val word = reader.wordAt(60f, firstLineY(reader))
        assertEquals(2, word.size)
        reader.highlight(word[0], word[1], 0, null)
        reader.highlight(word[0], word[1], 2, null)
        val rows = reader.noteRows()
        assertEquals(1, rows.size, "the same range must not be stored twice")
        assertEquals(2, rows.single().color)
        assertEquals(reader.textOf(word[0], word[1]), rows.single().snippet)
        assertEquals("1장", rows.single().section)
    }

    @Test
    fun `an empty memo removes the memo but keeps the highlight and removing takes it off`() = runTest {
        val reader = open()
        val word = reader.wordAt(60f, firstLineY(reader))
        reader.highlight(word[0], word[1], 1, "  생각  ")
        val id = reader.noteRows().single().id
        assertEquals("생각", reader.noteRows().single().note)
        reader.setNote(id, "   ")
        assertNull(reader.noteRows().single().note)
        reader.removeNote(id)
        assertTrue(reader.noteRows().isEmpty())
    }

    @Test
    fun `tapping highlighted text opens that highlight unless highlights are hidden`() = runTest {
        val reader = open()
        val y = firstLineY(reader)
        val word = reader.wordAt(60f, y)
        reader.highlight(word[0], word[1], 0, null)
        val box = reader.boxes(word[0], word[1]).first()
        val tap = assertNotNull(reader.tapLink((box.left + box.right) / 2, (box.top + box.bottom) / 2))
        assertEquals("note", tap.kind)
        assertEquals(word[0], tap.note?.start)
        // 숨김: 그리지도, 눌러 열지도 않는다(지우지는 않는다).
        reader.layOut(spec(18f), 0xFFFFFF, 0, true, showHighlights = false)
        assertNull(reader.tapLink((box.left + box.right) / 2, (box.top + box.bottom) / 2))
        assertEquals(1, reader.noteRows().size)
    }

    @Test
    fun `a highlight stays on the same words after the font size changes`() = runTest {
        val reader = open()
        val word = reader.wordAt(60f, firstLineY(reader))
        reader.highlight(word[0], word[1], 0, null)
        val before = reader.boxes(word[0], word[1]).first()
        reader.layOut(spec(26f), 0xFFFFFF, 0, true)
        val row = reader.noteRows().single()
        assertEquals(word[0], row.start)
        val after = reader.boxes(row.start, row.end)
        assertTrue(after.isNotEmpty(), "the highlighted words are still on the page")
        assertNotEquals(before.right - before.left, after.first().right - after.first().left)
    }

    private fun spec(size: Float) = ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f, size)

    /** 첫 쪽 첫 줄의 높이(글자 네모 가운데). */
    private suspend fun firstLineY(reader: IosBookReader): Float {
        val b = reader.boxes(0, 1).first()
        return (b.top + b.bottom) / 2
    }

    private suspend fun open(): IosBookReader {
        val path = "$root/b.txt"
        val bytes = text.encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        val reader = IosBookReader.open(path, "app:b.txt", "b.txt", ReadingStore("$root/r.tsv"), "$root/pages", "1")
        reader.layOut(spec(18f), 0xFFFFFF, 0, true)
        return reader
    }
}
