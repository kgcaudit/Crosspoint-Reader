package io.github.kgcaudit.reader.document.archive

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * 시험용 압축 견본을 그 자리에서 쓴다(0.37.0). 압축하지 않고 담기만(store) 하므로 짧은 코드로 형식을 지킬 수 있다.
 *
 * RAR 을 만드는 도구는 상용이라 쓸 수 없어(UnRAR 허가도 RAR 을 만드는 데 쓰지 못하게 한다) RAR 1.5–4.x 형식 문서
 * (technote.txt)의 "저장" 블록을 손으로 쓴다. 해제기(UnRAR)가 머리 CRC · 내용 CRC 를 확인하므로 틀리게 쓰면 시험이 안다.
 */
object StoredArchives {

    /** RAR 4 저장 압축. 이름은 ASCII 만(유니코드 이름은 따로 부호화해야 한다). */
    fun rar4(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        // 압축 머리: 종류 0x73, 플래그 0, 크기 13, 예약 6바이트.
        out.write(block(0x73, 0, ByteArray(6)))
        for ((name, data) in entries) {
            val n = name.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(data) }.value
            val body = ByteArrayOutputStream().apply {
                le32(data.size.toLong()) // 묶은 크기
                le32(data.size.toLong()) // 푼 크기
                write(3) // 만든 체계: 유닉스
                le32(crc)
                le32(DOS_TIME)
                write(20) // 풀 때 필요한 판 2.0
                write(0x30) // 방식: 저장
                le16(n.size)
                le32(0x81A4) // 유닉스 파일 속성(rw-r--r--)
                write(n)
            }.toByteArray()
            // 0x8000: 머리 뒤에 내용(ADD_SIZE = 묶은 크기)이 따른다.
            out.write(block(0x74, 0x8000, body))
            out.write(data)
        }
        out.write(block(0x7B, 0x4000, ByteArray(0)))
        return out.toByteArray()
    }

    /** POSIX tar(ustar) — cbt. 512바이트 머리 + 512 단위로 채운 내용, 끝에 빈 블록 둘. */
    fun tar(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val h = ByteArray(512)
            fun put(at: Int, s: String) = s.toByteArray(Charsets.UTF_8).copyInto(h, at)
            put(0, name)
            put(100, "0000644\u0000")
            put(108, "0000000\u0000")
            put(116, "0000000\u0000")
            put(124, "%011o\u0000".format(data.size))
            put(136, "%011o\u0000".format(DOS_TIME))
            h[156] = '0'.code.toByte()
            put(257, "ustar\u000000")
            // 체크섬: 체크섬 칸을 빈칸으로 두고 더한 값.
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xFF }
            put(148, "%06o\u0000 ".format(sum))
            out.write(h)
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private const val DOS_TIME = 0x5A210000L

    private fun block(type: Int, flags: Int, rest: ByteArray): ByteArray {
        val head = ByteArrayOutputStream().apply {
            write(type)
            le16(flags)
            le16(7 + rest.size)
            write(rest)
        }.toByteArray()
        val crc = CRC32().apply { update(head) }.value and 0xFFFF
        return ByteArrayOutputStream().apply { le16(crc.toInt()); write(head) }.toByteArray()
    }

    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.le32(v: Long) { for (i in 0 until 4) write(((v shr (8 * i)) and 0xFF).toInt()) }
}
