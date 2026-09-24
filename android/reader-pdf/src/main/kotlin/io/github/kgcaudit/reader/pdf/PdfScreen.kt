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
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpReaderBar
import io.github.kgcaudit.reader.ui.design.CpStatusBar
import io.github.kgcaudit.reader.ui.design.CpTabBar
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToolButton
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
fun PdfScreen(reader: PdfReader, onClose: () -> Unit, onChrome: (Boolean) -> Unit) {
    val state by reader.state.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = CpTheme.colors
    var panel by remember { mutableStateOf(PdfPanel.None) }

    LaunchedEffect(panel) { onChrome(panel != PdfPanel.None) }
    BackHandler {
        panel = when (panel) {
            PdfPanel.None -> { onClose(); PdfPanel.None }
            PdfPanel.Contents, PdfPanel.Bookmarks -> PdfPanel.Bar
            PdfPanel.Bar -> PdfPanel.None
        }
    }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        // 상태 막대 자리를 뺀 곳에 쪽을 놓는다. 겹치면 세로로 긴 쪽의 마지막 줄이 막대에 가린다.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout)) {
            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()
            if (state.ready && state.pageCount > 0 && viewW > 0f && viewH > 0f) {
                PageView(
                    reader = reader,
                    page = state.page,
                    viewW = viewW,
                    viewH = viewH,
                    onTap = { x ->
                        when {
                            panel != PdfPanel.None -> panel = PdfPanel.None
                            x < viewW * 0.3f -> scope.go { reader.previous() }
                            x > viewW * 0.7f -> scope.go { reader.next() }
                            else -> panel = PdfPanel.Bar
                        }
                    },
                    onSwipe = { forward -> scope.go { if (forward) reader.next() else reader.previous() } },
                )
            }
        }
        CpStatusBar(
            title = reader.title,
            // 인쇄된 쪽 번호가 파일 순서와 다르면(로마 숫자 머리말 등) 앞에 함께 적는다: "iv · 4 / 230".
            page = if (state.pageCount > 0) {
                val position = "${state.page + 1} / ${state.pageCount}"
                reader.book.pageLabel(state.page)?.let { "$it · $position" } ?: position
            } else {
                ""
            },
            percent = "${state.percent.roundToInt()}%",
            progress = state.percent / 100f,
            color = colors.inkMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp).windowInsetsPadding(WindowInsets.displayCutout),
        )
    }

    when (panel) {
        PdfPanel.None -> Unit
        PdfPanel.Bar -> CpReaderBar(
            title = reader.title,
            subtitle = "${state.page + 1} / ${state.pageCount} 쪽",
            bookmarked = state.bookmarked,
            onBookmark = { scope.go { reader.toggleBookmark() } },
            onBack = onClose,
            onDismiss = { panel = PdfPanel.None },
            progress = if (state.pageCount > 1) state.page / (state.pageCount - 1f) else 1f,
            progressLabel = { pageAt(it, state.pageCount).let { p -> "${reader.book.pageLabel(p) ?: (p + 1)}쪽" } },
            onSeek = { target -> scope.go { reader.seek(target) } },
        ) {
            // EPUB 리더와 같은 자리 · 같은 순서. 목차가 없는 PDF 도 단추는 둔다 — 열면 "목차가 없는
            // 파일입니다" 라고 말해 준다(책마다 단추가 생겼다 없어졌다 하면 손이 헤맨다).
            CpToolButton(CpIcons.Toc, "목차", { panel = PdfPanel.Contents })
            CpToolButton(CpIcons.Bookmark, "책갈피", { panel = PdfPanel.Bookmarks })
        }
        PdfPanel.Contents, PdfPanel.Bookmarks -> PdfLists(
            reader = reader,
            page = state.page,
            showBookmarks = panel == PdfPanel.Bookmarks,
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

private enum class PdfPanel { None, Bar, Contents, Bookmarks }

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
    onTap: (Float) -> Unit,
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
                    onTap = { at -> onTap(at.x) },
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

/** 목차·책갈피 전체 화면. EPUB 리더의 것과 같은 모양이다(탭 두 개, 지금 위치에 불). */
@Composable
private fun PdfLists(
    reader: PdfReader,
    page: Int,
    showBookmarks: Boolean,
    onPanel: (PdfPanel) -> Unit,
    scope: CoroutineScope,
) {
    var contents by remember { mutableStateOf<List<TocEntry>?>(null) }
    var marks by remember { mutableStateOf<List<Bookmark>?>(null) }
    LaunchedEffect(Unit) { contents = runCatching { reader.outline() }.getOrDefault(emptyList()) }
    LaunchedEffect(showBookmarks) {
        if (showBookmarks) marks = runCatching { reader.bookmarks() }.getOrDefault(emptyList())
    }
    CpFullScreen {
        CpHeader(title = reader.title, onBack = { onPanel(PdfPanel.Bar) })
        CpTabBar(
            listOf("목차", "책갈피"),
            if (showBookmarks) 1 else 0,
            { onPanel(if (it == 1) PdfPanel.Bookmarks else PdfPanel.Contents) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showBookmarks) {
                BookmarkList(
                    marks,
                    onOpen = { mark -> scope.go { reader.goTo(mark) }; onPanel(PdfPanel.None) },
                    onRemove = { mark -> scope.go { reader.removeBookmark(mark); marks = reader.bookmarks() } },
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
                        modifier = Modifier.padding(start = (entry.depth * 16).dp),
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
private fun BookmarkList(marks: List<Bookmark>?, onOpen: (Bookmark) -> Unit, onRemove: (Bookmark) -> Unit) {
    when {
        marks == null -> Unit
        marks.isEmpty() -> Empty("책갈피가 없습니다. 쪽 가운데를 누르고 위쪽 책갈피 단추로 꽂을 수 있습니다")
        // 보관소가 쪽 순서로 준다(BookmarkRepository.forBook).
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(marks, key = { it.id }) { mark ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpListRow(
                        title = mark.snippet ?: "${mark.locator.fixedPage + 1}쪽",
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
