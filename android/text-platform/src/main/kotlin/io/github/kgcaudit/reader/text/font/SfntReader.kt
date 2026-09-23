package io.github.kgcaudit.reader.text.font

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * 폰트 파일 한 벌(TTC 면 여러 벌) 안의 한 서체. 사용자가 넣은 폰트를 목록에 올리고
 * 보통·굵게를 짝지을 때 쓴다.
 *
 * @property family 짝짓기와 설정 키에 쓰는 가족 이름. 영어 이름이 있으면 영어 — 한국어
 *   이름만 쓰면 같은 가족의 파일 중 하나에만 한국어 이름이 있을 때 둘로 갈라진다.
 * @property label 사람에게 보이는 이름. 한국어 이름이 있으면 한국어.
 */
data class FontFace(
    val index: Int,
    val family: String,
    val label: String,
    val weight: Int,
    val italic: Boolean,
    val hasHangul: Boolean,
    /** 이름표에 한국어 이름이 있는가. TTC 에서 한국어 가족만 고르는 데 쓴다. */
    val hasKoreanName: Boolean,
)

/** 폰트로 읽을 수 없는 파일. [reason] 으로 사용자에게 할 말을 고른다. */
class FontFormatException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        /** 폰트가 아니다(그림, 문서, 압축 파일…). */
        NotAFont,

        /** 웹 폰트(WOFF/WOFF2). 안드로이드가 직접 읽지 못한다. */
        Woff,

        /** 폰트 머리는 맞는데 안이 깨졌다(덜 받은 파일 등). */
        Broken,
    }
}

/**
 * TrueType·OpenType·TTC 의 머리만 읽는 순수 Kotlin 판독기.
 *
 * 안드로이드의 `Typeface` 는 이름·굵기를 알려 주지 않고, `Paint.hasGlyph` 는 **대체 글꼴까지**
 * 뒤져서 한글이 없는 영문 폰트에도 "있다" 고 답한다. 그래서 name · OS/2 · head · cmap 표를
 * 직접 읽는다. 필요한 몇 KB 만 읽으므로 20MB 폰트도 순식간이다.
 *
 * 깨진 입력에 관대해야 하는 곳이 아니라 **거절해야** 하는 곳이다 — 여기를 통과한 파일만
 * 조판에 쓰인다. 모든 읽기는 범위를 확인하고, 벗어나면 [FontFormatException] 이다.
 */
object SfntReader {

    fun read(file: File): List<FontFace> = RandomAccessFile(file, "r").use { raf ->
        read(object : Source {
            override val size = raf.length()
            override fun read(offset: Long, length: Int): ByteArray {
                check(offset, length, size)
                raf.seek(offset)
                return ByteArray(length).also { raf.readFully(it) }
            }
        })
    }

    fun read(bytes: ByteArray): List<FontFace> = read(object : Source {
        override val size = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            check(offset, length, size)
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    })

    private interface Source {
        val size: Long
        fun read(offset: Long, length: Int): ByteArray
    }

    private fun check(offset: Long, length: Int, size: Long) {
        if (offset < 0 || length < 0 || offset + length > size) {
            throw FontFormatException(FontFormatException.Reason.Broken, "read $offset+$length past end $size")
        }
    }

    private fun read(source: Source): List<FontFace> {
        if (source.size < 12) throw FontFormatException(FontFormatException.Reason.NotAFont, "too small")
        val head = Buf(source.read(0, 12))
        return when (val tag = head.u32(0)) {
            TTCF -> {
                val count = head.u32(8)
                if (count <= 0 || count > 256) throw FontFormatException(FontFormatException.Reason.Broken, "ttc count $count")
                val offsets = Buf(source.read(12, (count * 4).toInt()))
                (0 until count.toInt()).map { i -> face(source, offsets.u32(i * 4), i) }
            }
            WOFF, WOFF2 -> throw FontFormatException(FontFormatException.Reason.Woff, "web font")
            TRUETYPE, OTTO, TRUE -> listOf(face(source, 0, 0))
            else -> throw FontFormatException(FontFormatException.Reason.NotAFont, "tag %08x".format(tag))
        }
    }

    private fun face(source: Source, at: Long, index: Int): FontFace {
        val header = Buf(source.read(at, 12))
        val tag = header.u32(0)
        if (tag != TRUETYPE && tag != OTTO && tag != TRUE) {
            throw FontFormatException(FontFormatException.Reason.Broken, "face $index tag %08x".format(tag))
        }
        val numTables = header.u16(4)
        val records = Buf(source.read(at + 12, numTables * 16))
        val tables = HashMap<Long, Pair<Long, Int>>()
        for (i in 0 until numTables) {
            val length = records.u32(i * 16 + 12)
            if (length > Int.MAX_VALUE) throw FontFormatException(FontFormatException.Reason.Broken, "table too large")
            val offset = records.u32(i * 16 + 8)
            // 덜 받은 파일은 머리가 멀쩡하고 뒤쪽 표(대개 글리프)가 잘려 있다. 읽지 않는 표라도
            // 파일 끝을 넘으면 거절한다 — 받아 두면 그 글자들만 빈 칸으로 그려진다.
            if (offset + length > source.size) {
                throw FontFormatException(FontFormatException.Reason.Broken, "table ends at ${offset + length} > ${source.size}")
            }
            tables[records.u32(i * 16)] = offset to length.toInt()
        }
        fun table(tag: Long): Buf? = tables[tag]?.let { (offset, length) -> Buf(source.read(offset, length)) }

        // cmap·name 이 없는 폰트는 글자를 그릴 수도, 이름을 댈 수도 없다.
        val names = Names(table(NAME) ?: throw FontFormatException(FontFormatException.Reason.Broken, "no name table"))
        val cmap = tables[CMAP] ?: throw FontFormatException(FontFormatException.Reason.Broken, "no cmap table")
        val os2 = table(OS2)
        val headTable = table(HEAD)

        val macStyle = headTable?.takeIf { it.size >= 46 }?.u16(44) ?: 0
        var weight = os2?.takeIf { it.size >= 6 }?.u16(4) ?: if (macStyle and 1 != 0) 700 else 400
        // 옛 폰트 중에는 굵기를 1~9 로 적은 것이 있다(FontLab 의 옛 버릇).
        if (weight in 1..9) weight *= 100
        if (weight !in 1..1000) weight = 400
        val italic = (os2?.takeIf { it.size >= 64 }?.u16(62)?.and(1) ?: 0) != 0 || macStyle and 2 != 0

        val family = names.best(16, preferKorean = false) ?: names.best(1, preferKorean = false)
            ?: throw FontFormatException(FontFormatException.Reason.Broken, "no family name")
        val label = names.best(16, preferKorean = true) ?: names.best(1, preferKorean = true) ?: family

        return FontFace(
            index = index,
            family = family,
            label = label,
            weight = weight,
            italic = italic,
            hasHangul = Cmap(source, cmap.first, cmap.second).coversAll(HANGUL_PROBE),
            hasKoreanName = names.hasKorean,
        )
    }

    /** name 표. nameID 16(타이포그래피 가족)이 있으면 1 보다 낫다 — 굵기별로 가족이 갈라지지 않는다. */
    private class Names(private val t: Buf) {
        private class Record(val platform: Int, val encoding: Int, val language: Int, val id: Int, val value: String)

        private val records: List<Record> = run {
            val count = t.u16(2)
            val strings = t.u16(4)
            (0 until count).mapNotNull { i ->
                val r = 6 + i * 12
                val platform = t.u16(r)
                val encoding = t.u16(r + 2)
                val language = t.u16(r + 4)
                val id = t.u16(r + 6)
                val length = t.u16(r + 8)
                val offset = strings + t.u16(r + 10)
                if (offset + length > t.size) return@mapNotNull null
                val raw = t.bytes(offset, length)
                val text = decode(platform, encoding, raw)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Record(platform, encoding, language, id, text)
            }
        }

        /** 가족 이름이 한국어로도 있는가. 저작권·제작자 칸만 한국어인 영문 폰트가 흔하다(Pretendard 도). */
        val hasKorean: Boolean get() = records.any { it.isKorean() && (it.id == 1 || it.id == 16) }

        private fun Record.isKorean() = (platform == 3 && language == 0x0412) || (platform == 1 && language == 23)

        fun best(id: Int, preferKorean: Boolean): String? {
            val candidates = records.filter { it.id == id }
            fun rank(r: Record): Int = when {
                preferKorean && r.isKorean() -> 0
                r.platform == 3 && r.language == 0x0409 -> 1
                r.platform == 1 && r.language == 0 -> 2
                r.platform == 3 || r.platform == 0 -> 3
                else -> 4
            }
            return candidates.minByOrNull(::rank)?.value
        }

        private fun decode(platform: Int, encoding: Int, raw: ByteArray): String? = when (platform) {
            0, 3 -> String(raw, Charsets.UTF_16BE)
            1 -> when (encoding) {
                0 -> String(raw, Charsets.ISO_8859_1)
                3 -> runCatching { String(raw, Charset.forName("EUC-KR")) }.getOrNull()
                else -> null
            }
            else -> null
        }
    }

    /** cmap 표. 유니코드 부표(형식 4·12)만 본다 — 한글이 있는지 알기에는 그걸로 충분하다. */
    private class Cmap(private val source: Source, private val at: Long, private val length: Int) {
        private val subtable: Buf? = run {
            val head = Buf(source.read(at, 4))
            val count = head.u16(2)
            val records = Buf(source.read(at + 4, count * 8))
            val candidates = (0 until count).map { i ->
                Triple(records.u16(i * 8), records.u16(i * 8 + 2), records.u32(i * 8 + 4))
            }
            fun rank(platform: Int, encoding: Int) = when {
                platform == 3 && encoding == 10 -> 0
                platform == 0 && encoding >= 4 -> 1
                platform == 3 && encoding == 1 -> 2
                platform == 0 -> 3
                else -> 9
            }
            for ((platform, encoding, offset) in candidates.sortedBy { rank(it.first, it.second) }) {
                if (rank(platform, encoding) == 9) break
                val start = at + offset
                val format = Buf(source.read(start, 2)).u16(0)
                val size = when (format) {
                    // 형식 4 의 길이 칸은 16비트라, 큰 CJK 폰트는 65535 로 잘려 적혀 있기도 하다.
                    // 표 끝까지 읽는다.
                    4 -> at + length - start
                    12 -> Buf(source.read(start, 8)).u32(4)
                    else -> continue
                }
                if (size > MAX_CMAP) continue
                return@run Buf(source.read(start, size.toInt()))
            }
            null
        }

        fun coversAll(text: String): Boolean {
            val t = subtable ?: return false
            return text.all { glyph(t, it.code) != 0 }
        }

        private fun glyph(t: Buf, c: Int): Int = when (t.u16(0)) {
            4 -> {
                val segX2 = t.u16(6)
                val ends = 14
                val starts = ends + segX2 + 2
                val deltas = starts + segX2
                val ranges = deltas + segX2
                var found = 0
                for (i in 0 until segX2 / 2) {
                    if (t.u16(ends + i * 2) < c) continue
                    val start = t.u16(starts + i * 2)
                    if (start > c) break
                    val delta = t.u16(deltas + i * 2)
                    val rangeOffset = t.u16(ranges + i * 2)
                    found = if (rangeOffset == 0) {
                        (c + delta) and 0xFFFF
                    } else {
                        val at = ranges + i * 2 + rangeOffset + (c - start) * 2
                        if (at + 2 > t.size) 0 else t.u16(at).let { g -> if (g == 0) 0 else (g + delta) and 0xFFFF }
                    }
                    break
                }
                found
            }
            12 -> {
                val groups = t.u32(12).toInt()
                var found = 0
                for (i in 0 until groups) {
                    val g = 16 + i * 12
                    if (g + 12 > t.size) break
                    val start = t.u32(g)
                    if (c < start || c > t.u32(g + 4)) continue
                    found = (t.u32(g + 8) + (c - start)).toInt()
                    break
                }
                found
            }
            else -> 0
        }
    }

    /** 큰 끝 정수 읽기. 범위를 벗어나면 "깨진 폰트" 로 거절한다. */
    private class Buf(private val b: ByteArray) {
        val size: Int get() = b.size

        fun u16(at: Int): Int {
            if (at < 0 || at + 2 > b.size) throw FontFormatException(FontFormatException.Reason.Broken, "u16 at $at")
            return ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        }

        fun u32(at: Int): Long {
            if (at < 0 || at + 4 > b.size) throw FontFormatException(FontFormatException.Reason.Broken, "u32 at $at")
            return (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }

        fun bytes(at: Int, length: Int): ByteArray = b.copyOfRange(at, at + length)
    }

    /** 한글 음절 몇 자. 완성형 2,350자만 있는 옛 폰트도 이 글자들은 갖고 있다. */
    private const val HANGUL_PROBE = "가나다한"

    private const val MAX_CMAP = 4L * 1024 * 1024

    private const val TTCF = 0x74746366L
    private const val WOFF = 0x774F4646L
    private const val WOFF2 = 0x774F4632L
    private const val TRUETYPE = 0x00010000L
    private const val OTTO = 0x4F54544FL
    private const val TRUE = 0x74727565L
    private const val NAME = 0x6E616D65L
    private const val CMAP = 0x636D6170L
    private const val OS2 = 0x4F532F32L
    private const val HEAD = 0x68656164L
}
