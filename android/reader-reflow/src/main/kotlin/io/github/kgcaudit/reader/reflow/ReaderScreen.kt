package io.github.kgcaudit.reader.reflow

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.PlacedImage
import io.github.kgcaudit.reader.text.AndroidTextMeasurer
import io.github.kgcaudit.reader.text.ReaderFont
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpStatusBar
import io.github.kgcaudit.reader.ui.design.CpStepper
import io.github.kgcaudit.reader.ui.design.CpTabBar
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 리플로우 리더.
 *
 * 화면 전체가 지면이다. 왼쪽 3분의 1 을 누르면 앞 장, 오른쪽 3분의 1 은 다음 장,
 * 가운데는 메뉴. 옆으로 밀어도 넘어간다. CrossPoint 의 물리 버튼 자리를 그대로 터치
 * 영역으로 옮겼다.
 *
 * @param onChrome 메뉴가 열리고 닫힐 때. 앱이 시스템 바를 보이고 숨기는 데 쓴다.
 */
@Composable
fun ReaderScreen(
    reader: BookReader,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
) {
    val state by reader.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val colors = CpTheme.colors
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(menu) { onChrome(menu) }
    BackHandler { if (menu) menu = false else onClose() }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.paper)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val cutout = WindowInsets.displayCutout
        val margin = with(density) {
            Insets(
                left = 24.dp.toPx() + cutout.getLeft(this, direction),
                top = 34.dp.toPx() + cutout.getTop(this),
                right = 24.dp.toPx() + cutout.getRight(this, direction),
                // 상태바(28dp) + 숨 쉴 틈. 본문이 상태바 밑으로 들어가면 마지막 줄이 가려진다.
                bottom = 56.dp.toPx() + cutout.getBottom(this),
            )
        }
        // 1sp 가 몇 px 인가. 시스템 글자 크기 설정(fontScale)을 따른다.
        val pxPerSp = density.density * density.fontScale
        val spec = remember(widthPx, heightPx, margin, prefs, pxPerSp) {
            prefs.toSpec(widthPx, heightPx, margin, pxPerSp)
        }
        LaunchedEffect(spec) { runCatching { reader.layOut(spec) } }

        // 그리기 전용 측정기. 조판에 쓴 설정(state.spec)으로 만든다 — 새 설정으로 조판이
        // 끝나기 전까지는 옛 페이지를 옛 글꼴로 그려야 한다.
        val painter = remember(state.spec) { state.spec?.let { AndroidTextMeasurer.forSpec(context, it) } }
        val images = remember(state.page) { mutableStateMapOf<PlacedImage, ImageBitmap>() }
        LaunchedEffect(state.page) {
            val page = state.page ?: return@LaunchedEffect
            val spine = state.position?.spineIndex ?: return@LaunchedEffect
            for (placed in page.images) {
                reader.image(spine, placed.href, placed.widthPx.roundToInt(), placed.heightPx.roundToInt())
                    ?.let { images[placed] = it }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(reader) {
                    detectTapGestures { offset ->
                        when {
                            menu -> menu = false
                            offset.x < size.width * 0.3f -> scope.go { reader.previous() }
                            offset.x > size.width * 0.7f -> scope.go { reader.next() }
                            else -> menu = true
                        }
                    }
                }
                .pointerInput(reader) {
                    var dragged = 0f
                    val threshold = 48.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            if (abs(dragged) > threshold) {
                                if (dragged < 0) scope.go { reader.next() } else scope.go { reader.previous() }
                            }
                        },
                    ) { _, amount -> dragged += amount }
                },
        ) {
            val page = state.page
            if (page != null && painter != null) drawPage(page, state.text, painter, colors.ink, images)
        }

        val position = state.position
        CpStatusBar(
            title = reader.title,
            page = if (position != null) "${position.pageIndex + 1} / ${position.pageCount}" else "",
            percent = "${state.percent.roundToInt()}%",
            progress = state.percent / 100f,
            color = colors.inkMuted,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).windowInsetsPadding(cutout),
        )

        if (menu) ReaderMenu(reader, state, prefs, onPrefsChange, onClose, onDismiss = { menu = false }, scope)

        if (state.busy && state.page == null) CpPopup(title = "책을 펼치는 중…", progress = null)
        state.error?.let { message ->
            if (state.page == null) {
                CpPopup(title = "이 책을 열지 못했습니다", message = message, onDismiss = onClose) {
                    Spacer(Modifier.height(16.dp))
                    CpButton("라이브러리로", onClose)
                }
            }
        }
    }
}

@Composable
private fun ReaderMenu(
    reader: BookReader,
    state: ReaderState,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
    scope: CoroutineScope,
) {
    val colors = CpTheme.colors
    var tab by remember { mutableIntStateOf(0) }
    var toc by remember { mutableStateOf<List<TocEntry>?>(null) }
    var marks by remember { mutableStateOf<List<Bookmark>?>(null) }
    LaunchedEffect(Unit) { toc = runCatching { reader.outline() }.getOrDefault(emptyList()) }
    LaunchedEffect(tab, state.bookmarked) { if (tab == 1) marks = runCatching { reader.bookmarks() }.getOrDefault(emptyList()) }

    Column(Modifier.fillMaxSize()) {
        // 위: 제목 · 뒤로 · 책갈피
        Column(Modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(WindowInsets.statusBars)) {
            val position = state.position
            CpHeader(
                title = reader.title,
                subtitle = if (position != null) "${position.spineIndex + 1} / ${state.chapterCount} 장 · ${state.percent.roundToInt()}%" else null,
                onBack = onClose,
            ) {
                CpIconButton(
                    if (state.bookmarked) CpIcons.BookmarkFilled else CpIcons.Bookmark,
                    if (state.bookmarked) "책갈피 빼기" else "책갈피 꽂기",
                    onClick = { scope.go { reader.toggleBookmark() } },
                    tint = if (state.bookmarked) colors.accent else colors.text,
                )
            }
        }
        // 가운데: 누르면 닫힌다(지면이 보이는 곳)
        Box(Modifier.weight(1f).fillMaxWidth().clickable(indication = null, interactionSource = null, onClick = onDismiss))
        // 아래: 목차 · 책갈피 · 보기
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            CpTabBar(listOf("목차", "책갈피", "보기"), tab, { tab = it })
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                when (tab) {
                    0 -> TocList(toc, state) { entry -> scope.go { reader.goTo(entry) }; onDismiss() }
                    1 -> BookmarkList(
                        marks,
                        onOpen = { mark -> scope.go { reader.goTo(mark) }; onDismiss() },
                        onRemove = { mark -> scope.go { reader.removeBookmark(mark); marks = reader.bookmarks() } },
                    )
                    else -> ViewSettings(prefs, onPrefsChange)
                }
            }
        }
    }
}

@Composable
private fun TocList(entries: List<TocEntry>?, state: ReaderState, onOpen: (TocEntry) -> Unit) {
    val colors = CpTheme.colors
    when {
        entries == null -> Unit
        entries.isEmpty() -> Empty("목차가 없는 책입니다")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { entry ->
                val spine = (entry.locator as? io.github.kgcaudit.reader.document.Locator.Reflow)?.spine
                CpListRow(
                    title = entry.label,
                    onClick = { onOpen(entry) },
                    modifier = Modifier.padding(start = (entry.depth * 16).dp),
                    selected = spine != null && spine == state.position?.spineIndex && entry.anchor == null,
                )
            }
        }
    }
}

@Composable
private fun BookmarkList(marks: List<Bookmark>?, onOpen: (Bookmark) -> Unit, onRemove: (Bookmark) -> Unit) {
    when {
        marks == null -> Unit
        marks.isEmpty() -> Empty("책갈피가 없습니다. 위의 책갈피 단추로 이 페이지에 꽂을 수 있습니다")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(marks, key = { it.id }) { mark ->
                val where = (mark.locator as? io.github.kgcaudit.reader.document.Locator.Reflow)?.let { "${it.spine + 1}장" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpListRow(
                        title = mark.snippet ?: "(내용 없음)",
                        subtitle = where,
                        icon = CpIcons.Bookmark,
                        onClick = { onOpen(mark) },
                        modifier = Modifier.weight(1f),
                    )
                    CpIconButton(CpIcons.Close, "책갈피 지우기", { onRemove(mark) }, tint = CpTheme.colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun ViewSettings(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        CpStepper("글자 크기", "${prefs.fontSizeSp}", { onChange(prefs.withSize(-1)) }, { onChange(prefs.withSize(+1)) })
        val fonts = ReaderFont.entries
        CpChoice("글꼴", fonts.map { it.label }, fonts.indexOf(prefs.font), { onChange(prefs.copy(font = fonts[it])) })
        val spacings = ReaderPrefs.LineSpacing.entries
        CpChoice("줄 간격", spacings.map { it.label }, spacings.indexOf(prefs.lineSpacing), {
            onChange(prefs.copy(lineSpacing = spacings[it]))
        })
    }
}

@Composable
private fun Empty(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CpText(message, CpTheme.type.subtitle, CpTheme.colors.textMuted, maxLines = 3)
    }
}

/** 한글 표시 이름. 설정 저장은 [ReaderFont.key] 로 한다. */
val ReaderFont.label: String
    get() = when (this) {
        ReaderFont.Batang -> "바탕"
        ReaderFont.Gothic -> "고딕"
    }

/**
 * 동작 하나를 띄운다. 실패는 [BookReader.state] 의 error 로 화면에 가고, 여기서는 로그만
 * 남긴다 — 아무 흔적 없이 삼키면 "단추가 먹통" 인 원인을 기기에서 찾을 수 없다.
 */
private fun CoroutineScope.go(block: suspend () -> Unit) {
    launch {
        runCatching { block() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w("OloReader", "reader action failed", it) }
    }
}
