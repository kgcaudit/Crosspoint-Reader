package io.github.kgcaudit.reader.reflow

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.PlacedImage
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpReaderBar
import io.github.kgcaudit.reader.layout.book.LinkTarget
import io.github.kgcaudit.reader.layout.html.Link
import io.github.kgcaudit.reader.ui.design.CpBrightnessOverlay
import io.github.kgcaudit.reader.ui.design.CpBrightnessRow
import io.github.kgcaudit.reader.ui.design.CpPageTurn
import io.github.kgcaudit.reader.ui.design.ReadingSpeed
import io.github.kgcaudit.reader.ui.design.brightnessEdge
import io.github.kgcaudit.reader.ui.design.systemBrightness
import io.github.kgcaudit.reader.ui.design.CpReadingFooter
import io.github.kgcaudit.reader.ui.design.CpRibbon
import io.github.kgcaudit.reader.ui.design.CpThemeSwatches
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.CpViewSettingsScreen
import io.github.kgcaudit.reader.ui.design.FooterInfo
import io.github.kgcaudit.reader.ui.design.ReadingWindow
import io.github.kgcaudit.reader.ui.design.TapAction
import io.github.kgcaudit.reader.ui.design.VolumeKeyPaging
import io.github.kgcaudit.reader.ui.design.actionAt
import io.github.kgcaudit.reader.ui.design.CpStepper
import io.github.kgcaudit.reader.ui.design.CpTabBar
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToolButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 리플로우 리더.
 *
 * 화면 전체가 지면이다. 왼쪽 3분의 1 을 누르면 앞 장, 오른쪽 3분의 1 은 다음 장,
 * 가운데는 메뉴(좌우는 터치 영역 설정으로 바꾼다). 옆으로 밀어도 넘어간다. CrossPoint 의 물리
 * 버튼 자리를 그대로 터치 영역으로 옮겼다. 오른쪽 위 모서리는 책갈피 꽂기 · 빼기다.
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
    /** 읽는 속도(글자/분). 남은 시간을 센다(E5). 앱이 저장해 두어 책을 닫아도 남는다. */
    speed: ReadingSpeed = remember { ReadingSpeed() },
    onSpeedChange: (ReadingSpeed) -> Unit = {},
) {
    val state by reader.state.collectAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val colors = CpTheme.colors
    var panel by remember { mutableStateOf(Panel.None) }
    // 글꼴을 넣거나 뺀 횟수. 같은 설정 값이라도 굵은 파일이 더해지면 글꼴 ID 가 바뀐다.
    var fontsRevision by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastCount by remember { mutableStateOf(0) }
    // 하단 정보의 "장 제목". 목차는 책마다 한 번 읽는다.
    var toc by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    LaunchedEffect(reader) { toc = runCatching { reader.outline() }.getOrDefault(emptyList()) }
    val search = remember(reader) { SearchSession() }
    // 각주 판(F3) · 브라우저 확인(F6).
    var note by remember { mutableStateOf<Pair<String, LinkTarget.Footnote>?>(null) }
    var external by remember { mutableStateOf<String?>(null) }
    // 왼쪽 끝을 미는 동안의 밝기(E6). 손을 떼면 설정으로 저장한다.
    var dragBrightness by remember { mutableStateOf<Float?>(null) }
    val latestPrefs by androidx.compose.runtime.rememberUpdatedState(prefs)
    val context = androidx.compose.ui.platform.LocalContext.current
    var speedRevision by remember { mutableStateOf(0) }

    ReadingWindow(prefs.screen.copy(brightness = dragBrightness ?: prefs.screen.brightness), activity = state.position)
    VolumeKeyPaging(enabled = prefs.screen.volumeKeys && panel == Panel.None) { forward ->
        scope.go { if (forward) reader.next() else reader.previous() }
    }
    fun toggleBookmark() = scope.go {
        reader.toggleBookmark()
        toast = if (reader.state.value.bookmarked) "책갈피를 꽂았습니다" else "책갈피를 뺐습니다"
        toastCount++
    }

    fun openLink(link: Link) = scope.go {
        val label = state.text.let { t -> t.substring(link.start.coerceIn(0, t.length), link.endExclusive.coerceIn(0, t.length)) }.trim()
        when (val target = reader.resolve(link)) {
            is LinkTarget.Footnote -> note = "각주 $label".trim() to target
            is LinkTarget.Jump -> reader.jumpTo(target.spine, target.anchor)
            is LinkTarget.External -> external = target.url
            LinkTarget.Missing -> {
                toast = if (link.isFootnote(state.text)) "이 각주의 내용을 책에서 찾지 못했습니다" else "링크가 가리키는 곳을 책에서 찾지 못했습니다"
                toastCount++
            }
        }
    }

    fun openHit(index: Int) {
        val found = search.results.getOrNull(index) ?: return
        search.current = index
        panel = Panel.None
        scope.go { reader.goTo(found.hit) }
    }

    // 읽는 속도: 앞으로 넘길 때마다 방금 읽은 쪽(두쪽이면 두 쪽)의 글자 수와 머문 시간으로 잰다.
    val turn = remember { TurnClock() }
    LaunchedEffect(state.position?.spineIndex, state.position?.pageIndex) {
        val p = state.position ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        val here = p.spineIndex to p.pageIndex
        val before = turn.key
        if (before != null && isAfter(here, before) && speed.record(turn.chars.toDouble(), now - turn.at)) {
            onSpeedChange(speed)
            speedRevision++
        }
        turn.key = here
        turn.at = now
        turn.chars = listOfNotNull(state.page, state.rightPage).sumOf { it.endCharExclusive - it.startChar }
    }

    LaunchedEffect(panel) { onChrome(panel != Panel.None) }
    BackHandler {
        panel = when (panel) {
            Panel.None -> { onClose(); Panel.None }
            // 목록에서 뒤로 가면 도구줄로 돌아온다 — 바로 닫히면 목록을 다시 열 길이 멀어진다.
            Panel.Contents, Panel.Bookmarks, Panel.View, Panel.Search -> Panel.Bar
            Panel.Fonts, Panel.Settings -> Panel.View
            Panel.Bar -> Panel.None
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(colors.paper).brightnessEdge(
            enabled = prefs.screen.brightnessGesture && panel == Panel.None,
            current = { latestPrefs.screen.brightness ?: systemBrightness(context) },
            onDrag = { value ->
                if (value != null) {
                    dragBrightness = value
                } else {
                    dragBrightness?.let { v -> onPrefsChange(latestPrefs.copy(screen = latestPrefs.screen.copy(brightness = v))) }
                    dragBrightness = null
                }
            },
        ),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val cutout = WindowInsets.displayCutout
        val margin = with(density) {
            Insets(
                left = prefs.margin.dp.dp.toPx() + cutout.getLeft(this, direction),
                top = 34.dp.toPx() + cutout.getTop(this),
                right = prefs.margin.dp.dp.toPx() + cutout.getRight(this, direction),
                // 상태바 + 숨 쉴 틈. 본문이 상태바 밑으로 들어가면 마지막 줄이 가려진다.
                bottom = (CpTheme.metrics.statusBarHeight + 28.dp).toPx() + cutout.getBottom(this),
            )
        }
        // 1sp 가 몇 px 인가. 시스템 글자 크기 설정(fontScale)을 따른다.
        val pxPerSp = density.density * density.fontScale
        val pxPerDp = density.density
        val useBookFonts = reader.usesBookFonts(prefs)
        // 글꼴 ID 는 글자를 재서 만든다(지문). 설정이 바뀔 때만 다시 잰다.
        val bodyFont = prefs.bodyFont(useBookFonts)
        val fontId = remember(bodyFont, fontsRevision) { reader.fonts.layoutFontId(bodyFont) }
        // 두쪽보기: 한 쪽 폭(화면의 절반)으로 조판하고 같은 장의 두 쪽을 나란히 놓는다. 여백은 쪽마다 따로 —
        // 가운데(책등)에도 여백이 있어야 두 쪽의 글자가 붙지 않는다.
        val smallestWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp
        val twoPages = prefs.screen.twoPages(widthPx / pxPerDp, heightPx / pxPerDp, smallestWidthDp)
        val pageWidthPx = if (twoPages) widthPx / 2f else widthPx
        val spec = remember(pageWidthPx, heightPx, margin, prefs, pxPerSp, pxPerDp, fontId, useBookFonts) {
            prefs.toSpec(pageWidthPx, heightPx, margin, pxPerSp, pxPerDp, fontId, useBookFonts)
        }
        LaunchedEffect(spec, twoPages) {
            // 실패는 reader.state.error 로 화면에 간다. 여기서는 로그만 — 흔적 없이 삼키면 기기에서 원인을 못 찾는다.
            runCatching { reader.layOut(spec, twoPages) }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "layout failed", it)
            }
        }

        // 그리기 전용 측정기. 조판에 쓴 설정(state.spec)으로 만든다 — 새 설정으로 조판이
        // 끝나기 전까지는 옛 페이지를 옛 글꼴로 그려야 한다.
        val painter = remember(state.spec) { state.spec?.let(reader::measurer) }
        val images = remember(state.page) { mutableStateMapOf<PlacedImage, ImageBitmap>() }
        // 오른쪽 쪽의 그림은 따로 둔다 — 두 쪽에 같은 자리 · 같은 파일의 그림이 있으면 한 표에서 서로 덮는다.
        val rightImages = remember(state.rightPage) { mutableStateMapOf<PlacedImage, ImageBitmap>() }
        LaunchedEffect(state.page, state.rightPage) {
            val spine = state.position?.spineIndex ?: return@LaunchedEffect
            for ((page, into) in listOf(state.page to images, state.rightPage to rightImages)) {
                for (placed in page?.images.orEmpty()) {
                    reader.image(spine, placed.href, placed.widthPx.roundToInt(), placed.heightPx.roundToInt())
                        ?.let { into[placed] = it }
                }
            }
        }

        // 각주 표시는 강조색(F1), 찾은 말은 글자 뒤 색(E3).
        val accent = colors.accent
        val noteMarks = remember(state.links, state.text) {
            state.links.filter { it.isFootnote(state.text) }.map { it.start until it.endExclusive }
        }
        val marks = PageMarks(
            highlight = state.highlight?.let { it.start until it.endExclusive },
            highlightColor = accent.copy(alpha = 0.3f),
            accent = noteMarks,
            accentColor = accent,
        )
        val frame = PageFrame(state.page, state.rightPage, state.text, state.spread, marks, images, rightImages, painter)
        val frameKey = state.position?.let { Triple(it.spineIndex, it.pageIndex, state.spread) }
        // 최근 쪽 셋. 넘기는 동안 옛 쪽(나가는 쪽)을 그릴 내용이 남아 있어야 한다.
        val frames = remember {
            object : LinkedHashMap<Triple<Int, Int, Boolean>?, PageFrame>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Int, Int, Boolean>?, PageFrame>?) = size > 3
            }
        }
        frames[frameKey] = frame
        // 넘김 효과(E7): 쪽이 바뀔 때만. 그리는 것만 움직이고 누르기 · 밀기는 아래의 고정된 층이 받는다.
        CpPageTurn(frameKey, prefs.screen.pageTurn, forward = { from, to -> from == null || to == null || isAfter(to.first to to.second, from.first to from.second) }) { key ->
            val shown = frames[key] ?: frame
            Canvas(Modifier.fillMaxSize().background(colors.paper)) {
                val page = shown.page
                val paint = shown.painter
                if (page != null && paint != null) {
                    drawPage(page, shown.text, paint, colors.ink, shown.images, shown.marks)
                    if (shown.spread) {
                        val half = size.width / 2f
                        // 책등: 옅은 선 한 줄. 그림자까지 그리면 e-ink 원형과 멀고 글자 옆이 탁해 보인다.
                        drawLine(
                            colors.ink.copy(alpha = 0.13f),
                            androidx.compose.ui.geometry.Offset(half, CORNER.toPx() * 0.5f),
                            androidx.compose.ui.geometry.Offset(half, size.height - margin.bottom),
                            strokeWidth = 1.dp.toPx(),
                        )
                        shown.right?.let { right ->
                            translate(left = half) {
                                drawPage(right, shown.text, paint, colors.ink, shown.rightImages, shown.marks)
                            }
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(reader, prefs.screen.touch) {
                    val corner = CORNER.toPx()
                    val touch = 48.dp.toPx()
                    detectTapGestures { offset ->
                        if (panel != Panel.None) {
                            panel = Panel.None
                            return@detectTapGestures
                        }
                        val action = prefs.screen.touch.actionAt(offset.x, offset.y, size.width.toFloat(), corner)
                        if (action == TapAction.Bookmark) {
                            toggleBookmark()
                            return@detectTapGestures
                        }
                        // 링크(각주 표시)가 먼저 — 그 자리가 "다음 쪽" 자리여도 넘기지 않는다(F2).
                        val current = reader.state.value
                        val paint = painter
                        val onRight = current.spread && offset.x > size.width / 2f
                        val page = if (onRight) current.rightPage else current.page
                        val x = if (onRight) offset.x - size.width / 2f else offset.x
                        val link = if (page != null && paint != null) linkAt(page, current.text, paint, current.links, x, offset.y, touch) else null
                        if (link != null) {
                            openLink(link)
                            return@detectTapGestures
                        }
                        when (action) {
                            TapAction.Previous -> scope.go { reader.previous() }
                            TapAction.Next -> scope.go { reader.next() }
                            TapAction.Menu -> panel = Panel.Bar
                            TapAction.Bookmark -> Unit
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
        )

        // 윗여백(34dp) 안에서 끝나 글자를 가리지 않는다. 노치가 있으면 그 아래로.
        if (state.bookmarked && state.page != null) {
            CpRibbon(Modifier.align(Alignment.TopEnd).windowInsetsPadding(cutout).padding(end = 20.dp))
        }

        val position = state.position
        CpReadingFooter(
            info = FooterInfo(
                bookTitle = reader.title,
                chapterTitle = position?.let { p -> toc.getOrNull(currentTocIndex(toc, p.spineIndex))?.label },
                // 두쪽이면 두 쪽을 묶어 "5–6 / 12"(T5). 오른쪽이 빈 펼침은 한 쪽 번호만.
                page = when {
                    position == null -> ""
                    state.rightPage != null -> "${position.pageIndex + 1}–${position.pageIndex + 2} / ${position.pageCount}"
                    else -> "${position.pageIndex + 1} / ${position.pageCount}"
                },
                percent = state.percent,
                chapterPagesLeft = position?.let { it.pageCount - it.pageIndex - 1 - (if (state.rightPage != null) 1 else 0) },
                // 남은 시간(E5): 이 장에서 보이는 쪽 뒤로 남은 글자 ÷ 읽는 속도. 속도를 모르면 빈칸.
                chapterMinutesLeft = speedRevision.let { _ ->
                    (state.rightPage ?: state.page)?.let { shown -> speed.minutesFor((state.chapterLength - shown.endCharExclusive).toDouble()) }
                },
                bookMinutesLeft = speedRevision.let { _ ->
                    (state.rightPage ?: state.page)?.let { shown ->
                        speed.minutesFor((state.chapterLength - shown.endCharExclusive).toDouble() + state.charsAfterChapter)
                    }
                },
            ),
            footer = prefs.screen.footer,
            color = colors.inkMuted,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).windowInsetsPadding(cutout),
        )
        CpToast(toast, onDone = { toast = null }, Modifier.align(Alignment.BottomCenter), key = toastCount)

        if (panel == Panel.None) {
            if (search.current >= 0 && search.results.isNotEmpty()) {
                SearchResultBar(
                    index = search.current,
                    total = search.results.size,
                    onPrevious = { if (search.current > 0) openHit(search.current - 1) },
                    onNext = { if (search.current < search.results.size - 1) openHit(search.current + 1) },
                    onList = { panel = Panel.Search },
                    onClose = { search.current = -1; scope.go { reader.clearHighlight() } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
                )
            } else if (state.canReturn) {
                ReturnChip({ scope.go { reader.returnBack() } }, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
            }
        }
        CpBrightnessOverlay(dragBrightness, Modifier.align(Alignment.CenterStart))

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
                onBookmark = ::toggleBookmark,
                onSearch = { panel = Panel.Search },
                scope = scope,
            )
            Panel.Search -> SearchScreen(
                session = search,
                toc = toc,
                onSearch = { search.start(reader, scope) },
                onOpen = ::openHit,
                onBack = { panel = Panel.Bar },
            )
            Panel.Settings -> CpViewSettingsScreen(
                prefs = prefs.screen,
                onChange = { onPrefsChange(prefs.copy(screen = it)) },
                onBack = { panel = Panel.View },
                paragraph = { child -> ParagraphSettings(prefs, onPrefsChange, child) },
            )
            Panel.Fonts -> FontsPanel(
                catalog = reader.fonts,
                publisher = if (reader.hasBookFonts) PublisherFonts(reader.bookFontPreview) else null,
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

        note?.let { (title, target) ->
            NoteSheet(
                title = title,
                text = target.text,
                onGoTo = {
                    note = null
                    scope.go { reader.jumpTo(target.spine, target.anchor, markLength = target.text.length) }
                },
                onClose = { note = null },
            )
        }
        external?.let { url ->
            val opener = androidx.compose.ui.platform.LocalUriHandler.current
            ExternalLinkPopup(url, onOpen = { external = null; runCatching { opener.openUri(url) } }, onDismiss = { external = null })
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
private enum class Panel { None, Bar, View, Fonts, Settings, Search, Contents, Bookmarks }

/** 한 번 그린 쪽. 넘김 효과가 옛 쪽을 새 쪽과 함께 그리는 동안 옛 쪽의 내용을 쥐고 있다. */
private data class PageFrame(
    val page: io.github.kgcaudit.reader.layout.Page?,
    val right: io.github.kgcaudit.reader.layout.Page?,
    val text: String,
    val spread: Boolean,
    val marks: PageMarks,
    val images: Map<PlacedImage, ImageBitmap>,
    val rightImages: Map<PlacedImage, ImageBitmap>,
    val painter: io.github.kgcaudit.reader.text.AndroidTextMeasurer?,
)

/** 마지막으로 쪽이 바뀐 때 · 그 쪽 · 그 쪽의 글자 수. */
private class TurnClock {
    var at = 0L
    var key: Pair<Int, Int>? = null
    var chars = 0
}

/** (장, 쪽) 이 [other] 보다 뒤인가. */
private fun isAfter(here: Pair<Int, Int>, other: Pair<Int, Int>): Boolean =
    here.first > other.first || (here.first == other.first && here.second > other.second)

/** 오른쪽 위 모서리의 책갈피 네모. 리본(22dp)보다 넉넉하되 "다음 쪽" 자리를 많이 빼앗지 않는 크기. */
private val CORNER = 56.dp

@Composable
private fun ReaderBar(
    reader: BookReader,
    state: ReaderState,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    showView: Boolean,
    onClose: () -> Unit,
    onPanel: (Panel) -> Unit,
    onBookmark: () -> Unit,
    onSearch: () -> Unit,
    scope: CoroutineScope,
) {
    val position = state.position
    CpReaderBar(
        title = reader.title,
        subtitle = if (position != null) "${position.spineIndex + 1} / ${state.chapterCount} 장" else null,
        bookmarked = state.bookmarked,
        onBookmark = onBookmark,
        onSearch = onSearch,
        onBack = onClose,
        onDismiss = { onPanel(Panel.None) },
        progress = state.percent / 100f,
        progressLabel = { "${(it * 100).roundToInt()}%" },
        onSeek = { target -> scope.go { reader.seek(target) } },
        above = {
            if (showView) {
                ViewSettings(reader, prefs, onPrefsChange, onFonts = { onPanel(Panel.Fonts) }, onAll = { onPanel(Panel.Settings) })
                Spacer(Modifier.height(4.dp))
            }
        },
    ) {
        CpToolButton(CpIcons.Toc, "목차", { onPanel(Panel.Contents) })
        CpToolButton(CpIcons.Bookmark, "책갈피", { onPanel(Panel.Bookmarks) })
        CpToolButton(CpIcons.TextSize, "보기", { onPanel(if (showView) Panel.Bar else Panel.View) }, selected = showView)
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
    var toc by remember { mutableStateOf<List<TocEntry>?>(null) }
    var marks by remember { mutableStateOf<List<Bookmark>?>(null) }
    LaunchedEffect(Unit) { toc = runCatching { reader.outline() }.getOrDefault(emptyList()) }
    LaunchedEffect(showBookmarks) {
        if (showBookmarks) marks = runCatching { reader.bookmarks() }.getOrDefault(emptyList())
    }

    CpFullScreen {
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
                        modifier = Modifier.padding(start = CpTheme.metrics.levelIndent * entry.depth),
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

/**
 * 보기 판(A안): 자주 바꾸는 것만 — 배경 · 밝기 · 글자 크기 · 글꼴 · 줄 간격 · 여백. 나머지는 "모든 보기 설정".
 * 판에 전부 두면 열두 줄이 되어 바꾸는 결과(지면)를 가린다.
 */
@Composable
private fun ViewSettings(
    reader: BookReader,
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
    onFonts: () -> Unit,
    onAll: () -> Unit,
) {
    val catalog = reader.fonts
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        CpThemeSwatches(prefs.screen.theme, { onChange(prefs.copy(screen = prefs.screen.copy(theme = it))) })
        CpBrightnessRow(prefs.screen.brightness, { onChange(prefs.copy(screen = prefs.screen.copy(brightness = it))) })
        CpStepper("글자 크기", "${prefs.fontSizeSp}", { onChange(prefs.withSize(-1)) }, { onChange(prefs.withSize(+1)) })
        // 고른 값이 목록에 없으면(지운 사용자 글꼴, 없어진 옛 설정) 실제로 쓰이는 글꼴 이름을 보인다.
        // 사용자 글꼴이 몇 개일지 모르므로 단추를 늘어놓지 않고 목록을 연다.
        val current = catalog.effectiveKey(prefs.font)
        val label = if (reader.usesBookFonts(prefs)) {
            PUBLISHER_LABEL
        } else {
            catalog.options().firstOrNull { it.key == current }?.label.orEmpty()
        }
        CpLinkRow("글꼴", label, onFonts)
        val spacings = ReaderPrefs.LineSpacing.entries
        CpChoice("줄 간격", spacings.map { it.label }, spacings.indexOf(prefs.lineSpacing), {
            onChange(prefs.copy(lineSpacing = spacings[it]))
        })
        val margins = ReaderPrefs.Margin.entries
        CpChoice("여백", margins.map { it.label }, margins.indexOf(prefs.margin), {
            onChange(prefs.copy(margin = margins[it]))
        })
        CpLinkRow("모든 보기 설정", "", onAll)
    }
}

/** 모든 보기 설정의 문단 묶음. 셋 다 조판을 바꾼다(LayoutSpec). */
@Composable
private fun ParagraphSettings(prefs: ReaderPrefs, onChange: (ReaderPrefs) -> Unit, child: Modifier) {
    val aligns = ReaderPrefs.ParagraphAlign.entries
    CpChoice("정렬", aligns.map { it.label }, aligns.indexOf(prefs.align), { onChange(prefs.copy(align = aligns[it])) }, child)
    val indents = ReaderPrefs.Indent.entries
    CpChoice("첫 줄 들여쓰기", indents.map { it.label }, indents.indexOf(prefs.indent), { onChange(prefs.copy(indent = indents[it])) }, child)
    val gaps = ReaderPrefs.ParagraphSpacing.entries
    CpChoice("문단 간격", gaps.map { it.label }, gaps.indexOf(prefs.paragraphSpacing), {
        onChange(prefs.copy(paragraphSpacing = gaps[it]))
    }, child)
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
        runCatching { block() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w(TAG, "reader action failed", it) }
    }
}

private const val TAG = "OloReader"
