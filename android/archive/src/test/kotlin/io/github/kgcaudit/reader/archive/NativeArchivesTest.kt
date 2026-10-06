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
        assertEquals(Unpacked.ALL, NativeArchives.extract(NativeKind.RAR, rar.path, out, null))
        for ((name, bytes) in pages) assertContentEquals(bytes, File(out, name).readBytes(), name)
        // 하나만.
        val one = File(tmp, "one")
        assertEquals(Unpacked.ALL, NativeArchives.extract(NativeKind.RAR, rar.path, one, setOf("002.png")))
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
        assertEquals(Unpacked.ALL, NativeArchives.extract(NativeKind.SEVEN_Z, cb7.path, out, null))
        assertTrue(File(out, "ComicInfo.xml").readText().contains("YesAndRightToLeft"))
        assertEquals(0x89.toByte(), File(out, "001.png").readBytes()[0])
    }

    @Test
    fun `an encrypted or damaged rar page is skipped and the pages after it still unpack`() {
        assumeTrue("이 PC 에는 해제기가 없다", NativeArchives.available(NativeKind.RAR))
        // 암호 걸린 쪽 하나에서 멈추던 때는 뒤 쪽들이 모두 빈 쪽이 됐고, 끝까지 못 돌아 만화를 열 때마다 통째로 다시 풀었다.
        val locked = file("locked.cbr", StoredArchives.rar4(pages, encrypted = setOf("002.png")))
        val out = File(tmp, "locked")
        assertEquals(Unpacked.SKIPPED_SOME, NativeArchives.extract(NativeKind.RAR, locked.path, out, null))
        assertContentEquals(pages[0].second, File(out, "001.png").readBytes())
        assertContentEquals(pages[2].second, File(out, "sub/003.png").readBytes())
        assertFalse(File(out, "002.png").exists(), "풀 수 없는 쪽이 파일로 남았다")

        // 내용이 깨진 쪽(CRC 불일치)도 같다 — 깨진 파일은 남기지 않는다.
        val good = StoredArchives.rar4(pages)
        val at = (0..good.size - 20).first { i -> (0 until 20).all { good[i + it] == pages[1].second[it] } }
        val damaged = file("damaged.cbr", good.copyOf().also { it[at + 100] = (it[at + 100] + 1).toByte() })
        val out2 = File(tmp, "damaged")
        assertEquals(Unpacked.SKIPPED_SOME, NativeArchives.extract(NativeKind.RAR, damaged.path, out2, null))
        assertTrue(File(out2, "sub/003.png").isFile, "깨진 쪽 뒤의 쪽을 풀지 않았다")
        assertFalse(File(out2, "002.png").exists(), "깨진 쪽이 온전한 쪽처럼 남았다")
    }

    @Test
    fun `a seven zip entry with an unsafe name is skipped and the rest unpack`() {
        assumeTrue("이 PC 에는 해제기가 없다", NativeArchives.available(NativeKind.SEVEN_Z))
        // "../evil.png" 는 풀 곳 밖에 쓰인다 — 건너뛰되, 그 하나 때문에 끝까지 못 돈 것으로 치면 열 때마다 다시 풀었다.
        val cb7 = file("unsafe.cb7", javaClass.getResource("/unsafe.cb7")!!.readBytes())
        val out = File(tmp, "inside/out")
        assertEquals(Unpacked.SKIPPED_SOME, NativeArchives.extract(NativeKind.SEVEN_Z, cb7.path, out, null))
        assertEquals("one", File(out, "001.png").readText())
        assertEquals("two", File(out, "002.png").readText())
        assertFalse(File(tmp, "inside/evil.png").exists(), "풀 곳 밖에 썼다")
    }

    @Test
    fun `a seven zip page that cannot be written stops the run and leaves no cut-off file`() {
        assumeTrue("이 PC 에는 해제기가 없다", NativeArchives.available(NativeKind.SEVEN_Z))
        val full = File("/dev/full")
        assumeTrue("/dev/full 이 없다", full.exists() && full.canWrite())
        // 저장 공간이 모자라 쓰다 만 쪽을 "다 풀었음" 으로 돌려주면, 부르는 쪽이 표시를 남겨 그 쪽이 영영 잘린 채 보였다.
        // 공간이 없는 장치로 이어진 이름에 쓰게 해 본다 — fwrite · fclose 가 실패한다.
        val cb7 = file("full.cb7", javaClass.getResource("/comic.cb7")!!.readBytes())
        val out = File(tmp, "full").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(out, "001.png").toPath(), full.toPath())
        assertEquals(Unpacked.STOPPED, NativeArchives.extract(NativeKind.SEVEN_Z, cb7.path, out, null))
        assertFalse(java.nio.file.Files.exists(File(out, "001.png").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS), "잘린 쪽을 남겼다")
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
