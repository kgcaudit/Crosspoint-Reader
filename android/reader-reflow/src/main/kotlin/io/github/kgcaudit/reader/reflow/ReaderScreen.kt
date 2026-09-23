package io.github.kgcaudit.reader.reflow

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.PlacedImage
import io.github.kgcaudit.reader.text.AndroidTextMeasurer
import io.github.kgcaudit.reader.text.FontCatalog
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpSlider
import io.github.kgcaudit.reader.ui.design.CpStatusBar
import io.github.kgcaudit.reader.ui.design.CpStepper
import io.github.kgcaudit.reader.ui.design.CpTabBar
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToolButton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val colors = CpTheme.colors
    var panel by remember { mutableStateOf(Panel.None) }
    // 글꼴을 넣거나 뺀 횟수. 같은 설정 값이라도 굵은 파일이 더해지면 글꼴 ID 가 바뀐다.
    var fontsRevision by remember { mutableStateOf(0) }

    LaunchedEffect(panel) { onChrome(panel != Panel.None) }
    BackHandler {
        panel = when (panel) {
            Panel.None -> { onClose(); Panel.None }
            // 목록에서 뒤로 가면 도구줄로 돌아온다 — 바로 닫히면 목록을 다시 열 길이 멀어진다.
            Panel.Contents, Panel.Bookmarks, Panel.View -> Panel.Bar
            Panel.Fonts -> Panel.View
            Panel.Bar -> Panel.None
        }
    }

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
        val pxPerDp = density.density
        // 글꼴 ID 는 글자를 재서 만든다(지문). 설정이 바뀔 때만 다시 잰다.
        val fontId = remember(prefs.font, fontsRevision) { reader.fonts.layoutFontId(prefs.font) }
        val spec = remember(widthPx, heightPx, margin, prefs, pxPerSp, pxPerDp, fontId) {
            prefs.toSpec(widthPx, heightPx, margin, pxPerSp, pxPerDp, fontId)
        }
        LaunchedEffect(spec) { runCatching { reader.layOut(spec) } }

        // 그리기 전용 측정기. 조판에 쓴 설정(state.spec)으로 만든다 — 새 설정으로 조판이
        // 끝나기 전까지는 옛 페이지를 옛 글꼴로 그려야 한다.
        val painter = remember(state.spec) { state.spec?.let { AndroidTextMeasurer.forSpec(reader.fonts, it) } }
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
                            panel != Panel.None -> panel = Panel.None
                            offset.x < size.width * 0.3f -> scope.go { reader.previous() }
                            offset.x > size.width * 0.7f -> scope.go { reader.next() }
                            else -> panel = Panel.Bar
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

        when (panel) {
            Panel.None -> Unit
            Panel.Bar, Panel.View -> ReaderBar(
                reader = reader,
                state = state,
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                showView = panel == Panel.View,
                onClose = onClose,
                onPanel = { panel = it },
                scope = scope,
            )
            Panel.Fonts -> FontsPanel(
                catalog = reader.fonts,
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                onFontsChanged = { fontsRevision++ },
                onBack = { panel = Panel.View },
            )
            Panel.Contents, Panel.Bookmarks -> ReaderLists(
                reader = reader,
                state = state,
                showBookmarks = panel == Panel.Bookmarks,
                onPanel = { panel = it },
                scope = scope,
            )
        }

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

/**
 * 리더 위에 뜨는 것들.
 *
 * 예전에는 가운데를 누르면 목차 판(320dp)과 탭이 함께 떠서 화면의 3분의 2 를 가렸다.
 * 이제 누르면 **얇은 도구줄**만 뜨고 지면은 거의 그대로 보인다. 목차·책갈피는 볼 때만
 * 전체 화면으로 연다(Play 북·리디와 같은 구성).
 */
private enum class Panel { None, Bar, View, Fonts, Contents, Bookmarks }

@Composable
private fun ReaderBar(
    reader: BookReader,
    state: ReaderState,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    showView: Boolean,
    onClose: () -> Unit,
    onPanel: (Panel) -> Unit,
    scope: CoroutineScope,
) {
    val colors = CpTheme.colors
    // 막대를 끄는 동안의 값. 손을 떼기 전까지는 옮기지 않고 숫자만 바꾼다.
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 위: 제목 · 뒤로 · 책갈피
        Column(Modifier.fillMaxWidth().background(colors.surface).windowInsetsPadding(WindowInsets.statusBars)) {
            val position = state.position
            CpHeader(
                title = reader.title,
                subtitle = if (position != null) "${position.spineIndex + 1} / ${state.chapterCount} 장" else null,
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
        // 가운데: 지면이 보이는 곳. 누르면 닫힌다.
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clickable(indication = null, interactionSource = null) { onPanel(Panel.None) },
        )
        // 아래: (보기 설정) · 진행 막대 · 도구
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            if (showView) {
                ViewSettings(reader.fonts, prefs, onPrefsChange, onFonts = { onPanel(Panel.Fonts) })
                Spacer(Modifier.height(4.dp))
            }
            val shown = dragging ?: (state.percent / 100f)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CpSlider(
                    value = shown,
                    onChange = { dragging = it },
                    onCommit = { target ->
                        scope.go { reader.seek(target) }
                        dragging = null
                    },
                    modifier = Modifier.weight(1f),
                    description = "읽은 위치",
                )
                CpText(
                    "${(shown * 100).roundToInt()}%",
                    CpTheme.type.label,
                    colors.text,
                    Modifier.padding(start = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CpToolButton(CpIcons.Toc, "목차", { onPanel(Panel.Contents) })
                CpToolButton(CpIcons.Bookmark, "책갈피", { onPanel(Panel.Bookmarks) })
                CpToolButton(
                    CpIcons.TextSize,
                    "보기",
                    { onPanel(if (showView) Panel.Bar else Panel.View) },
                    selected = showView,
                )
            }
        }
    }
}

/** 목차·책갈피 전체 화면. 한 줄 48dp, 열면 지금 위치로 스크롤한다. */
@Composable
private fun ReaderLists(
    reader: BookReader,
    state: ReaderState,
    showBookmarks: Boolean,
    onPanel: (Panel) -> Unit,
    scope: CoroutineScope,
) {
    val colors = CpTheme.colors
    var toc by remember { mutableStateOf<List<TocEntry>?>(null) }
    var marks by remember { mutableStateOf<List<Bookmark>?>(null) }
    LaunchedEffect(Unit) { toc = runCatching { reader.outline() }.getOrDefault(emptyList()) }
    LaunchedEffect(showBookmarks) {
        if (showBookmarks) marks = runCatching { reader.bookmarks() }.getOrDefault(emptyList())
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            // 목록 뒤의 지면으로 터치가 새지 않게 한다.
            .clickable(indication = null, interactionSource = null) {},
    ) {
        CpHeader(title = reader.title, onBack = { onPanel(Panel.Bar) })
        CpTabBar(
            listOf("목차", "책갈피"),
            if (showBookmarks) 1 else 0,
            { onPanel(if (it == 1) Panel.Bookmarks else Panel.Contents) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showBookmarks) {
                BookmarkList(
                    marks,
                    onOpen = { mark -> scope.go { reader.goTo(mark) }; onPanel(Panel.None) },
                    onRemove = { mark -> scope.go { reader.removeBookmark(mark); marks = reader.bookmarks() } },
                )
            } else {
                TocList(toc, state) { entry -> scope.go { reader.goTo(entry) }; onPanel(Panel.None) }
            }
        }
    }
}

@Composable
private fun TocList(entries: List<TocEntry>?, state: ReaderState, onOpen: (TocEntry) -> Unit) {
    when {
        entries == null -> Unit
        entries.isEmpty() -> Empty("목차가 없는 책입니다")
        else -> {
            val current = currentTocIndex(entries, state.position?.spineIndex ?: 0)
            val list = rememberLazyListState()
            // 지금 위치가 화면 위쪽 3분의 1 쯤 오게 연다. 맨 위에 붙이면 앞 항목이 안 보여
            // "어디쯤인지" 가 안 읽힌다.
            LaunchedEffect(current) { if (current > 0) list.scrollToItem((current - 3).coerceAtLeast(0)) }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                itemsIndexed(entries) { i, entry ->
                    CpListRow(
                        title = entry.label,
                        onClick = { onOpen(entry) },
                        modifier = Modifier.padding(start = (entry.depth * 16).dp),
                        selected = i == current,
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkList(marks: List<Bookmark>?, onOpen: (Bookmark) -> Unit, onRemove: (Bookmark) -> Unit) {
    when {
        marks == null -> Unit
        marks.isEmpty() -> Empty("책갈피가 없습니다. 페이지 가운데를 누르고 위쪽 책갈피 단추로 꽂을 수 있습니다")
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
private fun ViewSettings(catalog: FontCatalog, prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit, onFonts: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        CpStepper("글자 크기", "${prefs.fontSizeSp}", { onChange(prefs.withSize(-1)) }, { onChange(prefs.withSize(+1)) })
        // 고른 값이 목록에 없으면(지운 사용자 글꼴, 명조가 없는 기기로 옮긴 설정) 실제로 쓰이는
        // 글꼴 이름을 보인다. 사용자 글꼴이 몇 개일지 모르므로 단추를 늘어놓지 않고 목록을 연다.
        val current = catalog.effectiveKey(prefs.font)
        val label = catalog.options().firstOrNull { it.key == current }?.label.orEmpty()
        CpLinkRow("글꼴", label, onFonts)
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

/**
 * 동작 하나를 띄운다. 실패는 [BookReader.state] 의 error 로 화면에 가고, 여기서는 로그만
 * 남긴다 — 아무 흔적 없이 삼키면 "단추가 먹통" 인 원인을 기기에서 찾을 수 없다.
 */
private fun CoroutineScope.go(block: suspend () -> Unit) {
    launch {
        runCatching { block() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w("OloReader", "reader action failed", it) }
    }
}
