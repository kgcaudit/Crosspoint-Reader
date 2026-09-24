package io.github.kgcaudit.reader.document.pdf

/**
 * 쪽 이름표(`/PageLabels`): 책에 **인쇄된** 쪽 번호. 파일의 쪽 순서와 다를 수 있다 — 표지·머리말을
 * 로마 숫자(i, ii …)로 세고 본문을 1부터 세는 책, 잡지의 "표지 · 광고 · 4쪽부터" 같은 것.
 *
 * 목차의 쪽 번호는 이것으로 보여야 종이책 목차·색인의 번호와 맞는다. 파일 순서로 보이면 "12쪽" 이라고
 * 적힌 곳을 찾아가 보니 3쪽 어긋나 있다.
 *
 * 구간마다 방식(D 아라비아 · R/r 로마 대/소 · A/a 알파벳 대/소 · 없음)과 접두어, 시작값이 있다.
 */
class PdfPageLabels internal constructor(private val ranges: List<Range>) {

    internal data class Range(val start: Int, val style: Char?, val prefix: String, val first: Int)

    /** [pageIndex](0부터) 쪽의 이름. 이름표가 없는 앞쪽(첫 구간이 0 에서 시작하지 않음)은 파일 순서(1부터). */
    fun label(pageIndex: Int): String {
        val range = ranges.lastOrNull { it.start <= pageIndex } ?: return "${pageIndex + 1}"
        val n = range.first + (pageIndex - range.start)
        val number = when (range.style) {
            'D' -> n.toString()
            'R' -> roman(n)
            'r' -> roman(n).lowercase()
            'A' -> letters(n)
            'a' -> letters(n).lowercase()
            else -> ""
        }
        return range.prefix + number
    }

    /** 모든 쪽의 이름이 파일 순서(1, 2, 3 …)와 같다 — 따로 보일 까닭이 없다. */
    val isPlainNumbering: Boolean
        get() = ranges.all { it.style == 'D' && it.prefix.isEmpty() && it.first == it.start + 1 }

    private fun roman(value: Int): String {
        if (value !in 1..4999) return value.toString()
        val table = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var rest = value
        return buildString {
            for ((v, s) in table) while (rest >= v) { append(s); rest -= v }
        }
    }

    /** A–Z, 그다음 AA–ZZ, AAA… (명세: 같은 글자를 되풀이). */
    private fun letters(value: Int): String {
        if (value < 1) return value.toString()
        val letter = 'A' + (value - 1) % 26
        return letter.toString().repeat((value - 1) / 26 + 1)
    }

    internal companion object {
        /** 번호 나무(`/Nums` · `/Kids`)를 펼쳐 구간 목록으로. 깨진 구간은 버린다. 하나도 없으면 null. */
        fun read(file: PdfFile, tree: PdfObject?, decode: (PdfString) -> String): PdfPageLabels? {
            val ranges = ArrayList<Range>()
            val seen = HashSet<Int>()
            fun visit(node: PdfObject?, depth: Int) {
                if (depth > 32 || ranges.size > 10_000) return
                if (node is PdfRef && !seen.add(node.num)) return
                val dict = file.resolve(node) as? PdfDict ?: return
                (file.resolve(dict["Nums"]) as? PdfArray)?.items?.chunked(2)?.forEach { pair ->
                    val start = (file.resolve(pair.getOrNull(0)) as? PdfNumber)?.int ?: return@forEach
                    val label = file.resolve(pair.getOrNull(1)) as? PdfDict ?: return@forEach
                    if (start < 0) return@forEach
                    ranges.add(
                        Range(
                            start = start,
                            style = (label["S"] as? PdfName)?.name?.singleOrNull()?.takeIf { it in "DRrAa" },
                            prefix = (file.resolve(label["P"]) as? PdfString)?.let(decode).orEmpty(),
                            first = ((file.resolve(label["St"]) as? PdfNumber)?.int ?: 1).coerceAtLeast(1),
                        ),
                    )
                }
                (file.resolve(dict["Kids"]) as? PdfArray)?.items?.forEach { visit(it, depth + 1) }
            }
            visit(tree, 0)
            if (ranges.isEmpty()) return null
            // 같은 시작 쪽이 둘이면 뒤의 것(나무에서 나중에 나온 것)을 쓴다.
            return PdfPageLabels(ranges.associateBy { it.start }.values.sortedBy { it.start })
        }
    }
}
