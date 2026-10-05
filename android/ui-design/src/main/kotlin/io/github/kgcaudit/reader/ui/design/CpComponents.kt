package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── 기본 ────────────────────────────────────────────────────────────

@Composable
fun CpText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = align ?: TextAlign.Unspecified),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun CpIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(icon, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/**
 * 색 타일 위에 흰 글리프. OLO Explorer 의 `FileTile` 과 같은 모양이다.
 *
 * 연한 칩 위에 작은 선 그림을 얹던 것을 바꿨다. 행을 훑어 내릴 때는 그림이 아니라 색이
 * 먼저 읽힌다 — 이름을 읽기 전에 "무슨 종류인가" 가 보여야 한다.
 */
@Composable
fun CpTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    val m = CpTheme.metrics
    Box(
        modifier.size(m.tileSize).clip(RoundedCornerShape(m.tileCorner)).background(color),
        contentAlignment = Alignment.Center,
    ) { CpIcon(icon, Color.White, size = m.tileSize * 0.58f) }
}

/** 아이콘 단추. 그림은 24dp 여도 누르는 영역은 48dp 다. */
@Composable
fun CpIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CpTheme.colors.text,
) {
    Box(
        modifier = modifier
            .size(CpTheme.metrics.touchTarget)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { CpIcon(icon, tint) }
}

// ── 1. 헤더 ─────────────────────────────────────────────────────────

/** 화면 위쪽. 제목과 부제, 왼쪽 되돌아가기, 오른쪽 동작. CrossPoint `drawHeader`. */
@Composable
fun CpHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = CpTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CpTheme.metrics.headerHeight)
            .padding(horizontal = if (onBack != null) 4.dp else CpTheme.metrics.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) CpIconButton(CpIcons.Back, "뒤로", onBack)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            CpText(title, CpTheme.type.title, c.text)
            if (subtitle != null) CpText(subtitle, CpTheme.type.subtitle, c.textMuted)
        }
        actions()
    }
}

/** 목록 위 작은 제목("최근에 읽은 책"). */
@Composable
fun CpSectionLabel(text: String, modifier: Modifier = Modifier) {
    CpText(
        text,
        CpTheme.type.label,
        CpTheme.colors.textMuted,
        modifier.padding(start = CpTheme.metrics.gutter, end = CpTheme.metrics.gutter, top = 18.dp, bottom = 6.dp),
    )
}

// ── 2. 목록 ─────────────────────────────────────────────────────────

/**
 * 목록 한 줄. 아이콘 · 제목 · 부제 · 오른쪽 값 · 비활성. CrossPoint `drawList`.
 *
 * 비활성 행도 누를 수 있다([onClick] 이 불린다). 왜 안 되는지 알려 주는 편이 아무
 * 반응이 없는 것보다 낫다 — 화면이 알림을 띄울지 정한다.
 */
// 길게 누르기(combinedClickable)가 이 판의 Compose 에서는 아직 실험 딱지다. 쓰임새가 흔한 것이라 받아들인다.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CpListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** 주면 [icon] 을 이 색 타일 위에 흰색으로 그린다. 없으면 흐린 선 아이콘. */
    tile: Color? = null,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    /**
     * 한 줄짜리 목록(목차)용. 터치 최소값(48dp)까지 줄인다 — 부제가 있는 행 높이(64dp)를
     * 한 줄에도 쓰면 목차 39개 중 한 화면에 다섯 개만 보인다.
     */
    compact: Boolean = false,
    /** 아이콘 자리에 대신 그릴 것(책장의 작은 표지). 주면 [icon] 은 쓰지 않는다. */
    leading: (@Composable () -> Unit)? = null,
    /** 길게 누름(책 표지 바꾸기). 없으면 길게 눌러도 [onClick] 과 같다. */
    onLongClick: (() -> Unit)? = null,
    /** 좌우 안쪽 여백. 팝업 안에서는 0 — 판의 글자 시작선에 맞춘다(판이 이미 안쪽 여백을 두었다). */
    inset: Dp = CpTheme.metrics.gutter,
) {
    val c = CpTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) CpTheme.metrics.touchTarget else CpTheme.metrics.rowHeight)
            .background(if (selected) c.accentContainer else Color.Transparent)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = inset, vertical = if (compact) 4.dp else 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            if (tile != null) CpTile(icon, tile) else CpIcon(icon, if (selected) c.accent else c.textMuted)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            CpText(title, CpTheme.type.body, if (selected) c.onAccentContainer else c.text, maxLines = 2)
            if (subtitle != null) CpText(subtitle, CpTheme.type.subtitle, c.textMuted)
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            CpText(value, CpTheme.type.caption, if (selected) c.accentText else c.textMuted)
        }
    }
}

@Composable
fun CpDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CpTheme.metrics.gutter)
            .height(1.dp)
            .background(CpTheme.colors.divider),
    )
}

// ── 5. 탭 ───────────────────────────────────────────────────────────

/** 목차/책갈피 전환. CrossPoint `drawTabBar`. */
@Composable
fun CpTabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter)) {
        tabs.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = CpTheme.metrics.touchTarget)
                    .selectable(selected = on, role = Role.Tab) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CpText(label, CpTheme.type.label, if (on) c.accentText else c.textMuted, Modifier.padding(vertical = 10.dp))
                Box(Modifier.fillMaxWidth().height(if (on) 3.dp else 1.dp).background(if (on) c.accent else c.divider))
            }
        }
    }
}

// ── 6·7. 상태바 · 진행바 ────────────────────────────────────────────

enum class CpBarWeight(val height: Dp) { Thin(2.dp), Medium(4.dp) }

/** CrossPoint `drawProgressBar`. 굵기 3단계. */
@Composable
fun CpProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    weight: CpBarWeight = CpBarWeight.Thin,
    /** 빈 곳 색. 지면 위(하단 정보)는 지면에 맞춘 색을 준다 — progressTrack 은 UI 바탕용이라 세피아 · 회색에서 묻혔다. */
    track: Color = CpTheme.colors.progressTrack,
) {
    val c = CpTheme.colors
    Box(modifier.fillMaxWidth().height(weight.height).clip(RoundedCornerShape(50)).background(track)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(weight.height)
                .background(c.accent),
        )
    }
}

/**
 * 아이콘 몇 개 중 하나를 고르는 두세 칸 단추(격자 | 목록). 고른 칸만 강조색 바탕 — 지금 어떤 보기인지 단추가 알린다.
 * 화면 읽기에는 칸마다 [labels] 와 고른 상태(`selectable`)를 알린다.
 */
@Composable
fun CpIconToggle(
    icons: List<ImageVector>,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CpTheme.colors
    val shape = RoundedCornerShape(CpTheme.metrics.cornerSmall + 2.dp)
    // 칸마다 누르는 곳은 48dp(0.29.0). 보이는 틀은 36dp 높이 그대로 — 40×34dp 칸은 엄지로 옆 칸을 누르기 쉬웠다.
    Box(modifier) {
        Box(Modifier.matchParentSize().padding(vertical = 6.dp).clip(shape).border(1.dp, c.outline, shape))
        Row {
            icons.forEachIndexed { i, icon ->
                val on = i == selected
                Box(
                    Modifier
                        .size(CpTheme.metrics.touchTarget)
                        .selectable(selected = on, role = Role.RadioButton) { onSelect(i) }
                        .semantics { contentDescription = labels[i] }
                        .padding(vertical = 6.dp)
                        .clip(shape)
                        .background(if (on) c.accentContainer else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { CpIcon(icon, if (on) c.accent else c.textMuted, size = 20.dp) }
            }
        }
    }
}

/**
 * 누름을 받아 뒤로 흘리지 않는다 — 판 안을 눌러도 뒤의 막이 판을 닫거나 쪽이 넘어가지 않게. 빈 `clickable {}` 로 막던
 * 때는 그 틀이 "누를 수 있는 한 덩어리" 가 되어, 화면 읽기(TalkBack)가 안의 제목 · 안내를 하나씩 읽지 못하고 화면만 한
 * 누름 단추로 읽었다. 이것은 의미 정보를 더하지 않는다.
 */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) { detectTapGestures { } }

// ── 8. 팝업 ─────────────────────────────────────────────────────────

/**
 * 가운데 틀. 제목 + 문장 + (있으면) 진행. CrossPoint `drawPopup` + `fillPopupProgress`.
 * 바깥을 누르면 [onDismiss] — 진행 중(닫을 수 없음)이면 null 을 준다. 뒤로 가기도 [onDismiss] 다.
 *
 * 판은 굴러간다(0.28.3). 가로 폰(높이 393dp)이나 큰 글자에서 판이 화면보다 길면 마지막 줄(닫기 · 가져오기 단추,
 * 아래쪽 폴더의 ✕)이 높이 0 으로 눌려 보이지도 눌리지도 않았다.
 */
@Composable
fun CpPopup(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    progress: Float? = null,
    onDismiss: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val c = CpTheme.colors
    // 팝업마다 따로 달면 빠뜨린다 — 홈의 책 폴더 · 순서 · 표지 판이 그래서 뒤로 가기에 닫히지 않고 앱이 나갔다.
    // 나중에 그려진 이것이 화면의 뒤로 가기보다 먼저 받는다.
    androidx.activity.compose.BackHandler(enabled = onDismiss != null) { onDismiss?.invoke() }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 바깥 막은 판의 형제로 뒤에 둔다. 판을 감싸는 부모가 누를 수 있으면 판 안의 글이 모두 그 한 덩어리에 묶여 화면
        // 읽기가 하나씩 읽지 못했다. 막은 그 자체로 "닫기" 단추로 읽힌다.
        Box(
            Modifier
                .matchParentSize()
                .background(Color(0x66000000))
                .then(
                    if (onDismiss != null) Modifier.clickable(indication = null, interactionSource = null, onClickLabel = "닫기") { onDismiss() }
                    else Modifier.blockTouches(),
                ),
        )
        Column(
            Modifier
                .padding(32.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(CpTheme.metrics.cornerDialog))
                .background(c.dialog)
                // 틀 안을 눌러도 닫히지 않게 한다.
                .blockTouches()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            CpText(title, CpTheme.type.label.copy(fontSize = CpTheme.type.body.fontSize), c.text, maxLines = 2)
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                // 줄 수를 막지 않는다 — 판이 굴러가므로, 6줄에서 자르면 긴 주소 · 오류 문장의 끝이 사라졌다.
                CpText(message, CpTheme.type.subtitle, c.textMuted, maxLines = Int.MAX_VALUE)
            }
            if (progress != null) {
                Spacer(Modifier.height(16.dp))
                CpProgressBar(progress, weight = CpBarWeight.Medium)
            }
            content()
        }
    }
}

// ── 단추 ────────────────────────────────────────────────────────────

/**
 * 팝업 아래 단추 줄. 언제나 오른쪽 끝에 붙인다(0.29.0, 구상안 가안) — 팝업마다 왼쪽 · 오른쪽 · 반반으로 달라 확인 단추를
 * 찾아 손이 헤맸다. 여럿이면 덜 중요한 것이 왼쪽, 할 일이 맨 오른쪽.
 */
@Composable
fun CpPopupButtons(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun CpButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true) {
    val c = CpTheme.colors
    Box(
        modifier
            .heightIn(min = CpTheme.metrics.touchTarget)
            // 알약이 아니라 행 · 선택지와 같은 10dp(0.31.0, OLO-Design "알약 · 완전 둥근 모양을 쓰지 않는다").
            // 알약 단추는 바로 옆 선택지 칸(10dp)과 다른 앱의 부품처럼 보였다.
            .clip(RoundedCornerShape(CpTheme.metrics.cornerSmall))
            .background(if (primary) c.accent else Color.Transparent)
            .border(1.dp, if (primary) c.accent else c.outline, RoundedCornerShape(CpTheme.metrics.cornerSmall))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { CpText(text, CpTheme.type.label, if (primary) c.onAccent else c.text) }
}

/**
 * 테두리 없는 글자 단추("내보내기" · "목록" · "들어 보기"). 글자는 accentText — accent 는 채우는 색이라 밝은 바탕 위
 * 글자로는 대비가 4.5 에 못 미쳤다(0.29.0). 누르는 곳은 48dp.
 */
@Composable
fun CpTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = CpTheme.colors.accentText) {
    CpText(
        text,
        CpTheme.type.label,
        color,
        modifier
            .heightIn(min = CpTheme.metrics.touchTarget)
            .clip(RoundedCornerShape(CpTheme.metrics.cornerSmall))
            .clickable(role = Role.Button, onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 12.dp),
    )
}

/** 설정 한 줄: 이름 · [−] 값 [+]. 글자 크기 같은 단계 값에 쓴다. */
@Composable
fun CpStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(label, CpTheme.type.body, c.text, Modifier.weight(1f))
        CpIconButton(CpIcons.Minus, "$label 줄이기", onMinus)
        CpText(value, CpTheme.type.label, c.text, Modifier.widthIn(min = 44.dp), align = TextAlign.Center)
        CpIconButton(CpIcons.Plus, "$label 늘리기", onPlus)
    }
}

/** 설정 한 줄: 이름 · 선택지 몇 개 중 하나. */
@Composable
fun CpChoice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    // 이름과 선택지가 한 줄에 들어가면 나란히, 모자라면 선택지를 이름 아래 줄로 내린다(0.29.0, 구상안 가안). 한 줄에
    // 우겨 넣던 때는 360dp 폰에서 "자동 넘…" 으로 잘렸고, 큰 글자에서는 이름이 "…" 만 남았다. 아래 줄의 선택지는 이름
    // 글자 시작선에서 시작한다 — 이 줄에 속한 것이다.
    androidx.compose.ui.layout.Layout(
        contents = listOf(
            { CpText(label, CpTheme.type.body, c.text, maxLines = 3) },
            {
                // 선택지가 화면보다 넓으면(큰 글자 · 좁은 폰에 다섯 개) 옆으로 밀어 본다 — 그냥 두면 끝 선택지가 화면 밖에
                // 잘려 누를 수 없었다.
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    options.forEachIndexed { i, option ->
                        val on = i == selected
                        Box(
                            Modifier
                                .padding(end = if (i == options.lastIndex) 0.dp else 6.dp)
                                // 누르는 곳은 48dp, 보이는 칸은 40dp.
                                .heightIn(min = CpTheme.metrics.touchTarget)
                                .selectable(selected = on, role = Role.RadioButton) { onSelect(i) }
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(CpTheme.metrics.cornerSmall))
                                .background(if (on) c.accent else Color.Transparent)
                                .border(1.dp, if (on) c.accent else c.outline, RoundedCornerShape(CpTheme.metrics.cornerSmall))
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { CpText(option, CpTheme.type.label, if (on) c.onAccent else c.text) }
                    }
                }
            },
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 2.dp),
    ) { (labelM, optionsM), constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val chipsWant = optionsM.first().maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        val chips = optionsM.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = width))
        val labelWant = labelM.first().maxIntrinsicWidth(chips.height)
        if (labelWant + gap + chipsWant <= width) {
            val text = labelM.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = width - gap - chips.width))
            val h = maxOf(text.height, chips.height)
            layout(width, h) {
                text.place(0, (h - text.height) / 2)
                chips.place(width - chips.width, (h - chips.height) / 2)
            }
        } else {
            val text = labelM.first().measure(androidx.compose.ui.unit.Constraints(maxWidth = width))
            val top = 10.dp.roundToPx()
            layout(width, top + text.height + chips.height) {
                text.place(0, top)
                chips.place(0, top + text.height)
            }
        }
    }
}

/**
 * 설정 한 줄: 이름 · 지금 값 · ›. 누르면 고르는 화면이 따로 열린다.
 *
 * 선택지가 몇 개로 정해지지 않는 설정(사용자가 넣은 글꼴)에 쓴다. [CpChoice] 에 단추를
 * 늘어놓으면 네 개만 넘어도 한 줄을 넘친다.
 */
@Composable
fun CpLinkRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = CpTheme.metrics.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = CpTheme.metrics.gutter, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(label, CpTheme.type.body, c.text, Modifier.weight(1f))
        CpText(value, CpTheme.type.label, c.accentText, Modifier.widthIn(max = 200.dp))
        CpIcon(CpIcons.Forward, c.textMuted, Modifier.padding(start = 4.dp), size = 20.dp)
    }
}

/**
 * 하나만 고르는 목록의 한 줄. 왼쪽 동그라미 · 제목 · 부제 · (오른쪽 동작).
 *
 * [titleStyle] 을 따로 받는 이유: 글꼴 목록은 이름을 **그 글꼴로** 그려야 고르기 전에 모양을
 * 본다(삼성 설정의 글꼴 목록과 같다).
 */
@Composable
fun CpRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleStyle: TextStyle = CpTheme.type.body,
    /** 왼쪽 안쪽 여백. 팝업 안에서는 0([CpListRow] 와 같다). */
    inset: Dp = CpTheme.metrics.gutter,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = CpTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = CpTheme.metrics.rowHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = inset, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(2.dp, if (selected) c.accent else c.outline, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(c.accent))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            CpText(title, titleStyle, c.text, maxLines = 1)
            if (subtitle != null) CpText(subtitle, CpTheme.type.subtitle, c.textMuted, maxLines = 2)
        }
        trailing()
    }
}

// ── 도구줄 ──────────────────────────────────────────────────────────

private val TOOL_LABEL = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Default, fontSize = 11.sp, lineHeight = 14.sp)

/** 아이콘 아래 이름을 단 단추. 리더 도구줄의 "목차 · 책갈피 · 보기". */
@Composable
fun CpToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val c = CpTheme.colors
    val tint = if (selected) c.accent else c.text
    // 46dp(0.45.0): 그림 22 + 이름 11sp. 0.44 의 56dp + 위아래 여백은 읽기 메뉴 아래 판을 112dp 로 키웠다. 누르는 칸은 폭이
    // 넉넉해(세 단추가 줄을 나눠 가진다) 높이를 줄여도 손가락이 빗나가지 않는다.
    Column(
        modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(CpTheme.metrics.cornerMedium))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CpIcon(icon, tint, size = 22.dp)
        // 그림은 accent(3:1 이면 된다), 글자는 accentText — 고른 "보기" 이름이 밝은 판에서 대비 4.1 이었다(0.29.0).
        CpText(label, TOOL_LABEL, if (selected) c.accentText else c.text, Modifier.padding(top = 1.dp))
    }
}

/**
 * 진행 막대. 누르거나 끌어서 위치를 고른다.
 *
 * 끄는 동안에는 [onChange] 로 값만 알리고, 손을 뗄 때 [onCommit] 한 번으로 실제로 옮긴다.
 * 끄는 내내 옮기면 챕터마다 조판이 돌아 막대가 손을 따라오지 못한다.
 */
@Composable
fun CpSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String = "위치",
    /**
     * 오른쪽에서 차오른다(0.41.0, 오→왼 만화). 그리기는 배치 방향을 뒤집어 맞추지만 손가락 좌표는 뒤집히지 않는다 — 누른
     * 자리를 따로 뒤집지 않으면 오른쪽 끝을 눌러 첫 쪽으로 가려다 마지막 쪽으로 갔다.
     */
    reversed: Boolean = false,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            if (reversed) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.platform.LocalLayoutDirection.current,
    ) { SliderBody(value, onChange, onCommit, modifier, description, reversed) }
}

@Composable
private fun SliderBody(value: Float, onChange: (Float) -> Unit, onCommit: (Float) -> Unit, modifier: Modifier, description: String, reversed: Boolean) {
    val c = CpTheme.colors
    val latest = androidx.compose.runtime.rememberUpdatedState(value)
    val flip = androidx.compose.runtime.rememberUpdatedState(reversed)
    fun at(x: Float, width: Int): Float = (x / width).coerceIn(0f, 1f).let { if (flip.value) 1f - it else it }
    // pointerInput(Unit) 은 처음 받은 람다를 끝까지 쥔다. 부르는 쪽이 바뀐 값을 담은 람다를 다시 넘겨도
    // 옛것이 불린다 — 최신 것을 거쳐 부른다.
    val change = androidx.compose.runtime.rememberUpdatedState(onChange)
    val commit = androidx.compose.runtime.rememberUpdatedState(onCommit)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(CpTheme.metrics.touchTarget)
            // 화면 읽기(TalkBack)가 지금 값을 읽고, 위아래 쓸기 · 음량 단추로 옮길 수 있게 한다(0.28.3). 이름만 있을 때는
            // "읽은 위치" 라고만 읽고 값을 바꿀 길이 없었다. 누르기 동작을 따로 두는 까닭: 없으면 두 번 누르기가 막대
            // 가운데를 누른 것으로 흉내 내져 책이 50% 자리로 건너뛰었다.
            .semantics {
                contentDescription = description
                stateDescription = "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%"
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                setProgress { target ->
                    val v = target.coerceIn(0f, 1f)
                    change.value(v)
                    commit.value(v)
                    true
                }
                onClick { true }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val v = at(offset.x, size.width)
                    change.value(v)
                    commit.value(v)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { commit.value(latest.value) },
                    // 끄는 도중 끊겨도(시스템 제스처가 가로챔) 끝낸 것으로 본다. 그냥 두면 부르는 쪽이
                    // "끄는 중" 에 머물러 막대와 숫자가 멈춘다.
                    onDragCancel = { commit.value(latest.value) },
                ) { pointer, _ ->
                    change.value(at(pointer.position.x, size.width))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val fraction = value.coerceIn(0f, 1f)
        CpProgressBar(fraction, weight = CpBarWeight.Medium)
        val thumb = 18.dp
        Box(
            Modifier
                .padding(start = (maxWidth - thumb) * fraction)
                .size(thumb)
                .clip(RoundedCornerShape(50))
                .background(c.accent),
        )
    }
}
