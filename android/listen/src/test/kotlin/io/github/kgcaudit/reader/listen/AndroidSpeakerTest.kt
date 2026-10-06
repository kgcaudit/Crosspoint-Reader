package io.github.kgcaudit.reader.listen

import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 휴대폰의 음성 엔진과 만나는 곳. Robolectric 의 엔진 그림자로 "깔린 엔진이 한국어를 읽는가" 를 바꿔 본다. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSpeakerTest {

    @After
    fun forgetLanguages() = ShadowTextToSpeech.reset()

    /** 엔진을 깨우고, 엔진이 "준비됐다" 고 답하게 한 뒤 [AndroidSpeaker.prepare] 의 답을 돌려준다. */
    private fun prepared(): Pair<Boolean, TextToSpeech> {
        var answer = false
        lateinit var tts: TextToSpeech
        runTest {
            val speaker = AndroidSpeaker(ApplicationProvider.getApplicationContext(), null)
            val ready = async { speaker.prepare() }
            runCurrent()
            tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
            shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
            answer = ready.await()
        }
        return answer to tts
    }

    @Test
    fun `an engine that cannot speak korean is reported as missing instead of reading in another accent`() {
        // 영어만 깔린 엔진. 쓸 수 있다고 답하면 듣기가 한국어 책을 영어 발음으로 읽기 시작하고 "엔진을 찾지 못했습니다"
        // 안내는 뜨지 않는다.
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val (ok, tts) = prepared()
        assertFalse(ok)
        // 쓰지 못할 엔진은 놓아준다 — 쥐고 있으면 책을 닫을 때까지 엔진 서비스에 묶여 있다.
        assertTrue(shadowOf(tts).isShutdown)
    }

    @Test
    fun `an engine that speaks korean is ready and set to korean`() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREAN)
        val (ok, tts) = prepared()
        assertTrue(ok)
        assertEquals(Locale.KOREAN, shadowOf(tts).currentLanguage)
        assertFalse(shadowOf(tts).isShutdown)
    }
}
