package io.github.kgcaudit.reader.document.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * 시험용 PDF 를 바이트로 만든다. 사용자 책은 저장소에 넣지 않으므로, 실제 책에서 본 구조(쪽 나무 ·
 * 목차 나무 · 명명 목적지 · 덧붙인 판 · 상호 참조 스트림)를 여기서 그대로 짓는다.
 *
 * 객체 본문은 PDF 문법 문자열로 받는다(`"<< /Type /Catalog … >>"`). 문자열에 넣을 수 없는 바이트는
 * [raw] 로.
 *
 * 테스트 픽스처다: :reader-pdf · :app 의 테스트도 같은 것으로 목차 있는 PDF 를 만든다.
 */
class TestPdf {
    private val objects = LinkedHashMap<Int, ByteArray>()

    fun obj(num: Int, body: String) = raw(num, body.toByteArray(Charsets.ISO_8859_1))

    fun raw(num: Int, body: ByteArray) {
        objects[num] = body
    }

    /** 옛 상호 참조표. [shift] 만큼 위치를 틀리게 적는다(깨진 파일 흉내). */
    fun classic(root: Int = 1, trailerExtra: String = "", shift: Int = 0, writeXref: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = HashMap<Int, Int>()
        for ((num, body) in objects) {
            offsets[num] = out.size()
            out.write("$num 0 obj\n".toByteArray())
            out.write(body)
            out.write("\nendobj\n".toByteArray())
        }
        if (!writeXref) return out.toByteArray()
        val xrefAt = out.size()
        val size = (objects.keys.maxOrNull() ?: 0) + 1
        val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) {
            val off = offsets[n]
            sb.append(if (off == null) "0000000000 65535 f \n" else "%010d 00000 n \n".format(off + shift))
        }
        sb.append("trailer\n<< /Size $size /Root $root 0 R $trailerExtra >>\nstartxref\n$xrefAt\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /**
     * PDF 1.5 모양: [inStream] 번호의 객체는 객체 스트림(ObjStm) 안에, 상호 참조는 PNG 예측자(Up)를 건
     * FlateDecode 스트림으로.
     */
    fun modern(root: Int = 1, inStream: Set<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray())
        val offsets = HashMap<Int, Int>()
        val direct = objects.filterKeys { it !in inStream }
        for ((num, body) in direct) {
            offsets[num] = out.size()
            out.write("$num 0 obj\n".toByteArray())
            out.write(body)
            out.write("\nendobj\n".toByteArray())
        }
        val packed = objects.filterKeys { it in inStream }.toList()
        val stmNum = (objects.keys.maxOrNull() ?: 0) + 1
        val xrefNum = stmNum + 1
        // 객체 스트림: "번호 위치 번호 위치 …" 머리 + 본문들.
        val bodies = ByteArrayOutputStream()
        val header = StringBuilder()
        for ((num, body) in packed) {
            header.append("$num ${bodies.size()} ")
            bodies.write(body)
            bodies.write(' '.code)
        }
        val first = header.length
        val stmData = deflate(header.toString().toByteArray() + bodies.toByteArray())
        offsets[stmNum] = out.size()
        out.write("$stmNum 0 obj\n<< /Type /ObjStm /N ${packed.size} /First $first /Filter /FlateDecode /Length ${stmData.size} >>\nstream\n".toByteArray())
        out.write(stmData)
        out.write("\nendstream\nendobj\n".toByteArray())

        // 상호 참조 스트림: 행 = 종류(1) · 위치/스트림 번호(4) · 세대/순번(2). PNG Up 예측자로 적는다.
        val size = xrefNum + 1
        val xrefAt = out.size()
        offsets[xrefNum] = xrefAt
        val rows = ArrayList<ByteArray>()
        for (n in 0 until size) {
            val row = when {
                n == 0 -> byteArrayOf(0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte())
                offsets[n] != null -> byteArrayOf(1) + int(offsets[n]!!, 4) + int(0, 2)
                packed.any { it.first == n } -> byteArrayOf(2) + int(stmNum, 4) + int(packed.indexOfFirst { it.first == n }, 2)
                else -> ByteArray(7)
            }
            rows.add(row)
        }
        val predicted = ByteArrayOutputStream()
        var prev = ByteArray(7)
        for (row in rows) {
            predicted.write(2) // Up
            for (i in row.indices) predicted.write((row[i] - prev[i]) and 0xFF)
            prev = row
        }
        val xrefData = deflate(predicted.toByteArray())
        out.write(
            (
                "$xrefNum 0 obj\n<< /Type /XRef /Size $size /Root $root 0 R /W [1 4 2] /Filter /FlateDecode " +
                    "/DecodeParms << /Predictor 12 /Columns 7 >> /Length ${xrefData.size} >>\nstream\n"
                ).toByteArray(),
        )
        out.write(xrefData)
        out.write("\nendstream\nendobj\nstartxref\n$xrefAt\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    companion object {
        /** 제목용 UTF-16BE 16진 문자열(BOM 포함). */
        fun utf16(text: String): String =
            "<FEFF" + text.toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it) } + ">"

        /**
         * 제목용 UTF-16BE **괄호** 문자열. 실제 책처럼 제어 바이트를 이스케이프로 적는다(0x08 → `\b`,
         * 괄호·역슬래시 → `\(` `\)` `\\`, 나머지 제어 바이트 → 8진수).
         */
        fun utf16Literal(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write('('.code)
            for (b in byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)) {
                when (val v = b.toInt() and 0xFF) {
                    0x08 -> out.write("\\b".toByteArray())
                    0x0A -> out.write("\\n".toByteArray())
                    0x0D -> out.write("\\r".toByteArray())
                    '('.code, ')'.code, '\\'.code -> { out.write('\\'.code); out.write(v) }
                    in 0x00..0x1F -> out.write("\\%03o".format(v).toByteArray())
                    else -> out.write(v)
                }
            }
            out.write(')'.code)
            return out.toByteArray()
        }

        /** 쪽 [count] 장. 쪽 나무를 두 단으로 나눈다(`/Kids` 안의 `/Pages`) — 순서를 나무 순서로 세는지 본다. */
        fun TestPdf.pages(firstNum: Int, count: Int): List<Int> {
            val pagesRoot = firstNum
            val left = firstNum + 1
            val right = firstNum + 2
            val pageNums = (0 until count).map { firstNum + 3 + it }
            val half = count / 2
            obj(pagesRoot, "<< /Type /Pages /Kids [$left 0 R $right 0 R] /Count $count >>")
            obj(left, "<< /Type /Pages /Parent $pagesRoot 0 R /Kids [${pageNums.take(half).joinToString(" ") { "$it 0 R" }}] /Count $half >>")
            obj(right, "<< /Type /Pages /Parent $pagesRoot 0 R /Kids [${pageNums.drop(half).joinToString(" ") { "$it 0 R" }}] /Count ${count - half} >>")
            pageNums.forEachIndexed { i, n ->
                obj(n, "<< /Type /Page /Parent ${if (i < half) left else right} 0 R /MediaBox [0 0 595 842] >>")
            }
            return pageNums
        }

        private fun int(value: Int, width: Int) = ByteArray(width) { i -> (value shr (8 * (width - 1 - i))).toByte() }

        private fun deflate(data: ByteArray): ByteArray {
            val d = Deflater()
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }
    }
}
