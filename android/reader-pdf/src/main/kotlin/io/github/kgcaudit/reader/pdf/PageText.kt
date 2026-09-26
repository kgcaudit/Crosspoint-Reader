package io.github.kgcaudit.reader.pdf

import io.github.kgcaudit.reader.layout.book.Sentence
import io.github.kgcaudit.reader.layout.book.splitSentences
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 한 쪽의 글자 층: 글자와 글자마다의 네모(쪽 폭 · 높이에 대한 비율 0..1).
 *
 * 찾기 · 고르기 · 칠하기 · 듣기가 모두 이것 하나로 계산한다. 엔진(안드로이드 15 의 PdfRenderer)은 글자와 네모를
 * 내주기만 하고, "어느 글자를 눌렀나 · 이 구간은 어느 줄에 걸치나 · 어디서 문단이 바뀌나" 는 여기서 푼다 —
 * 그래야 기기 없이 시험할 수 있다.
 *
 * 자리를 모르는 글자(줄바꿈 · 엔진이 네모를 주지 않은 글자)는 네모가 NaN 이다. 그런 글자는 누를 수 없고 칠한
 * 네모에도 들어가지 않는다 — 글은 남는다(찾기 · 듣기에는 쓰인다).
 *
 * @param boxes 글자마다 네 수(왼 · 위 · 오른 · 아래). 길이는 글자 수 × 4.
 */
class PageText(val text: String, private val boxes: FloatArray) {
    init {
        require(boxes.size == text.length * 4) { "boxes must hold 4 numbers per char: ${boxes.size} for ${text.length}" }
    }

    val length: Int get() = text.length

    /** 글자 [i] 의 네모. 자리를 모르면 null. */
    fun box(i: Int): PageRegion? {
        if (i !in text.indices) return null
        val l = boxes[i * 4]
        val t = boxes[i * 4 + 1]
        val r = boxes[i * 4 + 2]
        val b = boxes[i * 4 + 3]
        if (l.isNaN() || t.isNaN() || r.isNaN() || b.isNaN() || r < l || b < t) return null
        return PageRegion(l, t, r, b)
    }

    /**
     * 줄들. 네모가 있는 글자를 차례로 보며, 앞 글자와 세로로 반 이상 겹치면 같은 줄이다. 줄바꿈 글자로 나누지 않는
     * 까닭: 엔진마다 줄 끝에 "\r\n" 을 두기도 하고 공백만 두기도 한다. 자리로 가르면 둘 다 맞는다.
     */
    val lines: List<TextLine> by lazy {
        val out = ArrayList<TextLine>()
        var start = -1
        var last = -1
        var l = 0f; var t = 0f; var r = 0f; var b = 0f
        fun close() {
            if (start >= 0) out += TextLine(start, last + 1, l, t, r, b)
            start = -1
        }
        for (i in text.indices) {
            // 공백은 줄을 가르지 않는다 — 엔진에 따라 공백에 높이 없는 네모를 줘서, 그것으로 가르면 줄이 조각난다.
            if (text[i].isWhitespace()) continue
            val box = box(i) ?: continue
            val same = start >= 0 && overlap(t, b, box.top, box.bottom) >= 0.5f * min(b - t, box.bottom - box.top) &&
                // 같은 높이라도 왼쪽으로 크게 되돌아가면 다음 단(여러 단 잡지)의 줄이다.
                box.left >= l - (b - t)
            if (!same) {
                close()
                start = i; l = box.left; t = box.top; r = box.right; b = box.bottom
            } else {
                l = min(l, box.left); t = min(t, box.top); r = max(r, box.right); b = max(b, box.bottom)
            }
            last = i
        }
        close()
        out
    }

    /** [start]..[endExclusive] 가 차지하는 네모, 줄마다 하나(줄 높이 그대로 — 칠이 줄마다 고르게 보인다). */
    fun rects(start: Int, endExclusive: Int): List<PageRegion> {
        val out = ArrayList<PageRegion>()
        for (line in lines) {
            val from = max(start, line.start)
            val to = min(endExclusive, line.endExclusive)
            if (from >= to) continue
            var left = Float.NaN
            var right = Float.NaN
            for (i in from until to) {
                val box = box(i) ?: continue
                if (text[i].isWhitespace()) continue
                left = if (left.isNaN()) box.left else min(left, box.left)
                right = if (right.isNaN()) box.right else max(right, box.right)
            }
            if (!left.isNaN()) out += PageRegion(left, line.top, right, line.bottom)
        }
        return out
    }

    /**
     * 쪽 비율 자리([x], [y])의 글자. 그 높이에 줄이 없으면 null(여백 · 그림 위를 누른 것). 줄 안이면 가로로 가장
     * 가까운 글자. [after] 면 그 글자의 **뒤** 자리를 준다(끝 손잡이 — 글자의 오른쪽 반을 누르면 그 글자까지 고른다).
     *
     * 줄 위아래로 줄 높이의 절반까지는 봐준다 — 손가락은 글자보다 굵고, 줄 사이를 누른 것을 "아무것도 아님" 으로
     * 두면 길게 눌러도 고르기가 자꾸 빗나간다.
     */
    fun charAt(x: Float, y: Float, after: Boolean = false): Int? {
        val line = lines.minByOrNull { distance(it, y) }?.takeIf { distance(it, y) <= (it.bottom - it.top) * 0.5f } ?: return null
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in line.start until line.endExclusive) {
            // 공백은 고를 글자가 아니다 — 어절 사이를 눌러도 가까운 어절을 잡는다.
            if (text[i].isWhitespace()) continue
            val box = box(i) ?: continue
            val d = when {
                x < box.left -> box.left - x
                x > box.right -> x - box.right
                else -> 0f
            }
            if (d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        if (best < 0) return null
        if (!after) return best
        val box = box(best)!!
        return if (x >= (box.left + box.right) / 2 || x > box.right) best + 1 else best
    }

    /** [i] 가 든 어절(공백 사이). EPUB 과 같다 — 한국어는 조사가 붙은 어절이 한 덩어리다. */
    fun wordAt(i: Int): IntRange {
        if (i !in text.indices || text[i].isWhitespace()) return i..i
        var s = i
        var e = i
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        while (e < text.length - 1 && !text[e + 1].isWhitespace()) e++
        return s..e
    }

    /**
     * 글 그대로 뜬 구간(독서노트 · 공유). 줄바꿈은 공백 하나로 — PDF 글은 줄마다 끊겨 있어 그대로 두면 인용이
     * 조각조각 보인다.
     */
    fun quote(start: Int, endExclusive: Int): String =
        text.substring(start.coerceIn(0, text.length), endExclusive.coerceIn(0, text.length)).replace(LINE_BREAKS, " ").trim()

    /**
     * 듣기의 문장들(결정 4).
     *
     * - 쪽 위아래 가장자리([EDGE])에 있는 짧은 줄(머리말 · 쪽 번호 · 잡지 이름)은 읽지 않는다. 읽으면 쪽이 바뀔
     *   때마다 "OLO 사용 설명서 이 장 넘기기 18" 을 듣는다. 가장자리라도 긴 줄은 본문이 쪽 끝까지 찬 것이라 읽는다.
     * - 문단 시작: 앞 줄이 짧게 끝났거나(글 폭보다 [SHORT] 이상 모자람) 줄 사이가 크게 벌어진 줄. PDF 글에는 문단
     *   표시가 없어, 이것이 없으면 마침표 없는 제목("2-1 확대하기")이 다음 문장에 붙어 한 숨에 읽힌다.
     */
    fun speech(): List<Sentence> {
        val all = lines
        if (all.isEmpty()) return splitSentences(text)
        val skipped = all.map { it.isMargin() }
        val body = all.filterIndexed { k, _ -> !skipped[k] }
        val left = body.minOfOrNull { it.left } ?: 0f
        val right = body.maxOfOrNull { it.right } ?: 1f
        val width = (right - left).coerceAtLeast(1e-3f)
        val lineHeight = body.map { it.bottom - it.top }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        val starts = HashSet<Int>()
        for (k in 1 until all.size) {
            val prev = all[k - 1]
            val cur = all[k]
            val newParagraph = skipped[k] || skipped[k - 1] ||
                prev.right < right - width * SHORT ||
                cur.top - prev.bottom > lineHeight * 0.8f
            if (newParagraph) starts += cur.start
        }
        return splitSentences(text, starts).filter { s -> all.withIndex().none { (k, line) -> skipped[k] && s.start in line.start until line.endExclusive } }
    }

    private fun TextLine.isMargin(): Boolean =
        (bottom <= EDGE || top >= 1f - EDGE) && text.substring(start, endExclusive).trim().length <= MARGIN_CHARS

    private fun distance(line: TextLine, y: Float): Float = when {
        y < line.top -> line.top - y
        y > line.bottom -> y - line.bottom
        else -> 0f
    }

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float): Float = min(a1, b1) - max(a0, b0)

    companion object {
        val EMPTY = PageText("", FloatArray(0))

        /** 머리말 · 쪽 번호로 볼 쪽 위아래 몫. A4 에서 약 24mm — 보통 여백 안쪽이다. */
        const val EDGE = 0.08f
        /** 머리말로 볼 줄의 최대 글자 수. 이보다 길면 본문이 가장자리까지 내려온 것이다. */
        const val MARGIN_CHARS = 40
        /** 앞 줄이 글 폭보다 이만큼 짧게 끝나면 문단이 끝난 것이다. */
        const val SHORT = 0.12f

        private val LINE_BREAKS = Regex("\\s*[\\r\\n]+\\s*")

        /**
         * 글자가 사람이 읽을 글인가(스캔본 · 글자 모양만 있는 PDF 를 가른다, 결정 1). 글자 · 숫자가 조금이라도 있고,
         * 깨진 글자(U+FFFD · 사용자 영역 · 제어 문자)가 5분의 1 이 안 될 때. 글자 모양을 문자로 되돌리는 표가 없는
         * PDF 는 엔진이 사용자 영역 글자를 내준다 — 그걸 찾거나 읽으면 아무것도 맞지 않는다.
         */
        fun isReadable(text: String): Boolean {
            var letters = 0
            var broken = 0
            for (c in text) {
                when {
                    c == '�' || c in ''..'' || (c < ' ' && c != '\n' && c != '\r' && c != '\t') -> broken++
                    c.isLetterOrDigit() -> letters++
                }
            }
            return letters >= MIN_LETTERS && broken * 5 < letters + broken
        }

        private const val MIN_LETTERS = 2

        /** 글자 네모가 하나도 없는 층(엔진이 네모를 주지 못했다). 찾기 · 듣기에만 쓰인다. */
        fun textOnly(text: String): PageText = PageText(text, FloatArray(text.length * 4) { Float.NaN })
    }
}

/** 한 줄: 글자 구간(끝은 마지막 보이는 글자 다음)과 그 줄을 담는 네모(쪽 비율). */
data class TextLine(val start: Int, val endExclusive: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)
