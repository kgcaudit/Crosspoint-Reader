package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.BookId
import kotlinx.coroutines.runBlocking
import org.junit.Before
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
 * 앱을 실제로 띄워 사람이 쓰는 순서대로 한 바퀴 돈다.
 *
 * 에뮬레이터 없이 Robolectric 네이티브 그래픽스로 `MainActivity` 를 띄운다. 번들 글꼴로
 * 조판된 페이지가 진짜로 그려지므로, 스크린샷(`build/screenshots/`)으로 화면을 눈으로
 * 확인할 수 있다. 폴더 선택기(시스템 UI)만은 띄울 수 없어 등록 호출로 대신한다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class AppWalkthroughTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var folder: File

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val app = ApplicationProvider.getApplicationContext<OloApp>()
        folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(folder, "메모").mkdirs()
        File(folder, "메모/옛 일기.txt").writeBytes(SampleBooks.txt())
        File(folder, "문서").mkdirs()
        File(folder, "문서/계약서.pdf").writeBytes(ByteArray(64))
    }

    @Test
    fun `a reader can add a folder, open a book, turn pages and bookmark one`() {
        // 1. 처음 켰을 때: 폴더를 추가하라는 안내
        waitFor(hasText("아직 책이 없습니다"))
        shot("01-empty-library")

        // 2. 폴더 등록(폴더 선택기가 돌려주는 트리 URI 를 그대로 넣는다) 후 다시 연다.
        val container = compose.activity.container
        container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("옛 일기.txt"))
        waitFor(hasText("준비 중"))
        shot("02-library")

        // 3. 책 열기: 첫 페이지가 그려질 때까지. 상태바에 "1 / N" 이 뜨면 조판이 끝난 것이다.
        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
        shot("03-reader-first-page")

        // 4. 오른쪽을 눌러 다음 장
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitFor(hasText("2 / ", substring = true))
        shot("04-reader-next-page")

        // 5. 가운데를 누르면 메뉴(목차)
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("제2장 사막의 아침"))
        shot("05-menu-toc")

        // 6. 책갈피를 꽂는다
        node(hasContentDescription("책갈피 꽂기")).performClick()
        waitFor(hasContentDescription("책갈피 빼기"))
        node(hasText("책갈피")).performClick()
        // 미리보기는 책갈피를 꽂은 페이지(2쪽)의 첫 글자부터다.
        waitFor(hasText("그 책에는", substring = true))
        shot("06-menu-bookmarks")

        // 7. 보기 설정: 고딕으로 바꾸고 글자를 키운다
        node(hasText("보기")).performClick()
        waitFor(hasText("글자 크기"))
        node(hasText("고딕")).performClick()
        node(hasContentDescription("글자 크기 늘리기")).performClick()
        node(hasContentDescription("글자 크기 늘리기")).performClick()
        shot("07-menu-settings")

        // 8. 메뉴를 닫고 바뀐 글꼴로 다시 조판된 페이지. 읽던 글자로 돌아와 있어야 한다.
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        waitFor(hasText(" / ", substring = true))
        shot("08-reader-gothic-larger")

        // 9. 라이브러리로 돌아오면 최근 책과 진도가 보인다.
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitFor(hasText("최근에 읽은 책"))
        waitFor(hasText("%", substring = true))
        shot("09-library-with-recent")

        // 저장된 것 확인: 책갈피 1개, 진도가 기록됨, 목록에 책 제목이 반영됨
        val bookId = BookId(
            android.provider.DocumentsContract.buildDocumentUriUsingTree(FolderProvider.treeUri, "Books/소설/어린 왕자.epub").toString(),
        )
        runBlocking {
            assertEquals(1, container.data.bookmarks.forBook(bookId).size)
            assertNotNull(container.data.progress.get(bookId))
            assertEquals("어린 왕자", container.data.library.get(bookId)?.title)
        }
        assertEquals("고딕", container.prefs.load().font.let { if (it.key == "gothic") "고딕" else it.key })
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-night-xhdpi")
    fun `the dark theme uses the OLO dark palette on the library and the page`() {
        compose.activity.container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("옛 일기.txt"))
        shot("11-dark-library")

        node(hasText("옛 일기.txt")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
        shot("12-dark-reader")

        // 다크 지면은 OLO 다크 바탕(#181613)이어야 한다. 검은색(#000)이면 흰 글자가 번져 보이고,
        // 밝은 회색이면 다크 모드의 뜻이 없다.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        assertEquals(0xFF181613.toInt(), bitmap.getPixel(4, view.height / 2))
    }

    @Test
    fun `a broken book shows why it cannot open and the library keeps working`() {
        // 확장자만 .epub 인 깨진 파일. 앱이 죽거나 빈 화면에 갇히면 안 된다 — 이유를 알리고
        // 라이브러리로 돌려보내, 다른 책은 계속 열 수 있어야 한다.
        File(folder, "소설/깨진 책.epub").writeBytes(ByteArray(300) { (it * 7).toByte() })
        compose.activity.container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("깨진 책.epub"))

        node(hasText("깨진 책.epub")).performClick()
        waitFor(hasText("이 책을 열지 못했습니다"))
        // 내부 예외 문장이 아니라 무엇을 하면 되는지가 보여야 한다.
        waitFor(hasText("EPUB 파일이 손상됐거나", substring = true))
        shot("10-broken-book")
        node(hasText("확인")).performClick()

        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
    }

    @Test
    fun `the launcher icon renders on the orange background`() {
        // 벡터 XML 로 옮긴 아이콘이 설계한 모양대로 그려지는지 사람이 볼 수 있게 남긴다.
        val app = ApplicationProvider.getApplicationContext<OloApp>()
        val icon = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0xE8, 0xF0, 0xF0))
        // 삼성 One UI 의 스쿼클과 비슷한 둥근 사각형으로 가린다.
        val inset = size * 0.08f
        canvas.clipPath(Path().apply { addRoundRect(RectF(inset, inset, size - inset, size - inset), size * 0.26f, size * 0.26f, Path.Direction.CW) })
        // 적응형 아이콘은 108 격자 중 가운데 72 만 보인다(바깥 18 씩은 여유).
        val extra = ((size - 2 * inset) * 18f / 72f).toInt()
        listOf(icon.background, icon.foreground).forEach {
            it.setBounds((inset - extra).toInt(), (inset - extra).toInt(), (size - inset + extra).toInt(), (size - inset + extra).toInt())
            it.draw(canvas)
        }
        save(bitmap, "00-launcher-icon")

        // 바탕이 주황이고, 가운데(책)는 밝은 그레이여야 한다.
        val bg = bitmap.getPixel((inset + 12).toInt(), size / 2)
        assertTrue(Color.red(bg) > 190 && Color.green(bg) in 90..140, "바탕색이 주황이 아니다: #${Integer.toHexString(bg)}")
        val book = bitmap.getPixel((size * 0.38f).toInt(), size / 2)
        assertTrue(Color.red(book) > 220 && Color.green(book) > 220, "책 도형이 밝은 그레이가 아니다: #${Integer.toHexString(book)}")
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private fun node(matcher: androidx.compose.ui.test.SemanticsMatcher): SemanticsNodeInteraction =
        compose.onAllNodes(matcher, useUnmergedTree = true).let { all ->
            compose.onNode(matcher, useUnmergedTree = true).takeIf { all.fetchSemanticsNodes().size == 1 }
                ?: all[0]
        }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 화면을 그림으로 남긴다. Compose 의 captureToImage 는 Robolectric 에서 프레임을
     * 기다리다 멈추므로, 창의 뷰를 소프트웨어 캔버스에 직접 그린다.
     */
    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        save(bitmap, name)
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
