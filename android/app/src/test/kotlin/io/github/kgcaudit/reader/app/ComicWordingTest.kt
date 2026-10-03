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
}
