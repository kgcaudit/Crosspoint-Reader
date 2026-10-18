package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.app.ReadLockMachine.Effect
import io.github.kgcaudit.reader.app.ReadLockMachine.Phase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 읽기 잠금의 판단(벨 · 확인 창 · 시스템 동작의 차례). 기기 없이. */
class ReadLockMachineTest {

    /** 잠그기 → 확인 창(초점 잃음) → "확인"(초점 되찾음, 고정됨). */
    private fun locked(): ReadLockMachine = ReadLockMachine().apply {
        assertEquals(listOf(Effect.Pin), request())
        focus(has = false, pinned = false)
        focus(has = true, pinned = true)
        assertEquals(Phase.On, phase)
    }

    @Test
    fun `locking says locked only after android confirms the pin`() {
        val m = ReadLockMachine()
        assertEquals(listOf(Effect.Pin), m.request())
        // 확인 창이 떠 있는 동안은 잠금 중이 아니다.
        assertEquals(Phase.Asking, m.phase)
        assertEquals(emptyList(), m.observed(pinned = false))
        assertEquals(Phase.Asking, m.phase, "창이 아직 떠 있는데 거절로 봤다")
        m.focus(has = false, pinned = false)
        assertEquals(listOf(Effect.QuietOn, Effect.ShowLocked), m.focus(has = true, pinned = true))
        assertEquals(Phase.On, m.phase)
    }

    @Test
    fun `declining the android dialog leaves nothing locked`() {
        val m = ReadLockMachine()
        m.request()
        m.focus(has = false, pinned = false)
        // 창이 닫혔는데 고정이 아니다: 거절. 방해 금지도 걸지 않는다.
        assertEquals(emptyList(), m.focus(has = true, pinned = false))
        assertEquals(Phase.Off, m.phase)
        // 다시 누르면 다시 묻는다.
        assertEquals(listOf(Effect.Pin), m.request())
    }

    @Test
    fun `a confirmation seen by the poll locks even when the dialog never took focus`() {
        val m = ReadLockMachine()
        m.request()
        assertEquals(listOf(Effect.QuietOn, Effect.ShowLocked), m.observed(pinned = true))
        assertEquals(Phase.On, m.phase)
    }

    @Test
    fun `a ring while locked unpins first, restores quiet and pauses auto scrolling`() {
        val m = locked()
        assertEquals(listOf(Effect.Unpin, Effect.QuietOff, Effect.PauseAuto), m.callStarted())
        assertEquals(Phase.CallFreed, m.phase)
        // 벨이 울리는 동안은 띠를 보이지 않는다 — "다시 잠그기" 를 누르면 통화 화면이 막힌다.
        assertFalse(m.showsBanner)
        m.callEnded()
        assertTrue(m.showsBanner)
    }

    @Test
    fun `an off hook call without ringing also unlocks`() {
        // 블루투스 · 음성 비서로 건 전화: 벨 없이 곧바로 통화.
        val m = locked()
        assertEquals(listOf(Effect.Unpin, Effect.QuietOff, Effect.PauseAuto), m.callStarted())
        assertEquals(Phase.CallFreed, m.phase)
    }

    @Test
    fun `a second ring does nothing more`() {
        val m = locked()
        m.callStarted()
        m.callEnded()
        // 다시 울려도 이미 풀었다 — 또 멈추면 처음 멈춘 것을 잊어 "다시 잠그기" 가 잇지 못한다.
        assertEquals(emptyList(), m.callStarted())
        assertEquals(Phase.CallFreed, m.phase)
    }

    @Test
    fun `a ring while unlocked does nothing`() {
        val m = ReadLockMachine()
        assertEquals(emptyList(), m.callStarted())
        assertEquals(Phase.Off, m.phase)
        assertFalse(m.showsBanner, "잠그지 않았는데 통화 뒤 띠가 뜬다")
    }

    @Test
    fun `locking during a call is refused`() {
        val m = ReadLockMachine()
        m.callStarted()
        assertEquals(listOf(Effect.RefuseInCall), m.request())
        assertEquals(Phase.Off, m.phase)
        m.callEnded()
        assertEquals(listOf(Effect.Pin), m.request())
    }

    @Test
    fun `relocking after a call resumes auto scrolling once android confirms`() {
        val m = locked()
        m.callStarted()
        m.callEnded()
        assertEquals(listOf(Effect.Pin), m.request())
        m.focus(has = false, pinned = false)
        assertEquals(listOf(Effect.QuietOn, Effect.ResumeAuto, Effect.ShowLocked), m.focus(has = true, pinned = true))
        assertFalse(m.showsBanner)
    }

    @Test
    fun `declining the relock drops the banner and leaves auto scrolling paused`() {
        val m = locked()
        m.callStarted()
        m.callEnded()
        m.request()
        m.focus(has = false, pinned = false)
        assertEquals(listOf(Effect.ForgetAuto), m.focus(has = true, pinned = false))
        assertEquals(Phase.Off, m.phase)
        assertFalse(m.showsBanner)
    }

    @Test
    fun `the system gesture unpin is noticed and quiet is restored`() {
        val m = locked()
        // 뒤로 + 최근 앱 길게: 우리 모르게 풀렸다. 다음에 볼 때 풀림으로 맞춘다.
        assertEquals(listOf(Effect.QuietOff), m.observed(pinned = false))
        assertEquals(Phase.Off, m.phase)
    }

    @Test
    fun `a ring while the dialog is up unpins in case it is confirmed later`() {
        val m = ReadLockMachine()
        m.request()
        assertEquals(listOf(Effect.Unpin), m.callStarted())
        assertEquals(Phase.Off, m.phase)
        // 벨이 울리는 사이 "확인" 을 눌렀다: 고정이 보이면 곧바로 푼다.
        assertEquals(listOf(Effect.Unpin), m.focus(has = true, pinned = true))
        assertEquals(Phase.Off, m.phase, "통화 중인데 잠금 중이 됐다")
    }

    @Test
    fun `holding the lock unlocks and restores quiet, but only when locked`() {
        assertEquals(emptyList(), ReadLockMachine().release())
        val m = locked()
        assertEquals(listOf(Effect.Unpin, Effect.QuietOff), m.release())
        assertEquals(Phase.Off, m.phase)
    }

    @Test
    fun `a decline the app never saw does not leave the lock stuck`() {
        // 확인 창이 초점을 가져가지 않는 기기에서 "취소": 초점이 오가지 않아 거절을 알 길이 없다. 0.54.1 까지는 "묻는 중" 에 갇혀
        // 자물쇠를 다시 눌러도 아무 일이 없었다. 다시 "잠그기"(설명 판을 거쳐야 오므로 앞 창은 이미 닫혔다)면 다시 묻는다.
        val m = ReadLockMachine()
        m.request()
        assertEquals(emptyList(), m.observed(pinned = false))
        assertEquals(Phase.Asking, m.phase)
        assertEquals(listOf(Effect.Pin), m.request(), "묻는 중에 다시 잠그기를 눌렀는데 다시 묻지 않았다")
        m.focus(has = false, pinned = false)
        m.focus(has = true, pinned = true)
        assertEquals(Phase.On, m.phase)
        // 잠긴 중에 또 누르면 할 일이 없다.
        assertEquals(emptyList(), m.request())
    }

    @Test
    fun `a pin android refused at once goes back to where it was`() {
        // 화면이 앞에 서지 않아 startLockTask 가 거절됐다 — 확인 창도 뜨지 않는다. "묻는 중" 에 머물면 안 된다.
        val m = ReadLockMachine()
        m.request()
        m.pinFailed()
        assertEquals(Phase.Off, m.phase)
        // 통화 뒤 다시 잠그기가 거절되면 띠로 돌아간다(멈춘 자동 진행을 아직 기억한다).
        val r = locked()
        r.callStarted(); r.callEnded()
        r.request()
        r.pinFailed()
        assertEquals(Phase.CallFreed, r.phase)
        assertTrue(r.showsBanner)
        // 묻는 중이 아니면 아무 일도 없다(늦게 온 실패 알림).
        val on = locked()
        assertEquals(emptyList(), on.pinFailed())
        assertEquals(Phase.On, on.phase)
    }

    @Test
    fun `a pin made from the recents screen is taken as our lock`() {
        // 최근 앱 화면의 "이 앱 고정"(0.54.4): 우리 자물쇠를 거치지 않았다. 받지 않으면 벨이 울려도 풀지 않는다.
        val m = ReadLockMachine()
        assertEquals(listOf(Effect.QuietOn), m.focus(has = true, pinned = true))
        assertEquals(Phase.On, m.phase)
        assertEquals(listOf(Effect.Unpin, Effect.QuietOff, Effect.PauseAuto), m.callStarted(), "사람이 건 고정을 벨이 풀지 않았다")
        // 통화 뒤 띠가 떠 있는 사이 다시 최근 앱에서 고정했다 — "다시 잠그기" 와 같다.
        m.callEnded()
        assertTrue(m.showsBanner)
        assertEquals(listOf(Effect.QuietOn, Effect.ResumeAuto), m.observed(pinned = false).let { m.observed(pinned = true) })
        assertEquals(Phase.On, m.phase)
        assertFalse(m.showsBanner)
    }

    @Test
    fun `the pin we just released is not taken back as a new lock`() {
        // 자물쇠를 길게 눌러 풀었는데 안드로이드의 고정 상태가 아직 따라오지 않았다. 그 옛 상태를 사람이 건 고정으로 받으면 풀자마자
        // 다시 잠금 중이 된다.
        val m = locked()
        m.release()
        assertEquals(emptyList(), m.focus(has = true, pinned = true), "막 푼 고정의 옛 상태를 새 잠금으로 받았다")
        assertEquals(Phase.Off, m.phase)
        // 풀린 것을 한 번 보고 나면, 그 뒤의 고정은 사람이 건 것이다.
        m.observed(pinned = false)
        assertEquals(listOf(Effect.QuietOn), m.observed(pinned = true))
        // 새로 뜬 앱이 지난번 고정을 푼 직후도 같다.
        val fresh = ReadLockMachine()
        assertEquals(listOf(Effect.Unpin), fresh.leftover())
        assertEquals(emptyList(), fresh.observed(pinned = true))
        assertEquals(Phase.Off, fresh.phase)
    }

    @Test
    fun `a recents pin during a call is undone at once`() {
        val m = ReadLockMachine()
        m.callStarted()
        assertEquals(listOf(Effect.Unpin), m.observed(pinned = true), "통화 중에 걸린 고정을 두어 통화 화면이 막힌다")
        assertEquals(Phase.Off, m.phase)
    }

    @Test
    fun `closing the app undoes the pin and quiet`() {
        val m = locked()
        assertEquals(listOf(Effect.Unpin, Effect.QuietOff, Effect.ForgetAuto), m.ended())
        assertEquals(Phase.Off, m.phase)
    }

    @Test
    fun `leaving the reader after a call drops the banner`() {
        val m = locked()
        m.callStarted()
        m.callEnded()
        assertEquals(listOf(Effect.ForgetAuto), m.leftReader())
        assertFalse(m.showsBanner)
    }

    @Test
    fun `only normal audio mode counts as no call`() {
        assertFalse(ReadLockCalls.activeMode(android.media.AudioManager.MODE_NORMAL))
        assertFalse(ReadLockCalls.activeMode(-2), "모르는 모드를 통화로 봤다")
        assertTrue(ReadLockCalls.activeMode(android.media.AudioManager.MODE_RINGTONE))
        assertTrue(ReadLockCalls.activeMode(android.media.AudioManager.MODE_IN_CALL))
        assertTrue(ReadLockCalls.activeMode(android.media.AudioManager.MODE_IN_COMMUNICATION))
    }
}
