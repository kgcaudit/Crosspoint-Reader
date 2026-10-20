@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.crc32

/** 압축 없이(stored) 짓는 zip. 시험 EPUB 을 코드에서 짓는다 — 파일로 두면 무엇이 든 책인지 시험에서 보이지 않는다. */
internal fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ArrayList<Byte>()
    val central = ArrayList<Byte>()
    fun MutableList<Byte>.le(value: Long, bytes: Int) = repeat(bytes) { add((value shr (8 * it)).toByte()) }
    for ((name, data) in entries) {
        val nameBytes = name.encodeToByteArray()
        val crc = data.usePinned { crc32(0u, it.addressOf(0).reinterpret(), data.size.toUInt()) }.toLong()
        val offset = out.size.toLong()
        out.le(0x04034b50, 4); out.le(20, 2); out.le(0x0800, 2); out.le(0, 2); out.le(0, 4)
        out.le(crc, 4); out.le(data.size.toLong(), 4); out.le(data.size.toLong(), 4)
        out.le(nameBytes.size.toLong(), 2); out.le(0, 2)
        out.addAll(nameBytes.toList()); out.addAll(data.toList())
        central.le(0x02014b50, 4); central.le(20, 2); central.le(20, 2); central.le(0x0800, 2); central.le(0, 2); central.le(0, 4)
        central.le(crc, 4); central.le(data.size.toLong(), 4); central.le(data.size.toLong(), 4)
        central.le(nameBytes.size.toLong(), 2); central.le(0, 2); central.le(0, 2); central.le(0, 2); central.le(0, 2); central.le(0, 4)
        central.le(offset, 4); central.addAll(nameBytes.toList())
    }
    val centralOffset = out.size.toLong()
    out.addAll(central)
    out.le(0x06054b50, 4); out.le(0, 2); out.le(0, 2); out.le(entries.size.toLong(), 2); out.le(entries.size.toLong(), 2)
    out.le(central.size.toLong(), 4); out.le(centralOffset, 4); out.le(0, 2)
    return out.toByteArray()
}
