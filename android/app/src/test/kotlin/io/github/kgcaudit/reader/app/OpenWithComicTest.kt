package io.github.kgcaudit.reader.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.archive.StoredArchives
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 파일 관리자의 "연결 프로그램" 으로 만화(cbz · cbr · 그냥 zip …)가 들어오는 길.
 *
 * 0.52.0 까지는 매니페스트가 EPUB · TXT · PDF 만 밝혀서, OLO Explorer 에서 cbz 를 눌러도 연결 프로그램 목록에 이 앱이 없었다
 * (사용자 보고). 파일은 등록 폴더 밖(`Downloads/`)에 둔다 — 서재에 같은 권이 있을 때만 서재 쪽으로 연다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class OpenWithComicTest {

    // OpenWithTest 와 같은 까닭으로 효과를 UI 스레드 하나에서 돌린다.
    @get:Rule
    val compose = createEmptyComposeRule(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private lateinit var base: File
    private lateinit var books: File
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun png(w: Int = 60, h: Int = 90, color: Int = 0xFF2E7D6B.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
    }.toByteArray()

    private val threePages by lazy { zip("001.png" to png(), "002.png" to png(), "003.png" to png()) }

    /**
     * 연 화면을 닫는다. 닫지 않으면 다음 시험(새 앱 · 새 DB)이 도는 동안 옛 화면이 닫힌 옛 DB 를 붙들고 계속 그려, 다음 시험
     * 클래스 전체가 "Compose did not get idle" 로 무너졌다(0.53.0 에서 받은 만화의 작품 설정 시험을 더한 뒤 25~75건).
     */
    @After
    fun tearDown() {
        // ActivityScenario.close() 는 이 화면에서 끝나지 않았다(파괴를 기다리다 멈춤) — 화면이 스스로 닫히게 하고 할 일을 비운다.
        runCatching { scenario?.onActivity { it.finish() } }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        scenario = null
    }

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        base = File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() }
        books = FolderProvider.install(base)
        File(base, "Downloads").mkdirs()
    }

    private fun download(name: String, bytes: ByteArray) = File(base, "Downloads/$name").writeBytes(bytes)

    private fun view(name: String, type: String? = null) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(DocumentsContract.buildDocumentUri(FolderProvider.AUTHORITY, "Downloads/$name"), type)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch(intent.setClass(app, MainActivity::class.java))
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) = compose.waitUntil(timeoutMs) { has(matcher) }
    private fun waitForText(text: String) = waitFor(hasText(text, substring = true))
    private fun page(n: Int) = hasContentDescription("만화 ${n}쪽")
    private fun onActivity(block: (MainActivity) -> Unit) = scenario!!.onActivity(block)
    private fun tapRight() {
        // 한 번 누르기는 두 번 누르기를 기다린 뒤에 온다 — 시계를 흘려 앞의 누름과 붙지 않게 한다.
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        compose.mainClock.advanceTimeBy(1_000)
    }
    private suspend fun comicProgress() = app.container.data.comics.progress().first()

    @Test
    fun `a cbz sent from a file manager opens in the comic reader and back returns to that app`() {
        download("별 01권.cbz", threePages)
        val intent = view("별 01권.cbz", "application/vnd.comicbook+zip")
        launch(intent)
        waitFor(page(1))
        // 받은 만화는 서재에 넣지 않는다 — 읽기 허락은 이 화면이 떠 있는 동안만이다.
        assertTrue(runBlocking { app.container.data.comics.units().first() }.isEmpty(), "받은 만화가 서재에 올랐다")
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        onActivity { assertEquals(1, it.leftToCaller) }
    }

    @Test
    fun `a cbz that the sender calls octet-stream still opens by its name`() {
        // 여러 파일 관리자가 cbz 에 "모르는 형식" 을 붙여 보낸다. 형식을 먼저 보면 "열 수 없는 형식" 이 떴다.
        download("별 01권.cbz", threePages)
        launch(view("별 01권.cbz", "application/octet-stream"))
        waitFor(page(1))
    }

    @Test
    fun `the name decides over the type the sender gives`() {
        // EPUB 은 속이 zip 이라 보낸 앱이 application/zip 을 붙이기도 한다. 형식을 먼저 보면 책이 "만화 그림이 없습니다" 로 거절됐다.
        download("받은 책.epub", SampleBooks.epub())
        launch(view("받은 책.epub", "application/zip"))
        waitForText("1 / ")
        assertTrue(!has(hasText("만화 그림이 없습니다", substring = true)))
        // 거꾸로, 이름이 cbz 면 EPUB 이라고 보내도 만화다.
        download("별 01권.cbz", threePages)
        onActivity { it.onNewIntent(view("별 01권.cbz", "application/epub+zip")) }
        waitFor(page(1))
    }

    @Test
    fun `a comic that is not in the library comes back to the page it was left at`() {
        download("별 01권.cbz", threePages)
        val intent = view("별 01권.cbz", "application/x-cbz")
        launch(intent)
        waitFor(page(1))
        tapRight()
        waitFor(page(2))
        // 자리는 받은 URI 를 열쇠로 적힌다(서재 단위가 없다).
        val id = intent.data.toString()
        compose.waitUntil(10_000) { runBlocking { comicProgress()[id]?.page } == 1 }
        // 닫았다가(보낸 앱으로 돌아감) 같은 파일을 다시 보내면(앱은 떠 있다) 처음이 아니라 읽던 쪽으로. 먼저 닫는다 — 열린 채 다시
        // 보내면 새로 열기 전의 옛 화면이 "2쪽" 을 보여, 처음부터 열어도 통과했다.
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { !has(page(2)) }
        onActivity { it.onNewIntent(Intent(intent)) }
        waitFor(page(2))
    }

    @Test
    fun `a comic that is also in the library opens as that volume and keeps its place`() {
        // 등록 폴더의 권과 이름 · 크기가 같은 사본. 따로 열면 읽던 자리 · 책갈피가 두 벌이 된다.
        File(books, "Comics/별").mkdirs()
        File(books, "Comics/별/별 01권.cbz").writeBytes(threePages)
        download("별 01권.cbz", threePages)
        val data = app.container.data
        runBlocking {
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        val libraryId = DocumentsContract.buildDocumentUriUsingTree(FolderProvider.treeUri, "Books/Comics/별/별 01권.cbz").toString()
        runBlocking { data.comics.saveProgress(libraryId, 2, 3, 1L, atEnd = false) }
        val intent = view("별 01권.cbz", "application/vnd.comicbook+zip")
        launch(intent)
        waitFor(page(3))
        // 받은 URI 로는 아무것도 적지 않았다 — 자리가 서재의 권 한 벌로만 이어진다.
        compose.waitForIdle()
        assertNull(runBlocking { comicProgress()[intent.data.toString()] }, "서재에 있는 권을 받은 URI 로 따로 열었다")
        // 서재의 권으로 열었어도 다른 앱에서 왔으니 닫으면 그 앱으로 돌아간다.
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        onActivity { assertEquals(1, it.leftToCaller) }
    }

    @Test
    fun `a library volume that no longer opens falls back to the file that was sent`() {
        // 서재의 권이 열리지 않는다(폴더 허락이 풀림 · 훑기 전에 바뀐 파일). 서재 쪽만 보면 "열지 못했습니다" 로 끝난다.
        File(books, "Comics/별").mkdirs()
        val inLibrary = File(books, "Comics/별/별 01권.cbz").apply { writeBytes(threePages) }
        download("별 01권.cbz", threePages)
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        // 크기는 그대로 두고 속만 망가뜨린다 — 지우면 앱이 켜지며 훑는 사이 서재에서 숨어, 이 길을 지나지 않고도 통과한다.
        inLibrary.writeBytes(ByteArray(threePages.size) { 7 })
        launch(view("별 01권.cbz", "application/vnd.comicbook+zip"))
        waitFor(page(1))
        assertTrue(!has(hasText("열지 못했습니다", substring = true)))
    }

    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }

    /** 메뉴 → 보기 판을 연다. 두 쪽 보기(넓은 가로 화면)라 표지 줄이 설 자리다 — 휴대폰 가로는 낮아 줄이 판 아래로 밀린다. */
    private fun openViewPanel() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("보기"))
        compose.onAllNodes(hasText("보기"), useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitFor(hasText("넘기는 방향"))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a sent copy of a library volume keeps the two-page cover choice of that work`() {
        // 서재 쪽이 열리지 않아(속이 망가짐) 받은 파일로 연 때. 0.52.0 까지는 표지 줄이 없어, 같은 작품을 서재에서 열 때와 보기 판이 달랐다.
        File(books, "Comics/별").mkdirs()
        val inLibrary = File(books, "Comics/별/별 01권.cbz").apply { writeBytes(threePages) }
        download("별 01권.cbz", threePages)
        val data = app.container.data
        runBlocking {
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        inLibrary.writeBytes(ByteArray(threePages.size) { 7 })
        launch(view("별 01권.cbz", "application/vnd.comicbook+zip"))
        waitFor(hasContentDescription("만화", substring = true))
        openViewPanel()
        waitFor(hasText("표지"))
        // "함께" 는 다른 줄(모든 보기 설정의 기본값)에도 있다 — 표지 줄과 같은 높이의 것을 누른다.
        val y = node(hasText("표지")).fetchSemanticsNode().boundsInRoot.center.y
        compose.onAllNodes(hasText("함께")).let { nodes ->
            val k = nodes.fetchSemanticsNodes().indexOfFirst { kotlin.math.abs(it.boundsInRoot.center.y - y) < 60f }
            assertTrue(k >= 0, "표지 줄에 \"함께\" 가 없다")
            nodes[k].performClick()
        }
        // 고른 값은 서재 작품에 적힌다 — 받은 파일의 열쇠(문서 주소)에 적으면 서재에서 열 때 따르지 않는다.
        val work = runBlocking { data.comics.works().first().single() }
        compose.waitUntil(10_000) { runBlocking { data.comics.coverChoices().first().works[work.key] } == false }
        // "넓은 그림" 줄도 같은 작품에 적는다.
        waitFor(hasText("넓은 그림"))
        val splitY = node(hasText("넓은 그림")).fetchSemanticsNode().boundsInRoot.center.y
        compose.onAllNodes(hasText("한 장")).let { nodes ->
            val k = nodes.fetchSemanticsNodes().indexOfFirst { kotlin.math.abs(it.boundsInRoot.center.y - splitY) < 60f }
            assertTrue(k >= 0, "넓은 그림 줄에 \"한 장\" 이 없다")
            nodes[k].performClick()
        }
        compose.waitUntil(10_000) { runBlocking { data.comics.works().first().single().splitSpreads } == false }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a sent comic that is not in the library has no cover row to save into`() {
        download("별 01권.cbz", threePages)
        launch(view("별 01권.cbz", "application/vnd.comicbook+zip"))
        waitFor(hasContentDescription("만화", substring = true))
        openViewPanel()
        // 적을 작품이 없다 — 줄을 두면 고른 값이 어디에도 남지 않는다.
        assertTrue(!has(hasText("표지")), "서재에 없는 받은 만화에 표지 줄이 있다")
        // 넓은 그림도 적을 작품이 없어 줄이 없다 — 늘 자동 판단이다.
        assertTrue(!has(hasText("넓은 그림")), "서재에 없는 받은 만화에 넓은 그림 줄이 있다")
    }

    @Test
    fun `a plain zip of pictures opens, and a zip without pictures is explained`() {
        // 만화를 받은 그대로 .zip 으로 두는 사람이 많다 — 그림만 들었으면 만화다(서재의 살피기와 같은 규칙).
        download("별 (스캔).zip", zip("01.jpg" to png(), "02.jpg" to png()))
        launch(view("별 (스캔).zip", "application/zip"))
        waitFor(page(1))

        // 문서 묶음: 검은 화면에 멈추거나 앱이 닫히지 않고 까닭을 보인다. 서재가 남는다(보낸 앱으로 말없이 돌아가지 않는다).
        download("보고서.zip", zip("보고서.docx" to ByteArray(40), "읽어 보기.txt" to "안녕".toByteArray()))
        onActivity { it.onNewIntent(view("보고서.zip", "application/zip")) }
        waitForText("이 압축 파일에는 만화 그림이 없습니다")
        onActivity { assertEquals(0, it.leftToCaller) }
        // 알림을 닫는다 — 남겨 두면 다음 파일이 열려도 이 문구가 보여 통과했다.
        compose.onAllNodes(hasText("확인"), useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodes(hasText("확인"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(10_000) { !has(hasText("만화 그림이 없습니다", substring = true)) }

        // 소설 zip 에 표지 그림 하나: 이름이 만화라고 말하지 않으니 그림 하나로는 만화가 아니다(서재와 같다).
        download("소설.zip", zip("소설.txt" to "옛날 옛적에".toByteArray(), "표지.jpg" to png()))
        onActivity { it.onNewIntent(view("소설.zip", "application/zip")) }
        waitForText("이 압축 파일에는 만화 그림이 없습니다")
    }

    @Test
    fun `a bundle of volume archives says how to read it instead of saying there are no pictures`() {
        download("별 (1-2권).zip", zip("별 1권.cbz" to threePages, "별 2권.cbz" to threePages))
        launch(view("별 (1-2권).zip", "application/zip"))
        waitForText("여러 권을 묶은 것입니다")
    }

    @Test
    fun `a broken archive says it is damaged, not that it has no pictures`() {
        download("깨진 01권.cbz", ByteArray(300) { (it * 7).toByte() })
        launch(view("깨진 01권.cbz", "application/vnd.comicbook+zip"))
        waitForText("손상됐거나")
        assertTrue(!has(hasText("만화 그림이 없습니다", substring = true)))
    }

    @Test
    fun `a rar and a tar sent by name open through the same reader`() {
        // RAR 은 해제기가 파일 경로를 요구해 서재의 권처럼 캐시에 풀어 둔다 — 받은 파일에서도 같은 길이 돈다.
        val pages = listOf("001.png" to png(), "002.png" to png())
        download("별 01권.rar", StoredArchives.rar4(pages))
        launch(view("별 01권.rar", "application/octet-stream"))
        waitFor(page(1))
        tapRight()
        waitFor(page(2))
        download("별 02권.tar", StoredArchives.tar(pages))
        onActivity { it.onNewIntent(view("별 02권.tar", "application/x-tar")) }
        waitFor(page(1))
    }

    @Test
    fun `a sent webtoon scrolls like a webtoon and remembers where it was`() {
        // 세로로 긴 그림. 쪽 넘김으로 보이면 한 쪽이 손톱만 하게 줄어든다 — 서재의 웹툰 판별을 그대로 따른다.
        app.container.comicRegions = false
        download("전학생 001화.cbz", zip("1.png" to png(60, 400), "2.png" to png(60, 400), "3.png" to png(60, 400)))
        val intent = view("전학생 001화.cbz", "application/vnd.comicbook+zip")
        launch(intent)
        val webtoon = SemanticsMatcher("webtoon view") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("웹툰 ") && it.endsWith("%") } == true
        }
        waitFor(webtoon)
        compose.onRoot().performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(1_000)
        val id = intent.data.toString()
        compose.waitUntil(30_000) { runBlocking { comicProgress()[id]?.let { it.page > 0 || (it.offset ?: 0f) > 0f } == true } }
        // 웹툰 화면을 닫고 끝낸다. 이 규칙은 화면(액티비티)을 닫지 않아(OpenWithTest 참고) 띠를 풀던 웹툰이 다음 시험까지 남으면, 다음
        // 시험의 Compose 가 한가해지지 않았다.
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        onActivity { assertEquals(1, it.leftToCaller) }
    }

    @Test
    fun `files the app cannot read name what it does open`() {
        // 음악 파일에 "모르는 형식" 이 붙어 왔다. 옛 문구는 EPUB · TXT · PDF 만 말해, 만화를 열 수 있다는 것을 알 길이 없었다.
        download("노래.mp3", ByteArray(64))
        launch(view("노래.mp3", "application/octet-stream"))
        waitForText("‘노래.mp3’ 은 열 수 없는 형식입니다")
        assertTrue(has(hasText("cbz", substring = true)), "거절 문구가 만화를 연다는 것을 말하지 않는다")
        onActivity { assertEquals(0, it.leftToCaller) }
    }

    @Test
    fun `an address to a private file is still refused`() {
        // file:// 을 받으면 이 앱이 제 권한으로 자기 안쪽 파일(설정 · DB)을 열어 보이게 된다. 만화 형식이어도 같다.
        val inside = File(app.filesDir, "몰래.cbz").apply { writeBytes(threePages) }
        launch(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.fromFile(inside), "application/vnd.comicbook+zip"))
        waitForText("이 주소는 열 수 없습니다")
        assertTrue(!has(page(1)))
    }

    @Test
    fun `the phone offers OLO eBook for comics and archives but not for pictures or music`() {
        // 연결 프로그램 목록은 안드로이드가 매니페스트 선언으로 만든다. 선언이 빠지면 앱이 아무리 잘 열어도 목록에 없다 — 사용자가
        // 본 증상이 바로 이것이었다.
        fun offered(type: String): Boolean {
            val probe = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("content://other.app.files/x"), type)
            return app.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == app.packageName }
        }
        // 앱이 여는 형식 목록(Incoming)과 선언이 같아야 한다 — 한쪽에만 더하면 열 수 있는데 목록에 없거나, 목록에 있는데 거절한다.
        for (type in Incoming.COMIC_TYPES + Incoming.ARCHIVE_TYPES + "application/octet-stream") assertTrue(offered(type), type)
        // 아무 형식(*/*)을 밝히면 사진 · 음악을 열 때마다 목록에 끼어 성가시기만 하다.
        for (type in listOf("image/jpeg", "audio/mpeg", "video/mp4", "application/vnd.android.package-archive")) assertTrue(!offered(type), type)
    }

    @Test
    fun `the kind of a sent file comes from its name before its type`() {
        fun kind(name: String, type: String?) = Incoming.kindOf(name) { type }
        assertEquals(Incoming.Kind.Comic(trusted = true), kind("별.cbz", "application/octet-stream"))
        assertEquals(Incoming.Kind.Comic(trusted = false), kind("별.zip", null))
        assertEquals(Incoming.Kind.Comic(trusted = false), kind("별.TAR", null))
        assertEquals(Incoming.Kind.Comic(trusted = true), kind("별", "application/x-cbr"))
        assertEquals(Incoming.Kind.Comic(trusted = false), kind("download", "application/zip; charset=binary"))
        assertEquals(Incoming.Kind.Book(io.github.kgcaudit.reader.document.BookFormat.EPUB), kind("책.epub", "application/zip"))
        assertNull(kind("노래.mp3", "application/octet-stream"))
        assertNull(kind("이름 없음", null))
        // 이름으로 정해지면 제공자에게 형식을 묻지 않는다(묻는 것 자체가 느릴 수 있다).
        var asked = false
        Incoming.kindOf("별.cbz") { asked = true; null }
        assertTrue(!asked, "이름으로 정해졌는데 형식을 물었다")
    }
}
