package io.github.kgcaudit.reader.ui.design

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 읽기 잠금(2026-10-15 사용자 결정): 읽는 동안 다른 앱 · 홈 · 최근 앱으로 나가지 못하게 한다. 안드로이드 화면 고정을 쓰고,
 * 전화가 오면 저절로 푼다. 고정 · 방해 금지 · 전화 감지는 앱([ReadLock] 을 만드는 쪽)이 하고, 이 모듈은 리더들이 함께 쓰는
 * 부품(윗줄 자물쇠 · 부제 표시 · 자동 진행 멈춤 · 바깥 화면 막기)만 둔다 — 책 · PDF · 만화 · 웹툰 리더가 서로 다른 모듈이라
 * 각자 만들면 넷이 조금씩 다르게 생긴다.
 */
interface ReadLock {
    /** 지금 화면이 고정돼 있다(안드로이드가 확인해 준 것). 컴포즈 상태로 읽혀야 한다 — 자물쇠 모양 · 부제가 따라 바뀐다. */
    val locked: Boolean

    /**
     * 자물쇠를 짧게 눌렀다(잠긴 동안 2초를 못 채우고 뗀 것도). 풀려 있으면 설명 판, 잠겨 있으면 푸는 법 안내.
     * [comic] 은 만화 화면이다 — 읽는 동안 메뉴가 숨어 있어 안내가 "가운데를 눌러" 부터 말해야 한다.
     */
    fun tap(comic: Boolean)

    /** 자물쇠를 2초 눌러 고리가 다 찼다: 푼다. */
    fun release()

    /**
     * 다른 앱의 화면(고르기 창 · 공유 · 바깥 링크)을 열려 한다. 잠겨 있으면 안내만 띄우고 true — 부르는 쪽은 열지 않는다.
     * 고정된 동안 그런 화면은 안드로이드가 막거나, 열리면서 고정이 깨진다.
     */
    fun blocks(): Boolean

    /** 전화가 오면 멈출 자동 진행(자동 넘김 · 웹툰 자동 스크롤)을 건다. 돌려준 것을 부르면 뗀다. */
    fun hold(auto: AutoProgress): () -> Unit

    /**
     * 통화 뒤 "다시 잠그기" 띠가 화면 아래에 떠 있다. 자동 진행 알약은 그동안 숨는다(구상안 6) — 같은 자리에 겹쳐 띠의 글이
     * 가려지고, 다시 잠그면 어차피 이어 흐른다. 컴포즈 상태로 읽혀야 한다.
     */
    val asksRelock: Boolean

    /**
     * 읽기 메뉴가 열렸다 · 닫혔다. 열린 동안은 띠를 거둔다 — 띠가 아래 도구줄(목차 · 보기 …)을 덮어 누를 수 없게 된다.
     * 메뉴 윗줄의 자물쇠로 다시 잠글 수 있다.
     */
    fun menu(open: Boolean)
}

/** 전화가 와서 잠금을 풀 때 멈추고, "다시 잠그기" 로 이어 가는 것. */
interface AutoProgress {
    val running: Boolean
    fun pause()
    fun resume()
}

/** 앱이 넣어 준다. 없으면(부품 시험 · 미리 보기) 자물쇠가 없다. */
val LocalReadLock = staticCompositionLocalOf<ReadLock?> { null }

/** 컴포즈 밖(공유 · 사전처럼 [Context] 만 받는 함수)에서 잠금을 찾는 길. Application 이 이것을 구현한다. */
interface ReadLockHost {
    val readLock: ReadLock?
}

/** 잠겨 있으면 안내하고 true. 다른 앱 화면을 여는 모든 곳이 이것 하나를 지난다. */
fun Context.readLockBlocks(): Boolean = (applicationContext as? ReadLockHost)?.readLock?.blocks() == true

/** 잠긴 동안 부제 끝에 붙는 말. 오→왼 만화는 이것을 방향 표시 앞에 둔다(같은 줄에서 상태가 먼저 읽히게). */
@Composable
fun readLockMark(): String = if (LocalReadLock.current?.locked == true) " · 잠금 중" else ""

/**
 * 이 자동 진행을 잠금에 건다. 화면에 있는 동안만 — 리더를 닫으면 떼야 전화가 왔을 때 닫힌 리더를 멈추려 하지 않는다.
 * 값은 매번 새로 읽는다(rememberUpdatedState): 멈춤 여부가 바뀔 때마다 다시 걸면 그사이 온 벨을 놓친다.
 */
@Composable
fun ReadLockPauses(running: Boolean, pause: () -> Unit, resume: () -> Unit) {
    val lock = LocalReadLock.current ?: return
    val now by rememberUpdatedState(running)
    val stop by rememberUpdatedState(pause)
    val go by rememberUpdatedState(resume)
    DisposableEffect(lock) {
        val off = lock.hold(object : AutoProgress {
            override val running: Boolean get() = now
            override fun pause() = stop()
            override fun resume() = go()
        })
        onDispose { off() }
    }
}

/** 잠금을 푸는 데 눌러야 하는 시간. 실수로 스친 손에 풀리지 않을 만큼, 기다리다 지치지 않을 만큼. */
const val READ_LOCK_HOLD_MS = 2_000

/**
 * 윗줄 자물쇠(책갈피 왼쪽). 풀려 있으면 열린 자물쇠 — 누르면 설명 판. 잠겨 있으면 38dp 동그라미 위 닫힌 자물쇠 — 2초 누르면
 * 둘레 고리가 차오르고 다 차면 풀린다. 일찍 떼면 고리가 비고 잠금은 그대로다(푸는 법 안내만).
 *
 * 누르기를 손으로 읽는다(clickable 의 길게 누르기는 시간이 0.5초로 정해져 있고 차오르는 모습을 그릴 수 없다).
 */
@Composable
internal fun ReadLockButton(lock: ReadLock, comic: Boolean) {
    val c = CpTheme.colors
    val locked = lock.locked
    val ring = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val comicNow by rememberUpdatedState(comic)
    Box(
        Modifier
            .size(CpTheme.metrics.touchTarget)
            .semantics {
                role = Role.Button
                contentDescription = if (locked) "잠금 풀기 (2초 누르기)" else "읽기 잠금"
                onClick { lock.tap(comicNow); true }
                // 화면 읽기 사용자는 두 번 눌러 길게 누르기로 푼다 — 2초를 손으로 재기 어렵다.
                if (locked) onLongClick("잠금 풀기") { lock.release(); true }
            }
            .pointerInput(locked) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!locked) {
                        if (waitForUpOrCancellation() != null) lock.tap(comicNow)
                        return@awaitEachGesture
                    }
                    // 고리는 이 단추 밖의 범위에서 채운다 — 다 차서 풀리면 locked 가 바뀌어 이 손 읽기가 다시 시작되는데,
                    // 그 안에서 돌던 채우기가 같이 취소되면 release 가 불리지 않는다.
                    val filling = scope.launch {
                        ring.snapTo(0f)
                        ring.animateTo(1f, tween(READ_LOCK_HOLD_MS, easing = LinearEasing))
                        ring.snapTo(0f)
                        lock.release()
                    }
                    val up = waitForUpOrCancellation()
                    if (filling.isActive) {
                        filling.cancel()
                        scope.launch { ring.snapTo(0f) }
                        // 손가락이 단추 밖으로 나가 취소된 것은 누르기가 아니다 — 안내를 띄우지 않는다.
                        if (up != null) lock.tap(comicNow)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (locked) Box(Modifier.size(38.dp).clip(CircleShape).background(c.accentContainer))
        val filled = ring.value
        if (filled > 0f) {
            Canvas(Modifier.size(42.dp)) {
                drawArc(c.accent, -90f, 360f * filled, useCenter = false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        CpIcon(if (locked) CpIcons.Lock else CpIcons.Unlock, if (locked) c.accentText else c.text)
    }
}
