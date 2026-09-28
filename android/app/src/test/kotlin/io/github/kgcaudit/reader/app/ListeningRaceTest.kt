package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.layout.book.splitSentences
import io.github.kgcaudit.reader.listen.ListenSource
import io.github.kgcaudit.reader.listen.Listening
import io.github.kgcaudit.reader.listen.SpeechChapter
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 듣기 명령이 겹칠 때(0.24.1). 다음 장을 불러오는 동안 온 멈춤이 무시돼, 이어폰을 뺐는데 스피커로 계속 읽었다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListeningRaceTest {

    private val scope = TestScope(StandardTestDispatcher())

    /** 둘째 장은 [gate] 가 열릴 때까지 불러오는 중이다(큰 장 · 느린 저장소). */
    private class SlowBook(val gate: CompletableDeferred<Unit>) : ListenSource {
        override val title = "느린 책"
        override suspend fun speech(unit: Int): SpeechChapter {
            if (unit == 1) gate.await()
            val text = if (unit == 0) "첫 장의 끝 문장이다." else "둘째 장의 첫 문장이다."
            return SpeechChapter(unit, text, splitSentences(text))
        }
        override suspend fun unitCount() = 2
        override suspend fun follow(unit: Int, offset: Int) = Unit
    }

    @Test
    fun `a pause that comes while the next chapter is loading keeps the reading stopped`() {
        val gate = CompletableDeferred<Unit>()
        val speaker = ListenAppTest.FakeSpeaker(null)
        val listening = Listening(SlowBook(gate), speaker, scope)
        scope.launch { listening.start(0, 0, 1f, null) }
        scope.runCurrent()
        assertEquals("첫 장의 끝 문장이다.", speaker.current)
        speaker.finish() // 장 끝 → 둘째 장을 불러오기 시작
        scope.runCurrent()
        listening.pause() // 그 사이 이어폰이 빠졌다
        gate.complete(Unit)
        scope.runCurrent()
        assertFalse(listening.state.value.playing, "멈춘 듣기가 다시 읽기 시작했다")
        assertNull(speaker.current, "엔진에 읽을 글이 들어갔다")
    }

    @Test
    fun `closing while the engine is waking up does not start reading`() {
        // 망가뜨린 순서: 듣기를 누르자마자 ✕ 로 껐다. 엔진이 깨어난 뒤 읽기 시작하면 닫은 듣기가 소리를 낸다.
        val gate = CompletableDeferred<Unit>()
        val speaker = ListenAppTest.FakeSpeaker(null)
        val listening = Listening(SlowBook(gate), speaker, scope)
        scope.launch { listening.start(1, 0, 1f, null) }
        scope.runCurrent()
        listening.close()
        gate.complete(Unit)
        scope.runCurrent()
        assertNull(speaker.current)
        assertFalse(listening.state.value.playing)
    }
}
