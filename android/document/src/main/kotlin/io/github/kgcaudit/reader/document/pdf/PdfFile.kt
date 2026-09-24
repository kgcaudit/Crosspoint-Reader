package io.github.kgcaudit.reader.document.pdf

import io.github.kgcaudit.reader.document.SeekableSource
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * PDF 파일의 객체 찾기. 상호 참조표(xref)로 객체 번호 → 위치를 알고, 필요한 객체만 그 자리에서 읽는다.
 *
 * 받아들이는 모양:
 * - 옛 상호 참조**표**(`xref` … `trailer`)와, 덧붙여 고친 파일의 `/Prev` 사슬(새것이 이긴다).
 * - PDF 1.5 의 상호 참조 **스트림**과 객체 스트림(ObjStm). 요즘 PDF 대부분이 이렇게 나온다.
 * - 둘을 섞은 파일(`/XRefStm`).
 *
 * 상호 참조가 깨졌으면(위치가 어긋남, 표가 없음) 파일을 처음부터 훑어 `N G obj` 를 다시 찾는다.
 * 뷰어들이 다 그렇게 한다 — 목차 하나 읽자고 "이 파일은 깨졌습니다" 를 띄우지 않는다.
 */
internal class PdfFile private constructor(private val source: SeekableSource) {

    private sealed interface Entry
    private data class AtOffset(val offset: Long) : Entry
    private data class InStream(val stream: Int, val index: Int) : Entry

    private val entries = HashMap<Int, Entry>()
    private val cache = HashMap<Int, PdfObject>()
    private val objectStreams = HashMap<Int, ObjectStream?>()
    private var scanned: Map<Int, Entry>? = null

    /** 상호 참조를 끝까지 읽었고 뿌리도 거기서 찾았다. 그러면 표에 없는 번호는 정말 없는 객체다. */
    private var xrefComplete = false

    /** 파일 전체를 훑었는가. 시험이 "상호 참조로 읽었나, 훑어서 되살렸나" 를 가르는 데 쓴다. */
    internal val scannedWholeFile: Boolean get() = scanned != null

    /** 맨 마지막 판의 trailer(또는 상호 참조 스트림의 사전). /Root · /Encrypt 가 여기 있다. */
    var trailer: PdfDict = PdfDict(emptyMap())
        private set

    val root: PdfDict? get() = resolve(trailer["Root"]) as? PdfDict

    val isEncrypted: Boolean get() = trailer["Encrypt"] != null

    /** 값이 참조면 가리키는 객체를, 아니면 그대로. 없는 객체는 null(명세: null 객체와 같다). */
    fun resolve(value: PdfObject?): PdfObject? {
        var current = value
        // 참조가 참조를 가리키는 사슬. 순환이면 끊는다.
        repeat(8) {
            if (current !is PdfRef) return current.takeUnless { it is PdfNull }
            current = getObject(current.num)
        }
        return null
    }

    fun getObject(num: Int): PdfObject? {
        cache[num]?.let { return it }
        val entry = entries[num]
        // 멀쩡한 상호 참조에 없는 번호(가리키기만 하고 지운 객체)는 없는 것으로 본다. 그때마다 훑으면 끊긴
        // 참조 하나 때문에 큰 파일을 여는 데 몇 초가 걸린다. 훑기는 상호 참조가 **틀렸을** 때만.
        if (entry == null && xrefComplete) return null
        val found = load(num, entry) ?: load(num, scan()[num])
        if (found != null) cache[num] = found
        return found
    }

    /** 스트림의 내용을 풀어 준다. FlateDecode 만 푼다 — 목차에 쓰는 스트림(상호 참조·객체 스트림)은 모두 이것이다. */
    fun decode(stream: PdfStream): ByteArray? {
        val raw = rawStreamData(stream) ?: return null
        val filters = when (val f = resolve(stream.dict["Filter"])) {
            null -> emptyList()
            is PdfName -> listOf(f.name)
            is PdfArray -> f.items.mapNotNull { (resolve(it) as? PdfName)?.name }
            else -> return null
        }
        val params = when (val p = resolve(stream.dict["DecodeParms"])) {
            is PdfDict -> listOf(p)
            is PdfArray -> p.items.map { resolve(it) as? PdfDict }
            else -> emptyList()
        }
        var data = raw
        for ((i, filter) in filters.withIndex()) {
            data = when (filter) {
                "FlateDecode", "Fl" -> inflate(data) ?: return null
                else -> return null
            }
            params.getOrNull(i)?.let { data = unpredict(data, it) ?: return null }
        }
        return data
    }

    // ── 상호 참조 ──────────────────────────────────────────────────

    private fun loadXref() {
        val start = findStartXref()
        val seen = HashSet<Long>()
        var next: Long? = start
        var first = true
        // /Prev 를 따라 옛 판으로 내려간다. 같은 위치를 두 번 보면(순환) 멈춘다.
        while (next != null && next in 0 until source.size && seen.add(next) && seen.size <= MAX_XREF_SECTIONS) {
            val section = runCatching { readXrefSection(next) }.getOrNull() ?: break
            if (first) {
                trailer = section
                first = false
            }
            // 섞은 파일: 표의 trailer 가 가리키는 스트림에 표에 없는 객체(객체 스트림 속)가 있다.
            (resolveDirect(section["XRefStm"]) as? PdfNumber)?.let { stm ->
                if (seen.add(stm.long)) runCatching { readXrefSection(stm.long) }
            }
            next = (resolveDirect(section["Prev"]) as? PdfNumber)?.long
        }
    }

    /** 파일 끝의 `startxref` 뒤 숫자. 못 찾으면 null(→ 훑기). */
    private fun findStartXref(): Long? {
        val tail = minOf(source.size, TAIL_BYTES.toLong()).toInt()
        val bytes = read(source.size - tail, tail)
        val text = String(bytes, Charsets.ISO_8859_1)
        val at = text.lastIndexOf("startxref")
        if (at < 0) return null
        return Regex("""\s*(\d+)""").find(text, at + 9)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** 표 하나 또는 상호 참조 스트림 하나를 읽고 그 trailer 사전을 돌려준다. */
    private fun readXrefSection(offset: Long): PdfDict? {
        val head = String(read(offset, 4), Charsets.ISO_8859_1)
        return if (head == "xref") readXrefTable(offset) else readXrefStream(offset)
    }

    private fun readXrefTable(offset: Long): PdfDict? = withWindow(offset, 64 * 1024) { p ->
        p.pos = 4
        val found = ArrayList<Pair<Int, Entry>>()
        while (true) {
            p.skipSpace()
            val startWord = p.readWord()
            if (startWord == "trailer" || startWord.isEmpty()) break
            val first = startWord.toIntOrNull() ?: break
            p.skipSpace()
            val count = p.readWord().toIntOrNull() ?: break
            for (k in 0 until count) {
                p.skipSpace()
                val off = p.readWord().toLongOrNull() ?: break
                p.skipSpace()
                p.readWord() // 세대 번호. 같은 번호의 새것은 위치로 이미 갈린다.
                p.skipSpace()
                val type = p.readWord()
                if (type == "n" && off > 0) found.add(first + k to AtOffset(off))
            }
        }
        val trailer = p.readObject() as? PdfDict
        // 창을 다시 읽을 수 있으므로(NeedMoreBytes) 다 읽은 뒤에 넣는다.
        for ((num, entry) in found) entries.putIfAbsent(num, entry)
        trailer
    }

    private fun readXrefStream(offset: Long): PdfDict? {
        val stream = readObjectAt(offset, expected = null) as? PdfStream ?: return null
        val dict = stream.dict
        if ((dict["Type"] as? PdfName)?.name != "XRef") return null
        val data = decode(stream) ?: return dict
        val widths = (dict["W"] as? PdfArray)?.items?.map { (it as? PdfNumber)?.int ?: 0 } ?: return dict
        if (widths.size < 3 || widths.any { it !in 0..8 }) return dict
        val rowSize = widths.sum()
        if (rowSize == 0) return dict
        val size = (dict["Size"] as? PdfNumber)?.int ?: 0
        val index = (dict["Index"] as? PdfArray)?.items?.map { (it as? PdfNumber)?.int ?: 0 } ?: listOf(0, size)
        var row = 0
        for (pair in index.chunked(2)) {
            if (pair.size < 2) break
            for (k in 0 until pair[1]) {
                val at = row * rowSize
                if (at + rowSize > data.size) return dict
                row++
                // 첫 칸 폭이 0 이면 종류는 1(명세 기본값).
                val type = if (widths[0] == 0) 1L else field(data, at, widths[0])
                val second = field(data, at + widths[0], widths[1])
                val third = field(data, at + widths[0] + widths[1], widths[2])
                val num = pair[0] + k
                when (type) {
                    1L -> if (second > 0) entries.putIfAbsent(num, AtOffset(second))
                    2L -> entries.putIfAbsent(num, InStream(second.toInt(), third.toInt()))
                }
            }
        }
        return dict
    }

    private fun field(data: ByteArray, at: Int, width: Int): Long {
        var value = 0L
        for (i in 0 until width) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    // ── 객체 ───────────────────────────────────────────────────────

    private fun load(num: Int, entry: Entry?): PdfObject? = when (entry) {
        null -> null
        is AtOffset -> runCatching { readObjectAt(entry.offset, expected = num) }.getOrNull()
        is InStream -> runCatching { objectStream(entry.stream)?.get(entry.index, num) }.getOrNull()
    }

    /**
     * [offset] 의 `N G obj … endobj`. [expected] 번호가 아니면 null — 위치가 어긋난 상호 참조를 믿고
     * 엉뚱한 객체를 쓰면 목차가 다른 쪽을 가리킨다.
     */
    private fun readObjectAt(offset: Long, expected: Int?): PdfObject? = withWindow(offset, 4096) { p ->
        val header = p.readObjectHeader() ?: return@withWindow null
        if (expected != null && header.first != expected) return@withWindow null
        p.readObject()
    }

    /** 객체 스트림: 머리에 (번호, 위치) 쌍이 N 개, /First 부터 객체들. */
    private class ObjectStream(val data: ByteArray, val numbers: IntArray, val offsets: IntArray, val first: Int) {
        fun get(index: Int, expected: Int): PdfObject? {
            val i = if (index in numbers.indices && numbers[index] == expected) index else numbers.indexOf(expected)
            if (i < 0) return null
            val p = PdfParser(data, base = 0)
            p.pos = first + offsets[i]
            if (p.pos !in data.indices) return null
            return p.readObject()
        }
    }

    private fun objectStream(num: Int): ObjectStream? = objectStreams.getOrPut(num) {
        runCatching {
            val stream = resolve(PdfRef(num, 0)) as? PdfStream ?: return@runCatching null
            val data = decode(stream) ?: return@runCatching null
            val n = (resolve(stream.dict["N"]) as? PdfNumber)?.int ?: return@runCatching null
            val first = (resolve(stream.dict["First"]) as? PdfNumber)?.int ?: return@runCatching null
            if (n !in 0..MAX_OBJECTS || first !in 0..data.size) return@runCatching null
            val p = PdfParser(data, length = first)
            val numbers = IntArray(n)
            val offsets = IntArray(n)
            for (i in 0 until n) {
                numbers[i] = (p.readObject() as? PdfNumber)?.int ?: break
                offsets[i] = (p.readObject() as? PdfNumber)?.int ?: break
            }
            ObjectStream(data, numbers, offsets, first)
        }.getOrNull()
    }

    private fun rawStreamData(stream: PdfStream): ByteArray? {
        val start = stream.dataOffset
        if (start !in 0 until source.size) return null
        val declared = (resolve(stream.dict["Length"]) as? PdfNumber)?.long
        // /Length 가 맞는지 뒤의 `endstream` 으로 확인한다. 틀린 길이를 적는 프로그램이 흔하다.
        if (declared != null && declared in 0..MAX_STREAM && start + declared <= source.size) {
            val after = String(read(start + declared, 32), Charsets.ISO_8859_1).trimStart()
            if (after.startsWith("endstream")) return read(start, declared.toInt())
        }
        val end = findForward(start, "endstream".toByteArray(), MAX_STREAM) ?: return null
        var length = (end - start).toInt()
        // endstream 앞의 줄바꿈은 데이터가 아니다.
        val tail = read(start + maxOf(0, length - 2), minOf(2, length))
        if (tail.isNotEmpty() && tail.last().toInt() == '\n'.code) length--
        if (tail.size == 2 && tail[0].toInt() == '\r'.code) length--
        return read(start, maxOf(0, length))
    }

    // ── 훑기(상호 참조가 깨진 파일) ─────────────────────────────────

    /**
     * 파일 전체에서 `N G obj` 를 찾는다. 뒤에 나온 것이 이긴다(덧붙인 판이 뒤에 온다). 한 번만 한다.
     * 객체 스트림 안의 객체도 등록한다 — 요즘 파일은 목차 항목이 거의 다 그 안에 있다.
     */
    private fun scan(): Map<Int, Entry> {
        scanned?.let { return it }
        val found = HashMap<Int, Entry>()
        scanned = found
        if (source.size > MAX_SCAN_BYTES) return found
        val streams = ArrayList<Int>()
        var chunkStart = 0L
        val overlap = 64
        while (chunkStart < source.size) {
            val len = minOf(SCAN_CHUNK.toLong(), source.size - chunkStart).toInt()
            val chunk = read(chunkStart, len)
            var i = chunk.indexOf("obj", 0)
            while (i >= 0) {
                headerStart(chunk, i)?.let { (at, num) ->
                    found[num] = AtOffset(chunkStart + at)
                }
                i = chunk.indexOf("obj", i + 3)
            }
            if (chunkStart + len >= source.size) break
            chunkStart += len - overlap
        }
        for ((num, entry) in found.toList()) {
            val obj = load(num, entry) as? PdfStream ?: continue
            if ((obj.dict["Type"] as? PdfName)?.name == "ObjStm") streams.add(num)
        }
        for (stm in streams) {
            val os = objectStream(stm) ?: continue
            os.numbers.forEachIndexed { index, num -> found.putIfAbsent(num, InStream(stm, index)) }
        }
        // 상호 참조와 함께 trailer 도 잃었으면 /Type /Catalog 객체를 뿌리로 삼는다. 객체 스트림 속에
        // 있을 수 있어 그것들을 등록한 **뒤에** 찾는다.
        if (root == null) {
            val catalog = found.keys.sorted().firstOrNull { num ->
                val obj = load(num, found[num]) as? PdfDict
                (obj?.get("Type") as? PdfName)?.name == "Catalog"
            }
            if (catalog != null) trailer = PdfDict(trailer.entries + ("Root" to PdfRef(catalog, 0)))
        }
        return found
    }

    /** chunk[objAt] 의 "obj" 앞이 `N G` 이면 (N 이 시작하는 곳, N). */
    private fun headerStart(chunk: ByteArray, objAt: Int): Pair<Int, Int>? {
        val after = objAt + 3
        if (after < chunk.size && !PdfParser.isDelimiterOrSpace(chunk[after].toInt() and 0xFF)) return null
        var i = objAt - 1
        fun skipSpaces() { while (i >= 0 && chunk[i].toInt().let { it == 32 || it == 10 || it == 13 || it == 9 || it == 0 || it == 12 }) i-- }
        fun digitsBack(): Int? {
            val end = i
            while (i >= 0 && chunk[i] in '0'.code.toByte()..'9'.code.toByte()) i--
            if (i == end) return null
            return String(chunk, i + 1, end - i, Charsets.ISO_8859_1).toIntOrNull()
        }
        skipSpaces()
        digitsBack() ?: return null
        skipSpaces()
        val num = digitsBack() ?: return null
        // 번호 앞은 줄 시작이거나 구분자여야 한다("12 0 obj" 가 "112 0 obj" 의 일부가 아니게).
        if (i >= 0 && !PdfParser.isDelimiterOrSpace(chunk[i].toInt() and 0xFF)) return null
        return i + 1 to num
    }

    private fun ByteArray.indexOf(word: String, from: Int): Int {
        val w = word.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in from..size - w.size) {
            for (j in w.indices) if (this[i + j] != w[j]) continue@outer
            return i
        }
        return -1
    }

    // ── 바이트 ─────────────────────────────────────────────────────

    /**
     * [offset] 에서 창을 열어 [block] 을 부른다. 값이 창 끝에서 잘리면 창을 두 배로 늘려 다시 부른다.
     * 파일 끝까지 늘렸으면 파일 끝으로 알려 준다(닫히지 않은 사전은 있는 만큼).
     */
    private fun <T> withWindow(offset: Long, initial: Int, block: (PdfParser) -> T): T {
        var size = initial.toLong()
        while (true) {
            val len = minOf(size, source.size - offset, MAX_WINDOW.toLong()).toInt()
            val atEof = offset + len >= source.size || len >= MAX_WINDOW
            val bytes = read(offset, len)
            try {
                return block(PdfParser(bytes, length = bytes.size, base = offset, atEof = atEof))
            } catch (e: NeedMoreBytes) {
                if (atEof) throw PdfSyntaxException("object does not end")
                size *= 4
            }
        }
    }

    private fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset < 0 || offset >= source.size) return ByteArray(0)
        val len = minOf(length.toLong(), source.size - offset).toInt()
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = source.readAt(offset + done, out, done, len - done)
            if (n <= 0) break
            done += n
        }
        return if (done == len) out else out.copyOf(done)
    }

    private fun findForward(from: Long, word: ByteArray, limit: Long): Long? {
        var at = from
        val end = minOf(source.size, from + limit)
        while (at < end) {
            val len = minOf(SCAN_CHUNK.toLong(), end - at).toInt()
            val chunk = read(at, len)
            val i = chunk.indexOf(String(word, Charsets.ISO_8859_1), 0)
            if (i >= 0) return at + i
            if (at + len >= end) break
            at += len - word.size
        }
        return null
    }

    /** 상호 참조를 읽는 중에는 객체를 풀 수 없다(아직 표가 없다). 직접 적힌 값만 본다. */
    private fun resolveDirect(value: PdfObject?): PdfObject? = value.takeUnless { it is PdfRef || it is PdfNull }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(maxOf(64, data.size * 3))
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    // 끝이 잘린 스트림: 풀린 데까지 쓴다(뷰어들과 같다).
                    break
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_STREAM) return null
            }
            out.toByteArray().takeIf { it.isNotEmpty() || data.isEmpty() }
        } finally {
            inflater.end()
        }
    }

    /** PNG 예측자(상호 참조 스트림은 거의 다 /Predictor 12). 다른 예측자는 모른다 → null. */
    private fun unpredict(data: ByteArray, params: PdfDict): ByteArray? {
        val predictor = (resolve(params["Predictor"]) as? PdfNumber)?.int ?: 1
        if (predictor == 1) return data
        if (predictor < 10) return null
        val colors = (resolve(params["Colors"]) as? PdfNumber)?.int ?: 1
        val bits = (resolve(params["BitsPerComponent"]) as? PdfNumber)?.int ?: 8
        val columns = (resolve(params["Columns"]) as? PdfNumber)?.int ?: 1
        val bpp = maxOf(1, (colors * bits + 7) / 8)
        val rowLen = (colors * bits * columns + 7) / 8
        if (rowLen <= 0) return null
        val out = ByteArrayOutputStream(data.size)
        var prev = ByteArray(rowLen)
        var at = 0
        while (at + 1 + rowLen <= data.size) {
            val type = data[at].toInt() and 0xFF
            val row = data.copyOfRange(at + 1, at + 1 + rowLen)
            for (i in 0 until rowLen) {
                val left = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val add = when (type) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upLeft)
                    else -> return null
                }
                row[i] = ((row[i].toInt() and 0xFF) + add).toByte()
            }
            out.write(row)
            prev = row
            at += 1 + rowLen
        }
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    companion object {
        private const val TAIL_BYTES = 4096
        private const val MAX_XREF_SECTIONS = 64
        private const val MAX_OBJECTS = 10_000_000
        private const val MAX_STREAM = 64L * 1024 * 1024
        private const val MAX_WINDOW = 64 * 1024 * 1024
        private const val SCAN_CHUNK = 1024 * 1024
        private const val MAX_SCAN_BYTES = 512L * 1024 * 1024

        /** 상호 참조를 읽는다. 뿌리(/Root)를 못 찾으면 파일을 훑어 되살린다. 그래도 없으면 null. */
        fun open(source: SeekableSource): PdfFile? {
            val file = PdfFile(source)
            runCatching { file.loadXref() }
            file.xrefComplete = file.entries.isNotEmpty()
            if (file.root == null) {
                file.xrefComplete = false
                file.scan()
            }
            return file.takeIf { it.root != null }
        }
    }
}
