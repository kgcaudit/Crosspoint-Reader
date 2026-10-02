package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.comic.ComicReading
import io.github.kgcaudit.reader.document.comic.ComicView
import io.github.kgcaudit.reader.document.comic.Webtoon
import io.github.kgcaudit.reader.document.comic.WebtoonColumn
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.comic.WorkEntry
import io.github.kgcaudit.reader.document.image.ImageSize
import io.github.kgcaudit.reader.ui.design.CpBrightnessRow
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpReaderBar
import io.github.kgcaudit.reader.ui.design.CpRibbon
import io.github.kgcaudit.reader.ui.design.CpStepper
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.CpToolButton
import io.github.kgcaudit.reader.ui.design.CpViewSettingsScreen
import io.github.kgcaudit.reader.ui.design.ReadingWindow
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import io.github.kgcaudit.reader.ui.design.TapAction
import io.github.kgcaudit.reader.ui.design.VolumeKeyPaging
import io.github.kgcaudit.reader.ui.design.actionAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * 웹툰 보기(0.35.0, docs/COMIC_PLAN.md C2 · 확정 구상안 ⑤): 그림들을 폭에 맞춰 틈 없이 세로로 잇고 밀어 내린다.
 *
 * 긴 그림은 띠(2048px 이하)로 나눠 화면 가까운 띠만 푼다 — 목록(LazyColumn)이 화면 밖 띠를 내려놓는다. 넘김 효과는
 * 없다(결정 6: 스크롤에는 넘길 쪽이 없다). 누름 구역은 한 화면씩 내린다 — 쪽 넘김과 같은 손짓이 같은 뜻이게.
 */

private enum class WebtoonPanel { None, Bar, View, Settings, Bookmarks }

/** 목록의 한 칸: 그림 [index] 의 원본 행 [rows]. 크기를 모르는 그림은 행 없이 한 칸. */
private class Strip(val index: Int, val rows: IntRange?, val height: Float)

/** 화면을 한 번 누를 때 내리는 양(화면 높이에 대한 비율). 조금 겹쳐야 어디까지 읽었는지 이어진다. */
private const val SCREEN_STEP = 0.85f

@Composable
fun WebtoonReader(
    book: ComicBook,
    sizes: List<ImageSize?>,
    title: String,
    work: Work?,
    startIndex: Int,
    startOffset: Float,
    bookmarks: List<Int>,
    prefs: ScreenPrefs,
    onPrefsChange: (ScreenPrefs) -> Unit,
    view: ComicView?,
    onView: (ComicView?) -> Unit,
    /** 지금 자리: 그림 번호 · 그 안의 비율 · 끝까지 내렸는가. */
    onPosition: (Int, Float, Boolean) -> Unit,
    onBookmark: (Int) -> Unit,
    onNext: (WorkEntry) -> Unit,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
) {
    var panel by remember { mutableStateOf(WebtoonPanel.None) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val next = work?.let { ComicReading.nextAfter(it, book.unit.id) }
    val latestPrefs by rememberUpdatedState(prefs)
    LaunchedEffect(panel) { onChrome(panel != WebtoonPanel.None) }
    fun say(message: String) {
        toast = message
        toastCount++
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(COMIC_BACKDROP)) {
        val density = LocalDensity.current
        val viewW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        val smallest = LocalConfiguration.current.smallestScreenWidthDp
        val colW = prefs.webtoonWidth(viewW, fullH, smallest).roundToInt().coerceAtLeast(1)
        val column = remember(sizes, colW) { WebtoonColumn(sizes, colW.toFloat()) }
        val strips = remember(column) {
            sizes.flatMapIndexed { i, size ->
                if (size == null) {
                    listOf(Strip(i, null, column.heights[i]))
                } else {
                    Webtoon.strips(size, scale = colW.toFloat() / size.width).map { rows -> Strip(i, rows, column.heights[i] * (rows.last - rows.first + 1) / size.height) }
                }
            }
        }
        val stripTops = remember(strips) { strips.runningFold(0f) { acc, s -> acc + s.height } }
        /** 기둥 위에서 [abs] 픽셀 내려온 곳의 (칸 번호, 칸 안 픽셀). */
        fun itemAt(abs: Float): Pair<Int, Int> {
            if (strips.isEmpty()) return 0 to 0
            val i = (stripTops.indexOfLast { it <= abs }).coerceIn(0, strips.size - 1)
            return i to (abs - stripTops[i]).roundToInt().coerceAtLeast(0)
        }
        // 처음 놓을 자리. 폭이 바뀌면(회전 · 기둥 폭) 그때의 자리를 다시 셈한다 — 픽셀이 아니라 그림 · 비율로 두는 까닭.
        var anchor by remember { mutableStateOf(startIndex to startOffset) }
        val list = remember(column) {
            val (i, o) = itemAt(column.offsetOf(anchor.first, anchor.second))
            LazyListState(i, o)
        }
        var viewH by remember { mutableIntStateOf(fullH.roundToInt()) }
        val abs by remember(list, stripTops) {
            derivedStateOf {
                val i = list.firstVisibleItemIndex
                (stripTops.getOrNull(i) ?: column.total) + list.firstVisibleItemScrollOffset
            }
        }
        val readable = (column.total - viewH).coerceAtLeast(1f)
        val percent = (abs / readable).coerceIn(0f, 1f)
        val current = column.at(abs).first
        val bookmarked = current in bookmarks
        ReadingWindow(prefs, activity = current)

        // 자리 적기: 손을 멈춘 뒤에(끄는 동안 매번 적으면 DB 쓰기가 쌓인다).
        LaunchedEffect(list, column) {
            snapshotFlow { abs }.collectLatest { y ->
                delay(SAVE_DELAY_MS)
                val (i, f) = column.at(y)
                anchor = i to f
                onPosition(i, f, y + viewH >= column.total - 1f)
            }
        }
        fun scrollScreen(forward: Boolean) {
            scope.launch { list.animateScrollBy((if (forward) 1 else -1) * viewH * SCREEN_STEP) }
        }
        fun toggleBookmark() {
            say(if (bookmarked) "책갈피를 뺐습니다" else "책갈피를 꽂았습니다")
            onBookmark(current)
        }
        VolumeKeyPaging(enabled = prefs.volumeKeys && panel == WebtoonPanel.None) { forward -> scrollScreen(forward) }
        BackHandler {
            panel = when (panel) {
                WebtoonPanel.None -> { onClose(); WebtoonPanel.None }
                WebtoonPanel.Settings -> WebtoonPanel.View
                WebtoonPanel.Bar -> WebtoonPanel.None
                else -> WebtoonPanel.Bar
            }
        }

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout)
                    .onSizeChanged { viewH = it.height }
                    .semantics { contentDescription = "웹툰 ${(percent * 100).roundToInt()}%" }
                    .pointerInput(viewW) {
                        // 두 번 누르기를 받지 않는다 — 받으면 한 번 누르기가 0.3초 늦다. 웹툰은 폭에 맞아 확대할 일이 드물다.
                        detectTapGestures(onTap = { at ->
                            if (panel != WebtoonPanel.None) { panel = WebtoonPanel.None; return@detectTapGestures }
                            when (latestPrefs.touch.actionAt(at.x, at.y, viewW, 56.dp.toPx())) {
                                TapAction.Next -> scrollScreen(true)
                                TapAction.Previous -> scrollScreen(false)
                                TapAction.Menu -> panel = WebtoonPanel.Bar
                                TapAction.Bookmark -> toggleBookmark()
                            }
                        })
                    },
            ) {
                LazyColumn(Modifier.fillMaxSize(), state = list, horizontalAlignment = Alignment.CenterHorizontally) {
                    itemsIndexed(strips, key = { _, s -> "${s.index}:${s.rows?.first ?: -1}" }) { _, s ->
                        val h = with(density) { s.height.toDp() }
                        val w = with(density) { colW.toDp() }
                        StripView(book, s, colW, Modifier.width(w).height(h))
                    }
                    item(key = "end") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            EndCard(
                                entryLabel = work?.let { ComicReading.entryOf(it, book.unit.id)?.label } ?: title,
                                workTitle = work?.title,
                                next = next,
                                onNext = onNext,
                                onLibrary = onClose,
                            )
                        }
                    }
                }
            }
            ComicFooterLine(title, "${(percent * 100).roundToInt()}%", percent, rtl = false, Modifier.windowInsetsPadding(WindowInsets.displayCutout))
        }
        if (bookmarked) CpRibbon(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(end = 20.dp))
        CpToast(toast, onDone = { toast = null }, Modifier.align(Alignment.BottomCenter), key = toastCount)

        fun seek(fraction: Float) {
            val (i, o) = itemAt(fraction.coerceIn(0f, 1f) * readable)
            scope.launch { list.scrollToItem(i, o) }
        }
        when (panel) {
            WebtoonPanel.None -> Unit
            WebtoonPanel.Bar, WebtoonPanel.View -> CpReaderBar(
                title = title,
                subtitle = "${(percent * 100).roundToInt()}%",
                bookmarked = bookmarked,
                onBookmark = ::toggleBookmark,
                onBack = onClose,
                onDismiss = { panel = WebtoonPanel.None },
                progress = percent,
                progressLabel = { "${(it * 100).roundToInt()}%" },
                onSeek = ::seek,
                above = {
                    if (panel == WebtoonPanel.View) {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            ViewChoice(view, onView)
                            // 기둥 폭은 넓은 화면에서만 뜻이 있다 — 휴대폰 세로는 늘 꽉 채운다(결정 7).
                            if (colW < viewW.roundToInt()) {
                                val step = 10
                                CpStepper(
                                    "기둥 폭", "${prefs.webtoonColumn}%",
                                    onMinus = { onPrefsChange(prefs.copy(webtoonColumn = (prefs.webtoonColumn - step).coerceIn(ScreenPrefs.WEBTOON_COLUMN_RANGE))) },
                                    onPlus = { onPrefsChange(prefs.copy(webtoonColumn = (prefs.webtoonColumn + step).coerceIn(ScreenPrefs.WEBTOON_COLUMN_RANGE))) },
                                )
                            }
                            CpBrightnessRow(prefs.brightness, { onPrefsChange(prefs.copy(brightness = it)) })
                            CpLinkRow("모든 보기 설정", "", { panel = WebtoonPanel.Settings })
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                },
            ) {
                CpToolButton(CpIcons.Bookmark, "책갈피", { panel = WebtoonPanel.Bookmarks })
                CpToolButton(CpIcons.View, "보기", { panel = if (panel == WebtoonPanel.View) WebtoonPanel.Bar else WebtoonPanel.View }, selected = panel == WebtoonPanel.View)
            }
            WebtoonPanel.Settings -> CpViewSettingsScreen(prefs, onPrefsChange, onBack = { panel = WebtoonPanel.View }, pdf = true, highlights = false)
            WebtoonPanel.Bookmarks -> BookmarkList(book, bookmarks, onBack = { panel = WebtoonPanel.Bar }) { i ->
                panel = WebtoonPanel.None
                val (item, o) = itemAt(column.offsetOf(i, 0f))
                scope.launch { list.scrollToItem(item, o) }
            }
        }
    }
}

/** 자리를 적기 전에 기다리는 시간. 밀어 내리는 동안은 적지 않는다. */
private const val SAVE_DELAY_MS = 300L

/** 띠 하나. 풀리기 전에는 바탕만 — 높이는 이미 알아서 목록이 출렁이지 않는다. */
@Composable
private fun StripView(book: ComicBook, strip: Strip, width: Int, modifier: Modifier) {
    val rows = strip.rows
    var bitmap by remember(strip, width) { mutableStateOf<Bitmap?>(rows?.let { book.cachedStrip(strip.index, it, width) }) }
    var broken by remember(strip, width) { mutableStateOf(rows == null || book.isBroken(strip.index)) }
    LaunchedEffect(strip, width) {
        if (rows != null && bitmap == null && !broken) {
            bitmap = book.strip(strip.index, rows, width)
            broken = bitmap == null
        }
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val b = bitmap ?: return@Canvas
            drawImage(
                b.asImageBitmap(),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
            )
        }
        if (broken) CpText("이 그림을 그리지 못했습니다", CpTheme.type.subtitle, COMIC_INK_MUTED, Modifier.align(Alignment.Center))
    }
}
