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
import io.github.kgcaudit.reader.reflow.ReaderPrefs
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
 * 앱을 실제로 띄워 사람이 쓰는 순서대로 한 바퀴 돈다.
 *
 * 에뮬레이터 없이 Robolectric 네이티브 그래픽스로 `MainActivity` 를 띄운다. 실제 글꼴로
 * 조판된 페이지가 진짜로 그려지므로, 스크린샷(`build/screenshots/`)으로 화면을 눈으로
 * 확인할 수 있다. 폴더 선택기(시스템 UI)만은 띄울 수 없어 등록 호출로 대신한다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class AppWalkthroughTest {

    /**
     * 효과(LaunchedEffect · collectAsState)를 **UI 스레드 하나**에서 돌린다 — 실제 앱(AndroidUiDispatcher)과 같게.
     *
     * 기본값은 UnconfinedTestDispatcher 라, 백그라운드에서 값이 온 코루틴(조판 스레드의 리더 상태, Room 의
     * 질의 결과, `withContext(IO)` 에서 돌아온 효과)이 그 스레드에서 그대로 이어졌다. 화면 상태 쓰기와 프레임이
     * 메인 밖에서 돌아 "잘못된 스레드" 예외가 나거나 깨움을 놓쳐 "첫 쪽을 30초 기다리다 실패" 했다(0.5.1 부터
     * 있던 간헐 실패). StandardTestDispatcher 는 돌아온 코루틴을 대기열에 넣고 시험 스레드에서 차례로 돌린다.
     */
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

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
        waitFor(hasText("계약서.pdf"))
        shot("02-library")

        // 폴더 단추는 하나다(＋ 와 폴더가 같은 일로 가던 중복을 없앴다). 추가는 그 안에 있다.
        assertTrue(compose.onAllNodes(hasContentDescription("폴더 추가"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        node(hasContentDescription("책 폴더")).performClick()
        waitFor(hasText("폴더 추가"))
        shot("02b-book-folders")
        // 팝업은 바깥을 눌러 닫는다.
        compose.onRoot().performTouchInput { click(topCenter.copy(y = 8f)) }
        compose.waitForIdle()

        // 3. 책 열기: 첫 페이지가 그려질 때까지. 상태바에 "1 / N" 이 뜨면 조판이 끝난 것이다.
        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
        shot("03-reader-first-page")

        // 4. 오른쪽을 눌러 다음 장
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitFor(hasText("2 / ", substring = true))
        shot("04-reader-next-page")

        // 5. 가운데를 누르면 얇은 도구줄만 뜬다. 지면은 거의 그대로 보인다.
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("목차"))
        shot("05-menu-bar")

        // 6. 책갈피를 꽂는다(위쪽 단추)
        node(hasContentDescription("책갈피 꽂기")).performClick()
        waitFor(hasContentDescription("책갈피 빼기"))

        // 7. 목차는 전체 화면으로 열리고, 지금 챕터가 표시된다.
        node(hasText("목차")).performClick()
        waitFor(hasText("제2장 사막의 아침"))
        shot("06-contents")
        node(hasText("책갈피")).performClick()
        // 미리보기는 책갈피를 꽂은 페이지(2쪽)의 첫 글자부터다.
        waitFor(hasText("그 책에는", substring = true))
        shot("07-bookmarks")
        // 뒤로 가면 도구줄로 돌아온다.
        node(hasContentDescription("뒤로")).performClick()
        waitFor(hasText("보기"))

        // 8. 보기 설정: 도구줄 위에 작은 판으로 열린다. 글자를 키운다. 글꼴은 휴대폰 글꼴이다
        // (이 환경에는 한국어 명조가 없다).
        node(hasText("보기")).performClick()
        waitFor(hasText("글자 크기"))
        waitFor(hasText("휴대폰 글꼴"))
        node(hasContentDescription("글자 크기 늘리기")).performClick()
        node(hasContentDescription("글자 크기 늘리기")).performClick()
        shot("08-view-settings")

        // 9. 진행 막대를 끝쪽으로 눌러 멀리 간다. 마지막 장(3/3)으로 가야 한다.
        node(hasContentDescription("읽은 위치")).performTouchInput { click(centerRight.copy(x = width * 0.97f)) }
        waitFor(hasText("3 / 3 장"), timeoutMs = 30_000)

        // 10. 지면을 누르면 메뉴가 닫히고, 바뀐 글꼴로 조판된 페이지가 보인다.
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        waitFor(hasText(" / ", substring = true))
        shot("09-reader-after-seek")

        // 11. 라이브러리로 돌아오면 최근 책과 진도가 보인다.
        compose.activity.onBackPressedDispatcher.onBackPressed()
        waitFor(hasText("최근에 읽은 책"))
        waitFor(hasText("%", substring = true))
        shot("10-library-with-recent")

        // 저장된 것 확인: 책갈피 1개, 진도가 기록됨, 목록에 책 제목이 반영됨
        val bookId = BookId(
            android.provider.DocumentsContract.buildDocumentUriUsingTree(FolderProvider.treeUri, "Books/소설/어린 왕자.epub").toString(),
        )
        runBlocking {
            assertEquals(1, container.data.bookmarks.forBook(bookId).size)
            assertNotNull(container.data.progress.get(bookId))
            assertEquals("어린 왕자", container.data.library.get(bookId)?.title)
        }
        assertEquals(ReaderPrefs.DEFAULT_SIZE_SP + 2, container.prefs.load().fontSizeSp)
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
    fun `a font file picked from storage becomes the reading font, and a broken one is explained`() {
        File(folder, "글꼴").mkdirs()
        TestFonts.copy("olo-test-regular.ttf", File(folder, "글꼴/올로.ttf"))
        File(folder, "글꼴/덜 받은.ttf").writeBytes(File(folder, "글꼴/올로.ttf").readBytes().copyOf(3000))
        val container = compose.activity.container
        container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("어린 왕자.epub"))
        node(hasText("어린 왕자.epub")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("글꼴")).performClick()
        waitFor(hasText("사용자 글꼴"))
        shot("13-fonts-system-only")

        // "사용자 글꼴" → 파일 선택기(시스템 UI)가 고른 파일을 돌려준다.
        pickFont("글꼴/올로.ttf")
        waitFor(hasText("Olo Test Sans"))
        shot("14-fonts-added")
        val key = container.prefs.load().font
        assertEquals("user:olo test sans", key, "넣은 글꼴로 바로 바뀌어야 한다")

        // 덜 받은 파일: 앱이 죽거나 조용히 넘어가지 않고 이유를 말한다.
        pickFont("글꼴/덜 받은.ttf")
        waitFor(hasText("글꼴을 넣지 못했습니다"))
        waitFor(hasText("손상", substring = true))
        shot("15-fonts-broken")
        node(hasText("확인")).performClick()
        assertEquals(1, container.fonts.user!!.families().size)

        // 돌아가면 넣은 글꼴로 조판된 페이지. 보기 판에도 이름이 보인다.
        node(hasContentDescription("뒤로")).performClick()
        waitFor(hasText("Olo Test Sans"))
        shot("16-view-settings-user-font")
        compose.onRoot().performTouchInput { click(topCenter.copy(y = height * 0.3f)) }
        waitFor(hasText(" / ", substring = true))
        shot("17-reader-user-font")

        // 빼면 휴대폰 글꼴로 돌아간다.
        compose.onRoot().performTouchInput { click(center) }
        node(hasText("보기")).performClick()
        node(hasText("Olo Test Sans")).performClick()
        node(hasContentDescription("Olo Test Sans 빼기")).performClick()
        node(hasText("빼기")).performClick()
        compose.waitUntil(10_000) { container.prefs.load().font == null }
        waitFor(hasText("휴대폰 글꼴"))
        assertTrue(container.fonts.user!!.families().isEmpty())
    }

    /** 파일 선택기 흉내: 앱이 띄운 선택 요청에 고른 문서의 URI 로 답한다. */
    private fun pickFont(path: String) {
        node(hasText("사용자 글꼴")).performClick()
        compose.waitForIdle()
        val activity = org.robolectric.Shadows.shadowOf(compose.activity)
        val request = checkNotNull(activity.nextStartedActivityForResult) { "파일 선택기를 띄우지 않았다" }
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(FolderProvider.treeUri, "Books/$path")
        compose.runOnUiThread {
            activity.receiveResult(request.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
        }
        compose.waitForIdle()
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
    fun `a portrait picture in a book is drawn in its own shape`() {
        // 세 권의 실제 책에서 세로 표지가 가로로 늘어나던 결함. 크기 없는 <img> 와 CSS
        // 클래스(width:100%)만 있는 Sigil 책 모양 그대로 넣고, 화면에 칠해진 영역을 잰다.
        val w = 591
        val h = 839
        val png = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 60, 200)) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        File(folder, "그림책.epub").writeBytes(SampleBooks.pictureBook(png))
        compose.activity.container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("그림책.epub"))
        node(hasText("그림책.epub")).performClick()
        waitFor(hasText("1 / ", substring = true), timeoutMs = 30_000)
        // 그림은 조판 뒤 따로 풀린다. 칠해질 때까지 기다린다.
        compose.waitUntil(15_000) { blueBox() != null }
        shot("11-picture-page")

        val box = blueBox()!!
        val ratio = box.height().toFloat() / box.width()
        assertEquals(h.toFloat() / w, ratio, 0.02f, "화면의 그림 비율이 파일과 다르다: ${box.width()}x${box.height()}")
        // 폭 100% 로 적혀 있으니 본문 폭 가까이 차야 한다(작게 쪼그라들어도 안 된다).
        assertTrue(box.width() > compose.activity.window.decorView.width * 0.8f, "그림이 너무 작다: ${box.width()}")
    }

    /** 화면에서 파란 그림이 칠해진 영역. 없으면 null. */
    private fun blueBox(): android.graphics.Rect? {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
            val p = bitmap.getPixel(x, y)
            if (Color.blue(p) > 170 && Color.red(p) < 60) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r, b)
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
