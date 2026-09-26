package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
        while (state.remaining > 0) {
            delay(1_000)
            state.remaining--
        }
        latest()
        // 넘겼는데 쪽이 그대로면(책 끝) 이 효과가 취소되지 않고 여기 온다 — 멈춘다. 끝 쪽에서 0초를 붙들고 있지 않게.
        delay(2_000)
        state.stopped = true
    }
    return state
}

/** 알약이 보일 때: 켜져 있고, ✕ 로 끄지 않았고, 메뉴 · 듣기 중이 아닐 때. */
fun AutoTurnState.visible(setting: AutoTurn, suspended: Boolean): Boolean = setting != AutoTurn.Off && !stopped && !suspended

/** 자동 넘김 중 아래에 뜨는 알약: "자동 넘김 · 다음 쪽까지 18초" ⏸ ✕. */
@Composable
fun CpAutoTurnPill(state: AutoTurnState, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(24.dp))
            .clickable(indication = null, interactionSource = null) {}
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(
            if (state.paused) "자동 넘김 · 멈춤" else "자동 넘김 · 다음 쪽까지 ${state.remaining}초",
            CpTheme.type.label, c.text,
        )
        CpIconButton(
            if (state.paused) CpIcons.Play else CpIcons.Pause,
            if (state.paused) "자동 넘김 이어 하기" else "자동 넘김 멈춤",
            { state.paused = !state.paused },
        )
        CpIconButton(CpIcons.Close, "자동 넘김 끄기", { state.stopped = true }, modifier = Modifier.semantics { contentDescription = "자동 넘김 끄기" })
    }
}
