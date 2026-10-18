package io.github.kgcaudit.reader.document.comic

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComicInfoTest {

    private fun parse(xml: String) = ComicInfo.parse(StringReader(xml))

    @Test
    fun `series, number and reading direction are read`() {
        val info = parse(
            """<?xml version="1.0"?>
            <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <Series>별을 줍는 아이</Series><Number>3</Number><Volume>2016</Volume>
              <Manga>YesAndRightToLeft</Manga><Format>Special</Format>
              <Pages><Page Image="0" Type="FrontCover"/><Page Image="1"><Series>안쪽</Series></Page></Pages>
            </ComicInfo>""",
        )!!
        assertEquals("별을 줍는 아이", info.series)
        assertEquals(3.0, info.number)
        // Volume 은 판본(연도)이다 — 권으로 쓰지 않으려고 따로 둔다.
        assertEquals(2016, info.volume)
        assertEquals(true, info.rightToLeft)
        assertTrue(info.isSpecial)
    }

    @Test
    fun `manga yes alone leaves the direction undecided`() {
        assertNull(parse("<ComicInfo><Series>a</Series><Manga>Yes</Manga></ComicInfo>")!!.rightToLeft)
        assertEquals(false, parse("<ComicInfo><Series>a</Series><Manga>No</Manga></ComicInfo>")!!.rightToLeft)
    }

    @Test
    fun `a broken file keeps what was read before it broke`() {
        val info = parse("<ComicInfo><Series>원피스</Series><Number>12.5</Number><Title>잘린")
        assertEquals("원피스", info?.series)
        assertEquals(12.5, info?.number)
    }

    @Test
    fun `nothing useful gives null instead of an empty record`() {
        assertNull(parse(""))
        assertNull(parse("<ComicInfo></ComicInfo>"))
        assertNull(parse("<ComicInfo><Series>   </Series></ComicInfo>"))
        assertNull(parse("이것은 XML 이 아니다 <<<"))
        // 숫자가 아닌 번호는 버리고 나머지는 남긴다.
        val odd = parse("<ComicInfo><Series>a</Series><Number>셋</Number></ComicInfo>")!!
        assertNull(odd.number)
        assertEquals("a", odd.series)
    }
}
