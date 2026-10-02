package io.github.kgcaudit.reader.document.comic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicNameTest {

    private fun p(name: String) = ComicName.parse(name)

    @Test
    fun `a volume file gives the series and the volume`() {
        val n = p("별을 줍는 아이 01권.cbz")
        assertEquals("별을 줍는 아이", n.series)
        assertEquals(1.0, n.volume)
        assertNull(n.chapter)
    }

    @Test
    fun `tags around the name are not part of the series`() {
        // [작가] · (완) · 연도 · 해상도 · 그룹 꼬리표를 지워야 같은 작품의 다른 파일과 이름 열쇠가 맞는다.
        val n = p("[오다 에이치로] 원피스 02권 (완) [1080x] (Digital).cbz")
        assertEquals("원피스", n.series)
        assertEquals(2.0, n.volume)
        assertTrue(n.complete, "완결 표시를 놓쳤다")
        assertEquals(ComicName.key(p("원피스 01.cbz").series), ComicName.key(n.series))
    }

    @Test
    fun `numbers in the title stay in the title`() {
        assertEquals("20세기 소년", p("20세기 소년 01권.cbz").series)
        assertEquals("3월의 라이온", p("3월의 라이온 5.zip").series)
        assertEquals(5.0, p("3월의 라이온 5.zip").volume)
        // 연도는 번호가 아니다.
        val y = p("1984.cbz")
        assertEquals("1984", y.series)
        assertNull(y.volume)
        assertNull(p("Title (2019).cbz").volume)
    }

    @Test
    fun `half volumes sort between their neighbours`() {
        assertEquals(12.5, p("몰루 아카이브 12.5권.cbz").volume)
        assertEquals(7.5, p("몰루 아카이브 7.5권").volume)
        // 확장자 없이 끝나는 소수 번호: ".5" 를 확장자로 떼면 12권이 된다.
        assertEquals(12.5, p("작품 12.5").volume)
    }

    @Test
    fun `a range is an omnibus, not its last volume`() {
        for (name in listOf("별을 줍는 아이 4-6권 합본.zip", "별을 줍는 아이 4~6권.zip")) {
            val n = p(name)
            assertEquals(4.0, n.volume, name)
            assertEquals(6.0, n.volumeEnd, name)
            assertTrue(n.isRange, name)
            assertEquals("별을 줍는 아이", n.series, name)
        }
        assertEquals(3.0, p("Title v01-03.cbz").volumeEnd)
        assertEquals(50.0, p("전학생 001-050화.zip").chapterEnd)
    }

    @Test
    fun `chapters, parts and english markers are read`() {
        assertEquals(17.0, p("전학생 17화").chapter)
        assertEquals(3.0, p("전학생 제3회").chapter)
        assertEquals(2, p("나 혼자 2부 15화.cbz").part)
        assertEquals(15.0, p("나 혼자 2부 15화.cbz").chapter)
        assertEquals("나 혼자", p("나 혼자 2부 15화.cbz").series)
        assertEquals(2, p("작품 시즌2 3화").part)
        assertEquals(4.0, p("Title Vol.4.cbz").volume)
        assertEquals(12.0, p("Title ch.12.cbz").chapter)
        assertEquals(3.0, p("タイトル 3巻.zip").volume)
    }

    @Test
    fun `specials are marked and zero is a special, not a missing number`() {
        assertTrue(p("별을 줍는 아이 외전.cbz").special)
        assertNull(p("별을 줍는 아이 외전.cbz").volume)
        assertTrue(p("Title SP01.cbz").special)
        val zero = p("별을 줍는 아이 0권.cbz")
        assertTrue(zero.special)
        assertEquals(0.0, zero.volume, "0권을 번호 없음으로 뭉갰다")
        assertFalse(p("별을 줍는 아이 1권.cbz").special)
    }

    @Test
    fun `a bare number with nothing before it has no series`() {
        val n = p("001화")
        assertEquals("", n.series)
        assertEquals(1.0, n.chapter)
        val bare = ComicName.parse("012", hasExtension = false)
        assertTrue(bare.bareNumber)
        assertEquals(12.0, bare.volume)
    }

    @Test
    fun `the name key ignores spacing, case, symbols and full-width letters`() {
        assertEquals(ComicName.key("별을 줍는 아이"), ComicName.key("별을줍는아이"))
        assertEquals(ComicName.key("One Piece"), ComicName.key("ONE_PIECE".replace('_', ' ')))
        assertEquals(ComicName.key("ＯＮＥ ＰＩＥＣＥ"), ComicName.key("one piece"))
        // 다른 작품은 다른 열쇠: "원피스 필름" 은 "원피스" 가 아니다.
        assertFalse(ComicName.key("원피스") == ComicName.key("원피스 필름"))
    }

    @Test
    fun `broken and strange names never throw and keep something to show`() {
        for (name in listOf("", ".", ".cbz", "[]", "[작가]", "((((", "]]]", "---", "#", "1-", "권", "화화화", "\u0000\u0007",
            "a".repeat(5000), "999999999999999999999999권", "[[[[[[[a]", "(완)", "v", "SP", "1.2.3.4.5.cbz")) {
            val n = p(name)
            // 보일 이름이 남아 있어야 한다 — 빈 칸이 목록에 생기면 무엇인지 모른다.
            assertTrue(n.cleaned.isNotEmpty() || name.isBlank() || name == ".cbz", "'$name' 의 보일 이름이 비었다")
        }
        // 꼬리표만 있는 이름은 꼬리표가 곧 이름이다.
        assertTrue("작가" in p("[작가].cbz").cleaned, "꼬리표만 있는 이름을 통째로 지웠다")
    }
}
