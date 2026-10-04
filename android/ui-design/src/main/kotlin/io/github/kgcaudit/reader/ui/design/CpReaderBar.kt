package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 리더 위에 뜨는 얇은 도구줄: 위(뒤로 · 제목 · 책갈피)와 아래(진행 막대 · 도구 단추).
 *
 * EPUB·TXT 리더와 PDF 리더가 **같은 모양**을 쓴다. 한쪽만 고치면 책 종류에 따라 메뉴가 다르게
 * 생긴 앱이 된다. 무엇을 여는지(목차·보기·책갈피 목록)는 [tools] 로 받는다.
 *
 * @param progress 0..1. 막대를 끄는 동안에는 [progressLabel] 만 바뀌고, 손을 떼면 [onSeek] 한 번.
 * @param above 진행 막대 위에 붙는 판(보기 설정). 없으면 비운다.
 */
@Composable
fun CpReaderBar(
    title: String,
    subtitle: String?,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    progress: Float,
    progressLabel: (Float) -> String,
    onSeek: (Float) -> Unit,
    above: @Composable ColumnScope.() -> Unit = {},
    /** 본문에서 찾기(E1: 위쪽 돋보기). PDF 처럼 찾을 수 없으면 null — 단추가 없다. */
    onSearch: (() -> Unit)? = null,
    /** 듣기(L1: 위쪽 헤드폰, 찾기 옆). PDF 처럼 글자가 없으면 null — 단추가 없다(L8). */
    onListen: (() -> Unit)? = null,
    /**
     * 진행 막대가 오른쪽에서 차오른다(오→왼 만화, 0.41.0). 쪽 숫자도 왼쪽으로 옮긴다 — 막대가 시작하는 쪽 반대편 끝에
     * 서야 "여기까지 왔다" 로 읽힌다. 화면 아래 줄과 같은 방향이어야 위아래가 서로 반대로 차오르지 않는다.
     */
    progressRightToLeft: Boolean = false,
    tools: @Composable RowScope.() -> Unit,
) {
    val colors = CpTheme.colors
    // 막대를 끄는 동안의 값. 손을 떼기 전까지는 옮기지 않고 숫자만 바꾼다 — 끄는 동안 매번 옮기면
    // 조판·렌더가 줄줄이 쌓여 손을 뗀 뒤에도 한참 페이지가 바뀐다.
    var dragging by remember { mutableStateOf<Float?>(null) }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPanel = maxHeight * 0.55f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)))) {
                // 위 줄은 52dp(0.45.0, 2026-10-04 사용자 결정 — 64dp 두 줄 머리가 읽던 쪽을 너무 가렸다). 서재 머리(19sp)보다
                // 작은 제목 17sp · 부제 12sp 를 한 단에 쌓는다. 제목이 길면 한 줄에서 자른다 — 두 줄로 늘면 판이 다시 두꺼워진다.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = READER_TOP).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CpIconButton(CpIcons.Back, "뒤로", onBack)
                    Column(Modifier.weight(1f)) {
                        CpText(title, READER_TITLE, colors.text, maxLines = 1)
                        if (subtitle != null) CpText(subtitle, CpTheme.type.caption, colors.textMuted, maxLines = 1)
                    }
                    if (onSearch != null) CpIconButton(CpIcons.Search, "책에서 찾기", onClick = onSearch)
                    if (onListen != null) CpIconButton(CpIcons.Headphones, "듣기", onClick = onListen)
                    CpIconButton(
                        if (bookmarked) CpIcons.BookmarkFilled else CpIcons.Bookmark,
                        if (bookmarked) "책갈피 빼기" else "책갈피 꽂기",
                        onClick = onBookmark,
                        tint = if (bookmarked) colors.accent else colors.text,
                    )
                }
            }
            // 가운데: 지면이 보이는 곳. 누르면 닫힌다.
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .clickable(indication = null, interactionSource = null, onClick = onDismiss),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))),
            ) {
                // 판은 화면 높이의 55% 까지만, 넘으면 스크롤한다. 가로 화면(높이 393dp)에서는 일곱 줄 판이 화면을 넘어
                // 아래 줄("모든 보기 설정")이 잘려 누를 수 없었다. 진행 막대 · 도구 단추는 늘 보인다.
                // 남는 높이를 weight(fill = false) 로 나눠 주는 방식은 가로에서 끝없이 다시 재는 고리에 빠졌다
                // (TwoPageTest 에서 "Compose did not get idle") — 높이의 상한을 숫자로 준다.
                // 태블릿 모드에서는 보기 판을 가운데 [VIEW_PANEL_MAX_WIDTH] 폭으로(0.30.0). 969dp 로 늘이면 이름과 단추가
                // 화면 양 끝으로 갈라졌다. 진행 막대 · 도구 단추는 그대로 폭 전체.
                Column(
                    Modifier.align(Alignment.CenterHorizontally)
                        .then(if (cpTablet()) Modifier.widthIn(max = VIEW_PANEL_MAX_WIDTH) else Modifier)
                        .heightIn(max = maxPanel).verticalScroll(rememberScrollState()),
                ) { above() }
                val shown = dragging ?: progress
                val direction = if (progressRightToLeft) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.platform.LocalLayoutDirection.current
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
                    // 진행 줄 40dp: 막대(48dp 높이)를 줄여 넣는다. 끄는 손잡이는 18dp 라 40dp 안에서도 잡힌다.
                    Row(Modifier.fillMaxWidth().height(READER_SLIDER).padding(horizontal = CpTheme.metrics.gutter), verticalAlignment = Alignment.CenterVertically) {
                        CpSlider(
                            value = shown,
                            onChange = { dragging = it },
                            onCommit = { target ->
                                onSeek(target)
                                dragging = null
                            },
                            modifier = Modifier.weight(1f),
                            description = "지금 위치",
                            reversed = progressRightToLeft,
                        )
                        CpText(progressLabel(shown), CpTheme.type.label, colors.text, Modifier.padding(start = 12.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    content = tools,
                )
            }
        }
    }
}

/** 읽기 메뉴 치수(0.45.0): 위 52 · 아래 진행 40 + 도구 46 = 86. 합 138dp — 0.44 의 176dp 보다 38dp 덜 가린다. */
private val READER_TOP = 52.dp
private val READER_SLIDER = 40.dp
private val READER_TITLE = androidx.compose.ui.text.TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Default, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    fontSize = 17.sp, lineHeight = 22.sp,
)

/**
 * 리더 위를 덮는 전체 화면 판(목차·책갈피·글꼴 목록). 시스템 바를 피하고, 뒤의 지면으로 터치가
 * 새지 않게 막는다 — 막지 않으면 목록의 빈 곳을 누를 때 뒤에서 페이지가 넘어간다.
 *
 * 태블릿 모드([cpTablet])에서는 오른쪽 옆 판([SIDE_PANEL_WIDTH])이다(0.30.0, 구상안 가안). 폴더블 본 화면에서 목차가
 * 화면 전체를 덮으면 읽던 쪽이 사라지고, 가로에서는 목록이 969dp 로 늘어났다. 판 밖을 누르면 뒤로 가기와 같다 —
 * 한 겹만 닫힌다(설정의 하위 화면이면 설정으로).
 *
 * @param side false 면 태블릿에서도 화면 전체를 덮되 내용 폭만 [CONTENT_MAX_WIDTH] 로 줄인다(리더 밖의 화면: 서재 찾기).
 */
@Composable
fun CpFullScreen(side: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    if (!cpTablet()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(CpTheme.colors.background)
                // 카메라 구멍도 피한다(0.29.0). 가로로 읽다 목차 · 설정 · 찾기를 열면 행 앞머리나 뒤로 단추가 구멍에 가렸다.
                .windowInsetsPadding(insets)
                .blockTouches(),
            content = content,
        )
        return
    }
    val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Box(Modifier.fillMaxSize()) {
        if (side) {
            // 뒤의 쪽은 흐리게 보인다. 누르면 판을 닫는다(뒤로 가기) — 쪽이 넘어가지 않게 여기서 받는다.
            Box(
                Modifier.matchParentSize().background(SIDE_SCRIM)
                    .clickable(indication = null, interactionSource = null, onClickLabel = "닫기") { back?.onBackPressed() },
            )
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(SIDE_PANEL_WIDTH)
                    .background(CpTheme.colors.background)
                    .windowInsetsPadding(insets.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                    .blockTouches(),
                content = content,
            )
        } else {
            Box(
                Modifier.matchParentSize().background(CpTheme.colors.background).windowInsetsPadding(insets).blockTouches(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.fillMaxHeight().widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(), content = content)
            }
        }
    }
}

/** 옆 판 뒤 막. 팝업보다 옅다 — 뒤의 쪽이 읽을 만큼 보여야 "지금 어디를 읽는지" 가 남는다. */
private val SIDE_SCRIM = androidx.compose.ui.graphics.Color(0x44000000)
