package io.github.kgcaudit.reader.data.library

import io.github.kgcaudit.reader.document.BookFormat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 폴더 트리를 훑는 규칙. SAF 없이 가짜 트리로 본다. */
class LibraryScannerTest {

    private suspend fun names(tree: DocumentTree) = LibraryScanner.scan(tree).books.map { it.displayName }.sorted()

    @Test
    fun `books are found in nested folders and other files are ignored`() = runTest {
        val tree = FakeTree(
            listOf(
                "어린 왕자.epub",
                "소설/한국/토지 1.EPUB",
                "소설/한국/메모.txt",
                "문서/계약서.pdf",
                "문서/사진.jpg",
                "문서/노트.docx",
                "문서/확장자없음",
            ),
        )
        val result = LibraryScanner.scan(tree)

        assertTrue(result.complete)
        assertEquals(listOf("계약서.pdf", "메모.txt", "어린 왕자.epub", "토지 1.EPUB"), names(tree))
        assertEquals(BookFormat.EPUB, result.books.single { it.displayName == "토지 1.EPUB" }.format)
    }

    @Test
    fun `hidden folders and hidden files are skipped`() = runTest {
        // 휴지통·썸네일 폴더의 책이 목록에 나오면 지운 책이 되살아난 것처럼 보인다.
        val tree = FakeTree(listOf("책.epub", ".trash/지운 책.epub", "책들/.epub", ".thumbnails/a.pdf"))
        assertEquals(listOf("책.epub"), names(tree))
    }

    @Test
    fun `a folder that cannot be listed is skipped but marks the scan incomplete`() = runTest {
        // 한 폴더 때문에 전체가 실패하면 안 되고, 그렇다고 "완전한 스캔" 으로 보고하면
        // 그 폴더의 책이 전부 사라진 것으로 처리된다.
        val tree = FakeTree(listOf("a/하나.epub", "b/둘.epub", "c/셋.epub"), failing = setOf("b"))
        val result = LibraryScanner.scan(tree)

        assertFalse(result.complete)
        assertEquals(listOf("셋.epub", "하나.epub"), names(tree))
    }

    @Test
    fun `an unreadable root is an incomplete scan, not an empty library`() = runTest {
        val result = LibraryScanner.scan(FakeTree(listOf("책.epub"), failing = setOf("")))
        assertFalse(result.complete)
        assertTrue(result.books.isEmpty())
    }

    @Test
    fun `a folder that links back to its parent does not loop forever`() = runTest {
        val tree = FakeTree(listOf("a/b/책.epub"))
        tree.link(from = "a/b", name = "위로", to = "a")
        tree.link(from = "a/b", name = "처음", to = "")

        val result = LibraryScanner.scan(tree)
        assertEquals(listOf("책.epub"), result.books.map { it.displayName })
        // 이미 본 폴더로 돌아가는 링크는 건너뛰는 것이지 못 읽은 것이 아니다. 깊이 제한에
        // 걸려 멈춘 것이라면 불완전으로 나와 사라진 책을 영영 숨기지 못한다.
        assertTrue(result.complete)
    }

    @Test
    fun `a tree deeper than the limit stops there and reports incomplete`() = runTest {
        // key 가 매번 새로 생기는 링크는 방문 기록으로 못 막는다. 깊이 제한이 마지막 방어선이다.
        val deep = (1..LibraryScanner.MAX_DEPTH + 5).joinToString("/") { "d$it" }
        val tree = FakeTree(listOf("위.epub", "$deep/깊은 책.epub"))

        val result = LibraryScanner.scan(tree)
        assertFalse(result.complete)
        assertEquals(listOf("위.epub"), result.books.map { it.displayName })
    }
}

class ComicScanTest {

    private suspend fun scan(vararg paths: String, rootName: String = "Comics", rootUri: String? = "content://fake/root") =
        LibraryScanner.scan(FakeTree(paths.toList(), rootName = rootName, rootUri = rootUri))

    @Test
    fun `comic archives are found with the folders they sit in, and are not books`() = kotlinx.coroutines.test.runTest {
        val r = scan("별을 줍는 아이/별을 줍는 아이 01권.cbz", "Download/바다.cbr", "x/사진.zip", "x/소설.epub")
        assertEquals(listOf("소설.epub"), r.books.map { it.displayName })
        val star = r.comics.single { it.name.endsWith(".cbz") }
        assertEquals(listOf("Comics", "별을 줍는 아이"), star.folders)
        assertEquals("cbz", star.extension)
        // 그냥 zip 도 후보로 올린다 — 살펴서 그림만 들었는지 본다.
        assertEquals("zip", r.comics.single { it.name == "사진.zip" }.extension)
        assertEquals(setOf("cbz", "cbr", "zip"), r.comics.map { it.extension }.toSet())
    }

    @Test
    fun `a folder of pictures is one comic unit, a folder with a book or two pictures is not`() = kotlinx.coroutines.test.runTest {
        val r = scan(
            "전학생/001화/1.jpg", "전학생/001화/2.jpg", "전학생/001화/3.jpg",
            "전학생/002화/1.jpg", "전학생/002화/2.jpg", "전학생/002화/3.webp",
            "전학생/cover.jpg",
            "책/소설.epub", "책/a.jpg", "책/b.jpg", "책/c.jpg",
            "표지/a.jpg", "표지/b.jpg",
        )
        val folders = r.comics.filter { it.kind == io.github.kgcaudit.reader.document.comic.ComicUnitKind.IMAGE_FOLDER }
        assertEquals(listOf("001화", "002화"), folders.map { it.name }.sorted())
        assertEquals(listOf("Comics", "전학생"), folders.first().folders)
        assertEquals(3, folders.first().folderContents!!.pages.size)
        // 폴더 단위의 열쇠는 그 폴더 자신의 URI.
        assertEquals(FakeTree.uriOf("전학생/001화"), folders.single { it.name == "001화" }.uri)
    }

    @Test
    fun `the registered folder itself can be a picture folder`() = kotlinx.coroutines.test.runTest {
        val r = scan("1.jpg", "2.jpg", "3.jpg", rootName = "017화")
        assertEquals("content://fake/root", r.comics.single().uri)
        assertEquals("017화", r.comics.single().name)
        // 등록 폴더의 URI 를 모르면(옛 제공자) 건너뛸 뿐 훑기는 끝까지 간다.
        val unknown = scan("1.jpg", "2.jpg", "3.jpg", rootUri = null)
        assertTrue(unknown.comics.isEmpty() && unknown.complete)
    }
}
