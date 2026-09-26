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
     * 듣기의 글과 문장들(결정 4). 글은 [text] 와 길이가 같다 — 문장 칠 · 쪽 따라가기가 같은 자리를 가리킨다.
     *
     * - 쪽 위아래 가장자리([EDGE])에 있는 짧은 줄(머리말 · 쪽 번호 · 잡지 이름)은 읽지 않는다. 읽으면 쪽이 바뀔
     *   때마다 "OLO 사용 설명서 이 장 넘기기 18" 을 듣는다. 가장자리라도 긴 줄은 본문이 쪽 끝까지 찬 것이라 읽는다.
     * - 문단 시작은 [paragraphStarts]. PDF 글에는 문단 표시가 없어, 이것이 틀리면 한 문장이 줄마다 끊겨 읽히고
     *   (0.18.0 의 실제 버그), 모자라면 마침표 없는 제목이 본문에 붙어 한 숨에 읽힌다.
     * - 줄 끝에서 한글 낱말이 끊긴 곳("흐↵르게")은 공백 없이 잇는다([joinedLines]). 줄바꿈을 공백으로 읽으면 낱말
     *   한가운데서 쉰다.
     */
    fun speech(): PdfSpeech {
        val all = lines
        if (all.isEmpty()) return PdfSpeech(text, splitSentences(text))
        val skipped = all.map { it.isMargin() }
        val starts = paragraphStarts(all, skipped)
        val spoken = joinedLines(all, skipped, starts)
        val sentences = splitSentences(spoken, starts).filter { s ->
            all.withIndex().none { (k, line) -> skipped[k] && s.start in line.start until line.endExclusive }
        }
        return PdfSpeech(spoken, sentences)
    }

    /**
     * 문단이 시작하는 글자 자리. 새 문단으로 보는 줄:
     * - 머리말 · 쪽 번호 앞뒤,
     * - 줄 사이가 **그 쪽의 보통 줄 간격**보다 크게 벌어진 줄 — 글자 높이와 견주면 안 된다. 줄 간격이 넉넉한
     *   책(글자 높이의 0.8배)에서 줄마다 새 문단이 된다(0.18.0 에서 소설 한 쪽 25줄 중 8줄이 그랬다),
     * - 글자 크기가 다른 줄(제목) 앞뒤,
     * - 들여 쓴 줄(문단 첫 줄 들여쓰기),
     * - 앞 줄이 짧게 끝났고 그 끝이 문장부호 · 닫는 따옴표인 줄. 문장부호를 함께 보는 까닭: 오른쪽이 들쭉날쭉한
     *   책은 문단 한가운데 줄도 짧게 끝난다.
     */
    private fun paragraphStarts(all: List<TextLine>, skipped: List<Boolean>): Set<Int> {
        val body = all.filterIndexed { k, _ -> !skipped[k] }
        val left = body.minOfOrNull { it.left } ?: 0f
        val right = body.maxOfOrNull { it.right } ?: 1f
        val width = (right - left).coerceAtLeast(1e-3f)
        val lineHeight = median(body.map { it.bottom - it.top })
        val gaps = (1 until all.size).filter { !skipped[it] && !skipped[it - 1] }.map { all[it].top - all[it - 1].bottom }.filter { it > 0f }
        val usualGap = if (gaps.size >= 2) median(gaps) else lineHeight * 0.8f
        val starts = HashSet<Int>()
        for (k in 1 until all.size) {
            val prev = all[k - 1]
            val cur = all[k]
            fun odd(line: TextLine) = lineHeight > 0f && kotlin.math.abs((line.bottom - line.top) - lineHeight) > lineHeight * 0.25f
            val prevEnd = text.substring(prev.start, prev.endExclusive).trimEnd().lastOrNull()
            val newParagraph = skipped[k] || skipped[k - 1] ||
                cur.top - prev.bottom > max(usualGap * 1.5f, lineHeight * 0.3f) ||
                odd(prev) || odd(cur) ||
                cur.left > left + lineHeight * 0.8f ||
                (prev.right < right - width * SHORT && prevEnd != null && prevEnd in PARAGRAPH_ENDS)
            if (newParagraph) starts += cur.start
        }
        return starts
    }

    /**
     * 줄바꿈 자리를 소리 내지 않는 글자(폭 없는 공백)로 바꾼 글 — [speakable] 이 지워 두 줄이 공백 없이 이어진다.
     * 앞 줄이 오른쪽 끝까지 찼고(끊긴 곳이 줄 끝) 양쪽이 한글일 때만. 오른쪽이 들쭉날쭉한 책(앞 줄이 짧게 끝남)은
     * 어절 단위로 줄을 바꾼 것이라 공백으로 둔다. 어절 사이에서 끊긴 줄을 붙이면 두 어절이 이어 읽힐 뿐이지만,
     * 낱말 한가운데를 띄우면 낱말이 둘로 쪼개져 들린다 — 그래서 한글끼리는 붙이는 쪽을 고른다.
     * 영어는 줄 끝 하이픈("exam-↵ple")만 지우고 잇는다.
     */
    private fun joinedLines(all: List<TextLine>, skipped: List<Boolean>, starts: Set<Int>): String {
        val body = all.filterIndexed { k, _ -> !skipped[k] }
        val left = body.minOfOrNull { it.left } ?: 0f
        val right = body.maxOfOrNull { it.right } ?: 1f
        val width = (right - left).coerceAtLeast(1e-3f)
        val out = StringBuilder(text)
        for (k in 1 until all.size) {
            val prev = all[k - 1]
            val cur = all[k]
            if (skipped[k] || skipped[k - 1] || cur.start in starts) continue
            if (prev.right < right - width * FULL) continue
            val last = text[prev.endExclusive - 1]
            val first = text[cur.start]
            val hangul = isHangul(last) && isHangul(first)
            val hyphen = last == '-' && prev.endExclusive >= 2 && text[prev.endExclusive - 2].isLetter() && first.isLetter() && !isHangul(first)
            if (!hangul && !hyphen) continue
            if (hangul && endsWord(prev, cur)) continue
            val from = if (hyphen) prev.endExclusive - 1 else prev.endExclusive
            for (i in from until cur.start) out.setCharAt(i, SILENT)
        }
        return out.toString()
    }

    /**
     * 한글 줄바꿈이 어절 사이인가(그러면 공백으로 둔다). 글자 단위로 줄을 바꾼 책도 어절 사이에서 끊기는 일이 많아,
     * 모두 붙이면 "영주가커피" 처럼 두 어절이 붙어 들린다(실제 소설 PDF 에서).
     * - 다음 줄이 조사 · 어미 하나로 시작하면("을 닦고", "고 묻지") 낱말이 이어진 것 — 붙인다.
     * - 앞 줄 끝 조각이 두 글자 이상이고 조사 · 어미로 끝나면("그러는", "영주가") 어절 끝 — 띄운다.
     * - 그 밖(한 글자 조각 "흐↵르게" · "평↵생")은 붙인다.
     * "다" · "지" 는 끝 글자로 보지 않는다 — "기다↵리다" 처럼 낱말 한가운데에도 흔하다.
     */
    private fun endsWord(prev: TextLine, cur: TextLine): Boolean {
        var e = cur.start
        while (e < cur.endExclusive && isHangul(text[e])) e++
        if (text.substring(cur.start, e) in BOUND) return false
        var s = prev.endExclusive
        while (s > prev.start && isHangul(text[s - 1])) s--
        return prev.endExclusive - s >= 2 && text[prev.endExclusive - 1] in WORD_ENDS
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

        /** 앞 줄이 오른쪽 끝에서 이만큼 안이면 "끝까지 찬 줄" 이다(양쪽 맞춤의 오차). */
        const val FULL = 0.03f
        /** 문단 끝으로 볼 줄 끝 글자(짧게 끝난 줄에서만 본다). */
        private const val PARAGRAPH_ENDS = ".!?…。！？:\"'”’)」』》"
        /** 줄 머리에 홀로 오면 앞 줄 낱말에 이어지는 조사 · 어미. */
        private val BOUND = setOf(
            "을", "를", "이", "가", "은", "는", "에", "의", "도", "고", "다", "요", "게", "서", "와", "과", "로", "만", "면", "며",
            "지", "께", "랑", "이다", "이었다", "였다", "했다", "에서", "에게", "으로", "로서", "처럼", "까지", "부터",
        )
        /** 어절 끝에 흔한 조사 · 어미 글자(두 글자 이상 조각의 끝일 때만 본다). */
        private const val WORD_ENDS = "는은을를가고서며게에의도와과로면요"
        /** 소리 내지 않고 [speakable] 이 지우는 글자(폭 없는 공백). */
        private const val SILENT = '\u200B'

        private fun isHangul(c: Char): Boolean = c in '\uAC00'..'\uD7A3'

        private fun median(values: List<Float>): Float = values.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }

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

/** PDF 쪽 하나의 듣기: 엔진에 줄 글([text], 원문과 길이가 같다)과 문장들. */
class PdfSpeech(val text: String, val sentences: List<Sentence>)
