package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.DocumentsContract
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.backup.RecordsCodec
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 읽기 기록 백업 · 가져오기(0.27.0, 구상안 가 확정): 앱 정보 안 묶음, 늘 합치기, 못 찾은 책은 기억. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReadingRecordsTest {

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
    private lateinit var folder: File
    private var prince = BookId("아직 모름")

    @Before
    fun setUp() {
        folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(folder, "옛 일기.txt").writeBytes(SampleBooks.txt())
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            prince = data.library.books().first().first { it.displayName == "어린 왕자.epub" }.id
            data.library.markOpened(prince, 100L)
            data.progress.save(ReadingProgress(prince, Locator.Reflow(0, 40), 30f, 200L))
            data.bookmarks.add(Bookmark(Bookmark.NO_ID, prince, Locator.Reflow(0, 10), "첫 책갈피", 300L))
        }
        compose.activityRule.scenario.recreate()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openAbout() {
        compose.libraryMenu("앱 정보")
        waitFor(hasText("책 1권 · 책갈피 1개 · 형광펜 · 메모 0개"))
    }

    /** 파일 고르기 · 저장할 곳 고르기 흉내: 앱이 띄운 요청에 책 폴더 안 [path] 로 답한다. */
    private fun answer(path: String) {
        compose.waitForIdle()
        val activity = shadowOf(compose.activity)
        val request = checkNotNull(activity.nextStartedActivityForResult) { "파일 고르기를 띄우지 않았다" }
        val uri = DocumentsContract.buildDocumentUriUsingTree(FolderProvider.treeUri, "Books/$path")
        compose.runOnUiThread {
            activity.receiveResult(request.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
        }
    }

    private fun bookmarks() = runBlocking { app.container.data.bookmarks.forBook(prince) }

    @Test
    fun `a backup made in the app brings the records back after they are lost`() {
        openAbout()
        shot("110-records-about")
        node(hasText("백업 파일 만들기")).performClick()
        answer("backup.json")
        waitFor(hasText("백업 파일을 만들었습니다 · 책 1권"))
        waitFor(hasText("마지막 백업", substring = true))
        val written = File(folder, "backup.json")
        assertTrue(RecordsCodec.decode(written.readText()) != null, "백업 파일이 이 앱의 형식이 아니다")

        // 휴대폰을 바꾼 것처럼 기록을 잃는다(책은 폴더에 그대로).
        runBlocking {
            val data = app.container.data
            data.bookmarks.forBook(prince).forEach { data.bookmarks.remove(it.id) }
            data.progress.remove(prince)
        }
        assertTrue(bookmarks().isEmpty())

        node(hasText("백업 파일에서 가져오기")).performClick()
        answer("backup.json")
        waitFor(hasText("이 휴대폰에서 찾은 책 1권"))
        shot("111-records-preview")
        node(hasText("가져오기")).performClick()
        waitFor(hasText("책 1권의 기록을 가져왔습니다"))
        shot("112-records-done")
        assertEquals(listOf(Locator.Reflow(0, 10)), bookmarks().map { it.locator })
        assertEquals(Locator.Reflow(0, 40), runBlocking { app.container.data.progress.get(prince)?.locator })
        node(hasText("확인")).performClick()
        // 되살린 기록이 묶음의 수에 바로 보인다.
        waitFor(hasText("책 1권 · 책갈피 1개 · 형광펜 · 메모 0개"))
    }

    @Test
    fun `records of a book that is not on this phone are listed and kept for later`() {
        File(folder, "old.json").writeText(
            """{"format":"olo-ebook-reading-records","version":1,"createdAt":0,"books":[
              {"name":"어린 왕자.epub","size":${File(folder, "어린 왕자.epub").length()},"bookmarks":[{"locator":"r:0:99","createdAt":1}]},
              {"name":"운수 좋은 날.epub","size":77,"bookmarks":[{"locator":"r:1:1","createdAt":1}]}]}""",
        )
        openAbout()
        node(hasText("백업 파일에서 가져오기")).performClick()
        answer("old.json")
        waitFor(hasText("아직 못 찾은 책 1권"))
        node(hasText("가져오기")).performClick()
        waitFor(hasText("운수 좋은 날.epub"))
        shot("113-records-missing")
        // 이 휴대폰의 책갈피에 백업의 책갈피가 더해졌다 — 지워지지 않았다.
        assertEquals(listOf(Locator.Reflow(0, 10), Locator.Reflow(0, 99)), bookmarks().map { it.locator })
        assertEquals(listOf("운수 좋은 날.epub"), runBlocking { app.container.data.records.pending().books.map { it.displayName } })

        // 나중에 그 책이 폴더에 들어오면, 폴더를 훑을 때 기록이 저절로 붙는다.
        File(folder, "운수 좋은 날.epub").writeBytes(ByteArray(77))
        runBlocking {
            val data = app.container.data
            data.rescanAll()
            val lucky = data.library.books().first().first { it.displayName == "운수 좋은 날.epub" }.id
            assertEquals(listOf(Locator.Reflow(1, 1)), data.bookmarks.forBook(lucky).map { it.locator })
            assertTrue(data.records.pending().books.isEmpty(), "붙인 기록이 계속 기다린다")
        }
    }

    @Test
    fun `choosing a file that is not a backup changes nothing and back closes only the message`() {
        openAbout()
        node(hasText("백업 파일에서 가져오기")).performClick()
        answer("어린 왕자.epub")
        waitFor(hasText("백업 파일이 아닙니다"))
        assertTrue(has(hasText("고른 파일(어린 왕자.epub)", substring = true)), "고른 파일 이름을 알려 주지 않았다")
        shot("114-records-not-backup")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { !has(hasText("백업 파일이 아닙니다")) }
        assertTrue(has(hasText("읽기 기록")), "뒤로 가기가 앱 정보까지 닫았다")
        assertEquals(1, bookmarks().size)
    }
}
