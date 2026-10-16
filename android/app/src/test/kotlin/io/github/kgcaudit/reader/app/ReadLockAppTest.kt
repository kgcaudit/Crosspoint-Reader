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

    /** 설명 판을 열어 (고르면 알림도 끄고) 잠근다 — 확인 창에서 "확인". */
    private fun lockFromMenu(quiet: Boolean = false) {
        click(lockButton)
        waitFor(hasText("알림 끄기"))
        if (quiet) click(hasText("켬"))
        click(hasText("잠그기"))
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
    fun `locking explains first, pins after android confirms and marks the subtitle`() {
        launch()
        openBook()
        menu()
        click(lockButton)
        waitFor(hasText("다른 앱으로 나가지 않게 잠급니다.\n전화는 그대로 받습니다."))
        for (t in listOf("알림 끄기", "취소", "잠그기")) assertTrue(has(hasText(t)), "설명 판에 \"$t\" 가 없다")
        // 두 줄과 고르기 하나뿐이다(0.54.1) — 0.54.0 의 다섯 줄 설명이 장황하다는 말을 들었다.
        for (t in listOf("2초", "방해 금지", "안드로이드 확인 창")) assertFalse(has(hasText(t, substring = true)), "설명 판에 \"$t\" 설명이 돌아왔다")
        // 처음 "알림 끄기" 는 끔.
        assertTrue(compose.onAllNodes(hasText("끔") and isSelected()).fetchSemanticsNodes().isNotEmpty(), "알림 끄기가 처음부터 켜져 있다")
        assertFalse(pinned(), "설명 판만 열었는데 고정됐다")
        shot("explainer")

        click(hasText("잠그기"))
        // 확인 창이 떠 있는 동안은 아직 잠금 중이 아니다.
        assertTrue(pinned(), "잠그기가 startLockTask 를 부르지 않았다")
        assertFalse(has(hasText("1 / 3장 · 잠금 중")), "확인 창의 답을 듣기 전에 잠금 중이라고 했다")
        androidDialog()
        waitFor(hasText("1 / 3장 · 잠금 중"))
        assertTrue(has(unlockButton), "잠긴 자물쇠가 아니다")
        waitFor(hasText("잠겼습니다 · 메뉴의 자물쇠를 길게 누르면 풀립니다"))
        shot("book-locked")

        // 짧게 누르면 푸는 법만 다시 알린다.
        compose.waitUntil(10_000) { !has(hasText("잠겼습니다", substring = true)) }
        click(unlockButton)
        waitFor(hasText("잠겼습니다 · 메뉴의 자물쇠를 길게 누르면 풀립니다"))
        assertTrue(pinned(), "짧게 눌렀는데 풀렸다")
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
            compose.mainClock.advanceTimeBy(1_500)
            node(unlockButton).performTouchInput { up() }
            compose.mainClock.advanceTimeBy(500)
            assertTrue(pinned(), "1.5초 만에 떼었는데 풀렸다")
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
        waitFor(hasText("잠금을 풀었습니다"))
        onActivity { assertTrue(shadowOf(it).showWhenLocked, "풀자마자 잠금 화면 위 표시를 거뒀다 — 휴대폰 잠금 화면이 책을 덮는다") }
        // 홈으로 나가거나 화면이 꺼지면(화면이 멈춤) 거둔다 — 남기면 꺼졌다 켜진 화면에서 PIN 없이 책이 보인다.
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        onActivity { assertFalse(shadowOf(it).showWhenLocked, "화면이 멈췄는데 잠금 화면 위 표시가 남았다") }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    @Test
    fun `declining the android dialog leaves the book unlocked`() {
        launch()
        // 확인 창에서 "취소": 고정이 걸리지 않는다.
        app.container.readLock.pinner = object : ReadLockController.Pinner by ReadLockController.PlatformPinner {
            override fun start(activity: android.app.Activity) = Unit
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
            ticking { !has(hasText("잠겼습니다", substring = true)) }
            shot("webtoon-banner")

            click(hasText("다시 잠그기"))
            frames(100)
            assertTrue(pinned(), "다시 잠그기가 고정을 다시 걸지 않았다")
            onActivity { it.onWindowFocusChanged(false) }
            onActivity { it.onWindowFocusChanged(true) }
            ticking { has(hasContentDescription("자동 스크롤 멈춤")) }
            ticking { !has(hasText("다시 잠그기")) }
            assertTrue(has(hasText("잠겼습니다 · 메뉴의 자물쇠를 길게 누르면 풀립니다")), "다시 잠근 뒤 안내가 없다")
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
        waitFor(hasText("잠겼습니다 · 메뉴의 자물쇠를 길게 누르면 풀립니다"))
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
