package io.github.kgcaudit.reader.app

import android.app.Notification
import android.content.Intent
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.listen.ListenHub
import io.github.kgcaudit.reader.listen.ListenKit
import io.github.kgcaudit.reader.listen.ListenService
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * 듣기 알림을 누르면 듣던 책으로 돌아온다(0.19.1). 0.19.0 까지는 화면이 새로 만들어진 뒤(작업 목록에서 밀어 닫음 ·
 * 시스템이 화면만 거둠) 알림을 누르면 라이브러리가 떴다 — 듣기는 계속 읽는데 그 책은 화면에 없었다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ListenReturnTest {

    @get:Rule
    val compose = createEmptyComposeRule(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val speakers = ArrayList<ListenAppTest.FakeSpeaker>()

    @Before
    fun setUp() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
        app.container.listenKit = ListenKit(
            speaker = { engine -> ListenAppTest.FakeSpeaker(engine).also { speakers += it } },
            voices = { emptyList() },
        )
    }

    @After
    fun tearDown() {
        compose.runOnIdle { ListenHub.detach() }
    }

    private fun launch(intent: Intent): ActivityScenario<MainActivity> =
        ActivityScenario.launch(intent.setClass(app, MainActivity::class.java))

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) = compose.waitUntil(timeoutMs) { hasNode(matcher) }

    /** 라이브러리에서 책을 열고 듣기를 켠다. */
    private fun listenFromLibrary(): ActivityScenario<MainActivity> {
        val first = launch(Intent(Intent.ACTION_MAIN))
        waitFor(hasText("어린 왕자", substring = true))
        compose.onAllNodes(hasText("어린 왕자", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("1 / ", substring = true))
        compose.onRoot().performTouchInput { click(center) }
        waitFor(hasContentDescription("듣기"))
        compose.onAllNodes(hasContentDescription("듣기"), useUnmergedTree = true)[0].performClick()
        waitFor(hasContentDescription("듣기 조종판"))
        // 드물게(전체 점검에서만, 여러 번 돌려도 재현 안 됨) 여기서 엔진이 말을 시작하지 않은 채 남는다. 다음에 걸리면
        // 원인을 볼 수 있게 그때의 엔진 · 듣기 상태를 실패 문구에 남긴다.
        runCatching { compose.waitUntil(5_000) { speakers.isNotEmpty() && speakers.last().current != null } }.onFailure {
            throw AssertionError("listening never started: speakers=${speakers.size} queues=${speakers.map { it.queue.toList() }} " +
                "stops=${speakers.map { it.stops }} state=${ListenHub.current.value?.state?.value}", it)
        }
        return first
    }

    /** 잠금 화면 · 알림 창의 카드가 누르면 보내는 인텐트(앱을 여는 인텐트). */
    private fun cardIntent(): Intent {
        val controller = Robolectric.buildService(ListenService::class.java).create()
        controller.startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val card: Notification = assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        val intent = shadowOf(assertNotNull(card.contentIntent)).savedIntent
        controller.destroy()
        return intent
    }

    @Test
    fun `tapping the listening card after the screen was swept away returns to the book being read`() {
        val first = listenFromLibrary()
        val listening = assertNotNull(ListenHub.current.value)
        val card = cardIntent()

        // 작업 목록에서 밀어 닫았다: 화면은 사라지고(저장된 상태도 없다) 듣기만 남는다.
        first.moveToState(Lifecycle.State.DESTROYED)
        assertSame(listening, ListenHub.current.value, "듣기는 화면이 사라져도 계속 읽는다")

        launch(card)
        // 라이브러리가 아니라 듣던 책이, 같은 듣기가 붙은 채로 뜬다(조종판이 보인다).
        waitFor(hasText("1 / ", substring = true))
        waitFor(hasContentDescription("듣기 조종판"))
        assertSame(listening, ListenHub.current.value)
        assertEquals(0, app.container.dropped, "듣는 책은 닫지 않는다 — 닫으면 듣기가 닫힌 파일을 읽는다")
    }

    @Test
    fun `a file sent from another app while listening stops the listening and says so`() {
        // 내 파일 · 다른 앱에서 파일이 왔다: 읽던 책은 닫히고 받은 파일이 열린다. 그 책의 듣기도 끝나고 알린다 —
        // 두면 닫힌 책을 계속 읽어, 멈출 곳이 알림 카드뿐이었다(0.19.0–0.20.1).
        val first = listenFromLibrary()
        val listening = assertNotNull(ListenHub.current.value)
        File(app.cacheDir, "sdcard/Downloads").apply { mkdirs() }.resolve("받은 책.epub").writeBytes(SampleBooks.epub())
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(DocumentsContract.buildDocumentUri(FolderProvider.AUTHORITY, "Downloads/받은 책.epub"), "application/epub+zip")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        compose.seeBriefly(hasText("‘어린 왕자’ 듣기를 멈췄습니다")) { first.onActivity { it.onNewIntent(view) } }
        assertEquals(null, ListenHub.current.value)
        assertFalse(listening.state.value.active, "닫힌 책의 듣기가 아직 켜져 있다")
        // 받은 파일은 듣기 없이 열린다.
        waitFor(hasText("1 / ", substring = true))
        assertFalse(hasNode(hasContentDescription("듣기 조종판")))
    }

    @Test
    fun `once listening has ended the card no longer holds the book and the app opens normally`() {
        // 망가뜨린 경우: 듣기를 끝낸 뒤 늦게 도착한 알림 누름. 끝난 듣기의 책을 되살려 붙들지 않는다.
        val first = listenFromLibrary()
        val card = cardIntent()
        compose.runOnIdle { ListenHub.detach() }
        first.moveToState(Lifecycle.State.DESTROYED)

        launch(card)
        waitFor(hasText("어린 왕자", substring = true))
        assertFalse(hasNode(hasText("1 / ", substring = true)), "듣기가 끝난 책은 되찾지 않는다")
        assertEquals(1, app.container.dropped, "되찾지 않은 책은 닫고 놓는다")
    }
}
