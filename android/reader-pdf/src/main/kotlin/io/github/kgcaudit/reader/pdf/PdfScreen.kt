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
import androidx.compose.runtime.LaunchedEffect
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

    ReadingWindow(prefs.copy(brightness = dragBrightness ?: prefs.brightness), activity = state.page)
    VolumeKeyPaging(enabled = prefs.volumeKeys && panel == PdfPanel.None) { forward ->
        scope.go { if (forward) reader.next() else reader.previous() }
    }
    fun toggleBookmark() = scope.go {
        reader.toggleBookmark()
        toast = if (reader.state.value.bookmarked) "책갈피를 꽂았습니다" else "책갈피를 뺐습니다"
        toastCount++
    }

    LaunchedEffect(panel) { onChrome(panel != PdfPanel.None) }
    BackHandler {
        panel = when (panel) {
            PdfPanel.None -> { onClose(); PdfPanel.None }
            PdfPanel.Contents, PdfPanel.Notes -> PdfPanel.Bar
            PdfPanel.View -> PdfPanel.Bar
            PdfPanel.Settings -> PdfPanel.View
            PdfPanel.Bar -> PdfPanel.None
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
                val twoPages = prefs.twoPages(viewW / density, viewH / density, smallest)
                LaunchedEffect(twoPages, prefs.pdfCoverAlone) { reader.setSpread(if (twoPages) prefs.pdfCoverAlone else null) }
                val onTap: (Offset, Float) -> Unit = { at, corner ->
                    if (panel != PdfPanel.None) {
                        panel = PdfPanel.None
                    } else {
                        when (prefs.touch.actionAt(at.x, at.y, viewW, corner)) {
                            TapAction.Previous -> scope.go { reader.previous() }
                            TapAction.Next -> scope.go { reader.next() }
                            TapAction.Menu -> panel = PdfPanel.Bar
                            TapAction.Bookmark -> toggleBookmark()
                        }
                    }
                }
                val onSwipe: (Boolean) -> Unit = { forward -> scope.go { if (forward) reader.next() else reader.previous() } }
                // 넘김 효과(E7): 보이는 쪽(들)이 바뀔 때.
                if (state.ready && state.pageCount > 0 && viewW > 0f && viewH > 0f) CpPageTurn(
                    key = state.shown,
                    effect = prefs.pageTurn,
                    forward = { from, to -> (to.firstOrNull() ?: 0) > (from.firstOrNull() ?: 0) },
                ) { shown ->
                    if (twoPages) {
                        SpreadView(reader, shown, viewW, viewH, onTap, onSwipe)
                    } else {
                        PageView(
                            reader = reader,
                            page = shown.first(),
                            viewW = viewW,
                            viewH = viewH,
                            onTap = onTap,
                            onSwipe = onSwipe,
                        )
                    }
                }
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
            onBack = onClose,
            onDismiss = { panel = PdfPanel.None },
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
        PdfPanel.Settings -> CpViewSettingsScreen(prefs, onPrefsChange, onBack = { panel = PdfPanel.View }, pdf = true)
        PdfPanel.Contents, PdfPanel.Notes -> PdfLists(
            reader = reader,
            page = state.page,
            showNotes = panel == PdfPanel.Notes,
            onPanel = { panel = it },
            scope = scope,
        )
    }

    if (state.ready && state.pageCount <= 0) {
        CpPopup(title = "이 PDF 를 열지 못했습니다", message = "쪽이 하나도 없는 파일입니다.", onDismiss = onClose) {
            Spacer(Modifier.height(16.dp))
            CpButton("라이브러리로", onClose)
        }
    }
}

private enum class PdfPanel { None, Bar, View, Settings, Contents, Notes }

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
) {
    // 크기를 모르면 그리지 않는다. A 판형으로 먼저 그렸다가 가로 쪽으로 바뀌면 한 번 출렁인다. 앞뒤 쪽은
    // 미리 재 두므로 넘길 때는 바로 안다.
    var aspect by remember(page) { mutableStateOf(reader.book.knownAspectRatio(page)) }
    LaunchedEffect(page) { if (aspect == null) aspect = reader.book.pageAspectRatio(page) }
    val pageAspect = aspect ?: return
    var viewport by remember(page, viewW, viewH, pageAspect) { mutableStateOf(PageViewport.fit(viewW, viewH, pageAspect)) }
    val fitW = (viewW.coerceAtMost(viewH * pageAspect)).roundToInt()
    val fitH = (fitW / pageAspect).roundToInt()

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
            val a = reader.book.pageAspectRatio(near)
            val w = (viewW.coerceAtMost(viewH * a)).roundToInt()
            reader.page(near, w, (w / a).roundToInt())
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
            .pointerInput(page, viewW, viewH, pageAspect) {
                detectTapGestures(
                    // 두 번 누르기를 기다리느라 한 번 누르기가 조금(약 0.3초) 늦다. PDF 는 글자가 작아
                    // 확대를 자주 하므로 받아들인다.
                    onDoubleTap = { at -> viewport = viewport.toggleZoom(at.x, at.y) },
                    onTap = { at -> onTap(at, CORNER.toPx()) },
                )
            }
            .pointerInput(page, viewW, viewH, pageAspect) {
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
                            if (zoom != 1f) {
                                val focus = event.calculateCentroid()
                                viewport = viewport.zoom(zoom, focus.x, focus.y)
                            }
                            // 확대돼 있으면 끌기는 쪽 안에서 움직이는 것이다. 넘김으로 읽으면 확대한 곳을
                            // 보려고 끌 때마다 쪽이 넘어간다.
                            if (viewport.isZoomed) viewport = viewport.pan(pan.x, pan.y) else swipe += pan.x
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // 두 손가락으로 줄였다가 전체 크기로 돌아온 것은 넘기려던 게 아니다.
                    if (moving && !pinched && !viewport.isZoomed && abs(swipe) > threshold) onSwipe(swipe < 0)
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
    }
}

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
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(pages, viewW, viewH) {
                detectTapGestures(onTap = { at -> onTap(at, CORNER.toPx()) })
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
    showNotes: Boolean,
    onPanel: (PdfPanel) -> Unit,
    scope: CoroutineScope,
) {
    var contents by remember { mutableStateOf<List<TocEntry>?>(null) }
    var marks by remember { mutableStateOf<List<Bookmark>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        contents = runCatching { reader.outline() }.getOrDefault(emptyList())
        marks = runCatching { reader.bookmarks() }.getOrDefault(emptyList())
    }
    val items = remember(marks, contents) {
        val entries = contents.orEmpty()
        marks?.map { mark ->
            val at = mark.locator.fixedPage
            NoteItem(
                key = "b${mark.id}",
                section = entries.getOrNull(currentContentsIndex(entries, at))?.label ?: "책갈피",
                text = mark.snippet ?: "${reader.book.pageLabel(at) ?: "${at + 1}"}쪽",
                pen = null,
                memo = null,
                where = noteWhere(null, mark.createdAtEpochMs),
            )
        }
    }
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
                    filter = NoteFilter.All,
                    onFilter = {},
                    onOpen = { item ->
                        marks?.firstOrNull { "b${it.id}" == item.key }?.let { mark -> scope.go { reader.goTo(mark) } }
                        onPanel(PdfPanel.None)
                    },
                    onRemove = { item ->
                        marks?.firstOrNull { "b${it.id}" == item.key }?.let { mark ->
                            scope.go { reader.removeBookmark(mark); marks = reader.bookmarks() }
                        }
                    },
                    chips = false,
                    caption = "PDF 는 글자를 고를 수 없어 책갈피만 모입니다",
                    empty = "책갈피가 없습니다. 쪽 오른쪽 위를 누르거나 가운데를 누르고 위쪽 책갈피 단추로 꽂을 수 있습니다",
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
