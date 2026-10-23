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
 * 다른 기기에서 이어 읽기(0.57.0 구글 드라이브)를 앱 화면으로: 앱 정보 › 이어 읽기 화면의 켬 · 끔과 계정 잇기, 켠 뒤 쪽을 넘기면 드라이브에
 * 올라가는가, 다른 기기가 더 읽은 책을 열면 띠가 뜨고 "거기로" 가 그 자리로 옮기는가, 잇지 못하거나 허락이 취소되면 무엇을 보이는가.
 * 드라이브 · 구글 계정은 가짜다([FakeDrive] · [FakeAccount]).
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
    private val drive = FakeDrive()
    private val account = FakeAccount(drive)

    /** 시험용 구글 계정. [connectResult] 를 [connect] 가 돌려준다(계정 고르기 화면 없이 바로 이어진 것처럼). */
    private class FakeAccount(private val drive: FakeDrive) : DriveAccount {
        override val email = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        override var connected = false
        var revoked = false
        var connects = 0
        var connectResult: ConnectStep = ConnectStep.Done("reader@gmail.com")

        override suspend fun connect(): ConnectStep {
            connects++
            val r = connectResult
            if (r is ConnectStep.Done) {
                connected = true
                email.value = r.email
            }
            return r
        }

        override suspend fun finish(data: Intent?) = connect()
        override suspend fun token(fresh: Boolean): String? = if (!connected) null else if (revoked) "revoked" else drive.validToken
        override suspend fun disconnect() {
            connected = false
            email.value = null
        }
    }

    /** 켜 둔 기기(계정을 이어 둠) — 화면을 거치지 않고. */
    private fun turnOn() {
        account.connected = true
        account.email.value = "reader@gmail.com"
        app.container.sync.enabled = true
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        app.container.driveAccount = account
        app.container.driveHttp = drive.http
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

    private fun register() = runBlocking {
        val data = app.container.data
        data.folders.register(FolderProvider.treeUri)
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

    /** 다른 기기(태블릿)가 드라이브에 올려 둔 파일. */
    private fun tabletRead(books: List<BookRecord> = emptyList(), comics: List<ComicRecord> = emptyList(), at: Long = System.currentTimeMillis() - 3_600_000) {
        drive.put("$tablet.json", SyncCodec.encode(SyncFile(tablet, "갤럭시 탭 S9", at, books, comics)))
    }

    private fun openSyncScreen() {
        launch()
        waitFor(hasText("어린 왕자", substring = true))
        compose.libraryMenu("앱 정보")
        tap(hasText(SYNC_TITLE))
        waitFor(hasText(SYNC_SWITCH))
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
    fun `app info leads to the sync screen and turning it on asks first then shows the account and devices one step in`() {
        register()
        tabletRead()
        launch()
        waitFor(hasText("어린 왕자", substring = true))
        compose.libraryMenu("앱 정보")
        waitFor(hasText(SYNC_TITLE))
        assertTrue(has(hasText("꺼짐")))
        tap(hasText(SYNC_TITLE))
        waitFor(hasText(SYNC_SWITCH))
        // 기본은 꺼짐. 꺼져 있으면 무엇을 올리는지 설명만 있다.
        assertTrue(has(hasText(SYNC_EXPLAIN)))
        assertFalse(has(hasText("계정")))
        // 켜면 바로 구글 화면으로 가지 않고 무엇을 하는지 먼저 묻는다. 취소하면 그대로 꺼짐.
        tap(hasText("켬"))
        waitFor(hasText(SYNC_CONNECT_TITLE))
        tap(hasText("취소"))
        compose.waitUntil(10_000) { !has(hasText(SYNC_CONNECT_TITLE)) }
        assertEquals(0, account.connects)
        assertFalse(app.container.sync.enabled)
        tap(hasText("켬"))
        tap(hasText("계정 고르기"))
        waitFor(hasText("reader@gmail.com"))
        assertTrue(app.container.sync.enabled)
        waitFor(hasText("갤럭시 탭 S9"))
        waitFor(hasText("마지막으로 맞춘 때"))
        // 위계 규칙: 켬 · 끔에 속한 줄(계정)의 글자는 부모 글자 시작선에서 한 단(levelIndent) 들어간다.
        val parent = compose.onAllNodes(hasText(SYNC_SWITCH), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left
        val child = compose.onAllNodes(hasText("계정"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left
        assertEquals(16f * app.resources.displayMetrics.density, child - parent, 0.5f)
        // 끄면 아래 줄도 사라지고 드라이브에 묻지 않는다.
        tap(hasText("끔"))
        compose.waitUntil(10_000) { !has(hasText("계정")) }
        assertFalse(app.container.sync.enabled)
    }

    @Test
    fun `while off nothing goes to the internet and once on a page turn is uploaded to the drive`() {
        register()
        openPrince()
        turnPage()
        waitFor(hasText("2 / ", substring = true))
        // 쓰기는 잠깐 뒤에 한 번 한다 — 그 시간이 지나도 꺼진 동안에는 드라이브에 아무것도 가지 않는다.
        compose.mainClock.advanceTimeBy(3_000)
        Thread.sleep(3_000)
        compose.waitForIdle()
        assertTrue(drive.requests.isEmpty(), "꺼 두었는데 드라이브에 요청했다")

        turnOn()
        turnPage()
        waitFor(hasText("3 / ", substring = true))
        fun written() = drive.text("${app.container.sync.deviceId}.json")?.let { SyncCodec.decode(it, app.container.sync.deviceId) }
        compose.waitUntil(20_000) { written()?.books?.singleOrNull()?.progress?.locator == princeProgress()?.let(Locator::encode) }
    }

    @Test
    fun `a book read further on another device offers to go there and going there moves the reader`() {
        register()
        turnOn()
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

        turnOn()
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
        turnOn()
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
    fun `a connection that fails leaves sync off and says why`() {
        register()
        account.connectResult = ConnectStep.Failed("이 판은 아직 구글 드라이브 연결이 준비되지 않았습니다(앱 등록 필요).")
        openSyncScreen()
        tap(hasText("켬"))
        tap(hasText("계정 고르기"))
        waitFor(hasText("앱 등록 필요", substring = true))
        assertFalse(app.container.sync.enabled)
        assertFalse(has(hasText("계정")))
    }

    @Test
    fun `a revoked permission asks to reconnect instead of showing the last sync time`() {
        register()
        turnOn()
        account.revoked = true
        openSyncScreen()
        // 화면을 열면 맞추려 하다 거절된다 — 맞춘 때 대신 할 일을 보인다.
        waitFor(hasText("구글 드라이브 허락이 취소되었습니다"))
        assertTrue(has(hasText("다시 연결")))
        assertFalse(has(hasText("지금 맞추기")))
        // 다시 이으면 줄이 돌아온다.
        account.revoked = false
        tap(hasText("다시 연결"))
        waitFor(hasText("지금 맞추기"))
    }

    @Test
    fun `a device that used the old book folder sync is told the method changed`() {
        File(app.noBackupFilesDir, "sync").mkdirs()
        File(app.noBackupFilesDir, "sync/enabled").writeText("1")
        register()
        launch()
        waitFor(hasText("어린 왕자", substring = true))
        compose.libraryMenu("앱 정보")
        waitFor(hasText("방식이 바뀌었습니다 — 다시 켜 주세요"))
        // 옛 켬으로 새 방식이 저절로 켜지지 않는다(계정을 이어야 한다).
        assertFalse(app.container.sync.enabled)
        tap(hasText(SYNC_TITLE))
        waitFor(hasText("이어 읽기 방식이 바뀌었습니다"))
        tap(hasText("나중에"))
        compose.waitUntil(10_000) { !has(hasText("이어 읽기 방식이 바뀌었습니다")) }
        assertFalse(app.container.sync.legacyWasOn)
    }
}
