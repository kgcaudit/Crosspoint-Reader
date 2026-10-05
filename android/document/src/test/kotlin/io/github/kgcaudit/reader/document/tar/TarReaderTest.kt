package io.github.kgcaudit.reader.document.tar

import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.archive.StoredArchives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TarReaderTest {

    private val pages = listOf("001.png" to ByteArray(700) { 1 }, "02/002.png" to "two".toByteArray(), "ComicInfo.xml" to "<ComicInfo/>".toByteArray())

    @Test
    fun `a tar lists its files in order and reads each one from its own place`() {
        val tar = TarReader.open(SeekableSource.of(StoredArchives.tar(pages)))
        assertEquals(pages.map { it.first }, tar.entries.keys.toList())
        for ((name, bytes) in pages) assertContentEquals(bytes, tar.openStream(name)!!.readBytes(), name)
        assertEquals(null, tar.openStream("없는 쪽"))
        assertTrue(TarReader.looksLikeTar(StoredArchives.tar(pages)))
    }

    @Test
    fun `a gnu long name and a pax path name the following file`() {
        val long = "아주/긴/폴더/이름/".repeat(10) + "001.png"
        val body = long.toByteArray()
        // GNU 'L' 머리를 손으로: 이름 "././@LongLink", 종류 L, 내용이 긴 이름.
        val gnu = StoredArchives.tar(listOf("././@LongLink" to body, "short.png" to "x".toByteArray())).also { b ->
            b[156] = 'L'.code.toByte()
            fixChecksum(b, 0)
        }
        assertEquals(listOf(long), TarReader.open(SeekableSource.of(gnu)).entries.keys.toList())
        val pax = StoredArchives.tar(listOf("PaxHeader" to "30 path=만화/001화/001.png\n".toByteArray(), "x.png" to "x".toByteArray())).also { b ->
            b[156] = 'x'.code.toByte()
            fixChecksum(b, 0)
        }
        assertEquals(listOf("만화/001화/001.png"), TarReader.open(SeekableSource.of(pax)).entries.keys.toList())
    }

    @Test
    fun `a cut or broken tar keeps what came before and a non tar is refused`() {
        val whole = StoredArchives.tar(pages)
        // 둘째 머리(첫 쪽 700바이트 → 내용 두 블록 뒤, 1536)를 망가뜨림: 첫 항목만 남는다.
        val broken = whole.copyOf().also { it[1536 + 10] = 'Z'.code.toByte() }
        assertEquals(listOf("001.png"), TarReader.open(SeekableSource.of(broken)).entries.keys.toList())
        // 중간에서 잘림.
        assertEquals(listOf("001.png"), TarReader.open(SeekableSource.of(whole.copyOf(1600))).entries.keys.toList())
        assertFailsWith<java.io.IOException> { TarReader.open(SeekableSource.of(ByteArray(2048) { 7 })) }
        assertEquals(false, TarReader.looksLikeTar("PK\u0003\u0004".toByteArray() + ByteArray(600)))
    }

    @Test
    fun `a size past the end of the file stops reading instead of looping`() {
        val whole = StoredArchives.tar(pages)
        // 둘째 머리(1536)의 크기를 base-256 의 아주 큰 값으로: 다음 머리 자리를 셈하다 넘쳐 앞으로 되돌아가 끝없이 돌았다.
        val huge = whole.copyOf().also { b ->
            b[1536 + 124] = 0x80.toByte()
            for (i in 1536 + 125 until 1536 + 136) b[i] = 0x7F
            fixChecksum(b, 1536)
        }
        assertEquals(listOf("001.png"), TarReader.open(SeekableSource.of(huge)).entries.keys.toList())
        // 파일보다 조금 긴 크기(잘린 내용)도 그 항목을 버린다 — 두면 쪽을 열 때 끝 밖을 읽는다.
        val past = whole.copyOf().also { b ->
            "%011o\u0000".format(whole.size.toLong()).toByteArray().copyInto(b, 1536 + 124)
            fixChecksum(b, 1536)
        }
        assertEquals(listOf("001.png"), TarReader.open(SeekableSource.of(past)).entries.keys.toList())
    }

    private fun fixChecksum(b: ByteArray, at: Int) {
        for (i in at + 148 until at + 156) b[i] = ' '.code.toByte()
        var sum = 0
        for (i in at until at + 512) sum += b[i].toInt() and 0xFF
        "%06o\u0000 ".format(sum).toByteArray().copyInto(b, at + 148)
    }
}
