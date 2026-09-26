package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
) {
    val c = CpTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) CpTheme.metrics.touchTarget else CpTheme.metrics.rowHeight)
            .background(if (selected) c.accentContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CpTheme.metrics.gutter, vertical = if (compact) 4.dp else 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            if (tile != null) CpTile(icon, tile) else CpIcon(icon, if (selected) c.accent else c.textMuted)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            CpText(title, CpTheme.type.body, if (selected) c.onAccentContainer else c.text, maxLines = 2)
            if (subtitle != null) CpText(subtitle, CpTheme.type.subtitle, c.textMuted)
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            CpText(value, CpTheme.type.caption, if (selected) c.accent else c.textMuted)
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
                    .clickable(role = Role.Tab) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CpText(label, CpTheme.type.label, if (on) c.accent else c.textMuted, Modifier.padding(vertical = 10.dp))
                Box(Modifier.fillMaxWidth().height(if (on) 3.dp else 1.dp).background(if (on) c.accent else c.divider))
            }
        }
    }
}

// ── 6·7. 상태바 · 진행바 ────────────────────────────────────────────

enum class CpBarWeight(val height: Dp) { Thin(2.dp), Medium(4.dp) }

/** CrossPoint `drawProgressBar`. 굵기 3단계. */
@Composable
fun CpProgressBar(fraction: Float, modifier: Modifier = Modifier, weight: CpBarWeight = CpBarWeight.Thin) {
    val c = CpTheme.colors
    Box(modifier.fillMaxWidth().height(weight.height).clip(RoundedCornerShape(50)).background(c.progressTrack)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(weight.height)
                .background(c.accent),
        )
    }
}

/**
 * 리더 아래쪽 한 줄. 왼쪽 제목 · 가운데 쪽 · 오른쪽 퍼센트 + 얇은 진행바.
 * CrossPoint `drawStatusBar` 에서 배터리를 뺐다 — 휴대폰은 시스템이 보여 준다.
 */
@Composable
fun CpStatusBar(
    title: String,
    page: String,
    percent: String,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter)) {
        CpProgressBar(progress, weight = CpBarWeight.Thin)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CpText(title, CpTheme.type.caption, color, Modifier.weight(1f))
            CpText(page, CpTheme.type.caption, color, Modifier.padding(horizontal = 12.dp))
            CpText(percent, CpTheme.type.caption, color, Modifier.widthIn(min = 40.dp), align = TextAlign.End)
        }
    }
}

// ── 8. 팝업 ─────────────────────────────────────────────────────────

/**
 * 가운데 틀. 제목 + 문장 + (있으면) 진행. CrossPoint `drawPopup` + `fillPopupProgress`.
 * 바깥을 누르면 [onDismiss] — 진행 중(닫을 수 없음)이면 null 을 준다.
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
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(enabled = onDismiss != null, indication = null, interactionSource = null) { onDismiss?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(CpTheme.metrics.cornerDialog))
                .background(c.dialog)
                // 틀 안을 눌러도 닫히지 않게 한다.
                .clickable(indication = null, interactionSource = null) {}
                .padding(20.dp),
        ) {
            CpText(title, CpTheme.type.label.copy(fontSize = CpTheme.type.body.fontSize), c.text, maxLines = 2)
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                CpText(message, CpTheme.type.subtitle, c.textMuted, maxLines = 6)
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

@Composable
fun CpButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true) {
    val c = CpTheme.colors
    Box(
        modifier
            .heightIn(min = CpTheme.metrics.touchTarget)
            .clip(RoundedCornerShape(50))
            .background(if (primary) c.accent else Color.Transparent)
            .border(1.dp, if (primary) c.accent else c.outline, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { CpText(text, CpTheme.type.label, if (primary) c.onAccent else c.text) }
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
    Row(
        modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(label, CpTheme.type.body, c.text, Modifier.weight(1f))
        options.forEachIndexed { i, option ->
            val on = i == selected
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(CpTheme.metrics.cornerSmall))
                    .background(if (on) c.accent else Color.Transparent)
                    .border(1.dp, if (on) c.accent else c.outline, RoundedCornerShape(CpTheme.metrics.cornerSmall))
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { CpText(option, CpTheme.type.label, if (on) c.onAccent else c.text) }
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
        CpText(value, CpTheme.type.label, c.accent, Modifier.widthIn(max = 200.dp))
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
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = CpTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = CpTheme.metrics.rowHeight)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(start = CpTheme.metrics.gutter, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(CpTheme.metrics.cornerMedium))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CpIcon(icon, tint)
        CpText(label, CpTheme.type.caption, tint, Modifier.padding(top = 2.dp))
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
) {
    val c = CpTheme.colors
    val latest = androidx.compose.runtime.rememberUpdatedState(value)
    // pointerInput(Unit) 은 처음 받은 람다를 끝까지 쥔다. 부르는 쪽이 바뀐 값을 담은 람다를 다시 넘겨도
    // 옛것이 불린다 — 최신 것을 거쳐 부른다.
    val change = androidx.compose.runtime.rememberUpdatedState(onChange)
    val commit = androidx.compose.runtime.rememberUpdatedState(onCommit)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(CpTheme.metrics.touchTarget)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val v = (offset.x / size.width).coerceIn(0f, 1f)
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
                    change.value((pointer.position.x / size.width).coerceIn(0f, 1f))
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
