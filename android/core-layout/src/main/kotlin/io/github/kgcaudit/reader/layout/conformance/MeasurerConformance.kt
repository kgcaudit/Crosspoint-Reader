package io.github.kgcaudit.reader.layout.conformance

import io.github.kgcaudit.reader.layout.TextMeasurer
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.layout.VerticalAlign
import kotlin.math.abs

/**
 * [TextMeasurer] 구현이 지켜야 하는 성질을 검사한다.
 *
 * 왜 테스트 프레임워크 없이 **main 소스**에 있는가: 진짜 구현은
 * `android.graphics.Paint` 를 쓰므로 기기(또는 Robolectric)에서만 돌 수 있고, 그쪽
 * 계측 테스트가 이 검사를 그대로 불러 써야 한다. JUnit 에 묶어 두면 두 벌로 갈라지고,
 * 두 벌은 반드시 어긋난다.
 *
 * 여기서 걸러 내려는 것은 "폰트가 한글을 못 그린다", "폭이 더해지지 않는다" 처럼
 * **기기에서만 드러나면서 증상이 조판 붕괴로 나타나는** 것들이다. 그런 실패는 화면을
 * 보고 원인을 짚기가 매우 어렵다.
 *
 * 쓰는 법:
 * ```
 * val problems = MeasurerConformance.check(AndroidTextMeasurer(paint))
 * assertTrue(problems.isEmpty(), problems.joinToString("\n"))
 * ```
 */
object MeasurerConformance {

    /**
     * 크기 배율처럼 곱셈으로 맞아야 하는 값에 허용하는 **상대** 오차.
     *
     * 작은 크기에서 힌팅 때문에 정확히 두 배가 안 되는 폰트가 있어 6% 를 둔다.
     */
    const val SCALE_TOLERANCE: Float = 0.06f

    /**
     * 구간을 쪼개 재거나 공백 폭을 비교할 때 허용하는 오차 — **기준 글자 크기(em)에
     * 대한 비율**이다.
     *
     * 상대 오차로 두면 안 된다. 긴 글에서는 상대 오차가 커져서, 글자 한 칸을 넘는
     * 어긋남도 "몇 퍼센트뿐" 으로 통과해 버린다. 쪼개는 지점에서 잃거나 얻을 수 있는
     * 것은 커닝 한 쌍뿐이므로 글자 한 칸의 1/4 면 넉넉하다.
     */
    const val EM_TOLERANCE: Float = 0.25f

    private val LATIN = "The quick brown fox jumps over the lazy dog."
    private val HANGUL = "어린 왕자는 사막에서 조종사를 만났다."
    private val MIXED = "Chapter 1 — 제1장, 1943년."

    /**
     * 위반 목록. 빈 목록이면 이 구현으로 조판해도 된다.
     *
     * 문장으로 돌려주는 이유: 계측 테스트가 실패했을 때 로그에 남는 한 줄이 곧 원인이
     * 되어야 한다. `assertTrue(false)` 만 남으면 기기에서 다시 파야 한다.
     */
    fun check(measurer: TextMeasurer): List<String> {
        val problems = ArrayList<String>()
        problems += checkBasics(measurer)
        problems += checkAdvances(measurer)
        problems += checkVerticalMetrics(measurer)
        problems += checkScaling(measurer)
        problems += checkDeterminism(measurer)
        problems += checkScriptCoverage(measurer)
        return problems
    }

    // ── 기본값 ──────────────────────────────────────────────────────

    private fun checkBasics(m: TextMeasurer): List<String> {
        val problems = ArrayList<String>()
        if (m.baseSizePx <= 0f) {
            problems += "baseSizePx 가 ${m.baseSizePx} 다. 양수여야 한다 — 0 이면 모든 em 환산이 0 이 된다."
        }
        val empty = m.advance(LATIN, 3, 3, TextStyle.Default)
        if (empty != 0f) {
            problems += "빈 구간의 폭이 $empty 다. 0 이어야 한다 — 빈 런이 줄 폭을 먹으면 줄바꿈이 어긋난다."
        }
        return problems
    }

    // ── 폭 ──────────────────────────────────────────────────────────

    private fun checkAdvances(m: TextMeasurer): List<String> {
        val problems = ArrayList<String>()
        val style = TextStyle.Default

        for (text in listOf(LATIN, HANGUL, MIXED)) {
            val whole = m.advance(text, 0, text.length, style)
            if (whole <= 0f) {
                problems += "\"${text.take(12)}…\" 의 폭이 $whole 다. 글자가 있는데 폭이 0 이면" +
                    " 한 줄에 무한히 들어가고, 조판기가 같은 자리를 맴돈다."
                continue
            }

            // 더해져야 한다. 조판기는 줄 안의 조각 폭을 합해 전체를 구한다.
            val split = text.length / 2
            val parts = m.advance(text, 0, split, style) + m.advance(text, split, text.length, style)
            if (!withinEm(whole, parts, m)) {
                problems += "\"${text.take(12)}…\" 를 쪼개 재면 폭이 달라진다($whole vs $parts)." +
                    " 조판기는 조각 폭을 합하므로, 어긋나면 줄이 지면을 넘거나 짧게 끊긴다."
            }

            // 길어지면 좁아질 수 없다.
            var previous = 0f
            for (end in 0..text.length) {
                val width = m.advance(text, 0, end, style)
                if (width < previous - 0.01f) {
                    problems += "\"${text.take(12)}…\" 의 폭이 ${end}번째 글자에서 줄어든다" +
                        "($previous → $width). 줄바꿈 이분 탐색이 성립하지 않는다."
                    break
                }
                previous = width
            }
        }

        val space = m.spaceAdvance(style)
        if (space <= 0f) {
            problems += "공백 폭이 $space 다. 양쪽정렬은 이 값으로 간격을 늘리므로 0 이면 정렬이 죽는다."
        } else if (!withinEm(space, m.advance(" ", 0, 1, style), m)) {
            problems += "spaceAdvance($space) 와 advance(\" \")(${m.advance(" ", 0, 1, style)}) 가 다르다." +
                " 양쪽정렬이 늘린 만큼과 실제로 그려지는 만큼이 어긋난다."
        }
        return problems
    }

    // ── 세로 지표 ───────────────────────────────────────────────────

    private fun checkVerticalMetrics(m: TextMeasurer): List<String> {
        val problems = ArrayList<String>()
        for (style in styles()) {
            val height = m.lineHeight(style)
            val ascent = m.ascent(style)
            val label = describe(style)

            if (height <= 0f) {
                problems += "$label 의 줄 높이가 $height 다. 페이지가 무한히 많은 줄을 받아들인다."
            }
            if (ascent <= 0f) {
                problems += "$label 의 ascent 가 $ascent 다. 베이스라인이 지면 위로 올라가 글자가 잘린다."
            }
            if (height > 0f && ascent > height) {
                problems += "$label 의 ascent($ascent) 가 줄 높이($height) 보다 크다. 줄이 겹쳐 그려진다."
            }
        }
        return problems
    }

    // ── 크기 배율 ───────────────────────────────────────────────────

    private fun checkScaling(m: TextMeasurer): List<String> {
        val problems = ArrayList<String>()
        val normal = TextStyle.Default
        val double = TextStyle(sizeScale = 2f)

        val one = m.advance(HANGUL, 0, HANGUL.length, normal)
        val two = m.advance(HANGUL, 0, HANGUL.length, double)
        if (one > 0f && !nearRelative(two, one * 2f)) {
            problems += "sizeScale=2 인데 폭이 ${two} 다(1배의 두 배는 ${one * 2f})." +
                " 제목이 본문 폭 계산을 그대로 쓰면 제목 줄이 지면을 넘는다."
        }

        val heightOne = m.lineHeight(normal)
        val heightTwo = m.lineHeight(double)
        if (heightOne > 0f && heightTwo <= heightOne) {
            problems += "sizeScale=2 인데 줄 높이가 커지지 않았다($heightOne → $heightTwo)." +
                " 큰 제목이 앞뒤 줄과 겹친다."
        }
        return problems
    }

    // ── 결정성 ──────────────────────────────────────────────────────

    private fun checkDeterminism(m: TextMeasurer): List<String> {
        val problems = ArrayList<String>()
        for (style in styles()) {
            val first = m.advance(MIXED, 0, MIXED.length, style)
            val again = m.advance(MIXED, 0, MIXED.length, style)
            if (first != again) {
                problems += "${describe(style)} 를 두 번 재니 값이 다르다($first vs $again)." +
                    " 캐시된 페이지와 화면이 어긋난다 — 증상이 \"가끔 글자가 밀린다\" 로 나타난다."
            }
        }
        return problems
    }

    // ── 문자 지원 ───────────────────────────────────────────────────

    /**
     * 폰트가 실제로 그 글자를 갖고 있는지 본다.
     *
     * 기기에서만 드러나는 실패다. 글리프가 없으면 폭이 0 이거나 모두 같은 값(두부)이
     * 되고, 그러면 조판은 멀쩡한데 화면이 깨진다. 한글은 이 앱의 존재 이유라 따로 본다.
     */
    private fun checkScriptCoverage(m: TextMeasurer): List<String> {
        val problems = ArrayList<String>()
        val style = TextStyle.Default

        val samples = mapOf(
            "한글 음절" to "가",
            "한글 자모 조합" to "각",
            "한자" to "漢",
            "라틴" to "A",
            "숫자" to "1",
            "한글 문장부호" to "、",
        )
        for ((label, glyph) in samples) {
            val width = m.advance(glyph, 0, 1, style)
            if (width <= 0f) {
                problems += "$label('$glyph') 의 폭이 $width 다. 폰트에 이 글자가 없다 —" +
                    " 조판은 맞지만 화면에는 빈칸이나 두부가 보인다."
            }
        }

        // 전각(한글·한자)은 라틴 소문자보다 넓다. 모든 글자가 같은 폭으로 나오면
        // 대개 폰트를 못 찾아 대체 폰트가 통째로 들어온 경우다.
        val hangul = m.advance("가", 0, 1, style)
        val latin = m.advance("i", 0, 1, style)
        if (hangul > 0f && latin > 0f && hangul <= latin) {
            problems += "한글('가', $hangul) 이 라틴 소문자('i', $latin) 보다 넓지 않다." +
                " 폰트가 한글을 대체 폰트로 그리고 있을 수 있다."
        }
        return problems
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private fun styles(): List<TextStyle> = listOf(
        TextStyle.Default,
        TextStyle(bold = true),
        TextStyle(italic = true),
        TextStyle(sizeScale = 0.75f, vertical = VerticalAlign.Superscript),
        TextStyle(sizeScale = 2f, bold = true),
    )

    private fun describe(style: TextStyle): String = buildString {
        append("서식(크기 ").append(style.sizeScale)
        if (style.bold) append(", 굵게")
        if (style.italic) append(", 기울임")
        if (style.vertical != VerticalAlign.Baseline) append(", ").append(style.vertical.name)
        append(')')
    }

    private fun nearRelative(a: Float, b: Float): Boolean {
        val scale = maxOf(abs(a), abs(b), 1f)
        return abs(a - b) / scale <= SCALE_TOLERANCE
    }

    /** 글자 한 칸의 [EM_TOLERANCE] 배 안에 들어오는가. */
    private fun withinEm(a: Float, b: Float, m: TextMeasurer): Boolean =
        abs(a - b) <= maxOf(m.baseSizePx, 1f) * EM_TOLERANCE
}
