package io.github.kgcaudit.reader.app

import android.app.ActivityManager
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.pdf.TestPdf
import io.github.kgcaudit.reader.document.pdf.TestPdf.Companion.pages
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 읽기 잠금(2026-10-15 사용자 결정): 윗줄 자물쇠 → 설명 판 → 안드로이드 확인 창 → 잠금 중. 전화가 오면 풀리고, 돌아오면
 * "다시 잠그기". 잠긴 동안 다른 앱 화면은 열지 않는다.
 *
 * 안드로이드 확인 창은 Robolectric 에 없다 — startLockTask 를 부르면 곧바로 고정된다. 창이 떴다 닫히는 것은 창 초점을 잃었다
 * 되찾는 것으로 흉내 낸다(실제 기기에서 우리가 답을 아는 길이 그것이다). 책 · 그림은 모두 여기서 만든 가짜다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ReadLockAppTest {

    @get:Rule
    val compose = createEmptyComposeRule(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private var scenario: ActivityScenario<MainActivity>? = null
    /** 구상안과 나란히 볼 스크린샷. OLO_SHOTS 로 곳을 정할 수 있다(세션의 작업 폴더) — 없으면 빌드 폴더에. */
    private val shots = File(System.getenv("OLO_SHOTS") ?: System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val pink = 0xFFE8B4C0.toInt()

    private fun png(w: Int, h: Int, draw: (Bitmap) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply(draw)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** 칸 넷짜리 만화 쪽(구상안처럼 하늘 · 살구 · 보라 · 연두). */
    private fun panels(): ByteArray = png(120, 170) { b ->
        val c = Canvas(b)
        c.drawColor(0xFFF3EEE2.toInt())
        val p = android.graphics.Paint()
        fun box(l: Int, t: Int, r: Int, bt: Int, color: Int) { p.color = color; c.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), bt.toFloat(), p) }
        box(6, 7, 114, 58, 0xFFDCE8F0.toInt()); box(6, 63, 58, 114, 0xFFF1DCCB.toInt())
        box(62, 63, 114, 114, 0xFFE4DCF0.toInt()); box(6, 119, 114, 163, 0xFFD9EBD6.toInt())
    }

    private fun zip(file: File, pages: List<Pair<String, ByteArray>>) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { z -> for ((n, b) in pages) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "소설").mkdirs()
        File(root, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(root, "문서").mkdirs()
        File(root, "문서/설명서.pdf").writeBytes(TestPdf().run { pages(10, 3); obj(1, "<< /Type /Catalog /Pages 10 0 R >>"); classic() })
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 3, readsText = false) }
        zip(File(root, "Comics/구름 정원/구름 정원 1권.cbz"), (1..4).map { "%03d.png".format(it) to panels() })
        for (ep in listOf("001화", "002화")) {
            val dir = File(root, "Webtoon/별빛 식당/$ep").apply { mkdirs() }
            for (i in 1..3) File(dir, "$i.png").writeBytes(png(60, 400) { it.eraseColor(pink) })
        }
        app.container.comicRegions = false
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
    }

    /** 연 화면을 닫는다(OpenWithTest 와 같은 까닭 — 남은 화면이 다음 시험 클래스를 무너뜨린다). */
    @After
    fun tearDown() {
        runCatching { scenario?.onActivity { it.finish() } }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        scenario = null
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun onActivity(block: (MainActivity) -> Unit) = scenario!!.onActivity(block)
    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        val ok = runCatching { compose.waitUntil(timeoutMs) { has(matcher) } }.isSuccess
        if (!ok) runCatching { shot("fail") }
        assertTrue(ok, "기다린 것이 끝내 나오지 않았다: ${matcher.description}")
    }
    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        node(matcher).performClick()
    }

    private val lockButton = hasContentDescription("읽기 잠금")
    private val unlockButton = hasContentDescription("잠금 풀기 (길게 누르기)")
    /** 누르는 사이 풀려 이름이 바뀌어도 같은 단추. */
    private val anyLock = lockButton or unlockButton

    private fun pinned(): Boolean {
        var state = ActivityManager.LOCK_TASK_MODE_NONE
        onActivity { state = it.getSystemService(ActivityManager::class.java).lockTaskModeState }
        return state != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /** 안드로이드 확인 창이 떴다가(초점을 가져감) 닫혔다(초점이 돌아옴). */
    private fun androidDialog() {
        onActivity { it.onWindowFocusChanged(false) }
        compose.waitForIdle()
        onActivity { it.onWindowFocusChanged(true) }
        compose.waitForIdle()
    }

    private fun menu() {
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("지금 위치"))
    }

    /** (고르면 알림도 끄고) 자물쇠를 눌러 잠근다 — 확인 창에서 "확인". "알림 끄기" 는 모든 보기 설정의 줄이 고른다. */
    private fun lockFromMenu(quiet: Boolean = false) {
        app.container.readLock.quietChosen = quiet
        click(lockButton)
        androidDialog()
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        onActivity {
            val view = it.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun shot(name: String) = File(shots, "$name.png").outputStream().use { screen().compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun openBook() {
        click(hasText("어린 왕자.epub"))
        waitFor(hasText("1 / ", substring = true))
    }

    private fun back() {
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** 서재(책 · 만화 탭이 보이는 곳)까지 물러난다. 리더마다 메뉴 · 작품 화면 겹 수가 달라 횟수로 정하지 않는다. */
    private fun backToLibrary() {
        repeat(5) {
            // 작품 화면은 서재 위에 겹쳐 그려져 서재의 글자도 함께 보인다 — 작품 화면이 없어야 서재다.
            if (has(hasText("만화 2")) && !has(hasContentDescription("작품 정리"))) return
            back()
            compose.mainClock.advanceTimeBy(500)
        }
        waitFor(hasText("만화 2"))
    }

    private val webtoon = SemanticsMatcher("webtoon view") { n ->
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it.startsWith("웹툰 ") && it.endsWith("%") } == true
    }

    private fun openWebtoon() {
        click(hasText("만화 2"))
        click(hasContentDescription("별빛 식당 작품"))
        click(hasText("1화"))
        waitFor(webtoon)
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun `the lock sits left of the bookmark in book, pdf, comic and webtoon menus`() {
        launch()
        // 책(EPUB)
        openBook()
        menu()
        assertTrue(has(lockButton), "책 윗줄에 자물쇠가 없다")
        // 책갈피 바로 왼쪽.
        val lock = node(lockButton).fetchSemanticsNode().boundsInRoot
        val mark = node(hasContentDescription("책갈피 꽂기")).fetchSemanticsNode().boundsInRoot
        assertTrue(lock.right <= mark.left + 1f && lock.top < mark.bottom && mark.top < lock.bottom, "자물쇠가 책갈피 왼쪽 같은 줄에 있지 않다: $lock / $mark")
        backToLibrary()
        // PDF
        click(hasText("설명서.pdf"))
        waitFor(hasText("1 / 3", substring = true))
        menu()
        assertTrue(has(lockButton), "PDF 윗줄에 자물쇠가 없다")
        backToLibrary()
        // 쪽 넘김 만화
        click(hasText("만화 2"))
        click(hasContentDescription("구름 정원 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
        compose.mainClock.advanceTimeBy(1_000)
        menu()
        assertTrue(has(lockButton), "만화 윗줄에 자물쇠가 없다")
        backToLibrary()
        // 웹툰
        openWebtoon()
        menu()
        assertTrue(has(lockButton), "웹툰 윗줄에 자물쇠가 없다")
    }

    @Test
    fun `the lock asks android at once and adds nothing of its own`() {
        launch()
        openBook()
        menu()
        click(lockButton)
        // 우리 설명 판 없이 곧바로 안드로이드 확인 창(0.54.3 사용자 결정 1) — 그 창이 매번 뜨는데 우리 판까지 두 번 물었다.
        assertTrue(pinned(), "자물쇠가 startLockTask 를 부르지 않았다")
        for (t in listOf("다른 앱으로 나가지 않게", "잠그기", "취소")) assertFalse(has(hasText(t, substring = true)), "우리 설명 판이 남아 있다(\"$t\")")
        // 확인 창이 떠 있는 동안은 아직 잠금 중이 아니다.
        assertFalse(has(hasText("1 / 3장 · 잠금 중")), "확인 창의 답을 듣기 전에 잠금 중이라고 했다")
        androidDialog()
        waitFor(hasText("1 / 3장 · 잠금 중"))
        assertTrue(has(unlockButton), "잠긴 자물쇠가 아니다")
        // 잠긴 순간의 안내는 안드로이드가 띄운다("앱을 고정했어요") — 우리 안내를 겹치지 않는다.
        compose.mainClock.advanceTimeBy(300)
        assertFalse(has(hasText("풀립니다", substring = true)), "안드로이드 안내와 겹치는 우리 안내를 띄웠다")
        shot("book-locked")

        // 짧게 누르면 푸는 법만 짧게.
        click(unlockButton)
        waitFor(hasText("자물쇠를 길게 누르면 풀립니다"))
        assertTrue(pinned(), "짧게 눌렀는데 풀렸다")
    }

    @Test
    // 설정 목록 맨 아래 줄이다 — 긴 화면이라야 굴리지 않고 보인다(굴리기는 이 화면에서 시험의 메모리를 다 썼다).
    @Config(qualifiers = "w400dp-h1600dp-xhdpi")
    fun `quiet mode is chosen in all view settings and remembered`() {
        launch()
        openBook()
        menu()
        click(hasText("보기"))
        click(hasText("모든 보기 설정"))
        waitFor(hasText("잠긴 동안 알림 끄기"))
        // 처음은 끔. 켜면 잠금이 그 값을 쓰고, 앱을 다시 켜도 남는다.
        assertFalse(app.container.readLock.quietChosen, "알림 끄기가 처음부터 켜져 있다")
        val y = node(hasText("잠긴 동안 알림 끄기")).fetchSemanticsNode().boundsInRoot.center.y
        compose.onAllNodes(hasText("켬")).let { nodes ->
            val k = nodes.fetchSemanticsNodes().indexOfFirst { kotlin.math.abs(it.boundsInRoot.center.y - y) < 60f }
            assertTrue(k >= 0, "알림 끄기 줄에 \"켬\" 이 없다")
            nodes[k].performClick()
        }
        compose.waitForIdle()
        assertTrue(app.container.readLock.quietChosen, "켬을 눌렀는데 잠금이 모른다")
        assertTrue(ReadLockQuiet(app).chosen, "알림 끄기를 저장하지 않았다 — 다시 켜면 끔으로 돌아간다")
    }

    @Test
    fun `a hold shorter than two seconds keeps the lock and a full hold unlocks`() {
        launch()
        openBook()
        menu()
        lockFromMenu()
        waitFor(unlockButton)
        compose.mainClock.autoAdvance = false
        try {
            node(unlockButton).performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(1_000)
            // 누르는 동안 손가락에 가리지 않는 자리(윗줄 아래)에 진행이 보인다(0.54.3 사용자 결정 2).
            assertTrue(has(hasText("잠금 푸는 중 · 떼면 그대로")), "누르는 동안 진행을 보이지 않았다")
            val bar = node(hasText("잠금 푸는 중 · 떼면 그대로")).fetchSemanticsNode().boundsInRoot
            val lockAt = node(unlockButton).fetchSemanticsNode().boundsInRoot
            assertTrue(bar.top >= lockAt.bottom, "진행 표시가 자물쇠(누르는 손가락) 아래가 아니라 겹친다")
            compose.mainClock.advanceTimeBy(500)
            node(unlockButton).performTouchInput { up() }
            compose.mainClock.advanceTimeBy(500)
            assertTrue(pinned(), "1.5초 만에 떼었는데 풀렸다")
            assertFalse(has(hasText("잠금 푸는 중 · 떼면 그대로")), "떼었는데 진행 표시가 남았다")
            assertTrue(has(unlockButton))

            node(unlockButton).performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(2_100)
            assertFalse(pinned(), "2초를 눌렀는데 풀리지 않았다")
            node(anyLock).performTouchInput { up() }
            compose.mainClock.advanceTimeBy(500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        waitFor(lockButton)
        assertFalse(has(hasText("1 / 3장 · 잠금 중")), "풀었는데 부제에 잠금 중이 남았다")
    }

    @Test
    fun `unlocking keeps the book over the phone lock screen until the reader leaves the app`() {
        // 안드로이드는 고정을 풀 때 휴대폰을 잠근다(설정 "고정 해제 때 잠금 화면", 화면 잠금을 쓰면 기본 켬). 0.54.0 은 풀자마자
        // PIN 화면이 떠 읽던 책이 사라졌다 — 잠긴 동안 책을 잠금 화면 위에 두어, 풀어도 책이 그대로 있게 한다.
        launch()
        openBook()
        menu()
        onActivity { assertFalse(shadowOf(it).showWhenLocked, "잠그기 전부터 잠금 화면 위에 책을 두었다") }
        lockFromMenu()
        waitFor(unlockButton)
        onActivity { assertTrue(shadowOf(it).showWhenLocked, "잠긴 동안 책을 잠금 화면 위에 두지 않았다 — 풀면 PIN 화면이 뜬다") }
        compose.mainClock.autoAdvance = false
        try {
            node(unlockButton).performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(2_100)
            node(anyLock).performTouchInput { up() }
            compose.mainClock.advanceTimeBy(300)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertFalse(pinned())
        // 푼 순간의 안내도 안드로이드가 띄운다("앱 고정을 해제했어요") — 우리 것은 없다.
        assertFalse(has(hasText("잠금을 풀었습니다")), "안드로이드 안내와 겹치는 우리 안내를 띄웠다")
        onActivity { assertTrue(shadowOf(it).showWhenLocked, "풀자마자 잠금 화면 위 표시를 거뒀다 — 휴대폰 잠금 화면이 책을 덮는다") }
        // 홈으로 나가거나 화면이 꺼지면(화면이 멈춤) 거둔다 — 남기면 꺼졌다 켜진 화면에서 PIN 없이 책이 보인다.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        onActivity { assertFalse(shadowOf(it).showWhenLocked, "화면이 멈췄는데 잠금 화면 위 표시가 남았다") }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    @Test
    fun `a call that ends with the screen off takes the book off the lock screen`() {
        // 통화로 풀린 채 통화 중 전원 단추로 화면을 껐다(화면은 이미 멈춤). 그 사이 통화가 끝나면 잠금 화면 위 표시를 거둔다 —
        // 0.54.1 까지는 남아, 그 뒤 켠 화면에서 PIN 없이 책이 보였다.
        launch()
        openBook()
        menu()
        lockFromMenu()
        waitFor(unlockButton)
        val audio = app.getSystemService(AudioManager::class.java)
        try {
            audio.mode = AudioManager.MODE_RINGTONE
            compose.waitForIdle()
            assertFalse(pinned())
            audio.mode = AudioManager.MODE_IN_CALL
            compose.waitForIdle()
            // 통화 화면이 위에 서서 책 화면이 멈췄다 — 통화 중에는 둔다(끊으면 책으로 바로 돌아와야 한다).
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            onActivity { assertTrue(shadowOf(it).showWhenLocked, "통화 중에 잠금 화면 위 표시를 거뒀다 — 끊으면 PIN 화면이 뜬다") }
            // 화면이 꺼진 채 통화가 끝났다.
            audio.mode = AudioManager.MODE_NORMAL
            shadowOf(android.os.Looper.getMainLooper()).idle()
            onActivity { assertFalse(shadowOf(it).showWhenLocked, "화면이 멈춘 채 통화가 끝났는데 잠금 화면 위 표시가 남았다") }
        } finally {
            audio.mode = AudioManager.MODE_NORMAL
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
    }

    /** 최근 앱 화면을 열어 "이 앱 고정" 을 눌렀다 — 우리 자물쇠를 거치지 않고 고정되고, 돌아오며 초점을 되찾는다. */
    private fun pinFromRecents() {
        onActivity { it.onWindowFocusChanged(false) }
        onActivity { it.startLockTask() }
        compose.waitForIdle()
        onActivity { it.onWindowFocusChanged(true) }
        compose.waitForIdle()
    }

    @Test
    fun `a pin from the recents screen reads locked and a ring still unlocks it`() {
        launch()
        openBook()
        menu()
        pinFromRecents()
        // 0.54.3 까지는 고정된 채 "잠금 중" 도 아니고 자물쇠도 열린 모양이었다 — 길게 눌러 풀 수도, 벨에 풀리지도 않았다.
        waitFor(hasText("1 / 3장 · 잠금 중"))
        assertTrue(has(unlockButton), "최근 앱에서 건 고정인데 자물쇠가 열린 모양이다")
        val audio = app.getSystemService(AudioManager::class.java)
        try {
            audio.mode = AudioManager.MODE_RINGTONE
            compose.waitForIdle()
            assertFalse(pinned(), "최근 앱에서 건 고정을 벨이 풀지 않았다 — 전화 화면이 막힌다")
        } finally {
            audio.mode = AudioManager.MODE_NORMAL
        }
        // 메뉴가 열린 동안은 띠를 거둔다 — 닫고 본다.
        back()
        waitFor(hasText("통화로 잠금이 풀렸습니다"))
    }

    @Test
    fun `a pin from the recents screen unlocks with a long press like our own`() {
        launch()
        openBook()
        menu()
        pinFromRecents()
        waitFor(unlockButton)
        compose.mainClock.autoAdvance = false
        try {
            node(unlockButton).performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(2_100)
            assertFalse(pinned(), "길게 눌렀는데 최근 앱에서 건 고정이 남았다")
            node(anyLock).performTouchInput { up() }
            compose.mainClock.advanceTimeBy(500)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        waitFor(lockButton)
        // 푼 뒤 초점이 오가도(안드로이드 안내) 다시 잠기지 않는다.
        androidDialog()
        assertFalse(has(hasText("잠금 중", substring = true)), "풀자마자 다시 잠금 중이 됐다")
    }

    @Test
    fun `a pin from the recents screen during a call is undone`() {
        launch()
        openBook()
        val audio = app.getSystemService(AudioManager::class.java)
        try {
            audio.mode = AudioManager.MODE_IN_CALL
            pinFromRecents()
            assertFalse(pinned(), "통화 중에 걸린 고정을 두었다 — 통화 화면으로 돌아갈 길이 막힌다")
            assertFalse(has(hasText("잠금 중", substring = true)))
        } finally {
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    @Test
    fun `declining the android dialog leaves the book unlocked`() {
        launch()
        // 확인 창에서 "취소": 고정이 걸리지 않는다.
        app.container.readLock.pinner = object : ReadLockController.Pinner by ReadLockController.PlatformPinner {
            // 확인 창은 떴지만 "취소" 를 눌렀다 — 청하기는 받아들여졌다.
            override fun start(activity: android.app.Activity) = true
        }
        openBook()
        menu()
        lockFromMenu()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue(has(lockButton), "거절했는데 자물쇠가 잠긴 모양이다")
        assertFalse(has(hasText("잠금 중", substring = true)), "거절했는데 잠금 중이라고 한다")
    }

    @Test
    fun `while locked a folder pick is refused with a notice and opens no other screen`() {
        launch()
        openBook()
        menu()
        lockFromMenu()
        waitFor(unlockButton)
        // 서재로 돌아가도 잠금은 이어진다. 폴더 추가는 문서 고르기 창(다른 앱)이라 열지 않는다.
        backToLibrary()
        shadowOf(app).clearNextStartedActivities()
        compose.libraryMenu("책 폴더")
        click(hasText("폴더 추가"))
        waitFor(hasText("잠금 중에는 열 수 없습니다"))
        assertNull(shadowOf(app).nextStartedActivity, "잠긴 동안 고르기 창을 열었다")
        assertTrue(pinned())
    }

    @Test
    fun `a ring unlocks and pauses webtoon auto scroll, and relocking resumes it`() {
        launch()
        openWebtoon()
        menu()
        lockFromMenu()
        waitFor(hasText("잠금 중", substring = true))
        assertTrue(has(hasText("1화 · 0% · 잠금 중")), "웹툰 부제에 잠금 중이 없다")
        val audio = app.getSystemService(AudioManager::class.java)
        compose.mainClock.autoAdvance = false
        try {
            node(hasText("자동")).performClick()
            ticking { has(hasContentDescription("자동 스크롤 멈춤")) }
            // 벨: 고정을 풀고 자동 스크롤을 멈춘다. 벨이 울리는 동안은 띠를 보이지 않는다.
            audio.mode = AudioManager.MODE_RINGTONE
            ticking { !pinned() }
            ticking { has(hasContentDescription("자동 스크롤 이어 하기")) }
            assertFalse(has(hasText("다시 잠그기")), "벨이 울리는 중에 다시 잠그기를 보였다")
            // 통화를 마치고 돌아왔다.
            audio.mode = AudioManager.MODE_IN_CALL
            frames(500)
            audio.mode = AudioManager.MODE_NORMAL
            ticking { has(hasText("통화로 잠금이 풀렸습니다")) }
            assertTrue(has(hasText("다시 잠그기")))
            assertFalse(pinned())
            // 띠가 떠 있는 동안 자동 스크롤 알약은 숨는다(같은 자리 — 구상안 6).
            assertFalse(has(hasContentDescription("자동 스크롤 이어 하기")), "띠와 자동 스크롤 알약이 겹쳤다")
            // 잠글 때의 안내가 사라진 뒤에 찍는다.
            compose.mainClock.advanceTimeBy(3_000)
            shot("webtoon-banner")

            click(hasText("다시 잠그기"))
            frames(100)
            assertTrue(pinned(), "다시 잠그기가 고정을 다시 걸지 않았다")
            onActivity { it.onWindowFocusChanged(false) }
            onActivity { it.onWindowFocusChanged(true) }
            ticking { has(hasContentDescription("자동 스크롤 멈춤")) }
            ticking { !has(hasText("다시 잠그기")) }
            assertFalse(has(hasText("풀립니다", substring = true)), "다시 잠근 뒤 안드로이드 안내와 겹치는 우리 안내를 띄웠다")
            node(hasContentDescription("자동 스크롤 끄기")).performClick()
            frames(100)
        } finally {
            audio.mode = AudioManager.MODE_NORMAL
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun `a ring while reading a book only unlocks and says so`() {
        launch()
        openBook()
        menu()
        lockFromMenu()
        waitFor(unlockButton)
        // 메뉴를 닫고 읽는다.
        back()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_RINGTONE
        compose.waitForIdle()
        assertFalse(pinned(), "벨이 울렸는데 고정이 남았다 — 전화 화면이 막힌다")
        audio.mode = AudioManager.MODE_NORMAL
        waitFor(hasText("통화로 잠금이 풀렸습니다"))
        // 메뉴를 열면 띠를 거둔다 — 아래 도구줄을 덮지 않게. 닫으면 다시 뜬다.
        menu()
        assertFalse(has(hasText("통화로 잠금이 풀렸습니다")), "띠가 메뉴의 도구줄을 덮었다")
        back()
        waitFor(hasText("통화로 잠금이 풀렸습니다"))
        // 책을 닫으면 띠도 거둔다.
        backToLibrary()
        assertFalse(has(hasText("통화로 잠금이 풀렸습니다")), "책을 닫았는데 띠가 남았다")
    }

    @Test
    fun `a page turned comic reads locked before the direction and shows the comic hint`() {
        launch()
        val work = runBlocking { app.container.data.comics.works().first().single { it.title == "구름 정원" } }
        runBlocking { app.container.data.comics.setRightToLeft(work, true) }
        click(hasText("만화 2"))
        click(hasContentDescription("구름 정원 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
        compose.mainClock.advanceTimeBy(1_000)
        menu()
        lockFromMenu()
        waitFor(hasText("1 / 4쪽 · 잠금 중 · 오→왼"))
        compose.mainClock.advanceTimeBy(300)
        shot("comic-locked")
    }

    @Test
    fun `quiet mode is set by our own rule while locked and removed on unlock`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        launch()
        openBook()
        menu()
        lockFromMenu(quiet = true)
        waitFor(unlockButton)
        val rules = nm.automaticZenRules.values
        assertEquals(1, rules.size, "방해 금지 규칙을 만들지 않았다")
        val rule = rules.single()
        assertEquals("읽기 잠금", rule.name)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, rule.interruptionFilter)
        val policy = assertNotNull(rule.zenPolicy)
        assertEquals(android.service.notification.ZenPolicy.PEOPLE_TYPE_ANYONE, policy.priorityCallSenders, "전화가 울리지 않는 규칙이다")
        assertEquals(android.service.notification.ZenPolicy.STATE_ALLOW, policy.priorityCategoryMedia, "미디어를 막아 듣기 · 넘김 소리가 끊긴다")
        // 고른 것을 기억한다.
        assertTrue(app.container.readLock.quiet.chosen)
        // 벨에 풀리면 규칙도 거둔다.
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_RINGTONE
        compose.waitForIdle()
        assertTrue(nm.automaticZenRules.isEmpty(), "전화로 풀렸는데 방해 금지가 남았다")
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
    }

    @Test
    @Config(sdk = [33])
    fun `before android 15 quiet mode changes the filter and gives the old one back`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        val before = NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES, 0, NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS, 0)
        nm.notificationPolicy = before
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        launch()
        openBook()
        menu()
        lockFromMenu(quiet = true)
        waitFor(unlockButton)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
        val on = nm.notificationPolicy
        assertEquals(ReadLockQuiet.QUIET_CATEGORIES, on.priorityCategories)
        assertEquals(NotificationManager.Policy.PRIORITY_SENDERS_ANY, on.priorityCallSenders)
        // 2초 눌러 푼다: 처음 것으로 돌아간다.
        compose.mainClock.autoAdvance = false
        try {
            node(unlockButton).performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(2_100)
            node(anyLock).performTouchInput { up() }
            compose.mainClock.advanceTimeBy(300)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter, "풀었는데 방해 금지가 남았다")
        assertEquals(before.priorityCategories, nm.notificationPolicy.priorityCategories, "사용자의 방해 금지 정책을 되돌리지 않았다")
        assertEquals(before.priorityMessageSenders, nm.notificationPolicy.priorityMessageSenders)
    }

    @Test
    @Config(sdk = [33])
    fun `quiet mode left behind by a killed app is undone on the next start`() {
        // 잠금 중에 앱이 거둬졌다: 방해 금지를 건 채 사라졌다.
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        ReadLockQuiet(app).on()
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
        launch()
        waitFor(hasText("설명서.pdf"))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter, "다시 켰는데 지난번 방해 금지가 남았다")
    }

    @Test
    fun `without quiet permission the settings screen opens first and nothing is pinned`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        launch()
        openBook()
        menu()
        shadowOf(app).clearNextStartedActivities()
        lockFromMenu(quiet = true)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, started?.action, "방해 금지 허락 화면을 열지 않았다")
        assertFalse(pinned(), "허락을 받기 전에 고정했다 — 고정되면 설정 화면이 열리지 않는다")
        // 허락하고 돌아왔다: 이어서 잠근다.
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        onActivity { app.container.readLock.resumed() }
        compose.waitForIdle()
        assertTrue(pinned(), "허락하고 돌아왔는데 잠그지 않았다")
        androidDialog()
        waitFor(unlockButton)
        assertEquals(1, nm.automaticZenRules.size)
    }

    /** 시계를 손으로 한 프레임씩 돌리며 [condition] 을 기다린다(자동 스크롤 중에는 한가해지지 않는다). */
    private fun ticking(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "끝내 맞지 않았다" }
            compose.mainClock.advanceTimeByFrame()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun frames(ms: Long) = repeat((ms / 16).toInt()) { compose.mainClock.advanceTimeByFrame() }
}
