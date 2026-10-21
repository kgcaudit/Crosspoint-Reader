@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 두 쪽 보기(구상안 27) · 아래 줄의 남은 글자(28). 펼침은 화면 전체 좌표로 오간다 — 왼쪽 쪽은 [INSET], 오른쪽은 그 옆. */
class SpreadTest {
    private val root = NSTemporaryDirectory() + "olo-spread-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private fun text(paragraphs: Int) = (1..paragraphs).joinToString("\n\n") { "창밖의 빗소리가 책장 넘기는 소리와 섞였다. 문단 $it 의 끝." }

    @Test
    fun `two pages turn together and the spread is one picture as wide as the screen`() = runTest {
        val reader = open()
        val first = reader.seek(0f)
        assertEquals(0, first.pageIndex)
        assertTrue(first.hasRight)
        first.image.size.useContents { assertEquals(FULL.toDouble(), width) }
        assertEquals(2, reader.next()!!.pageIndex, "one turn shows the next two pages")
        assertEquals(0, reader.previous()!!.pageIndex)
    }

    @Test
    fun `a place on the right page opens its spread from the left page`() = runTest {
        val reader = open()
        val frame = reader.seek(0f)
        // 오른쪽 쪽의 글자 자리로 건너가도(찾기 · 책갈피 · 목차) 펼침의 왼쪽부터 보인다.
        val onRight = reader.goToHit(0, frame.pageEnd - 3, frame.pageEnd - 1)
        assertEquals(0, onRight.pageIndex)
        val last = reader.seek(100f)
        assertEquals(0, last.pageIndex % 2, "the left page of a spread is always an even index")
    }

    @Test
    fun `reaching a spread whose right page is the last page finishes the book`() = runTest {
        // 오른쪽 쪽이 책의 마지막 쪽인 책을 찾는다(문단 수로 쪽 수가 홀짝을 오간다). 왼쪽 쪽으로만 진도를 세면 다 읽어도 끝이 아니다.
        val evenEnd = (40..48).firstNotNullOfOrNull { n ->
            open(n).seek(100f).takeIf { it.hasRight }
        }
        assertTrue(evenEnd != null, "some length ends on a right page")
        assertEquals(100f, evenEnd.percent)
    }

    @Test
    fun `a press on the right half finds a letter on the right page`() = runTest {
        val reader = open()
        val frame = reader.seek(0f)
        val box = reader.boxes(0, 1).first()
        val leftChar = reader.charAt(box.centerX, box.centerY, after = false)
        val rightChar = reader.charAt(box.centerX + PAGE, box.centerY, after = false)
        assertEquals(0, leftChar)
        assertTrue(rightChar > leftChar && rightChar < frame.pageEnd, "right half belongs to the right page: $rightChar")
        // 그 글자의 상자는 오른쪽 쪽 자리에 있다 — 고른 칠이 왼쪽 쪽 위에 겹쳐 그려지면 안 된다.
        assertTrue(reader.boxes(rightChar, rightChar + 1).first().left >= INSET + PAGE)
    }

    @Test
    fun `letters left in the chapter shrink as pages turn and a missing right page is not counted`() = runTest {
        val reader = open()
        val a = reader.seek(0f)
        val b = reader.next()!!
        assertTrue(a.charsLeftChapter > b.charsLeftChapter)
        assertEquals(b.charsLeftChapter.toLong(), b.charsLeftBook, "a one-chapter book has nothing after this chapter")
        // 마지막 펼침: 오른쪽이 비었든 아니든 이 장에 남은 글자가 없다(빈 오른쪽 쪽을 남은 것으로 세지 않는다).
        assertEquals(0, reader.seek(100f).charsLeftChapter)
    }

    private suspend fun open(paragraphs: Int = 40): IosBookReader {
        val path = "$root/b$paragraphs.txt"
        val bytes = text(paragraphs).encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        val reader = IosBookReader.open(path, "app:b$paragraphs.txt", "b.txt", ReadingStore("$root/r$paragraphs.tsv"), "$root/pages$paragraphs", "1")
        reader.layOut(ReadingSpec.of(PAGE, 390f, 0f, 0f, 0f, 0f, 18f), 0xFFFFFF, 0, true, twoPages = true, fullWidth = FULL, leftInset = INSET)
        return reader
    }

    private companion object {
        const val INSET = 59f
        const val PAGE = 367f
        const val FULL = INSET * 2 + PAGE * 2
    }
}
