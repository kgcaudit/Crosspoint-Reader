package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.layout.book.Sentence
import io.github.kgcaudit.reader.layout.book.splitSentences
import io.github.kgcaudit.reader.listen.ListenSource
import io.github.kgcaudit.reader.listen.Listening
import io.github.kgcaudit.reader.listen.SpeechChapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 듣기를 어디서부터 여는가(0.28.1). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListeningStartTest {

    private val scope = TestScope(StandardTestDispatcher())

    /** 한 장짜리 책. [order] 로 문장 목록을 늘어놓는 차례를 바꾼다(PDF 의 단 · 상자 순서). 쪽을 옮긴 자리를 적어 둔다. */
    private class Book(val text: String, val order: (List<Sentence>) -> List<Sentence> = { it }) : ListenSource {
        val followed = mutableListOf<Int>()
        override val title = "책"
        override suspend fun speech(unit: Int) = SpeechChapter(unit, text, order(splitSentences(text)))
        override suspend fun unitCount() = 1
        override suspend fun follow(unit: Int, offset: Int) { followed += offset }
    }

    private val text = "첫 문장은 앞 쪽에서 시작해 이 쪽까지 이어진다. 이 쪽에서 시작하는 문장이다. 마지막 문장이다."

    @Test
    fun `listening from a page starts at the first sentence that begins on it and does not jump back`() {
        // 쪽이 첫 문장 한가운데서 시작한다. 그 문장부터 읽으면 앞 쪽으로 튀어 돌아간다.
        val book = Book(text)
        val speaker = ListenAppTest.FakeSpeaker(null)
        val pageStart = text.indexOf("이 쪽까지")
        scope.launch { Listening(book, speaker, scope).start(0, pageStart, 1f, null, pageEnd = text.length) }
        scope.runCurrent()
        assertEquals("이 쪽에서 시작하는 문장이다.", speaker.current?.trim())
        assertTrue(book.followed.all { it >= pageStart }, "앞 쪽으로 옮겼다: ${book.followed}")
    }

    @Test
    fun `a page inside one long sentence reads that sentence and stays on the page`() {
        val book = Book(text)
        val speaker = ListenAppTest.FakeSpeaker(null)
        val pageStart = text.indexOf("이 쪽까지")
        val pageEnd = pageStart + 3
        scope.launch { Listening(book, speaker, scope).start(0, pageStart, 1f, null, pageEnd = pageEnd) }
        scope.runCurrent()
        assertTrue(speaker.current.orEmpty().startsWith("첫 문장은"), speaker.current)
        assertTrue(book.followed.isEmpty(), "사람이 둔 쪽을 옮겼다: ${book.followed}")
    }

    @Test
    fun `restarting on another engine reads the sentence that was being read even in column order`() {
        // PDF 는 문장을 보이는 순서로 늘어놓는다 — 여기서는 뒤 문장이 목록 앞에 온다.
        val book = Book(text) { it.reversed() }
        val speaker = ListenAppTest.FakeSpeaker(null)
        val heard = text.indexOf("이 쪽에서 시작하는")
        scope.launch { Listening(book, speaker, scope).start(0, heard, 1f, null) }
        scope.runCurrent()
        assertEquals("이 쪽에서 시작하는 문장이다.", speaker.current?.trim())
    }
}
