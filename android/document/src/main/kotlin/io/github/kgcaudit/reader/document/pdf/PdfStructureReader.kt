package io.github.kgcaudit.reader.document.pdf

import io.github.kgcaudit.reader.document.SeekableSource
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** PDF 목차 항목. [pageIndex] 는 0부터 센 쪽. */
data class PdfOutlineItem(val label: String, val pageIndex: Int, val depth: Int)

/**
 * 파일에 적힌 제목·저자(문서 정보 `/Info`), 목차, 쪽 이름표. 없는 것은 null · 빈 목록.
 *
 * [pageLabels] 는 인쇄된 쪽 번호가 파일 순서와 **다를 때만** 있다(같으면 null — 보일 까닭이 없다).
 */
data class PdfStructure(
    val title: String?,
    val author: String?,
    val outline: List<PdfOutlineItem>,
    val pageLabels: PdfPageLabels? = null,
) {
    companion object {
        val EMPTY = PdfStructure(null, null, emptyList())
    }
}

/**
 * PDF 의 목차(Outlines, "책갈피" 패널)와 제목·저자를 읽는다.
 *
 * 플랫폼 PdfRenderer 는 쪽을 그릴 줄만 알고 목차를 주지 않는다. 목차는 파일 구조에 적힌 **나무**라
 * 쪽을 그리는 엔진 없이 읽을 수 있다 — 그래서 여기(:document, 순수 Kotlin)에 두고, 기기 없이 시험한다.
 *
 * 목적지(어느 쪽으로 가나)는 네 모양을 다 받는다: 쪽을 직접 가리키는 배열, `/Dests` 사전의 이름,
 * `/Names` 이름 나무의 문자열, `/A` GoTo 동작. 쪽을 알 수 없는 항목은 **첫 자식의 쪽**을 쓴다
 * (자식만 목적지가 있는 "제1부" 같은 머리). 자식도 없으면 버린다.
 *
 * 무엇이 깨졌든 예외를 던지지 않고 읽은 데까지 돌려준다. 목차가 없어도 책은 열려야 한다.
 */
object PdfStructureReader {

    fun read(source: SeekableSource): PdfStructure = runCatching { readOrThrow(source) }.getOrDefault(PdfStructure.EMPTY)

    private fun readOrThrow(source: SeekableSource): PdfStructure {
        val file = PdfFile.open(source) ?: return PdfStructure.EMPTY
        // 암호화된 파일은 문자열도 암호화돼 있다. 빈 사용자 암호로 풀리면(소유자 암호만 걸린 잡지·전자책)
        // 풀어서 읽고, 진짜 암호가 걸렸으면 깨진 글자 대신 아무것도 주지 않는다.
        if (!file.isReadable) return PdfStructure.EMPTY
        val info = file.resolve(file.trailer["Info"]) as? PdfDict
        fun text(key: String) = (file.resolve(info?.get(key)) as? PdfString)?.let(::decodeTextString)?.let(::clean)
        val outline = runCatching { outline(file) }.getOrDefault(emptyList())
        val labels = runCatching { PdfPageLabels.read(file, file.root?.get("PageLabels"), ::decodeTextString) }.getOrNull()
        return PdfStructure(
            title = text("Title")?.let(::cleanTitle),
            author = text("Author")?.takeUnless { it.lowercase() in MEANINGLESS_AUTHORS },
            outline = outline,
            pageLabels = labels?.takeUnless { it.isPlainNumbering },
        )
    }

    private fun outline(file: PdfFile): List<PdfOutlineItem> {
        val root = file.root ?: return emptyList()
        val outlines = file.resolve(root["Outlines"]) as? PdfDict ?: return emptyList()
        val context = Context(file, root)
        val raw = ArrayList<Raw>()
        context.walk(file.resolve(outlines["First"]), outlines["First"] as? PdfRef, depth = 0, out = raw)
        return liftSoleWrapper(fillMissingPages(raw))
    }

    /**
     * 맨 위 항목이 **하나뿐이고** 나머지가 모두 그 아래에 있으면(잡지의 "목차", 책 제목 하나 아래 모든 장)
     * 아래 항목들을 한 단계 올린다. 그대로 두면 목록 전체가 한 칸씩 들여 써져 머리 하나만 튀어나온다.
     * 감싸던 항목은 그대로 첫 줄에 둔다 — 대개 그 자체도 갈 곳(목차 쪽, 표지)이다.
     */
    private fun liftSoleWrapper(items: List<PdfOutlineItem>): List<PdfOutlineItem> {
        if (items.size < 2 || items.count { it.depth == 0 } != 1 || items[0].depth != 0) return items
        return listOf(items[0]) + items.drop(1).map { it.copy(depth = it.depth - 1) }
    }

    /**
     * 문서 정보의 제목은 만든 프로그램이 채우기도 한다("Microsoft Word - 원고.docx", "제목 없음"). 그런 것은
     * 파일 이름보다 못하므로 앞머리와 확장자를 떼고, 남는 게 없거나 뜻 없는 말이면 버린다(→ 파일 이름).
     */
    private fun cleanTitle(title: String): String? {
        val stripped = title
            .removePrefix("Microsoft Word - ").removePrefix("Microsoft PowerPoint - ")
            .replace(Regex("""\.(docx?|hwpx?|pptx?|xlsx?|pdf|txt|indd)$""", RegexOption.IGNORE_CASE), "")
            .trim()
        return stripped.takeUnless { it.isEmpty() || it.lowercase() in MEANINGLESS_TITLES }
    }

    private val MEANINGLESS_TITLES = setOf("untitled", "제목 없음", "제목없음", "무제", "unknown", "document")

    /** 만든 컴퓨터의 계정 이름이 저자로 들어간 것들. 저자 자리에 "USER" 가 보이면 없느니만 못하다. */
    private val MEANINGLESS_AUTHORS = setOf(
        "user", "admin", "administrator", "owner", "unknown", "사용자", "windows 사용자", "관리자", "pc", "home",
    )

    private class Raw(val label: String, val page: Int?, val depth: Int)

    /** 쪽 없는 머리는 바로 뒤의 더 깊은 항목(첫 자식)의 쪽을 쓴다. */
    private fun fillMissingPages(raw: List<Raw>): List<PdfOutlineItem> {
        val out = ArrayList<PdfOutlineItem>(raw.size)
        for ((i, item) in raw.withIndex()) {
            val page = item.page
                ?: raw.subList(i + 1, raw.size).asSequence().takeWhile { it.depth > item.depth }.firstNotNullOfOrNull { it.page }
            if (page != null) out.add(PdfOutlineItem(item.label, page, item.depth))
        }
        return out
    }

    private class Context(private val file: PdfFile, private val root: PdfDict) {
        private val visited = HashSet<Int>()
        private var count = 0

        /** 쪽 객체 번호 → 쪽 순서. 쪽 나무를 한 번 훑어 만든다. */
        private val pageIndex: Map<Int, Int> by lazy {
            val map = HashMap<Int, Int>()
            val seen = HashSet<Int>()
            fun visit(ref: PdfObject?, depth: Int) {
                if (ref !is PdfRef || depth > MAX_TREE_DEPTH || !seen.add(ref.num)) return
                val node = file.resolve(ref) as? PdfDict ?: return
                val kids = file.resolve(node["Kids"]) as? PdfArray
                // /Type 을 빼먹은 파일이 있어 /Kids 로도 가른다.
                if (kids != null && (node["Type"] as? PdfName)?.name != "Page") {
                    kids.items.forEach { visit(it, depth + 1) }
                } else {
                    map[ref.num] = map.size
                }
            }
            visit(root["Pages"], 0)
            map
        }

        /** `/Dests` 사전(PDF 1.1). */
        private val namedDests: PdfDict? by lazy { file.resolve(root["Dests"]) as? PdfDict }

        /** `/Names` → `/Dests` 이름 나무(PDF 1.2). 필요할 때 한 번만 펼친다. */
        private val nameTree: Map<PdfString, PdfObject> by lazy {
            val map = HashMap<PdfString, PdfObject>()
            val seen = HashSet<Int>()
            fun visit(node: PdfObject?, depth: Int) {
                if (depth > MAX_TREE_DEPTH) return
                if (node is PdfRef && !seen.add(node.num)) return
                val dict = file.resolve(node) as? PdfDict ?: return
                (file.resolve(dict["Names"]) as? PdfArray)?.items?.chunked(2)?.forEach { pair ->
                    val key = file.resolve(pair.getOrNull(0)) as? PdfString ?: return@forEach
                    pair.getOrNull(1)?.let { map.putIfAbsent(key, it) }
                }
                (file.resolve(dict["Kids"]) as? PdfArray)?.items?.forEach { visit(it, depth + 1) }
            }
            val names = file.resolve(root["Names"]) as? PdfDict
            visit(names?.get("Dests"), 0)
            map
        }

        /**
         * 형제 사슬(/Next)을 따라가며 자식(/First)으로 내려간다. 되풀이가 아니라 사슬로 도는 이유:
         * 형제는 수백 개일 수 있어 되부르면 스택이 깊어진다. 이미 본 항목이면 멈춘다(순환 목차).
         */
        fun walk(first: PdfObject?, firstRef: PdfRef?, depth: Int, out: MutableList<Raw>) {
            var node = first as? PdfDict
            var ref = firstRef
            while (node != null && count < MAX_ITEMS && depth <= MAX_TREE_DEPTH) {
                if (ref != null && !visited.add(ref.num)) return
                count++
                val label = (file.resolve(node["Title"]) as? PdfString)?.let(::decodeTextString)?.let(::clean)
                // 제목 없는 항목은 보일 수 없지만 자식은 살린다(한 칸 얕게 — 빈 머리 아래에 들여 쓰지 않게).
                if (label != null) out.add(Raw(label, pageOf(node), depth))
                walk(file.resolve(node["First"]), node["First"] as? PdfRef, if (label != null) depth + 1 else depth, out)
                val nextRef = node["Next"]
                ref = nextRef as? PdfRef
                node = file.resolve(nextRef) as? PdfDict
            }
        }

        private fun pageOf(item: PdfDict): Int? {
            item["Dest"]?.let { return destinationPage(it, 0) }
            val action = file.resolve(item["A"]) as? PdfDict ?: return null
            // GoTo 만 이 문서 안이다. GoToR(다른 파일) · URI 는 갈 쪽이 없다.
            if ((action["S"] as? PdfName)?.name != "GoTo") return null
            return destinationPage(action["D"], 0)
        }

        private fun destinationPage(dest: PdfObject?, hops: Int): Int? {
            if (hops > 4) return null
            return when (val d = file.resolve(dest)) {
                is PdfArray -> when (val target = d.items.firstOrNull()) {
                    is PdfRef -> pageIndex[target.num]
                    // 쪽 번호를 숫자로 적은 파일(원래 다른 파일로 갈 때의 모양)도 받아 준다.
                    is PdfNumber -> target.int.takeIf { it >= 0 }
                    else -> null
                }
                is PdfName -> destinationPage(namedDests?.get(d.name), hops + 1)
                is PdfString -> destinationPage(nameTree[d] ?: namedDests?.get(String(d.bytes, Charsets.ISO_8859_1)), hops + 1)
                // 이름이 가리키는 값이 `<< /D [...] >>` 사전인 경우.
                is PdfDict -> destinationPage(d["D"], hops + 1)
                else -> null
            }
        }
    }

    /**
     * PDF 텍스트 문자열 → 글자. BOM 이 FE FF 면 UTF-16BE(대부분의 한글 제목), EF BB BF 면 UTF-8(PDF 2.0),
     * 아니면 PDFDocEncoding.
     *
     * BOM 없이 UTF-8 이나 CP949 바이트를 그대로 넣는 프로그램이 있다(옛 한글 워드프로세서 변환기). 명세대로
     * PDFDocEncoding 으로 읽으면 "ì„œì " 같은 글자가 나온다. 바이트가 UTF-8 로 온전하면 UTF-8, 한글이 되는
     * CP949 면 CP949 로 읽는다 — 진짜 PDFDocEncoding 제목(라틴 악센트)이 그 둘로 온전히 풀리는 일은 드물다.
     */
    internal fun decodeTextString(value: PdfString): String {
        val b = value.bytes
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        // 드물게 리틀 엔디언으로 적은 파일도 있다.
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.any { it < 0 }) {
            strict(b, Charsets.UTF_8)?.let { return it }
            strict(b, CP949)?.takeIf { text -> text.any { it in '가'..'힣' } }?.let { return it }
        }
        return buildString(b.size) { for (x in b) append(PDF_DOC[x.toInt() and 0xFF]) }
    }

    private fun strict(bytes: ByteArray, charset: Charset?): String? {
        if (charset == null) return null
        return runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    /** 제어 문자(줄바꿈 포함)는 빈칸 하나로, 앞뒤 빈칸은 뗀다. 빈 제목은 버린다(누를 수 없는 빈 줄). */
    private fun clean(text: String): String? =
        decodeCodePointTags(text).replace(Regex("[\\u0000-\\u001F\\u007F\\s]+"), " ").trim().takeIf { it.isNotEmpty() }

    /**
     * `<C88B><C740>` 처럼 글자를 유니코드 번호로 적은 조각을 글자로("좋은"). 한글 자판이 없는 조판 프로그램이
     * 문서 정보에 이렇게 넣는다(실제 잡지에서 봄: "2608 <C88B><C740><C0DD><AC01>" = "2608 좋은생각").
     *
     * 한글 · 한자 · 가나 범위일 때만 바꾼다. 아무 번호나 바꾸면 제목에 정말로 적힌 `<ABCD>` 가 엉뚱한 기호가 된다.
     */
    private fun decodeCodePointTags(text: String): String {
        if ('<' !in text) return text
        return CODE_POINT_TAG.replace(text) { m ->
            val c = m.groupValues[1].toInt(16)
            val cjk = c in 0xAC00..0xD7A3 || c in 0x1100..0x11FF || c in 0x3130..0x318F ||
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x3040..0x30FF
            if (cjk) c.toChar().toString() else m.value
        }
    }

    private val CODE_POINT_TAG = Regex("<([0-9A-Fa-f]{4})>")

    private val CP949: Charset? = runCatching { Charset.forName("x-windows-949") }.getOrNull()
        ?: runCatching { Charset.forName("MS949") }.getOrNull()

    /** PDFDocEncoding. 0x00–0x7F · 0xA1–0xFF 는 Latin-1 과 같고, 나머지 몇 자리가 다르다. */
    private val PDF_DOC: CharArray = CharArray(256) { it.toChar() }.also { t ->
        "˘ˇˆ˙˝˛˚˜".forEachIndexed { i, c -> t[0x18 + i] = c }
        (
            "•†‡…—–ƒ⁄‹›−‰„“”‘" +
                "’‚™ﬁﬂŁŒŠŸŽıłœšž�"
            ).forEachIndexed { i, c -> t[0x80 + i] = c }
        t[0xA0] = '€'
        t[0xAD] = '�'
    }

    private const val MAX_ITEMS = 10_000
    private const val MAX_TREE_DEPTH = 64
}
