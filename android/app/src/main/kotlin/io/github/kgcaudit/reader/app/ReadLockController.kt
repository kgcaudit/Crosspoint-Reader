package io.github.kgcaudit.reader.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kgcaudit.reader.app.ReadLockMachine.Effect
import io.github.kgcaudit.reader.app.ReadLockMachine.Phase
import io.github.kgcaudit.reader.ui.design.AutoProgress
import io.github.kgcaudit.reader.ui.design.ReadLock

/**
 * 읽기 잠금(2026-10-15 사용자 결정): 안드로이드 화면 고정(startLockTask, 기기 관리자 없이)으로 다른 앱 · 홈 · 최근 앱으로
 * 나가지 못하게 하고, 벨이 울리면 저절로 푼다. 판단은 [ReadLockMachine] 이, 플랫폼 일(고정 · 방해 금지 · 전화 듣기 · 안내)은
 * 이것이 한다.
 *
 * 앱(프로세스)에 하나다 — 화면(액티비티)이 다시 만들어져도 잠금 상태가 이어져야 한다. 프로세스가 새로 뜨면 풀린 채로 시작한다:
 * 잠금은 저장하지 않는다. 그때 남아 있는 고정 · 방해 금지는 되돌린다([resumed] 의 첫 번).
 */
internal class ReadLockController(private val context: Context) : ReadLock {

    /** 고정을 거는 길. 시험이 확인 창의 "거절" 을 흉내 내려고 바꿔 끼운다(Robolectric 은 부르는 즉시 고정한다). */
    internal interface Pinner {
        /** 고정을 청했다. 안드로이드가 바로 거절했으면(예외) false — 확인 창도 뜨지 않는다. */
        fun start(activity: Activity): Boolean
        fun stop(activity: Activity)
        fun pinned(activity: Activity): Boolean
    }

    internal object PlatformPinner : Pinner {
        // 화면이 앞에 서지 않았거나 이미 고정돼 있으면 예외가 날 수 있다 — 잠금 실패로 앱이 닫히면 안 된다.
        override fun start(activity: Activity): Boolean = runCatching { activity.startLockTask() }.isSuccess
        override fun stop(activity: Activity) { runCatching { activity.stopLockTask() } }

        /**
         * 고정돼 있는가. PINNED(사람이 확인한 화면 고정)만이 아니라 LOCKED(기기 관리자 잠금)도 잠긴 것으로 본다 — 둘 다 다른 앱으로
         * 나가는 길을 막고, 둘 다 stopLockTask 로 풀린다.
         */
        override fun pinned(activity: Activity): Boolean =
            activity.getSystemService(ActivityManager::class.java)?.lockTaskModeState?.let { it != ActivityManager.LOCK_TASK_MODE_NONE } ?: false
    }

    internal var pinner: Pinner = PlatformPinner
    internal val quiet = ReadLockQuiet(context)
    private val machine = ReadLockMachine()
    private val calls = ReadLockCalls(context) { active ->
        act(if (active) machine.callStarted() else machine.callEnded())
        // 통화가 끝났는데 화면이 멈춰 있다(통화 중 전원 단추로 껐다). 통화 동안 두었던 잠금 화면 위 표시를 거둔다 — 남기면 그 뒤
        // 켠 화면에서 PIN 없이 책이 보인다. 화면이 서 있으면(통화 화면에서 돌아옴) 그대로 둔다: 읽던 책으로 바로 돌아와야 한다.
        if (!active && !visible) overKeyguard(false)
    }
    private val main = Handler(Looper.getMainLooper())
    private var activity: Activity? = null
    private var firstResume = true

    /** 화면이 앞에 서 있다(onResume ~ onStop). */
    private var visible = false

    // 화면이 읽는 값은 컴포즈 상태로 비춘다(기계는 플랫폼 · 컴포즈를 모른다).
    private var phase by mutableStateOf(Phase.Off)
    private var banner by mutableStateOf(false)
    override val locked: Boolean get() = phase == Phase.On

    /** 잠깐 뜨는 안내. 같은 말을 또 띄울 때 [toastKey] 가 바뀐다. */
    var toast by mutableStateOf<String?>(null)
        private set
    var toastKey by mutableIntStateOf(0)
        private set

    /** 통화로 멈춘 자동 진행이 있다. 띠의 말이 달라진다. */
    var pausedAuto by mutableStateOf(false)
        private set

    /** 지금 떠 있는 읽기 메뉴 수(윗줄이 들어오고 나가는 차례가 겹칠 수 있어 수로 센다). */
    private var menus by mutableIntStateOf(0)

    override val asksRelock: Boolean get() = banner

    override fun menu(open: Boolean) {
        menus = (menus + if (open) 1 else -1).coerceAtLeast(0)
    }

    /** 통화 뒤 띠의 말. 띠를 보일 때가 아니면 null. 메뉴가 열린 동안은 거둔다 — 아래 도구줄을 덮는다. */
    val bannerText: String?
        get() = when {
            !banner || menus > 0 -> null
            // 한 말로(0.54.1 사용자 결정 1). 자동 진행이 멈춘 것은 "다시 잠그기" 를 누르면 저절로 이어지므로 따로 말하지 않는다.
            else -> "통화로 잠금이 풀렸습니다"
        }

    /** 이번 잠금에서 고른 "알림도 끄기". 띠의 "다시 잠그기" 도 이것을 따른다. */
    private var quietNow = false

    /** 만화에서 잠갔다 — 안내가 메뉴 여는 법부터 말한다. */
    private var comic = false

    /** 방해 금지 허락 화면에 보냈다. 돌아오면 이어서 잠근다. */
    private var awaitingAccess = false

    private val holds = mutableListOf<AutoProgress>()
    private var paused = emptyList<AutoProgress>()

    fun attach(activity: Activity) {
        this.activity = activity
        // 새 화면(설정 변경으로 다시 만들어짐)은 잠금 화면 위 표시를 모른다 — 잠긴 채면 다시 건다.
        shownOverKeyguard = false
        if (phase == Phase.On) overKeyguard(true)
    }

    /** 화면이 사라진다. 끝나는 것이면(앱을 닫음) 고정 · 방해 금지 · 전화 듣기를 모두 거둔다. */
    fun detach(activity: Activity, finishing: Boolean) {
        if (this.activity !== activity) return
        if (finishing) act(machine.ended())
        this.activity = null
    }

    fun resumed() {
        val a = activity ?: return
        visible = true
        if (firstResume) {
            firstResume = false
            // 새로 뜬 앱은 풀린 채로 시작한다. 지난번 잠금이 남긴 것(앱이 거둬진 사이의 고정 · 방해 금지)을 되돌린다.
            quiet.recover()
            if (pinner.pinned(a)) act(machine.leftover())
        }
        if (awaitingAccess) {
            awaitingAccess = false
            // 허락 화면에서 돌아왔다. 다 그려진 뒤에 고정한다 — 앞에 막 선 화면에서 부르면 거절되는 기기가 있다.
            if (quiet.granted()) main.post { begin() } else say("알림 끄기 권한이 없어 잠그지 않았습니다")
            return
        }
        observe(pinner.pinned(a)) { machine.observed(it) }
    }

    fun focus(has: Boolean) {
        val a = activity ?: return
        observe(pinner.pinned(a)) { machine.focus(has, it) }
    }

    /**
     * 고정 상태를 기계에 알린다. 풀린 채 고정이 보이면 사람이 최근 앱 화면에서 건 것일 수 있다(0.54.4) — 그러면 기계가 잠금으로
     * 받기 **전에** 전화 듣기를 켜고 지금 통화 중인지 알린다: 늦게 켜면 통화 중에 걸린 고정을 풀지 못해 통화 화면이 막히고, 아예
     * 안 켜면 잠긴 동안 벨이 울려도 풀지 않는다. 받지 않았으면(우리가 막 푼 고정의 옛 상태 · 통화 중) 다시 끈다.
     */
    private fun observe(pinned: Boolean, read: (Boolean) -> List<Effect>) {
        val adopting = pinned && phase == Phase.Off
        if (adopting) {
            // 자물쇠를 거치지 않았으니 이번 잠금의 "알림 끄기" 는 보기 설정에 고른 것을 따른다.
            quietNow = quiet.chosen
            act(if (calls.start()) machine.callStarted() else machine.callEnded())
        }
        act(read(pinned))
        if (adopting && phase == Phase.Off) calls.stop()
    }

    /**
     * 풀려 있으면 바로 잠근다 — 안드로이드 확인 창이 곧 뜬다(0.54.3 사용자 결정 1: 그 앞의 우리 설명 판을 없앴다. 안드로이드
     * 창이 매번 뜨는데 우리 판까지 겹쳐 두 번 묻는 꼴이었다). 잠겨 있으면 푸는 법만 짧게.
     */
    override fun tap(comic: Boolean) {
        this.comic = comic
        if (locked) say(lockedHint()) else confirm(quiet.chosen)
    }

    override var quietChosen: Boolean
        get() = quietChoice
        set(value) { quietChoice = value; quiet.chosen = value }

    /** "알림 끄기" 를 컴포즈 상태로 비춘다(설정 화면이 따라 바뀐다). 저장은 [ReadLockQuiet.chosen]. */
    private var quietChoice by mutableStateOf(quiet.chosen)

    /** 잠근다. [quiet] 는 "알림 끄기". */
    private fun confirm(quiet: Boolean) {
        quietNow = quiet
        if (quiet && !this.quiet.granted()) {
            // 허락 화면을 **고정보다 먼저** 연다. 고정된 뒤에는 설정 화면이 열리지 않는다.
            val a = activity ?: return
            val opened = runCatching { a.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }.isSuccess
            if (opened) { awaitingAccess = true; return }
            // 허락 화면이 없는 기기: 알림은 그대로 두고 잠근다.
        }
        begin()
    }

    /** 띠의 "다시 잠그기". 고른 방해 금지도 다시 걸고, 멈춘 자동 진행은 고정이 확인되면 잇는다. */
    fun relock() = begin()

    override fun release() {
        act(machine.release())
    }

    /**
     * 화면이 멈췄다(홈 · 다른 앱으로 나감, 화면 꺼짐). 휴대폰 잠금 화면 위에 책을 두던 것을 거둔다 — 그대로 두면 꺼졌다 켜진 화면에서
     * 잠금 화면 없이 책이 보인다. 통화로 푼 채 통화 중이면 둔다: 통화 화면이 위에 서서 멈춘 것이고, 끊으면 읽던 책으로 바로 돌아와야
     * 한다.
     */
    fun stopped() {
        visible = false
        if (phase == Phase.CallFreed && machine.inCall) return
        overKeyguard(false)
    }

    override fun blocks(): Boolean {
        if (!locked) return false
        say("잠금 중에는 열 수 없습니다")
        return true
    }

    override fun hold(auto: AutoProgress): () -> Unit {
        holds += auto
        return { holds -= auto }
    }

    /** 리더를 닫았다. */
    fun leftReader() = act(machine.leftReader())

    fun toastShown() {
        toast = null
    }

    private fun begin() {
        // 듣기를 먼저 켜고 지금 통화 중인지 기계에 알린다 — 통화 중에 고정하면 통화 화면으로 돌아갈 길이 막힌다.
        val active = calls.start()
        act(if (active) machine.callStarted() else machine.callEnded())
        act(machine.request())
        // 통화 중이라 잠그지 않았다 — 막 켠 듣기를 끈다. 0.54.1 까지는 풀림에서 풀림으로(바뀌지 않음) 남아 앱이 사는 내내 들었다.
        if (phase == Phase.Off) calls.stop()
    }

    /** 잠긴 채 자물쇠를 짧게 누를 때의 안내. 잠근 · 푼 순간에는 띄우지 않는다 — 안드로이드가 제 안내를 띄워 겹쳤다(0.54.3). */
    private fun lockedHint() = "자물쇠를 길게 누르면 풀립니다"

    /** 지금 책 화면을 휴대폰 잠금 화면 위에 두고 있다. 같은 값을 거듭 거는 일을 줄인다. */
    private var shownOverKeyguard = false

    /**
     * 휴대폰 잠금 화면 위에 책을 둔다 · 거둔다(0.54.1 사용자 결정 2). 안드로이드는 고정을 풀 때 "고정 해제 때 잠금 화면"(화면 잠금을
     * 쓰는 폰은 기본 켬, 앱이 읽거나 바꿀 수 없는 설정)에 따라 휴대폰을 잠근다 — 0.54.0 은 자물쇠를 풀자마자 PIN 화면이 떠 읽던
     * 책이 사라졌다. 잠긴 동안 미리 걸어 두면 풀린 뒤에도 책이 잠금 화면 위에 그대로 있고, 홈 · 다른 앱으로 나갈 때에만 잠금 화면이
     * 나온다 — 고정을 풀 때 PIN 을 묻는 까닭(남이 다른 앱을 열지 못하게)은 그대로 지켜진다. 거두는 것은 [stopped].
     */
    private fun overKeyguard(on: Boolean) {
        val a = activity ?: return
        if (shownOverKeyguard == on) return
        shownOverKeyguard = on
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            a.setShowWhenLocked(on)
        } else {
            @Suppress("DEPRECATION")
            val flag = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            if (on) a.window.addFlags(flag) else a.window.clearFlags(flag)
        }
    }

    private fun say(message: String) {
        toast = message
        toastKey++
    }

    private fun act(effects: List<Effect>) {
        val was = phase
        for (effect in effects) when (effect) {
            // 걸지 못했으면 다음 차례에 "묻는 중" 에서 빼낸다(이 순회 안에서 기계를 다시 움직이지 않는다).
            Effect.Pin -> activity?.let { if (!pinner.start(it)) main.post { act(machine.pinFailed()) } }
            Effect.Unpin -> activity?.let(pinner::stop)
            Effect.QuietOn -> if (quietNow) quiet.on()
            Effect.QuietOff -> quiet.off()
            Effect.PauseAuto -> {
                paused = holds.filter { it.running }
                paused.forEach { it.pause() }
                pausedAuto = paused.isNotEmpty()
            }
            Effect.ResumeAuto -> {
                // 그사이 닫힌 리더의 것은 잇지 않는다.
                paused.filter { it in holds }.forEach { it.resume() }
                paused = emptyList()
                pausedAuto = false
            }
            Effect.ForgetAuto -> {
                paused = emptyList()
                pausedAuto = false
            }
            // 잠긴 순간의 안내는 안드로이드가 띄운다("앱을 고정했어요") — 우리 것을 겹치지 않는다(0.54.3 사용자 결정).
            Effect.ShowLocked -> Unit
            Effect.RefuseInCall -> say("통화 중에는 잠글 수 없습니다")
        }
        phase = machine.phase
        banner = machine.showsBanner
        if (phase == Phase.On) overKeyguard(true)
        // 풀린 뒤에는 전화를 듣지 않는다 — 잠그지 않은 사람의 통화를 지켜볼 까닭이 없다. 풀림으로 **바뀔 때만** 끈다: 잠그기
        // 직전(풀린 채) 통화 상태를 알리는 일에서 끄면, 막 켠 듣기가 꺼져 벨을 놓친다.
        if (phase == Phase.Off && was != Phase.Off) calls.stop()
        if (phase == Phase.Asking || phase == Phase.On) poll() else main.removeCallbacks(check)
    }

    /**
     * 묻는 동안 · 잠긴 동안 1초마다 고정 상태를 본다. 확인 창이 초점을 가져가지 않는 기기에서도 "확인" 을 알아채고, 시스템 동작
     * (뒤로 + 최근 앱 길게)으로 풀린 것을 화면이 앞에 선 채로도 알아챈다 — 안 보면 풀렸는데 "잠금 중" 이라고 한다.
     */
    private val check = object : Runnable {
        override fun run() {
            val a = activity ?: return
            observe(pinner.pinned(a)) { machine.observed(it) }
        }
    }

    private fun poll() {
        main.removeCallbacks(check)
        main.postDelayed(check, POLL_MS)
    }

    private companion object {
        const val POLL_MS = 1_000L
    }
}
