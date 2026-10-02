package io.github.kgcaudit.reader.document.comic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicContentsTest {

    @Test
    fun `pages come in natural order and junk is ignored`() {
        val c = ComicContents.ofArchive(
            listOf("10.jpg", "2.jpg", "1.jpg", "__MACOSX/._1.jpg", ".DS_Store", "Thumbs.db", "ComicInfo.xml", "dir/"),
            trustExtension = true,
        )!!
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), c.pages)
        assertEquals("ComicInfo.xml", c.comicInfo)
        assertTrue(c.sections.isEmpty())
    }

    @Test
    fun `a plain zip is a comic only when it holds nothing but pictures`() {
        assertNotNull(ComicContents.ofArchive(listOf("a.jpg", "b.png", "info.nfo"), trustExtension = false))
        // 소설 zip(글 + 표지 그림)은 만화가 아니다.
        assertNull(ComicContents.ofArchive(listOf("소설.txt", "cover.jpg", "1.jpg"), trustExtension = false))
        assertNull(ComicContents.ofArchive(listOf("photo.jpg"), trustExtension = false))
        // cbz 는 이름이 만화라고 말하므로 한 장이어도 만화다.
        assertNotNull(ComicContents.ofArchive(listOf("photo.jpg"), trustExtension = true))
        assertNull(ComicContents.ofArchive(emptyList(), trustExtension = true))
        assertNull(ComicContents.ofArchive(listOf("readme.txt", "a/"), trustExtension = true))
    }

    @Test
    fun `an omnibus with a folder per volume shows its volumes`() {
        val c = ComicContents.ofArchive(
            listOf("별 4-6권/4권/001.jpg", "별 4-6권/4권/002.jpg", "별 4-6권/5권/001.jpg", "별 4-6권/6권/001.jpg", "별 4-6권/6권/002.jpg"),
            trustExtension = true,
        )!!
        assertEquals(listOf("4권", "5권", "6권"), c.sections.map { it.name })
        assertEquals(listOf(0, 2, 3), c.sections.map { it.firstPage })
        assertEquals(listOf(2, 1, 2), c.sections.map { it.pageCount })
    }

    @Test
    fun `one wrapping folder is not a table of contents`() {
        val c = ComicContents.ofArchive(listOf("별 1권/001.jpg", "별 1권/002.jpg"), trustExtension = true)!!
        assertTrue(c.sections.isEmpty())
    }

    @Test
    fun `the cover is the picture named cover, else the first page`() {
        assertEquals("x/cover.jpg", ComicContents.ofArchive(listOf("x/001.jpg", "x/cover.jpg"), true)!!.cover)
        assertEquals("001.jpg", ComicContents.ofArchive(listOf("002.jpg", "001.jpg"), true)!!.cover)
    }

    @Test
    fun `a folder needs three pictures and no books to be a comic`() {
        assertNotNull(ComicContents.ofFolder(listOf("1.jpg", "2.jpg", "3.jpg")))
        assertNull(ComicContents.ofFolder(listOf("cover.jpg", "author.jpg")), "표지 두 장 폴더를 만화로 봤다")
        assertNull(ComicContents.ofFolder(listOf("1.jpg", "2.jpg", "3.jpg", "책.epub")), "책 폴더의 삽화를 만화로 봤다")
    }
}
