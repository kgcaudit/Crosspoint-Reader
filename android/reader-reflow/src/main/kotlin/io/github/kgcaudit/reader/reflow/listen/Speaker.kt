package io.github.kgcaudit.reader.reflow.listen

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 소리 내어 읽는 엔진. 듣기(4단계)가 음성 엔진과 만나는 유일한 경계다.
 *
 * 안드로이드의 TextToSpeech 를 직접 부르지 않고 이 인터페이스를 두는 이유: 시험 환경(Robolectric)에는 음성
 * 엔진이 없어 "말하기 시작 · 끝" 을 흉내 낼 수 없다. 가짜 엔진을 끼우면 문장이 어떤 순서로 넘어가는지, 쪽이
 * 따라가는지를 기기 없이 확인할 수 있다.
 */
interface Speaker {
    /** 엔진을 준비한다. 쓸 수 있으면 true. 설치된 엔진이 없거나 한국어를 못 읽으면 false. */
    suspend fun prepare(): Boolean

    /** [text] 를 읽는다. [flush] 면 읽던 것을 끊고, 아니면 뒤에 잇는다. [id] 로 시작 · 끝을 알린다. */
    fun speak(id: String, text: String, flush: Boolean)

    /** 읽던 것과 줄 선 것을 모두 멈춘다. */
    fun stop()

    /** 1.0 이 보통 빠르기. */
    fun setRate(rate: Float)

    /** 목소리를 고른다. null 이면 엔진의 기본 목소리. */
    fun setVoice(voice: String?)

    var events: SpeakerEvents?

    fun shutdown()
}

interface SpeakerEvents {
    fun onStart(id: String)
    fun onDone(id: String)
    fun onError(id: String)
}

/** 목소리 고르기 화면의 한 줄(L5). [engine] 은 엔진 패키지 이름, [name] 은 엔진 안의 목소리 이름. */
data class VoiceChoice(val engine: String, val engineLabel: String, val name: String, val label: String, val needsDownload: Boolean)

/**
 * 안드로이드 음성 엔진(삼성 TTS · Google 등). 인터넷을 쓰지 않는다 — 기기에 깔린 엔진이 읽는다.
 *
 * @param engine 엔진 패키지. null 이면 휴대폰 설정의 기본 엔진.
 */
class AndroidSpeaker(private val context: Context, private val engine: String?) : Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    override var events: SpeakerEvents? = null

    override suspend fun prepare(): Boolean {
        if (ready) return true
        val done = CompletableDeferred<Boolean>()
        val created = TextToSpeech(context.applicationContext, { status -> done.complete(status == TextToSpeech.SUCCESS) }, engine)
        tts = created
        // 엔진이 대답하지 않는 기기가 있다(엔진을 지운 직후 등). 끝없이 기다리면 단추가 먹통이 된다.
        ready = withTimeoutOrNull(8_000) { done.await() } == true
        if (!ready) return false
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { events?.onStart(utteranceId) }
            override fun onDone(utteranceId: String) { events?.onDone(utteranceId) }
            @Deprecated("API 21 에서 바뀌었지만 옛 엔진은 이것만 부른다")
            override fun onError(utteranceId: String) { events?.onError(utteranceId) }
            override fun onError(utteranceId: String, errorCode: Int) { events?.onError(utteranceId) }
        })
        // 음악과 같은 소리 줄기로 — 그래야 음량 단추 · 이어폰 · 오디오 포커스가 음악 앱처럼 동작한다.
        created.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // 한국어를 못 읽는 엔진이면 한국어 책을 영어 발음으로 읽는다 — 차라리 알리는 편이 낫다.
        val korean = created.isLanguageAvailable(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
        if (korean) created.language = Locale.KOREAN
        return true
    }

    override fun speak(id: String, text: String, flush: Boolean) {
        val params = Bundle()
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    override fun setVoice(voice: String?) {
        val t = tts ?: return
        val found = voice?.let { name -> runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == name } }
        if (found != null) t.voice = found else runCatching { t.defaultVoice }.getOrNull()?.let { t.voice = it }
    }

    override fun shutdown() {
        tts?.runCatching { stop(); shutdown() }
        tts = null
        ready = false
    }

    companion object {
        /**
         * 깔린 엔진마다 한국어 목소리를 모은다(L5). 엔진을 하나씩 잠깐 켰다 끈다 — 목소리 목록은 켠 엔진만 알려 준다.
         * 한국어 목소리가 없는 엔진은 뺀다(골라도 한국어 책을 읽지 못한다).
         */
        suspend fun voices(context: Context): List<VoiceChoice> {
            val probe = AndroidSpeaker(context, null)
            probe.prepare()
            val engines = probe.tts?.engines.orEmpty()
            probe.shutdown()
            val out = ArrayList<VoiceChoice>()
            for (engine in engines) {
                val speaker = AndroidSpeaker(context, engine.name)
                if (speaker.prepare()) {
                    val korean = runCatching { speaker.tts?.voices }.getOrNull().orEmpty()
                        // 인터넷이 있어야 읽는 목소리는 뺀다. 앱은 인터넷을 쓰지 않는다는 약속을 엔진을 통해 깨지 않는다.
                        .filter { it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired }
                        .sortedBy { it.name }
                    korean.forEachIndexed { i, v -> out += VoiceChoice(engine.name, engine.label, v.name, voiceLabel(v, i), needsDownload(v)) }
                }
                speaker.shutdown()
            }
            return out
        }

        private fun needsDownload(v: Voice): Boolean = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true

        /** 엔진이 주는 이름("ko-kr-x-kob-local")은 사람이 읽을 수 없다 — 순번으로 부른다. */
        @Suppress("UNUSED_PARAMETER")
        private fun voiceLabel(v: Voice, index: Int): String = "한국어 ${index + 1}"
    }
}
