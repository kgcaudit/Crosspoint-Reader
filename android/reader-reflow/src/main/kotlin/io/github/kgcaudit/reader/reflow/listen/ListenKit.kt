package io.github.kgcaudit.reader.reflow.listen

import android.content.Context

/**
 * 듣기가 쓰는 엔진 만들기 · 목소리 목록. 앱이 쥐고 있다가 시험에서는 가짜로 바꿔 끼운다(Robolectric 에는
 * 음성 엔진이 없다).
 */
class ListenKit(
    val speaker: (engine: String?) -> Speaker,
    val voices: suspend () -> List<VoiceChoice>,
) {
    companion object {
        fun android(context: Context): ListenKit {
            val app = context.applicationContext
            return ListenKit({ AndroidSpeaker(app, it) }, { AndroidSpeaker.voices(app) })
        }
    }
}
