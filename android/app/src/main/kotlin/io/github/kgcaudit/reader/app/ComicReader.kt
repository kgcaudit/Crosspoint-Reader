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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.kgcaudit.reader.ui.design.ComicSpread
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPageTurn
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
import io.github.kgcaudit.reader.ui.design.rememberPageTurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
internal val COMIC_INK_MUTED = Color(0xFFB9B2A8)

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
    view: ComicView? = null,
    onView: (ComicView?) -> Unit = {},
    /** 쪽 크기(머리만 읽은 것). 두 쪽 보기의 짝 · 함께 맞춤에 쓴다. 모르면 빈 목록. */
    sizes: List<ImageSize?> = emptyList(),
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
    val turns = rememberPageTurnState()
    val latestPrefs by rememberUpdatedState(prefs)
    // 두 쪽 보기는 화면 모양에 따라 정한다(가로에서 · 늘). 판 목록은 그때마다 다시 짠다 — 짝은 쪽 크기로 정해진다.
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val two = prefs.comicSpread.twoPages(screen.width.toFloat(), screen.height.toFloat()) && count > 1
    val spreads = remember(two, count, sizes) {
        if (two) ComicSpreads.of(List(count) { sizes.getOrNull(it) }) else List(count) { listOf(it) }
    }
    val spreadIndex = ComicSpreads.indexOf(spreads, page)
    val shown = spreads.getOrElse(spreadIndex) { listOf(page) }
    val bookmarked = shown.any { it in bookmarks }

    // 자리는 판의 마지막 쪽으로 적는다 — 끝 판을 보면 다 읽음이고, 다시 열면 그 쪽이 든 판이 나온다.
    LaunchedEffect(shown) { onPage(shown.last()) }
    LaunchedEffect(panel) { onChrome(panel != ComicPanel.None) }
    ReadingWindow(prefs, activity = page)

    fun say(message: String) {
        toast = message
        toastCount++
    }
    /** 읽는 순서로 한 판(한 쪽 또는 두 쪽). 마지막 판에서 앞으로 가면 권 끝 판. */
    fun advance(forward: Boolean) {
        if (forward) {
            if (spreadIndex < spreads.size - 1) { turns.request(); page = spreads[spreadIndex + 1].first() } else ended = true
        } else if (spreadIndex > 0) {
            turns.request()
            page = spreads[spreadIndex - 1].first()
        }
    }
    fun toggleBookmark() {
        say(if (bookmarked) "책갈피를 뺐습니다" else "책갈피를 꽂았습니다")
        // 두 쪽 중 이미 꽂힌 쪽이 있으면 그것을 뺀다. 없으면 판의 첫 쪽에 꽂는다.
        onBookmark(shown.firstOrNull { it in bookmarks } ?: shown.first())
    }
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

    Box(Modifier.fillMaxSize().background(COMIC_BACKDROP)) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).onSizeChanged { screen = it }) {
                val viewW = constraints.maxWidth.toFloat()
                val viewH = constraints.maxHeight.toFloat()
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                // 지금 판의 확대 · 위치. 판이 바뀌면 다시 전체가 보인다. 두 쪽은 한 그림처럼 함께 맞추고 함께 확대한다.
                var aspect by remember(shown) { mutableStateOf(spreadAspect(book, sizes, shown)) }
                var viewport by remember(shown, w, h, aspect) { mutableStateOf(aspect?.let { PageViewport.fit(viewW, viewH, it) }) }
                LaunchedEffect(shown, w, h) {
                    for (p in shown) book.page(p, w, h)
                    aspect = spreadAspect(book, sizes, shown)
                    // 앞뒤 판을 미리 풀어 둔다 — 넘길 때 빈 화면이 번쩍이지 않게.
                    for (near in listOf(spreadIndex + 1, spreadIndex - 1)) spreads.getOrNull(near)?.forEach { book.page(it, w, h) }
                }
                if (w > 0 && h > 0 && count > 0) {
                    // 오→왼: 넘김 효과를 통째로 거울에 비춘다(말림이 왼쪽 모서리에서 일어난다). 쪽 그림은 안에서 다시 뒤집어
                    // 바로 보인다. 누름은 거울 밖에서 받는다 — 화면 좌표가 그대로라 누름 구역 계산이 하나다.
                    Box(Modifier.fillMaxSize().graphicsLayer { scaleX = if (rtl) -1f else 1f }) {
                        CpPageTurn(
                            key = shown,
                            effect = prefs.pageTurn,
                            forward = { from, to -> to.first() > from.first() },
                            turns = turns,
                            spread = shown.size == 2,
                            sound = prefs.turnSound,
                            haptic = prefs.turnHaptic,
                        ) { pages ->
                            ComicPageImage(book, sizes, pages, w, h, if (pages == shown) viewport else null, mirrored = rtl)
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().clipToBounds()
                        .semantics { contentDescription = "만화 ${pagesLabel(shown)}쪽" }
                        .pointerInput(shown, w, h, rtl) {
                            detectTapGestures(
                                onDoubleTap = { at -> viewport = viewport?.toggleZoom(at.x, at.y) },
                                onTap = { at ->
                                    if (panel != ComicPanel.None) { panel = ComicPanel.None; return@detectTapGestures }
                                    when (latestPrefs.touch.actionAt(at.x, at.y, viewW, 56.dp.toPx())) {
                                        // 화면의 오른쪽 = 왼→오 책의 다음. 오→왼이면 뒤집는다.
                                        TapAction.Next -> advance(ComicReading.forward(screenNext = true, rightToLeft = rtl))
                                        TapAction.Previous -> advance(ComicReading.forward(screenNext = false, rightToLeft = rtl))
                                        TapAction.Menu -> panel = ComicPanel.Bar
                                        TapAction.Bookmark -> toggleBookmark()
                                    }
                                },
                            )
                        }
                        .pointerInput(shown, w, h, rtl) {
                            val threshold = 48.dp.toPx()
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var moving = false
                                var pinched = false
                                var travel = Offset.Zero
                                var swipe = 0f
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
                                        val vp = viewport
                                        if (vp != null && zoom != 1f) {
                                            val c = event.calculateCentroid()
                                            viewport = vp.zoom(zoom, c.x, c.y)
                                        }
                                        // 확대한 동안 끌기는 쪽 안을 움직인다. 넘김으로 읽으면 확대한 곳을 보려고 끌 때마다 넘어간다.
                                        if (viewport?.isZoomed == true) viewport = viewport?.pan(pan.x, pan.y) else swipe += pan.x
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                                if (moving && !pinched && viewport?.isZoomed != true && abs(swipe) > threshold) {
                                    // 왼쪽으로 밀면 화면의 "다음"(왼→오 책). 오→왼이면 오른쪽으로 밀어야 다음.
                                    advance(ComicReading.forward(screenNext = swipe < 0, rightToLeft = rtl))
                                }
                            }
                        },
                )
            }
            ComicFooter(title, shown, count, rtl, Modifier.windowInsetsPadding(WindowInsets.displayCutout))
        }
        if (bookmarked) CpRibbon(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(end = 20.dp))
        CpToast(toast, onDone = { toast = null }, Modifier.align(Alignment.BottomCenter), key = toastCount)
    }

    when (panel) {
        ComicPanel.None -> Unit
        ComicPanel.Bar, ComicPanel.View -> CpReaderBar(
            title = title,
            subtitle = "${pagesLabel(shown)} / ${count}쪽",
            bookmarked = bookmarked,
            onBookmark = ::toggleBookmark,
            onBack = onClose,
            onDismiss = { panel = ComicPanel.None },
            progress = if (count > 1) page / (count - 1f) else 1f,
            progressLabel = { "${pageAt(it, count) + 1}쪽" },
            onSeek = { target -> page = pageAt(target, count) },
            above = {
                if (panel == ComicPanel.View) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        ViewChoice(view, onView)
                        CpChoice("넘기는 방향", listOf("왼→오", "오→왼"), if (rtl) 1 else 0, { onDirection(it == 1) })
                        val choices = ComicSpread.entries
                        CpChoice("두 쪽 보기", choices.map { it.label }, choices.indexOf(prefs.comicSpread), { onPrefsChange(prefs.copy(comicSpread = choices[it])) })
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
        ComicPanel.Settings -> CpViewSettingsScreen(prefs, onPrefsChange, onBack = { panel = ComicPanel.View }, pdf = true, highlights = false)
        ComicPanel.Pages -> PageGrid(book, title, page, onBack = { panel = ComicPanel.Bar }) { panel = ComicPanel.None; page = it }
        ComicPanel.Bookmarks -> BookmarkList(book, bookmarks, onBack = { panel = ComicPanel.Bar }) { panel = ComicPanel.None; page = it }
    }

    if (ended) {
        VolumeEnd(
            entryLabel = work?.let { ComicReading.entryOf(it, book.unit.id)?.label } ?: title,
            workTitle = work?.title,
            next = next,
            onNext = { n -> ended = false; onNext(n) },
            onLibrary = onClose,
        )
    }
}

/**
 * 목적격 조사: 받침이 있으면 "을", 없으면 "를". 줄 이름은 "1권" · "48화" · "외전" · "4–6권" 처럼 늘 한글로 끝나지만, 한글이
 * 아니면(이름 그대로인 줄) "을" — 숫자 · 영문 뒤에서는 어느 쪽도 틀리지 않게 읽힌다.
 */
internal fun objectParticle(word: String): String {
    val last = word.trimEnd().lastOrNull() ?: return "을"
    if (last !in '가'..'힣') return "을"
    return if ((last - '가') % 28 == 0) "를" else "을"
}

/** 보기 판의 "보는 방식" 줄: 자동 · 쪽 넘김 · 웹툰. 고른 값은 작품마다 기억한다(결정 2). */
@Composable
internal fun ViewChoice(view: ComicView?, onView: (ComicView?) -> Unit) {
    val options = listOf(null, ComicView.PAGE, ComicView.WEBTOON)
    CpChoice("보는 방식", listOf("자동", "쪽 넘김", "웹툰"), options.indexOf(view), { onView(options[it]) })
}

/** 진행 막대 0..1 → 쪽. 막대 위 숫자와 가는 곳이 같아야 한다. */
internal fun pageAt(fraction: Float, pageCount: Int): Int =
    if (pageCount <= 1) 0 else (fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt()

/**
 * 쪽 그림 하나. [viewport] 가 없으면(넘어가는 중의 옛 쪽 · 크기를 아직 모름) 화면에 맞춰 가운데. [mirrored] 면 그림을
 * 좌우로 뒤집어 그린다 — 바깥 거울(오→왼 넘김 효과)과 겹쳐 바로 보인다.
 */
@Composable
private fun ComicPageImage(book: ComicBook, sizes: List<ImageSize?>, pages: List<Int>, w: Int, h: Int, viewport: PageViewport?, mirrored: Boolean) {
    val bitmaps = remember(pages, w, h) { androidx.compose.runtime.mutableStateListOf(*pages.map { book.cached(it, w, h) }.toTypedArray()) }
    var broken by remember(pages, w, h) { mutableStateOf(pages.any { book.isBroken(it) }) }
    LaunchedEffect(pages, w, h) {
        pages.forEachIndexed { k, p ->
            if (bitmaps[k] == null && !book.isBroken(p)) bitmaps[k] = book.page(p, w, h)
        }
        broken = pages.any { book.isBroken(it) }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // 각 쪽의 비. 아직 모르면 푼 그림에서, 그것도 없으면 그리지 않는다(흔들리지 않게).
            val aspects = pages.mapIndexed { k, p -> sizes.getOrNull(p)?.let { it.width.toFloat() / it.height } ?: bitmaps[k]?.let { it.width.toFloat() / it.height } }
            if (aspects.any { it == null }) return@Canvas
            val total = aspects.sumOf { it!!.toDouble() }.toFloat()
            val vp = viewport ?: PageViewport.fit(size.width, size.height, total)
            withTransform({ if (mirrored) scale(-1f, 1f, pivot = center) }) {
                // 화면에 보이는 차례로 늘어놓는다: 오→왼 책은 앞 쪽이 오른쪽(구상안 ⑥). 바깥 거울 · 안쪽 뒤집기가 서로를 지워
                // 자리는 여기 적은 그대로 보인다.
                val order = if (mirrored) pages.indices.reversed() else pages.indices
                var x = vp.left
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
        }
        if (broken) {
            // 거울 안이라 글자도 뒤집혀 보인다 — 같은 뒤집기로 바로 세운다.
            CpText(
                "이 쪽을 그리지 못했습니다",
                CpTheme.type.subtitle,
                COMIC_INK_MUTED,
                Modifier.align(Alignment.Center).graphicsLayer { scaleX = if (mirrored) -1f else 1f },
            )
        }
    }
}

/**
 * 아래 줄: 가는 진행 막대 + "제목 · 12 / 180". 오→왼 책은 막대가 오른쪽에서 차오른다(네이버 시리즈 — 진행도 읽는 방향을
 * 따른다).
 */
@Composable
private fun ComicFooter(title: String, shown: List<Int>, count: Int, rtl: Boolean, modifier: Modifier) =
    ComicFooterLine(title, "${pagesLabel(shown)} / $count" + if (rtl) "  ← 오→왼" else "", if (count > 1) shown.last() / (count - 1f) else 1f, rtl, modifier)

/** "12" · 두 쪽이면 "2–3". */
internal fun pagesLabel(shown: List<Int>): String =
    if (shown.size >= 2) "${shown.first() + 1}–${shown.last() + 1}" else "${(shown.firstOrNull() ?: 0) + 1}"

/**
 * 판의 가로/세로 비: 쪽들을 같은 높이로 나란히 놓은 폭의 합. 크기를 모르는 쪽은 푼 그림의 비, 그것도 모르면 null(아직 기다림).
 */
internal fun spreadAspect(book: ComicBook, sizes: List<ImageSize?>, pages: List<Int>): Float? {
    var sum = 0f
    for (p in pages) sum += pageAspect(book, sizes, p) ?: return null
    return sum
}

private fun pageAspect(book: ComicBook, sizes: List<ImageSize?>, page: Int): Float? =
    sizes.getOrNull(page)?.let { it.width.toFloat() / it.height } ?: book.knownAspect(page)

/** 아래 줄 그리기. 웹툰(%)도 같은 모양으로 쓴다. */
@Composable
internal fun ComicFooterLine(title: String, position: String, fraction: Float, rtl: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0x33FFFFFF))) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(2.dp).background(CpTheme.colors.accent)
                    .align(if (rtl) Alignment.CenterEnd else Alignment.CenterStart)
                    .semantics { contentDescription = if (rtl) "진행 오른쪽부터" else "진행 왼쪽부터" },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CpText(title, CpTheme.type.caption, COMIC_INK_MUTED, Modifier.weight(1f))
            CpText(position, CpTheme.type.caption, COMIC_INK_MUTED)
        }
    }
}

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
                "꽂은 책갈피가 없습니다. 오른쪽 위 모서리를 누르거나 메뉴의 책갈피 단추로 꽂습니다.",
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
private fun VolumeEnd(entryLabel: String, workTitle: String?, next: WorkEntry?, onNext: (WorkEntry) -> Unit, onLibrary: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(COMIC_BACKDROP).clickable(indication = null, interactionSource = null) {}
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { EndCard(entryLabel, workTitle, next, onNext, onLibrary) }
}

/** 권 · 화 끝 판의 내용. 쪽 넘김은 화면을 덮고, 웹툰은 목록 맨 끝에 이어 붙인다. */
@Composable
internal fun EndCard(entryLabel: String, workTitle: String?, next: WorkEntry?, onNext: (WorkEntry) -> Unit, onLibrary: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CpText("${entryLabel}${objectParticle(entryLabel)} 다 읽었습니다", CpTheme.type.title, Color.White)
        Spacer(Modifier.height(24.dp))
        if (next != null) {
            ComicCover(next.unit, workTitle ?: next.label, next.label, Modifier.width(150.dp))
            Spacer(Modifier.height(16.dp))
            CpText("다음: ${listOfNotNull(workTitle, next.label).joinToString(" ")}", CpTheme.type.subtitle, COMIC_INK_MUTED)
            Spacer(Modifier.height(20.dp))
            CpButton("이어서 보기", { onNext(next) })
        } else {
            CpText("마지막 권입니다", CpTheme.type.subtitle, COMIC_INK_MUTED)
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
) {
    val container = LocalContext.current.container
    val data = container.data
    val scope = rememberCoroutineScope()
    var book by remember(unitId) { mutableStateOf<ComicBook?>(null) }
    var sizes by remember(unitId) { mutableStateOf<List<io.github.kgcaudit.reader.document.image.ImageSize?>>(emptyList()) }
    // 지금 자리(그림 번호 · 그 안의 비율). 보는 방식을 바꿔도 같은 그림에서 이어진다.
    var position by remember(unitId) { mutableStateOf(0 to 0f) }
    LaunchedEffect(unitId) {
        val opened = runCatching {
            withContext(Dispatchers.IO) {
                val unit = data.comics.unit(unitId) ?: throw java.io.FileNotFoundException(unitId)
                val p = data.comics.progressOf(unitId)
                val pages = data.openComic(unit)
                val comic = ComicBook(unit, pages, regions = container.comicRegions)
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
            if (it is kotlinx.coroutines.CancellationException) throw it
            android.util.Log.w("OloComic", "cannot open $unitId", it)
            onFail(
                if (it is java.io.FileNotFoundException || it is SecurityException) {
                    "파일을 찾을 수 없습니다. 옮겨졌거나 지워졌을 수 있습니다. 서재에서 새로고침해 보세요."
                } else {
                    "만화 파일이 손상됐거나 그림이 없습니다. 다른 곳에서 다시 받아 보세요."
                },
            )
        }
    }
    androidx.compose.runtime.DisposableEffect(unitId) { onDispose { book?.close() } }
    val works by remember { data.comics.works() }.collectAsState(initial = null)
    val bookmarks by remember(unitId) { data.comics.bookmarks(unitId) }.collectAsState(initial = emptyList())
    val opened = book
    val all = works
    if (opened == null || all == null) {
        Box(Modifier.fillMaxSize().background(COMIC_BACKDROP))
        return
    }
    val work = all.firstOrNull { w -> ComicReading.entryOf(w, unitId) != null }
    val entry = work?.let { ComicReading.entryOf(it, unitId) }
    val title = listOfNotNull(work?.title, entry?.label).joinToString(" ").ifEmpty { opened.unit.name }
    val view = Webtoon.view(work?.view, opened.unit.info, sizes)
    val now = { System.currentTimeMillis() }
    val onBookmark: (Int) -> Unit = { p -> scope.launch(Dispatchers.IO) { data.comics.toggleBookmark(unitId, p, now()) } }
    val onView: (ComicView?) -> Unit = { v -> work?.let { w -> scope.launch(Dispatchers.IO) { data.comics.setView(w, v) } } }
    when (view) {
        ComicView.PAGE -> ComicReader(
            book = opened,
            title = title,
            work = work,
            startPage = position.first,
            bookmarks = bookmarks,
            prefs = prefs,
            onPrefsChange = onPrefsChange,
            onPage = { p ->
                position = p to 0f
                scope.launch(Dispatchers.IO) { data.comics.saveProgress(unitId, p, opened.pageCount, now()) }
            },
            onBookmark = onBookmark,
            onDirection = { r -> work?.let { w -> scope.launch(Dispatchers.IO) { data.comics.setRightToLeft(w, r) } } },
            onNext = { n -> onOpen(n.unit.id) },
            onClose = onClose,
            onChrome = onChrome,
            view = work?.view,
            onView = onView,
            sizes = sizes,
        )
        ComicView.WEBTOON -> WebtoonReader(
            book = opened,
            sizes = sizes,
            title = title,
            work = work,
            startIndex = position.first,
            startOffset = position.second,
            bookmarks = bookmarks,
            prefs = prefs,
            onPrefsChange = onPrefsChange,
            view = work?.view,
            onView = onView,
            onPosition = { i, f, end ->
                position = i to f
                scope.launch(Dispatchers.IO) { data.comics.saveProgress(unitId, i, opened.pageCount, now(), offset = f, atEnd = end) }
            },
            onBookmark = onBookmark,
            onNext = { n -> onOpen(n.unit.id) },
            onClose = onClose,
            onChrome = onChrome,
        )
    }
}
