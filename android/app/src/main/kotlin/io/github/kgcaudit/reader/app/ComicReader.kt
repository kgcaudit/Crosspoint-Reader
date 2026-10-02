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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.comic.ComicReading
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.comic.WorkEntry
import io.github.kgcaudit.reader.pdf.PageViewport
import io.github.kgcaudit.reader.ui.design.CpBrightnessRow
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
private val COMIC_INK_MUTED = Color(0xFFB9B2A8)

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
) {
    val count = book.pageCount
    var page by rememberSaveable(book.unit.id) { mutableIntStateOf(startPage.coerceIn(0, (count - 1).coerceAtLeast(0))) }
    var ended by rememberSaveable(book.unit.id) { mutableStateOf(false) }
    var panel by remember { mutableStateOf(ComicPanel.None) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastCount by remember { mutableIntStateOf(0) }
    val rtl = ComicReading.rightToLeft(work)
    val next = work?.let { ComicReading.nextAfter(it, book.unit.id) }
    val bookmarked = page in bookmarks
    val turns = rememberPageTurnState()
    val latestPrefs by rememberUpdatedState(prefs)

    LaunchedEffect(page) { onPage(page) }
    LaunchedEffect(panel) { onChrome(panel != ComicPanel.None) }
    ReadingWindow(prefs, activity = page)

    fun say(message: String) {
        toast = message
        toastCount++
    }
    /** 읽는 순서로 한 쪽. 마지막 쪽에서 앞으로 가면 권 끝 판. */
    fun advance(forward: Boolean) {
        if (forward) {
            if (page < count - 1) { turns.request(); page++ } else ended = true
        } else if (page > 0) {
            turns.request()
            page--
        }
    }
    fun toggleBookmark() {
        say(if (bookmarked) "책갈피를 뺐습니다" else "책갈피를 꽂았습니다")
        onBookmark(page)
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
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout)) {
                val viewW = constraints.maxWidth.toFloat()
                val viewH = constraints.maxHeight.toFloat()
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                // 지금 쪽의 확대 · 위치. 쪽이 바뀌면 다시 전체가 보인다.
                var aspect by remember(page) { mutableStateOf(book.knownAspect(page)) }
                var viewport by remember(page, w, h, aspect) { mutableStateOf(aspect?.let { PageViewport.fit(viewW, viewH, it) }) }
                LaunchedEffect(page, w, h) {
                    book.page(page, w, h)
                    aspect = book.knownAspect(page)
                    // 앞뒤 쪽을 미리 풀어 둔다 — 넘길 때 빈 화면이 번쩍이지 않게.
                    for (near in intArrayOf(page + 1, page - 1, page + 2)) if (near in 0 until count) book.page(near, w, h)
                }
                if (w > 0 && h > 0 && count > 0) {
                    // 오→왼: 넘김 효과를 통째로 거울에 비춘다(말림이 왼쪽 모서리에서 일어난다). 쪽 그림은 안에서 다시 뒤집어
                    // 바로 보인다. 누름은 거울 밖에서 받는다 — 화면 좌표가 그대로라 누름 구역 계산이 하나다.
                    Box(Modifier.fillMaxSize().graphicsLayer { scaleX = if (rtl) -1f else 1f }) {
                        CpPageTurn(
                            key = page,
                            effect = prefs.pageTurn,
                            forward = { from, to -> to > from },
                            turns = turns,
                            sound = prefs.turnSound,
                            haptic = prefs.turnHaptic,
                        ) { shown ->
                            ComicPageImage(book, shown, w, h, if (shown == page) viewport else null, mirrored = rtl)
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().clipToBounds()
                        .semantics { contentDescription = "만화 ${page + 1}쪽" }
                        .pointerInput(page, w, h, rtl) {
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
                        .pointerInput(page, w, h, rtl) {
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
            ComicFooter(title, page, count, rtl, Modifier.windowInsetsPadding(WindowInsets.displayCutout))
        }
        if (bookmarked) CpRibbon(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(end = 20.dp))
        CpToast(toast, onDone = { toast = null }, Modifier.align(Alignment.BottomCenter), key = toastCount)
    }

    when (panel) {
        ComicPanel.None -> Unit
        ComicPanel.Bar, ComicPanel.View -> CpReaderBar(
            title = title,
            subtitle = "${page + 1} / ${count}쪽",
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
                        // 보는 방식(웹툰) · 두 쪽 보기는 다음 단계(C2 · C3)에서 이 판에 더한다.
                        CpChoice("넘기는 방향", listOf("왼→오", "오→왼"), if (rtl) 1 else 0, { onDirection(it == 1) })
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

/** 진행 막대 0..1 → 쪽. 막대 위 숫자와 가는 곳이 같아야 한다. */
internal fun pageAt(fraction: Float, pageCount: Int): Int =
    if (pageCount <= 1) 0 else (fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt()

/**
 * 쪽 그림 하나. [viewport] 가 없으면(넘어가는 중의 옛 쪽 · 크기를 아직 모름) 화면에 맞춰 가운데. [mirrored] 면 그림을
 * 좌우로 뒤집어 그린다 — 바깥 거울(오→왼 넘김 효과)과 겹쳐 바로 보인다.
 */
@Composable
private fun ComicPageImage(book: ComicBook, index: Int, w: Int, h: Int, viewport: PageViewport?, mirrored: Boolean) {
    var bitmap by remember(index, w, h) { mutableStateOf<Bitmap?>(book.cached(index, w, h)) }
    var broken by remember(index, w, h) { mutableStateOf(book.isBroken(index)) }
    LaunchedEffect(index, w, h) {
        if (bitmap == null && !broken) {
            bitmap = book.page(index, w, h)
            broken = bitmap == null
        }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val b = bitmap ?: return@Canvas
            val vp = viewport ?: PageViewport.fit(size.width, size.height, b.width.toFloat() / b.height)
            withTransform({ if (mirrored) scale(-1f, 1f, pivot = center) }) {
                drawImage(
                    b.asImageBitmap(),
                    dstOffset = IntOffset(vp.left.roundToInt(), vp.top.roundToInt()),
                    dstSize = IntSize(vp.width.roundToInt(), vp.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
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
private fun ComicFooter(title: String, page: Int, count: Int, rtl: Boolean, modifier: Modifier) {
    val fraction = if (count > 1) page / (count - 1f) else 1f
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
            CpText("${page + 1} / $count" + if (rtl) "  ← 오→왼" else "", CpTheme.type.caption, COMIC_INK_MUTED)
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
private fun BookmarkList(book: ComicBook, pages: List<Int>, onBack: () -> Unit, onPick: (Int) -> Unit) {
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
    ) {
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
 * 서재에서 만화 한 권을 연다: 단위를 찾아 열고, 작품 · 책갈피를 지켜보며, 넘길 때마다 자리를 적는다. 열지 못하면 [onFail].
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
    val data = LocalContext.current.container.data
    val scope = rememberCoroutineScope()
    var book by remember(unitId) { mutableStateOf<ComicBook?>(null) }
    var start by remember(unitId) { mutableIntStateOf(0) }
    LaunchedEffect(unitId) {
        val opened = runCatching {
            withContext(Dispatchers.IO) {
                val unit = data.comics.unit(unitId) ?: throw java.io.FileNotFoundException(unitId)
                val p = data.comics.progressOf(unitId)
                val pages = data.openComic(unit)
                // 다 읽은 권을 다시 열면 처음부터 — 끝 쪽에서 열면 곧바로 "다 읽었습니다" 판이 뜬다.
                start = when {
                    startAt != null -> startAt
                    p == null || (p.finished && p.page >= pages.count - 1) -> 0
                    else -> p.page
                }
                ComicBook(unit, pages)
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
    val opened = book ?: run {
        Box(Modifier.fillMaxSize().background(COMIC_BACKDROP))
        return
    }
    val work = works?.firstOrNull { w -> ComicReading.entryOf(w, unitId) != null }
    val entry = work?.let { ComicReading.entryOf(it, unitId) }
    val title = listOfNotNull(work?.title, entry?.label).joinToString(" ").ifEmpty { opened.unit.name }
    ComicReader(
        book = opened,
        title = title,
        work = work,
        startPage = start,
        bookmarks = bookmarks,
        prefs = prefs,
        onPrefsChange = onPrefsChange,
        onPage = { p -> scope.launch(Dispatchers.IO) { data.comics.saveProgress(unitId, p, opened.pageCount, System.currentTimeMillis()) } },
        onBookmark = { p -> scope.launch(Dispatchers.IO) { data.comics.toggleBookmark(unitId, p, System.currentTimeMillis()) } },
        onDirection = { r -> work?.let { w -> scope.launch(Dispatchers.IO) { data.comics.setRightToLeft(w, r) } } },
        onNext = { n -> onOpen(n.unit.id) },
        onClose = onClose,
        onChrome = onChrome,
    )
}
