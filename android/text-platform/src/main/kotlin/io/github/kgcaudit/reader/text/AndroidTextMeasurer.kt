package io.github.kgcaudit.reader.text

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextMeasurer
import io.github.kgcaudit.reader.layout.TextStyle

/**
 * `Paint` 로 글자 폭을 재는 [TextMeasurer].
 *
 * 커닝·한글 조합·대체 글꼴(이모지, 고른 글꼴에 없는 한자) 셰이핑은 플랫폼이 한다.
 * 이 클래스가 하는 일은 서식별 `Paint` 를 만들어 두는 것과, 세로 지표를 글꼴과 무관하게
 * 맞추는 것뿐이다.
 *
 * **한 스레드에서만 쓴다.** `Paint` 는 스레드 안전하지 않다. 조판용과 그리기용을 따로
 * 만들면 된다 — 같은 입력이면 같은 값이 나오므로 둘이 어긋나지 않는다.
 */
class AndroidTextMeasurer(
    private val regular: Typeface,
    /** null 이면 굵게를 합성한다(굵은 파일이 없는 사용자 글꼴). */
    private val bold: Typeface?,
    override val baseSizePx: Float,
    /**
     * 책 글꼴. null 이면 [TextStyle.face] 를 무시하고 모두 본문 글꼴로 잰다(출판사 글꼴을 끈 상태).
     *
     * 세로 지표(줄 높이·베이스라인)는 책 글꼴이 섞여도 **본문 글꼴 하나로** 정한다. 가족마다 따로
     * 재면 제목 글꼴이 든 줄만 베이스라인이 달라져 줄 간격이 들쭉날쭉해진다.
     */
    private val book: BookTypefaces? = null,
) : TextMeasurer {

    init {
        require(baseSizePx > 0f) { "baseSizePx must be positive, was $baseSizePx" }
    }

    /**
     * 베이스라인이 줄 위쪽에서 얼마나 내려오는가(em).
     *
     * 폰트의 ascent 를 그대로 쓰지 않는다. 책에 흔한 KoPubWorld 는 hhea ascent 가 1.05em,
     * descent 가 0.49em 이라 줄 높이가 글자 크기의 1.54배가 되고, 사용자가 흔히 넣는
     * Pretendard 는 1.19배다. 그대로 쓰면 **글꼴만 바꿔도 한 페이지의 줄 수가 30% 가까이 바뀌고**,
     * 사용자가 고른 줄 간격 1.2 가 글꼴마다 다른 간격이 된다.
     *
     * 대신 줄 높이를 1em 으로 두고(줄 간격 배수는 조판기가 곱한다), 베이스라인은 실제
     * 글리프 윗변에 맞춘다. 첫 줄의 글자 머리가 위 여백에 정확히 닿는다.
     *
     * 굵은 글꼴이 아니라 보통 글꼴 하나에서만 잰다. 굵게마다 따로 재면 굵은 단어가 섞인
     * 줄만 베이스라인이 조금 내려가 줄 간격이 들쭉날쭉해 보인다.
     */
    private val ascentEm: Float = glyphTopEm(regular)

    private val cache = HashMap<Key, Metrics>()

    override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle): Float {
        if (endExclusive <= start) return 0f
        return metrics(style).paint.measureText(text, start, endExclusive)
    }

    override fun lineHeight(style: TextStyle): Float = metrics(style).sizePx

    override fun ascent(style: TextStyle): Float = metrics(style).sizePx * ascentEm

    override fun spaceAdvance(style: TextStyle): Float = metrics(style).space

    /**
     * 이 서식을 그릴 때 쓸 `Paint`.
     *
     * 그리는 쪽은 반드시 이것을 써야 한다. 플래그 하나(서브픽셀, 기울임)만 달라도 잰
     * 폭과 그린 폭이 어긋나 양쪽정렬된 줄 끝이 들쭉날쭉해진다. **고치지 말고 읽기만
     * 한다** — 캐시된 객체라 고치면 이후의 측정이 전부 틀어진다.
     */
    fun paintFor(style: TextStyle): TextPaint = metrics(style).paint

    private fun metrics(style: TextStyle): Metrics {
        // 밑줄·취소선·위첨자는 폭을 바꾸지 않으므로 키에서 뺀다. 넣으면 같은 Paint 가
        // 서식 조합 수만큼 복제된다.
        val key = Key(style.bold, style.italic, style.sizeScale, if (book != null) style.face else 0)
        return cache.getOrPut(key) { Metrics(newPaint(key)) }
    }

    /**
     * 서식마다 `Paint` 를 따로 둔다.
     *
     * 하나를 두고 `textSize` 를 바꿔 가며 재면 `Paint` 내부의 글리프 캐시가 매번
     * 무효화돼 조판이 수 배 느려진다. 제목·본문·각주가 번갈아 나오는 챕터에서 두드러진다.
     */
    private fun newPaint(key: Key): TextPaint =
        TextPaint(PAINT_FLAGS).apply {
            val fromBook = if (key.face > 0) book?.select(key.face, key.bold) else null
            if (fromBook != null) {
                typeface = fromBook.first
                isFakeBoldText = fromBook.second
            } else {
                typeface = if (key.bold && bold != null) bold else regular
                // 굵은 파일이 없으면 획을 두껍게 그린다. 합성 굵게는 폭을 조금 넓히므로 잴 때도
                // 같은 플래그여야 한다 — 그래서 그리기와 재기가 같은 이 Paint 를 쓴다.
                isFakeBoldText = key.bold && bold == null
            }
            textSize = baseSizePx * key.sizeScale
            // 한글 글꼴에는 기울임꼴이 없다. 기울이기는 폭을 바꾸지 않으므로 조판과
            // 무관하고, 그리는 쪽도 이 Paint 를 쓰니 같은 모양이 나온다.
            textSkewX = if (key.italic) ITALIC_SKEW else 0f
        }

    private data class Key(val bold: Boolean, val italic: Boolean, val sizeScale: Float, val face: Int)

    private class Metrics(val paint: TextPaint) {
        val sizePx: Float = paint.textSize
        val space: Float = paint.measureText(" ")
    }

    companion object {
        /**
         * 서브픽셀은 켜고 `LINEAR_TEXT_FLAG` 는 끈다.
         *
         * 선형 텍스트를 켜면 폭이 소수로 나와 크기에 정확히 비례하지만, 플랫폼이 글리프
         * 캐시를 끄므로 페이지를 그릴 때마다 글리프를 새로 래스터화한다. 페이지 넘김
         * 16ms 예산과 맞바꿀 수 없다. 힌팅으로 폭이 반올림되는 오차는 조판기가 합산할
         * 때 그대로 따라가므로(잰 값으로 놓고 같은 Paint 로 그린다) 화면에서는 어긋나지
         * 않는다.
         */
        const val PAINT_FLAGS: Int = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG

        /** 흔한 합성 기울임 값(약 14°). 플랫폼의 가짜 이탤릭과 같다. */
        const val ITALIC_SKEW: Float = -0.25f

        /** 글리프 윗변을 잴 때의 크기. 정수 좌표 반올림 오차를 0.1% 로 줄인다. */
        private const val PROBE_SIZE_PX: Float = 1000f

        /** 윗변을 재는 글자. 한글·한자·라틴 대문자·어센더 중 가장 높은 것을 따른다. */
        private const val PROBE_GLYPHS: String = "가漢Hbdhklt"

        /** 글꼴에서 글리프를 못 찾았을 때. 대부분의 CJK 글꼴이 이 근처다. */
        private const val FALLBACK_ASCENT_EM: Float = 0.8f

        /**
         * 조판 설정에서 측정기를 만든다. **이 경로로만 만드는 것을 권한다.**
         *
         * 글꼴과 기준 크기를 [LayoutSpec] 에서 꺼내므로, 캐시 키와 실제로 잰 글꼴이
         * 갈라질 수 없다. 따로 넘기면 "설정은 고딕인데 명조로 잰 페이지가 고딕 캐시에
         * 들어가는" 일이 생긴다.
         */
        fun forSpec(fonts: FontCatalog, spec: LayoutSpec, book: BookTypefaces? = null): AndroidTextMeasurer {
            val pair = fonts.resolve(spec.fontId)
            // 설정이 책 글꼴을 끈 상태면 넘겨받아도 쓰지 않는다 — 캐시 키(useBookFonts)와 잰 글꼴이
            // 갈라지지 않게 한다.
            return AndroidTextMeasurer(pair.regular, pair.bold, spec.baseSizePx, book.takeIf { spec.useBookFonts })
        }

        internal fun glyphTopEm(typeface: Typeface): Float {
            val paint = Paint(PAINT_FLAGS).apply {
                this.typeface = typeface
                textSize = PROBE_SIZE_PX
            }
            val bounds = Rect()
            paint.getTextBounds(PROBE_GLYPHS, 0, PROBE_GLYPHS.length, bounds)
            if (bounds.isEmpty) return FALLBACK_ASCENT_EM
            // 1em 을 넘으면 ascent 가 줄 높이보다 커져 윗줄과 겹쳐 그려진다. 장식이 큰
            // 글꼴이라도 줄 안에 가둔다.
            return (-bounds.top / PROBE_SIZE_PX).coerceIn(0.5f, 1f)
        }
    }
}
