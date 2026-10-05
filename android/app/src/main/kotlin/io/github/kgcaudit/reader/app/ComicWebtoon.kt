package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.document.comic.ComicProgress
import io.github.kgcaudit.reader.document.comic.ComicReading
import io.github.kgcaudit.reader.document.comic.ComicView
import io.github.kgcaudit.reader.document.comic.Webtoon
import io.github.kgcaudit.reader.document.comic.WebtoonChain
import io.github.kgcaudit.reader.document.comic.CoverCrop
import io.github.kgcaudit.reader.document.comic.WebtoonColumn
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.comic.WorkEntry
import io.github.kgcaudit.reader.document.image.ImageSize
import io.github.kgcaudit.reader.ui.design.CpAutoScrollPill
import io.github.kgcaudit.reader.ui.design.CpBrightnessRow
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpReaderBar
import io.github.kgcaudit.reader.ui.design.CpRibbon
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.CpToolButton
import io.github.kgcaudit.reader.ui.design.CpViewSettingsScreen
import io.github.kgcaudit.reader.ui.design.CpWebtoonWidthRow
import io.github.kgcaudit.reader.ui.design.ReadingWindow
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import io.github.kgcaudit.reader.ui.design.TapAction
import io.github.kgcaudit.reader.ui.design.VolumeKeyPaging
import io.github.kgcaudit.reader.ui.design.actionAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * 웹툰 보기(0.35.0, docs/COMIC_PLAN.md C2 · 확정 구상안 ⑤): 그림들을 폭에 맞춰 틈 없이 세로로 잇고 밀어 내린다.
 *
 * 긴 그림은 띠(2048px 이하)로 나눠 화면 가까운 띠만 푼다 — 목록(LazyColumn)이 화면 밖 띠를 내려놓는다. 넘김 효과는
 * 없다(결정 6: 스크롤에는 넘길 쪽이 없다). 누름 구역은 한 화면씩 내린다 — 쪽 넘김과 같은 손짓이 같은 뜻이게.
 *
 * 0.47.0(확정 구상안 ①~⑪): 다음 화를 그대로 이어 붙이고 사이에 경계 띠를 둔다(③ B) — 몰아 보기가 끊기지 않는다. 화면에
 * 걸린 화가 "지금 화" 다: 아래 줄 · 책갈피 · 진도가 그 화를 따른다. 회차 목록(②) · 자동 스크롤(④) · 두 손가락 확대(⑤).
 */

private enum class WebtoonPanel { None, Bar, View, Settings, Bookmarks, Episodes }

/** 연 화 하나: 책과 그림 크기. [entry] 는 작품을 모르면(낱개 파일) null. */
class WebtoonEpisode(val entry: WorkEntry?, val book: ComicBook, val sizes: List<ImageSize?>) {
    val unitId: String get() = book.unit.id
}

/** 목록의 한 칸. */
private sealed class Cell(val key: String, val height: Float)

/** 화 [episode] 의 그림 [index] 의 원본 행 [rows]. 크기를 모르는 그림은 행 없이 한 칸. */
private class Strip(val episode: WebtoonEpisode, val index: Int, val rows: IntRange?, height: Float) :
    Cell("${episode.unitId}:$index:${rows?.first ?: -1}", height)

/** 화와 화 사이 경계 띠. [after] 는 다음 화(아직 열지 않았을 수도 있다). */
private class Seam(val before: WebtoonEpisode, val after: WorkEntry, height: Float) : Cell("seam:${after.unit.id}", height)

/** 화면을 한 번 누를 때 내리는 양(화면 높이에 대한 비율). 조금 겹쳐야 어디까지 읽었는지 이어진다. */
private const val SCREEN_STEP = 0.85f

/** 경계 띠 높이. 고정이어야 자리 셈(WebtoonChain)과 실제 배치가 맞는다. */
private val SEAM_HEIGHT = 132.dp

/** 확대 한도. 3배면 휴대폰에서 말풍선 작은 글씨가 본문 크기쯤 된다. */
private const val MAX_ZOOM = 3f

/** 이어 붙일 다음 화를 미리 여는 거리(화면 수). 경계 띠에 닿기 전에 열려 있어야 기다림 없이 이어진다. */
private const val PRELOAD_SCREENS = 2f

@Composable
fun WebtoonReader(
    first: WebtoonEpisode,
    /** 작품 이름. 메뉴의 제목이다 — 화는 부제("3화 · 55%")로 간다. */
    title: String,
    work: Work?,
    /** 지금 화(호스트가 아는). 화면이 다른 화로 넘어가면 [onEnter] 로 알린 뒤 이 값이 따라온다. */
    currentId: String,
    startIndex: Int,
    startOffset: Float,
    /** 지금 화의 책갈피. */
    bookmarks: List<Int>,
    /** 회차 목록의 읽은 정도. */
    progress: Map<String, ComicProgress>,
    prefs: ScreenPrefs,
    onPrefsChange: (ScreenPrefs) -> Unit,
    view: ComicView?,
    onView: (ComicView?) -> Unit,
    /** 다음 화를 연다(입출력). 열지 못하면 null — 경계 띠 대신 끝 판에서 "이어서 읽기" 로 다시 시도한다. */
    openEpisode: suspend (WorkEntry) -> WebtoonEpisode?,
    /** 화면에 걸린 화가 바뀌었다. */
    onEnter: (String) -> Unit,
    /** 화 [episode] 의 자리: 그림 번호 · 그 안의 비율 · 끝까지 내렸는가. */
    onPosition: (WebtoonEpisode, Int, Float, Boolean) -> Unit,
    onBookmark: (String, Int) -> Unit,
    /** 이어 붙인 사슬 밖의 화로 건너뛴다(회차 목록 · 끝 판). 호스트가 새로 연다. */
    onJump: (WorkEntry) -> Unit,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    /** 표지로 쓸 장면을 고르러 열었다(서재의 "보던 장면에서 표지 고르기", ⑪). */
    pickCover: Boolean = false,
    /** 고른 부분을 표지로 둔다. 못 두면 false. */
    onCover: suspend (Bitmap) -> Boolean = { false },
) {
    var panel by remember { mutableStateOf(WebtoonPanel.None) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val latestPrefs by rememberUpdatedState(prefs)
    // 표지를 고르는 동안은 상태 줄을 보인다 — 머리("표지로 쓸 부분")가 그 아래에 선다.
    LaunchedEffect(panel, pickCover) { onChrome(pickCover || panel != WebtoonPanel.None) }
    fun say(message: String) {
        toast = message
        toastCount++
    }

    // 이어 붙인 화들. 지나온 화는 둘 앞부터 내려놓는다 — 화마다 풀어 둔 그림을 쥐고 있으면 몇 화 만에 메모리가 넘친다.
    val episodes = remember { mutableStateListOf(first) }
    // 다음 화를 열다 실패했다: 경계 띠 대신 끝 판을 보인다(눌러서 다시 — 호스트가 열고 안 되면 까닭을 말한다).
    var failedNext by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { episodes.filter { it !== first }.forEach { it.book.close() } }
    }
    fun release(e: WebtoonEpisode) {
        // 처음 연 화는 호스트가 닫는다. 여기서는 풀어 둔 그림만 내려놓는다.
        if (e === first) e.book.trim() else e.book.close()
    }
    val nextEntry = work?.let { w -> ComicReading.nextAfter(w, episodes.last().unitId) }

    BoxWithConstraints(Modifier.fillMaxSize().background(COMIC_BACKDROP)) {
        val density = LocalDensity.current
        val viewW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        val smallest = LocalConfiguration.current.smallestScreenWidthDp
        val wide = ScreenPrefs.webtoonWide(viewW, fullH, smallest)
        // 그림 폭 막대를 끄는 동안의 값. 저장은 손을 뗄 때 한 번 — 끄는 내내 저장하면 설정 쓰기가 쌓인다.
        var previewPercent by remember { mutableStateOf<Int?>(null) }
        val percentNow = previewPercent ?: prefs.webtoonPercent(wide)
        val colW = (viewW * percentNow / 100f).roundToInt().coerceAtLeast(1)
        val seamPx = with(density) { SEAM_HEIGHT.toPx() }

        val shown = episodes.toList()
        val columns = remember(shown, colW) { shown.map { WebtoonColumn(it.sizes, colW.toFloat()) } }
        val chain = WebtoonChain(columns, seamPx)
        val cells: List<Cell> = buildList {
            episodes.forEachIndexed { k, e ->
                val column = columns[k]
                e.sizes.forEachIndexed { i, size ->
                    if (size == null) {
                        add(Strip(e, i, null, column.heights[i]))
                    } else {
                        Webtoon.strips(size, scale = colW.toFloat() / size.width).forEach { rows ->
                            add(Strip(e, i, rows, column.heights[i] * (rows.last - rows.first + 1) / size.height))
                        }
                    }
                }
                val after = episodes.getOrNull(k + 1)?.entry ?: if (k == episodes.size - 1 && failedNext == null) nextEntry else null
                if (after != null) add(Seam(e, after, seamPx))
            }
        }
        val cellTops = cells.runningFold(0f) { acc, c -> acc + c.height }
        /** 기둥 위에서 [abs] 픽셀 내려온 곳의 (칸 번호, 칸 안 픽셀). */
        fun itemAt(abs: Float): Pair<Int, Int> {
            if (cells.isEmpty()) return 0 to 0
            val i = (cellTops.indexOfLast { it <= abs }).coerceIn(0, cells.size - 1)
            return i to (abs - cellTops[i]).roundToInt().coerceAtLeast(0)
        }
        // 놓을 자리: (화, 그림, 비율). 폭이 바뀌면(회전 · 그림 폭) 그때의 자리를 다시 셈한다 — 픽셀이 아니라 그림 · 비율로 둔다.
        var anchor by remember { mutableStateOf(Triple(first.unitId, startIndex, startOffset)) }
        val list = remember(colW) {
            val k = episodes.indexOfFirst { it.unitId == anchor.first }.coerceAtLeast(0)
            val (i, o) = itemAt(chain.offsetOf(k, anchor.second, anchor.third))
            LazyListState(i, o)
        }
        var viewH by remember { mutableIntStateOf(fullH.roundToInt()) }
        val latestTops by rememberUpdatedState(cellTops)
        val abs by remember(list) {
            derivedStateOf {
                val tops = latestTops
                (tops.getOrNull(list.firstVisibleItemIndex) ?: tops.last()) + list.firstVisibleItemScrollOffset
            }
        }
        val (k, current, _) = chain.at(abs)
        val here = episodes.getOrElse(k) { episodes.last() }
        val percent = chain.fraction(abs, viewH.toFloat())
        val bookmarked = here.unitId == currentId && current in bookmarks
        val hereLabel = here.entry?.label
        ReadingWindow(prefs, activity = here.unitId to current)

        // 화면에 걸린 화가 바뀌면 알린다 — 아래 줄 · 책갈피 · 이어 보기가 그 화를 따른다.
        LaunchedEffect(here.unitId) { if (here.unitId != currentId) onEnter(here.unitId) }

        // 다음 화를 미리 연다: 화면 아래가 사슬 끝 [PRELOAD_SCREENS] 화면 안으로 들어오면.
        val latestChainTotal by rememberUpdatedState(chain.total)
        LaunchedEffect(list, nextEntry?.unit?.id) {
            val next = nextEntry ?: return@LaunchedEffect
            snapshotFlow { abs + viewH * PRELOAD_SCREENS >= latestChainTotal }.distinctUntilChanged().collect { near ->
                if (!near || loading != null || failedNext != null || episodes.any { it.unitId == next.unit.id }) return@collect
                loading = next.unit.id
                // 취소(회전 · 그림 폭 바꾸기로 이 효과가 다시 시작)는 실패가 아니다 — 실패로 적으면 경계 띠 대신 끝 판이 나왔다.
                val opened = try {
                    openEpisode(next)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    loading = null
                    throw e
                } catch (e: Exception) {
                    null
                }
                loading = null
                if (opened == null) failedNext = next.unit.id else episodes.add(opened)
            }
        }

        // 자리 적기: 손을 멈춘 뒤에(끄는 동안 매번 적으면 DB 쓰기가 쌓인다). 지나온 화는 다 읽은 것으로.
        val finishedSaved = remember { mutableSetOf<String>() }
        val onPositionNow by rememberUpdatedState(onPosition)
        LaunchedEffect(list) {
            snapshotFlow { abs }.collectLatest { y ->
                delay(SAVE_DELAY_MS)
                val c = WebtoonChain(episodes.map { WebtoonColumn(it.sizes, colW.toFloat()) }, seamPx)
                val (ek, i, f) = c.at(y)
                val e = episodes.getOrNull(ek) ?: return@collectLatest
                anchor = Triple(e.unitId, i, f)
                onPositionNow(e, i, f, c.reachedEnd(ek, y, viewH.toFloat()))
                for (j in 0 until ek) {
                    val before = episodes[j]
                    if (finishedSaved.add(before.unitId)) onPositionNow(before, (before.sizes.size - 1).coerceAtLeast(0), 1f, true)
                }
                // 둘 앞의 화는 내려놓는다. 목록은 칸 열쇠로 첫 칸을 붙잡으므로 위의 칸이 빠져도 화면이 튀지 않는다.
                while (episodes.indexOf(e) >= 2) release(episodes.removeAt(0))
            }
        }

        // 확대: 두 손가락으로만(⑤). 손을 떼도 그 배율로 내려 읽는다.
        var zoom by remember { mutableFloatStateOf(1f) }
        var panX by remember { mutableFloatStateOf(0f) }
        var zoomTick by remember { mutableIntStateOf(0) }
        var zoomLabel by remember { mutableStateOf(false) }
        LaunchedEffect(zoomTick) {
            if (zoomTick == 0) return@LaunchedEffect
            zoomLabel = true
            delay(1_000)
            zoomLabel = false
        }

        // 표지 틀(⑪): 화면 좌표의 가운데와 폭. 높이는 표지 비율. 처음엔 기둥 폭의 4/5 를 화면 가운데에.
        var frameW by remember { mutableFloatStateOf(0f) }
        var frameX by remember { mutableFloatStateOf(0f) }
        var frameY by remember { mutableFloatStateOf(0f) }
        var saving by remember { mutableStateOf(false) }
        // 화면이 바뀌면(회전 · 그림 폭) 틀을 새 화면 안으로 다시 넣는다. 처음 크기 그대로 두었더니 가로로 돌린 뒤 틀이 화면보다
        // 커져, 끌면 범위 셈(min > max)이 무너져 앱이 닫혔다.
        LaunchedEffect(pickCover, viewH, colW) {
            if (!pickCover) return@LaunchedEffect
            val most = minOf(colW.toFloat(), viewH / CoverCrop.ASPECT)
            if (frameW <= 0f) {
                frameW = minOf(colW * 0.8f, viewH * 0.7f / CoverCrop.ASPECT)
                frameX = viewW / 2
                frameY = viewH * 0.45f
            }
            frameW = frameW.coerceAtMost(most)
            frameX = frameX.within(frameW / 2, viewW - frameW / 2)
            frameY = frameY.within(frameW * CoverCrop.ASPECT / 2, viewH - frameW * CoverCrop.ASPECT / 2)
        }
        fun frameRect(): androidx.compose.ui.geometry.Rect =
            androidx.compose.ui.geometry.Rect(
                androidx.compose.ui.geometry.Offset(frameX - frameW / 2, frameY - frameW * CoverCrop.ASPECT / 2),
                androidx.compose.ui.geometry.Size(frameW, frameW * CoverCrop.ASPECT),
            )

        /** 틀 안의 그림을 원본에서 잘라 표지로. 틀 위쪽이 걸친 그림 하나 안에서 자른다 — 두 그림에 걸치면 아래 그림은 버린다. */
        suspend fun saveFrame(): Boolean {
            val r = frameRect()
            val top = abs + r.top / zoom
            val (ek, i, f) = chain.at(top)
            val e = episodes.getOrNull(ek) ?: return false
            val size = e.sizes.getOrNull(i) ?: return false
            val colLeft = (viewW - colW) / 2f
            val h = columns[ek].heights[i]
            val scale = size.width / colW.toFloat()
            val x0 = (((r.left - panX) / zoom - colLeft) * scale).toInt().coerceIn(0, size.width - 1)
            val x1 = (((r.right - panX) / zoom - colLeft) * scale).toInt().coerceIn(x0 + 1, size.width)
            val y0 = (f * size.height).toInt().coerceIn(0, size.height - 1)
            val y1 = (y0 + r.height / zoom / h * size.height).toInt().coerceIn(y0 + 1, size.height)
            val bitmap = e.book.crop(i, android.graphics.Rect(x0, y0, x1, y1), CoverStore.COVER_HEIGHT) ?: return false
            return onCover(bitmap)
        }

        // 자동 스크롤(④): 켜고 끄기는 그때그때, 빠르기는 설정에 둔다. 손을 대면 멈춘다 — 다시 이어 가는 것은 ▶.
        var autoOn by remember { mutableStateOf(false) }
        var autoPaused by remember { mutableStateOf(false) }
        // 이번 손짓이 자동 스크롤을 멈췄다: 그 누르기는 멈춤으로만 쓴다. 넘김까지 하면 멈추려고 누른 손에 한 화면이 더 내려가
        // 읽던 칸을 놓친다.
        var stoppedByTouch by remember { mutableStateOf(false) }
        LaunchedEffect(autoOn, autoPaused, panel, prefs.webtoonAutoSpeed, viewH) {
            if (!autoOn || autoPaused || panel != WebtoonPanel.None) return@LaunchedEffect
            val perNano = viewH / (prefs.webtoonAutoSpeed.secondsPerScreen * 1_000_000_000f)
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val want = perNano * (now - last)
                last = now
                list.scrollBy(want)
                // 끝(마지막 화의 끝 판)에 닿으면 끈다 — 알약이 남아 "자동 스크롤" 이라고 하는데 서 있으면 고장으로 보인다.
                if (!list.canScrollForward) {
                    autoOn = false
                    break
                }
            }
        }

        fun scrollScreen(forward: Boolean) {
            scope.launch { list.animateScrollBy((if (forward) 1 else -1) * viewH / zoom * SCREEN_STEP) }
        }
        fun toggleBookmark() {
            say(if (bookmarked) "책갈피를 뺐습니다" else "책갈피를 꽂았습니다")
            onBookmark(here.unitId, current)
        }
        // 손짓 처리기는 한 번 만들어 계속 쓴다 — 그 안에서 지금 값(목록 · 지금 그림 · 기둥 폭)을 바로 읽으면 처음 값에 묶인다.
        // 그림 폭을 바꾼 뒤 누름 넘김이 버려진 옛 목록을 굴려 아무 일도 없었고, 책갈피 구석은 처음 본 그림에 꽂혔다.
        val scrollNow by rememberUpdatedState<(Boolean) -> Unit> { forward -> scrollScreen(forward) }
        val toggleNow by rememberUpdatedState<() -> Unit> { toggleBookmark() }
        val listNow by rememberUpdatedState(list)
        val colNow by rememberUpdatedState(colW)
        fun goTo(episode: Int, index: Int) {
            val (item, o) = itemAt(chain.offsetOf(episode, index, 0f))
            scope.launch { list.scrollToItem(item, o) }
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
                    .clipToBounds()
                    .onSizeChanged { viewH = it.height }
                    .semantics { contentDescription = "웹툰 ${(percent * 100).roundToInt()}%" }
                    .pointerInput(Unit) {
                        // 두 손가락: 확대 · 옆으로 옮기기. 한 손가락은 건드리지 않는다(목록이 밀어 내린다). 아무 손가락이든 닿으면
                        // 자동 스크롤을 멈춘다.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            stoppedByTouch = autoOn && !autoPaused && panel == WebtoonPanel.None
                            if (stoppedByTouch) autoPaused = true
                            // 표지 틀 안을 누르면 틀을 옮긴다. 틀 밖은 그대로 그림을 밀어 내린다 — 틀을 놓을 장면을 찾는다.
                            val onFrame = pickCover && frameRect().contains(down.position)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (pickCover && event.changes.count { it.pressed } >= 2) {
                                    // 표지를 고르는 동안 두 손가락은 틀 크기다(그림 확대가 아니다).
                                    frameW = (frameW * event.calculateZoom()).within(colNow * 0.25f, minOf(colNow.toFloat(), size.height / CoverCrop.ASPECT))
                                    event.changes.forEach { it.consume() }
                                } else if (onFrame) {
                                    event.changes.firstOrNull()?.let { c ->
                                        val d = c.position - c.previousPosition
                                        val half = frameW * CoverCrop.ASPECT / 2
                                        frameX = (frameX + d.x).within(frameW / 2, size.width - frameW / 2)
                                        frameY = (frameY + d.y).within(half, size.height - half)
                                        c.consume()
                                    }
                                } else if (event.changes.count { it.pressed } >= 2) {
                                    val before = zoom
                                    val after = (before * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                    val focus = event.calculateCentroid()
                                    val pan = event.calculatePan()
                                    // 손가락 사이의 칸이 손가락 아래에 머물게: 세로는 목록을 그만큼 굴리고, 가로는 옮김값을 고친다.
                                    listNow.dispatchRawDelta(focus.y / before - focus.y / after - pan.y / after)
                                    panX = (focus.x - (focus.x - panX) / before * after + pan.x).coerceIn(size.width * (1 - after), 0f)
                                    zoom = after
                                    zoomTick++
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (zoom < 1.05f) {
                                zoom = 1f
                                panX = 0f
                            }
                        }
                    }
                    .pointerInput(viewW) {
                        // 두 번 누르기를 받지 않는다 — 받으면 한 번 누르기가 0.3초 늦다. 확대는 두 손가락으로(⑤).
                        detectTapGestures(onTap = { at ->
                            if (pickCover) return@detectTapGestures
                            if (panel != WebtoonPanel.None) { panel = WebtoonPanel.None; return@detectTapGestures }
                            if (stoppedByTouch) { stoppedByTouch = false; return@detectTapGestures }
                            when (latestPrefs.touch.actionAt(at.x, at.y, viewW, 56.dp.toPx())) {
                                TapAction.Next -> scrollNow(true)
                                TapAction.Previous -> scrollNow(false)
                                TapAction.Menu -> panel = WebtoonPanel.Bar
                                TapAction.Bookmark -> toggleNow()
                            }
                        })
                    },
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                    state = list,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(cells, key = { it.key }) { c ->
                        val h = with(density) { c.height.toDp() }
                        when (c) {
                            is Strip -> StripView(c.episode.book, c, colW, Modifier.width(with(density) { colW.toDp() }).height(h))
                            is Seam -> SeamView(c.before.entry?.label, c.after, work, Modifier.fillMaxWidth().height(h))
                        }
                    }
                    if (nextEntry == null || failedNext != null) {
                        item(key = "end") {
                            val last = episodes.last()
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                EndCard(
                                    entryLabel = last.entry?.label ?: title,
                                    workTitle = work?.title,
                                    next = nextEntry,
                                    onNext = { failedNext = null; onJump(it) },
                                    onLibrary = onClose,
                                )
                            }
                        }
                    }
                }
                if (zoomLabel && zoom > 1f) {
                    CpText(
                        "${"%.1f".format(zoom)}배", CpTheme.type.label, Color.White,
                        Modifier.align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(CpTheme.metrics.cornerMedium))
                            .background(Color(0xCC1C1A17)).padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            ComicFooterLine(
                title, hereLabel, "${(percent * 100).roundToInt()}%", percent,
                rtl = false, footer = prefs.footer, modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout),
            )
        }
        if (pickCover) {
            // 틀 밖은 어둡게. 머리와 단추는 화면 위아래에 — 아래 줄 · 책갈피 띠는 가린다(고르는 중에 쓸 일이 없다).
            Canvas(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                val r = frameRect()
                val dim = Color(0x99000000)
                drawRect(dim, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(size.width, r.top))
                drawRect(dim, androidx.compose.ui.geometry.Offset(0f, r.bottom), androidx.compose.ui.geometry.Size(size.width, size.height - r.bottom))
                drawRect(dim, androidx.compose.ui.geometry.Offset(0f, r.top), androidx.compose.ui.geometry.Size(r.left, r.height))
                drawRect(dim, androidx.compose.ui.geometry.Offset(r.right, r.top), androidx.compose.ui.geometry.Size(size.width - r.right, r.height))
                drawRect(Color.White, r.topLeft, r.size, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.background(CpTheme.colors.surface).windowInsetsPadding(WindowInsets.statusBars)) {
                    CpHeader("표지로 쓸 부분", subtitle = "틀을 끌어 옮기고, 두 손가락으로 크기를 바꿉니다", onBack = onClose)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.fillMaxWidth().background(CpTheme.colors.surface).windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CpButton(if (saving) "저장하는 중…" else "이 부분을 표지로", {
                        if (saving) return@CpButton
                        saving = true
                        scope.launch {
                            val ok = saveFrame()
                            saving = false
                            if (ok) onClose() else say("이 부분을 표지로 쓸 수 없습니다")
                        }
                    })
                }
            }
        }
        if (bookmarked && !pickCover) CpRibbon(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(end = 20.dp))
        if (autoOn && panel == WebtoonPanel.None) {
            CpAutoScrollPill(
                prefs.webtoonAutoSpeed, autoPaused,
                onSpeed = { onPrefsChange(prefs.copy(webtoonAutoSpeed = it)) },
                onPause = { autoPaused = !autoPaused },
                onClose = { autoOn = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
            )
        }
        CpToast(toast, onDone = { toast = null }, Modifier.align(Alignment.BottomCenter), key = toastCount)

        fun seek(fraction: Float) {
            val y = chain.starts.getOrElse(k) { 0f } + fraction.coerceIn(0f, 1f) * chain.readable(k.coerceIn(0, columns.size - 1), viewH.toFloat())
            val (i, o) = itemAt(y)
            scope.launch { list.scrollToItem(i, o) }
        }
        val subtitle = listOfNotNull(hereLabel, "${(percent * 100).roundToInt()}%").joinToString(" · ")
        when (panel) {
            WebtoonPanel.None -> Unit
            WebtoonPanel.Bar, WebtoonPanel.View -> CpReaderBar(
                title = title,
                subtitle = subtitle,
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
                            // 그림 폭은 모든 화면에서 바꾼다(0.47.0, 사용자 결정 ⑧) — 휴대폰에서도 꽉 채우면 컷이 커져 웹툰의
                            // 느낌이 옅어지는 기기가 있다. 값은 화면 등급(좁음 · 넓음)마다 따로 둔다.
                            CpWebtoonWidthRow(
                                percentNow,
                                onPreview = { previewPercent = it },
                                onCommit = {
                                    previewPercent = null
                                    onPrefsChange(prefs.withWebtoonPercent(wide, it))
                                },
                            )
                            CpBrightnessRow(prefs.brightness, { onPrefsChange(prefs.copy(brightness = it)) })
                            CpLinkRow("모든 보기 설정", "", { panel = WebtoonPanel.Settings })
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                },
            ) {
                if (work != null) CpToolButton(CpIcons.Toc, "회차", { panel = WebtoonPanel.Episodes })
                CpToolButton(CpIcons.Bookmark, "책갈피", { panel = WebtoonPanel.Bookmarks })
                CpToolButton(CpIcons.Play, "자동", {
                    autoOn = true
                    autoPaused = false
                    panel = WebtoonPanel.None
                })
                CpToolButton(CpIcons.View, "보기", { panel = if (panel == WebtoonPanel.View) WebtoonPanel.Bar else WebtoonPanel.View }, selected = panel == WebtoonPanel.View)
            }
            WebtoonPanel.Settings -> CpViewSettingsScreen(
                prefs, onPrefsChange, onBack = { panel = WebtoonPanel.View }, highlights = false,
                reader = io.github.kgcaudit.reader.ui.design.CpReaderKind.Webtoon,
            )
            WebtoonPanel.Bookmarks -> BookmarkList(here.book, if (here.unitId == currentId) bookmarks else emptyList(), onBack = { panel = WebtoonPanel.Bar }) { i ->
                panel = WebtoonPanel.None
                goTo(k, i)
            }
            WebtoonPanel.Episodes -> if (work != null) EpisodeList(work, here.unitId, progress, onBack = { panel = WebtoonPanel.Bar }) { entry ->
                panel = WebtoonPanel.None
                val loaded = episodes.indexOfFirst { it.unitId == entry.unit.id }
                if (loaded >= 0) goTo(loaded, 0) else onJump(entry)
            }
        }
    }
}

/** [min]~[max] 안으로. 범위가 뒤집히면(틀이 화면보다 큼) 가운데 — coerceIn 은 이때 예외를 던져 앱이 닫힌다. */
private fun Float.within(min: Float, max: Float): Float = if (max < min) (min + max) / 2 else coerceIn(min, max)

/** 자리를 적기 전에 기다리는 시간. 밀어 내리는 동안은 적지 않는다. */
private const val SAVE_DELAY_MS = 300L

/**
 * 화와 화 사이 경계 띠(③ B): "3화 끝 ─ 4화 · 전학생 · 48화 가운데 4번째". 그림 사이에 끼는 얇은 띠라 몰아 보기가 끊기지
 * 않으면서도, 어디서 화가 바뀌었는지는 보인다.
 */
@Composable
private fun SeamView(beforeLabel: String?, after: WorkEntry, work: Work?, modifier: Modifier) {
    Column(modifier.background(COMIC_BACKDROP), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        beforeLabel?.let { CpText("$it 끝", CpTheme.type.caption, COMIC_INK_MUTED) }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(48.dp).height(1.dp).background(Color(0xFF5A544D)))
        Spacer(Modifier.height(10.dp))
        CpText(after.label, CpTheme.type.title, Color.White)
        if (work != null) {
            val n = work.entries.indexOfFirst { it.unit.id == after.unit.id } + 1
            CpText("${work.title} · ${work.volumeCount}화 가운데 ${n}번째", CpTheme.type.caption, COMIC_INK_MUTED)
        }
    }
}

/**
 * 회차 목록(②): 화마다 첫 칸 그림 · 읽은 정도. 지금 화를 칠한다. 화는 모두 같은 급이라 들이지 않는다(UI 규칙 1).
 */
@Composable
private fun EpisodeList(work: Work, currentId: String, progress: Map<String, ComicProgress>, onBack: () -> Unit, onPick: (WorkEntry) -> Unit) {
    fun isHere(e: WorkEntry) = e.unit.id == currentId || e.copies.any { it.id == currentId }
    val here = work.entries.indexOfFirst(::isHere).coerceAtLeast(0)
    // 지금 화가 목록 위에서 셋째 줄쯤 — 앞뒤 화가 함께 보인다.
    val state = rememberLazyListState((here - 2).coerceAtLeast(0))
    CpFullScreen {
        CpHeader("회차", subtitle = "${work.title} · ${work.volumeCount}화", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(work.entries, key = { it.slot }) { e ->
                val p = (listOf(e.unit) + e.copies).mapNotNull { progress[it.id] }.maxByOrNull { it.updatedAtEpochMs }
                CpListRow(
                    e.label, { onPick(e) },
                    value = when {
                        p == null -> null
                        p.finished -> "다 읽음"
                        else -> "${(p.fraction * 100).roundToInt()}%"
                    },
                    selected = isHere(e),
                    compact = true,
                    leading = { EpisodeThumb(e) },
                )
            }
        }
    }
}

/** 회차의 작은 그림: 표지(첫 칸을 자른 것)의 위쪽. 그림이 없으면 바탕만. */
@Composable
private fun EpisodeThumb(entry: WorkEntry) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.size(40.dp, 30.dp).clip(shape).background(CpTheme.colors.tiles.archive).border(1.dp, CpTheme.colors.divider, shape)) {
        rememberComicCover(entry.unit)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) }
    }
}

/** 띠 하나. 풀리기 전에는 바탕만 — 높이는 이미 알아서 목록이 출렁이지 않는다. */
@Composable
private fun StripView(book: ComicBook, strip: Strip, width: Int, modifier: Modifier) {
    val rows = strip.rows
    var bitmap by remember(strip.key, width) { mutableStateOf<Bitmap?>(rows?.let { book.cachedStrip(strip.index, it, width) }) }
    var broken by remember(strip.key, width) { mutableStateOf(rows == null || book.isBroken(strip.index)) }
    LaunchedEffect(strip.key, width) {
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
