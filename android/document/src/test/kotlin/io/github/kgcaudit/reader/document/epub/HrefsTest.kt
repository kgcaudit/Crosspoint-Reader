package io.github.kgcaudit.reader.document.epub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HrefsTest {

    @Test
    fun `a relative href resolves against the referring directory`() {
        assertEquals("OEBPS/ch1.xhtml", Hrefs.resolve("OEBPS", "ch1.xhtml"))
        assertEquals("OEBPS/text/ch1.xhtml", Hrefs.resolve("OEBPS/text", "ch1.xhtml"))
        assertEquals("ch1.xhtml", Hrefs.resolve("", "ch1.xhtml"))
    }

    @Test
    fun `parent segments are collapsed`() {
        // 챕터가 text/ 안에서 ../images/ 를 가리키는 것이 EPUB의 기본 배치다.
        assertEquals("OEBPS/images/a.png", Hrefs.resolve("OEBPS/text", "../images/a.png"))
        assertEquals("images/a.png", Hrefs.resolve("text", "../images/a.png"))
        assertEquals("OEBPS/a.png", Hrefs.resolve("OEBPS/a/b", "../../a.png"))
        // 루트를 넘어가는 '..' 는 루트에서 멈춘다(경로가 음수가 될 수는 없다).
        assertEquals("a.png", Hrefs.resolve("text", "../../../a.png"))
    }

    @Test
    fun `current directory segments are dropped`() {
        assertEquals("OEBPS/ch1.xhtml", Hrefs.resolve("OEBPS", "./ch1.xhtml"))
        assertEquals("OEBPS/a/b.xhtml", Hrefs.resolve("OEBPS", "a/./b.xhtml"))
        assertEquals("OEBPS/ch1.xhtml", Hrefs.resolve("OEBPS", "a/..//ch1.xhtml"))
    }

    @Test
    fun `an absolute href is taken relative to the zip root`() {
        // zip 엔트리 이름에는 선행 '/' 가 없다.
        assertEquals("OEBPS/ch1.xhtml", Hrefs.resolve("anywhere", "/OEBPS/ch1.xhtml"))
    }

    @Test
    fun `percent escapes are decoded because zip entry names are not encoded`() {
        // 디코딩을 빼먹으면 "이 책만 이미지가 안 나온다"로 증상이 나타난다.
        assertEquals("OEBPS/my image.png", Hrefs.resolve("OEBPS", "my%20image.png"))
        assertEquals("OEBPS/한글 파일.xhtml", Hrefs.resolve("OEBPS", "%ED%95%9C%EA%B8%80%20%ED%8C%8C%EC%9D%BC.xhtml"))
    }

    @Test
    fun `a plus sign survives decoding`() {
        // URLDecoder 를 쓰면 '+' 가 공백이 되어 파일을 못 찾는다. 그건 질의 문자열
        // 규칙이고 경로에는 틀리다.
        assertEquals("OEBPS/C++ 입문.xhtml", Hrefs.resolve("OEBPS", "C++%20%EC%9E%85%EB%AC%B8.xhtml"))
        assertEquals("a+b", Hrefs.decode("a+b"))
    }

    @Test
    fun `malformed escapes are left alone rather than dropped`() {
        assertEquals("100%ZZ", Hrefs.decode("100%ZZ"))
        assertEquals("truncated%A", Hrefs.decode("truncated%A"))
        assertEquals("bare%", Hrefs.decode("bare%"))
    }

    @Test
    fun `fragments are split off the path`() {
        assertEquals("ch1.xhtml", Hrefs.withoutFragment("ch1.xhtml#note3"))
        assertEquals("note3", Hrefs.fragment("ch1.xhtml#note3"))
        assertNull(Hrefs.fragment("ch1.xhtml"))
        assertNull(Hrefs.fragment("ch1.xhtml#"))
        // resolve 는 파일을 여는 쪽이므로 프래그먼트를 뗀다.
        assertEquals("OEBPS/ch1.xhtml", Hrefs.resolve("OEBPS", "ch1.xhtml#note3"))
    }

    @Test
    fun `backslashes are treated as separators`() {
        // 윈도우에서 만든 EPUB 에 있다.
        assertEquals("OEBPS/images/a.png", Hrefs.resolve("OEBPS", "images\\a.png"))
    }

    @Test
    fun `dirOf gives the containing directory`() {
        assertEquals("OEBPS", Hrefs.dirOf("OEBPS/content.opf"))
        assertEquals("OEBPS/text", Hrefs.dirOf("OEBPS/text/ch1.xhtml"))
        assertEquals("", Hrefs.dirOf("content.opf"))
    }
}
