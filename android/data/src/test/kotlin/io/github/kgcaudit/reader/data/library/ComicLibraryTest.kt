package io.github.kgcaudit.reader.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.comic.ComicContents
import io.github.kgcaudit.reader.document.comic.ComicInfo
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
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
}
