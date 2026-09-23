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
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val c = CpTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CpTheme.metrics.rowHeight)
            .background(if (selected) c.selection else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            CpIcon(icon, if (selected) c.accent else c.textMuted)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            CpText(title, CpTheme.type.body, c.text, maxLines = 2)
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

enum class CpBarWeight(val height: Dp) { Thin(2.dp), Medium(4.dp), Thick(6.dp) }

/** CrossPoint `drawProgressBar`. 굵기 3단계. */
@Composable
fun CpProgressBar(fraction: Float, modifier: Modifier = Modifier, weight: CpBarWeight = CpBarWeight.Thin) {
    val c = CpTheme.colors
    Box(modifier.fillMaxWidth().height(weight.height).clip(RoundedCornerShape(50)).background(c.divider)) {
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
                .clip(RoundedCornerShape(CpTheme.metrics.corner))
                .background(c.surface)
                .border(1.dp, c.divider, RoundedCornerShape(CpTheme.metrics.corner))
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
            .border(1.dp, if (primary) c.accent else c.divider, RoundedCornerShape(50))
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
                    .clip(RoundedCornerShape(50))
                    .background(if (on) c.accent else Color.Transparent)
                    .border(1.dp, if (on) c.accent else c.divider, RoundedCornerShape(50))
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { CpText(option, CpTheme.type.label, if (on) c.onAccent else c.text) }
        }
    }
}
