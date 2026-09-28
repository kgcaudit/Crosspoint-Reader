package io.github.kgcaudit.reader.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryScanTest {

    private val scope = TestScope(StandardTestDispatcher())

    @Test
    fun `a folder added during a scan is scanned right after it`() {
        // 훑는 중에 폴더를 더했다. 첫 판은 "이미 훑는 중" 이라 요청을 버려, 새 폴더의 책이 새로고침 전까지 안 보였다.
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val scan = LibraryScan(scope) { runs++; if (runs == 1) gate.await(); true }
        scan.request()
        scope.runCurrent()
        assertTrue(scan.running.value)
        repeat(3) { scan.request() }   // 여러 번 청해도 한 번만 더 돈다
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(2, runs)
        assertFalse(scan.running.value)
    }

    @Test
    fun `a scan that fails is counted so the library can say so and the next request still runs`() {
        // 망가뜨린 경우: 훑기가 예외로 끝났다. 진행 막대가 멈춘 채 남거나 다음 요청이 무시되면 안 된다.
        var runs = 0
        val scan = LibraryScan(scope) { runs++; if (runs == 1) error("boom") else false }
        scan.request()
        scope.runCurrent()
        assertFalse(scan.running.value)
        assertEquals(1, scan.incomplete.value)
        scan.request()
        scope.runCurrent()
        assertEquals(2, runs)
        assertEquals(2, scan.incomplete.value)
    }
}
