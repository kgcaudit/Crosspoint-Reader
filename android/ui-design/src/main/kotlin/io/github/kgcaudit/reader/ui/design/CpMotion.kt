package io.github.kgcaudit.reader.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp

/**
 * 판이 열리고 닫히는 움직임(0.55.0, 2026-10-22 사용자 확정 구상안). 모든 판이 이 값을 함께 쓴다 — 판마다 따로 적으면
 * 메뉴는 0.25초, 목차는 0.4초처럼 같은 앱 안에서 빠르기가 갈린다.
 *
 * 0.54 까지는 움직임이 하나도 없어, 판이 그 자리에 "툭" 나타났다(사용자 지적: 어디서 무엇이 열렸는지 눈이 따라가지 못한다).
 * 곡선은 Material 3 의 강조 곡선(들어올 때 느려지며 멈추고, 나갈 때 빨라지며 사라짐), 길이는 M3 기본(0.4~0.5초)보다
 * 짧게 잡았다 — 읽는 동안 자주 여닫는 메뉴라 기다림이 쌓인다(구상안 결정 3).
 *
 * 휴대폰의 "애니메이션 제거"(개발자 옵션 · 접근성)를 켜면 Compose 가 길이를 0 으로 줄여 모두 바로 나타난다 — 따로 막지 않는다.
 */
object CpMotion {
    /** 들어올 때: 빠르게 나와서 천천히 멈춘다(M3 emphasized decelerate). */
    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 나갈 때: 천천히 떠나 빠르게 사라진다(M3 emphasized accelerate). */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val BAR_IN = 250
    const val BAR_OUT = 200
    const val CARD_IN = 250
    const val CARD_OUT = 150
    const val SIDE_IN = 350
    const val SIDE_OUT = 200
    const val SCREEN_IN = 300
    const val SCREEN_OUT = 200
    const val SHEET_IN = 300
    const val SHEET_OUT = 200
    const val POPUP_IN = 200
    const val POPUP_OUT = 150

    /** 위 막대: 위에서 내려온다. 움직이는 판보다 흐려지기가 먼저 끝나야 "떨어져 나온 조각" 으로 보이지 않는다. */
    val topBarIn: EnterTransition get() = slideInVertically(tween(BAR_IN, easing = Enter)) { -it } + fadeIn(tween(BAR_IN / 2))
    val topBarOut: ExitTransition get() = slideOutVertically(tween(BAR_OUT, easing = Exit)) { -it } + fadeOut(tween(BAR_OUT))
    val bottomBarIn: EnterTransition get() = slideInVertically(tween(BAR_IN, easing = Enter)) { it } + fadeIn(tween(BAR_IN / 2))
    val bottomBarOut: ExitTransition get() = slideOutVertically(tween(BAR_OUT, easing = Exit)) { it } + fadeOut(tween(BAR_OUT))

    /** 옆 판(태블릿)과 휴대폰의 전체 화면 판: 오른쪽에서 밀려 들어온다. */
    fun sideIn(ms: Int): EnterTransition = slideInHorizontally(tween(ms, easing = Enter)) { it }
    fun sideOut(ms: Int): ExitTransition = slideOutHorizontally(tween(ms, easing = Exit)) { it }

    /** 아래 판(메모 · 각주 · 듣기): 아래에서 올라온다. */
    val sheetIn: EnterTransition get() = slideInVertically(tween(SHEET_IN, easing = Enter)) { it }
    val sheetOut: ExitTransition get() = slideOutVertically(tween(SHEET_OUT, easing = Exit)) { it }

    /** 판 뒤 막은 판과 같은 길이로 서서히 짙어진다 — 막이 먼저 "탁" 어두워지면 판보다 막이 먼저 눈에 띈다. */
    fun scrimIn(ms: Int): EnterTransition = fadeIn(tween(ms))
    fun scrimOut(ms: Int): ExitTransition = fadeOut(tween(ms))

    /** 팝업 · 카드: 연 자리([origin])에서 조금 작게 시작해 커진다. 크게 줄이면(0.8) 튀어 나오는 장난감처럼 보였다. */
    fun popIn(ms: Int, origin: TransformOrigin = TransformOrigin.Center, from: Float = 0.95f): EnterTransition =
        scaleIn(tween(ms, easing = Enter), initialScale = from, transformOrigin = origin) + fadeIn(tween(ms * 3 / 5))
    fun popOut(ms: Int, origin: TransformOrigin = TransformOrigin.Center): ExitTransition =
        scaleOut(tween(ms, easing = Exit), targetScale = 0.95f, transformOrigin = origin) + fadeOut(tween(ms))
}

/**
 * 지금 그리는 판이 [CpPanelHost] 안에서 바뀌는 중인가. 있으면 판은 이 무대의 들고 남에 맞춰 움직이고(닫힐 때도 움직인다),
 * 없으면(서재의 팝업처럼 앱이 `if` 로 띄우는 판) 처음 나타날 때만 움직인다.
 */
private val LocalCpPanelScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 리더의 판을 바꾸는 무대. [group] 이 같은 판끼리는 움직이지 않고 갈아 끼운다 — 목차에서 설정으로 넘어갈 때마다 옆 판이
 * 나갔다 다시 들어오면 정신없다. 무리가 바뀌면(메뉴 → 목차) 옛 판은 제 길로 나가고 새 판은 제 길로 들어온다. 어떻게
 * 움직이는지는 판 쪽([CpMotionBox])이 정한다 — 무대가 정하면 위 막대와 아래 막대가 같은 쪽으로 밀려 버린다.
 */
@Composable
fun <T> CpPanelHost(target: T, group: (T) -> Any?, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable (T) -> Unit) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None using null },
        contentKey = group,
        label = "panel",
    ) { shown ->
        CompositionLocalProvider(LocalCpPanelScope provides this) { content(shown) }
    }
}

/**
 * 움직이는 판 한 조각. [CpPanelHost] 안이면 무대의 들고 남을 따르고, 밖이면 처음 나타날 때 [enter] 만 한다.
 * 안쪽에는 무대를 넘겨주지 않는다 — 판 안에서 나중에 뜨는 팝업이 이미 다 들어온 무대를 따라 움직이지 않게 된다.
 */
@Composable
fun CpMotionBox(enter: EnterTransition, exit: ExitTransition, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val stage = LocalCpPanelScope.current
    CompositionLocalProvider(LocalCpPanelScope provides null) {
        if (stage != null) {
            Box(with(stage) { modifier.animateEnterExit(enter, exit) }, propagateMinConstraints = true, content = content)
        } else {
            // 첫 그림 뒤에 켠다. MutableTransitionState(false → true) 로 처음부터 켜 두면, 시험에서 화면을 닫은 뒤에도 그 전이가
            // 끝나지 않은 채 남아 다음 화면이 "쉬지 않는다" 고 멈췄다(FoldTest 앱 정보 → 덮개 화면 서재).
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { shown = true }
            AnimatedVisibility(shown, modifier, enter, ExitTransition.None, label = "appear") {
                Box(propagateMinConstraints = true, content = content)
            }
        }
    }
}

/**
 * 판과 지면 사이의 경계(0.55.0, 구상안 결정 2): 판 가장자리의 1dp 선 + 지면 쪽으로 떨어지는 옅은 그림자. 판 색이 종이와
 * 거의 같아(아이보리 · 휴대폰 밝은 테마) 0.54 까지는 메뉴가 어디서 끝나고 본문이 어디서 시작하는지 흐렸다.
 * 선은 판 안쪽에 그린다 — 판 높이(위 52 · 아래 86dp)를 1dp 도 늘리지 않는다. 그림자는 판 밖(지면 위)에 그린다.
 */
fun Modifier.cpEdge(atBottom: Boolean, line: Color): Modifier = drawWithContent {
    // 판 바탕 위에 선을 긋는다 — 바탕보다 먼저 그리면 바탕이 선을 덮는다.
    drawContent()
    val shade = EDGE_SHADOW.toPx()
    val hair = 1.dp.toPx()
    if (atBottom) {
        drawRect(line, Offset(0f, size.height - hair), Size(size.width, hair))
        drawRect(Brush.verticalGradient(listOf(EDGE_COLOR, Color.Transparent), startY = size.height, endY = size.height + shade), Offset(0f, size.height), Size(size.width, shade))
    } else {
        drawRect(line, Offset.Zero, Size(size.width, hair))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, EDGE_COLOR), startY = -shade, endY = 0f), Offset(0f, -shade), Size(size.width, shade))
    }
}

private val EDGE_SHADOW = 10.dp
private val EDGE_COLOR = Color(0x26000000)
