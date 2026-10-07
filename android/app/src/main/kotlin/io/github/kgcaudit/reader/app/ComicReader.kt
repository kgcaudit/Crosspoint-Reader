package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalView
import io.github.kgcaudit.reader.ui.design.BOOKMARK_CORNER
import io.github.kgcaudit.reader.ui.design.pageAt
import io.github.kgcaudit.reader.ui.design.rememberTurnFeedback
import io.github.kgcaudit.reader.ui.design.turnSide
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import io.github.kgcaudit.reader.document.image.ImageSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.comic.ComicReading
import io.github.kgcaudit.reader.document.comic.ComicSpreads
import io.github.kgcaudit.reader.document.comic.ComicView
import io.github.kgcaudit.reader.document.comic.Webtoon
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.comic.WorkEntry
import io.github.kgcaudit.reader.pdf.PageViewport
import io.github.kgcaudit.reader.ui.design.CpBrightnessRow
import io.github.kgcaudit.reader.ui.design.CpTwoPageRows
import io.github.kgcaudit.reader.ui.design.CpReaderKind
import io.github.kgcaudit.reader.ui.design.CpAutoTurnPill
import io.github.kgcaudit.reader.ui.design.CpBrightnessOverlay
import io.github.kgcaudit.reader.ui.design.brightnessEdge
import io.github.kgcaudit.reader.ui.design.rememberAutoTurn
import io.github.kgcaudit.reader.ui.design.systemBrightness
import io.github.kgcaudit.reader.ui.design.visible
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpReaderBar
import io.github.kgcaudit.reader.ui.design.CpRibbon
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTextButton
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.CpToolButton
import io.github.kgcaudit.reader.ui.design.CpViewSettingsScreen
import io.github.kgcaudit.reader.ui.design.ReadingWindow
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import io.github.kgcaudit.reader.ui.design.TapAction
import io.github.kgcaudit.reader.ui.design.VolumeKeyPaging
import io.github.kgcaudit.reader.ui.design.actionAt
import io.github.kgcaudit.reader.document.SpreadSlot
import io.github.kgcaudit.reader.ui.design.CpPageTurn
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.rememberPageTurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 만화 뷰어 — 쪽 넘김(0.34.0, docs/COMIC_PLAN.md C1 · 확정 구상안 ①).
 *
 * 조작은 책 · PDF 와 같다(누름 구역 · 밀기 · 볼륨키 · 넘김 효과 · 밝기). 다른 것은 셋:
 * - 쪽 밖은 늘 어두운 바탕(사용자 결정 4). 그림이 떠 보이고, 흰 지면이면 쪽 가장자리가 어디인지 흐려진다.
 * - 오→왼 책은 누름 구역 · 밀기 · 넘김 효과 · 진행 막대가 모두 뒤집힌다(결정 3). 하나라도 안 뒤집으면 손이 헤맨다.
 * - 권 끝에서 앞으로 넘기면 "다 읽었습니다" 판 — 다음 권으로 이어 본다.
 */

/** 쪽 밖 바탕. 거의 검정 — 순검정이면 OLED 에서 쪽 가장자리가 번져 보인다. */
internal val COMIC_BACKDROP = Color(0xFF141311)

/** 여는 중 안내를 띄우기까지 기다리는 시간. 보통 권은 이보다 빨리 열려 안내가 보이지 않는다. */
internal const val OPENING_NOTICE_DELAY_MS = 400L

/** "1권을 여는 중…" · "3화를 여는 중…". 권 이름을 모르면(서재 밖에서 연 권) "여는 중…". */
internal fun openingNotice(label: String?): String =
    if (label.isNullOrBlank()) "여는 중…" else "$label${objectParticle(label)} 여는 중…"
internal val COMIC_INK_MUTED = Color(0xFFB9B2A8)

/** 판 하나를 밀어 넘기는 시간. 책 넘김(말림)보다 짧게 — 판이 손가락을 따라오므로 놓은 뒤에는 남은 거리만 간다. */
private const val SLIDE_MS = 220

/** 넘길 판이 없는 쪽으로 끌 때 따라오는 비율. */
private const val EDGE_RESIST = 0.3f

private enum class ComicPanel { None, Bar, View, Settings, Pages, Bookmarks }

/**
 * @param work 이 권이 든 작품. 다음 권 · 방향을 여기서 안다. 아직 모르면(서재가 묶는 중) null.
 * @param startPage 처음 볼 쪽(이어 보기).
 * @param onNext 다음 권을 연다.
 * @param onDirection 이 작품의 넘기는 방향을 바꾼다(작품마다 기억).
 */
@Composable
fun ComicReader(
    book: ComicBook,
    title: String,
    work: Work?,
    startPage: Int,
    bookmarks: List<Int>,
    prefs: ScreenPrefs,
    onPrefsChange: (ScreenPrefs) -> Unit,
    onPage: (Int) -> Unit,
    onBookmark: (Int) -> Unit,
    onDirection: (Boolean) -> Unit,
    onNext: (WorkEntry) -> Unit,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    /** 사람이 고른 보는 방식(null 은 자동). */
    view: ComicView?,
    /** 보는 방식을 바꿀 때. null 이면 보기 판에 그 줄을 두지 않는다 — 책을 만화로 볼 때(0.50.0)는 쪽 넘김뿐이다. */
    onView: ((ComicView?) -> Unit)?,
    /** 쪽 크기(머리만 읽은 것). 두 쪽 보기의 짝 · 함께 맞춤에 쓴다. 모르면 빈 목록. */
    sizes: List<ImageSize?>,
) {
    val count = book.pageCount
    // 지금 판의 첫 쪽. 두 쪽 보기를 켜고 끄면 그 쪽이 든 판으로 맞춘다.
    var page by rememberSaveable(book.unit.id) { mutableIntStateOf(startPage.coerceIn(0, (count - 1).coerceAtLeast(0))) }
    var ended by rememberSaveable(book.unit.id) { mutableStateOf(false) }
    var panel by remember { mutableStateOf(ComicPanel.None) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastCount by remember { mutableIntStateOf(0) }
    val rtl = ComicReading.rightToLeft(work)
    val next = work?.let { ComicReading.nextAfter(it, book.unit.id) }
    val latestPrefs by rememberUpdatedState(prefs)
    // 두 쪽 보기는 책 · PDF 와 같은 설정을 따른다(0.42.0): 가로에서 두 쪽 · 세로에서 두 쪽(넓은 화면만) · 두 쪽의 표지.
    // 0.41 까지는 만화만 따로 고르는 값이 있어, 모든 보기 설정의 같은 이름 줄을 바꿔도 만화는 따르지 않았다.
    // 판 목록은 화면 모양이 바뀔 때마다 다시 짠다 — 짝은 쪽 크기로 정해진다.
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val smallestWidth = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val two = prefs.twoPages(screen.width / density, screen.height / density, smallestWidth) && count > 1
    // 여백 자르기(0.49.0). 이 뷰어는 쪽 넘김만 그린다 — 웹툰은 따로 그려 이 설정을 보지 않는다(사용자 결정 3-라).
    val trim = prefs.comicTrimMargins
    val spreads = remember(two, count, sizes, prefs.pdfCoverAlone) {
        if (two) ComicSpreads.of(List(count) { sizes.getOrNull(it) }, coverAlone = prefs.pdfCoverAlone) else List(count) { listOf(it) }
    }
    val spreadIndex = ComicSpreads.indexOf(spreads, page)
    val shown = spreads.getOrElse(spreadIndex) { listOf(page) }
    val bookmarked = shown.any { it in bookmarks }

    // 자리는 판의 마지막 쪽으로 적는다 — 끝 판을 보면 다 읽음이고, 다시 열면 그 쪽이 든 판이 나온다.
    LaunchedEffect(shown) { onPage(shown.last()) }
    LaunchedEffect(panel) { onChrome(panel != ComicPanel.None) }
    // 왼쪽 끝을 밀어 밝기(0.42.0 — 책과 같다). 미는 동안의 값은 창에만 걸고, 손을 떼면 저장한다.
    var dragBrightness by remember { mutableStateOf<Float?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    ReadingWindow(prefs.copy(brightness = dragBrightness ?: prefs.brightness), activity = page, autoRunning = prefs.autoTurn != io.github.kgcaudit.reader.ui.design.AutoTurn.Off)

    fun say(message: String) {
        toast = message
        toastCount++
    }
    // 판 밀기(0.40.0, 2026-10-03 사용자 결정 7): 앞뒤 판이 지금 판의 양옆에 붙어 있고 손가락을 따라 함께 미끄러진다. 만화는
    // 두 쪽에 걸쳐 한 그림을 그리는 장면이 있다 — 종이 말림은 넘기는 동안 다음 쪽을 가려, 이어진 그림이 한 번도 나란히
    // 보이지 않았다. [slide] 는 지금 판이 밀려난 거리(px). 다음 판은 읽는 쪽에 붙는다: 왼→오는 오른쪽, 오→왼은 왼쪽.
    val scope = rememberCoroutineScope()
    val slide = remember(book.unit.id) { Animatable(0f) }
    val feedback = rememberTurnFeedback()
    val hostView = LocalView.current
    val nextSide = if (rtl) -1f else 1f
    val tablet = io.github.kgcaudit.reader.ui.design.cpTablet()
    /** 남는 높이를 둘 곳: 휴대폰은 아래(그림을 카메라 구멍 바로 아래에), 태블릿은 위(그림을 아래에). */
    val bias = if (tablet) 1f else 0f
    val turns = rememberPageTurnState()

    /**
     * 판의 자리(0.46.0, 종이책 규칙): 두 쪽 보기에서 혼자인 표지는 뒤 쪽 자리, 혼자 남은 쪽은 앞 쪽 자리, 펼침면 그림은
     * 가운데. 한 쪽 보기는 늘 가운데.
     */
    fun slotOf(index: Int): SpreadSlot {
        val s = spreads.getOrNull(index) ?: return SpreadSlot.Center
        if (!two) return SpreadSlot.Center
        val single = s.singleOrNull()?.let { pageAspect(book, sizes, it, trim) }
        return SpreadSlot.of(s, first = s.firstOrNull() == 0, wide = single != null && single > 1f, coverAlone = prefs.pdfCoverAlone)
    }

    /**
     * 판이 차지하는 비. 한쪽 자리에 선 혼자인 쪽은 빈 짝까지 두 쪽 폭으로 친다 — 그래야 확대 · 위치 · 붙여 넘기기가 두 쪽
     * 판과 같은 틀에서 셈하고, 표지에서 다음 펼침으로 넘어갈 때 쪽이 옆으로 튀지 않는다.
     */
    fun aspectOf(index: Int): Float? {
        val a = spreads.getOrNull(index)?.let { spreadAspect(book, sizes, it, trim) } ?: return null
        return if (slotOf(index).let { it == SpreadSlot.Earlier || it == SpreadSlot.Later }) a * 2 else a
    }

    /**
     * 판이 화면에 그려지는 폭: 화면에 맞춘 그림의 폭(화면보다 좁을 수 있다). 비를 아직 모르면 화면 폭.
     * 붙여 넘기기(0.45.0, 사용자 결정 — Explorer 처럼)는 앞뒤 판을 화면 폭이 아니라 이 폭만큼 떨어뜨려 놓는다. 화면 폭으로
     * 놓으면 그림이 화면보다 좁을 때(가로 화면 · 두 쪽) 넘기는 동안 쪽 사이에 검은 틈이 보였다.
     */
    fun spanOf(index: Int): Float {
        val w = screen.width.toFloat()
        val h = screen.height.toFloat()
        val aspect = aspectOf(index)
        return if (aspect == null || w <= 0f || h <= 0f) w.coerceAtLeast(1f) else minOf(w, h * aspect)
    }
    /** 지금 판의 가운데에서 [index] 판의 가운데까지 — 두 그림의 가장자리가 맞닿는 거리. */
    fun stepTo(index: Int): Float = (spanOf(spreadIndex) + spanOf(index)) / 2f

    /**
     * 읽는 순서로 한 판(한 쪽 또는 두 쪽) 밀어 넘긴다. 끌던 중이면 그 자리에서 이어 민다. 마지막 판에서 앞으로 가면 권 끝 판,
     * 첫 판에서 뒤로 가면 제자리로 돌아온다.
     */
    fun advance(forward: Boolean, quiet: Boolean = false) {
        val target = spreadIndex + if (forward) 1 else -1
        if (target !in spreads.indices) {
            scope.launch { slide.animateTo(0f, tween(SLIDE_MS)) }
            if (forward) ended = true
            return
        }
        if (latestPrefs.pageTurn != PageTurn.Slide) {
            // 말림 · 덮기 · 서서히 · 없음은 책과 같은 넘김 효과(0.45.0)가 그린다. 소리 · 진동도 거기서 — 자동 넘김은 조용히.
            turns.request(quiet)
            page = spreads[target].first()
            return
        }
        // 넘기는 중에 또 누르면 받지 않는다 — 받으면 아직 바뀌지 않은 판 번호로 셈해 같은 판으로 두 번 간다.
        if (slide.isRunning) return
        val step = stepTo(target)
        scope.launch {
            // 다음 판이 [nextSide] 쪽에서 들어오도록 지금 판을 반대로 민다 — 다음 판의 가장자리가 지금 판에 붙은 거리만큼.
            slide.animateTo(if (forward) -nextSide * step else nextSide * step, tween(SLIDE_MS))
            page = spreads[target].first()
            slide.snapTo(0f)
            // 밀기도 넘어가는 판의 쪽에서 소리를 낸다(0.48.0) — 오→왼 만화는 판이 왼쪽에서 넘어간다.
            if (!quiet) feedback.turned(latestPrefs.turnSound, latestPrefs.turnHaptic, hostView, turnSide(forward, rtl))
        }
    }
    fun toggleBookmark() {
        say(if (bookmarked) "책갈피를 뺐습니다" else "책갈피를 꽂았습니다")
        // 두 쪽 중 이미 꽂힌 쪽이 있으면 그것을 뺀다. 없으면 판의 첫 쪽에 꽂는다.
        onBookmark(shown.firstOrNull { it in bookmarks } ?: shown.first())
    }
    // 구석 누름의 처리기는 판이 바뀔 때만 새로 만든다 — 같은 판에서 책갈피를 꽂았다 빼면 처음 값에 묶여 "꽂았습니다" 라고 했다.
    val toggleBookmarkNow by rememberUpdatedState<() -> Unit> { toggleBookmark() }
    VolumeKeyPaging(enabled = prefs.volumeKeys && panel == ComicPanel.None && !ended) { forward -> advance(forward) }

    BackHandler {
        when {
            ended -> ended = false
            panel == ComicPanel.None -> onClose()
            panel == ComicPanel.Settings -> panel = ComicPanel.View
            panel == ComicPanel.Bar -> panel = ComicPanel.None
            else -> panel = ComicPanel.Bar
        }
    }

    Box(
        Modifier.fillMaxSize().background(COMIC_BACKDROP).brightnessEdge(
            enabled = prefs.brightnessGesture && panel == ComicPanel.None && !ended,
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
        Column(Modifier.fillMaxSize()) {
            // 태블릿(폴더블 안쪽 화면)은 상태 줄 높이만큼 위를 비우고 그림을 아래에 붙인다(0.45.1, 사용자 결정). 안쪽 화면의
            // 카메라 구멍은 잘림 영역(cutout)으로 알려지지 않는 기기가 있어, 구멍 자리를 비워 두려면 상태 줄 높이를 함께 쓴다.
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth()
                    .windowInsetsPadding(if (tablet) tabletTopInsets() else WindowInsets.displayCutout)
                    .onSizeChanged { screen = it },
            ) {
                val viewW = constraints.maxWidth.toFloat()
                val viewH = constraints.maxHeight.toFloat()
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                // 지금 판의 확대 · 위치. 판이 바뀌면 다시 전체가 보인다. 두 쪽은 한 그림처럼 함께 맞추고 함께 확대한다.
                // 두 쪽 여부 · 표지 설정도 열쇠다 — 혼자인 쪽의 비(빈 짝 포함)가 그것으로 바뀐다. 빠뜨리면 화면 크기를 알기 전
                // (한 쪽으로 판정된 때)의 비로 굳어 표지가 절반 폭으로 그려졌다(0.46.0 개발 중 시험이 찾음).
                var aspect by remember(shown, two, prefs.pdfCoverAlone, trim) { mutableStateOf(aspectOf(spreadIndex)) }
                var viewport by remember(shown, w, h, aspect) { mutableStateOf(aspect?.let { PageViewport.fit(viewW, viewH, it, verticalBias = bias) }) }
                // 끌기 몸짓(판이 바뀌어도 이어지는 쪽)이 읽고 쓰는 최신 값.
                val viewportNow = rememberUpdatedState(viewport)
                val setViewport by rememberUpdatedState<(PageViewport?) -> Unit>({ viewport = it })
                val indexNow by rememberUpdatedState(spreadIndex)
                val spreadsNow by rememberUpdatedState(spreads)
                val advanceNow by rememberUpdatedState<(Boolean) -> Unit>({ advance(it) })
                // 지금 판과 앞뒤 판의 그림은 그림 저장소 한도와 상관없이 붙잡아 둔다(0.45.1). 태블릿 두 쪽에서는 쪽 그림이 커서
                // 저장소가 넘김 도중 지금 쪽을 내보냈고, 넘김이 끝나 화면을 다시 짜는 한 장면 동안 그 쪽이 검게 비었다(깜박임).
                androidx.compose.runtime.SideEffect {
                    book.pin((spreadIndex - 1..spreadIndex + 1).flatMap { spreads.getOrNull(it).orEmpty() }.toSet())
                }
                LaunchedEffect(shown, w, h, two, prefs.pdfCoverAlone, trim) {
                    for (p in shown) book.page(p, w, h, trim)
                    aspect = aspectOf(spreadIndex)
                    // 앞뒤 판을 미리 풀어 둔다 — 넘길 때 빈 화면이 번쩍이지 않게.
                    for (near in listOf(spreadIndex + 1, spreadIndex - 1)) spreads.getOrNull(near)?.forEach { book.page(it, w, h, trim) }
                }
                if (w > 0 && h > 0 && count > 0 && prefs.pageTurn != PageTurn.Slide) {
                    // 책과 같은 넘김 효과(0.45.0, 2026-10-04 사용자 결정). 오→왼 만화는 효과를 좌우로 뒤집는다 — 말림이 왼쪽 아래
                    // 모서리에서 시작해야 손가락이 미는 쪽과 맞는다. 쪽 밖 바탕까지 함께 그려 둔다: 넘어가는 종이의 빈 곳이 비치면
                    // 아래 쪽 그림이 말린 종이 위로 새어 보인다.
                    CpPageTurn(
                        spreadIndex,
                        prefs.pageTurn,
                        forward = { from, to -> to > from },
                        turns = turns,
                        spread = two,
                        sound = prefs.turnSound,
                        haptic = prefs.turnHaptic,
                        mirrored = rtl,
                    ) { index ->
                        Box(Modifier.fillMaxSize().background(COMIC_BACKDROP)) {
                            spreads.getOrNull(index)?.let { ComicPageImage(book, sizes, it, w, h, if (index == spreadIndex) viewport else null, rtl, bias, slotOf(index), trim) }
                        }
                    }
                } else if (w > 0 && h > 0 && count > 0) {
                    // 밀기: 앞 판 · 지금 판 · 다음 판을 그림 가장자리끼리 붙여 둔다. 옮기는 것은 그리기 단계의 이동뿐이라 끄는 동안 다시
                    // 짜지 않는다. 쉬는 동안(밀지 않을 때) 이웃 판은 숨긴다 — 그림이 화면보다 좁으면 옆 여백에 이웃 판 끝이 비친다.
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        for (k in -1..1) {
                            val spread = spreads.getOrNull(spreadIndex + k) ?: continue
                            val offset = if (k == 0) 0f else k * nextSide * stepTo(spreadIndex + k)
                            key(spreadIndex + k) {
                                Box(Modifier.fillMaxSize().graphicsLayer { translationX = slide.value + offset; alpha = if (k == 0 || slide.value != 0f) 1f else 0f }) {
                                    ComicPageImage(book, sizes, spread, w, h, if (k == 0) viewport else null, rtl, bias, slotOf(spreadIndex + k), trim)
                                }
                            }
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().clipToBounds()
                        .semantics { contentDescription = "만화 ${pagesLabel(shown)}쪽" }
                        .pointerInput(shown, w, h, rtl) {
                            detectTapGestures(
                                // 지금 값을 거쳐 읽고 쓴다 — 쪽 비율을 나중에 알게 되면 확대 상태가 새로 만들어지는데, 이 처리기는 그대로라
                                // 버려진 옛 상태를 바꿔 두 번 눌러도 커지지 않았다.
                                onDoubleTap = { at -> setViewport(viewportNow.value?.toggleZoom(at.x, at.y)) },
                                onTap = { at ->
                                    if (panel != ComicPanel.None) { panel = ComicPanel.None; return@detectTapGestures }
                                    when (latestPrefs.touch.actionAt(at.x, at.y, viewW, BOOKMARK_CORNER.toPx())) {
                                        // 화면의 오른쪽 = 왼→오 책의 다음. 오→왼이면 뒤집는다.
                                        TapAction.Next -> advance(ComicReading.forward(screenNext = true, rightToLeft = rtl))
                                        TapAction.Previous -> advance(ComicReading.forward(screenNext = false, rightToLeft = rtl))
                                        TapAction.Menu -> panel = ComicPanel.Bar
                                        TapAction.Bookmark -> toggleBookmarkNow()
                                    }
                                },
                            )
                        }
                        // 끌기는 판이 바뀌어도 이어진다(말림 · 덮기는 끌기 시작에 판을 넘긴다) — 열쇠에 지금 판을 넣으면 넘기는 순간
                        // 몸짓이 끊겨 종이가 손가락을 놓친다. 그래서 지금 판 · 확대 상태는 최신 값을 읽는다.
                        .pointerInput(book.unit.id, w, h, rtl) {
                            val threshold = 48.dp.toPx()
                            val start = 12.dp.toPx()
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var moving = false
                                var pinched = false
                                var travel = Offset.Zero
                                var swipe = 0f
                                val origin = indexNow
                                val fx = latestPrefs.pageTurn
                                val follows = fx == PageTurn.Curl || fx == PageTurn.Cover
                                // 말림 · 덮기에서 끌기가 정한 방향(앞으로면 true). 정해지면 곧바로 넘긴다 — 손가락을 따라 말리려면 아래에
                                // 다음 판이 그려져 있어야 한다. 덜 끌고 놓으면 앞 판으로 되돌린다.
                                var committed: Boolean? = null
                                do {
                                    val event = awaitPointerEvent()
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val fingers = event.changes.count { it.pressed }
                                    if (!moving) {
                                        travel += pan
                                        moving = fingers > 1 || travel.getDistance() > viewConfiguration.touchSlop
                                    }
                                    if (moving) {
                                        if (fingers > 1) pinched = true
                                        val vp = viewportNow.value
                                        if (vp != null && zoom != 1f && committed == null) {
                                            val c = event.calculateCentroid()
                                            setViewport(vp.zoom(zoom, c.x, c.y))
                                        }
                                        // 확대한 동안 끌기는 쪽 안을 움직인다. 넘김으로 읽으면 확대한 곳을 보려고 끌 때마다 넘어간다.
                                        if (committed == null && viewportNow.value?.isZoomed == true) {
                                            setViewport(viewportNow.value?.pan(pan.x, pan.y))
                                        } else if (!pinched && fx == PageTurn.Slide && !slide.isRunning) {
                                            swipe += pan.x
                                            // 그쪽에 판이 없으면(첫 판에서 앞으로 · 마지막 판에서 뒤로) 덜 따라온다 — 끝에 닿았다는 느낌.
                                            val towardNext = (swipe < 0) != rtl
                                            val open = if (towardNext) origin < spreadsNow.size - 1 else origin > 0
                                            val follow = if (open) swipe else swipe * EDGE_RESIST
                                            scope.launch { slide.snapTo(follow) }
                                        } else if (!pinched && fx != PageTurn.Slide) {
                                            swipe += pan.x
                                            if (follows && committed == null && abs(swipe) > start) {
                                                val forward = ComicReading.forward(screenNext = swipe < 0, rightToLeft = rtl)
                                                val target = origin + if (forward) 1 else -1
                                                // 그쪽에 판이 없으면(권 끝) 넘기지 않는다 — 놓을 때 아래에서 권 끝 판을 띄운다.
                                                if (target in spreadsNow.indices) {
                                                    committed = forward
                                                    turns.dragStart()
                                                    page = spreadsNow[target].first()
                                                }
                                            }
                                            if (committed != null) turns.drag(abs(swipe) / size.width)
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                val way = committed
                                if (way != null) {
                                    // 화면 폭의 4분의 1 또는 48dp 넘게 끌었으면 넘기고, 아니면 제자리로 말려 돌아간다(책과 같다).
                                    val far = abs(swipe) > maxOf(threshold, size.width * 0.25f)
                                    turns.dragEnd(far) { page = spreadsNow[origin].first() }
                                } else if (moving && !pinched && viewportNow.value?.isZoomed != true && abs(swipe) > threshold) {
                                    // 왼쪽으로 밀면 화면의 "다음"(왼→오 책). 오→왼이면 오른쪽으로 밀어야 다음.
                                    advanceNow(ComicReading.forward(screenNext = swipe < 0, rightToLeft = rtl))
                                } else if (slide.value != 0f && !slide.isRunning) {
                                    // 덜 밀었다 — 제자리로.
                                    scope.launch { slide.animateTo(0f, tween(SLIDE_MS)) }
                                }
                            }
                        },
                )
            }
        }
        if (bookmarked) CpRibbon(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(end = 20.dp))
        // 자동 넘김(0.42.0 — 책 · PDF 와 같다). 메뉴가 열렸거나 권 끝 판이 떠 있으면 쉰다.
        val autoSuspended = panel != ComicPanel.None || ended
        val autoTurn = rememberAutoTurn(prefs.autoTurn, page, autoSuspended) { advance(true, quiet = true) }
        if (autoTurn.visible(prefs.autoTurn, autoSuspended)) {
            CpAutoTurnPill(autoTurn, Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp))
        }
        CpBrightnessOverlay(dragBrightness, Modifier.align(Alignment.CenterStart))
        CpToast(toast, onDone = { toast = null }, Modifier.align(Alignment.BottomCenter), key = toastCount)
    }

    when (panel) {
        ComicPanel.None -> Unit
        ComicPanel.Bar, ComicPanel.View -> CpReaderBar(
            title = title,
            // 오→왼이면 방향을 붙인다 — 0.44 까지는 아래 정보 줄이 "← 오→왼" 을 늘 보였는데, 줄을 없애면서(0.45.0) 여기로 옮겼다.
            subtitle = "${pagesLabel(shown)} / ${count}쪽" + if (rtl) " · 오→왼" else "",
            bookmarked = bookmarked,
            onBookmark = ::toggleBookmark,
            onBack = onClose,
            onDismiss = { panel = ComicPanel.None },
            progress = if (count > 1) page / (count - 1f) else 1f,
            progressLabel = { "${pageAt(it, count) + 1}쪽" },
            onSeek = { target -> page = pageAt(target, count) },
            // 오→왼: 위 막대도 아래 줄처럼 오른쪽에서 차오른다(2026-10-03 사용자 결정 9). 0.40 까지는 위는 왼쪽, 아래는
            // 오른쪽에서 차올라 한 화면에 두 막대가 서로 반대로 갔다.
            progressRightToLeft = rtl,
            above = {
                if (panel == ComicPanel.View) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        onView?.let { ViewChoice(view, it) }
                        CpChoice("넘기는 방향", listOf("왼→오", "오→왼"), if (rtl) 1 else 0, { onDirection(it == 1) })
                        // 모든 만화에 한 설정(사용자 결정 3-나) — 작품마다 고르게 하면 스캔본마다 한 번씩 켜야 한다.
                        // 다른 켬 · 끔 줄(두 쪽 보기)과 같은 차례로 "켬" 이 앞이다 — 이 줄만 뒤집혀 있으면 같은 자리를 누른 손이 반대로 간다.
                        CpChoice("여백 자르기", listOf("켬", "끔"), if (prefs.comicTrimMargins) 0 else 1, { onPrefsChange(prefs.copy(comicTrimMargins = it == 0)) })
                        // 책 · PDF 와 같은 두 줄(0.42.0).
                        CpTwoPageRows(prefs, onPrefsChange)
                        CpBrightnessRow(prefs.brightness, { onPrefsChange(prefs.copy(brightness = it)) })
                        CpLinkRow("모든 보기 설정", "", { panel = ComicPanel.Settings })
                    }
                    Spacer(Modifier.height(4.dp))
                }
            },
        ) {
            CpToolButton(CpIcons.Grid, "쪽 목록", { panel = ComicPanel.Pages })
            CpToolButton(CpIcons.Bookmark, "책갈피", { panel = ComicPanel.Bookmarks })
            CpToolButton(CpIcons.View, "보기", { panel = if (panel == ComicPanel.View) ComicPanel.Bar else ComicPanel.View }, selected = panel == ComicPanel.View)
        }
        ComicPanel.Settings -> CpViewSettingsScreen(prefs, onPrefsChange, onBack = { panel = ComicPanel.View }, highlights = false, reader = CpReaderKind.Comic)
        ComicPanel.Pages -> PageGrid(book, title, page, onBack = { panel = ComicPanel.Bar }) { panel = ComicPanel.None; page = it }
        ComicPanel.Bookmarks -> BookmarkList(book, bookmarks, onBack = { panel = ComicPanel.Bar }) { panel = ComicPanel.None; page = it }
    }

    if (ended) {
        val endLabel = work?.let { ComicReading.entryOf(it, book.unit.id)?.label } ?: title
        VolumeEnd(
            entryLabel = endLabel,
            workTitle = work?.title,
            unit = endUnit(endLabel, work),
            next = next,
            onNext = { n -> ended = false; onNext(n) },
            onLibrary = onClose,
        )
    }
}

/**
 * 목적격 조사: 받침이 있으면 "을", 없으면 "를". 숫자로 끝나면 읽는 소리로 — 일 · 삼 · 육 · 칠 · 팔 · 영(0)은 받침이 있다.
 * 줄 이름은 "1권" · "48화" · "외전" 처럼 대개 한글로 끝나지만, 이름 그대로인 줄("Extra 2")도 있다. 영문 뒤에서는 "을".
 *
 * 받침 판정이 두 벌이던 때(0.49.0 까지)는 여는 중 안내만 숫자를 읽어, 같은 줄이 "Extra 2를 여는 중…" 다음에
 * "Extra 2을 다 읽었습니다" 가 됐다.
 */
internal fun objectParticle(word: String): String {
    val last = word.trimEnd().lastOrNull() ?: return "을"
    val batchim = when {
        last in '가'..'힣' -> (last - '가') % 28 != 0
        last.isDigit() -> last in "013678"
        else -> true
    }
    return if (batchim) "을" else "를"
}

/** 보기 판의 "보는 방식" 줄: 자동 · 쪽 넘김 · 웹툰. 고른 값은 작품마다 기억한다(결정 2). */
@Composable
internal fun ViewChoice(view: ComicView?, onView: (ComicView?) -> Unit) {
    val options = listOf(null, ComicView.PAGE, ComicView.WEBTOON)
    CpChoice("보는 방식", listOf("자동", "쪽 넘김", "웹툰"), options.indexOf(view), { onView(options[it]) })
}

/**
 * 한 판(쪽 하나 · 둘)의 그림. [viewport] 가 없으면(옆에 붙은 판 · 크기를 아직 모름) 화면에 맞춰 가운데. [rtl] 이면 두 쪽의
 * 앞 쪽을 오른쪽에 둔다.
 */
@Composable
private fun ComicPageImage(book: ComicBook, sizes: List<ImageSize?>, pages: List<Int>, w: Int, h: Int, viewport: PageViewport?, rtl: Boolean, bias: Float, slot: SpreadSlot, trim: Boolean) {
    val bitmaps = remember(pages, w, h, trim) { androidx.compose.runtime.mutableStateListOf(*pages.map { book.cached(it, w, h, trim) }.toTypedArray()) }
    var broken by remember(pages, w, h, trim) { mutableStateOf(pages.any { book.isBroken(it) }) }
    LaunchedEffect(pages, w, h, trim) {
        pages.forEachIndexed { k, p ->
            if (bitmaps[k] == null && !book.isBroken(p)) bitmaps[k] = book.page(p, w, h, trim)
        }
        broken = pages.any { book.isBroken(it) }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // 각 쪽의 비. 아직 모르면 푼 그림에서, 그것도 없으면 그리지 않는다(흔들리지 않게).
            // 여백을 자르면 비는 자른 그림의 것이다 — 머리의 비로 그리면 잘린 그림이 옆으로 늘어난다.
            val known = pages.mapIndexed { k, p ->
                if (trim) bitmaps[k]?.let { it.width.toFloat() / it.height }
                else sizes.getOrNull(p)?.let { it.width.toFloat() / it.height } ?: bitmaps[k]?.let { it.width.toFloat() / it.height }
            }
            // 깨진 쪽은 그림이 영영 오지 않는다 — 머리의 비, 그것도 없으면 짝의 비로 자리만 잡는다. 기다리면 판 전체를 그리지
            // 않아, 두 쪽 중 하나가 깨지면 성한 쪽까지 검게 비었다(여백 자르기를 켜면 늘 그랬다 — 자른 비는 푼 그림에서만 안다).
            val aspects = pages.mapIndexed { k, p ->
                known[k] ?: if (broken && book.isBroken(p)) sizes.getOrNull(p)?.let { it.width.toFloat() / it.height } ?: known.firstNotNullOfOrNull { it } else null
            }
            if (aspects.any { it == null }) return@Canvas
            // 한쪽 자리에 선 혼자인 쪽은 빈 짝까지 두 쪽 폭으로 맞추고 제 반에 그린다(0.46.0).
            val side = slot == SpreadSlot.Earlier || slot == SpreadSlot.Later
            val total = aspects.sumOf { it!!.toDouble() }.toFloat() * if (side) 2 else 1
            // 받은 확대 · 위치의 비가 판과 다르면(자른 그림이 막 풀려 판의 비가 바뀐 한 장면) 버리고 새로 맞춘다 — 그대로 쓰면
            // 그 한 장면 동안 쪽이 늘어나 보인다.
            val vp = viewport?.takeIf { kotlin.math.abs(it.pageAspect - total) <= total * 0.01f }
                ?: PageViewport.fit(size.width, size.height, total, verticalBias = bias)
            // 화면에 보이는 차례로 늘어놓는다: 오→왼 책은 앞 쪽이 오른쪽(구상안 ⑥).
            val order = if (rtl) pages.indices.reversed() else pages.indices
            var x = vp.left + if (side && !slot.onLeft(rtl)) vp.width / 2 else 0f
            for (k in order) {
                val pw = vp.width * aspects[k]!! / total
                bitmaps[k]?.let { b ->
                    drawImage(
                        b.asImageBitmap(),
                        dstOffset = IntOffset(x.roundToInt(), vp.top.roundToInt()),
                        dstSize = IntSize(pw.roundToInt(), vp.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                x += pw
            }
        }
        if (broken) CpText("이 쪽을 그리지 못했습니다", CpTheme.type.subtitle, COMIC_INK_MUTED, Modifier.align(Alignment.Center))
    }
}

/** 태블릿의 그림 자리: 잘림 영역 + 숨긴 상태 줄 높이(위). 둘 중 큰 쪽만큼 위를 비운다. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun tabletTopInsets(): WindowInsets =
    WindowInsets.displayCutout.union(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top))

/** "12" · 두 쪽이면 "2–3". */
internal fun pagesLabel(shown: List<Int>): String =
    if (shown.size >= 2) "${shown.first() + 1}–${shown.last() + 1}" else "${(shown.firstOrNull() ?: 0) + 1}"

/**
 * 판의 가로/세로 비: 쪽들을 같은 높이로 나란히 놓은 폭의 합. 크기를 모르는 쪽은 푼 그림의 비, 그것도 모르면 null(아직 기다림).
 */
internal fun spreadAspect(book: ComicBook, sizes: List<ImageSize?>, pages: List<Int>, trim: Boolean = false): Float? {
    var sum = 0f
    for (p in pages) sum += pageAspect(book, sizes, p, trim) ?: return null
    return sum
}

/** 쪽의 비. 여백을 자르면 자른 그림의 비가 먼저고, 아직 풀지 않았으면 머리의 비로 어림한다(풀리면 바로잡힌다). */
private fun pageAspect(book: ComicBook, sizes: List<ImageSize?>, page: Int, trim: Boolean = false): Float? =
    (if (trim) book.trimmedAspect(page) else null) ?: sizes.getOrNull(page)?.let { it.width.toFloat() / it.height } ?: book.knownAspect(page)

/** 쪽 목록(구상안 ③): 쪽 그림 격자, 지금 쪽 강조. 목차가 없는 만화의 "목차" 다. */
@Composable
private fun PageGrid(book: ComicBook, title: String, current: Int, onBack: () -> Unit, onPick: (Int) -> Unit) {
    val c = CpTheme.colors
    CpFullScreen {
        CpHeader("쪽 목록", subtitle = "$title · ${book.pageCount}쪽", onBack = onBack)
        val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
        LazyVerticalGrid(
            GridCells.Adaptive(96.dp),
            Modifier.fillMaxSize(),
            state = grid,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = CpTheme.metrics.gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(book.pageCount) { i ->
                Column(
                    Modifier.clickable { onPick(i) }.semantics(mergeDescendants = true) { contentDescription = "${i + 1}쪽으로" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val shape = RoundedCornerShape(CpTheme.metrics.cornerChip)
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.7f).clip(shape).background(COMIC_BACKDROP)
                            .border(if (i == current) 3.dp else 1.dp, if (i == current) c.accent else c.outline, shape),
                    ) { Thumbnail(book, i) }
                    CpText("${i + 1}쪽", CpTheme.type.caption, if (i == current) c.accentText else c.textMuted, Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** 작은 쪽 그림. 쪽 목록 · 책갈피 · 권 끝이 쓴다. */
@Composable
private fun Thumbnail(book: ComicBook, index: Int) {
    var bitmap by remember(index) { mutableStateOf(book.cached(index, THUMB_W, THUMB_H)) }
    LaunchedEffect(index) { if (bitmap == null) bitmap = book.page(index, THUMB_W, THUMB_H) }
    Canvas(Modifier.fillMaxSize()) {
        val b = bitmap ?: return@Canvas
        val vp = PageViewport.fit(size.width, size.height, b.width.toFloat() / b.height)
        drawImage(b.asImageBitmap(), dstOffset = IntOffset(vp.left.roundToInt(), vp.top.roundToInt()), dstSize = IntSize(vp.width.roundToInt(), vp.height.roundToInt()))
    }
}

private const val THUMB_W = 240
private const val THUMB_H = 340

@Composable
internal fun BookmarkList(book: ComicBook, pages: List<Int>, onBack: () -> Unit, onPick: (Int) -> Unit) {
    CpFullScreen {
        CpHeader("책갈피", subtitle = "${pages.size}개", onBack = onBack)
        if (pages.isEmpty()) {
            CpText(
                "책갈피가 없습니다. 쪽 오른쪽 위를 누르거나 가운데를 누르고 위쪽 책갈피 단추로 꽂을 수 있습니다.",
                CpTheme.type.subtitle, CpTheme.colors.textMuted,
                Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 24.dp), maxLines = 3,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(pages) { p ->
                    CpListRow("${p + 1}쪽", { onPick(p) }, leading = {
                        Box(Modifier.width(40.dp).aspectRatio(0.7f).background(COMIC_BACKDROP)) { Thumbnail(book, p) }
                    })
                }
            }
        }
    }
}

/** 권 끝(구상안 ④): "1권을 다 읽었습니다" + 다음 권 표지 · 이어서 보기 · 서재로. 마지막 권이면 서재로만. */
@Composable
private fun VolumeEnd(entryLabel: String, workTitle: String?, unit: String, next: WorkEntry?, onNext: (WorkEntry) -> Unit, onLibrary: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(COMIC_BACKDROP).clickable(indication = null, interactionSource = null) {}
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { EndCard(entryLabel, workTitle, unit, next, onNext, onLibrary) }
}

/**
 * 끝 판 "마지막 권/화입니다" 의 세는 말. 마지막 줄 자신의 이름이 먼저다 — 끝 글자가 "화" 면 화, "권" 이면 권. 이름으로 알 수
 * 없을 때("외전" · "Extra 2")만 작품의 세는 말([unitWord])로 물러난다. 작품의 말만 쓰면 권과 화가 섞인 작품(단행본 뒤에
 * 연재분이 이어짐)은 웹툰이 아니라 "권" 이라, 마지막 줄이 "3화" 인데 "마지막 권입니다" 가 떴다.
 */
internal fun endUnit(entryLabel: String, work: Work?): String = when {
    entryLabel.trimEnd().endsWith("화") -> "화"
    entryLabel.trimEnd().endsWith("권") -> "권"
    else -> unitWord(work)
}

/**
 * 권 · 화 끝 판의 내용. 쪽 넘김은 화면을 덮고, 웹툰은 목록 맨 끝에 이어 붙인다. [unit] 은 "마지막 권/화입니다" 의 세는 말
 * ([endUnit]).
 */
@Composable
internal fun EndCard(entryLabel: String, workTitle: String?, unit: String, next: WorkEntry?, onNext: (WorkEntry) -> Unit, onLibrary: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CpText("${entryLabel}${objectParticle(entryLabel)} 다 읽었습니다", CpTheme.type.title, Color.White)
        Spacer(Modifier.height(24.dp))
        if (next != null) {
            ComicCover(next.unit, workTitle ?: next.label, next.label, Modifier.width(150.dp))
            Spacer(Modifier.height(16.dp))
            CpText("다음: ${listOfNotNull(workTitle, next.label).joinToString(" ")}", CpTheme.type.subtitle, COMIC_INK_MUTED)
            Spacer(Modifier.height(20.dp))
            CpButton("이어서 읽기", { onNext(next) })
        } else {
            CpText("마지막 ${unit}입니다", CpTheme.type.subtitle, COMIC_INK_MUTED)
        }
        Spacer(Modifier.height(12.dp))
        CpTextButton("서재로", onLibrary, Modifier.heightIn(min = CpTheme.metrics.touchTarget), color = COMIC_INK_MUTED)
    }
}

/**
 * 서재에서 만화 한 권을 연다: 단위를 찾아 열고, 보는 방식(쪽 넘김 · 웹툰)을 정하고, 작품 · 책갈피를 지켜보며, 자리를
 * 적는다. 열지 못하면 [onFail].
 */
@Composable
fun ComicHost(
    unitId: String,
    /** 이 쪽부터(합본 안의 권을 눌렀다). null 이면 읽던 자리. */
    startAt: Int?,
    prefs: ScreenPrefs,
    onPrefsChange: (ScreenPrefs) -> Unit,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    onFail: (String) -> Unit,
    /** 서재의 "보던 장면에서 표지 고르기" 로 열었다(0.47.0, ⑪). 고르면 작품 표지로 두고 닫는다. */
    pickCover: Boolean = false,
) {
    val container = LocalContext.current.container
    val data = container.data
    val scope = rememberCoroutineScope()
    // 웹툰 이어 보기(0.47.0): 화면이 이어 붙인 다음 화로 넘어가면 그 화가 [unitId] 가 된다(아래 줄 · 책갈피 · 서재의 이어
    // 보기가 따른다). 그때 처음 연 화를 다시 열면 이어 붙인 사슬이 끊기고 화면이 깜박인다 — 사슬 안의 화로 바뀐 것이면
    // 처음 연 화([loadKey])를 그대로 둔다.
    var chain by remember { mutableStateOf(emptySet<String>()) }
    var loadKey by remember { mutableStateOf(unitId) }
    val key = if (unitId in chain) loadKey else unitId
    // 사슬 밖으로 건너뛸 때 늘린다. 내려놓은 첫 화로 돌아가면 열쇠(처음 연 화)가 그대로라 아무 일도 없었다 — 이것으로 새로 연다.
    var reload by remember { mutableIntStateOf(0) }
    var book by remember(key, reload) { mutableStateOf<ComicBook?>(null) }
    var sizes by remember(key, reload) { mutableStateOf<List<io.github.kgcaudit.reader.document.image.ImageSize?>>(emptyList()) }
    // 지금 자리(그림 번호 · 그 안의 비율). 보는 방식을 바꿔도 같은 그림에서 이어진다.
    var position by remember(key, reload) { mutableStateOf(0 to 0f) }
    LaunchedEffect(key, reload) {
        if (key != loadKey || chain.isEmpty()) {
            loadKey = key
            chain = setOf(key)
        }
        // 연 책. 크기를 읽다 실패하거나 여는 사이 화면을 떠나 취소되면 여기서 닫는다 — 두면 압축 · 파일이 열린 채 남았다.
        var made: ComicBook? = null
        val opened = runCatching {
            withContext(Dispatchers.IO) {
                val unit = data.comics.unit(unitId) ?: throw java.io.FileNotFoundException(unitId)
                val p = data.comics.progressOf(unitId)
                val pages = data.openComic(unit)
                val comic = ComicBook(unit, pages, regions = container.comicRegions, stripDelayMs = { container.comicStripDelayMs }).also { made = it }
                // 크기는 머리만 읽는다 — 웹툰 판별 · 기둥 배치에 쓴다.
                sizes = comic.sizes()
                position = when {
                    startAt != null -> startAt to 0f
                    // 다 읽고 끝에 멈춘 권을 다시 열면 처음부터 — 끝에서 열면 곧바로 "다 읽었습니다" 판이 뜬다. 다 읽은 뒤 앞으로
                    // 들춰 보던 자리면 그 자리다.
                    p == null || (p.finished && p.page >= pages.count - 1) -> 0 to 0f
                    else -> p.page to (p.offset ?: 0f)
                }
                comic
            }
        }
        opened.onSuccess { book = it }.onFailure {
            made?.close()
            if (it is kotlinx.coroutines.CancellationException) throw it
            android.util.Log.w("OloComic", "cannot open $unitId", it)
            onFail(
                if (it is java.io.FileNotFoundException || it is SecurityException) {
                    "파일을 찾을 수 없습니다. 옮겨졌거나 지워졌을 수 있습니다. 서재에서 새로고침해 주세요."
                } else {
                    "만화 파일이 손상됐거나 그림이 없습니다. 다른 곳에서 다시 받아 보세요."
                },
            )
        }
    }
    androidx.compose.runtime.DisposableEffect(key, reload) { onDispose { book?.close() } }
    val works by remember { data.comics.works() }.collectAsState(initial = null)
    val progress by remember { data.comics.progress() }.collectAsState(initial = emptyMap())
    val bookmarks by remember(unitId) { data.comics.bookmarks(unitId) }.collectAsState(initial = emptyList())
    val opened = book
    val all = works
    if (opened == null || all == null) {
        // 처음 여는 압축 속 권(0.48.0, 결정 ④)은 바깥 압축에서 꺼내느라 1~2초 걸린다 — 빈 화면이면 멈춘 것처럼 보였다. 금방 열리는
        // 권에서 글자가 번쩍이지 않게 잠깐 기다렸다가 보인다.
        var slow by remember(key, reload) { mutableStateOf(false) }
        LaunchedEffect(key, reload) {
            kotlinx.coroutines.delay(OPENING_NOTICE_DELAY_MS)
            slow = true
        }
        Box(Modifier.fillMaxSize().background(COMIC_BACKDROP), contentAlignment = Alignment.Center) {
            if (slow) {
                val label = all?.firstNotNullOfOrNull { w -> ComicReading.entryOf(w, unitId) }?.label
                CpText(openingNotice(label), CpTheme.type.body, Color.White.copy(alpha = 0.72f))
            }
        }
        return
    }
    val work = all.firstOrNull { w -> ComicReading.entryOf(w, unitId) != null }
    val entry = work?.let { ComicReading.entryOf(it, unitId) }
    val title = listOfNotNull(work?.title, entry?.label).joinToString(" ").ifEmpty { opened.unit.name }
    val view = Webtoon.view(work?.view, opened.unit.info, sizes)
    val now = { System.currentTimeMillis() }
    // 자리 · 책갈피 · 작품 설정 쓰기가 실패하면(저장 공간이 가득 · DB 가 깨짐) 알림만 띄우고 계속 본다 — 잡지 않으면 쪽을
    // 넘기는 손에 앱이 닫혔다.
    var writeError by remember { mutableIntStateOf(0) }
    val failed: (Exception) -> Unit = { writeError++ }
    val onBookmark: (Int) -> Unit = { p -> scope.launchWrite(failed) { data.comics.toggleBookmark(unitId, p, now()) } }
    val onView: (ComicView?) -> Unit = { v ->
        // 이어 붙인 다음 화를 보던 중이면 그 화로 새로 연다 — 쪽 넘김은 처음 연 화의 책을 그린다.
        if (unitId != loadKey) chain = emptySet()
        work?.let { w -> scope.launchWrite(failed) { data.comics.setView(w, v) } }
    }
    val saveCover: suspend (android.graphics.Bitmap) -> Boolean = { bitmap ->
        work != null && container.covers.setCustom(CoverStore.workId(work.key), bitmap)
    }
    Box(Modifier.fillMaxSize()) {
        when (view) {
            ComicView.PAGE -> Box(Modifier.fillMaxSize()) { ComicReader(
                book = opened,
                title = title,
                work = work,
                startPage = position.first,
                bookmarks = bookmarks,
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                onPage = { p ->
                    position = p to 0f
                    scope.launchWrite(failed) { data.comics.saveProgress(unitId, p, opened.pageCount, now()) }
                },
                onBookmark = onBookmark,
                onDirection = { r -> work?.let { w -> scope.launchWrite(failed) { data.comics.setRightToLeft(w, r) } } },
                onNext = { n -> onOpen(n.unit.id) },
                onClose = onClose,
                onChrome = onChrome,
                view = work?.view,
                onView = onView,
                sizes = sizes,
            )
                // 쪽 넘김 만화는 장면이 곧 쪽이다 — 보던 쪽을 통째로 표지로(웹툰처럼 틀로 자르면 쪽의 제목 · 그림이 잘린다).
                if (pickCover) PagePickBar(onBack = onClose) {
                    val size = sizes.getOrNull(position.first) ?: return@PagePickBar false
                    val bitmap = opened.crop(position.first, android.graphics.Rect(0, 0, size.width, size.height), CoverStore.COVER_HEIGHT) ?: return@PagePickBar false
                    saveCover(bitmap).also { if (it) onClose() }
                }
            }
            ComicView.WEBTOON -> WebtoonReader(
                first = remember(opened) { WebtoonEpisode(work?.entries?.firstOrNull { e -> e.unit.id == loadKey || e.copies.any { it.id == loadKey } }, opened, sizes) },
                title = work?.title ?: title,
                work = work,
                currentId = unitId,
                startIndex = position.first,
                startOffset = position.second,
                bookmarks = bookmarks,
                progress = progress,
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                view = work?.view,
                onView = onView,
                openEpisode = { entry ->
                    // 처음 연 화와 같다: 크기를 읽다 실패하거나 미리 여는 사이 취소되면(회전 · 그림 폭 바꾸기) 여기서 닫는다 — 두면
                    // 압축 · 파일이 열린 채 남았다.
                    var made: ComicBook? = null
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val unit = data.comics.unit(entry.unit.id) ?: return@withContext null
                            // 이어 붙이는 화는 몫을 반으로 — 처음 연 화와 함께 메모리에 있다.
                            val comic = ComicBook(unit, data.openComic(unit), budget = ComicBook.memoryBudget() / 2, regions = container.comicRegions, stripDelayMs = { container.comicStripDelayMs })
                                .also { made = it }
                            WebtoonEpisode(entry, comic, comic.sizes())
                        }
                    }.onFailure {
                        made?.close()
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.w("OloComic", "cannot open next ${entry.unit.id}", it)
                    }.getOrNull()
                },
                onEnter = { id ->
                    chain = chain + id
                    onOpen(id)
                },
                onPosition = { e, i, f, end ->
                    if (e.unitId == unitId) position = i to f
                    scope.launchWrite(failed) { data.comics.saveProgress(e.unitId, i, e.book.pageCount, now(), offset = f, atEnd = end) }
                },
                onBookmark = { id, p -> scope.launchWrite(failed) { data.comics.toggleBookmark(id, p, now()) } },
                onJump = { n ->
                    chain = emptySet()
                    reload++
                    onOpen(n.unit.id)
                },
                onClose = onClose,
                onChrome = onChrome,
                pickCover = pickCover,
                onCover = saveCover,
            )
        }
        // 쪽마다 실패해도 알림은 하나가 이어 떠 있다(열쇠가 바뀌면 시간을 새로 센다).
        CpToast(WRITE_FAILED.takeIf { writeError > 0 }, onDone = { writeError = 0 }, Modifier.align(Alignment.BottomCenter), key = writeError)
    }
}

/** 쪽 넘김 만화에서 표지 고르기: 위에 "표지로 쓸 쪽", 아래에 "이 쪽을 표지로". 넘겨서 쪽을 고른다. */
@Composable
private fun PagePickBar(onBack: () -> Unit, onPick: suspend () -> Boolean) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.background(CpTheme.colors.surface).windowInsetsPadding(WindowInsets.statusBars)) {
            CpHeader("표지로 쓸 쪽", subtitle = if (failed) "이 쪽을 표지로 쓸 수 없습니다" else "넘겨서 쪽을 고릅니다", onBack = onBack)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().background(CpTheme.colors.surface).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            CpButton(if (saving) "저장하는 중…" else "이 쪽을 표지로", {
                if (saving) return@CpButton
                saving = true
                scope.launch {
                    failed = !onPick()
                    saving = false
                }
            })
        }
    }
}
