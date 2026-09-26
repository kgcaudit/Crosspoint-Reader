package io.github.kgcaudit.reader.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.listen.ListenHub
import io.github.kgcaudit.reader.listen.ListenKit
import io.github.kgcaudit.reader.listen.ListenService
import io.github.kgcaudit.reader.listen.Speaker
import io.github.kgcaudit.reader.listen.SpeakerEvents
import io.github.kgcaudit.reader.listen.VoiceChoice
import io.github.kgcaudit.reader.ui.design.AutoTurn
import io.github.kgcaudit.reader.ui.design.CpMetrics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 4단계(0.16.0) 듣기 · 자동 넘김. 시험 환경에는 음성 엔진이 없어 [FakeSpeaker] 가 "읽기 시작 · 끝" 을 흉내 낸다 —
 * 사람이 듣는 순서(문장 → 다음 문장 → 쪽 넘김 → 다음 장)를 그 알림으로 몰아 본다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ListenAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()
    private val density get() = compose.activity.resources.displayMetrics.density

    /** 엔진 흉내. [queue] 는 엔진이 줄 세운 글, 맨 앞이 지금 읽는 것. */
    class FakeSpeaker(val engine: String?, private val works: Boolean = true) : Speaker {
        val queue = ArrayDeque<Pair<String, String>>()
        val spoken = ArrayList<String>()
        var lastRate = 1f
        var lastVoice: String? = null
        var stops = 0
        override var events: SpeakerEvents? = null
        override suspend fun prepare() = works
        override fun speak(id: String, text: String, flush: Boolean) {
            if (flush) queue.clear()
            queue.addLast(id to text)
            spoken += text
            if (queue.size == 1) events?.onStart(id)
        }
        override fun stop() { queue.clear(); stops++ }
        override fun setRate(rate: Float) { lastRate = rate }
        override fun setVoice(voice: String?) { lastVoice = voice }
        override fun shutdown() { queue.clear() }

        /** 지금 문장을 다 읽었다. 줄 선 다음 문장이 시작된다. */
        fun finish() {
            val (id, _) = queue.removeFirstOrNull() ?: return
            events?.onDone(id)
            queue.firstOrNull()?.let { events?.onStart(it.first) }
        }

        val current: String? get() = queue.firstOrNull()?.second
    }

    private val speakers = ArrayList<FakeSpeaker>()
    private var engineWorks = true
    private val speaker get() = speakers.last()

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/듣기 책.epub").writeBytes(book())
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
        app.container.listenKit = ListenKit(
            speaker = { engine -> FakeSpeaker(engine, engineWorks).also { speakers += it } },
            voices = {
                listOf(
                    VoiceChoice("com.samsung.SMT", "Samsung TTS", "ko-1", "한국어 1", false),
                    VoiceChoice("com.samsung.SMT", "Samsung TTS", "ko-2", "한국어 2", false),
                    VoiceChoice("com.google.tts", "Google 음성", "ko-a", "한국어 1", true),
                )
            },
        )
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { ListenHub.detach() }
    }

    /** 첫 장: 두 문장짜리 문단이 여럿(쪽이 셋쯤). 둘째 장은 두 문장. */
    private fun book(): ByteArray {
        val body = (1..16).joinToString("") { "<p>보아 구렁이 이야기 $it 번째 문단이다. 어른들은 모자라고 했다.</p>" }
        val files = mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>듣기 책</dc:title></metadata>
                  <manifest>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/ch1.xhtml" to """<html><body><p>첫 문장이다. 둘째 문장이다.</p><p>셋째 문장이다.</p>$body</body></html>""",
            "OEBPS/ch2.xhtml" to """<html><body><p>둘째 장의 첫 문장이다. 책의 끝 문장이다.</p></body></html>""",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (n, b) -> zip.putNextEntry(ZipEntry(n)); zip.write(b.toByteArray()); zip.closeEntry() } }
        return out.toByteArray()
    }

    private fun openWith(prefs: ReaderPrefs = ReaderPrefs()) {
        app.container.prefs.save(prefs)
        compose.activityRule.scenario.recreate()
        waitFor(hasText("듣기 책", substring = true))
        node(hasText("듣기 책", substring = true)).performClick()
        waitFor(hasText("1 / ", substring = true))
    }

    private fun startListening() {
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("듣기"))
        shot("85-bar-listen")
        node(hasContentDescription("듣기")).performClick()
        waitFor(hasContentDescription("듣기 조종판"))
        // 드물게(전체 점검에서만, 여러 번 돌려도 재현 안 됨) 여기서 엔진이 말을 시작하지 않은 채 남는다. 다음에 걸리면
        // 원인을 볼 수 있게 그때의 엔진 · 듣기 상태를 실패 문구에 남긴다.
        runCatching { compose.waitUntil(5_000) { speakers.isNotEmpty() && speaker.current != null } }.onFailure {
            throw AssertionError("listening never started: speakers=${speakers.size} queues=${speakers.map { it.queue.toList() }} " +
                "stops=${speakers.map { it.stops }} state=${ListenHub.current.value?.state?.value}", it)
        }
        waitFor(hasContentDescription("멈춤"))
    }

    /** 엔진 알림은 메인 스레드에서 온다(실제 엔진은 바인더 스레드 → 듣기가 메인으로 옮긴다). */
    private fun finish(times: Int = 1) = repeat(times) {
        compose.runOnUiThread { speaker.finish() }
        // 쪽 따라가기는 조판 스레드에서 늦게 끝난다. 틈을 주지 않으면 쪽이 바뀌는 순간을 지나쳐 다음 장까지 가 버린다.
        // 시험의 화면 시계는 저절로 흐르지 않는다 — 돌려야 새 쪽이 그려진다.
        Thread.sleep(40)
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    @Test
    fun `the headphones read the page aloud sentence by sentence and the page follows`() {
        openWith()
        startListening()
        // 보이는 쪽의 첫 문장부터. 다음 문장을 미리 줄 세워 둔다(문장 사이에 끊기지 않게).
        assertEquals("첫 문장이다.", speaker.current)
        assertEquals(listOf("첫 문장이다.", "둘째 문장이다."), speaker.spoken)
        // 지금 문장은 강조색 옅은 칠(L3).
        compose.waitUntil(5_000) { tinted(page(), 0.18f) }
        shot("80-listening")

        finish()
        assertEquals("둘째 문장이다.", speaker.current)
        // 문단이 바뀌어도(구분자 없이 이어진 장 텍스트) 문장이 붙지 않는다.
        finish()
        assertEquals("셋째 문장이다.", speaker.current)

        // 쪽 끝을 넘으면 쪽이 따라 넘어간다.
        var turns = 0
        while (!hasNode(hasText("2 / ", substring = true)) && turns++ < 40) finish()
        waitFor(hasText("2 / ", substring = true))
        assertTrue(speaker.current!!.startsWith("보아 구렁이") || speaker.current!!.startsWith("어른들은"))
        // 쪽이 넘어가면 진도도 저장된다 — 화면을 끈 채 듣다 앱을 닫아도 들은 곳에서 연다.
        val saved = runBlocking { app.container.data.progress.get(bookId()) }
        assertNotNull(saved)
        assertTrue((saved.locator as io.github.kgcaudit.reader.document.Locator.Reflow).charOffset > 0)
    }

    @Test
    fun `pause, next sentence and speed are on the small player`() {
        openWith()
        startListening()
        waitFor(hasContentDescription("멈춤"))
        node(hasContentDescription("멈춤")).performClick()
        compose.waitForIdle()
        assertTrue(speaker.stops > 0)
        assertEquals(null, speaker.current)
        waitFor(hasContentDescription("읽기"))

        // 멈춘 채 다음 문장: 읽지 않고 자리만 옮긴다. 다시 읽으면 그 문장부터.
        val before = speaker.spoken.size
        node(hasContentDescription("다음 문장")).performClick()
        compose.waitForIdle()
        assertEquals(before, speaker.spoken.size)
        node(hasContentDescription("읽기")).performClick()
        compose.waitUntil(5_000) { speaker.current != null }
        assertEquals("둘째 문장이다.", speaker.current)

        // 빠르기: 조종판의 1.0× → 듣기 판. + 를 누르면 1.1×, 엔진 · 설정에 들어간다.
        node(hasContentDescription("듣기 설정")).performClick()
        waitFor(hasText("읽는 속도"))
        shot("81-listen-sheet")
        node(hasContentDescription("읽는 속도 늘리기")).performClick()
        compose.waitForIdle()
        assertEquals(1.1f, speaker.lastRate)
        assertEquals(1.1f, app.container.prefs.load().listen.rate)
        waitFor(hasText("1.1×"))
    }

    @Test
    fun `word pause setting joins words for the engine only, applies at once and is kept`() {
        openWith()
        startListening()
        assertEquals("첫 문장이다.", speaker.current)
        node(hasContentDescription("듣기 설정")).performClick()
        waitFor(hasText("어절 쉼 줄이기"))
        // 선택지는 일반 · 속독 둘뿐이고, 따로 여는 비교 판은 없다 — 두 단추를 번갈아 누르면 지금 문장이 바로 다시
        // 들려 그것으로 견준다("강하게" · "비교 들어 보기" 는 들어 보고 뺐다, 2026-09-26).
        assertFalse(hasNode(hasText("강하게")))
        assertFalse(hasNode(hasText("비교 들어 보기")))
        shot("86-listen-join-setting")

        // 속독: 엔진에는 "첫문장이다." 로 넘긴다(관형사 "첫" 을 붙임). 지금 문장부터 바로 다시 읽는다.
        node(hasText("속독")).performClick()
        compose.waitUntil(5_000) { speaker.current == "첫문장이다." }
        assertEquals(io.github.kgcaudit.reader.layout.book.WordJoin.Light, app.container.prefs.load().listen.join)
        // 붙인 글은 엔진에만 간다 — 지금 문장(칠 · 잠금 화면 카드에 보이는 글)은 원문 그대로다.
        assertEquals("첫 문장이다.", ListenHub.current.value!!.state.value.sentenceText)

        // 일반으로 되돌리면 같은 문장을 원문 그대로 다시 읽고, 그 값이 저장된다.
        node(hasText("일반")).performClick()
        compose.waitUntil(5_000) { speaker.current == "첫 문장이다." }
        compose.waitUntil(5_000) { app.container.prefs.load().listen.join == io.github.kgcaudit.reader.layout.book.WordJoin.Off }
        assertTrue(ListenHub.current.value!!.state.value.playing, "세기를 바꿔도 읽기는 이어진다")
    }

    @Test
    fun `turning the page by hand moves the reading there`() {
        openWith()
        startListening()
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.9f)) }
        waitFor(hasText("2 / ", substring = true))
        // 넘긴 쪽의 첫 문장부터 읽는다 — 넘긴 쪽을 보면서 앞 쪽 문장을 듣지 않게.
        compose.waitUntil(5_000) { speaker.current?.let { it != "첫 문장이다." && it != "둘째 문장이다." } == true }
        val onPage2 = speaker.current!!
        assertTrue(onPage2.startsWith("보아 구렁이") || onPage2.startsWith("어른들은"), onPage2)
        // 듣기가 옮겨 간 뒤에도 사람이 넘긴 쪽에 머문다. 넘긴 쪽이 문장 한가운데서 시작하면 그 문장은 앞 쪽에서
        // 시작하는데, 듣기가 그 문장을 따라가며 쪽을 앞으로 되돌렸다(전체 점검에서만 가끔 "2 / " 를 못 보고 실패).
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue(hasNode(hasText("2 / ", substring = true)), "넘긴 쪽에서 앞 쪽으로 되돌아갔다")
    }

    @Test
    fun `without a speech engine it says so and nothing starts`() {
        engineWorks = false
        openWith()
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("듣기"))
        node(hasContentDescription("듣기")).performClick()
        waitFor(hasText("음성 엔진을 찾지 못했습니다", substring = true))
        assertFalse(hasNode(hasContentDescription("듣기 조종판")))
    }

    @Test
    fun `the lock screen card shows the book and its buttons control the same reading`() {
        openWith()
        startListening()
        // 듣기가 켜지면 앞에 선 서비스가 뜬다(화면을 꺼도 계속 읽는다, L6).
        val started = shadowOf(app).nextStartedService
        assertEquals(ListenService::class.java.name, started?.component?.className)

        val controller = Robolectric.buildService(ListenService::class.java).create()
        val service = controller.get()
        controller.startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val card = shadowOf(service).lastForegroundNotification
        assertNotNull(card)
        assertEquals("듣기 책", card.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("첫 문장이다.", card.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
        assertEquals(4, card.actions.size)

        // 잠금 화면의 멈춤 · 다음 문장은 화면의 조종판과 같은 듣기를 움직인다.
        controller.withIntent(Intent(service, ListenService::class.java).setAction(ListenService.ACTION_TOGGLE)).startCommand(0, 2)
        compose.waitForIdle()
        waitFor(hasContentDescription("읽기"))
        controller.withIntent(Intent(service, ListenService::class.java).setAction(ListenService.ACTION_TOGGLE)).startCommand(0, 3)
        compose.waitForIdle()
        waitFor(hasContentDescription("멈춤"))
        controller.withIntent(Intent(service, ListenService::class.java).setAction(ListenService.ACTION_NEXT)).startCommand(0, 4)
        compose.waitUntil(5_000) { speaker.current == "둘째 문장이다." }

        // ✕: 듣기가 끝나고 조종판도 사라진다.
        controller.withIntent(Intent(service, ListenService::class.java).setAction(ListenService.ACTION_CLOSE)).startCommand(0, 5)
        compose.waitUntil(5_000) { !hasNode(hasContentDescription("듣기 조종판")) }
        controller.destroy()
    }

    @Test
    fun `the chapter end timer stops before the next chapter and the book end is announced`() {
        openWith()
        startListening()
        node(hasContentDescription("듣기 설정")).performClick()
        waitFor(hasText("장 끝"))
        node(hasText("장 끝")).performClick()
        node(hasText("닫기")).performClick()
        // 장의 마지막 문장까지 읽는다.
        var guard = 0
        while (speaker.current != null && guard++ < 60) finish()
        // 둘째 장을 읽기 시작하지 않고 멈춘다.
        assertFalse(speaker.spoken.any { it.startsWith("둘째 장의") }, speaker.spoken.takeLast(3).toString())
        waitFor(hasContentDescription("읽기"))

        // 다시 읽으면 둘째 장부터, 그리고 책 끝에서 알린다.
        node(hasContentDescription("읽기")).performClick()
        compose.waitUntil(5_000) { speaker.current == "둘째 장의 첫 문장이다." }
        finish(2)
        waitFor(hasText("책을 끝까지 읽었습니다"))
    }

    @Test
    fun `a sleep timer stops the reading after its minutes`() {
        openWith()
        startListening()
        node(hasContentDescription("듣기 설정")).performClick()
        waitFor(hasText("30분"))
        node(hasText("30분")).performClick()
        node(hasText("닫기")).performClick()
        // 조종판에 남은 시간.
        waitFor(hasText("30분"))
        compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(29)) }
        compose.waitForIdle()
        assertTrue(speaker.current != null, "29분에 멈췄다")
        compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2)) }
        compose.waitForIdle()
        waitFor(hasContentDescription("읽기"))
        assertEquals(null, speaker.current)
    }

    @Test
    fun `voices are grouped by engine and indented under it, and picking another engine keeps reading`() {
        openWith()
        startListening()
        node(hasContentDescription("듣기 설정")).performClick()
        waitFor(hasText("휴대폰 기본"))
        node(hasText("목소리")).performClick()
        waitFor(hasText("Samsung TTS"))
        shot("82-voices")
        // 위계(UI 규칙 1): 엔진 이름(글자만)은 gutter, 목소리 줄의 동그라미는 한 단 안쪽, 글자는 거기서 childIndent.
        val m = CpMetrics()
        val section = node(hasText("Samsung TTS")).fetchSemanticsNode().boundsInRoot
        val row = compose.onAllNodes(hasText("한국어 2"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        assertNear(m.gutter.value, section.left / density, "엔진 이름")
        assertNear((m.gutter + m.levelIndent + m.childIndent).value, row.left / density, "목소리 줄 글자")
        assertTrue(hasNode(hasText("한국어 1 (내려받기 필요)")))

        // 들어 보기는 고르지 않고 소리만.
        node(hasContentDescription("한국어 2 들어 보기")).performClick()
        compose.waitUntil(5_000) { speakers.any { it.spoken.any { t -> t.startsWith("안녕하세요") } } }
        assertEquals(null, app.container.prefs.load().listen.voice)

        // 다른 엔진의 목소리를 고르면 그 엔진으로 다시 열고, 듣던 문장부터 읽는다.
        node(hasText("한국어 1 (내려받기 필요)")).performClick()
        waitTicking { speaker.engine == "com.google.tts" && speaker.current != null }
        assertEquals("첫 문장이다.", speaker.current)
        assertEquals("ko-a", speaker.lastVoice)
        assertEquals("Google 음성 · 한국어 1", app.container.prefs.load().listen.voiceLabel)
    }

    @Test
    fun `auto turn goes to the next page after its seconds and rests while the menu is open`() {
        openWith()
        // 모든 보기 설정 › 넘기기 › 자동 넘김 15초. 메뉴가 열린 동안은 세지 않는다.
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasText("보기"))
        node(hasText("보기")).performClick()
        waitFor(hasText("모든 보기 설정"))
        node(hasText("모든 보기 설정")).performClick()
        waitFor(hasText("자동 넘김"))
        node(hasText("15초")).performClick()
        compose.waitForIdle()
        assertEquals(AutoTurn.S15, app.container.prefs.load().screen.autoTurn)
        shot("84-auto-turn-setting")
        repeat(3) { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
        waitFor(hasText("다음 쪽까지 15초", substring = true))
        assertTrue(hasNode(hasText("1 / ", substring = true)))
        shot("83-auto-turn")
        // 여기부터는 시계를 손으로만 돌린다(저절로 흐르면 언제 넘어갈지 셀 수 없다).
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertTrue(hasNode(hasText("1 / ", substring = true)), "15초 전에 넘어갔다")
        compose.mainClock.advanceTimeBy(6_000)
        waitTicking { hasNode(hasText("2 / ", substring = true)) }
        // 새 쪽에서 다시 센다.
        waitTicking { hasNode(hasText("다음 쪽까지 1", substring = true)) }

        // 메뉴가 열려 있으면 넘기지 않는다.
        compose.onRoot().performTouchInput { click(center) }
        waitTicking { hasNode(hasText("독서노트")) }
        compose.mainClock.advanceTimeBy(30_000)
        compose.waitForIdle()
        assertTrue(hasNode(hasText("2 / ", substring = true)), "메뉴가 열린 채 넘어갔다")
        compose.onRoot().performTouchInput { click(Offset(width / 2f, height * 0.25f)) }

        // ✕ 로 끄면 이번에는 더 넘기지 않는다(설정은 그대로).
        waitTicking { hasNode(hasContentDescription("자동 넘김 끄기")) }
        node(hasContentDescription("자동 넘김 끄기")).performClick()
        compose.mainClock.advanceTimeBy(40_000)
        compose.waitForIdle()
        assertTrue(hasNode(hasText("2 / ", substring = true)), "끈 뒤에도 넘어갔다")
        assertFalse(hasNode(hasText("다음 쪽까지", substring = true)))
        assertEquals(AutoTurn.S15, app.container.prefs.load().screen.autoTurn)
        compose.mainClock.autoAdvance = true
    }

    // ── 도구 ────────────────────────────────────────────────────────

    private fun bookId() = runBlocking {
        app.container.data.library.books().first().first { it.displayName.startsWith("듣기 책") }.id
    }

    /**
     * 시험의 화면 시계는 저절로 흐르지 않는다. 조판 스레드가 끝낸 일(쪽 넘김)이 화면에 오려면 시계를 조금씩 돌려야
     * 한다 — waitUntil 만으로는 새 쪽이 그려지지 않는다.
     */
    private fun waitTicking(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) {
                throw AssertionError("조건이 끝내 맞지 않았다: speakers=${speakers.map { it.engine to it.spoken.takeLast(2) }}")
            }
            Thread.sleep(20)
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 1.5f, "$what: ${expected}dp 에서 시작해야 하는데 ${actual}dp")

    /** 지면에 강조색 [alpha] 칠이 12점 넘게 이어진 띠로 있는가. 지면 색은 왼쪽 여백에서 읽는다(테마마다 다르다). */
    private fun tinted(bitmap: Bitmap, alpha: Float): Boolean {
        val accent = Color(0xFFB95B3B)
        val paper = Color(bitmap.getPixel((8 * density).toInt(), bitmap.height / 2))
        val want = accent.copy(alpha = alpha).compositeOver(paper).toArgb()
        for (y in (bitmap.height * 0.03).toInt() until (bitmap.height * 0.9).toInt() step 2) {
            var run = 0
            for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val close = abs(android.graphics.Color.red(c) - android.graphics.Color.red(want)) <= 6 &&
                    abs(android.graphics.Color.green(c) - android.graphics.Color.green(want)) <= 6 &&
                    abs(android.graphics.Color.blue(c) - android.graphics.Color.blue(want)) <= 6
                run = if (close) run + 1 else 0
                if (run >= 12) return true
            }
        }
        return false
    }

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { hasNode(matcher) }
    }

    private fun page(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        File(shots, "$name.png").outputStream().use { page().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
