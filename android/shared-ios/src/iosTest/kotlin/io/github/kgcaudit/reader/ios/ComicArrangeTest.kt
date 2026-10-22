package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.ComicShelf
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.Work
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 작품 정리(구상안 40): 합치기 · 빼기 · 이름 고치기 · 같은 권 고르기는 앱 기록에 남고, 다시 묶어도 그대로다. */
class ComicArrangeTest {
    private fun unit(id: String, name: String, vararg folders: String) =
        ComicUnit(id, name, folders.toList(), ComicUnitKind.ARCHIVE, 1000L, addedAtEpochMs = 0L)

    private val units = listOf(
        unit("a1", "One Piece 01.cbz", "만화"),
        unit("b2", "원피스 02.cbz", "만화"),
        unit("c3", "원피스 03.cbz", "만화"),
        unit("c3b", "원피스 03.cbz", "만화", "옛 백업"),
    )

    private fun store() = ReadingStore(NSTemporaryDirectory() + "olo-arrange-" + NSUUID().UUIDString + ".tsv")
    private fun works(s: ReadingStore): List<Work> = ComicShelf.group(units, s.comicOverrides())
    private fun named(s: ReadingStore, title: String) = works(s).single { it.title == title }

    @Test
    fun `merging moves every volume and keeps the chosen name`() {
        val s = store()
        assertEquals(2, works(s).size, "two names, two works")
        val one = works(s).single { it.entries.any { e -> e.unit.id == "a1" } }
        val other = works(s).single { it.key != one.key }
        s.mergeWork(one, other)
        val merged = works(s).single()
        assertEquals(other.title, merged.title)
        assertEquals(3, merged.entries.size)
    }

    @Test
    fun `merging the bigger work into the smaller one takes the smaller one's name and its copies too`() {
        val s = store()
        val big = works(s).single { it.entries.size == 2 }
        val small = works(s).single { it.key != big.key }
        // 큰 쪽을 작은 쪽에 합친다: 이름을 적지 않으면 많이 나온 "원피스" 가 이겨 고른 이름이 사라진다.
        s.mergeWork(big, small)
        val merged = works(s).single()
        assertEquals(small.title, merged.title)
        assertTrue(merged.entries.any { e -> e.copies.any { it.id == "c3b" } || e.unit.id == "c3b" }, "the copy came along — no stray work")
    }

    @Test
    fun `a split volume becomes its own work with its own name`() {
        val s = store()
        val w = works(s).single { it.entries.size == 2 }
        s.splitEntry(w.entries.first { it.unit.id == "b2" })
        val all = works(s)
        assertEquals(3, all.size)
        assertTrue(all.any { it.entries.single().unit.id == "b2" && it.title.isNotBlank() })
    }

    @Test
    fun `a renamed work keeps the name and an empty name brings the original back`() {
        val s = store()
        val w = works(s).single { it.entries.size == 2 }
        val original = w.title
        s.renameWork(w, "  바다의 해적  ")
        assertEquals("바다의 해적", works(s).single { it.key == w.key }.title, "trimmed")
        s.renameWork(w, "   ")
        assertEquals(original, works(s).single { it.key == w.key }.title)
        assertTrue(s.workRecords().none { it.key == w.key }, "an empty name leaves nothing behind to back up")
    }

    @Test
    fun `choosing a copy reads that file and the other one hides`() {
        val s = store()
        val w = works(s).single { it.entries.size == 2 }
        val third = w.entries.single { it.copies.isNotEmpty() }
        val before = third.unit.id
        val pick = IosComicArrange.copies(third).first { it.id != before }
        s.preferCopy(third, pick.id)
        val after = works(s).single { it.key == w.key }.entries.single { it.slot == third.slot }
        assertEquals(pick.id, after.unit.id)
        assertEquals(listOf(before), after.copies.map { it.id })
    }

    @Test
    fun `each folder shows what it holds and copies are marked`() {
        val s = store()
        val w = works(s).single { it.entries.size == 2 }
        val places = w.places
        assertTrue(places.size == 2, "$places")
        // 3권은 두 곳에 있다: 읽는 파일이 든 곳은 "3권", 다른 곳은 "3권(같은 권)".
        val third = w.entries.single { it.copies.isNotEmpty() }
        val other = places.single { it != third.unit.place }
        assertTrue(IosComicArrange.placeSummary(w, other).contains("3권(같은 권)"), IosComicArrange.placeSummary(w, other))
        assertTrue(!IosComicArrange.placeSummary(w, third.unit.place).contains("같은 권"), IosComicArrange.placeSummary(w, third.unit.place))
        assertEquals("권", IosComicArrange.unitWord(w))
        assertTrue(IosComicArrange.others(w, works(s)).none { it.key == w.key })
    }

    @Test
    fun `merges splits and chosen copies stay on this phone and are not backed up`() {
        val s = store()
        val all = works(s)
        s.mergeWork(all[0], all[1])
        val merged = works(s).single()
        s.splitEntry(merged.entries.first())
        s.preferCopy(merged.entries.single { it.copies.isNotEmpty() }, "c3b")
        s.renameWork(works(s).first { !it.key.startsWith("#own:") }, "새 이름")
        val records = s.workRecords()
        assertTrue(records.none { it.key.startsWith("#own:") }, "a split work's key is this phone's file id")
        assertTrue(records.all { it.title != null || it.rightToLeft != null })
        assertTrue(records.any { it.title == "새 이름" }, "a renamed title is carried")
    }
}
