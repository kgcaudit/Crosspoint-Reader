package io.github.kgcaudit.reader.text

import android.graphics.Paint
import android.graphics.Typeface

/** 글꼴 목록의 한 줄. [key] 가 설정에 저장되는 값이다. */
data class FontOption(
    val key: String,
    val label: String,
    val kind: Kind,
    /** 한글 글리프가 있는가. 없으면 한글은 기본 글꼴로 그려진다 — 목록에서 알려 준다. */
    val hasHangul: Boolean = true,
) {
    enum class Kind { System, User }
}

/** 한 글꼴의 보통·굵게. 굵게 파일이 없으면 [bold] 는 null 이고 측정기가 합성한다. */
data class FontPair(val regular: Typeface, val bold: Typeface?)

/**
 * 본문 글꼴의 출처.
 *
 * **번들 폰트를 쓰지 않는다**(2026-09-23 결정, B2 번복). 번들의 근거는 "시스템 폰트는 기기마다
 * 조판이 다르다" 였지만, 위치를 글자 오프셋으로 저장하고 캐시를 기기마다 따로 만들므로 그
 * 차이는 사용자에게 드러나지 않는다. 반면 번들은 APK 12MB 중 11MB 였고, 책이 이미 같은
 * 폰트를 내장한 경우가 많았다(올려 받은 세 권 모두 KoPub 을 내장).
 *
 * 목록은 세 갈래뿐이다(2026-09-23 사용자 결정): 출판사 글꼴(책에 든 것, 화면 쪽이 더한다) ·
 * 휴대폰 글꼴 · 사용자 글꼴. 시스템 명조와 추천 글꼴 받기는 한때 있었지만 뺐다 — 목록이 길어질
 * 뿐 고를 이유가 흐려진다. 명조로 읽고 싶은 사람은 명조 파일을 사용자 글꼴로 넣는다.
 */
open class FontCatalog(
    /** 사용자가 넣은 글꼴. 없으면 시스템 글꼴만. */
    val user: UserFonts? = null,
) {

    /**
     * 처음 쓰는 사람의 본문 글꼴 — 휴대폰 글꼴. 예전에 고른 명조(`system-serif`)나 지운 사용자 글꼴도
     * [effectiveKey] 를 거쳐 여기로 온다.
     */
    val defaultKey: String get() = SANS

    open fun options(): List<FontOption> = buildList {
        // "고딕" 이 아니라 "휴대폰 글꼴" 이다. 삼성은 설정 › 글꼴 스타일(SamsungOne, 굵은 고딕,
        // 내려받은 글꼴…)로 시스템 산세리프 자체를 바꾼다. 그 선택을 따르는 항목이므로 모양이
        // 아니라 출처로 부른다.
        add(FontOption(SANS, "휴대폰 글꼴", FontOption.Kind.System))
        user?.families()?.forEach { add(FontOption(it.key, it.label, FontOption.Kind.User, it.hasHangul)) }
    }

    /** 설정 값(없거나 모르는 값 포함)을 실제로 쓸 글꼴 키로. 모르면 기본값 — 책은 열려야 한다. */
    fun effectiveKey(key: String?): String =
        key?.takeIf { k -> options().any { it.key == k } } ?: defaultKey

    /**
     * `LayoutSpec.fontId` 에 넣는 값: 키 + **글자 폭 지문**.
     *
     * 시스템 폰트는 OS 업데이트나 삼성 "글꼴 스타일" 변경으로 바뀐다. 이름만 캐시 키에 넣으면
     * 옛 폭으로 조판한 페이지를 새 폰트로 그리게 된다(양쪽정렬이 어긋난다). 기준 문자열을 실제로
     * 재서 키에 넣으면, 폰트가 바뀌는 순간 다른 캐시가 된다.
     */
    fun layoutFontId(key: String?): String {
        val effective = effectiveKey(key)
        val pair = pair(effective)
        // 굵게 파일을 나중에 더하면 굵은 글자의 폭이 바뀐다. 보통만 재면 옛 캐시가 남는다.
        val bold = pair.bold?.let(::fingerprint) ?: "fake"
        return "$effective@${fingerprint(pair.regular)}.$bold"
    }

    /** 목록에서 이름을 그 글꼴로 그릴 때 쓰는 보통 서체. */
    fun typeface(key: String?): Typeface = pair(effectiveKey(key)).regular

    /** [layoutFontId] 가 가리키는 글꼴. 지문은 무시한다(지금 기기의 폰트가 곧 답이다). */
    fun resolve(layoutFontId: String): FontPair = pair(effectiveKey(layoutFontId.substringBefore('@')))

    /**
     * 사용자 글꼴이면 그 파일, 아니면 휴대폰 글꼴.
     *
     * 목록을 한 번만 읽는다. "있나?" 와 "가져오기" 를 따로 하면 그 사이에 글꼴이 빠질 수 있다(빼기는 입출력
     * 스레드, 조판은 조판 스레드) — 그러면 여기서 죽고 다시 조판이 조용히 실패한다.
     */
    protected open fun pair(key: String): FontPair =
        user?.family(key)?.let(user::pair) ?: FontPair(Typeface.SANS_SERIF, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))

    companion object {
        const val SANS = "system-sans"

        private const val PROBE = "가나다라 漢字 ABCabc 0123 ,.「」"

        /** 기준 문자열의 폭으로 만든 짧은 지문. 같은 폰트면 언제나 같다. */
        fun fingerprint(typeface: Typeface): String {
            val paint = Paint(AndroidTextMeasurer.PAINT_FLAGS).apply { this.typeface = typeface; textSize = 100f }
            var hash = 0x811C9DC5.toInt()
            for (i in PROBE.indices) {
                val w = (paint.measureText(PROBE, i, i + 1) * 64).toInt()
                hash = (hash xor w) * 0x01000193
            }
            return (hash.toLong() and 0xFFFFFFFFL).toString(16)
        }
    }
}
