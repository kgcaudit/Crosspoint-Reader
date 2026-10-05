package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.slice
import io.github.kgcaudit.reader.document.zip.ZipReader
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NestedArchivesTest {

    private fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val outerName = "[김민혜] 인스타 걸 (1-3, 완결).zip"
    private val inner = listOf(
        "[김민혜] 인스타 걸 (1-3, 완결)/",
        "[김민혜] 인스타 걸 (1-3, 완결)/인스타 걸 2권.zip",
        "[김민혜] 인스타 걸 (1-3, 완결)/인스타 걸 10권.zip",
        "[김민혜] 인스타 걸 (1-3, 완결)/인스타 걸 1권.zip",
        "__MACOSX/[김민혜] 인스타 걸 (1-3, 완결)/._인스타 걸 1권.zip",
        "[김민혜] 인스타 걸 (1-3, 완결)/.zip",
        "[김민혜] 인스타 걸 (1-3, 완결)/읽어 주세요.txt",
    )

    @Test
    fun `volume archives inside an archive are found in reading order without junk`() {
        // 맥이 만든 "._" 그림자 · 이름 없는 ".zip" · 폴더 · 글 파일은 권이 아니다 — 권으로 펼치면 빈 권이 서재에 섞인다.
        assertEquals(
            listOf("인스타 걸 1권.zip", "인스타 걸 2권.zip", "인스타 걸 10권.zip"),
            NestedArchives.volumes(inner).map { it.substringAfterLast('/') },
        )
        assertTrue(NestedArchives.isVolumeName("a/별 3권.CBR"))
        assertEquals(false, NestedArchives.isVolumeName("a/사진.rar"))
        assertEquals(false, NestedArchives.isVolumeName("a/zip"))
    }

    @Test
    fun `a volume id leads back to its outer archive and inner path`() {
        val outer = "content://p/tree/root/document/root%2FComic%2F%5B%EA%B9%80%5D.zip"
        val id = NestedArchives.id(outer, inner[1])
        assertEquals(outer to inner[1], NestedArchives.split(id))
        // 보통 문서 주소는 압축 속 권이 아니다.
        assertNull(NestedArchives.split(outer))
        assertNull(NestedArchives.split(NestedArchives.SEPARATOR + "a.zip"))
        assertNull(NestedArchives.split(outer + NestedArchives.SEPARATOR))
    }

    @Test
    fun `the wrapper folder named like the outer archive is not repeated in the place`() {
        // 압축 프로그램이 폴더째 묶으면 생기는 같은 이름의 폴더 — 두면 "모은 곳" 이 "… .zip › … " 으로 같은 이름을 두 번 적는다.
        assertEquals(listOf("sdcard", "Comic", outerName), NestedArchives.folders(listOf("sdcard", "Comic"), outerName, inner[1]))
        assertEquals(listOf("Comic", outerName, "부록"), NestedArchives.folders(listOf("Comic"), outerName, "부록/외전.cbz"))
        assertEquals(listOf("Comic", outerName), NestedArchives.folders(listOf("Comic"), outerName, "외전.cbz"))
    }

    @Test
    fun `pictures beside volume archives still make a comic`() {
        // 결정 ③: 바깥 그림은 한 권, 안쪽 압축은 따로 권. 예전 규칙이면 zip 속 zip 이 "다른 파일" 이라 그림까지 통째로 빠졌다.
        val contents = ComicContents.ofArchive(listOf("001.jpg", "002.jpg", "외전.zip"), trustExtension = false)
        assertEquals(listOf("001.jpg", "002.jpg"), contents?.pages)
        // 권 압축만 있으면 그 자체로는 쪽이 없다.
        assertNull(ComicContents.ofArchive(inner, trustExtension = false))
    }

    @Test
    fun `a stored inner zip is read in place and a deflated one is not`() {
        val page = ByteArray(3000) { (it % 251).toByte() }
        val volume = zip(listOf("001.png" to page, "002.png" to page), stored = false)
        for (stored in listOf(true, false)) {
            val outer = ZipReader.open(SeekableSource.of(zip(listOf("권/별 1권.zip" to volume, "뒤.txt" to "x".toByteArray()), stored)))
            val slice = outer.storedSource("권/별 1권.zip")
            if (!stored) {
                // 압축해 담은 것은 구간을 그대로 읽으면 깨진 바이트다 — 꺼내서 열어야 한다.
                assertNull(slice)
                continue
            }
            assertNotNull(slice)
            assertEquals(volume.size.toLong(), slice.size)
            val innerZip = ZipReader.open(slice)
            assertEquals(listOf("001.png", "002.png"), innerZip.entries.keys.toList())
            assertContentEquals(page, innerZip.readBytes("002.png"))
        }
    }

    @Test
    fun `a slice never reads past its end into the next entry`() {
        val base = SeekableSource.of(ByteArray(100) { it.toByte() })
        val slice = base.slice(10, 20)
        val buf = ByteArray(50)
        assertEquals(20, slice.readAt(0, buf, 0, 50))
        assertEquals(29, buf[19].toInt())
        assertEquals(5, slice.readAt(15, buf, 0, 50))
        assertEquals(-1, slice.readAt(20, buf, 0, 5))
        assertFailsWith<IllegalArgumentException> { base.slice(90, 20) }
    }

    @Test
    fun `volumes from one outer archive become one work with its volumes`() {
        val outerId = "content://x/Comic/insta.zip"
        val units = listOf("인스타 걸 1권.zip", "인스타 걸 2권.zip", "인스타 걸 3권 (완결).zip").map { n ->
            ComicUnit(
                id = NestedArchives.id(outerId, "[김민혜] 인스타 걸 (1-3, 완결)/$n"),
                name = n,
                folders = listOf("Comic", outerName),
                kind = ComicUnitKind.NESTED,
            )
        }
        val work = ComicShelf.group(units).single()
        assertEquals("인스타 걸", work.title)
        assertEquals(listOf("1권", "2권", "3권"), work.entries.map { it.label })
        assertTrue(work.complete)
        assertEquals(listOf("Comic › $outerName"), work.places)
    }

    @Test
    fun `a volume without a series name takes the outer archive name without its extension`() {
        // "01.zip" 처럼 이름 없는 권: 작품 이름은 바깥 압축 이름에서 — 확장자까지 이름으로 읽으면 "별 .zip" 같은 작품이 따로 생긴다.
        val unit = ComicUnit(NestedArchives.id("o", "01.zip"), "01.zip", listOf("Comic", "별을 줍는 아이.zip"), ComicUnitKind.NESTED)
        assertEquals("별을 줍는 아이", ComicShelf.group(listOf(unit)).single().title)
    }
}
