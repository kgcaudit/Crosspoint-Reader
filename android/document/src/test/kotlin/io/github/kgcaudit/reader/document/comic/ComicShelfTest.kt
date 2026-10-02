package io.github.kgcaudit.reader.document.comic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComicShelfTest {

    private fun archive(id: String, name: String, vararg folders: String, info: ComicInfo? = null, contents: ComicContents? = null) =
        ComicUnit(id, name, folders.toList(), ComicUnitKind.ARCHIVE, info = info, contents = contents)

    private fun imageFolder(id: String, name: String, vararg folders: String) =
        ComicUnit(id, name, folders.toList(), ComicUnitKind.IMAGE_FOLDER)

    /** 구상안 도해(모으는 법)와 같은 폴더 모양. */
    private val scattered = listOf(
        archive("a1", "별을 줍는 아이 01권.cbz", "Comics", "별을 줍는 아이"),
        archive("a2", "별을 줍는 아이 02권.cbz", "Comics", "별을 줍는 아이"),
        archive("a46", "별을 줍는 아이 4-6권 합본.zip", "Comics", "별을 줍는 아이"),
        archive("ax", "별을 줍는 아이 외전.cbz", "Comics", "별을 줍는 아이"),
        archive("b7", "[작가] 별을 줍는 아이 07권 (완).cbz", "Download"),
        archive("b3", "별을 줍는 아이 03권.cbz", "Download"),
        archive("b3copy", "별을줍는아이_03.cbz", "Download"),
        archive("sea", "바다의 노래 1.cbz", "Download"),
        imageFolder("w2", "002화", "Webtoon", "전학생"),
        imageFolder("w1", "001화", "Webtoon", "전학생"),
    )

    @Test
    fun `one series scattered across folders becomes one work`() {
        val works = ComicShelf.group(scattered)
        val star = works.single { it.title == "별을 줍는 아이" }
        assertEquals(listOf("1권", "2권", "3권", "4–6권", "7권", "외전"), star.entries.map { it.label })
        assertEquals(listOf("Comics › 별을 줍는 아이", "Download"), star.places.sorted())
        assertTrue(star.complete, "7권의 (완) 을 놓쳤다")
        assertFalse(star.webtoon)
        assertEquals(setOf("별을 줍는 아이", "바다의 노래", "전학생"), works.map { it.title }.toSet())
    }

    @Test
    fun `the same volume in two places shows once with its copies`() {
        val star = ComicShelf.group(scattered).single { it.title == "별을 줍는 아이" }
        val third = star.entries.single { it.label == "3권" }
        assertEquals(1, third.copies.size, "같은 권이 따로 두 줄로 나왔거나 사본을 잃었다")
        assertEquals(setOf("b3", "b3copy"), (third.copies + third.unit).map { it.id }.toSet())
    }

    @Test
    fun `the chosen copy is the one read, and survives regrouping`() {
        val first = ComicShelf.group(scattered).single { it.title == "별을 줍는 아이" }.entries.single { it.label == "3권" }
        val other = first.copies.single().id
        val again = ComicShelf.group(scattered.reversed(), ComicOverrides(preferred = mapOf(first.slot to other)))
            .single { it.title == "별을 줍는 아이" }.entries.single { it.label == "3권" }
        assertEquals(other, again.unit.id, "고른 파일이 다시 묶을 때 풀렸다")
    }

    @Test
    fun `episode folders are chapters of the webtoon named by their folder`() {
        val webtoon = ComicShelf.group(scattered).single { it.title == "전학생" }
        assertTrue(webtoon.webtoon)
        assertEquals(listOf("1화", "2화"), webtoon.entries.map { it.label })
        // 숫자뿐인 화 폴더도 화다 — 권으로 읽으면 화 마흔여덟 개가 1~48권이 된다.
        val bare = ComicShelf.group(listOf(imageFolder("x", "012", "Webtoon", "전학생"), imageFolder("y", "013", "Webtoon", "전학생"))).single()
        assertTrue(bare.webtoon)
        assertEquals(listOf("12화", "13화"), bare.entries.map { it.label })
    }

    @Test
    fun `volumes sort by value with halves in between and specials last`() {
        val units = listOf("작품 10권", "작품 외전", "작품 2권", "작품 12.5권", "작품 13권", "작품 0권", "작품 1권")
            .mapIndexed { i, n -> archive("$i", "$n.cbz", "C") }
        val labels = ComicShelf.group(units).single().entries.map { it.label }
        assertEquals(listOf("1권", "2권", "10권", "12.5권", "13권", "0권", "외전"), labels)
    }

    @Test
    fun `an omnibus with inner volumes lists them under itself`() {
        val contents = ComicContents.ofArchive(listOf("w/4권/1.jpg", "w/5권/1.jpg", "w/6권/1.jpg"), true)
        val work = ComicShelf.group(listOf(archive("o", "별 합본.cbz", "C", contents = contents))).single()
        val entry = work.entries.single()
        assertTrue(entry.omnibus)
        assertEquals(listOf("4권", "5권", "6권"), entry.sections)
    }

    @Test
    fun `comic info names the series and the volume even when the file name says neither`() {
        val units = listOf(
            archive("1", "scan_final.cbz", "C", info = ComicInfo(series = "원피스", number = 1.0)),
            archive("2", "원피스 02권.cbz", "D"),
        )
        val work = ComicShelf.group(units).single()
        assertEquals("원피스", work.title)
        assertEquals(listOf("1권", "2권"), work.entries.map { it.label })
    }

    @Test
    fun `a merge and a rename by hand outlive a rescan`() {
        val units = listOf(archive("x", "One Piece 01.cbz", "C"), archive("y", "원피스 02권.cbz", "C"))
        assertEquals(2, ComicShelf.group(units).size, "이름이 다른 두 작품이 저절로 합쳐졌다")
        val merged = ComicOverrides(workOf = mapOf("x" to ComicName.key("원피스")), titles = mapOf(ComicName.key("원피스") to "원피스(해적판 아님)"))
        val once = ComicShelf.group(units, merged)
        assertEquals(1, once.size)
        assertEquals("원피스(해적판 아님)", once.single().title)
        // 다시 훑으면 순서가 바뀌어 들어와도 같은 결과.
        assertEquals(once, ComicShelf.group(units.reversed(), merged))
    }

    @Test
    fun `a volume taken out by hand becomes its own work`() {
        val units = listOf(archive("x", "원피스 01권.cbz", "C"), archive("y", "원피스 02권.cbz", "C"))
        val split = ComicShelf.group(units, ComicOverrides(workOf = mapOf("y" to "y-own")))
        assertEquals(2, split.size)
    }

    @Test
    fun `nameless files fall back to their folder and never vanish`() {
        val units = listOf(
            archive("1", "01.cbz", "Comics", "고양이 탐정"),
            archive("2", "02.cbz", "Comics", "고양이 탐정"),
            archive("3", "[].cbz", "Comics"),
            archive("4", ".cbz"),
        )
        val works = ComicShelf.group(units)
        assertEquals(listOf("1권", "2권"), works.single { it.title == "고양이 탐정" }.entries.map { it.label })
        // 이름을 하나도 얻지 못한 단위도 어딘가에는 보인다(규칙 6).
        assertEquals(4, works.sumOf { it.entries.size + it.entries.sumOf { e -> e.copies.size } })
    }

    @Test
    fun `different formats of names for one series share a key but different series do not merge`() {
        val units = listOf(archive("1", "원피스 01.cbz", "C"), archive("2", "원피스 필름 01.cbz", "C"))
        assertEquals(2, ComicShelf.group(units).size)
    }
}
