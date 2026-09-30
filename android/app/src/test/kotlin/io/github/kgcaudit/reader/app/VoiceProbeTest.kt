package io.github.kgcaudit.reader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.listen.AndroidSpeaker
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 목소리 화면을 목록이 뜨기 전에 나가도 켜던 음성 엔진은 꺼진다(0.28.1). 끄지 않으면 엔진 서비스에 묶인 채 앱이 끝날 때까지
 * 남아, 목소리 화면을 드나들 때마다 쌓였다(메모리 · 전지).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceProbeTest {

    @Test
    fun `leaving the voice list before it is ready shuts the engine it was waking`() = runBlocking {
        ShadowTextToSpeech.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 시험의 엔진은 "준비됐다" 를 알리지 않는다 — 사용자가 엔진이 깨어나기를 기다리는 동안과 같다.
        val job = launch { AndroidSpeaker.voices(context) }
        yield()
        val waking = assertNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance(), "엔진을 켜지 않았다")
        job.cancel()
        job.join()
        assertTrue(shadowOf(waking).isShutdown, "화면을 떠났는데 엔진이 켜진 채 남았다")
    }

    @Test
    fun `closing listening while the engine wakes up shuts that engine`() = runBlocking {
        // 듣기를 누르자마자 끔 — 엔진 준비가 취소된다. 만들어 둔 엔진이 남으면 책을 닫아도 붙들고 있다.
        ShadowTextToSpeech.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val job = launch { AndroidSpeaker(context, null).prepare() }
        yield()
        val waking = assertNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        job.cancel()
        job.join()
        assertTrue(shadowOf(waking).isShutdown)
    }
}
