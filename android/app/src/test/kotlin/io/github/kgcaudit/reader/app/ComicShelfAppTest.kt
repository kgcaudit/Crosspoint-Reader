package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 서재의 만화 탭 · 작품 화면 · 작품 정리(0.33.0, docs/LIBRARY_COMIC_PLAN.md 확정 구상안).
 *
 * 가짜 저장소에 진짜 cbz(그림 든 zip)와 그림 폴더를 넣고, 앱과 같은 길(훑기 → 살피기)로 서재를 채운다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ComicShelfAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    /** 단색 쪽 그림. 표지가 대신 표지가 아니라 그림으로 나오는지 색으로 가린다. */
    private fun page(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun cbz(file: File, vararg pages: String, color: Int = 0xFF2E7D6B.toInt()) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in pages) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(page(color))
                zip.closeEntry()
            }
        }
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        val star = File(root, "Comics/별을 줍는 아이")
        cbz(File(star, "별을 줍는 아이 01권.cbz"), "001.png", "002.png", "003.png")
        cbz(File(star, "별을 줍는 아이 02권.cbz"), "001.png", "002.png")
        // 합본: 안에 권 폴더 셋. 줄표(–)로 붙인 이름 — 맥 · 워드가 붙임표 대신 넣는다.
        cbz(File(star, "별을 줍는 아이 4–6권 합본.cbz"), "4권/1.png", "4권/2.png", "5권/1.png", "5권/2.png", "6권/1.png")
        cbz(File(star, "별을 줍는 아이 외전.cbz"), "1.png")
        // 같은 권이 다른 폴더에 둘(크기가 달라 판본이 다르다).
        cbz(File(root, "Download/별을 줍는 아이 03권.cbz"), "1.png")
        cbz(File(root, "Download/별을줍는아이_03.cbz"), "1.png", "2.png")
        cbz(File(root, "Download/One Piece 01.cbz"), "1.png")
        cbz(File(root, "Download/원피스 02권.cbz"), "1.png")
        for (ep in listOf("001화", "002화")) {
            val dir = File(root, "Webtoon/전학생/$ep").apply { mkdirs() }
            for (i in 1..3) File(dir, "$i.png").writeBytes(page(0xFF8E3B5C.toInt()))
        }
        File(root, "소설").mkdirs()
        File(root, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        compose.activityRule.scenario.recreate()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    /**
     * 맞는 것 중 맨 위(나중에 그린) 화면의 것. 작품 화면 · 판은 서재 위에 덮여 그려져 뒤의 격자(꼬리표 "5권" · 작품 이름)가
     * 같은 글자로 남아 있다 — 첫 것을 고르면 덮인 격자를 재거나 누른다.
     */
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        node(matcher).performClick()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openStar() {
        click(hasText("만화 4"))
        click(hasContentDescription("별을 줍는 아이 작품"))
        waitFor(hasText("권 · 5"))
    }

    private suspend fun works() = app.container.data.comics.works().first()

    @Test
    fun `comics get their own tab, and books stay where they were`() {
        waitFor(hasText("책 1권 · 만화 4작품"))
        // 책 탭이 먼저다 — 만화를 넣었다고 책이 밀려나지 않는다.
        assertTrue(has(hasText("어린 왕자.epub")) || has(hasText("어린 왕자")))
        assertFalse(has(hasContentDescription("별을 줍는 아이 작품")), "책 탭에 만화가 섞였다")
        click(hasText("만화 4"))
        waitFor(hasContentDescription("전학생 작품"))
        assertTrue(has(hasText("만화 · 4작품")))
        // 웹툰은 화로 센다. 흩어진 두 폴더의 별을 줍는 아이는 한 작품.
        assertTrue(has(hasText("웹툰 · 2화")))
        assertTrue(has(hasText("만화 · 5권")))
        shot("comic-tab")
    }

    @Test
    fun `a work lists its volumes with the omnibus inside indented to the omnibus text`() {
        openStar()
        assertTrue(has(hasText("만화 · 5권 · 2곳에서 모음")))
        assertTrue(has(hasText("4–6권 합본")))
        assertTrue(has(hasText("한 파일 · 5쪽")))
        assertTrue(has(hasText("3–4쪽")), "합본 안 5권의 쪽 범위가 없다")
        assertTrue(has(hasText("5쪽")) && !has(hasText("5–5쪽")), "한 쪽뿐인 6권이 범위로 나왔다")
        assertTrue(has(hasText("같은 권 2곳")))
        val density = compose.activity.resources.displayMetrics.density
        fun left(text: String) = node(hasText(text)).fetchSemanticsNode().boundsInRoot.left / density
        // UI 규칙 1: 합본 안의 권은 합본 줄의 글자가 시작하는 자리에서 시작한다(표지 40 + 사이 14).
        assertEquals(left("4–6권 합본") + 54f, left("5권"), 1f)
        // 같은 급(낱권 · 합본 · 외전)은 같은 자리.
        assertEquals(left("1권"), left("4–6권 합본"), 1f)
        assertEquals(left("1권"), left("외전"), 1f)
        shot("comic-work")
    }

    @Test
    fun `the arrange screen shows where the volumes came from`() {
        openStar()
        click(hasContentDescription("작품 정리"))
        waitFor(hasText("모은 곳"))
        assertTrue(has(hasText("Books › Comics › 별을 줍는 아이")))
        assertTrue(has(hasText("1–2권 · 4–6권 · 외전")), "폴더별 권 요약이 틀렸다")
        assertTrue(has(hasText("같은 권 2곳 — 3권")))
        shot("comic-arrange")
    }

    @Test
    fun `renaming a work changes only its title, and an empty name brings the old one back`() {
        openStar()
        click(hasContentDescription("작품 정리"))
        click(hasText("작품 이름 고치기"))
        waitFor(hasContentDescription("작품 이름 입력"))
        node(hasContentDescription("작품 이름 입력")).performTextReplacement("별(보관용)")
        click(hasText("저장"))
        waitFor(hasText("별(보관용)"))
        runBlocking { assertTrue(works().any { it.title == "별(보관용)" }) }
        click(hasText("작품 이름 고치기"))
        waitFor(hasContentDescription("작품 이름 입력"))
        node(hasContentDescription("작품 이름 입력")).performTextReplacement("")
        click(hasText("저장"))
        compose.waitUntil(30_000) { runBlocking { works().any { it.title == "별을 줍는 아이" } } }
    }

    @Test
    fun `merging a differently named series keeps the screen on the merged work`() {
        click(hasText("만화 4"))
        click(hasContentDescription("One Piece 작품"))
        click(hasContentDescription("작품 정리"))
        click(hasText("다른 작품과 합치기"))
        click(hasText("원피스"))
        // 합친 작품 화면에 머문다 — 서재로 튕기면 합친 결과를 다시 찾아야 한다.
        waitFor(hasText("권 · 2"))
        assertTrue(has(hasText("원피스")))
        runBlocking {
            val all = works()
            assertEquals(3, all.size)
            assertEquals(listOf("1권", "2권"), all.single { it.title == "원피스" }.entries.map { it.label })
        }
    }

    @Test
    fun `choosing the other copy of a volume sticks`() {
        openStar()
        val before = runBlocking { works().single { it.title == "별을 줍는 아이" }.entries.single { it.label == "3권" }.unit.id }
        click(hasContentDescription("3권 같은 권 2곳"))
        waitFor(hasText("같은 권 2곳 — 3권"))
        val other = if (before.endsWith("_03.cbz")) "별을 줍는 아이 03권.cbz" else "별을줍는아이_03.cbz"
        click(hasText(other))
        compose.waitUntil(30_000) {
            runBlocking { works().single { it.title == "별을 줍는 아이" }.entries.single { it.label == "3권" }.unit.id != before }
        }
        val after = runBlocking { works().single { it.title == "별을 줍는 아이" }.entries.single { it.label == "3권" }.unit.id }
        assertNotEquals(before, after)
        assertTrue(after.endsWith(android.net.Uri.encode(other)) || after.endsWith(other), after)
    }

    @Test
    fun `a comic cover is drawn from the first page, not the stand-in tile`() {
        click(hasText("만화 4"))
        waitFor(hasContentDescription("전학생 작품"))
        waitFor(hasContentDescription("별을 줍는 아이 작품"))
        compose.waitUntil(30_000) {
            runBlocking {
                // 그림 폴더(폴더 안 파일을 찾음)와 압축(항목 하나만 읽음) 두 길 모두.
                works().filter { it.title == "전학생" || it.title == "별을 줍는 아이" }
                    .all { app.container.covers.cachedComic(it.entries.first().unit) != null }
            }
        }
    }
}
