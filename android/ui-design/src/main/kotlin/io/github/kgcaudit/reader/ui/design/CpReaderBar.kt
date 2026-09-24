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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    tools: @Composable RowScope.() -> Unit,
) {
    val colors = CpTheme.colors
    // 막대를 끄는 동안의 값. 손을 떼기 전까지는 옮기지 않고 숫자만 바꾼다 — 끄는 동안 매번 옮기면
    // 조판·렌더가 줄줄이 쌓여 손을 뗀 뒤에도 한참 페이지가 바뀐다.
    var dragging by remember { mutableStateOf<Float?>(null) }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPanel = maxHeight * 0.55f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(WindowInsets.statusBars)) {
                CpHeader(title = title, subtitle = subtitle, onBack = onBack) {
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
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                // 판은 화면 높이의 55% 까지만, 넘으면 스크롤한다. 가로 화면(높이 393dp)에서는 일곱 줄 판이 화면을 넘어
                // 아래 줄("모든 보기 설정")이 잘려 누를 수 없었다. 진행 막대 · 도구 단추는 늘 보인다.
                // 남는 높이를 weight(fill = false) 로 나눠 주는 방식은 가로에서 끝없이 다시 재는 고리에 빠졌다
                // (TwoPageTest 에서 "Compose did not get idle") — 높이의 상한을 숫자로 준다.
                Column(Modifier.heightIn(max = maxPanel).verticalScroll(rememberScrollState())) { above() }
                val shown = dragging ?: progress
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CpSlider(
                        value = shown,
                        onChange = { dragging = it },
                        onCommit = { target ->
                            onSeek(target)
                            dragging = null
                        },
                        modifier = Modifier.weight(1f),
                        description = "읽은 위치",
                    )
                    CpText(progressLabel(shown), CpTheme.type.label, colors.text, Modifier.padding(start = 12.dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    content = tools,
                )
            }
        }
    }
}

/**
 * 리더 위를 덮는 전체 화면 판(목차·책갈피·글꼴 목록). 시스템 바를 피하고, 뒤의 지면으로 터치가
 * 새지 않게 막는다 — 막지 않으면 목록의 빈 곳을 누를 때 뒤에서 페이지가 넘어간다.
 */
@Composable
fun CpFullScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CpTheme.colors.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .clickable(indication = null, interactionSource = null) {},
        content = content,
    )
}
