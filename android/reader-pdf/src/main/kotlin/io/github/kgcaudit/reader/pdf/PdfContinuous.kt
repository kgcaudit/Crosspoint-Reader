package io.github.kgcaudit.reader.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.BOOKMARK_CORNER
import io.github.kgcaudit.reader.ui.design.CpPill
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.LocalPaperImageFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * PDF 이어서 보기(사용자 결정 5-1 · 5-2, 구상안 확정 "권고안대로").
 *
 * 쪽을 화면 폭에 맞춰 위아래로 이어 붙이고 쪽 사이에 틈을 둔다. 보고서 · 논문은 문장이 쪽 끝에서 다음 쪽으로 이어지는데,
 * 폭 맞춤은 쪽마다 끊겨 쪽 끝에서 한 번 넘기고 다시 머리부터 찾아야 했다.
 *
 * - 누름 · 볼륨 키 · 자동 넘김: 한 화면(화면 높이의 90%)씩. 폭 맞춤과 같은 겹침(PageViewport.OVERLAP)이라 화면 끝에 걸린
 *   줄이 다음 화면 머리에 다시 보인다.
 * - 옆으로 밀기: 한 쪽을 넘긴다(다음 · 앞 쪽의 머리로).
 * - 지금 쪽: 화면 **가운데**가 걸친 쪽. 화면 맨 위로 정하면 앞 쪽 끝 한두 줄만 남아 있어도 앞 쪽 번호가 떠, 화면 대부분이
 *   다음 쪽인데 "11쪽" 이라 했다(구상안 1번 그림이 가운데 기준 — 위 절반 가까이 11쪽 끝인데 "12쪽"). 저장하는 자리는
 *   따로 화면 맨 위의 쪽과 그 쪽 안 천분율이다(PdfReader.keepPlace) — 폭 맞춤과 같은 뜻이라 둘 사이를 바꿔도 자리가 같다.
 * - 확대(두 손가락 · 두 번 누르기)는 이 보기에 없다. 기둥 전체를 확대하면 옆으로도 밀어야 해 "옆으로 밀면 한 쪽" 과
 *   부딪친다. 작은 글자는 폭 맞춤에서 확대한다. 두 번 누르기를 받지 않으니 한 번 누르기가 늦지 않다.
 * - 메모리: 그리는 것은 목록이 짓는 쪽(보이는 쪽과 미리 짓는 한 쪽)뿐이다. 1000쪽 PDF 도 쪽마다 그림을 미리 만들지 않고,
 *   쪽 높이는 아직 재지 않은 쪽이면 시작 쪽의 비로 어림했다가 지을 때 잰다.
 */

/** 쪽 사이 틈. 구상안의 14dp — 쪽이 바뀌는 곳이 한눈에 보이면서 글이 끊겨 보이지 않는 폭. */
internal val CONTINUOUS_GAP = 14.dp

/** 한 화면 옮기기 · 한 쪽 넘기기의 손잡이. 화면(PdfScreen)의 누름 · 볼륨 키 · 자동 넘김 · 밀기가 이것을 부른다. */
@androidx.compose.runtime.Stable
internal class ContinuousMover {
    var screen: ((forward: Boolean) -> Unit)? = null
    var page: ((forward: Boolean) -> Unit)? = null

    /**
     * 지금 읽는 자리. 자동 넘김이 이것을 "쪽" 으로 센다 — 쪽 번호로 세면 한 화면 내려가도 쪽이 그대로라 책 끝으로 읽고
     * 멈췄다. 끝에서 더 내려가지 못하면 자리가 그대로라 그때는 멈춘다(쪽 넘김의 책 끝과 같다).
     */
    var spot by mutableStateOf<ContinuousSpot?>(null)
}

/** 쪽 사이 바탕색: 지면과 글자색의 사이. 밝은 지면에서는 회갈색(구상안), 검은 지면에서는 그보다 밝은 회색이라 틈이 보인다. */
@Composable
private fun backdrop(): Color = lerp(CpTheme.colors.paper, CpTheme.colors.ink, 0.3f)

/** 목록의 한 순간에서 읽는 자리: 화면 맨 위가 걸친 쪽과 그 쪽 안 천분율, 화면 가운데가 걸친 쪽. */
internal data class ContinuousSpot(val top: Int, val topPermille: Int, val centre: Int)

/**
 * [info] 에서 자리를 읽는다. 틈에 걸리면 그 아래 쪽 — 틈은 쪽이 아니고, 곧 보일 쪽이 그 아래다. 보이는 쪽이 없으면 null.
 */
internal fun continuousSpot(info: LazyListLayoutInfo): ContinuousSpot? {
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return null
    val height = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    fun at(y: Int) = items.firstOrNull { y < it.offset + it.size } ?: items.last()
    val top = at(0)
    val permille = if (top.offset >= 0 || top.size <= 0) 0 else (-top.offset * 1000L / top.size).toInt().coerceIn(0, 1000)
    return ContinuousSpot(top.index, permille, at(height / 2).index)
}

@Composable
internal fun ContinuousView(
    reader: PdfReader,
    viewW: Float,
    viewH: Float,
    /** 누른 자리와 책갈피 모서리의 크기(px). */
    onTap: (Offset, Float) -> Unit,
    onLongPress: (Offset) -> Unit,
    /** 보이는 쪽들이 화면 어디에 놓였는지(칠 · 고르기 층이 따라 놓인다). 쪽 넘김 보기와 달리 여러 쪽이 함께 보인다. */
    onPlaced: (List<Placed>) -> Unit,
    text: PdfTextState?,
    /** 보이게 옮길 곳(찾은 말 · 듣는 문장): 쪽과 그 쪽 안 구역. */
    focus: Pair<Int, PageRegion>?,
    mover: ContinuousMover,
) {
    val state by reader.state.collectAsState()
    val count = state.pageCount
    if (count <= 0 || viewW <= 0f || viewH <= 0f) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val gap = with(density) { CONTINUOUS_GAP.roundToPx() }
    // 시작 자리: 저장한 자리(화면 맨 위의 쪽, 그 쪽 안 천분율). 그 쪽의 비를 알아야 쪽 안 몇 px 인지 안다 — 모르면 그리지
    // 않는다(PageView 와 같다). 어림한 높이로 시작하면 재고 난 뒤 자리가 미끄러진다.
    val start = remember(reader) { reader.place() }
    var startAspect by remember(reader) { mutableStateOf(reader.book.knownAspectRatio(start.first)) }
    LaunchedEffect(reader) { if (startAspect == null) startAspect = reader.book.pageAspectRatio(start.first) }
    val guess = startAspect ?: return

    fun heightOf(aspect: Float): Float = viewW / aspect
    // 화면 폭이 바뀌면(돌림) 쪽 높이가 모두 바뀐다 — 목록을 새로 만들고 저장한 자리에서 다시 시작한다. 옛 목록의 px 자리를 새
    // 폭에 쓰면 엉뚱한 줄로 간다.
    val list = remember(reader, viewW) {
        val (page, permille) = reader.place()
        val aspect = reader.book.knownAspectRatio(page) ?: guess
        LazyListState(page.coerceIn(0, count - 1), (heightOf(aspect) * permille / 1000f).roundToInt())
    }
    /**
     * 목차 · 찾기 · 듣기가 옮긴 쪽은 사람이 손을 댈 때까지 지금 쪽으로 붙들어 둔다. 붙들지 않으면 그 쪽 머리를 화면 맨 위에
     * 놓았을 때 쪽이 화면 절반보다 짧으면(가로 슬라이드 · 마지막 쪽) 가운데가 다음 쪽이라, 듣기가 읽는 쪽이 아닌 쪽이 지금
     * 쪽이 되고 듣기가 그 쪽으로 건너뛰었다.
     */
    var pinned by remember(reader) { mutableStateOf(false) }
    var appliedJump by remember(reader) { mutableIntStateOf(state.jump) }

    // 쪽을 옮겼다(목차 · 찾기 · 책갈피 · 진행 막대 · 듣기 · 두 쪽 끄기): 저장한 자리로.
    LaunchedEffect(list, state.jump) {
        if (state.jump == appliedJump) return@LaunchedEffect
        val (page, permille) = reader.place()
        val aspect = reader.book.pageAspectRatio(page)
        pinned = true
        list.scrollToItem(page, (heightOf(aspect) * permille / 1000f).roundToInt())
        appliedJump = state.jump
    }

    // 자리 읽기: 지금 쪽(화면 가운데)은 바로, 저장은 손을 멈춘 뒤에.
    LaunchedEffect(list) {
        snapshotFlow { continuousSpot(list.layoutInfo) }.distinctUntilChanged().collectLatest { spot ->
            mover.spot = spot
            if (spot == null || pinned) return@collectLatest
            reader.scrolledTo(spot.centre)
            delay(SETTLE_MS)
            reader.keepPlace(spot.top, spot.topPermille)
        }
    }
    // 칠 · 고르기 층에 보이는 쪽들의 자리를 알린다. 쪽 그림은 화면 폭 그대로 그린다(item 의 offset · size 가 곧 쪽 네모).
    val placed by rememberUpdatedState(onPlaced)
    LaunchedEffect(list) {
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.map { Placed(it.index, Rect(0f, it.offset.toFloat(), viewW, (it.offset + it.size).toFloat())) }
        }.distinctUntilChanged().collect { placed(it) }
    }

    // 찾은 말 · 듣는 문장이 화면 밖이면 그곳을 화면 위에서 3분의 1 쯤에(PageViewport.reveal 과 같은 자리). 쪽 옮기기가 먼저
    // 끝난 뒤에 — 거꾸로 되면 쪽 옮기기가 쪽 머리로 되돌려 찾은 곳이 다시 화면 밖이 됐다.
    LaunchedEffect(list, focus) {
        val (page, region) = focus ?: return@LaunchedEffect
        snapshotFlow { appliedJump == reader.state.value.jump }.first { it }
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page }
        if (item != null && item.offset + region.top * item.size >= 0f && item.offset + region.bottom * item.size <= viewH) return@LaunchedEffect
        val h = heightOf(reader.book.pageAspectRatio(page))
        list.scrollToItem(page, (region.top * h - viewH * 0.3f).roundToInt().coerceAtLeast(0))
    }

    // 한 화면씩: 잇달아 누르면 남은 거리에 더한다 — 새로 시작하면 앞 움직임이 중간에 끊겨 겹침이 어긋난다.
    DisposableEffect(mover, list, viewH, count) {
        var queued = 0f
        var job: Job? = null
        val screen: (Boolean) -> Unit = { forward ->
            pinned = false
            queued += viewH * (1f - PageViewport.OVERLAP) * if (forward) 1f else -1f
            if (job?.isActive != true) {
                job = scope.launch {
                    while (queued != 0f) {
                        val d = queued
                        queued = 0f
                        list.animateScrollBy(d)
                    }
                }
            }
        }
        val page: (Boolean) -> Unit = { forward ->
            pinned = false
            queued = 0f
            job?.cancel()
            val target = (reader.state.value.page + if (forward) 1 else -1).coerceIn(0, count - 1)
            job = scope.launch { list.animateScrollToItem(target) }
        }
        mover.screen = screen
        mover.page = page
        onDispose {
            if (mover.screen === screen) mover.screen = null
            if (mover.page === page) mover.page = null
        }
    }

    val tap by rememberUpdatedState(onTap)
    val pressed by rememberUpdatedState(onLongPress)
    val swipe by rememberUpdatedState(mover)
    val paper = CpTheme.colors.paper
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(backdrop())
            .then(if (text != null) Modifier.selectionHandles(reader, text) else Modifier)
            .pointerInput(list) {
                // 손을 대면 붙들어 둔 쪽을 놓는다 — 이제부터는 사람이 읽는 자리다.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pinned = false
                }
            }
            .pointerInput(list) {
                detectTapGestures(onTap = { at -> tap(at, BOOKMARK_CORNER.toPx()) }, onLongPress = { at -> pressed(at) })
            }
            .pointerInput(list) {
                // 옆으로 밀기 = 한 쪽. 목록이 세로로 굴리기 시작했으면(움직임을 먹었으면) 굴리기다 — 비스듬히 내리다 쪽이
                // 넘어가면 읽던 곳을 잃는다.
                val threshold = 48.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var decided = false
                    var sideways = false
                    var last = down.position
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        val travel = change.position - down.position
                        if (!decided && travel.getDistance() > viewConfiguration.touchSlop) {
                            decided = true
                            sideways = !change.isConsumed && event.changes.size == 1 && abs(travel.x) > abs(travel.y) * 1.5f
                        }
                        if (sideways) change.consume()
                    } while (event.changes.any { it.pressed })
                    val dx = last.x - down.position.x
                    if (sideways && abs(dx) > threshold) swipe.page?.invoke(dx < 0)
                }
            },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            verticalArrangement = Arrangement.spacedBy(with(density) { gap.toDp() }),
        ) {
            items(count, key = { it }) { page ->
                ContinuousPage(reader, page, viewW, viewH, guess, paper)
            }
        }
        PagePosition(list, state.page, count, Modifier.matchParentSize())
    }
}

/** 기둥의 쪽 하나. 비를 모르면 시작 쪽의 비로 자리를 잡고 재는 대로 바로잡는다. 그림이 오기 전에는 지면색 네모. */
@Composable
private fun ContinuousPage(reader: PdfReader, page: Int, viewW: Float, viewH: Float, guess: Float, paper: Color) {
    var aspect by remember(page) { mutableStateOf(reader.book.knownAspectRatio(page)) }
    LaunchedEffect(page) { if (aspect == null) aspect = reader.book.pageAspectRatio(page) }
    val a = aspect ?: guess
    // 그리는 크기는 폭 맞춤의 바탕 그림과 같은 셈(baseSize) — 세로로 아주 긴 쪽도 한 장이 상한을 넘지 않는다.
    val (w, h) = baseSize(PageViewport.fitWidth(viewW, viewH, a))
    var bitmap by remember(page, w, h) { mutableStateOf<Bitmap?>(reader.cachedPage(page, w, h)) }
    var broken by remember(page, w, h) { mutableStateOf(false) }
    // 비를 재기 전에는 그리지 않는다 — 어림한 비로 그리면 재고 나서 한 번 더 그린다.
    LaunchedEffect(page, w, h, aspect != null) {
        if (aspect == null || bitmap != null) return@LaunchedEffect
        val made = reader.page(page, w, h)
        if (made == null) broken = true else bitmap = made
    }
    val heightDp = with(LocalDensity.current) { (viewW / a).toDp() }
    Box(Modifier.fillMaxWidth().height(heightDp).background(paper).semantics { contentDescription = "${page + 1}쪽" }) {
        val imageFilter = LocalPaperImageFilter.current
        Canvas(Modifier.fillMaxSize()) {
            val b = bitmap ?: return@Canvas
            drawImage(
                b.asImageBitmap(),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
                colorFilter = imageFilter,
            )
        }
        if (broken) {
            CpText("이 쪽을 그리지 못했습니다", CpTheme.type.subtitle, CpTheme.colors.inkMuted, Modifier.align(Alignment.Center))
        }
    }
}

/**
 * 굴리는 동안 잠깐 보이는 자리(구상안 1번): 오른쪽 스크롤 막대와 아래 "12쪽 · 240쪽 중". 손을 멈추고 [POSITION_MS] 뒤에
 * 사라진다 — 늘 떠 있으면 쪽 아래 글을 가린다.
 */
@Composable
private fun PagePosition(list: LazyListState, page: Int, count: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    val moving = list.isScrollInProgress
    LaunchedEffect(moving, page) {
        if (moving) {
            visible = true
        } else if (visible) {
            delay(POSITION_MS)
            visible = false
        }
    }
    if (!visible || count <= 1) return
    Box(modifier) {
        val density = LocalDensity.current
        val info = list.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()
        val inner = first?.let { if (it.size > 0) (-it.offset).toFloat() / it.size else 0f }?.coerceIn(0f, 1f) ?: 0f
        val fraction = (((first?.index ?: 0) + inner) / count).coerceIn(0f, 1f)
        val viewH = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        with(density) {
            val bar = 60.dp.toPx().coerceAtMost(viewH / 3f)
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 3.dp)
                    .offset(y = (fraction * (viewH - bar)).toDp())
                    .width(4.dp).height(bar.toDp())
                    .clip(RoundedCornerShape(50)).background(Color(0x88000000)),
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(CpTheme.metrics.cornerMedium))
                .background(CpPill.translucent).padding(horizontal = 12.dp, vertical = 5.dp)
                .semantics { contentDescription = "쪽 위치" },
        ) { CpText(continuousLabel(page, count), CpTheme.type.caption, Color.White) }
    }
}

/** "12쪽 · 240쪽 중". */
internal fun continuousLabel(page: Int, count: Int): String = "${page + 1}쪽 · ${count}쪽 중"
