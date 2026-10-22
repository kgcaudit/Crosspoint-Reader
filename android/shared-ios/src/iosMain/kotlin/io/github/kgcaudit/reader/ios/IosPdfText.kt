package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.layout.book.findAll
import io.github.kgcaudit.reader.layout.pdf.BrokenHangul
import io.github.kgcaudit.reader.layout.pdf.PageText
import io.github.kgcaudit.reader.layout.pdf.layerHits

/** 쪽 위에 칠할 네모 하나(쪽 비율). [memo] 면 칠이 아니라 끝 글자 옆의 메모 쪽지 자리다. */
class PdfPaintBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val rgb: Int, val alpha: Double, val memo: Boolean)

/** 쪽에 보이는 칠 하나(이 쪽 글자 층의 구간). */
class PdfTint(val start: Int, val end: Int, val color: Int, val hasNote: Boolean)

/**
 * PDF 글자 기능(구상안 34A): 찾기 · 고르기 · 칠 · 듣기 문장. 글자 층의 셈은 공통 `PageText`(안드로이드 `reader-pdf` 에서 옮겨 온
 * 것)가 하고, 여기는 iOS 가 PDFKit 에서 꺼낸 글자와 네모를 그 층으로 만들고 화면이 쓰는 모양으로 바꾼다.
 *
 * 글자 번호는 화면에서 쪽 번호와 묶어 하나의 수로 다닌다([encode]) — 리더의 고르기 · 칠 길은 한 장(EPUB 의 장) 안 번호 하나를
 * 쓰는데, PDF 는 쪽마다 번호가 따로다.
 */
object IosPdfText {
    /** 한 쪽의 글자 수 한도(넘으면 다음 쪽 번호와 섞인다). 실제 쪽은 수천 자다. */
    const val PAGE_SPAN: Int = 100_000

    fun encode(page: Int, char: Int): Int = page * PAGE_SPAN + char.coerceIn(0, PAGE_SPAN - 1)
    fun pageOf(index: Int): Int = index / PAGE_SPAN
    fun charOf(index: Int): Int = index % PAGE_SPAN

    /**
     * PDFKit 의 글([text])과 글자마다의 네모([boxes] — 왼 · 위 · 오른 · 아래, 쪽 비율, 모르면 NaN)로 만든 층. 안드로이드와 **같은 차례로**
     * 다듬는다(되살리기 → 보이는 차례 → 겹쳐 찍은 글자 빼기) — 형광펜 자리는 다듬은 층의 글자 번호라, 차례가 다르면 백업으로 오간 칠이
     * 다른 글자에 앉는다. 네모 수가 맞지 않으면(깨진 입력) 자리 없는 층으로 — 찾기 · 듣기는 된다.
     */
    fun layer(text: String, boxes: FloatArray): PageText {
        if (text.isEmpty()) return PageText.EMPTY
        val raw = if (boxes.size == text.length * 4) PageText(text, boxes) else PageText.textOnly(text)
        return runCatching { raw.repaired().inVisualOrder().withoutOverprint() }.getOrElse { raw }
    }

    /** 찾기용 쪽 글(안드로이드 `plainText` + `searchPage`): 되살린 글, 줄 끝 "\r" 은 한 칸으로(자리 수는 그대로). */
    fun searchText(text: String): String = BrokenHangul.repair(text).replace('\r', ' ')

    /** 쪽 하나에서 찾는다. 읽을 글이 없는 쪽(그림뿐)은 없다. 결과의 spine 은 쪽 번호. */
    fun search(text: String, page: Int, query: String): List<SearchRow> {
        val plain = searchText(text)
        if (query.isBlank() || !PageText.isReadable(plain)) return emptyList()
        return findAll(plain, query, page).map { SearchRow(page, it.start, it.endExclusive, it.context, it.contextMatchStart, 0f, "") }
    }

    /**
     * 찾은 말을 층에서 칠할 구간: 그 쪽에서 [ordinal] 번째로 찾은 것(쪽 글과 층은 번호가 다를 수 있어 차례로 맞춘다 — 안드로이드와 같다).
     * 없으면 마지막, 그것도 없으면 null.
     */
    fun hit(layer: PageText, query: String, ordinal: Int): IntArray? {
        val all = layerHits(layer, query)
        val r = all.getOrNull(ordinal) ?: all.lastOrNull() ?: return null
        return intArrayOf(r.first, r.last + 1)
    }

    /** 칠 · 찾은 말 · 듣는 문장의 네모들(그리는 차례: 듣는 문장 → 칠 → 찾은 말 → 쪽지). 어두운 지면은 옅게(책과 같은 값). */
    fun paint(layer: PageText, tints: List<PdfTint>, mark: IntArray?, speaking: IntArray?, dark: Boolean, accent: Int): List<PdfPaintBox> {
        val out = ArrayList<PdfPaintBox>()
        fun add(s: Int, e: Int, rgb: Int, alpha: Double) {
            for (r in layer.rects(s.coerceIn(0, layer.length), e.coerceIn(0, layer.length))) out += PdfPaintBox(r.left, r.top, r.right, r.bottom, rgb, alpha, false)
        }
        speaking?.let { add(it[0], it[1], accent, if (dark) 0.30 else 0.18) }
        for (t in tints) add(t.start, t.end, IosBookReader.PEN_BASE.getOrElse(t.color) { IosBookReader.PEN_BASE[0] }, if (dark) 0.38 else 0.45)
        mark?.let { add(it[0], it[1], accent, 0.3) }
        for (t in tints.filter { it.hasNote }) {
            val last = layer.rects((t.end - 1).coerceAtLeast(0), t.end).lastOrNull() ?: continue
            val color = if (dark) IosBookReader.PEN_BASE.getOrElse(t.color) { 0 } else IosBookReader.PEN_DEEP.getOrElse(t.color) { 0 }
            out += PdfPaintBox(last.right, last.top, last.right, last.bottom, color, 1.0, true)
        }
        return out
    }

    /** 쪽 비율 자리의 글자(없으면 −1). */
    fun charAt(layer: PageText, x: Float, y: Float, after: Boolean): Int = layer.charAt(x, y, after) ?: -1

    /** [i] 가 든 어절 [시작, 끝). */
    fun wordAt(layer: PageText, i: Int): IntArray {
        val w = layer.wordAt(i)
        return intArrayOf(w.first, w.last + 1)
    }

    /** 고른 구간의 네모들(쪽 비율). */
    fun rects(layer: PageText, start: Int, end: Int): List<FloatArray> =
        layer.rects(start.coerceIn(0, layer.length), end.coerceIn(0, layer.length)).map { floatArrayOf(it.left, it.top, it.right, it.bottom) }

    /** 누른 자리(쪽 비율)에 걸린 칠. 없으면 −1. 줄 높이 안쪽만 — 칠 옆 여백을 눌러 메뉴가 뜨면 넘기려던 손이 막힌다. */
    fun tintAt(layer: PageText, tints: List<PdfTint>, x: Float, y: Float): Int {
        for ((k, t) in tints.withIndex()) {
            if (layer.rects(t.start.coerceIn(0, layer.length), t.end.coerceIn(0, layer.length)).any { x in it.left..it.right && y in it.top..it.bottom }) return k
        }
        return -1
    }

    /** 듣기 문장(글자 층 자리 그대로 — 문장 칠이 같은 자리를 가리킨다). [시작, 끝) 쌍을 이어 붙인 목록. */
    fun sentences(layer: PageText): IntArray {
        val sp = layer.speech()
        return sp.sentences.flatMap { listOf(it.start, it.endExclusive) }.toIntArray()
    }

    /** 듣기로 읽을 글(줄 끝 낱말을 이은 글 — 층과 길이가 같다). */
    fun spoken(layer: PageText, start: Int, end: Int): String {
        val text = layer.speech().text
        return text.substring(start.coerceIn(0, text.length), end.coerceIn(0, text.length)).trim()
    }

    /** 사람이 읽을 글이 있는가(스캔본 안내). */
    fun readable(text: String): Boolean = PageText.isReadable(searchText(text))
}
