package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.ui.design.HANDLE_RADIUS
import io.github.kgcaudit.reader.ui.design.handleCentres
import io.github.kgcaudit.reader.ui.design.lookUp
import io.github.kgcaudit.reader.ui.design.shareOut
import io.github.kgcaudit.reader.ui.design.copyText
import io.github.kgcaudit.reader.ui.design.shareText
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import io.github.kgcaudit.reader.listen.ListenHub
import io.github.kgcaudit.reader.listen.ListenKit
import io.github.kgcaudit.reader.listen.ListenPlayer
import io.github.kgcaudit.reader.listen.ListenSheet
import io.github.kgcaudit.reader.listen.ListenState
import io.github.kgcaudit.reader.listen.Listening
import io.github.kgcaudit.reader.listen.VoiceScreen
import io.github.kgcaudit.reader.ui.design.CpAutoTurnPill
import io.github.kgcaudit.reader.ui.design.rememberAutoTurn
import io.github.kgcaudit.reader.ui.design.visible
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.draw.clip
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.ui.design.CpReadingNotesList
import io.github.kgcaudit.reader.ui.design.NoteFilter
import io.github.kgcaudit.reader.ui.design.NoteItem
import io.github.kgcaudit.reader.ui.design.Pen
import io.github.kgcaudit.reader.ui.design.darkPaper
import io.github.kgcaudit.reader.ui.design.exportNotes
import io.github.kgcaudit.reader.ui.design.noteWhere
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
    /** 듣기 엔진(4단계). 앱이 주고, 시험은 가짜 엔진을 준다. null 이면 휴대폰의 음성 엔진. */
    listenKit: ListenKit? = null,
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
    // 독서노트(N1–N4): 고른 구간 · 누른 형광펜 · 쓰는 중인 메모. 마지막에 고른 색을 다음 메모의 색으로.
    var selection by remember { mutableStateOf<Selection?>(null) }
    var tapped by remember { mutableStateOf<io.github.kgcaudit.reader.document.Annotation?>(null) }
    var memo by remember { mutableStateOf<MemoDraft?>(null) }
    var lastPen by remember { mutableStateOf(Pen.Yellow) }
    // 쪽이 바뀌면 고르기를 푼다(한 쪽 안에서만 고른다 — N9).
    // "이어서 ›" 로 넘긴 고르기(① 쪽을 넘어 이어서 고르기). 장 번호와 함께 들고 있다가 다음 쪽이 보이면 잇는다.
    var carry by remember { mutableStateOf<Pair<Int, Selection>?>(null) }
    LaunchedEffect(state.position?.spineIndex, state.position?.pageIndex, state.spread) {
        tapped = null
        val c = carry
        carry = null
        val p = state.position
        val page = state.page
        selection = if (c != null && p != null && page != null && p.spineIndex == c.first) {
            // 앞 쪽에서 고른 시작은 그대로, 끝은 새 쪽의 첫 문장 끝 — 대개 거기까지가 이어 고르려던 곳이고, 아니면 끝
            // 손잡이로 줄이거나 늘린다.
            val shownEnd = (state.rightPage ?: page).endCharExclusive
            Selection(c.second.start, continueEnd(state.text, state.paragraphStarts, page.startChar, shownEnd).coerceAtLeast(c.second.endExclusive))
        } else {
            null
        }
    }
    fun say(message: String) {
        toast = message
        toastCount++
    }
    // 형광펜을 숨긴 채 칠하면 아무 일도 없어 보인다 — 저장은 됐다고 알린다.
    fun hiddenHint() {
        if (!latestPrefs.screen.showHighlights) say("형광펜을 숨겨 둔 상태라 보이지 않습니다. 보기 설정에서 켤 수 있습니다")
    }

    // ── 듣기(4단계) ──
    val kit = remember(listenKit) { listenKit ?: ListenKit.android(context) }
    val hub by ListenHub.current.collectAsState()
    // 이 책의 듣기만 조종판에 보인다(다른 책을 듣던 중에 이 책을 열었으면 그 듣기는 붙이지 않는다).
    val listening = hub?.takeIf { it.belongsTo(reader) }
    val listen = listening?.state?.collectAsState()?.value ?: ListenState()
    var listenSheet by remember { mutableStateOf(false) }
    fun startListening(from: Int? = null) {
        val position = state.position ?: return
        val page = state.page ?: return
        val l = Listening(reader, kit.speaker(prefs.listen.engine), ListenHub.scope)
        ListenHub.attach(context, l)
        panel = Panel.None
        ListenHub.scope.launch { l.start(position.spineIndex, from ?: page.startChar, prefs.listen.rate, prefs.listen.voice) }
    }
    // 책을 닫으면 듣기도 끝낸다. 닫은 책을 화면 없이 계속 읽으면 멈출 곳이 잠금 화면뿐이다.
    val closeBook = {
        ListenHub.detach(listening)
        onClose()
    }
    LaunchedEffect(listen.message) {
        listen.message?.let { say(it); listening?.consumeMessage() }
    }
    // 사람이 쪽을 옮기면(넘기기 · 목차 · 진행 막대) 듣기도 그 쪽의 첫 문장으로 온다.
    LaunchedEffect(state.position?.spineIndex, state.position?.pageIndex, state.spread) {
        val p = state.position ?: return@LaunchedEffect
        val page = state.page ?: return@LaunchedEffect
        listening?.onPageShown(p.spineIndex, page.startChar, (state.rightPage ?: page).endCharExclusive)
    }
    // 화면을 끈 채 듣는 동안 쪽이 넘어갔으면, 돌아왔을 때 알린다 — 보던 쪽이 바뀐 까닭을 모르면 놀란다.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, listening) {
        var left: Pair<Int, Int>? = null
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            val p = reader.state.value.position
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> left = p?.let { it.spineIndex to it.pageIndex }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    val now = p?.let { it.spineIndex to it.pageIndex }
                    if (left != null && now != left && listening?.state?.value?.active == true) say("듣던 곳으로 쪽을 옮겼습니다")
                    left = null
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 자동 넘김을 켜 두면 화면을 켜 둔다 — 넘기는 사이 화면 꺼짐 시간이 지나 꺼지면 자동 넘김이 뜻이 없다.
    ReadingWindow(
        prefs.screen.copy(
            brightness = dragBrightness ?: prefs.screen.brightness,
            keepScreenOn = if (prefs.screen.autoTurn != io.github.kgcaudit.reader.ui.design.AutoTurn.Off) io.github.kgcaudit.reader.ui.design.KeepScreenOn.Always else prefs.screen.keepScreenOn,
        ),
        activity = state.position,
    )
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
        if (selection != null || tapped != null) {
            selection = null
            tapped = null
            return@BackHandler
        }
        panel = when (panel) {
            Panel.None -> { closeBook(); Panel.None }
            // 목록에서 뒤로 가면 도구줄로 돌아온다 — 바로 닫히면 목록을 다시 열 길이 멀어진다.
            Panel.Contents, Panel.Notes, Panel.View, Panel.Search -> Panel.Bar
            Panel.Fonts, Panel.Settings -> Panel.View
            Panel.Voices -> Panel.None
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
        val dark = colors.darkPaper
        // 형광펜 숨김(3-5): 그리지도, 눌러 열지도 않는다. 지우지는 않는다.
        val shownNotes = if (prefs.screen.showHighlights) state.annotations else emptyList()
        val marks = PageMarks(
            highlight = state.highlight?.let { it.start until it.endExclusive },
            highlightColor = accent.copy(alpha = 0.3f),
            accent = noteMarks,
            accentColor = accent,
            // 지금 읽는 문장(L3)을 먼저 깐다 — 형광펜이 그 위에 보인다. 강조색이라 4색 형광펜과 헷갈리지 않는다.
            tints = listOfNotNull(
                listen.sentence?.takeIf { listen.active && listen.spine == state.position?.spineIndex }
                    ?.let { Tint(it.start until it.endExclusive, accent.copy(alpha = if (dark) 0.30f else 0.18f)) },
            ) + shownNotes.map { Tint(it.start.charOffset until it.end.charOffset, it.color.pen.fill(dark)) },
            memos = shownNotes.filter { it.note != null }.map { MemoMark(it.end.charOffset, it.color.pen.mark(dark)) },
            selection = selection?.range,
            selectionColor = accent.copy(alpha = 0.28f),
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

        // 제스처는 조판이 바뀌어도 다시 달지 않는다 — 그리기 측정기는 늘 최신 것을 읽는다(옛 글자 크기로 재면
        // 글자를 크게 한 뒤 누른 자리와 고른 글자가 어긋난다).
        val latestPainter by androidx.compose.runtime.rememberUpdatedState(painter)
        fun boxesOf(start: Int, end: Int): List<androidx.compose.ui.geometry.Rect> {
            val current = reader.state.value
            val paint = latestPainter ?: return emptyList()
            return screenBoxes(current.page, current.rightPage, current.text, paint, start, end, if (current.spread) widthPx / 2f else Float.MAX_VALUE)
        }
        fun charAtScreen(x: Float, y: Float, after: Boolean): Int? {
            val current = reader.state.value
            val paint = latestPainter ?: return null
            val right = if (current.spread) current.rightPage else null
            return screenCharAt(current.page, right, current.text, paint, x, y, widthPx / 2f, after)
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(reader, prefs.screen.touch) {
                    val corner = CORNER.toPx()
                    val touch = 48.dp.toPx()
                    detectTapGestures(
                        onLongPress = { offset ->
                            // 길게 누르면 그 낱말을 고른다(N4). 메뉴가 떠 있으면 먼저 닫는다.
                            if (panel != Panel.None) return@detectTapGestures
                            tapped = null
                            val at = charAtScreen(offset.x, offset.y, after = false) ?: return@detectTapGestures
                            val word = wordAt(reader.state.value.text, at)
                            if (!word.isEmpty()) selection = Selection(word.first, word.last + 1)
                        },
                    ) { offset ->
                        if (panel != Panel.None) {
                            panel = Panel.None
                            return@detectTapGestures
                        }
                        // 고르는 중 · 칠한 곳 메뉴가 떠 있으면 그것만 닫는다 — 쪽이 넘어가면 고른 것을 잃는다.
                        if (selection != null || tapped != null) {
                            selection = null
                            tapped = null
                            return@detectTapGestures
                        }
                        val action = prefs.screen.touch.actionAt(offset.x, offset.y, size.width.toFloat(), corner)
                        if (action == TapAction.Bookmark) {
                            toggleBookmark()
                            return@detectTapGestures
                        }
                        // 링크(각주 표시)가 먼저 — 그 자리가 "다음 쪽" 자리여도 넘기지 않는다(F2).
                        val current = reader.state.value
                        val paint = latestPainter
                        val onRight = current.spread && offset.x > size.width / 2f
                        val page = if (onRight) current.rightPage else current.page
                        val x = if (onRight) offset.x - size.width / 2f else offset.x
                        val link = if (page != null && paint != null) linkAt(page, current.text, paint, current.links, x, offset.y, touch) else null
                        // 칠한 곳을 누르면 그 칠의 메뉴(N4). 다만 링크 글자 바로 위를 눌렀으면 링크가 먼저 — 각주 표시가
                        // 칠 안에 있어도 열린다. 링크의 넉넉한 누름 자리(48dp)보다는 칠한 글자가 앞선다.
                        val onLinkText = link != null && boxesOf(link.start, link.endExclusive).any { it.contains(offset) }
                        if (!onLinkText) {
                            val visible = if (latestPrefs.screen.showHighlights) current.annotations else emptyList()
                            annotationAt({ boxesOf(it.start.charOffset, it.end.charOffset) }, visible, offset.x, offset.y)?.let {
                                tapped = it
                                return@detectTapGestures
                            }
                        }
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
                }
                // 손잡이 끌기. 맨 안쪽에 달아 누름을 먼저 받는다 — 손잡이를 잡았으면 소비해서 넘기기 · 누르기가
                // 끼어들지 않게 한다. 손잡이 밖이면 건드리지 않고 흘려보낸다.
                .pointerInput(reader) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val now = selection ?: return@awaitEachGesture
                        val boxes = boxesOf(now.start, now.endExclusive)
                        if (boxes.isEmpty()) return@awaitEachGesture
                        val r = HANDLE_RADIUS.toPx()
                        val (a, b) = handleCentres(boxes.first(), boxes.last(), r)
                        val reach = 24.dp.toPx()
                        val da = (down.position - a).getDistance()
                        val db = (down.position - b).getDistance()
                        if (minOf(da, db) > reach) return@awaitEachGesture
                        val shownStart = reader.state.value.page?.startChar ?: 0
                        // 앞 쪽에서 이어 온 고르기는 시작 손잡이가 이 쪽에 없다 — 끝 손잡이만 잡힌다.
                        val isStart = da < db && now.start >= shownStart
                        // 손가락은 물방울을 잡고 있지만 고를 글자는 그 위의 줄이다. 잡은 순간의 높이 차를 끝까지 유지한다.
                        val grabbed = if (isStart) boxes.first() else boxes.last()
                        val lift = down.position.y - grabbed.center.y
                        down.consume()
                        drag(down.id) { change ->
                            change.consume()
                            val current = selection ?: return@drag
                            selection = dragHandle(current, isStart, charAtScreen(change.position.x, change.position.y - lift, after = !isStart))
                        }
                    }
                },
        )

        // 고른 구간: 손잡이와 메뉴(N4).
        selection?.let { sel ->
            val boxes = boxesOf(sel.start, sel.endExclusive)
            val page = state.page
            val position = state.position
            val shownStart = page?.startChar ?: 0
            val shownEnd = (state.rightPage ?: page)?.endCharExclusive ?: 0
            val fromBefore = sel.start < shownStart
            // "이어서 ›": 고른 끝이 보이는 쪽의 마지막 글자에 닿았고, 이 장에 뒤 쪽이 있을 때만.
            val canContinue = position != null && sel.endExclusive >= lastVisible(state.text, shownStart, shownEnd) &&
                position.pageIndex + (if (state.rightPage != null) 1 else 0) < position.pageCount - 1
            if (boxes.isNotEmpty()) {
                SelectionHandles(boxes.first(), boxes.last(), accent, showStart = !fromBefore)
                if (fromBefore) {
                    ContinueBanner(shownStart - sel.start, Modifier.align(Alignment.TopCenter).windowInsetsPadding(cutout).padding(top = 4.dp))
                }
                // 메모 판이 떠 있는 동안에는 메뉴를 숨긴다. 고른 칠 · 손잡이는 남겨 "무엇에 대한 메모인지" 보이게 하되,
                // 판 뒤에 같은 색 단추가 한 벌 더 있으면 어느 것이 판의 것인지 헷갈린다.
                if (memo == null) FloatingMenu(
                    anchor = boxes.bounds(),
                    current = null,
                    words = listOf("메모", "복사", "공유", "사전") + if (canContinue) listOf(CONTINUE) else emptyList(),
                    onPen = { pen ->
                        lastPen = pen
                        selection = null
                        scope.go { reader.highlight(sel.start, sel.endExclusive, pen.color) }
                        hiddenHint()
                    },
                    onWord = { word ->
                        val quote = snippetOf(state.text, sel.start, sel.endExclusive, state.paragraphStarts)
                        when (word) {
                            "메모" -> memo = MemoDraft(null, sel, quote, lastPen)
                            "복사" -> if (!copyText(context, quote)) say("복사했습니다")
                            "공유" -> shareOut(context, shareText(quote, null, reader.title))
                            "사전" -> if (!lookUp(context, quote)) say("낱말을 찾아 줄 사전 앱이 없습니다")
                            CONTINUE -> {
                                val spine = state.position?.spineIndex
                                if (spine != null) {
                                    carry = spine to sel
                                    scope.go { reader.next() }
                                }
                            }
                        }
                        if (word != "메모" && word != CONTINUE) selection = null
                    },
                )
            }
        }
        tapped?.let { note ->
            val boxes = boxesOf(note.start.charOffset, note.end.charOffset)
            if (boxes.isNotEmpty()) {
                FloatingMenu(
                    anchor = boxes.bounds(),
                    current = note.color.pen,
                    words = listOf("메모", "복사", "공유", "지우기"),
                    onPen = { pen ->
                        lastPen = pen
                        tapped = null
                        scope.go { reader.update(note.copy(color = pen.color)) }
                    },
                    onWord = { word ->
                        tapped = null
                        when (word) {
                            "메모" -> memo = MemoDraft(note, null, note.snippet, note.color.pen)
                            "복사" -> if (!copyText(context, note.snippet)) say("복사했습니다")
                            "공유" -> shareOut(context, shareText(note.snippet, note.note, reader.title))
                            "지우기" -> scope.go { reader.remove(note); say("형광펜을 지웠습니다") }
                        }
                    },
                )
            }
        }

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

        // 자동 넘김(L7). 메뉴가 열렸거나 듣는 중이면 쉰다.
        val autoSuspended = panel != Panel.None || listen.active || selection != null || memo != null
        val autoTurn = rememberAutoTurn(prefs.screen.autoTurn, state.position?.let { it.spineIndex to it.pageIndex }, autoSuspended) {
            scope.go { reader.next() }
        }
        val lift = if (listen.active || autoTurn.visible(prefs.screen.autoTurn, autoSuspended)) 124.dp else 64.dp
        if (panel == Panel.None) {
            if (listen.active) {
                ListenPlayer(
                    state = listen,
                    onPrevious = { listening?.previous() },
                    onToggle = { listening?.toggle() },
                    onNext = { listening?.next() },
                    onSettings = { listenSheet = true },
                    onClose = { ListenHub.detach(listening) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
                )
            } else if (autoTurn.visible(prefs.screen.autoTurn, autoSuspended)) {
                CpAutoTurnPill(autoTurn, Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp))
            }
            if (search.current >= 0 && search.results.isNotEmpty()) {
                SearchResultBar(
                    index = search.current,
                    total = search.results.size,
                    onPrevious = { if (search.current > 0) openHit(search.current - 1) },
                    onNext = { if (search.current < search.results.size - 1) openHit(search.current + 1) },
                    onList = { panel = Panel.Search },
                    onClose = { search.current = -1; scope.go { reader.clearHighlight() } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = lift),
                )
            } else if (state.canReturn) {
                ReturnChip({ scope.go { reader.returnBack() } }, Modifier.align(Alignment.BottomCenter).padding(bottom = lift))
            }
        }
        CpBrightnessOverlay(dragBrightness, Modifier.align(Alignment.CenterStart))

        when (panel) {
            // 목소리 화면은 아래에서 듣기 판과 함께 그린다.
            Panel.None, Panel.Voices -> Unit
            Panel.Bar, Panel.View -> ReaderBar(
                reader = reader,
                state = state,
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                showView = panel == Panel.View,
                onClose = closeBook,
                onPanel = { panel = it },
                onBookmark = ::toggleBookmark,
                onSearch = { panel = Panel.Search },
                onListen = { if (listening != null) { panel = Panel.None; listening.play() } else startListening() },
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
            Panel.Contents, Panel.Notes -> ReaderLists(
                reader = reader,
                state = state,
                showNotes = panel == Panel.Notes,
                onPanel = { panel = it },
                onMemo = { memo = it },
                onShare = { shareOut(context, it) },
                scope = scope,
            )
        }

        if (listenSheet && listen.active) {
            ListenSheet(
                prefs = prefs.listen,
                timer = listen.timer,
                onRate = { next -> onPrefsChange(prefs.copy(listen = next)); listening?.setRate(next.rate) },
                onVoices = { listenSheet = false; panel = Panel.Voices },
                onTimer = { listening?.setTimer(it) },
                onClose = { listenSheet = false },
            )
        }
        if (panel == Panel.Voices) {
            VoiceScreen(
                kit = kit,
                current = prefs.listen,
                onPick = { picked ->
                    val engineChanged = picked.engine != prefs.listen.engine
                    onPrefsChange(prefs.copy(listen = picked))
                    val l = listening
                    if (l != null && engineChanged) {
                        // 엔진이 바뀌면 새 엔진으로 다시 연다 — 듣던 문장부터.
                        val at = l.state.value.sentence?.start
                        val spine = l.state.value.spine
                        ListenHub.detach(l)
                        val position = state.position
                        if (position != null) {
                            val next = Listening(reader, kit.speaker(picked.engine), ListenHub.scope)
                            ListenHub.attach(context, next)
                            ListenHub.scope.launch { next.start(spine.takeIf { it >= 0 } ?: position.spineIndex, at ?: (state.page?.startChar ?: 0), picked.rate, picked.voice) }
                        }
                    } else {
                        l?.setVoice(picked.voice)
                    }
                },
                onBack = { panel = Panel.None; if (listen.active) listenSheet = true },
            )
        }

        // 메모 판은 독서노트 목록 위에서도 뜬다("메모 고치기").
        memo?.let { draft ->
            MemoSheet(
                draft = draft,
                onSave = { text, pen ->
                    memo = null
                    selection = null
                    lastPen = pen
                    scope.go {
                        val sel = draft.selection
                        if (draft.annotation != null) {
                            reader.update(draft.annotation.copy(color = pen.color).withNote(text))
                        } else if (sel != null) {
                            reader.highlight(sel.start, sel.endExclusive, pen.color, text)
                            hiddenHint()
                        }
                    }
                },
                onCancel = { memo = null },
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
private enum class Panel { None, Bar, View, Fonts, Settings, Search, Contents, Notes, Voices }

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
    onListen: () -> Unit,
    scope: CoroutineScope,
) {
    val position = state.position
    CpReaderBar(
        title = reader.title,
        subtitle = if (position != null) "${position.spineIndex + 1} / ${state.chapterCount} 장" else null,
        bookmarked = state.bookmarked,
        onBookmark = onBookmark,
        onSearch = onSearch,
        onListen = onListen,
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
        // 책갈피 도구는 독서노트에 합쳤다(N5). 꽂기는 위쪽 책갈피 단추 · 오른쪽 위 모서리 누르기 그대로.
        CpToolButton(CpIcons.Note, "독서노트", { onPanel(Panel.Notes) })
        CpToolButton(CpIcons.TextSize, "보기", { onPanel(if (showView) Panel.Bar else Panel.View) }, selected = showView)
    }
}

/** 목차 · 독서노트 전체 화면. 목차는 한 줄 48dp, 열면 지금 위치로 스크롤한다. */
@Composable
private fun ReaderLists(
    reader: BookReader,
    state: ReaderState,
    showNotes: Boolean,
    onPanel: (Panel) -> Unit,
    onMemo: (MemoDraft) -> Unit,
    onShare: (String) -> Unit,
    scope: CoroutineScope,
) {
    var toc by remember { mutableStateOf<List<TocEntry>?>(null) }
    var notes by remember { mutableStateOf<ReadingNotes?>(null) }
    var filter by remember { mutableStateOf(NoteFilter.All) }
    LaunchedEffect(Unit) { toc = runCatching { reader.outline() }.getOrDefault(emptyList()) }
    // 칠을 고치면(메모 판 · ⋮ 메뉴) notesVersion 이 는다 — 그때 목록도 다시 모은다.
    LaunchedEffect(toc, state.notesVersion) {
        val entries = toc ?: return@LaunchedEffect
        notes = runCatching { readingNotes(reader, entries) }.getOrNull() ?: ReadingNotes(emptyList(), emptyMap())
    }

    CpFullScreen {
        CpHeader(title = reader.title, subtitle = reader.document.meta.author, onBack = { onPanel(Panel.Bar) }) {
            if (showNotes && !notes?.items.isNullOrEmpty()) {
                CpText(
                    "내보내기", CpTheme.type.label, CpTheme.colors.accent,
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .clickable { notes?.let { onShare(exportNotes(reader.title, reader.document.meta.author, it.items)) } }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        CpTabBar(
            listOf("목차", notes?.let { "독서노트 ${it.items.size}" } ?: "독서노트"),
            if (showNotes) 1 else 0,
            { onPanel(if (it == 1) Panel.Notes else Panel.Contents) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showNotes) {
                val shown = notes
                CpReadingNotesList(
                    items = shown?.items,
                    filter = filter,
                    onFilter = { filter = it },
                    onOpen = { item ->
                        when (val source = shown?.sources?.get(item.key)) {
                            is Bookmark -> scope.go { reader.goTo(source) }
                            is Annotation -> scope.go { reader.goTo(source) }
                        }
                        onPanel(Panel.None)
                    },
                    onRemove = { item ->
                        when (val source = shown?.sources?.get(item.key)) {
                            is Bookmark -> scope.go { reader.removeBookmark(source); notes = readingNotes(reader, toc.orEmpty()) }
                            is Annotation -> scope.go { reader.remove(source) }
                        }
                    },
                    onMemo = { item ->
                        (shown?.sources?.get(item.key) as? Annotation)?.let { onMemo(MemoDraft(it, null, it.snippet, it.color.pen)) }
                    },
                    onRecolor = { item, pen ->
                        (shown?.sources?.get(item.key) as? Annotation)?.let { a -> scope.go { reader.update(a.copy(color = pen.color)) } }
                    },
                    onShare = { item ->
                        (shown?.sources?.get(item.key) as? Annotation)?.let { onShare(shareText(it.snippet, it.note, reader.title)) }
                    },
                )
            } else {
                TocList(toc, state) { entry -> scope.go { reader.goTo(entry) }; onPanel(Panel.None) }
            }
        }
    }
}

/** 독서노트 목록과, 항목 열쇠 → 원래 것(책갈피 · 형광펜). */
private class ReadingNotes(val items: List<NoteItem>, val sources: Map<String, Any>)

/**
 * 책갈피와 형광펜을 한 목록으로: 책 순서(N6) · 장별 묶음 · "3% · 날짜"(N7). 같은 자리면 책갈피가 먼저 — 쪽의
 * 머리에 꽂은 것이 그 쪽 안의 칠보다 앞에 읽힌다.
 */
private suspend fun readingNotes(reader: BookReader, toc: List<TocEntry>): ReadingNotes {
    fun section(spine: Int) = toc.getOrNull(currentTocIndex(toc, spine))?.label ?: "${spine + 1}장"
    val rows = ArrayList<Triple<Triple<Int, Int, Int>, NoteItem, Any>>()
    for (mark in reader.bookmarks()) {
        val at = mark.locator as? io.github.kgcaudit.reader.document.Locator.Reflow ?: continue
        val item = NoteItem(
            key = "b${mark.id}",
            section = section(at.spine),
            text = mark.snippet?.takeIf { it.isNotBlank() } ?: "(내용 없음)",
            pen = null,
            memo = null,
            where = noteWhere(reader.percentOf(at), mark.createdAtEpochMs),
        )
        rows += Triple(Triple(at.spine, at.charOffset, 0), item, mark)
    }
    for (note in reader.annotations()) {
        val item = NoteItem(
            key = "a${note.id}",
            section = section(note.start.spine),
            text = note.snippet,
            pen = note.color.pen,
            memo = note.note,
            where = noteWhere(reader.percentOf(note.start), note.createdAtEpochMs, reader.pagesOf(note)),
        )
        rows += Triple(Triple(note.start.spine, note.start.charOffset, 1), item, note)
    }
    rows.sortWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
    return ReadingNotes(rows.map { it.second }, rows.associate { it.second.key to it.third })
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
