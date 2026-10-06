package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.document.comic.ComicShelf
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import org.junit.Test
import kotlin.test.assertEquals

/** 세는 말(0.43.0): 웹툰에 "같은 권 2곳" · "마지막 권" 이 나왔다. 만화는 권, 웹툰은 화. */
class ComicWordingTest {

    private fun folder(id: String, name: String, vararg path: String) = ComicUnit(id, name, path.toList(), ComicUnitKind.IMAGE_FOLDER)
    private fun archive(id: String, name: String, vararg path: String) = ComicUnit(id, name, path.toList(), ComicUnitKind.ARCHIVE)

    @Test
    fun `a webtoon counts in episodes and a comic in volumes`() {
        val webtoon = ComicShelf.group(listOf(folder("a", "001화", "W", "전학생"), folder("b", "002화", "W", "전학생"))).single()
        val comic = ComicShelf.group(listOf(archive("c", "별 01권.cbz", "C"), archive("d", "별 02권.cbz", "C"))).single()
        assertEquals("화", unitWord(webtoon))
        assertEquals("화", unitWord(webtoon.entries.first()))
        assertEquals("권", unitWord(comic))
        assertEquals("권", unitWord(comic.entries.first()))
        // 작품을 모르면(묶는 중) 권 — 만화가 더 흔하다.
        assertEquals("권", unitWord(null as io.github.kgcaudit.reader.document.comic.Work?))
    }

    @Test
    fun `a copy of an episode found elsewhere is labelled as the same episode`() {
        val units = listOf(folder("a", "001화", "W", "전학생"), folder("b", "002화", "W", "전학생"), folder("c", "001화", "D", "전학생"))
        val work = ComicShelf.group(units).single()
        val other = work.places.first { it != work.entries.first().unit.place }
        assertEquals(true, placeSummary(work, other).contains("(같은 화)"), placeSummary(work, other))
    }

    @Test
    fun `the end card names the last line by its own unit`() {
        // 권과 화가 섞인 작품(단행본 두 권 뒤에 화 하나)은 웹툰이 아니라 작품의 세는 말이 "권" 이다. 그래도 마지막 줄이
        // "3화" 면 끝 판은 "마지막 화입니다" — 작품의 말만 보던 때는 "마지막 권입니다" 가 떴다.
        val mixed = ComicShelf.group(
            listOf(archive("a", "별 01권.cbz", "C"), archive("b", "별 02권.cbz", "C"), archive("c", "별 03화.cbz", "C")),
        ).single()
        assertEquals(false, mixed.webtoon)
        val last = mixed.entries.last().label
        assertEquals("화", endUnit(last, mixed), "마지막 줄 '$last'")
        assertEquals("권", endUnit("2권", mixed))
        // 이름으로 알 수 없으면 작품의 말.
        val webtoon = ComicShelf.group(listOf(folder("w1", "001화", "W", "전학생"), folder("w2", "002화", "W", "전학생"))).single()
        assertEquals("화", endUnit("외전", webtoon))
        assertEquals("권", endUnit("외전", mixed))
        assertEquals("권", endUnit("외전", null))
    }
}
