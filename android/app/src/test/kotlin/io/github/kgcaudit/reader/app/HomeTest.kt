package io.github.kgcaudit.reader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 홈(0.25.0, 구상안 나 확정): 읽는 중 · 읽은 책 · 읽을 책. 모든 책 목록을 없애고 한 번도 열지 않은 책을 "읽을 책" 으로
 * 모았다. 읽을 책은 격자 · 목록으로 볼 수 있고, 목록은 형식 · 크기 · 폴더 · 추가한 날 · 독서노트 수를 보인다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1400dp-xhdpi")
class HomeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    /**
     * 넘김 효과(0.32.0 부터 기본 말림)가 도는 채로 시험이 끝나면, 같은 JVM 의 다음 시험 화면이 60초 동안 쉬지 못하고
     * 줄줄이 실패했다(AppNotIdleException). 쪽을 넘기고 끝나는 시험이 많아 시험마다 붙이지 않고 여기서 기다린다.
     */
    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(app.filesDir, "covers").deleteRecursively()
        app.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        app.container.libraryView.setLayout(LibraryLayout.Grid)
        app.container.libraryView.setSort(LibrarySort.Name)
        fun put(path: String, bytes: ByteArray) = File(folder, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        put("소설/어린 왕자.epub", SampleBooks.epub())
        put("메모/옛 일기.txt", SampleBooks.txt())
        put("소설/데미안.epub", SampleBooks.epub())
        put("메모/새 일기.txt", SampleBooks.txt())
        // 가장 큰 책(잡음 표지라 압축되지 않는다) — 크기순의 맨 앞.
        put("그림책/큰 책.epub", CoverTest.coverEpub(noise(), title = "큰 책"))
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            val books = data.library.books().first()
            listOf("어린 왕자.epub", "옛 일기.txt").forEachIndexed { i, name ->
                data.library.markOpened(books.first { it.displayName == name }.id, 100L + i)
            }
        }
        compose.activityRule.scenario.recreate()
    }

    private fun noise(): ByteArray {
        val random = java.util.Random(7)
        val bitmap = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888)
        for (y in 0 until 450) for (x in 0 until 300) bitmap.setPixel(x, y, random.nextInt() or (0xFF shl 24))
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** 가로 화면으로 돈 시험 뒤에 세로로 되돌린다 — 화면 크기는 다음 시험에 남는다(PdfAppTest 참고). */
    @After
    fun backToPortrait() = RuntimeEnvironment.setQualifiers("w393dp-h1400dp-xhdpi")

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]
    private fun top(text: String) = node(hasText(text)).fetchSemanticsNode().boundsInRoot.top
    private fun idOf(name: String): BookId = runBlocking { app.container.data.library.books().first().first { it.displayName == name }.id }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `books never opened are the books to read and an opened book shows only on the shelf`() {
        waitFor(hasText("읽을 책 · 3권"))
        assertTrue(has(hasText("읽는 중 · 2권")))
        // 모든 책 목록은 없다. 빈 갈래(읽은 책 0권)는 숨긴다.
        assertFalse(has(hasText("모든 책")))
        assertFalse(has(hasText("읽은 책 · ", substring = true)))
        // 연 책은 책장에만 — 한 번 더 나오면 목록이 길기만 하다(0.24.x 의 모든 책).
        assertEquals(1, compose.onAllNodes(hasContentDescription("어린 왕자.epub 대신 표지"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(has(hasContentDescription("데미안.epub 대신 표지")))
        shot("100-home-grid")
    }

    @Test
    fun `the list view shows format, size, folder, date added and notes, and is remembered`() {
        runBlocking {
            app.container.data.bookmarks.add(Bookmark(Bookmark.NO_ID, idOf("데미안.epub"), Locator.Reflow(0, 0), snippet = "첫 쪽", createdAtEpochMs = 1L))
        }
        waitFor(hasText("읽을 책 · 3권"))
        node(hasContentDescription("목록으로 보기")).performClick()
        waitFor(hasText("책갈피 1"))
        assertTrue(has(hasText("EPUB")), "형식 표시가 없다")
        assertTrue(has(hasText("소설", substring = true)), "든 폴더가 없다")
        assertTrue(has(hasText("추가", substring = true)), "추가한 날이 없다")
        assertTrue(has(hasText("KB", substring = true)), "크기가 없다")
        shot("101-home-list")
        // 앱을 다시 켜도 목록 그대로.
        compose.activityRule.scenario.recreate()
        waitFor(hasText("책갈피 1"))
        assertEquals(LibraryLayout.List, LibraryViewStore(app).layout.value)
    }

    @Test
    fun `the bookshelf view stands covers on wooden planks for every section and is remembered`() {
        waitFor(hasText("읽을 책 · 3권"))
        node(hasContentDescription("책장으로 보기")).performClick()
        compose.waitForIdle()
        // 세 갈래 모두 책장 — 읽는 중의 책도 판 위에 선다(보기 도구가 탭 전체에 먹는다).
        assertTrue(has(hasText("읽는 중 · 2권")))
        assertTrue(has(hasContentDescription("어린 왕자.epub 대신 표지")))
        val shelved = runCatching { compose.waitUntil(10_000) { !has(hasText("0%")) } }.isSuccess
        assertTrue(shelved, "책장 보기에 진도 글자가 남았다 — 격자 칸이 그대로다")
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "104-home-shelf.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 표지 사이 여백이 나무색(붉은 갈색)이다. 지면색(밝은 미색)이면 판만 그은 격자다.
        val density = compose.activity.resources.displayMetrics.density
        val cover = compose.onAllNodes(hasContentDescription("어린 왕자.epub 대신 표지"), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        val p = bitmap.getPixel((4 * density).toInt(), cover.center.y.toInt())
        val r = android.graphics.Color.red(p); val g = android.graphics.Color.green(p); val b = android.graphics.Color.blue(p)
        assertTrue(r in 100..190 && r > g + 20 && g > b, "책장 바탕이 나무색이 아니다: #${Integer.toHexString(p)}")
        // 판: 표지 바로 아래가 판 색(더 밝은 나무).
        val plank = bitmap.getPixel(cover.center.x.toInt(), (cover.bottom + 5 * density).toInt())
        assertTrue(android.graphics.Color.red(plank) > r, "표지 밑에 판이 없다: #${Integer.toHexString(plank)}")
        compose.activityRule.scenario.recreate()
        waitFor(hasText("읽을 책 · 3권"))
        assertEquals(LibraryLayout.Shelf, LibraryViewStore(app).layout.value)
        // 책장에서도 표지를 누르면 책이 열린다.
        compose.onAllNodes(hasContentDescription("데미안.epub 대신 표지"), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("1 / ", substring = true))
    }

    @Test
    fun `sorting by size puts the biggest book first and by name in Korean order`() {
        waitFor(hasText("읽을 책 · 3권"))
        node(hasContentDescription("목록으로 보기")).performClick()
        waitFor(hasText("추가", substring = true))
        // 이름순: 데미안 · 새 일기 · 큰 책(가나다).
        compose.waitForIdle()
        assertTrue(top("데미안.epub") < top("새 일기.txt") && top("새 일기.txt") < top("큰 책.epub"), "이름순이 아니다: ${listOf("데미안.epub", "새 일기.txt", "큰 책.epub").map { n -> n to compose.onAllNodes(hasText(n), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.top } }}")
        node(hasContentDescription("순서: 이름순")).performClick()
        waitFor(hasText("크기순"))
        node(hasText("크기순")).performClick()
        compose.waitUntil(10_000) { has(hasContentDescription("순서: 크기순")) }
        compose.waitForIdle()
        assertTrue(top("큰 책.epub") < top("데미안.epub"), "큰 책이 맨 앞이 아니다")
    }

    @Test
    fun `an opened book can go back to the books to read`() {
        // 한 번 열어 보고 만 책이 0% 로 "읽는 중" 에 계속 남지 않게.
        waitFor(hasText("읽는 중 · 2권"))
        node(hasText("어린 왕자.epub")).performTouchInput { longClick() }
        waitFor(hasText("읽을 책으로 되돌리기"))
        node(hasText("읽을 책으로 되돌리기")).performClick()
        waitFor(hasText("읽을 책 · 4권"))
        assertTrue(has(hasText("읽는 중 · 1권")))
        // 한 번도 열지 않은 책의 판에는 없다 — 이미 읽을 책이다.
        node(hasText("데미안.epub")).performTouchInput { longClick() }
        waitFor(hasText("읽은 책으로 옮기기"))
        assertFalse(has(hasText("읽을 책으로 되돌리기")))
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `on a wide screen more books fit in a row instead of covers growing past the screen`() {
        // 셋으로 고정하면 가로 화면에서 표지 하나가 263dp 로 화면 높이를 넘어 제목이 밀려났다.
        // 가로 폰(높이 393dp)에서는 읽을 책 격자가 화면 아래라, 화면 위쪽 책장의 표지로 잰다 — 책장 칸도 같은 폭 셈을
        // 쓴다(한 줄에 몇 권, 폭은 실제로 쓰는 폭에서).
        waitFor(hasContentDescription("어린 왕자.epub 대신 표지"))
        val density = compose.activity.resources.displayMetrics.density
        val width = node(hasContentDescription("어린 왕자.epub 대신 표지")).fetchSemanticsNode().boundsInRoot.width / density
        assertTrue(width in 90f..140f, "표지 폭 ${width}dp")
    }

    @Test
    fun `back closes a home popup instead of leaving the app`() {
        // 홈의 책 폴더 · 순서 · 표지 판이 뒤로 가기에 닫히지 않고 앱이 나갔다. 다시 들어오면 판이 그대로 떠 있었다(0.28.3).
        waitFor(hasText("읽는 중 · 2권"))
        node(hasContentDescription("책 폴더")).performClick()
        waitFor(hasText("폴더를 빼도", substring = true))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(has(hasText("폴더를 빼도", substring = true)), "뒤로 가기에 책 폴더 판이 닫히지 않았다")
        assertFalse(compose.activity.isFinishing, "뒤로 가기에 앱이 나갔다")

        node(hasText("데미안.epub")).performTouchInput { longClick() }
        waitFor(hasText("읽은 책으로 옮기기"))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(has(hasText("읽은 책으로 옮기기")), "뒤로 가기에 표지 판이 닫히지 않았다")
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `on a landscape phone the cover popup scrolls so its last buttons can still be pressed`() {
        // 가로 휴대폰(높이 393dp)에서 읽는 중인 책의 표지 판이 화면보다 길어 "닫기" 가 높이 0 으로 눌려 사라졌다.
        waitFor(hasText("읽는 중 · 2권"))
        node(hasText("어린 왕자.epub")).performTouchInput { longClick() }
        waitFor(hasText("닫기"))
        node(hasText("닫기")).performScrollTo()
        val close = node(hasText("닫기")).fetchSemanticsNode().boundsInRoot
        assertTrue(close.height > 20f, "닫기 단추가 눌려 사라졌다: $close")
        node(hasText("닫기")).performClick()
        compose.waitUntil(5_000) { !has(hasText("읽을 책으로 되돌리기")) }
    }

    @Test
    fun `coming back to the app finds a book dropped into the folder meanwhile`() {
        // 앱을 켜 둔 채 브라우저로 등록 폴더에 책을 받고 돌아왔다. 화면이 새로 만들어질 때만 훑어서 "새로고침" 을 눌러야 보였다.
        waitFor(hasText("데미안.epub"))
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        File(File(app.cacheDir, "sdcard"), "Books/소설/새로 받은 책.epub").writeBytes(SampleBooks.epub())
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitFor(hasText("새로 받은 책.epub"))
    }
}
