@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.crc32

/**
 * 시험용 RAR 4 "저장" 압축(안드로이드 `StoredArchives.rar4` 를 옮김). RAR 을 만드는 도구는 상용이라 쓸 수 없어(UnRAR 허가도 RAR 을
 * 만드는 데 쓰지 못하게 한다) 형식 문서의 저장 블록을 손으로 쓴다. 해제기가 머리 · 내용 CRC 를 확인하므로 틀리게 쓰면 시험이 안다.
 *
 * @param encrypted 암호 표시만 세운 항목 — 내용은 그대로라 풀 수 없다. 암호 걸린 쪽을 건너뛰는지 보는 데 쓴다.
 */
internal fun storedRar(entries: List<Pair<String, ByteArray>>, encrypted: Set<String> = emptySet()): ByteArray {
    val out = Bytes()
    out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
    out.write(block(0x73, 0, ByteArray(6)))
    for ((name, data) in entries) {
        val n = name.encodeToByteArray()
        val body = Bytes().apply {
            le32(data.size.toLong())
            le32(data.size.toLong())
            write(3)  // 만든 체계: 유닉스
            le32(crc(data))
            le32(0x5A210000L)
            write(20)  // 풀 때 필요한 판 2.0
            write(0x30)  // 방식: 저장
            le16(n.size)
            le32(0x81A4)
            write(n)
        }.bytes()
        out.write(block(0x74, 0x8000 or (if (name in encrypted) 0x04 else 0), body))
        out.write(data)
    }
    out.write(block(0x7B, 0x4000, ByteArray(0)))
    return out.bytes()
}

private fun block(type: Int, flags: Int, rest: ByteArray): ByteArray {
    val head = Bytes().apply { write(type); le16(flags); le16(7 + rest.size); write(rest) }.bytes()
    return Bytes().apply { le16((crc(head) and 0xFFFF).toInt()); write(head) }.bytes()
}

private fun crc(data: ByteArray): Long =
    if (data.isEmpty()) 0L else data.usePinned { crc32(0u, it.addressOf(0).reinterpret(), data.size.toUInt()) }.toLong()

private class Bytes {
    private val list = ArrayList<Byte>()
    fun write(b: Int) { list.add(b.toByte()) }
    fun write(b: ByteArray) { b.forEach { list.add(it) } }
    fun le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    fun le32(v: Long) { for (i in 0 until 4) write(((v shr (8 * i)) and 0xFF).toInt()) }
    fun bytes() = list.toByteArray()
}
