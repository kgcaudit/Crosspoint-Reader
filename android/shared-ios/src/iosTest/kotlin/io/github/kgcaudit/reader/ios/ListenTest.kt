@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIImagePNGRepresentation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 듣기(구상안 24): 문장 나누기 · 엔진에 넘길 글 · 읽는 문장을 쪽이 따라가는 것 · 문장 칠. */
class ListenTest {
    private val root = NSTemporaryDirectory() + "olo-listen-" + NSUUID().UUIDString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    // 문단 끝에 마침표가 없다(제목 · 시 · 대화 끝에 흔하다) — 문단 경계에서 끊지 않으면 다음 문단 첫 문장과 붙어 읽힌다.
    private val text = (1..30).joinToString("\n\n") { "비가 내렸다. 나는 그 책을 읽을 수 있었다 $it" }

    @Test
    fun `sentences break at paragraphs and the reading speed setting only changes what the engine hears`() = runTest {
        val reader = open()
        val sentences = reader.speechSentences(0)
        // 문단마다 두 문장.
        assertEquals(60, sentences.size)
        val second = sentences[1]
        val plain = reader.spoken(0, second.start, second.end, 0)
        val fast = reader.spoken(0, second.start, second.end, 1)
        assertEquals(reader.textOf(second.start, second.end), plain, "plain speech reads the page text as it is")
        assertTrue(fast.count { it == ' ' } < plain.count { it == ' ' }, "속독 joins some words: '$fast'")
        assertEquals(plain.replace(" ", ""), fast.replace(" ", ""), "joining words must not drop letters")
    }

    @Test
    fun `a sentence on a later page turns the page but one already visible keeps the page where it is`() = runTest {
        val reader = open()
        val last = reader.speechSentences(0).last()
        val far = reader.speak(0, last.start, last.end, follow = true)
        assertTrue(far.pageIndex > 0, "the page follows the sentence being read")
        assertTrue(last.start in far.pageStart until far.pageEnd)
        // 같은 쪽의 다른 문장이면 쪽을 다시 짜지 않는다.
        val sameStart = reader.speechSentences(0).first { it.start >= far.pageStart }
        assertEquals(far.pageIndex, reader.speak(0, sameStart.start, sameStart.end, follow = true).pageIndex)
        // 쪽을 지키라고 하면(쪽 전체가 한 문장 안) 앞 쪽 문장을 읽어도 넘기지 않는다.
        val first = reader.speechSentences(0).first()
        assertEquals(far.pageIndex, reader.speak(0, first.start, first.end, follow = false).pageIndex)
    }

    @Test
    fun `the sentence being read is painted and ending listening paints the page as before`() = runTest {
        val reader = open()
        val before = UIImagePNGRepresentation(reader.seek(0f).image)!!
        val first = reader.speechSentences(0).first()
        val reading = UIImagePNGRepresentation(reader.speak(0, first.start, first.end, follow = true).image)!!
        assertFalse(before.isEqual(reading), "the sentence must be marked on the page")
        val after = UIImagePNGRepresentation(reader.stopSpeaking().image)!!
        assertTrue(before.isEqual(after), "no trace of the sentence after listening ends")
    }

    @Test
    fun `a missing chapter or a broken range is read as nothing instead of failing`() = runTest {
        val reader = open()
        assertTrue(reader.speechSentences(99).isEmpty())
        assertTrue(reader.speechSentences(-1).isEmpty())
        assertEquals("", reader.spoken(99, 0, 10, 0))
        // 뒤집히고 장 밖으로 나간 구간 · 없는 세기: 장 끝에서 자르고 일반으로 읽는다.
        assertEquals("", reader.spoken(0, 50, 10, 7))
        assertTrue(reader.spoken(0, -5, 1_000_000, 9).endsWith("30"))
    }

    private fun spec(size: Float) = ReadingSpec.of(360f, 640f, 0f, 0f, 0f, 0f, size)

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
