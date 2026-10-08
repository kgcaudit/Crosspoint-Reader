package io.github.kgcaudit.reader.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.backup.BookRecord
import io.github.kgcaudit.reader.data.backup.ComicRecord
import io.github.kgcaudit.reader.data.backup.ProgressRecord
import io.github.kgcaudit.reader.data.sync.SyncCodec
import io.github.kgcaudit.reader.data.sync.SyncFile
import io.github.kgcaudit.reader.document.Locator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertTrue

/**
 * 기기 간 이어 읽기(사용자 결정 8)를 앱 화면으로: 앱 정보의 켬 · 끔과 그 아래 줄, 켠 뒤 쪽을 넘기면 책 폴더에 적히는가, 다른 기기가
 * 더 읽은 책을 열면 띠가 뜨고 "거기로" 가 그 자리로 옮기는가, 쓰기 허락이 없는 폴더는 다시 고르라고 묻는가.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReadingSyncAppTest {

    // 활동 규칙(createAndroidComposeRule)은 끝날 때 활동을 닫으며 기다리다, 만화를 위젯 인텐트로 연 시험에서 멈췄다. 다른 앱
    // 시험(OpenWithTest)처럼 빈 규칙에 시나리오를 직접 띄운다.
    @get:Rule
    val compose = createEmptyComposeRule(StandardTestDispatcher())
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
    }

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private lateinit var root: File
    private val tablet = "0b5c2a6e-1f4d-4a3b-9c8d-7e6f5a4b3c2d"

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "소설").mkdirs()
        File(root, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(root, "만화").mkdirs()
        ZipOutputStream(File(root, "만화/별 01권.cbz").outputStream()).use { zip ->
            listOf(0xFFD03030, 0xFF3050D0, 0xFF2E7D6B, 0xFF888888).forEachIndexed { i, c ->
                zip.putNextEntry(ZipEntry("00${i + 1}.png"))
                zip.write(png(c.toInt()))
                zip.closeEntry()
            }
        }
    }

    private fun register(readOnly: Boolean = false) = runBlocking {
        val data = app.container.data
        if (readOnly) {
            app.contentResolver.takePersistableUriPermission(FolderProvider.treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            data.folders.register(FolderProvider.treeUri)
        }
        data.rescanAll()
        data.probeComics()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) = compose.waitUntil(timeoutMs) { has(matcher) }
    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher, useUnmergedTree = true)[0].performClick()
    }

    private fun turnPage() {
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.waitForIdle()
    }

    private val ownFile: File get() = File(root, ".olo/sync/${app.container.sync.deviceId}.json")

    /** 다른 기기(태블릿)가 동기화 앱으로 넘겨준 파일. */
    private fun tabletRead(books: List<BookRecord> = emptyList(), comics: List<ComicRecord> = emptyList(), at: Long = System.currentTimeMillis() - 3_600_000) {
        File(root, ".olo/sync").mkdirs()
        File(root, ".olo/sync/$tablet.json").writeText(SyncCodec.encode(SyncFile(tablet, "갤럭시 탭 S9", at, books, comics)))
    }

    private fun princeRecord(locator: Locator) = BookRecord(
        "어린 왕자.epub",
        File(root, "소설/어린 왕자.epub").length(),
        progress = ProgressRecord(Locator.encode(locator), 80f, System.currentTimeMillis() - 3_600_000),
    )

    private fun openPrince() {
        if (scenario == null) launch()
        tap(hasText("어린 왕자", substring = true))
        waitFor(hasText("1 / ", substring = true))
    }

    private fun princeProgress() = runBlocking {
        val data = app.container.data
        data.progress.get(data.library.books().first().single().id)?.locator
    }

    @Test
    fun `the sync rows sit under app info and the devices row hangs one step under the switch`() {
        register()
        tabletRead()
        launch()
        waitFor(hasText("어린 왕자", substring = true))
        compose.libraryMenu("앱 정보")
        waitFor(hasText("기기 간 이어 읽기"))
        compose.onAllNodes(hasText("책 폴더에 읽은 자리 남기기"), useUnmergedTree = true)[0].performScrollTo()
        // 기본은 꺼짐(8-4). 꺼져 있으면 아래 줄이 없다 — 켬 · 끔에 속한 줄이다.
        assertFalse(app.container.sync.enabled)
        assertFalse(has(hasText("함께 읽는 기기")))
        tap(hasText("켬"))
        waitFor(hasText("함께 읽는 기기"))
        waitFor(hasText("갤럭시 탭 S9", substring = true))
        assertTrue(app.container.sync.enabled)
        compose.onAllNodes(hasText("함께 읽는 기기"), useUnmergedTree = true)[0].performScrollTo()
        compose.waitForIdle()
        // 위계 규칙: 자식 줄의 글자는 부모(켬 · 끔 줄) 글자 시작선에서 한 단(levelIndent) 들어간다 — 같은 선이면 같은 급의 설정으로 읽힌다.
        val parent = compose.onAllNodes(hasText("책 폴더에 읽은 자리 남기기"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left
        val child = compose.onAllNodes(hasText("함께 읽는 기기"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left
        val density = app.resources.displayMetrics.density
        assertEquals(16f * density, child - parent, 0.5f)
        // 끄면 아래 줄도 사라진다.
        tap(hasText("끔"))
        compose.waitUntil(10_000) { !has(hasText("함께 읽는 기기")) }
        assertFalse(app.container.sync.enabled)
    }

    @Test
    fun `while off nothing is written and once on a page turn lands in the book folder`() {
        register()
        openPrince()
        turnPage()
        waitFor(hasText("2 / ", substring = true))
        // 쓰기는 잠깐 뒤에 한 번 한다 — 그 시간이 지나도 꺼진 동안에는 책 폴더에 아무것도 없다.
        compose.mainClock.advanceTimeBy(3_000)
        Thread.sleep(3_000)
        compose.waitForIdle()
        assertFalse(File(root, ".olo").exists(), "꺼 두었는데 책 폴더에 숨은 폴더가 생겼다")

        app.container.sync.enabled = true
        turnPage()
        waitFor(hasText("3 / ", substring = true))
        // 파일은 만들어진 뒤 채워진다 — 읽힐 때까지 기다린다.
        fun written() = ownFile.takeIf { it.isFile }?.let { SyncCodec.decode(it.readText(), app.container.sync.deviceId) }
        compose.waitUntil(20_000) { written()?.books?.singleOrNull()?.progress?.locator == princeProgress()?.let(Locator::encode) }
    }

    @Test
    fun `a book read further on another device offers to go there and going there moves the reader`() {
        register()
        app.container.sync.enabled = true
        tabletRead(listOf(princeRecord(Locator.Reflow(2, 0))))
        openPrince()
        waitFor(hasText("갤럭시 탭 S9 에서 더 읽었습니다"))
        assertTrue(has(hasText("80% · ", substring = true)))
        // 저절로 옮기지 않는다(8-2): 띠가 떠 있는 동안 자리는 그대로다.
        assertTrue(has(hasText("1 / ", substring = true)))
        tap(hasText("거기로"))
        compose.waitUntil(20_000) { princeProgress().let { it is Locator.Reflow && it.spine == 2 } }
        compose.waitUntil(10_000) { !has(hasText("갤럭시 탭 S9 에서 더 읽었습니다")) }
    }

    @Test
    fun `turning the page dismisses the offer and nothing is offered while sync is off`() {
        register()
        tabletRead(listOf(princeRecord(Locator.Reflow(2, 0))))
        openPrince()
        // 꺼져 있으면 다른 기기의 파일이 있어도 묻지 않는다.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse(has(hasText("에서 더 읽었습니다", substring = true)))

        app.container.sync.enabled = true
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("어린 왕자", substring = true))
        openPrince()
        waitFor(hasText("갤럭시 탭 S9 에서 더 읽었습니다"))
        turnPage()
        // 띠는 8초 뒤에도 저절로 사라진다 — 그보다 훨씬 짧게 기다려야 넘김으로 사라진 것을 본다.
        compose.waitUntil(2_000) { !has(hasText("갤럭시 탭 S9 에서 더 읽었습니다")) }
        // 넘긴 것은 한 쪽뿐 — 다른 기기의 자리로 가지 않았다.
        assertTrue(princeProgress().let { it is Locator.Reflow && it.spine == 0 })
    }

    @Test
    fun `a comic read further elsewhere reopens at that page when asked`() {
        register()
        app.container.sync.enabled = true
        tabletRead(comics = listOf(ComicRecord("별 01권.cbz", File(root, "만화/별 01권.cbz").length(), page = 2, pageCount = 4, updatedAtEpochMs = System.currentTimeMillis())))
        val unit = runBlocking { app.container.data.comics.units().first().single().id }
        launch()
        scenario!!.onActivity { it.onNewIntent(ContinueWidget.intentFor(app, ContinueTarget.Comic(unit))) }
        // 위젯으로 처음 여는 권이라 진도가 없다 — 1쪽에서 열리고 띠가 뜬다.
        waitFor(hasContentDescription("만화 1쪽"))
        waitFor(hasText("갤럭시 탭 S9 에서 더 읽었습니다"))
        assertTrue(has(hasText("3쪽 · ", substring = true)))
        tap(hasText("거기로"))
        waitFor(hasContentDescription("만화 3쪽"))
        assertEquals(2, runBlocking { app.container.data.comics.progressOf(unit)?.page })
    }

    @Test
    fun `a folder added before sync existed is asked to be picked again`() {
        register(readOnly = true)
        launch()
        waitFor(hasText("어린 왕자", substring = true))
        compose.libraryMenu("앱 정보")
        waitFor(hasText("기기 간 이어 읽기"))
        compose.onAllNodes(hasText("책 폴더에 읽은 자리 남기기"), useUnmergedTree = true)[0].performScrollTo()
        tap(hasText("켬"))
        // 0.50 까지는 읽기만 받고 등록했다. 쓸 수 없는 폴더는 조용히 건너뛰지 않고, 무엇을 하면 되는지 묻는다.
        waitFor(hasText("폴더를 한 번 더 골라 주세요"))
        assertTrue(has(hasText("‘Books’ 폴더", substring = true)))
        tap(hasText("나중에"))
        compose.waitUntil(10_000) { !has(hasText("폴더를 한 번 더 골라 주세요")) }
        // 건너뛴 폴더에는 쓰지 않는다 — 실패로 다루지 않고 켜 둔 채다.
        assertTrue(app.container.sync.enabled)
        assertFalse(File(root, ".olo").exists())
    }
}
