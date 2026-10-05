package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.archive.StoredArchives
import io.github.kgcaudit.reader.document.comic.NestedArchives
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 압축 속 압축(0.48.0): 권별 zip 을 다시 zip 하나로 묶은 작품("[김민혜] 인스타 걸 (1-3, 완결).zip" → 폴더 → 권 zip)이 서재에
 * 권으로 펼쳐 보이고 열린다. 예전에는 바깥 zip 에 그림이 바로 없어 "만화 아님" 으로 적혀 통째로 빠졌다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class NestedComicAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val red = 0xFFD03030.toInt()
    private val blue = 0xFF3050D0.toInt()
    private val green = 0xFF30A050.toInt()
    private lateinit var root: File
    private val insta = "[김민혜] 인스타 걸 (1-3, 완결)"

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun pages(color: Int) = listOf("001.png" to png(color), "002.png" to png(color))

    @Before
    fun setUp() {
        root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "Comic").mkdirs()
        // 압축해 담은 바깥 zip(대부분의 압축 프로그램 기본값): 권을 꺼내서 연다.
        File(root, "Comic/$insta.zip").writeBytes(
            zip(
                listOf(
                    "$insta/인스타 걸 1권.zip" to zip(pages(red), stored = false),
                    "$insta/인스타 걸 2권.zip" to zip(pages(blue), stored = false),
                    "$insta/인스타 걸 3권 (완결).zip" to zip(pages(green), stored = false),
                ),
                stored = false,
            ),
        )
        // 압축 없이 담은 바깥 zip: zip 은 그 자리에서, cbr 은 꺼내서. 깨진 권 하나가 섞였다.
        File(root, "Comic/별 묶음.zip").writeBytes(
            zip(
                listOf(
                    "별 1권.cbz" to zip(pages(red), stored = true),
                    "별 2권.cbr" to StoredArchives.rar4(pages(blue)),
                    "별 3권.zip" to ByteArray(4000) { 7 },
                ),
                stored = true,
            ),
        )
        scan()
        compose.activityRule.scenario.recreate()
    }

    private fun scan() = runBlocking {
        val data = app.container.data
        data.folders.register(FolderProvider.treeUri)
        data.rescanAll()
        data.probeComics()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        node(matcher).performClick()
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun near(pixel: Int, color: Int): Boolean {
        fun d(c: Int) = listOf(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue).sumOf { f -> (f(pixel) - f(c)).let { it * it } }
        return d(color) < 40 * 40 * 3
    }

    private fun waitForColor(color: Int, message: String) {
        val ok = runCatching {
            compose.waitUntil(30_000) { screen().let { near(it.getPixel(it.width / 2, it.height / 2), color) } }
        }.isSuccess
        assertTrue(ok, message)
    }

    private fun open(work: String, label: String) {
        click(hasText("만화 2"))
        click(hasContentDescription("$work 작품"))
        click(hasText(label))
        waitFor(hasContentDescription("만화 1쪽"))
    }

    private fun labels(work: String): List<String> = runBlocking {
        app.container.data.comics.works().first().first { it.title == work }.entries.map { it.label }
    }

    @Test
    fun `volumes packed inside one archive show up as the volumes of one work`() {
        assertEquals(listOf("1권", "2권", "3권"), labels("인스타 걸"))
        val work = runBlocking { app.container.data.comics.works().first().first { it.title == "인스타 걸" } }
        assertTrue(work.complete, "3권 (완결) 이 있는데 완결로 보이지 않는다")
        assertEquals(listOf("Books › Comic › $insta.zip"), work.places)
        // 깨진 권 하나는 그 권만 빠진다 — 묶음 전체가 사라지지 않는다(규칙 6).
        assertEquals(listOf("1권", "2권"), labels("별"))
    }

    @Test
    fun `a volume packed with compression is copied out and opens`() {
        open("인스타 걸", "2권")
        waitForColor(blue, "압축해 담은 2권이 그려지지 않았다")
    }

    @Test
    fun `a stored volume opens in place and a rar volume inside a zip opens too`() {
        open("별", "1권")
        waitForColor(red, "압축 없이 담은 1권이 그려지지 않았다")
        // 그 자리에서 읽었다 — 꺼낸 사본이 없다. 꺼내면 열 때마다 몇십 MB 를 옮기느라 늦게 열린다.
        val copies = File(app.cacheDir, "comic-unpacked").walkTopDown().filter { it.name.startsWith(".olo-volume.") }.toList()
        assertEquals(emptyList(), copies)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        click(hasText("2권"))
        waitFor(hasContentDescription("만화 1쪽"))
        waitForColor(blue, "zip 속 cbr 2권이 그려지지 않았다")
    }

    @Test
    fun `a refresh keeps the volumes and removing the outer archive hides them until it returns`() {
        // 압축 속 권은 훑기가 보지 못하는 파일이다 — 새로고침에서 "없어진 파일" 로 치면 서재에서 사라졌다.
        scan()
        assertEquals(listOf("1권", "2권", "3권"), labels("인스타 걸"))
        val outer = File(root, "Comic/$insta.zip")
        val kept = File(app.cacheDir, "kept.zip").also { outer.copyTo(it, overwrite = true); it.setLastModified(outer.lastModified()) }
        outer.delete()
        scan()
        assertEquals(emptyList(), runBlocking { app.container.data.comics.works().first().filter { it.title == "인스타 걸" } })
        // 같은 파일이 그대로(크기 · 수정 시각) 돌아왔다 — 바깥은 "바뀌지 않음" 이라 다시 살피지 않으면 안의 권이 숨은 채 남았다.
        kept.copyTo(outer)
        outer.setLastModified(kept.lastModified())
        scan()
        assertEquals(listOf("1권", "2권", "3권"), labels("인스타 걸"))
    }

    @Test
    fun `an archive an older version called not a comic is looked at again after the update`() {
        // 0.47 까지의 휴대폰 상태: 바깥 zip 은 "만화 아님" 으로 살펴져 있고 안의 권은 없다. 파일은 그대로라 크기 · 수정 시각으로는
        // 다시 살필 까닭이 없다 — 0.48.0 을 깔고도 서재에 나오지 않았다(사용자 보고).
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("reader.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DELETE FROM comic_units WHERE kind = 'NESTED'")
            db.execSQL("UPDATE comic_units SET notComic = 1 WHERE name = ?", arrayOf<Any>("$insta.zip"))
        }
        File(app.noBackupFilesDir, "comic-probe-generation").delete()
        assertEquals(emptyList(), runBlocking { app.container.data.comics.works().first().filter { it.title == "인스타 걸" } })
        scan()
        assertEquals(listOf("1권", "2권", "3권"), labels("인스타 걸"))
        // 한 번만: 표시를 남겨, 정말 만화가 아닌 zip 을 열 때마다 다시 살피지 않는다.
        assertEquals("2", File(app.noBackupFilesDir, "comic-probe-generation").readText())
    }

    @Test
    fun `a volume taken out of the archive leaves the shelf`() {
        File(root, "Comic/별 묶음.zip").writeBytes(zip(listOf("별 1권.cbz" to zip(pages(red), stored = true)), stored = true))
        scan()
        assertEquals(listOf("1권"), labels("별"))
    }

    @Test
    fun `reading progress is kept per volume inside the archive`() {
        val units = runBlocking { app.container.data.comics.works().first().first { it.title == "인스타 걸" }.entries.map { it.unit } }
        assertTrue(units.all { NestedArchives.split(it.id) != null })
        assertEquals(3, units.map { it.id }.distinct().size)
    }

    @Test
    fun `the opening notice reads naturally`() {
        assertEquals("1권을 여는 중…", openingNotice("1권"))
        assertEquals("3화를 여는 중…", openingNotice("3화"))
        assertEquals("외전을 여는 중…", openingNotice("외전"))
        assertEquals("Extra 2를 여는 중…", openingNotice("Extra 2"))
        assertEquals("Part 9를 여는 중…", openingNotice("Part 9"))
        assertEquals("Part 1을 여는 중…", openingNotice("Part 1"))
        assertEquals("여는 중…", openingNotice(null))
    }
}
