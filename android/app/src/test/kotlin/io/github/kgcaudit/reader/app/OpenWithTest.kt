package io.github.kgcaudit.reader.app

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.BookId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 파일 관리자(OLO Explorer, 내 파일…)의 "연결 프로그램" 으로 책이 들어오는 길.
 *
 * 파일은 등록 폴더 밖(`Downloads/`)에 둔다. 폴더 선택기로 등록한 책만 열던 0.5.0 까지는 이 길이
 * 아예 없어서 연결 프로그램 목록에 앱이 뜨지 않았다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class OpenWithTest {

    /**
     * 효과(LaunchedEffect · collectAsState)를 **UI 스레드 하나**에서 돌린다 — 실제 앱(AndroidUiDispatcher)과 같게.
     *
     * 기본값은 UnconfinedTestDispatcher 라, 백그라운드에서 값이 온 코루틴(조판 스레드의 리더 상태, Room 의
     * 질의 결과, `withContext(IO)` 에서 돌아온 효과)이 그 스레드에서 그대로 이어졌다. 화면 상태 쓰기와 프레임이
     * 메인 밖에서 돌아 "잘못된 스레드" 예외가 나거나 깨움을 놓쳐 "첫 쪽을 30초 기다리다 실패" 했다(0.5.1 부터
     * 있던 간헐 실패). StandardTestDispatcher 는 돌아온 코루틴을 대기열에 넣고 시험 스레드에서 차례로 돌린다.
     */
    @get:Rule
    val compose = createEmptyComposeRule(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private lateinit var base: File
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        base = File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() }
        val books = FolderProvider.install(base)
        File(books, "소설").mkdirs()
        File(books, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(base, "Downloads").mkdirs()
    }

    // ActivityScenario.close() 는 부르지 않는다. Compose 테스트 규칙 안에서 부르면 Robolectric 의
    // 메인 스레드에서 DESTROYED 를 기다리며 멈춘다. 테스트마다 Robolectric 이 환경을 새로 만든다.

    private fun download(name: String, bytes: ByteArray) = File(base, "Downloads/$name").writeBytes(bytes)

    private fun view(name: String, type: String? = null) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(DocumentsContract.buildDocumentUri(FolderProvider.AUTHORITY, "Downloads/$name"), type)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch(intent.setClass(app, MainActivity::class.java))
    }

    private fun waitFor(text: String, substring: Boolean = false, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun onActivity(block: (MainActivity) -> Unit) = scenario!!.onActivity(block)

    @Test
    fun `a book sent from a file manager opens straight away and back returns to that app`() {
        download("받은 책.epub", SampleBooks.epub())
        val intent = view("받은 책.epub", "application/epub+zip")
        launch(intent)
        waitFor("1 / ", substring = true)
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitFor("2 / ", substring = true)

        // 진도는 받은 URI 로 저장되고, 라이브러리·최근 목록에는 오르지 않는다 — 나중에 눌러도
        // 읽기 권한이 없어 열리지 않는다.
        val id = BookId(intent.data.toString())
        compose.waitUntil(10_000) { runBlocking { app.container.data.progress.get(id) } != null }
        assertTrue(runBlocking { app.container.data.library.recent(limit = 20).first() }.isEmpty())

        // 뒤로: 라이브러리가 아니라 보낸 앱으로 돌아간다.
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        onActivity { assertEquals(1, it.leftToCaller) }

        // 같은 파일을 다시 보내면(앱은 떠 있다 — onNewIntent) 읽던 곳으로 돌아온다.
        onActivity { it.onNewIntent(Intent(intent)) }
        waitFor("2 / ", substring = true)
    }

    @Test
    fun `a file that is also in the library opens as the library book`() {
        // 등록 폴더의 책과 이름·크기가 같은 복사본. 따로 열면 진도·책갈피가 두 벌이 된다.
        download("어린 왕자.epub", SampleBooks.epub())
        launch(Intent(app, MainActivity::class.java))
        app.container.data.let { runBlocking { it.folders.register(FolderProvider.treeUri) } }
        scenario!!.recreate()
        waitFor("어린 왕자.epub")

        onActivity { it.onNewIntent(view("어린 왕자.epub")) }
        waitFor("1 / ", substring = true)
        val libraryId = DocumentsContract.buildDocumentUriUsingTree(FolderProvider.treeUri, "Books/소설/어린 왕자.epub").toString()
        val recent = { runBlocking { app.container.data.library.recent(limit = 20).first() }.map { it.id.value } }
        runCatching { compose.waitUntil(10_000) { recent() == listOf(libraryId) } }
            .onFailure { throw AssertionError("최근 목록 ${recent()}, 라이브러리 ${runBlocking { app.container.data.library.books().first() }}", it) }
    }

    @Test
    fun `files the app cannot read are explained instead of opening a blank reader`() {
        // 모르는 형식, 확장자만 epub 인 깨진 파일.
        download("사진.heic", ByteArray(64))
        launch(view("사진.heic", "image/heic"))
        waitFor("열 수 없는 형식", substring = true)

        download("깨진 책.epub", ByteArray(300) { (it * 7).toByte() })
        onActivity { it.onNewIntent(view("깨진 책.epub", "application/epub+zip")) }
        waitFor("EPUB 파일이 손상됐거나", substring = true)
        // 이유를 본 뒤에는 라이브러리가 남는다(빈 리더에 갇히지 않는다).
        onActivity { assertEquals(0, it.leftToCaller) }
    }

    @Test
    fun `a pdf sent from a file manager opens in the pdf reader`() {
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 3) }
        download("계약서.pdf", ByteArray(64))
        launch(view("계약서.pdf", "application/pdf"))
        waitFor("1 / 3")
    }

    @Test
    fun `a file without an extension is recognised by the type the sender gives`() {
        download("book", SampleBooks.epub())
        launch(view("book", "application/epub+zip"))
        waitFor("1 / ", substring = true)
    }

    @Test
    fun `the phone offers OLO eBook for EPUB, TXT and PDF but not for pictures`() {
        // 연결 프로그램 목록은 안드로이드가 매니페스트 선언으로 만든다. 선언이 빠지면 앱이 아무리
        // 잘 열어도 목록에 없다 — 사용자가 본 증상이 바로 이것이었다.
        fun offered(type: String): Boolean {
            val probe = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("content://other.app.files/x"), type)
            return app.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == app.packageName }
        }
        assertTrue(offered("application/epub+zip"), "EPUB")
        assertTrue(offered("text/plain"), "TXT")
        assertTrue(offered("application/pdf"), "PDF")
        assertTrue(!offered("image/jpeg"))
        assertNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName), "홈 화면 아이콘은 그대로")
    }
}
