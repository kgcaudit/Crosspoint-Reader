package io.github.kgcaudit.reader.text

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import io.github.kgcaudit.reader.layout.LayoutSpec

/**
 * 앱에 번들된 본문 글꼴.
 *
 * 시스템 글꼴을 쓰지 않는 이유: 기기마다 한글 글꼴이 달라서 같은 책·같은 설정이라도
 * 한 페이지에 들어가는 글자 수가 달라진다. 그러면 "12쪽에 있던 문장" 을 다른 기기에서
 * 찾을 수 없고, 한글 글꼴이 없는 기기에서는 대체 글꼴이 섞여 들어와 줄 끝이 흔들린다.
 *
 * 라이선스는 `assets/licenses/` 에 함께 있다. KoPubWorld 는 OFL 이 **아니라** KOPUS
 * 약관이다 — 재배포는 되지만 유료 판매가 금지되고, 받는 사람에게 약관을 알려야 하며,
 * 파일을 고치면(서브셋 포함) "KoPub" 이름을 쓸 수 없다. 그래서 원본 그대로 넣었다.
 */
enum class ReaderFont(
    /** 설정에 저장하는 값. 파일이 바뀌어도 그대로 둔다 — 바꾸면 사용자 설정이 풀린다. */
    val key: String,
    /**
     * 폰트 파일의 판. 파일을 교체하면 올린다.
     *
     * 같은 이름이라도 판이 다르면 글자 폭이 다르다. 이 값이 [layoutFontId] 에 들어가
     * 캐시 키를 바꾸지 않으면, 옛 폰트로 잰 페이지를 새 폰트로 그리게 된다.
     */
    private val revision: String,
    @FontRes private val regular: Int,
    @FontRes private val bold: Int,
) {
    /** KoPubWorld 바탕 1.0.3. 본문 기본값 — 종이책에 가까운 명조. */
    Batang("batang", "kopubworld-1.0.3", R.font.kopubworld_batang_medium, R.font.kopubworld_batang_bold),

    /** Pretendard 1.3.9. 화면에서 또렷한 고딕. */
    Gothic("gothic", "pretendard-1.3.9", R.font.pretendard_regular, R.font.pretendard_bold),
    ;

    /** [LayoutSpec.fontId] 에 넣는 값. */
    val layoutFontId: String get() = "$key@$revision"

    internal fun load(context: Context): Pair<Typeface, Typeface> =
        context.resources.getFont(regular) to context.resources.getFont(bold)

    companion object {
        val Default: ReaderFont = Batang

        /**
         * 설정 값이나 [LayoutSpec.fontId] 에서 글꼴을 찾는다.
         *
         * 모르는 값이면 기본 글꼴로 연다. 폰트를 빼거나 이름을 바꾼 뒤 옛 설정이 남아
         * 있어도 "책이 열리지 않는다" 가 되어서는 안 된다. 판이 달라진 옛 [layoutFontId]
         * 도 같은 글꼴로 이어진다 — 캐시 키는 새 판으로 다시 만들어지므로 섞이지 않는다.
         */
        fun of(value: String): ReaderFont {
            val key = value.substringBefore('@')
            return entries.firstOrNull { it.key == key } ?: Default
        }
    }
}
