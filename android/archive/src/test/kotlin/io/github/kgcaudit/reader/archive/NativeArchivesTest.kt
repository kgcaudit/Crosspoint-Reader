package io.github.kgcaudit.reader.archive

import io.github.kgcaudit.reader.document.archive.StoredArchives
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RAR · 7z 해제기(C++ 기준 구현)를 PC 에서 실제로 돌린다. 휴대폰용(arm64)과 같은 원본을 PC 용으로 빌드한 것이다
 * (build.gradle.kts 의 hostNatives). cmake · 컴파일러가 없는 PC 에서는 건너뛴다.
 */
class NativeArchivesTest {

    private val tmp = kotlin.io.path.createTempDirectory("olo-archive").toFile()
    private val pages = listOf("001.png" to "one".toByteArray(), "002.png" to ByteArray(3000) { (it % 251).toByte() }, "sub/003.png" to "three".toByteArray())

    private fun file(name: String, bytes: ByteArray) = File(tmp, name).apply { writeBytes(bytes) }

    @Test
    fun `archives are told apart by their first bytes, not their names`() {
        assertEquals(NativeKind.RAR, NativeArchives.kindOf(StoredArchives.rar4(pages)))
        assertEquals(NativeKind.SEVEN_Z, NativeArchives.kindOf(javaClass.getResource("/comic.cb7")!!.readBytes()))
        assertNull(NativeArchives.kindOf("PK\u0003\u0004 zip".toByteArray()))
        assertNull(NativeArchives.kindOf(ByteArray(3)))
    }

    @Test
    fun `a rar lists and unpacks to the same bytes`() {
        assumeTrue("이 PC 에는 해제기가 없다", NativeArchives.available(NativeKind.RAR))
        val rar = file("a.cbr", StoredArchives.rar4(pages))
        assertEquals(pages.map { it.first }.toSet(), NativeArchives.list(NativeKind.RAR, rar.path).filter { !it.isDirectory }.map { it.path }.toSet())
        val out = File(tmp, "out")
        assertTrue(NativeArchives.extract(NativeKind.RAR, rar.path, out, null))
        for ((name, bytes) in pages) assertContentEquals(bytes, File(out, name).readBytes(), name)
        // 하나만.
        val one = File(tmp, "one")
        assertTrue(NativeArchives.extract(NativeKind.RAR, rar.path, one, setOf("002.png")))
        assertTrue(File(one, "002.png").isFile)
        assertFalse(File(one, "001.png").exists(), "고른 것 말고도 풀었다")
    }

    @Test
    fun `a seven zip comic lists and unpacks`() {
        assumeTrue("이 PC 에는 해제기가 없다", NativeArchives.available(NativeKind.SEVEN_Z))
        val cb7 = file("a.cb7", javaClass.getResource("/comic.cb7")!!.readBytes())
        val names = NativeArchives.list(NativeKind.SEVEN_Z, cb7.path).map { it.path }
        assertEquals(listOf("001.png", "002.png", "003.png", "ComicInfo.xml"), names.sorted())
        val out = File(tmp, "out7")
        assertTrue(NativeArchives.extract(NativeKind.SEVEN_Z, cb7.path, out, null))
        assertTrue(File(out, "ComicInfo.xml").readText().contains("YesAndRightToLeft"))
        assertEquals(0x89.toByte(), File(out, "001.png").readBytes()[0])
    }

    @Test
    fun `a broken or truncated archive is refused instead of crashing`() {
        assumeTrue(NativeArchives.available(NativeKind.RAR) && NativeArchives.available(NativeKind.SEVEN_Z))
        val good = StoredArchives.rar4(pages)
        // 머리 CRC 를 망가뜨린 RAR · 반쯤 잘린 7z · 없는 파일.
        val badRar = file("bad.cbr", good.copyOf().also { it[7] = (it[7] + 1).toByte() })
        assertFailsWith<java.io.IOException> { NativeArchives.list(NativeKind.RAR, badRar.path) }
        val seven = javaClass.getResource("/comic.cb7")!!.readBytes()
        val cut = file("cut.cb7", seven.copyOf(seven.size / 2))
        assertFailsWith<java.io.IOException> { NativeArchives.list(NativeKind.SEVEN_Z, cut.path) }
        assertFailsWith<java.io.IOException> { NativeArchives.list(NativeKind.RAR, File(tmp, "none.cbr").path) }
    }
}
