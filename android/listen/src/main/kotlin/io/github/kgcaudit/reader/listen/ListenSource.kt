package io.github.kgcaudit.reader.listen

import io.github.kgcaudit.reader.layout.book.Sentence
import io.github.kgcaudit.reader.layout.book.WordJoin

/**
 * 듣기가 읽는 책. EPUB 은 장 하나, PDF 는 쪽 하나가 [speech] 의 한 단위다([SpeechChapter.spine]).
 *
 * 듣기는 이 경계만 안다. EPUB 리더에 기대면 PDF 듣기를 만들 때 EPUB 모듈을 끌어와야 한다.
 */
interface ListenSource {
    val title: String

    /** 단위 하나의 글과 문장. 깨졌거나 글이 없으면 문장 없는 것 — 듣기는 그 단위를 건너뛴다. */
    suspend fun speech(unit: Int): SpeechChapter

    /** 단위의 수(장 수 · 쪽 수). */
    suspend fun unitCount(): Int

    /** 그 단위의 글자 [offset] 이 보이게 한다(듣기가 쪽을 따라 넘긴다). 이미 보이면 아무것도 안 한다. */
    suspend fun follow(unit: Int, offset: Int)
}

/** 한 장(PDF 는 한 쪽)의 텍스트와 문장들(듣기). */
class SpeechChapter(val spine: Int, val text: String, val sentences: List<Sentence>)

/**
 * 듣기 설정. 빠르기 · 목소리는 책이 바뀌어도 그대로다(사람의 귀에 맞춘 값이다). 잠자기 타이머는 저장하지 않는다 —
 * 어젯밤 30분 타이머가 오늘 아침 듣기를 30분 만에 끊으면 안 된다.
 *
 * @param engine 음성 엔진 패키지. null 이면 휴대폰 기본 엔진.
 * @param voice 엔진 안의 목소리 이름. null 이면 엔진의 기본 목소리.
 * @param voiceLabel 듣기 판에 보일 이름("Samsung TTS · 한국어 1").
 */
data class ListenPrefs(
    val rate: Float = 1f,
    val engine: String? = null,
    val voice: String? = null,
    val voiceLabel: String? = null,
    /** 어절 쉼 줄이기(실험). 기본 끔 — 지금 듣는 방식을 몰래 바꾸지 않는다. */
    val join: WordJoin = WordJoin.Off,
) {
    /** 한 단계 빠르게(+) · 느리게(−). 0.5–2.0 을 0.1 씩(L4). 떠돌이 소수(1.2000001)가 생기지 않게 10배로 센다. */
    fun stepRate(by: Int): ListenPrefs = copy(rate = ((kotlin.math.round(rate * 10f).toInt() + by).coerceIn(5, 20)) / 10f)

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
    }
}