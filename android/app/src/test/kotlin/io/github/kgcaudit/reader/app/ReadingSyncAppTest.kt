package io.github.kgcaudit.reader.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.backup.BookRecord
import io.github.kgcaudit.reader.data.backup.ComicRecord
import io.github.kgcaudit.reader.data.backup.ProgressRecord
import io.github.kgcaudit.reader.data.sync.SyncCodec
import io.github.kgcaudit.reader.data.sync.SyncFile
import io.github.kgcaudit.reader.data.sync.FtpSecurity
import io.github.kgcaudit.reader.data.sync.SyncProblem
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 다른 기기에서 이어 읽기(0.58.0 FTP · FTPS · SFTP)를 앱 화면으로: 앱 정보 › 동기화 설정의 켬 · 끔과 서버 정하기(인증서 믿기 ·
 * 일반 FTP 로 내려가기 포함), 켠 뒤 쪽을 넘기면 서버에
 * 올라가는가, 다른 기기가 더 읽은 책을 열면 띠가 뜨고 "거기로" 가 그 자리로 옮기는가, 서버에 닿지 못하면 무엇을 보이는가.
 * 서버는 가짜가 아니라 이 프로세스 안의 진짜 FTP 서버다([TestFtpServer]).
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
    private val ftp = TestFtpServer(File(app.cacheDir, "ftp/root").apply { deleteRecursively() })

    /** 서버의 이어 읽기 폴더. */
    private val folder get() = File(ftp.root, "OLO Ecosystem")

    /** 켜 둔 기기(서버를 정해 둠) — 화면을 거치지 않고. */
    private fun turnOn() {
        app.container.ftpSettings.save(ftp.config())
        app.container.sync.enabled = true
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        // Robolectric 에는 안드로이드 키 저장소가 없다 — 감추지 않는 상자.
        app.container.secretBox = object : SecretBox {
            override fun seal(plain: String) = plain
            override fun open(sealed: String) = sealed
        }
        app.container.ftpSettings.clear()
        ftp.start()
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

    @After
    fun tearDown() {
        // 화면을 끝낸다. 안 끝내면 서버를 껐다 켠 시험 다음의 시험이 "Compose 가 쉬지 않음" 으로 60초 뒤 실패했다. close() 는
        // 위젯으로 만화를 연 시험에서 멈춰(위 규칙 주석) 기다리지 않는 finish() 로.
        scenario?.onActivity { it.finish() }
        // finish() 는 주 스레드 차례를 기다린다 — 비워 줘야 실제로 닫힌다.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        ftp.stop()
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

    /** 다른 기기(태블릿)가 서버에 올려 둔 파일. */
    private fun tabletRead(books: List<BookRecord> = emptyList(), comics: List<ComicRecord> = emptyList(), at: Long = System.currentTimeMillis() - 3_600_000) {
        folder.mkdirs()
        File(folder, "$tablet.json").writeText(SyncCodec.encode(SyncFile(tablet, "갤럭시 탭 S9", at, books, comics)))
    }

    /** 이 기기가 [dir] 에 올린 파일. */
    private fun uploaded(dir: File = folder): SyncFile? {
        val id = app.container.sync.deviceId
        return File(dir, "$id.json").takeIf { it.isFile }?.let { SyncCodec.decode(it.readText(), id) }
    }

    private fun field(label: String) = compose.onAllNodes(hasContentDescription(label) and hasSetTextAction(), useUnmergedTree = true)[0]

    /** 서버 설정 칸 셋을 채운다. 포트는 주소에 붙여 쓴다. */
    private fun fill(password: String = TestFtpServer.PASSWORD, address: String = "ftp://127.0.0.1:${ftp.port}/") {
        waitFor(hasContentDescription(FTP_HOST) and hasSetTextAction())
        field(FTP_HOST).performTextReplacement(address)
        field(FTP_USER).performTextReplacement(TestFtpServer.USER)
        field(FTP_PASSWORD).performTextReplacement(password)
    }

    private fun back() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun left(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.left

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
    fun `app info leads to the sync screen and turning it on asks for a server that is checked before it is kept`() {
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
        assertFalse(has(hasText(FTP_HOST)))
        // 켜면 서버 칸 셋이 펼쳐진다(구상안 C). 폴더 · 보안 연결은 고급 설정 안에 있어 처음엔 보이지 않는다. 취소하면 그대로 꺼짐.
        tap(hasText("켬"))
        waitFor(hasContentDescription(FTP_HOST) and hasSetTextAction())
        assertTrue(has(hasText(FTP_ADVANCED)))
        assertTrue(has(hasText("${FtpSettings.DEFAULT_PATH} · 자동")))
        assertFalse(has(hasText(FTP_PATH)))
        assertFalse(has(hasText(FTP_SECURITY)))
        // 위계 규칙: 켬 · 끔에 속한 칸의 이름은 부모 글자 시작선에서 한 단(levelIndent) 들어간다.
        val indent = 16f * app.resources.displayMetrics.density
        assertEquals(indent, left(hasText(FTP_HOST)) - left(hasText(SYNC_SWITCH)), 0.5f)
        tap(hasText("취소"))
        compose.waitUntil(10_000) { !has(hasText(FTP_HOST)) }
        assertFalse(app.container.sync.enabled)

        // 틀린 비밀번호: 까닭을 보이고 켜지지도 저장되지도 않는다.
        tap(hasText("켬"))
        fill(password = "틀림")
        tap(hasText(FTP_CHECK))
        waitFor(hasText(syncProblemText(SyncProblem.Auth)))
        assertFalse(app.container.sync.enabled)
        assertNull(app.container.ftpSettings.config())
        assertFalse(File(folder, "${app.container.sync.deviceId}.json").exists())

        // 고치면 연결되어 켜지고, 칸이 접히며 서버 줄 · 함께 읽는 기기가 보인다.
        field(FTP_PASSWORD).performTextReplacement(TestFtpServer.PASSWORD)
        tap(hasText(FTP_CHECK))
        // 시험 서버는 암호화를 받지 않는다 — 보안 연결 "자동" 은 일반 FTP 로 다시 해 잇고, 그 사실을 서버 줄에 빨갛게 알린다.
        waitFor(hasText("127.0.0.1:${ftp.port} · ${FtpSettings.DEFAULT_PATH} · 암호화 안 됨"))
        assertTrue(has(hasText(SYNC_PLAIN_WARNING)))
        assertTrue(app.container.sync.enabled)
        // 주소창에서 복사한 "ftp://…/" 는 떼고, 포트는 주소에서 읽어 저장한다. 된 쪽(암호화 없음)을 저장한다 — 자동으로 남기면
        // 나중에 가운데서 "암호화 안 받음" 으로 속여도 조용히 내려간다.
        val saved = app.container.ftpSettings.config()!!
        assertEquals("127.0.0.1", saved.host)
        assertEquals(ftp.port, saved.port)
        assertEquals(FtpSecurity.None, saved.security)
        waitFor(hasText("갤럭시 탭 S9"))
        waitFor(hasText("마지막으로 맞춘 때"))
        assertFalse(has(hasText(FTP_HOST)))
        assertEquals(indent, left(hasText("서버")) - left(hasText(SYNC_SWITCH)), 0.5f)
        // 연결 확인에 쓴 시험 파일은 남지 않는다.
        assertFalse(File(folder, ".olo-check").exists())
        // 끄면 아래 줄도 사라진다. 서버 설정은 남아 다시 켤 때 묻지 않는다.
        tap(hasText("끔"))
        compose.waitUntil(10_000) { !has(hasText("마지막으로 맞춘 때")) }
        assertFalse(app.container.sync.enabled)
        tap(hasText("켬"))
        waitFor(hasText("마지막으로 맞춘 때"))
        assertFalse(has(hasText(FTP_HOST)))
    }

    @Test
    fun `a self signed ftps server shows its fingerprint and connects encrypted once trusted`() {
        val tls = TestFtpServer(File(app.cacheDir, "ftps/root").apply { deleteRecursively() }, FtpSecurity.Explicit).start()
        try {
            register()
            openSyncScreen()
            tap(hasText("켬"))
            fill(address = "127.0.0.1:${tls.port}")
            tap(hasText(FTP_CHECK))
            // 자동은 FTPS 를 먼저 한다 — 스스로 만든 인증서라 지문을 보이고 묻는다. 일반 FTP 로 몰래 내려가지 않는다.
            waitFor(hasText("처음 보는 인증서입니다"))
            assertTrue(has(hasText(tls.certificateFingerprint, substring = true)))
            // 판의 취소(칸의 취소는 앞에 있다 — 나중에 그려진 판의 것을 누른다)는 아무것도 저장하지 않고 칸을 남긴다.
            compose.onAllNodes(hasText("취소"), useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            compose.waitUntil(10_000) { !has(hasText("처음 보는 인증서입니다")) }
            assertNull(app.container.ftpSettings.config())
            tap(hasText(FTP_CHECK))
            tap(hasText("이 인증서 믿기"))
            waitFor(hasText("마지막으로 맞춘 때"))
            val saved = app.container.ftpSettings.config()!!
            assertEquals(FtpSecurity.Explicit, saved.security)
            assertEquals(tls.certificateFingerprint, saved.trusted)
            assertFalse(has(hasText(SYNC_PLAIN_WARNING)))
            assertFalse(has(hasText("암호화 안 됨", substring = true)))
        } finally {
            tls.stop()
        }
    }

    @Test
    fun `an sftp address hides the ftp only settings and asks for the server key`() {
        val sftp = TestSftpServer(File(app.cacheDir, "sftp/root").apply { deleteRecursively() }).start()
        try {
            register()
            openSyncScreen()
            tap(hasText("켬"))
            fill(address = "sftp://127.0.0.1:${sftp.port}")
            waitFor(hasText("${FtpSettings.DEFAULT_PATH} · SFTP"))
            // SFTP 는 늘 암호화되고 연결이 하나라 보안 연결 · 연결 모드 줄이 없다.
            tap(hasText(FTP_ADVANCED))
            waitFor(hasText(FTP_ENCODING))
            assertFalse(has(hasText(FTP_SECURITY)))
            assertFalse(has(hasText(FTP_MODE)))
            back()
            compose.waitUntil(10_000) { !has(hasText(FTP_ENCODING)) }
            tap(hasText(FTP_CHECK))
            waitFor(hasText("처음 보는 서버 키입니다"))
            tap(hasText("이 서버 키 믿기"))
            waitFor(hasText("마지막으로 맞춘 때"))
            val saved = app.container.ftpSettings.config()!!
            assertEquals(io.github.kgcaudit.reader.data.sync.FtpProtocol.Sftp, saved.protocol)
            assertTrue(saved.trusted != null)
            assertTrue(File(sftp.root, "OLO Ecosystem").isDirectory)
            assertFalse(has(hasText(SYNC_PLAIN_WARNING)))
        } finally {
            sftp.stop()
        }
    }

    @Test
    fun `changing the server folder uploads this device's record to the new folder`() {
        register()
        openPrince()
        turnPage()
        waitFor(hasText("2 / ", substring = true))
        turnOn()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("어린 왕자", substring = true))
        compose.libraryMenu("앱 정보")
        tap(hasText(SYNC_TITLE))
        compose.waitUntil(20_000) { uploaded() != null }
        tap(hasText("바꾸기"))
        // 펼친 칸에는 지금 설정이 들어 있다 — 고급 설정에서 저장 폴더만 바꾸면 된다.
        waitFor(hasText("127.0.0.1:${ftp.port}"))
        tap(hasText(FTP_ADVANCED))
        waitFor(hasContentDescription(FTP_PATH) and hasSetTextAction())
        field(FTP_PATH).performTextReplacement("/책/이어 읽기")
        back()
        compose.waitUntil(10_000) { !has(hasContentDescription(FTP_PATH) and hasSetTextAction()) }
        assertTrue(has(hasText("/책/이어 읽기 · 암호화 없음")))
        tap(hasText(FTP_CHECK))
        waitFor(hasText("127.0.0.1:${ftp.port} · /책/이어 읽기", substring = true))
        // 내용은 바뀌지 않았지만 새 폴더에는 처음 쓰는 것이다 — "이미 올렸다" 로 건너뛰면 다른 기기가 이 기기를 못 본다.
        compose.waitUntil(20_000) { uploaded(File(ftp.root, "책/이어 읽기"))?.books?.size == 1 }
    }

    @Test
    fun `while off nothing goes to the internet and once on a page turn is uploaded to the server`() {
        register()
        openPrince()
        turnPage()
        waitFor(hasText("2 / ", substring = true))
        // 쓰기는 잠깐 뒤에 한 번 한다 — 그 시간이 지나도 꺼진 동안에는 서버에 아무것도 가지 않는다.
        compose.mainClock.advanceTimeBy(3_000)
        Thread.sleep(3_000)
        compose.waitForIdle()
        assertFalse(folder.exists(), "꺼 두었는데 서버에 올렸다")

        turnOn()
        turnPage()
        waitFor(hasText("3 / ", substring = true))
        compose.waitUntil(20_000) { uploaded()?.books?.singleOrNull()?.progress?.locator == princeProgress()?.let(Locator::encode) }
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
    fun `with the server down the screen says why and syncing again after it is back clears it`() {
        register()
        turnOn()
        ftp.stop()
        openSyncScreen()
        // 화면을 열면 맞추려 하다 닿지 못한다 — 맞춘 때 대신 까닭과 할 일을 보인다. 켬은 그대로다.
        waitFor(hasText(syncProblemText(SyncProblem.Unreachable)))
        assertTrue(app.container.sync.enabled)
        ftp.start()
        tap(hasText("지금 맞추기"))
        compose.waitUntil(20_000) { !has(hasText(syncProblemText(SyncProblem.Unreachable))) }
        compose.waitUntil(20_000) { app.container.sync.status.value.let { it.problem == null && it.lastSyncedAtEpochMs != null } }
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
        // 옛 켬으로 새 방식이 저절로 켜지지 않는다(서버를 정해야 한다).
        assertFalse(app.container.sync.enabled)
        tap(hasText(SYNC_TITLE))
        waitFor(hasText("이어 읽기 방식이 바뀌었습니다"))
        tap(hasText("나중에"))
        compose.waitUntil(10_000) { !has(hasText("이어 읽기 방식이 바뀌었습니다")) }
        assertFalse(app.container.sync.legacyWasOn)
    }
}
