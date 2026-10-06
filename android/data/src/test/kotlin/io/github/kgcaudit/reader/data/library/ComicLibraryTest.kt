package io.github.kgcaudit.reader.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.comic.ArchiveExtensions
import io.github.kgcaudit.reader.document.comic.ComicContents
import io.github.kgcaudit.reader.document.comic.ComicInfo
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.ShelfMark
import io.github.kgcaudit.reader.document.comic.WorkShelf
import io.github.kgcaudit.reader.document.comic.WorkStatuses
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComicLibraryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
    private val comics = ComicLibrary(db)
    private val sd = "content://fake/tree/sd"
    private val phone = "content://fake/tree/phone"

    @After
    fun close() = db.close()

    private fun cbz(name: String, vararg folders: String, size: Long = 100) =
        ScannedComic("content://$name", name, folders.toList(), ComicUnitKind.ARCHIVE, name.substringAfterLast('.'), size, 1L)

    private suspend fun works() = comics.works().first()
    private suspend fun titles() = works().map { it.title }.sorted()

    @Test
    fun `volumes in two registered folders become one work`() = runTest {
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("별 01권.cbz", "Comics", "별"), cbz("별 02권.cbz", "Comics", "별"))), 1)
        comics.applyScan(sd, ScanResult(emptyList(), true, listOf(cbz("별 03권.cbz", "Download"))), 2)
        val star = works().single()
        assertEquals(listOf("1권", "2권", "3권"), star.entries.map { it.label })
        assertEquals(2, star.places.size)
    }

    @Test
    fun `a vanished file is hidden and comes back with its place`() = runTest {
        val one = cbz("별 01권.cbz", "C")
        val two = cbz("별 02권.cbz", "C")
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(one, two)), 1)
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(one)), 2)
        assertEquals(listOf("1권"), works().single().entries.map { it.label })
        // 끝까지 읽지 못한 훑기는 아무것도 숨기지 않는다.
        comics.applyScan(phone, ScanResult(emptyList(), false, emptyList()), 3)
        assertEquals(listOf("1권"), works().single().entries.map { it.label })
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(one, two)), 4)
        assertEquals(listOf("1권", "2권"), works().single().entries.map { it.label })
    }

    @Test
    fun `a plain zip shows only after it is found to hold pictures`() = runTest {
        val photos = cbz("사진 묶음.zip", "C")
        val novel = cbz("소설.zip", "C")
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(photos, novel)), 1)
        assertTrue(works().isEmpty(), "살피기 전의 zip 이 만화로 보였다")
        val todo = comics.needingProbe().associateBy { it.name }
        comics.saveProbe(todo.getValue("사진 묶음.zip"), ComicContents.ofArchive(listOf("1.jpg", "2.jpg"), false), null)
        comics.saveProbe(todo.getValue("소설.zip"), ComicContents.ofArchive(listOf("소설.txt", "표지.jpg"), false), null)
        assertEquals(listOf("사진 묶음"), titles())
        assertTrue(comics.needingProbe().isEmpty(), "살핀 zip 을 또 살피려 한다")
    }

    @Test
    fun `every archive the scanner keeps is probed, and only plain ones wait for the probe to show`() = runTest {
        // 서재의 SQL 은 확장자 목록을 글로 적는다(ComicDao) — ArchiveExtensions 에 더하고 SQL 에 빠뜨리면 그 압축은 영영 살피지
        // 않거나(안 보임) 살피기 전의 사진 묶음이 만화로 보인다.
        val all = ArchiveExtensions.ALL.map { cbz("별 ${it}권.$it", "C") }
        comics.applyScan(phone, ScanResult(emptyList(), true, all), 1)
        assertEquals(ArchiveExtensions.ALL, comics.needingProbe().map { it.extension }.toSet())
        assertEquals(ArchiveExtensions.COMIC, comics.units().first().map { it.name.substringAfterLast('.') }.toSet())
    }

    @Test
    fun `a broken zip volume inside a bundle stays visible like a cbz`() = runTest {
        // 묶음 안의 권은 살피기가 cbz 처럼 믿는데 적을 때만 그냥 zip 으로 쳐서, 깨진 "3권.zip" 이 숨어 그 권만 빠진 채
        // 이어 보기가 끊겼다.
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("별 (1-3).zip", "C"))), 1)
        val outer = comics.needingProbe().single()
        comics.saveVolumes(outer, listOf("별/별 1권.zip" to 10L, "별/별 3권.zip" to 10L), 1)
        comics.saveProbe(outer, null, null, holdsVolumes = true)
        val volumes = comics.needingProbe().associateBy { it.name }
        comics.saveProbe(volumes.getValue("별 1권.zip"), ComicContents.ofArchive(listOf("1.jpg"), true), null)
        comics.saveProbe(volumes.getValue("별 3권.zip"), null, null)
        assertEquals(setOf("별 1권.zip", "별 3권.zip"), comics.units().first().map { it.name }.toSet())
    }

    @Test
    fun `a broken cbz stays visible, and a changed file is probed again`() = runTest {
        val broken = cbz("깨진 01권.cbz", "C")
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(broken)), 1)
        comics.saveProbe(comics.needingProbe().single(), null, null)
        assertEquals(listOf("깨진"), titles())
        // 같은 이름으로 다른 파일을 덮어썼다(크기가 바뀜) → 다시 살필 차례.
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(broken.copy(sizeBytes = 999))), 2)
        assertEquals(1, comics.needingProbe().size)
    }

    @Test
    fun `comic info found while probing names the work`() = runTest {
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("scan_a.cbz", "C"))), 1)
        comics.saveProbe(comics.needingProbe().single(), ComicContents.ofArchive(listOf("1.jpg"), true), ComicInfo(series = "원피스", number = 3.0, rightToLeft = true))
        val work = works().single()
        assertEquals("원피스", work.title)
        assertEquals("3권", work.entries.single().label)
        assertEquals(true, work.rightToLeft)
    }

    @Test
    fun `merge, split, rename and the chosen copy survive a rescan`() = runTest {
        val scanned = listOf(
            cbz("One Piece 01.cbz", "C"), cbz("원피스 02권.cbz", "C"), cbz("원피스 03권.cbz", "C"),
            cbz("원피스_03.cbz", "D", size = 101), cbz("원피스 외전.cbz", "C"),
        )
        comics.applyScan(phone, ScanResult(emptyList(), true, scanned), 1)
        val english = works().single { it.title == "One Piece" }
        val korean = works().single { it.title == "원피스" }
        comics.merge(english, korean)
        comics.rename(works().single(), "원피스(보관용)")
        val third = works().single().entries.single { it.label == "3권" }
        comics.prefer(third, third.copies.single().id)
        comics.split(works().single().entries.single { it.label == "외전" })

        // 다시 훑는다 — 들어오는 순서가 달라도 고친 것이 그대로.
        comics.applyScan(phone, ScanResult(emptyList(), true, scanned.reversed()), 2)
        val after = works()
        val main = after.single { it.title == "원피스(보관용)" }
        assertEquals(listOf("1권", "2권", "3권"), main.entries.map { it.label })
        assertEquals("content://원피스_03.cbz", main.entries.single { it.label == "3권" }.unit.id)
        assertEquals(2, after.size, "뺀 외전이 따로 서지 않았다")

        // 빈 이름으로 고치면 원래 이름으로.
        comics.rename(main, "  ")
        assertTrue(works().any { it.title == "원피스" })
    }

    @Test
    fun `forgetting a folder hides its comics but keeps the merges`() = runTest {
        val a = cbz("A 01권.cbz", "C")
        val b = cbz("B 01권.cbz", "C")
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(a, b)), 1)
        comics.merge(works().single { it.title == "A" }, works().single { it.title == "B" })
        comics.forgetFolder(phone)
        assertTrue(works().isEmpty())
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(a, b)), 2)
        assertEquals(1, works().size, "다시 등록한 폴더에서 합친 것이 풀렸다")
    }

    @Test
    fun `a merged work keeps the name of the work it was merged into`() = runTest {
        // 이름마다 한 권씩 — 가장 많이 나온 이름으로 정하면 동률이라 자연 순서로 "One Piece" 가 이겼다.
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("One Piece 01.cbz", "C"), cbz("원피스 02권.cbz", "C"))), 1)
        comics.merge(works().single { it.title == "One Piece" }, works().single { it.title == "원피스" })
        assertEquals(listOf("원피스"), titles())
    }

    @Test
    fun `reading position is kept per volume and finishing sticks when paging back`() = runTest {
        comics.saveProgress("a", 10, 100, 1)
        assertEquals(10, comics.progressOf("a")?.page)
        assertEquals(null, comics.progressOf("a")?.finishedAtEpochMs)
        comics.saveProgress("a", 99, 100, 2)
        assertEquals(2L, comics.progressOf("a")?.finishedAtEpochMs, "마지막 쪽에 닿았는데 다 읽음이 아니다")
        // 다 읽은 권을 앞으로 들춰 봐도 다 읽음은 남는다.
        comics.saveProgress("a", 3, 100, 3)
        assertEquals(2L, comics.progressOf("a")?.finishedAtEpochMs)
        assertEquals(3, comics.progress().first().getValue("a").page)
        assertEquals(null, comics.progressOf("b"))
    }

    @Test
    fun `a bookmark toggles on and off for one page of one volume`() = runTest {
        assertTrue(comics.toggleBookmark("a", 12, 1))
        assertTrue(comics.toggleBookmark("a", 3, 2))
        assertTrue(comics.toggleBookmark("b", 12, 3))
        assertEquals(listOf(3, 12), comics.bookmarks("a").first())
        assertEquals(false, comics.toggleBookmark("a", 12, 4))
        assertEquals(listOf(3), comics.bookmarks("a").first())
        assertEquals(listOf(12), comics.bookmarks("b").first(), "다른 권의 책갈피가 함께 빠졌다")
    }

    @Test
    fun `the direction chosen for a work holds for its other volumes, and a broken value is ignored`() = runTest {
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("별 01권.cbz", "C"), cbz("별 02권.cbz", "C"))), 1)
        assertEquals(null, works().single().rightToLeft)
        comics.setRightToLeft(works().single(), true)
        assertEquals(true, works().single().rightToLeft)
        comics.setRightToLeft(works().single(), false)
        assertEquals(false, works().single().rightToLeft, "왼→오로 되돌린 것이 \"정하지 않음\" 과 섞였다")
        db.comics().setOverride(io.github.kgcaudit.reader.data.db.ComicOverrideEntity(ComicLibrary.RTL, works().single().key, "yes"))
        assertEquals(null, works().single().rightToLeft)
    }

    @Test
    fun `a webtoon is finished only when the reader says the end was reached, and the view choice can be cleared`() = runTest {
        comics.saveProgress("w", 3, 4, 1, offset = 0.1f, atEnd = false)
        assertEquals(null, comics.progressOf("w")?.finishedAtEpochMs, "마지막 그림 머리만 보였는데 다 읽음이 됐다")
        comics.saveProgress("w", 3, 4, 2, offset = 1f, atEnd = true)
        assertEquals(2L, comics.progressOf("w")?.finishedAtEpochMs)
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("전학생 001화.cbz", "C"), cbz("전학생 002화.cbz", "C"))), 1)
        assertEquals(null, works().single().view)
        comics.setView(works().single(), io.github.kgcaudit.reader.document.comic.ComicView.PAGE)
        assertEquals(io.github.kgcaudit.reader.document.comic.ComicView.PAGE, works().single().view)
        comics.setView(works().single(), null)
        assertEquals(null, works().single().view, "자동으로 되돌리지 못했다")
    }

    @Test
    fun `a work moved to finished stays there across rescans until a new volume arrives`() = runTest {
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("별 01권.cbz", "C"), cbz("별 02권.cbz", "C"))), 1)
        comics.setShelf(works().single(), ShelfMark.Finished(5, 2))
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("별 01권.cbz", "C"), cbz("별 02권.cbz", "C"))), 6)
        assertEquals(WorkShelf.FINISHED, WorkStatuses.of(works().single(), emptyMap()).shelf, "다시 훑었더니 옮긴 갈래를 잃었다")
        // 3권이 들어왔다(훑은 때 7) → 읽는 중 + 새 권.
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("별 01권.cbz", "C"), cbz("별 02권.cbz", "C"), cbz("별 03권.cbz", "C"))), 7)
        val status = WorkStatuses.of(works().single(), emptyMap())
        assertEquals(WorkShelf.READING, status.shelf)
        assertTrue(status.newVolumes)
        assertEquals(7L, works().single().entries.last().unit.addedAtEpochMs, "들어온 때가 단위에 실리지 않았다")
        comics.setShelf(works().single(), null)
        assertEquals(null, works().single().shelfMark)
    }

    @Test
    fun `a broken shelf mark is ignored and the work follows its volumes`() = runTest {
        // 망가뜨린 입력: 다음 판이 쓴 모르는 표시 · 잘린 표시. 작품이 엉뚱한 칸에 붙박이지 않는다.
        comics.applyScan(phone, ScanResult(emptyList(), true, listOf(cbz("별 01권.cbz", "C"))), 1)
        for (bad in listOf("LATER:5", "DONE:", "DONE:5:x")) {
            db.comics().setOverride(io.github.kgcaudit.reader.data.db.ComicOverrideEntity(ComicLibrary.SHELF, works().single().key, bad))
            assertEquals(null, works().single().shelfMark, bad)
            assertEquals(WorkShelf.TO_READ, WorkStatuses.of(works().single(), emptyMap()).shelf)
        }
    }
}
