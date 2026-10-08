package io.github.kgcaudit.reader.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.TimeItem
import io.github.kgcaudit.reader.data.TimeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertTrue

/**
 * 독서 기록 화면(0.51.0). 서재의 더 보기에서 열리고, 적힌 시간을 타일 · 달력 · 목록으로 보인다. 오늘은 2026년 10월 7일(수요일)로
 * 정해 둔다 — 주의 시작(일요일)과 해의 경계를 날짜에 기대지 않고 잰다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class ReadingStatsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()
    private val today = LocalDate.of(2026, 10, 7)

    @Before
    fun setUp() {
        app.container.today = { today }
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "데미안.epub").writeBytes(SampleBooks.epub())
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
        // 화면은 폴더를 등록하기 전에 떴다 — 다시 띄워 폴더를 훑게 한다.
        compose.activityRule.scenario.recreate()
    }

    private fun at(day: LocalDate) = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun seed(item: TimeItem, id: String, mode: TimeMode, minutes: Int, day: LocalDate) =
        runBlocking { app.container.data.readingTime.record(item, id, mode, minutes * 60_000L, at(day)) }

    private fun openStats() {
        compose.libraryMenu("독서 기록")
        waitFor(hasText("이 휴대폰에서 읽은 시간"))
    }

    @Test
    fun `the more menu opens the records and back returns to the library`() {
        openStats()
        waitFor(hasText("아직 읽은 기록이 없습니다", substring = true))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasContentDescription("더 보기"))
    }

    @Test
    fun `tiles, calendar and lists show the recorded time`() {
        // 서재를 한 번 훑어 책을 얻는다.
        compose.waitUntil(30_000) { runBlocking { app.container.data.library.books().first() }.isNotEmpty() }
        val book = runBlocking { app.container.data.library.books().first() }.single()
        seed(TimeItem.BOOK, book.id.value, TimeMode.READ, 42, today)
        seed(TimeItem.BOOK, book.id.value, TimeMode.READ, 18, today.minusDays(2)) // 5일(월)
        seed(TimeItem.BOOK, book.id.value, TimeMode.READ, 40, today.minusDays(3)) // 4일(일) — 이번 주의 첫날
        seed(TimeItem.BOOK, book.id.value, TimeMode.READ, 25, today.minusDays(5)) // 2일(금) — 지난주
        seed(TimeItem.BOOK, book.id.value, TimeMode.READ, 30, LocalDate.of(2025, 12, 31)) // 작년
        seed(TimeItem.BOOK, book.id.value, TimeMode.LISTEN, 180, today)
        runBlocking { app.container.data.library.setFinished(book.id, at(LocalDate.of(2026, 10, 3)), at(today)) }

        openStats()
        // 오늘 42분. 이번 주는 일요일(4일)부터: 40 + 18 + 42 = 100분. 월요일부터 세면 60분(1.0 시간)이 된다.
        waitFor(hasContentDescription("오늘 42 분"))
        waitFor(hasContentDescription("이번 주 1.6 시간"))
        // 올해: 작년 12월 31일은 빼고, 들은 3시간도 빼고 125분.
        waitFor(hasContentDescription("올해 2.0 시간"))
        waitFor(hasContentDescription("10월 2일 25분"))
        waitFor(hasContentDescription("10월 6일 안 읽음"))
        waitFor(hasText("4일 읽음 · 2시간 5분"))
        // 다 읽은 책: 시간은 그 책의 모든 읽은 시간(작년 포함).
        waitFor(hasText("올해 다 읽은 것 · 책 1"))
        waitFor(hasText("10월 3일"))
        waitFor(hasText("2시간 35분", substring = true))
        // 들은 시간은 따로 한 줄로.
        waitFor(hasText("들은 시간 3시간", substring = true))

        // 앞 달로 가면 9월 — 읽은 날이 없다. 다음 달(11월)로는 갈 수 없다.
        node(hasContentDescription("앞 달")).performClick()
        waitFor(hasText("2026년 9월"))
        node(hasContentDescription("다음 달")).performClick()
        waitFor(hasText("2026년 10월"))
        node(hasContentDescription("다음 달")).performClick()
        compose.waitForIdle()
        assertTrue(hasNode(hasText("2026년 10월")), "이번 달 뒤로 넘어갔다")
    }

    @Test
    fun `only time with no finished book still shows the screen, not the empty message`() {
        seed(TimeItem.COMIC, "content://없는/권.cbz", TimeMode.READ, 5, today)
        openStats()
        waitFor(hasContentDescription("오늘 5 분"))
        assertTrue(!hasNode(hasText("아직 읽은 기록이 없습니다", substring = true)))
        waitFor(hasText("올해 다 읽은 책이 아직 없습니다"))
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun `a wide screen puts the lists in a right pane beside the tiles and calendar`() {
        seed(TimeItem.BOOK, "content://x/a.epub", TimeMode.READ, 12, today)
        openStats()
        waitFor(hasContentDescription("오늘 12 분"))
        waitFor(hasText("올해 다 읽은 것"))
        val density = compose.activity.resources.displayMetrics.density
        val tile = node(hasContentDescription("오늘 12 분")).fetchSemanticsNode().boundsInRoot
        val list = node(hasText("올해 다 읽은 것")).fetchSemanticsNode().boundsInRoot
        // 목록은 왼쪽 판(320dp) 오른쪽에 서고, 타일과 같은 높이에서 시작한다 — 휴대폰 화면을 늘여 아래로 이어 붙이지 않는다.
        assertTrue(list.left / density >= 320f, "목록이 왼쪽 판 안에 있다: ${list.left / density}dp")
        assertTrue(tile.right / density <= 320f, "타일이 왼쪽 판을 넘었다: ${tile.right / density}dp")
        assertTrue(list.top < tile.bottom + 40 * density, "목록이 타일 아래로 밀렸다")
    }

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0]

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { hasNode(matcher) }
    }
}
