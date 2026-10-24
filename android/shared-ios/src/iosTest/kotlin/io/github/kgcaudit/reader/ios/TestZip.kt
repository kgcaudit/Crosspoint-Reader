@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.crc32
import platform.zlib.uLongfVar

/** 압축 없이(stored) 짓는 zip. 시험 EPUB 을 코드에서 짓는다 — 파일로 두면 무엇이 든 책인지 시험에서 보이지 않는다. */
internal fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray = zip(entries, deflate = false)

/** 항목을 압축해(deflate) 담은 zip. 압축 프로그램이 보통 이렇게 묶는다 — 안쪽 압축을 그 자리에서 못 읽고 꺼내야 하는 경우. */
internal fun deflatedZip(entries: List<Pair<String, ByteArray>>): ByteArray = zip(entries, deflate = true)

/** zlib 의 compress2 는 앞 2바이트 머리 · 뒤 4바이트 검사합을 붙인다 — zip 의 deflate 는 그것을 뺀 날것이다. */
private fun rawDeflate(data: ByteArray): ByteArray = memScoped {
    val bound = compressBound(data.size.toULong()).toInt()
    val out = ByteArray(bound)
    val length = alloc<uLongfVar>()
    length.value = bound.toULong()
    val rc = out.usePinned { o ->
        (if (data.isEmpty()) byteArrayOf(0) else data).usePinned { i ->
            compress2(o.addressOf(0).reinterpret(), length.ptr, i.addressOf(0).reinterpret(), data.size.toULong(), 6)
        }
    }
    check(rc == 0) { "compress2 failed: $rc" }
    out.copyOfRange(2, length.value.toInt() - 4)
}

private fun zip(entries: List<Pair<String, ByteArray>>, deflate: Boolean): ByteArray {
    val out = ArrayList<Byte>()
    val central = ArrayList<Byte>()
    fun MutableList<Byte>.le(value: Long, bytes: Int) = repeat(bytes) { add((value shr (8 * it)).toByte()) }
    for ((name, data) in entries) {
        val nameBytes = name.encodeToByteArray()
        val crc = if (data.isEmpty()) 0L else data.usePinned { crc32(0u, it.addressOf(0).reinterpret(), data.size.toUInt()) }.toLong()
        val packed = if (deflate) rawDeflate(data) else data
        val method = if (deflate) 8L else 0L
        val offset = out.size.toLong()
        out.le(0x04034b50, 4); out.le(20, 2); out.le(0x0800, 2); out.le(method, 2); out.le(0, 4)
        out.le(crc, 4); out.le(packed.size.toLong(), 4); out.le(data.size.toLong(), 4)
        out.le(nameBytes.size.toLong(), 2); out.le(0, 2)
        out.addAll(nameBytes.toList()); out.addAll(packed.toList())
        central.le(0x02014b50, 4); central.le(20, 2); central.le(20, 2); central.le(0x0800, 2); central.le(method, 2); central.le(0, 4)
        central.le(crc, 4); central.le(packed.size.toLong(), 4); central.le(data.size.toLong(), 4)
        central.le(nameBytes.size.toLong(), 2); central.le(0, 2); central.le(0, 2); central.le(0, 2); central.le(0, 2); central.le(0, 4)
        central.le(offset, 4); central.addAll(nameBytes.toList())
    }
    val centralOffset = out.size.toLong()
    out.addAll(central)
    out.le(0x06054b50, 4); out.le(0, 2); out.le(0, 2); out.le(entries.size.toLong(), 2); out.le(entries.size.toLong(), 2)
    out.le(central.size.toLong(), 4); out.le(centralOffset, 4); out.le(0, 2)
    return out.toByteArray()
}
