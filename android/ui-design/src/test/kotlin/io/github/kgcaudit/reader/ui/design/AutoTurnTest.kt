package io.github.kgcaudit.reader.ui.design

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTurnTest {

    @Test
    fun `auto turn does not count down while the screen is off and resumes where it stopped`() = runTest {
        // 화면을 끈 사이에도 초가 흘러, 돌아와 보니 모르는 사이 한 쪽이 넘어가 있었다(0.28.3).
        val onScreen = MutableStateFlow(true)
        val state = AutoTurnState().apply { remaining = 30 }
        val job = launch { state.countDown { onScreen.first { it } } }
        advanceTimeBy(10_500)
        assertEquals(20, state.remaining)
        onScreen.value = false
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(20, state.remaining, "화면이 꺼진 동안 셌다")
        onScreen.value = true
        advanceTimeBy(19_000)
        runCurrent()
        assertEquals(0, state.remaining)
        job.join()
    }
}
