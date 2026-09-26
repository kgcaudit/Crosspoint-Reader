package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.kgcaudit.reader.ui.design.PdfFit
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.listen.ListenHub
import io.github.kgcaudit.reader.listen.ListenKit
import io.github.kgcaudit.reader.listen.ListenPlayer
import io.github.kgcaudit.reader.listen.ListenPrefs
import io.github.kgcaudit.reader.listen.ListenSheet
import io.github.kgcaudit.reader.listen.ListenState
import io.github.kgcaudit.reader.listen.Listening
import io.github.kgcaudit.reader.listen.VoiceScreen
import io.github.kgcaudit.reader.ui.design.CpMemoSheet
import io.github.kgcaudit.reader.ui.design.CpSearchResultBar
import io.github.kgcaudit.reader.ui.design.CpSearchRow
import io.github.kgcaudit.reader.ui.design.CpSearchScreen
import io.github.kgcaudit.reader.ui.design.Pen
import io.github.kgcaudit.reader.ui.design.copyText
import io.github.kgcaudit.reader.ui.design.lookUp
import io.github.kgcaudit.reader.ui.design.shareOut
import io.github.kgcaudit.reader.ui.design.shareText
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpReaderBar
import io.github.kgcaudit.reader.ui.design.CpBrightnessOverlay
import io.github.kgcaudit.reader.ui.design.CpBrightnessRow
import io.github.kgcaudit.reader.ui.design.CpPageTurn
import io.github.kgcaudit.reader.ui.design.ReadingSpeed
import io.github.kgcaudit.reader.ui.design.brightnessEdge
import io.github.kgcaudit.reader.ui.design.systemBrightness
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpReadingFooter
import io.github.kgcaudit.reader.ui.design.CpRibbon
import io.github.kgcaudit.reader.ui.design.CpThemeSwatches
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.CpViewSettingsScreen
import io.github.kgcaudit.reader.ui.design.FooterInfo
import io.github.kgcaudit.reader.ui.design.ReadingWindow
import io.github.kgcaudit.reader.ui.design.AutoTurn
import io.github.kgcaudit.reader.ui.design.CpAutoTurnPill
import io.github.kgcaudit.reader.ui.design.KeepScreenOn
import io.github.kgcaudit.reader.ui.design.rememberAutoTurn
import io.github.kgcaudit.reader.ui.design.visible
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import io.github.kgcaudit.reader.ui.design.TapAction
import io.github.kgcaudit.reader.ui.design.VolumeKeyPaging
import io.github.kgcaudit.reader.ui.design.actionAt
import io.github.kgcaudit.reader.ui.design.CpTabBar
import io.github.kgcaudit.reader.ui.design.CpReadingNotesList
import io.github.kgcaudit.reader.ui.design.NoteFilter
import io.github.kgcaudit.reader.ui.design.NoteItem
import io.github.kgcaudit.reader.ui.design.exportNotes
import io.github.kgcaudit.reader.ui.design.noteWhere
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToolButton
import io.github.kgcaudit.reader.ui.design.ScreenRotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PDF 리더. 조작은 EPUB 리더와 같다 — 왼쪽 3분의 1 은 앞 쪽, 오른쪽 3분의 1 은 다음 쪽, 가운데는
 * 메뉴, 옆으로 밀어도 넘어간다. 책 종류에 따라 손에 익은 동작이 달라지면 안 된다.
 *
 * 더해진 것은 확대뿐이다: 두 손가락으로 벌리거나 두 번 누른다. 확대한 동안 한 손가락으로 끌면
 * 쪽 안을 움직인다(넘기지 않는다). 넘기면 새 쪽은 다시 전체가 보인다.
 *
 * @param onChrome 메뉴가 열리고 닫힐 때. 앱이 시스템 바를 보이고 숨기는 데 쓴다.
 */
@Composable
fun PdfScreen(
    reader: PdfReader,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    prefs: ScreenPrefs = ScreenPrefs(),
    onPrefsChange: (ScreenPrefs) -> Unit = {},
    /** 읽는 속도(쪽/분). 남은 시간을 센다(E5). */
    speed: ReadingSpeed = remember { ReadingSpeed() },
    onSpeedChange: (ReadingSpeed) -> Unit = {},
    /** 듣기 설정(빠르기 · 목소리). EPUB 과 한 벌이다. */
    listen: ListenPrefs = ListenPrefs(),
    onListenChange: (ListenPrefs) -> Unit = {},
    /** 음성 엔진. null 이면 휴대폰의 것(시험은 가짜를 준다). */
    listenKit: ListenKit? = null,
) {
    val state by reader.state.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = CpTheme.colors
    var panel by remember { mutableStateOf(PdfPanel.None) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastCount by remember { mutableStateOf(0) }
    // 하단 정보의 "장 제목" · "이 장 남은 쪽". 목차는 파일마다 한 번 읽는다.
    var contents by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    LaunchedEffect(reader) { contents = runCatching { reader.outline() }.getOrDefault(emptyList()) }

    // 왼쪽 끝을 미는 동안의 밝기(E6). 손을 떼면 설정으로 저장한다.
    var dragBrightness by remember { mutableStateOf<Float?>(null) }
    val latestPrefs by androidx.compose.runtime.rememberUpdatedState(prefs)
    val context = androidx.compose.ui.platform.LocalContext.current
    var speedRevision by remember { mutableStateOf(0) }
    // 읽는 속도: 앞으로 넘길 때마다 방금 본 쪽 수와 머문 시간으로 잰다.
    val turn = remember { longArrayOf(0L, -1L, 0L) } // [시각, 첫 쪽, 쪽 수]
    LaunchedEffect(state.page) {
        if (!state.ready) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (turn[1] >= 0 && state.page > turn[1] && speed.record(turn[2].toDouble(), now - turn[0])) {
            onSpeedChange(speed)
            speedRevision++
        }
        turn[0] = now
        turn[1] = state.page.toLong()
        turn[2] = state.shown.size.toLong()
    }

    // 자동 넘김을 켜 두면 화면을 켜 둔다(EPUB 과 같다).
    ReadingWindow(
        prefs.copy(
            brightness = dragBrightness ?: prefs.brightness,
            keepScreenOn = if (prefs.autoTurn != AutoTurn.Off) KeepScreenOn.Always else prefs.keepScreenOn,
        ),
        activity = state.page,
    )
    // 폭 맞춤(③): 지금 쪽 안을 한 화면씩 내리는 손잡이. 보이는 PageView 가 걸어 둔다.
    val scroller = remember { PageScroller() }
    // 앞 쪽으로 돌아갈 때 그 쪽을 끝에서 보이게 할 쪽. 다른 쪽으로 가면 지운다 — 남겨 두면 나중에 그 쪽에 앞으로
    // 넘어 들어와도 끝부터 보인다.
    var enterBottom by remember { mutableStateOf(-1) }
    LaunchedEffect(state.page) { if (state.page != enterBottom) enterBottom = -1 }
    val fitWidth = prefs.pdfFit == PdfFit.Width

    /**
     * 누름 · 볼륨 키 · 자동 넘김의 "앞으로 / 뒤로". 폭 맞춤이면 쪽 안에서 한 화면 옮기고, 쪽 끝이면 넘긴다.
     * 셋이 따로 판단하면 누르면 내려가는데 볼륨 키는 쪽을 건너뛰어 쪽 아래쪽을 못 읽는다.
     */
    fun advance(forward: Boolean) {
        if (fitWidth && scroller.scroll?.invoke(forward) == true) return
        scope.go {
            if (forward) {
                reader.next()
            } else {
                if (fitWidth && state.page > 0) enterBottom = state.page - 1
                reader.previous()
            }
        }
    }
    VolumeKeyPaging(enabled = prefs.volumeKeys && panel == PdfPanel.None) { forward -> advance(forward) }
    fun say(message: String) {
        toast = message
        toastCount++
    }
    fun toggleBookmark() = scope.go {
        reader.toggleBookmark()
        say(if (reader.state.value.bookmarked) "책갈피를 꽂았습니다" else "책갈피를 뺐습니다")
    }

    // ── 글자 층(4단계 PDF): 찾기 · 고르기 · 칠 · 듣기 ─────────────────────
    val text = remember(reader) { PdfTextState() }
    val search = remember(reader) { PdfSearch() }
    var lastPen by remember { mutableStateOf(Pen.Yellow) }
    // 찾은 곳 · 듣는 문장을 보이게 옮길 곳(쪽, 쪽 안 네모). 폭 맞춤 · 확대에서만 실제로 움직인다.
    var focus by remember { mutableStateOf<Pair<Int, PageRegion>?>(null) }
    // 쪽이 바뀌면 고르던 것 · 누른 칠은 놓는다(한 쪽 안에서만 고른다, 결정 2).
    LaunchedEffect(state.shown) {
        text.selection = null
        text.tapped = null
    }
    /** 글자가 그림인 PDF(결정 1): 단추는 두고 누르면 까닭을 알린다. */
    fun whenReadable(action: () -> Unit) = scope.go {
        if (reader.hasText()) {
            action()
        } else {
            // 도구줄을 닫고 알린다 — 열어 둔 채면 알림이 도구줄 밑에 가려 보이지 않는다.
            panel = PdfPanel.None
            say(SCANNED)
        }
    }
    fun hiddenHint() {
        if (!latestPrefs.showHighlights) say("형광펜을 숨겨 둔 상태라 보이지 않습니다. 보기 설정에서 켤 수 있습니다")
    }
    fun longPress(at: Offset) {
        if (!reader.readsText) return
        val placed = text.placedAt(at) ?: return
        scope.go {
            if (!reader.hasText()) {
                say(SCANNED)
                return@go
            }
            val layer = reader.textLayer(placed.page)
            text.layers++
            val p = placed.toPage(at)
            val i = layer.charAt(p.x, p.y) ?: return@go
            val word = layer.wordAt(i)
            text.tapped = null
            text.selection = PdfSelection(placed.page, word.first, word.last + 1)
        }
    }
    fun onPen(pen: Pen) {
        lastPen = pen
        val sel = text.selection
        val note = text.tapped
        text.selection = null
        text.tapped = null
        if (sel != null) {
            scope.go { reader.highlight(sel.page, sel.start, sel.endExclusive, pen.color) }
            hiddenHint()
        } else if (note != null) {
            scope.go { reader.update(note.copy(color = pen.color)) }
        }
    }
    fun onWord(word: String) {
        val sel = text.selection
        val note = text.tapped
        val quote = if (sel != null) reader.cachedLayer(sel.page)?.quote(sel.start, sel.endExclusive).orEmpty() else note?.snippet.orEmpty()
        when (word) {
            "메모" -> text.memo = PdfMemo(note, sel, quote, note?.color?.pen ?: lastPen)
            "복사" -> if (!copyText(context, quote)) say("복사했습니다")
            "공유" -> shareOut(context, shareText(quote, note?.note, reader.title))
            "사전" -> if (!lookUp(context, quote)) say("낱말을 찾아 줄 사전 앱이 없습니다")
            "지우기" -> note?.let { n ->
                scope.go { reader.remove(n) }
                say("형광펜을 지웠습니다")
            }
        }
        if (word != "메모") {
            text.selection = null
            text.tapped = null
        }
    }
    fun openHit(i: Int) {
        val hit = search.results.getOrNull(i) ?: return
        search.current = i
        text.found = search.results.groupBy({ it.spine }, { it.start until it.endExclusive })
        text.current = hit.spine to (hit.start until hit.endExclusive)
        panel = PdfPanel.None
        scope.go {
            reader.goTo(hit.spine)
            val box = reader.textLayer(hit.spine).rects(hit.start, hit.endExclusive).firstOrNull()
            text.layers++
            if (box != null) focus = hit.spine to box
        }
    }
    fun closeSearch() {
        search.current = -1
        text.found = emptyMap()
        text.current = null
    }

    // 듣기(4-3). EPUB 과 같은 듣기 — 쪽 하나가 한 단위다.
    val kit = remember(listenKit) { listenKit ?: ListenKit.android(context) }
    val hub by ListenHub.current.collectAsState()
    val listening = hub?.takeIf { it.belongsTo(reader) }
    val heard = listening?.state?.collectAsState()?.value ?: ListenState()
    var listenSheet by remember { mutableStateOf(false) }
    fun startListening() = whenReadable {
        val l = Listening(reader, kit.speaker(listen.engine), ListenHub.scope)
        ListenHub.attach(context, l)
        panel = PdfPanel.None
        ListenHub.scope.launch { l.start(state.page, 0, listen.rate, listen.voice, listen.join) }
    }
    // 책을 닫으면 듣기도 끝낸다(EPUB 과 같다).
    val closeBook = {
        ListenHub.detach(listening)
        onClose()
    }
    LaunchedEffect(heard.message) {
        heard.message?.let { say(it); listening?.consumeMessage() }
    }
    // 사람이 쪽을 옮기면 듣기도 그 쪽의 첫 문장으로. 듣기가 넘긴 것이면 읽는 문장이 이미 보이는 쪽에 있다.
    LaunchedEffect(state.shown) {
        val l = listening ?: return@LaunchedEffect
        if (heard.active && heard.spine !in state.shown) l.onPageShown(state.page, 0, Int.MAX_VALUE)
    }
    val sentence = heard.sentence?.takeIf { heard.active && heard.spine in state.shown }?.let { heard.spine to (it.start until it.endExclusive) }
    // 폭 맞춤 · 확대에서 읽는 문장이 화면 밖으로 가면 따라 내린다.
    LaunchedEffect(sentence) {
        val (page, range) = sentence ?: return@LaunchedEffect
        val box = reader.textLayer(page).rects(range.first, range.last + 1).firstOrNull() ?: return@LaunchedEffect
        focus = page to box
    }

    LaunchedEffect(panel) { onChrome(panel != PdfPanel.None) }
    BackHandler {
        if (panel == PdfPanel.None && (text.selection != null || text.tapped != null)) {
            text.selection = null
            text.tapped = null
            return@BackHandler
        }
        panel = when (panel) {
            PdfPanel.None -> { closeBook(); PdfPanel.None }
            PdfPanel.Contents, PdfPanel.Notes, PdfPanel.Search -> PdfPanel.Bar
            PdfPanel.View -> PdfPanel.Bar
            PdfPanel.Settings -> PdfPanel.View
            PdfPanel.Bar -> PdfPanel.None
            PdfPanel.Voices -> { if (heard.active) listenSheet = true; PdfPanel.None }
        }
    }

    Box(
        Modifier.fillMaxSize().brightnessEdge(
            enabled = prefs.brightnessGesture && panel == PdfPanel.None,
            current = { latestPrefs.brightness ?: systemBrightness(context) },
            onDrag = { value ->
                if (value != null) {
                    dragBrightness = value
                } else {
                    dragBrightness?.let { v -> onPrefsChange(latestPrefs.copy(brightness = v)) }
                    dragBrightness = null
                }
            },
        ),
    ) {
        Column(Modifier.fillMaxSize().background(colors.paper)) {
            // 상태 막대 자리를 뺀 곳에 쪽을 놓는다. 겹치면 세로로 긴 쪽의 마지막 줄이 막대에 가린다.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout)) {
                val viewW = constraints.maxWidth.toFloat()
                val viewH = constraints.maxHeight.toFloat()
                val density = androidx.compose.ui.platform.LocalDensity.current.density
                val smallest = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp
                // 폭 맞춤은 한 쪽씩이다 — 두 쪽을 나란히 폭에 맞추면 쪽 전체와 다를 게 없다.
                val twoPages = !fitWidth && prefs.twoPages(viewW / density, viewH / density, smallest)
                LaunchedEffect(twoPages, prefs.pdfCoverAlone) { reader.setSpread(if (twoPages) prefs.pdfCoverAlone else null) }
                val onTap: (Offset, Float) -> Unit = { at, corner ->
                    val note = if (latestPrefs.showHighlights) annotationAt(reader, text, reader.state.value.notes, at) else null
                    when {
                        panel != PdfPanel.None -> panel = PdfPanel.None
                        // 고르는 중에 다른 곳을 누르면 고르기만 푼다(쪽이 넘어가면 고른 것을 잃는다).
                        text.selection != null || text.tapped != null -> {
                            text.selection = null
                            text.tapped = null
                        }
                        note != null -> text.tapped = note
                        else -> when (prefs.touch.actionAt(at.x, at.y, viewW, corner)) {
                            TapAction.Previous -> advance(false)
                            TapAction.Next -> advance(true)
                            TapAction.Menu -> panel = PdfPanel.Bar
                            TapAction.Bookmark -> toggleBookmark()
                        }
                    }
                }
                // 넘김 효과 동안 옛 쪽도 자리를 알리므로, 지금 보이는 쪽의 것만 받는다.
                val onPlaced: (List<Placed>) -> Unit = { list -> if (list.all { it.page in reader.state.value.shown } && list != text.placed) text.placed = list }
                val onSwipe: (Boolean) -> Unit = { forward -> scope.go { if (forward) reader.next() else reader.previous() } }
                // 넘김 효과(E7): 보이는 쪽(들)이 바뀔 때.
                if (state.ready && state.pageCount > 0 && viewW > 0f && viewH > 0f) CpPageTurn(
                    key = state.shown,
                    effect = prefs.pageTurn,
                    forward = { from, to -> (to.firstOrNull() ?: 0) > (from.firstOrNull() ?: 0) },
                ) { shown ->
                    if (twoPages) {
                        SpreadView(reader, shown, viewW, viewH, onTap, onSwipe, ::longPress, onPlaced, text)
                    } else {
                        PageView(
                            reader = reader,
                            page = shown.first(),
                            viewW = viewW,
                            viewH = viewH,
                            onTap = onTap,
                            onSwipe = onSwipe,
                            fitWidth = fitWidth,
                            fromBottom = enterBottom == shown.first(),
                            scroller = scroller,
                            onLongPress = ::longPress,
                            onPlaced = onPlaced,
                            text = text,
                            focus = focus?.takeIf { it.first == shown.first() }?.second,
                        )
                    }
                }
                // 칠 · 찾은 곳 · 읽는 문장 · 고르기(쪽 그림 위에 얹는다, 누름은 받지 않는다).
                if (reader.readsText) PdfTextOverlay(
                    reader = reader,
                    text = text,
                    notes = state.notes,
                    showNotes = prefs.showHighlights,
                    sentence = sentence,
                    accent = colors.accent,
                    onPen = ::onPen,
                    onWord = ::onWord,
                )
            }
            CpReadingFooter(
                info = FooterInfo(
                    bookTitle = reader.title,
                    chapterTitle = contents.getOrNull(currentContentsIndex(contents, state.page))?.label,
                    // 인쇄된 쪽 번호가 파일 순서와 다르면(로마 숫자 머리말 등) 앞에 함께 적는다: "iv · 4 / 230".
                    // 두쪽이면 두 쪽을 묶어 "2–3 / 84"(T5).
                    page = if (state.pageCount > 0) {
                        val last = state.shown.maxOrNull() ?: state.page
                        val numbers = if (last > state.page) "${state.page + 1}–${last + 1}" else "${state.page + 1}"
                        val position = "$numbers / ${state.pageCount}"
                        reader.book.pageLabel(state.page)?.let { "$it · $position" } ?: position
                    } else {
                        ""
                    },
                    percent = state.percent,
                    chapterPagesLeft = if (state.pageCount > 0) {
                        pagesLeftInSection(contents, state.shown.maxOrNull() ?: state.page, state.pageCount)
                    } else {
                        null
                    },
                    // 남은 시간(E5): PDF 는 쪽 단위로 잰다(글자를 읽을 수 없다).
                    chapterMinutesLeft = speedRevision.let { _ ->
                        if (state.pageCount > 0) {
                            speed.minutesFor(pagesLeftInSection(contents, state.shown.maxOrNull() ?: state.page, state.pageCount).toDouble())
                        } else {
                            null
                        }
                    },
                    bookMinutesLeft = speedRevision.let { _ ->
                        speed.minutesFor((state.pageCount - 1 - (state.shown.maxOrNull() ?: state.page)).toDouble())
                    },
                ),
                footer = prefs.footer,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp).windowInsetsPadding(WindowInsets.displayCutout),
            )
        }
        // PDF 는 쪽이 화면을 채우는 일이 많아 리본을 쪽 위가 아니라 화면 모서리에 둔다. 쪽 안에 두면 표지·그림을
        // 가린다(구상안 ⑥).
        if (state.bookmarked && state.pageCount > 0) {
            CpRibbon(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(end = 20.dp))
        }
        // 자동 넘김(L7). PDF 도 글자 없이 쪽만 넘기면 되므로 같이 쓴다. 메뉴가 열렸거나 듣는 중 · 고르는 중이면 쉰다.
        val autoSuspended = panel != PdfPanel.None || heard.active || text.selection != null || text.memo != null
        val autoTurn = rememberAutoTurn(prefs.autoTurn, state.page, autoSuspended) { advance(true) }
        val lift = if (heard.active || autoTurn.visible(prefs.autoTurn, autoSuspended)) 124.dp else 64.dp
        if (panel == PdfPanel.None) {
            if (heard.active) {
                ListenPlayer(
                    state = heard,
                    onPrevious = { listening?.previous() },
                    onToggle = { listening?.toggle() },
                    onNext = { listening?.next() },
                    onSettings = { listenSheet = true },
                    onClose = { ListenHub.detach(listening) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
                )
            } else if (autoTurn.visible(prefs.autoTurn, autoSuspended)) {
                CpAutoTurnPill(autoTurn, Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp))
            }
            if (search.current >= 0 && search.results.isNotEmpty()) {
                CpSearchResultBar(
                    index = search.current,
                    total = search.results.size,
                    onPrevious = { if (search.current > 0) openHit(search.current - 1) },
                    onNext = { if (search.current < search.results.size - 1) openHit(search.current + 1) },
                    onList = { panel = PdfPanel.Search },
                    onClose = ::closeSearch,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = lift),
                )
            }
        }
        CpToast(toast, onDone = { toast = null }, Modifier.align(Alignment.BottomCenter), key = toastCount)
        CpBrightnessOverlay(dragBrightness, Modifier.align(Alignment.CenterStart))
    }

    when (panel) {
        PdfPanel.None -> Unit
        PdfPanel.Bar, PdfPanel.View -> CpReaderBar(
            title = reader.title,
            subtitle = "${state.page + 1} / ${state.pageCount} 쪽",
            bookmarked = state.bookmarked,
            onBookmark = ::toggleBookmark,
            onBack = closeBook,
            onDismiss = { panel = PdfPanel.None },
            // 찾기 · 듣기(4단계 PDF). 글자를 꺼낼 수 없는 휴대폰(안드로이드 14 이하)에서는 단추가 없다.
            onSearch = if (reader.readsText) ({ whenReadable { panel = PdfPanel.Search } }) else null,
            onListen = if (reader.readsText) ({ if (listening != null) { panel = PdfPanel.None; listening.play() } else startListening() }) else null,
            progress = if (state.pageCount > 1) state.page / (state.pageCount - 1f) else 1f,
            progressLabel = { pageAt(it, state.pageCount).let { p -> "${reader.book.pageLabel(p) ?: (p + 1)}쪽" } },
            onSeek = { target -> scope.go { reader.seek(target) } },
            above = {
                // EPUB 의 보기 판과 같은 자리. PDF 는 글자 크기·글꼴·여백이 없어 배경 · 밝기 · 화면 회전이다 —
                // 잡지·도면은 가로로 돌려 보는 일이 많아 회전을 판에 둔다.
                if (panel == PdfPanel.View) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        CpThemeSwatches(prefs.theme, { onPrefsChange(prefs.copy(theme = it)) })
                        CpBrightnessRow(prefs.brightness, { onPrefsChange(prefs.copy(brightness = it)) })
                        val rotations = ScreenRotation.entries
                        // 쪽 맞춤(③)을 회전 위에 둔다 — 가로로 돌리는 까닭이 대개 글자를 크게 보려는 것이라, 돌린 뒤
                        // 바로 위 줄에서 폭을 고른다.
                        val fits = PdfFit.entries
                        CpChoice("쪽 맞춤", fits.map { it.label }, fits.indexOf(prefs.pdfFit), {
                            onPrefsChange(prefs.copy(pdfFit = fits[it]))
                        })
                        CpChoice("화면 회전", rotations.map { it.label }, rotations.indexOf(prefs.rotation), {
                            onPrefsChange(prefs.copy(rotation = rotations[it]))
                        })
                        CpLinkRow("모든 보기 설정", "", { panel = PdfPanel.Settings })
                    }
                    Spacer(Modifier.height(4.dp))
                }
            },
        ) {
            // EPUB 리더와 같은 자리 · 같은 순서. 목차가 없는 PDF 도 단추는 둔다 — 열면 "목차가 없는
            // 파일입니다" 라고 말해 준다(책마다 단추가 생겼다 없어졌다 하면 손이 헤맨다).
            CpToolButton(CpIcons.Toc, "목차", { panel = PdfPanel.Contents })
            CpToolButton(CpIcons.Note, "독서노트", { panel = PdfPanel.Notes })
            CpToolButton(
                CpIcons.Rotate,
                "보기",
                { panel = if (panel == PdfPanel.View) PdfPanel.Bar else PdfPanel.View },
                selected = panel == PdfPanel.View,
            )
        }
        PdfPanel.Settings -> CpViewSettingsScreen(prefs, onPrefsChange, onBack = { panel = PdfPanel.View }, pdf = true, highlights = reader.readsText)
        PdfPanel.Contents, PdfPanel.Notes -> PdfLists(
            reader = reader,
            page = state.page,
            notes = state.notes,
            showNotes = panel == PdfPanel.Notes,
            onPanel = { panel = it },
            onMemo = { note -> text.memo = PdfMemo(note, null, note.snippet, note.color.pen) },
            scope = scope,
        )
        PdfPanel.Search -> CpSearchScreen(
            query = search.query,
            onQuery = { search.query = it },
            onClear = { search.query = ""; search.stop(); closeSearch() },
            summary = search.summary(state.pageCount),
            progress = if (search.running && state.pageCount > 0) search.searched / state.pageCount.toFloat() else null,
            rows = search.results.map { hit ->
                CpSearchRow(
                    section = contents.getOrNull(currentContentsIndex(contents, hit.spine))?.label ?: "${hit.spine + 1}쪽",
                    context = hit.context,
                    matchStart = hit.contextMatchStart,
                    matchLength = (hit.endExclusive - hit.start).coerceAtMost(hit.context.length - hit.contextMatchStart),
                    where = "${reader.book.pageLabel(hit.spine) ?: (hit.spine + 1)}쪽",
                )
            },
            onSearch = { search.start(reader, scope) },
            onOpen = ::openHit,
            onBack = { panel = PdfPanel.Bar },
        )
        PdfPanel.Voices -> VoiceScreen(
            kit = kit,
            current = listen,
            onPick = { picked ->
                val engineChanged = picked.engine != listen.engine
                onListenChange(picked)
                val l = listening
                if (l != null && engineChanged) {
                    // 엔진이 바뀌면 새 엔진으로 다시 연다 — 듣던 쪽부터.
                    val page = l.state.value.spine.takeIf { it >= 0 } ?: state.page
                    val at = l.state.value.sentence?.start ?: 0
                    ListenHub.detach(l)
                    val next = Listening(reader, kit.speaker(picked.engine), ListenHub.scope)
                    ListenHub.attach(context, next)
                    ListenHub.scope.launch { next.start(page, at, picked.rate, picked.voice, picked.join) }
                } else {
                    l?.setVoice(picked.voice)
                }
            },
            onBack = { panel = PdfPanel.None; if (heard.active) listenSheet = true },
        )
    }

    if (listenSheet && heard.active) {
        ListenSheet(
            prefs = listen,
            timer = heard.timer,
            onRate = { next -> onListenChange(next); listening?.setRate(next.rate) },
            onVoices = { listenSheet = false; panel = PdfPanel.Voices },
            onTimer = { listening?.setTimer(it) },
            onClose = { listenSheet = false },
            onJoin = { level -> onListenChange(listen.copy(join = level)); listening?.setJoin(level) },
            sample = heard.sentenceText,
            previewing = heard.previewing,
            onPreview = { listening?.preview(it) },
        )
    }

    // 메모 판은 독서노트 목록 위에서도 뜬다("메모 고치기").
    text.memo?.let { draft ->
        CpMemoSheet(
            key = draft,
            quote = draft.quote,
            initialText = draft.annotation?.note.orEmpty(),
            initialPen = draft.pen,
            onSave = { body, pen ->
                lastPen = pen
                text.memo = null
                text.selection = null
                text.tapped = null
                val a = draft.annotation
                val sel = draft.selection
                scope.go {
                    if (a != null) {
                        reader.update(a.copy(color = pen.color).withNote(body))
                    } else if (sel != null) {
                        reader.highlight(sel.page, sel.start, sel.endExclusive, pen.color, body)
                    }
                }
                if (a == null) hiddenHint()
            },
            onCancel = { text.memo = null },
        )
    }

    if (state.ready && state.pageCount <= 0) {
        CpPopup(title = "이 PDF 를 열지 못했습니다", message = "쪽이 하나도 없는 파일입니다.", onDismiss = closeBook) {
            Spacer(Modifier.height(16.dp))
            CpButton("라이브러리로", closeBook)
        }
    }
}

private enum class PdfPanel { None, Bar, View, Settings, Contents, Notes, Search, Voices }

/** 글자가 그림인 PDF 에서 찾기 · 듣기 · 고르기를 누르면(결정 1). */
internal const val SCANNED = "이 PDF 는 글자가 그림으로 되어 있어(스캔본) 찾기 · 듣기 · 형광펜을 쓸 수 없습니다"

/**
 * 보이는 쪽의 "한 화면 옮기기". 넘김 효과 동안 옛 쪽과 새 쪽이 함께 있으므로, 나중에 걸린(새) 쪽만 남고 옛 쪽이
 * 사라질 때 새 쪽의 것을 지우지 않는다. 옮겼으면 true, 이미 그 끝이면 false(그때 쪽을 넘긴다).
 */
private class PageScroller {
    var scroll: ((forward: Boolean) -> Boolean)? = null
}

/** 오른쪽 위 모서리의 책갈피 네모(EPUB 과 같다). */
private val CORNER = 56.dp

/** 진행 막대의 0..1 → 쪽(0부터). [PdfReader.seek] 과 같은 셈이라야 막대 위 숫자와 가는 곳이 같다. */
internal fun pageAt(fraction: Float, pageCount: Int): Int =
    if (pageCount <= 1) 0 else (fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt()

/** 그린 구역과 그것을 그린 화면 배치. 배치가 바뀐 뒤 옛 구역을 새 자리에 그리지 않게 함께 둔다. */
private class Rendered<K>(val key: K, val bitmap: Bitmap)

@Composable
private fun PageView(
    reader: PdfReader,
    page: Int,
    viewW: Float,
    viewH: Float,
    /** 누른 자리와 책갈피 모서리의 크기(px). */
    onTap: (Offset, Float) -> Unit,
    onSwipe: (forward: Boolean) -> Unit,
    fitWidth: Boolean = false,
    /** 쪽 끝부터 보인다(폭 맞춤에서 앞 쪽으로 돌아왔다). 처음 놓을 때만 본다. */
    fromBottom: Boolean = false,
    scroller: PageScroller? = null,
    /** 길게 누른 자리(글자 고르기). */
    onLongPress: (Offset) -> Unit = {},
    /** 쪽이 화면 어디에 놓였는지(칠 · 고르기 층이 따라 놓인다). */
    onPlaced: (List<Placed>) -> Unit = {},
    text: PdfTextState? = null,
    /** 보이게 옮길 쪽 안의 곳(찾은 말 · 듣는 문장). */
    focus: PageRegion? = null,
) {
    // 크기를 모르면 그리지 않는다. A 판형으로 먼저 그렸다가 가로 쪽으로 바뀌면 한 번 출렁인다. 앞뒤 쪽은
    // 미리 재 두므로 넘길 때는 바로 안다.
    var aspect by remember(page) { mutableStateOf(reader.book.knownAspectRatio(page)) }
    LaunchedEffect(page) { if (aspect == null) aspect = reader.book.pageAspectRatio(page) }
    val pageAspect = aspect ?: return
    fun rest(aspect: Float, bottom: Boolean = false) =
        if (fitWidth) PageViewport.fitWidth(viewW, viewH, aspect, bottom) else PageViewport.fit(viewW, viewH, aspect)
    var viewport by remember(page, viewW, viewH, pageAspect, fitWidth) { mutableStateOf(rest(pageAspect, fromBottom)) }
    // 바탕 그림은 쉬는 크기 그대로 그린다. 폭 맞춤을 쪽 전체 크기로 그려 늘리면 쉬는 동안 내내 글자가 흐리다.
    val restView = rest(pageAspect)
    val fitW = restView.width.roundToInt()
    val fitH = restView.height.roundToInt()
    if (scroller != null && fitWidth) {
        DisposableEffect(scroller, page) {
            val mine: (Boolean) -> Boolean = { forward -> viewport.scroll(forward)?.also { viewport = it } != null }
            scroller.scroll = mine
            onDispose { if (scroller.scroll === mine) scroller.scroll = null }
        }
    }

    SideEffect {
        onPlaced(listOf(Placed(page, Rect(viewport.left, viewport.top, viewport.left + viewport.width, viewport.top + viewport.height))))
    }
    LaunchedEffect(focus) { focus?.let { viewport = viewport.reveal(it) } }

    // 미리 그려 둔 쪽이면 첫 프레임부터 보인다. 기다렸다 받으면 넘길 때마다 빈 종이가 한 번 번쩍인다.
    var base by remember(page, fitW, fitH) { mutableStateOf(reader.cachedPage(page, fitW, fitH)) }
    var broken by remember(page) { mutableStateOf(false) }
    LaunchedEffect(page, fitW, fitH) {
        if (base == null) {
            val bitmap = reader.page(page, fitW, fitH)
            if (bitmap == null) broken = true else base = bitmap
        }
        for (near in intArrayOf(page + 1, page - 1)) {
            if (near !in 0 until reader.book.pageCount) continue
            val near0 = rest(reader.book.pageAspectRatio(near))
            reader.page(near, near0.width.roundToInt(), near0.height.roundToInt())
        }
    }

    // 확대한 채 손을 멈추면 보이는 부분만 화면 해상도로 다시 그린다. 쪽 전체를 확대 배율로 그리면
    // 5배에서 비트맵 하나가 250MB 다. 움직이는 동안은 흐린 전체 그림을 늘려 보여 준다.
    var sharp by remember(page) { mutableStateOf<Rendered<PageViewport>?>(null) }
    LaunchedEffect(viewport) {
        if (!viewport.isZoomed) return@LaunchedEffect
        delay(SETTLE_MS)
        val region = viewport.visible()
        val w = (region.width * viewport.width).roundToInt()
        val h = (region.height * viewport.height).roundToInt()
        reader.region(page, region, w, h)?.let { sharp = Rendered(viewport, it) }
    }

    Box(
        Modifier
            .fillMaxSize()
            // 확대한 쪽이 제 자리 밖(상태 막대 위)까지 그려지지 않게 자른다.
            .clipToBounds()
            .then(if (text != null) Modifier.selectionHandles(reader, text) else Modifier)
            .pointerInput(page, viewW, viewH, pageAspect) {
                detectTapGestures(
                    // 두 번 누르기를 기다리느라 한 번 누르기가 조금(약 0.3초) 늦다. PDF 는 글자가 작아
                    // 확대를 자주 하므로 받아들인다.
                    onDoubleTap = { at -> viewport = viewport.toggleZoom(at.x, at.y) },
                    onTap = { at -> onTap(at, CORNER.toPx()) },
                    onLongPress = { at -> onLongPress(at) },
                )
            }
            .pointerInput(page, viewW, viewH, pageAspect) {
                val threshold = 48.dp.toPx()
                awaitEachGesture {
                    // 손잡이를 끄는 누름은 손잡이 층이 먼저 먹었다 — 넘기기 · 확대로 읽지 않는다.
                    if (awaitFirstDown(requireUnconsumed = false).isConsumed) return@awaitEachGesture
                    var moving = false
                    var pinched = false
                    var travel = Offset.Zero
                    var swipe = 0f
                    // 폭 맞춤에서 처음 움직인 방향이 세로면 쪽 안을 내려 보는 것, 가로면 넘기는 것. 한 번 정하면
                    // 손을 뗄 때까지 바꾸지 않는다 — 비스듬히 내리다 쪽이 넘어가면 읽던 곳을 잃는다.
                    var vertical = false
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val fingers = event.changes.count { it.pressed }
                        if (!moving) {
                            travel += pan
                            moving = fingers > 1 || travel.getDistance() > viewConfiguration.touchSlop
                            if (moving) vertical = fitWidth && abs(travel.y) > abs(travel.x)
                        }
                        if (moving) {
                            if (fingers > 1) pinched = true
                            if (zoom != 1f) {
                                val focus = event.calculateCentroid()
                                viewport = viewport.zoom(zoom, focus.x, focus.y)
                            }
                            // 확대돼 있으면 끌기는 쪽 안에서 움직이는 것이다. 넘김으로 읽으면 확대한 곳을
                            // 보려고 끌 때마다 쪽이 넘어간다.
                            when {
                                viewport.isZoomed -> viewport = viewport.pan(pan.x, pan.y)
                                vertical -> viewport = viewport.pan(0f, pan.y)
                                else -> swipe += pan.x
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // 두 손가락으로 줄였다가 전체 크기로 돌아온 것은 넘기려던 게 아니다.
                    if (moving && !pinched && !vertical && !viewport.isZoomed && abs(swipe) > threshold) onSwipe(swipe < 0)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val shown = base
            val left = viewport.left.roundToInt()
            val top = viewport.top.roundToInt()
            if (shown != null) {
                drawImage(
                    shown.asImageBitmap(),
                    dstOffset = IntOffset(left, top),
                    dstSize = IntSize(viewport.width.roundToInt(), viewport.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            val detail = sharp?.takeIf { it.key == viewport }
            if (detail != null) {
                drawImage(
                    detail.bitmap.asImageBitmap(),
                    dstOffset = IntOffset(left.coerceAtLeast(0), top.coerceAtLeast(0)),
                    dstSize = IntSize(detail.bitmap.width, detail.bitmap.height),
                )
            }
        }
        if (broken) {
            CpText(
                "이 쪽을 그리지 못했습니다",
                CpTheme.type.subtitle,
                CpTheme.colors.inkMuted,
                Modifier.align(Alignment.Center),
            )
        }
        if (fitWidth) ScreenPosition(page, viewport, Modifier.matchParentSize())
    }
}

/**
 * 폭 맞춤에서 쪽 안의 자리: 오른쪽 스크롤 막대와 아래 "18쪽 · 첫 화면 (1/3)". 화면을 옮기거나 쪽이 바뀐 뒤 잠깐만
 * 보인다 — 늘 떠 있으면 쪽 아래 글을 가린다. 한 화면에 다 들어가는 쪽이면 보이지 않는다.
 */
@Composable
private fun ScreenPosition(page: Int, viewport: PageViewport, modifier: Modifier) {
    val (index, total) = viewport.screen()
    if (total <= 1) return
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(page, index) {
        visible = true
        delay(POSITION_MS)
        visible = false
    }
    if (!visible) return
    Box(modifier) {
        val h = viewport.height
        val barTop = (-viewport.top / h).coerceIn(0f, 1f)
        val barLen = (viewport.viewHeight / h).coerceIn(0f, 1f)
        val density = androidx.compose.ui.platform.LocalDensity.current
        with(density) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 3.dp)
                    .offset(y = (barTop * viewport.viewHeight).toDp())
                    .width(4.dp).height((barLen * viewport.viewHeight).toDp())
                    .clip(RoundedCornerShape(50)).background(Color(0x88000000)),
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xE6302A24)).padding(horizontal = 12.dp, vertical = 5.dp)
                .semantics { contentDescription = "쪽 안 위치" },
        ) { CpText(screenLabel(page, index, total), CpTheme.type.caption, Color.White) }
    }
}

/** "18쪽 · 첫 화면 (1/3)". 처음 · 끝은 말로, 가운데는 몇 번째인지. */
internal fun screenLabel(page: Int, index: Int, total: Int): String {
    val where = when (index) {
        1 -> "첫 화면"
        total -> "끝 화면"
        else -> "${index}번째 화면"
    }
    return "${page + 1}쪽 · $where ($index/$total)"
}

/** 쪽 안 위치가 보이는 시간. 한 번 흘끗 보기에 충분하고 읽기를 오래 가리지 않는다. */
private const val POSITION_MS = 1500L

/**
 * 두쪽보기: 펼침의 쪽들을 가운데(책등)에 붙여 나란히 놓는다. 쪽마다 화면 절반 × 전체 높이에 맞춘다. 표지처럼
 * 혼자인 쪽은 가운데에 둔다.
 *
 * 확대는 없다 — 두 쪽을 한 판으로 확대하려면 두 쪽에 걸친 구역을 그려야 한다. 작은 글자는 세로로 돌려 한 쪽
 * 보기에서 확대한다.
 */
@Composable
private fun SpreadView(
    reader: PdfReader,
    pages: List<Int>,
    viewW: Float,
    viewH: Float,
    onTap: (Offset, Float) -> Unit,
    onSwipe: (forward: Boolean) -> Unit,
    onLongPress: (Offset) -> Unit = {},
    onPlaced: (List<Placed>) -> Unit = {},
    text: PdfTextState? = null,
) {
    val half = viewW / 2f
    // 쪽 비율을 모르면 그리지 않는다(PageView 와 같다). 앞뒤 쪽은 미리 재 두므로 넘길 때는 바로 안다.
    // 펼침이 바뀌면 값을 새로 시작한다(remember 의 열쇠) — 넘기는 순간 옛 펼침(쪽 하나)의 비율로 새 펼침(쪽 둘)을
    // 그리면 칸 수가 달라 죽는다(처음 쓴 판에서 표지 → 2–3쪽으로 넘길 때 났다).
    var aspects by remember(pages) {
        mutableStateOf(pages.map { reader.book.knownAspectRatio(it) }.takeIf { it.all { a -> a != null } }?.map { it!! })
    }
    LaunchedEffect(pages) { if (aspects == null) aspects = pages.map { reader.book.pageAspectRatio(it) } }
    val fits = aspects?.map { a ->
        val w = half.coerceAtMost(viewH * a).roundToInt()
        w to (w / a).roundToInt()
    }
    var bitmaps by remember(pages, fits) {
        mutableStateOf(fits?.let { f -> pages.mapIndexed { i, p -> reader.cachedPage(p, f[i].first, f[i].second) } })
    }
    LaunchedEffect(pages, fits) {
        val f = fits ?: return@LaunchedEffect
        bitmaps = pages.mapIndexed { i, p -> reader.page(p, f[i].first, f[i].second) }
        // 다음 펼침을 미리 그려 둔다 — 넘길 때 빈 종이가 번쩍이지 않게.
        val next = (pages.maxOrNull() ?: 0) + 1
        for (p in next..next + 1) {
            if (p >= reader.book.pageCount) break
            val a = reader.book.pageAspectRatio(p)
            val w = half.coerceAtMost(viewH * a).roundToInt()
            reader.page(p, w, (w / a).roundToInt())
        }
    }
    // 쪽마다 놓인 자리. 그리기(아래 Canvas)와 같은 셈이다 — 두 쪽이면 책등이 가운데, 한 쪽이면 그 쪽이 가운데.
    val placed = fits?.let { f ->
        var x = if (f.size == 2) half - f[0].first else (viewW - f.sumOf { it.first }) / 2f
        f.mapIndexed { i, (w, h) ->
            val top = (viewH - h) / 2f
            Placed(pages[i], Rect(x, top, x + w, top + h)).also { x += w }
        }
    }
    SideEffect { placed?.let(onPlaced) }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (text != null) Modifier.selectionHandles(reader, text) else Modifier)
            .pointerInput(pages, viewW, viewH) {
                detectTapGestures(onTap = { at -> onTap(at, CORNER.toPx()) }, onLongPress = { at -> onLongPress(at) })
            }
            .pointerInput(pages, viewW, viewH) {
                val threshold = 48.dp.toPx()
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (abs(dragged) > threshold) onSwipe(dragged < 0) },
                ) { _, amount -> dragged += amount }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val f = fits ?: return@Canvas
            val shown = bitmaps ?: return@Canvas
            val total = f.sumOf { it.first }
            // 두 쪽이면 책등이 화면 가운데, 한 쪽이면 그 쪽이 가운데.
            var x = if (f.size == 2) half - f[0].first else (viewW - total) / 2f
            f.forEachIndexed { i, (w, h) ->
                val top = (viewH - h) / 2f
                shown.getOrNull(i)?.let { bitmap ->
                    drawImage(
                        bitmap.asImageBitmap(),
                        dstOffset = IntOffset(x.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(w, h),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                x += w
            }
        }
    }
}

/**
 * 목차 · 독서노트 전체 화면. EPUB 리더의 것과 같은 모양이다(탭 두 개, 지금 위치에 불). PDF 는 글자를 고를 수
 * 없어 독서노트에 책갈피만 모인다 — 칩 대신 그렇다고 한 줄로 알린다.
 */
@Composable
private fun PdfLists(
    reader: PdfReader,
    page: Int,
    notes: List<Annotation>,
    showNotes: Boolean,
    onPanel: (PdfPanel) -> Unit,
    onMemo: (Annotation) -> Unit,
    scope: CoroutineScope,
) {
    var contents by remember { mutableStateOf<List<TocEntry>?>(null) }
    var marks by remember { mutableStateOf<List<Bookmark>?>(null) }
    var filter by remember { mutableStateOf(NoteFilter.All) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        contents = runCatching { reader.outline() }.getOrDefault(emptyList())
        marks = runCatching { reader.bookmarks() }.getOrDefault(emptyList())
    }
    fun label(p: Int) = "${reader.book.pageLabel(p) ?: "${p + 1}"}쪽"
    // 책 순서(쪽, 쪽 안의 글자)로. 같은 쪽이면 책갈피가 먼저 — 쪽 머리에 꽂은 것이 그 쪽 안의 칠보다 앞에 읽힌다.
    val rows = remember(marks, contents, notes) {
        val entries = contents.orEmpty()
        fun section(p: Int) = entries.getOrNull(currentContentsIndex(entries, p))?.label ?: "앞부분"
        val list = ArrayList<Triple<Pair<Int, Int>, NoteItem, Any>>()
        marks?.forEach { mark ->
            val at = mark.locator.fixedPage
            list += Triple(
                at to -1,
                NoteItem("b${mark.id}", section(at), mark.snippet ?: label(at), null, null, "${label(at)} · ${noteWhere(null, mark.createdAtEpochMs)}"),
                mark,
            )
        }
        notes.forEach { note ->
            val at = note.start.spine
            list += Triple(
                at to note.start.charOffset,
                NoteItem("a${note.id}", section(at), note.snippet, note.color.pen, note.note, "${label(at)} · ${noteWhere(null, note.createdAtEpochMs)}"),
                note,
            )
        }
        list.sortedWith(compareBy({ it.first.first }, { it.first.second }))
    }
    val items = if (marks == null) null else rows.map { it.second }
    fun find(item: NoteItem): Any? = rows.firstOrNull { it.second.key == item.key }?.third
    CpFullScreen {
        CpHeader(title = reader.title, subtitle = "PDF", onBack = { onPanel(PdfPanel.Bar) }) {
            if (showNotes && !items.isNullOrEmpty()) {
                CpText(
                    "내보내기", CpTheme.type.label, CpTheme.colors.accent,
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, exportNotes(reader.title, null, items))
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
                    }.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        CpTabBar(
            listOf("목차", items?.let { "독서노트 ${it.size}" } ?: "독서노트"),
            if (showNotes) 1 else 0,
            { onPanel(if (it == 1) PdfPanel.Notes else PdfPanel.Contents) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showNotes) {
                CpReadingNotesList(
                    items = items,
                    filter = filter,
                    onFilter = { filter = it },
                    onOpen = { item ->
                        when (val row = find(item)) {
                            is Bookmark -> scope.go { reader.goTo(row) }
                            is Annotation -> scope.go { reader.goTo(row) }
                        }
                        onPanel(PdfPanel.None)
                    },
                    onRemove = { item ->
                        when (val row = find(item)) {
                            is Bookmark -> scope.go { reader.removeBookmark(row); marks = reader.bookmarks() }
                            is Annotation -> scope.go { reader.remove(row) }
                        }
                    },
                    onMemo = { item -> (find(item) as? Annotation)?.let(onMemo) },
                    onRecolor = { item, pen -> (find(item) as? Annotation)?.let { a -> scope.go { reader.update(a.copy(color = pen.color)) } } },
                    onShare = { item -> shareOut(context, shareText(item.text, item.memo, reader.title)) },
                    // 글자를 꺼낼 수 없는 휴대폰(안드로이드 14 이하)에서는 책갈피만 모인다 — 그때만 거르개를 숨기고 까닭을 적는다.
                    chips = reader.readsText,
                    caption = if (reader.readsText) null else "이 휴대폰(안드로이드 14 이하)에서는 PDF 글자를 고를 수 없어 책갈피만 모입니다",
                    empty = if (reader.readsText) {
                        "독서노트가 비어 있습니다. 글자를 길게 눌러 칠하거나 쪽 오른쪽 위를 눌러 책갈피를 꽂으면 여기에 모입니다"
                    } else {
                        "책갈피가 없습니다. 쪽 오른쪽 위를 누르거나 가운데를 누르고 위쪽 책갈피 단추로 꽂을 수 있습니다"
                    },
                )
            } else {
                ContentsList(contents, page, labelOf = { reader.book.pageLabel(it) ?: "${it + 1}" }) { entry ->
                    scope.go { reader.goTo(entry) }
                    onPanel(PdfPanel.None)
                }
            }
        }
    }
}

@Composable
private fun ContentsList(entries: List<TocEntry>?, page: Int, labelOf: (Int) -> String, onOpen: (TocEntry) -> Unit) {
    when {
        entries == null -> Unit
        entries.isEmpty() -> Empty("목차가 없는 파일입니다. 진행 막대로 원하는 쪽에 갈 수 있습니다")
        else -> {
            val current = currentContentsIndex(entries, page)
            val list = rememberLazyListState()
            // 지금 위치가 화면 위쪽 3분의 1 쯤 오게 연다. 맨 위에 붙이면 앞 항목이 안 보여 "어디쯤인지" 가
            // 안 읽힌다(EPUB 목차와 같다).
            LaunchedEffect(current) { if (current > 0) list.scrollToItem((current - 3).coerceAtLeast(0)) }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                itemsIndexed(entries) { i, entry ->
                    CpListRow(
                        title = entry.label,
                        onClick = { onOpen(entry) },
                        modifier = Modifier.padding(start = CpTheme.metrics.levelIndent * entry.depth),
                        // 쪽 번호는 책에 인쇄된 번호(쪽 이름표), 없으면 1부터 센 번호. 종이책 목차와 같은 숫자다.
                        value = labelOf(entry.locator.fixedPage),
                        selected = i == current,
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun Empty(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CpText(message, CpTheme.type.subtitle, CpTheme.colors.textMuted, maxLines = 3)
    }
}

private val io.github.kgcaudit.reader.document.Locator.fixedPage: Int
    get() = (this as? io.github.kgcaudit.reader.document.Locator.FixedPage)?.page ?: 0

/** 동작 하나를 띄운다. 실패는 로그로 — 흔적 없이 삼키면 "단추가 먹통" 인 원인을 기기에서 찾을 수 없다. */
private fun CoroutineScope.go(block: suspend () -> Unit) {
    launch {
        runCatching { block() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w(TAG, "pdf action failed", it) }
    }
}

/** 손을 멈춘 뒤 선명하게 다시 그리기까지. 끄는 동안 매 프레임 그리면 렌더가 줄줄이 쌓인다. */
private const val SETTLE_MS = 150L

private const val TAG = "OloPdf"
