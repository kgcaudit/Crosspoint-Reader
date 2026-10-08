package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** 자동 넘김(L7): n초마다 다음 쪽. 스크롤 보기가 없는 대신이다. */
enum class AutoTurn(val label: String, val seconds: Int?) {
    Off("끔", null),
    S15("15초", 15),
    S30("30초", 30),
    S60("60초", 60),
}

/** 자동 넘김의 지금 모습. 쪽이 바뀔 때마다 처음부터 센다. */
class AutoTurnState {
    var remaining by mutableIntStateOf(0)
    /** 사람이 알약의 ⏸ 를 눌렀다. */
    var paused by mutableStateOf(false)
    /** 사람이 ✕ 를 눌렀거나 책 끝에 닿았다. 이번에 책을 연 동안만 — 설정은 그대로다. */
    var stopped by mutableStateOf(false)
    internal var key: Any? = null
}

/**
 * 자동 넘김을 돌린다. [suspended] 동안(메뉴가 열림 · 듣는 중)은 세지 않는다 — 메뉴를 보는 사이에 쪽이 넘어가면
 * 고르던 것을 잃는다. 쪽이 바뀌면([pageKey]) 처음부터 센다: 스스로 넘겼든 사람이 넘겼든 새 쪽을 읽을 시간이
 * 다시 필요하다.
 */
@Composable
fun rememberAutoTurn(setting: AutoTurn, pageKey: Any?, suspended: Boolean, onTurn: () -> Unit): AutoTurnState {
    val state = remember { AutoTurnState() }
    val latest by rememberUpdatedState(onTurn)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(setting) {
        state.stopped = false
        state.paused = false
        state.key = null
    }
    LaunchedEffect(setting, pageKey, state.paused, state.stopped, suspended) {
        val seconds = setting.seconds ?: return@LaunchedEffect
        if (state.key != pageKey) {
            state.key = pageKey
            state.remaining = seconds
        }
        if (state.paused || state.stopped || suspended) return@LaunchedEffect
        // 화면이 꺼졌거나 다른 앱을 보는 동안은 세지 않는다(0.28.3). 이 효과의 delay 는 뒤에서도 흐르는데 화면은 다시
        // 그려지지 않아, 모르는 사이 한 쪽이 넘어가고 쪽 번호가 안 바뀐 줄 알고 아래의 "책 끝" 으로 빠져 자동 넘김이 꺼졌다.
        suspend fun onScreen() { lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) } }
        state.countDown(::onScreen)
        latest()
        // 넘겼는데 쪽이 그대로면(책 끝) 이 효과가 취소되지 않고 여기 온다 — 멈춘다. 끝 쪽에서 0초를 붙들고 있지 않게.
        // 그림 두 장을 기다린다: 새 쪽이 그려졌다면 그사이 이 효과는 취소됐다. 앞에 서지 않은 동안은 그리지 않으므로
        // 뒤로 물러난 사이 여기 와도 책 끝으로 잘못 읽지 않는다.
        delay(2_000)
        onScreen()
        withFrameNanos { }
        withFrameNanos { }
        state.stopped = true
    }
    return state
}

/**
 * 남은 초를 0 까지 센다. 한 초가 지날 때마다 [onScreen] 을 기다린다 — 화면이 앞에 서지 않은 동안은 거기서 멈춰 있다가,
 * 돌아오면 남은 초부터 이어 센다.
 */
internal suspend fun AutoTurnState.countDown(onScreen: suspend () -> Unit) {
    while (remaining > 0) {
        delay(1_000)
        onScreen()
        remaining--
    }
}

/**
 * 자동 넘김이 이 책에서 쪽을 넘기는 중인가: 켜져 있고 ⏸ · ✕ 로 세우지 않았다. 메뉴가 열려 잠깐 쉬는 동안도 넘기는 중으로 본다 —
 * 그 쪽에 머문 시간은 사람이 읽은 시간이 아니라 고른 초에 묶여 있다(읽는 속도에 넣지 않는다).
 */
fun AutoTurnState.running(setting: AutoTurn): Boolean = setting != AutoTurn.Off && !stopped && !paused

/** 알약이 보일 때: 켜져 있고, ✕ 로 끄지 않았고, 메뉴 · 듣기 중이 아닐 때. */
fun AutoTurnState.visible(setting: AutoTurn, suspended: Boolean): Boolean = setting != AutoTurn.Off && !stopped && !suspended

/**
 * 자동 넘김 중 아래에 뜨는 알림: "자동 넘김 · 다음 쪽까지 18초" ⏸ ✕. 모서리는 카드(14dp)다(0.31.0, OLO-Design) — 24dp 알약은
 * 계열 규칙("알약을 쓰지 않는다")에 어긋났다.
 */
@Composable
fun CpAutoTurnPill(state: AutoTurnState, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    val shape = RoundedCornerShape(CpTheme.metrics.cornerMedium)
    Row(
        modifier.shadow(8.dp, shape).clip(shape).background(c.surface)
            .border(1.dp, c.divider, shape)
            .blockTouches()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(
            if (state.paused) "자동 넘김 · 멈춰 있음" else "자동 넘김 · 다음 쪽까지 ${state.remaining}초",
            CpTheme.type.label, c.text,
        )
        CpIconButton(
            if (state.paused) CpIcons.Play else CpIcons.Pause,
            if (state.paused) "자동 넘김 이어 하기" else "자동 넘김 멈춤",
            { state.paused = !state.paused },
        )
        CpIconButton(CpIcons.Close, "자동 넘김 끄기", { state.stopped = true })
    }
}

/**
 * 웹툰 자동 스크롤 빠르기(0.47.0, 사용자 결정 ④): 한 화면을 내려가는 데 걸리는 초. 다섯 단 — 더 잘게 나누면 −/+ 를 여러 번
 * 눌러야 차이가 보인다.
 */
enum class AutoScrollSpeed(val label: String, val secondsPerScreen: Float) {
    VerySlow("아주 느림", 25f),
    Slow("느림", 15f),
    Normal("보통", 10f),
    Fast("빠름", 7f),
    VeryFast("아주 빠름", 5f),
    ;

    fun slower(): AutoScrollSpeed = entries[(ordinal - 1).coerceAtLeast(0)]
    fun faster(): AutoScrollSpeed = entries[(ordinal + 1).coerceAtMost(entries.size - 1)]
}

/**
 * 자동 스크롤 중 아래에 뜨는 조절기: "자동 스크롤" − 보통 + ⏸ ✕. 자동 넘김 알림([CpAutoTurnPill])과 같은 카드 모양이다 — 한 앱
 * 안에서 같은 일을 하는 부품이 두 모양이면 다른 기능으로 읽힌다.
 */
@Composable
fun CpAutoScrollPill(
    speed: AutoScrollSpeed,
    paused: Boolean,
    onSpeed: (AutoScrollSpeed) -> Unit,
    onPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CpTheme.colors
    val shape = RoundedCornerShape(CpTheme.metrics.cornerMedium)
    Row(
        modifier.shadow(8.dp, shape).clip(shape).background(c.surface)
            .border(1.dp, c.divider, shape)
            .blockTouches()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(if (paused) "자동 스크롤 · 멈춤" else "자동 스크롤", CpTheme.type.label, c.text)
        CpIconButton(CpIcons.Minus, "더 느리게", { onSpeed(speed.slower()) }, tint = if (speed == AutoScrollSpeed.entries.first()) c.textMuted else c.text)
        CpText(speed.label, CpTheme.type.label, c.accentText)
        CpIconButton(CpIcons.Plus, "더 빠르게", { onSpeed(speed.faster()) }, tint = if (speed == AutoScrollSpeed.entries.last()) c.textMuted else c.text)
        CpIconButton(
            if (paused) CpIcons.Play else CpIcons.Pause,
            if (paused) "자동 스크롤 이어 하기" else "자동 스크롤 멈춤",
            onPause,
        )
        CpIconButton(CpIcons.Close, "자동 스크롤 끄기", onClose)
    }
}
