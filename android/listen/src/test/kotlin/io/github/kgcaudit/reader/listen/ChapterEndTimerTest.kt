package io.github.kgcaudit.reader.listen

import io.github.kgcaudit.reader.layout.book.splitSentences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** "장 끝" 잠자기 타이머는 장이 끝날 때 멈춘다 — 단위(PDF 의 쪽)가 끝날 때가 아니다. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterEndTimerTest {

    private val scope = TestScope(StandardTestDispatcher())

    /** 단위마다 한 문장. [chapters] 는 단위 → 장(PDF 면 목차 항목). */
    private class Pages(private val chapters: List<Int>) : ListenSource {
        override val title = "책"
        override suspend fun speech(unit: Int): SpeechChapter {
            val text = "${unit + 1}쪽의 문장이다."
            return SpeechChapter(unit, text, splitSentences(text))
        }
        override suspend fun unitCount() = chapters.size
        override suspend fun follow(unit: Int, offset: Int) = Unit
        override suspend fun chapterOf(unit: Int) = chapters[unit]
    }

    private class FakeSpeaker : Speaker {
        val queue = ArrayDeque<String>()
        override var events: SpeakerEvents? = null
        override suspend fun prepare() = true
        override fun speak(id: String, text: String, flush: Boolean) {
            if (flush) queue.clear()
            queue.addLast(id)
            if (queue.size == 1) events?.onStart(id)
        }
        override fun stop() = queue.clear()
        override fun setRate(rate: Float) = Unit
        override fun setVoice(voice: String?) = Unit
        override fun shutdown() = queue.clear()

        /** 지금 문장을 다 읽었다. */
        fun finish() {
            val id = queue.removeFirstOrNull() ?: return
            events?.onDone(id)
            queue.firstOrNull()?.let { events?.onStart(it) }
        }
    }

    /** 0쪽부터 "장 끝" 으로 들으며 문장을 하나씩 끝낸다. 타이머가 멈췄으면 듣기가 서 있는 단위, 책 끝까지 읽었으면 null. */
    private fun stopsAt(chapters: List<Int>): Int? {
        val speaker = FakeSpeaker()
        val listening = Listening(Pages(chapters), speaker, scope)
        scope.launch { listening.start(0, 0, 1f, null) }
        scope.runCurrent()
        listening.setTimer(ListenTimer.ChapterEnd)
        repeat(chapters.size) {
            speaker.finish()
            scope.runCurrent()
            val st = listening.state.value
            if (!st.playing) return st.spine.takeIf { st.timer == ListenTimer.Off }
        }
        return null
    }

    @Test
    fun `chapter end stops where the next chapter begins, not at every page`() {
        // 1–2쪽이 1장, 3–4쪽이 2장(목차 있는 PDF). 쪽마다 멈추면 2쪽에서 서고, 장 끝이면 3쪽 머리에서 선다.
        assertEquals(2, stopsAt(listOf(0, 0, 1, 1)))
    }

    @Test
    fun `chapter end stops after every unit when every unit is its own chapter`() {
        // EPUB: 단위가 곧 장이다(기본값). 바꾸기 전과 같이 다음 장으로 넘어가는 순간 멈춘다.
        val speaker = FakeSpeaker()
        val pages = Pages(listOf(0, 0, 0))
        // chapterOf 를 적지 않은 책 — EPUB 리더가 그렇다.
        val book = object : ListenSource {
            override val title = "책"
            override suspend fun speech(unit: Int) = pages.speech(unit)
            override suspend fun unitCount() = 3
            override suspend fun follow(unit: Int, offset: Int) = Unit
        }
        val listening = Listening(book, speaker, scope)
        scope.launch { listening.start(0, 0, 1f, null) }
        scope.runCurrent()
        listening.setTimer(ListenTimer.ChapterEnd)
        speaker.finish()
        scope.runCurrent()
        assertFalse(listening.state.value.playing)
        assertEquals(1, listening.state.value.spine)
    }

    @Test
    fun `without chapters the timer never stops before the end of the book`() {
        // 목차 없는 PDF 는 모든 쪽이 한 장이다(그래서 판에서 "장 끝" 을 숨긴다). 쪽 경계에서 멈추지 않는다.
        assertEquals(null, stopsAt(listOf(-1, -1, -1)))
    }
}
