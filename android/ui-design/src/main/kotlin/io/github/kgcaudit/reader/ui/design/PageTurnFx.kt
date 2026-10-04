package io.github.kgcaudit.reader.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── 넘김 효과(E7 · 0.32.0: 말림 · 덮기 · 소리 · 진동) ──────────────────────────

/**
 * 넘김 효과를 낼지 정하는 신호. 화면이 사람의 넘김(누르기 · 밀기 · 음량 단추 · 자동 넘김)마다 [request] 를 부르고, 쪽 번호가
 * 바뀐 것이 그 요청 직후일 때만 효과 · 소리를 낸다. 글자 크기를 바꿔 다시 짠 쪽, 회전, 목차 · 책갈피로 건너뛴 쪽에는 내지
 * 않는다 — 0.31 까지는 쪽 열쇠가 바뀌면 무엇이든 밀거나 사라지게 했고, 소리를 붙이면 회전할 때마다 바스락거렸을 것이다.
 *
 * 끌기(손가락을 따라 말림)는 [dragStart] → [drag] → [dragEnd]. 화면은 끌기가 시작되면 곧바로 쪽을 넘기고(그래야 아래에
 * 다음 쪽을 그릴 수 있다), 덜 끌고 놓으면 [dragEnd] 의 되돌리기로 앞 쪽으로 돌아간다.
 */
@Stable
class PageTurnState {
    internal var requestedAt = 0L
    internal var requestQuiet = false

    internal var dragging by mutableStateOf(false)
    internal var dragProgress by mutableFloatStateOf(0f)
    /** 끄는 동안 쪽이 실제로 바뀌었다 — 책 끝에서 끌면 바뀌지 않으니, 그때 되돌리면 한 쪽 앞으로 가 버린다. */
    internal var dragTurned = false
    /** 손을 뗐다: true 면 끝까지, false 면 되돌림. null 이면 아직 끄는 중. */
    internal var released by mutableStateOf<Boolean?>(null)
    internal var revert: (() -> Unit)? = null
    internal var revertOnArrivalUntil = 0L

    /** 사람이 넘겼다. [quiet] 면 효과만 — 소리 · 진동 없이(자동 넘김: 손을 대지 않았는데 소리가 나면 놀란다). */
    fun request(quiet: Boolean = false) {
        requestedAt = now()
        requestQuiet = quiet
    }

    fun dragStart() {
        dragging = true
        dragProgress = 0f
        dragTurned = false
        released = null
        revert = null
    }

    /** 끈 정도(0 → 1, 화면 폭에 대한 비율). */
    fun drag(progress: Float) {
        if (dragging) dragProgress = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    }

    /** 손을 뗐다. [complete] 면 끝까지 넘기고, 아니면 [revert] 로 앞 쪽으로 되돌린다(이미 넘어갔을 때만). */
    fun dragEnd(complete: Boolean, revert: () -> Unit) {
        if (!dragging) return
        dragging = false
        this.revert = revert
        if (!dragTurned) {
            // 쪽이 아직 바뀌지 않았다(조판이 늦다 · 책 끝). 끝까지면 도착할 때 저절로 넘기고, 되돌림이면 도착하는 대로 되돌린다.
            if (complete) request() else revertOnArrivalUntil = now() + ARRIVAL_MS
            return
        }
        released = complete
    }

    internal fun consumeRequest(): Boolean {
        val fresh = requestedAt != 0L && now() - requestedAt < ARRIVAL_MS
        requestedAt = 0L
        return fresh
    }

    internal fun consumeRevertOnArrival(): Boolean {
        val due = now() < revertOnArrivalUntil
        revertOnArrivalUntil = 0L
        return due
    }

    private fun now() = SystemClock.uptimeMillis()
}

@Composable
fun rememberPageTurnState(): PageTurnState = remember { PageTurnState() }

/** 넘김 요청이 쪽 번호 바뀜보다 이만큼 앞서면 같은 넘김으로 본다. 조판이 늦어도 넉넉하게, 목차 이동과 섞이지 않게. */
private const val ARRIVAL_MS = 2_000L

private class Turn<K>(val from: K, val to: K, val ahead: Boolean)

/**
 * 쪽이 바뀔 때의 효과. [turns] 의 요청 직후에 [key] 가 바뀌면 옛 쪽에서 새 쪽으로 — 서서히 · 밀기 · 덮기 · 말림. 그 밖의
 * 바뀜(다시 짬 · 건너뜀)은 그대로 바꿔 그린다. [turns] 가 없으면 모든 바뀜에 효과를 낸다(옛 동작).
 *
 * [spread] 는 두 쪽 보기 — 말림에서 오른쪽 쪽만 말려 넘어가고 그 뒷면이 다음 펼침의 왼쪽 쪽으로 내려앉는다(종이책과 같다).
 * [forward] 는 앞으로 넘겼는지. 휴대폰의 "애니메이션 제거" 가 켜져 있으면 효과 없이 바꾼다(소리 · 진동은 그대로).
 *
 * [mirrored] 는 오른쪽부터 읽는 만화(0.45.0): 효과 전체를 좌우로 뒤집는다 — 말림은 왼쪽 아래 모서리에서, 덮기 · 밀기는
 * 다음 쪽이 왼쪽에서 들어온다. 쪽 그림은 뒤집히지 않게 기록을 미리 한 번 뒤집어 두고 화면 전체를 다시 뒤집는다.
 */
@Composable
fun <K> CpPageTurn(
    key: K,
    effect: PageTurn,
    forward: (from: K, to: K) -> Boolean,
    turns: PageTurnState? = null,
    spread: Boolean = false,
    sound: TurnSound = TurnSound.Off,
    haptic: Boolean = false,
    mirrored: Boolean = false,
    content: @Composable (K) -> Unit,
) {
    val feedback = rememberTurnFeedback()
    val view = LocalView.current
    val motionOff = rememberAnimationsOff()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var settled by remember { mutableStateOf(key) }
    var turn by remember { mutableStateOf<Turn<K>?>(null) }
    val progress = remember { Animatable(0f) }
    val latestForward by rememberUpdatedState(forward)
    val latest by rememberUpdatedState(Triple(effect, sound, haptic))

    LaunchedEffect(key) {
        // 연달아 빨리 넘기면 앞 효과는 끝난 것으로 치고 거기서부터.
        turn?.let { settled = it.to; turn = null }
        if (key == settled) return@LaunchedEffect
        val from = settled
        val (fx, snd, buzz) = latest
        if (turns != null && turns.consumeRevertOnArrival()) {
            // 덜 끌고 놓았는데 쪽이 이제야 바뀌었다 — 옛 쪽에 머문 채 되돌린다(새 쪽이 한 번 번쩍이지 않게).
            turns.revert?.invoke()
            return@LaunchedEffect
        }
        val dragged = turns != null && (turns.dragging || turns.released != null)
        val asked = turns == null || dragged || turns.consumeRequest()
        val quiet = turns?.requestQuiet == true
        if (!asked) {
            settled = key
            return@LaunchedEffect
        }
        fun cue() { if (!quiet) feedback.turned(snd, buzz, view) }
        // 화면이 보이지 않으면(꺼짐 · 다른 앱) 프레임이 오지 않는다. 그때 효과를 시작하면 다음 프레임을 영원히 기다려,
        // 돌아왔을 때 넘김이 덜 끝난 쪽이 남거나 화면이 "바쁨" 에서 풀리지 않았다(듣기 시험에서 찾음 — 화면을 끈 채 자동
        // 넘김). 보이지 않는 넘김은 효과 없이.
        val visible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (fx == PageTurn.None || motionOff || !visible) {
            settled = key
            if (dragged) {
                // 효과는 없어도 끌기는 끌기다: 덜 끌고 놓으면 되돌린다.
                turns!!.dragTurned = true
                val done = snapshotFlow { turns.released }.filterNotNull().first()
                turns.released = null
                if (done) cue() else turns.revert?.invoke()
            } else {
                cue()
            }
            return@LaunchedEffect
        }
        turn = Turn(from, key, latestForward(from, key))
        progress.snapTo(0f)
        if (dragged) {
            turns!!.dragTurned = true
            val done = coroutineScope {
                // 손가락을 따라간다 — 끄는 동안의 진행도를 그대로.
                val follow = launch { snapshotFlow { turns.dragProgress }.collect { progress.snapTo(it) } }
                val result = snapshotFlow { turns.released }.filterNotNull().first()
                follow.cancel()
                result
            }
            turns.released = null
            if (done) {
                cue()
                progress.animateTo(1f, tween(remainingMs(fx, 1f - progress.value), easing = FastOutSlowInEasing))
                settled = key
                turn = null
            } else {
                // 덜 끌었다: 제자리로 말려 돌아간 뒤 앞 쪽으로 되돌린다. 되돌린 쪽 열쇠는 settled 와 같아 효과 없이 지나간다.
                progress.animateTo(0f, tween(remainingMs(fx, progress.value), easing = FastOutSlowInEasing))
                settled = from
                turn = null
                turns.revert?.invoke()
            }
        } else {
            cue()
            progress.animateTo(1f, tween(durationOf(fx), easing = FastOutSlowInEasing))
            settled = key
            turn = null
        }
    }

    // 넘기는 도중 화면이 꺼지면 그 자리에서 끝낸다 — 기다리던 효과는 프레임이 오지 않아 끝나지 못한다.
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.collect { state ->
            if (!state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) turn?.let {
                settled = it.to
                turn = null
                progress.snapTo(1f)
            }
        }
    }

    val t = turn
    if (t == null) {
        content(settled)
    } else {
        TurnFrame(t, effect, spread, mirrored, { progress.value }, content)
    }
}

private fun durationOf(effect: PageTurn): Int = when (effect) {
    PageTurn.Curl -> CURL_MS
    PageTurn.Cover -> COVER_MS
    else -> TURN_MS
}

/** 손을 뗀 뒤 남은 거리만큼만 — 거의 다 끌어 놓았는데 0.35초를 다 쓰면 굼뜨다. */
private fun remainingMs(effect: PageTurn, left: Float): Int = (durationOf(effect) * left.coerceIn(0.25f, 1f)).toInt()

/** 서서히 · 밀기. 길면 빠르게 넘기는 사람을 붙잡는다. */
private const val TURN_MS = 220
private const val COVER_MS = 280
/** 말림은 종이가 넘어가는 것이 보여야 해서 조금 길다(구상안 0.35초). */
private const val CURL_MS = 350

/** 한 쪽의 그리기 기록. 그리기 단계마다 새로 남긴다 — PDF 쪽 그림처럼 늦게 도착하는 것도 다음 프레임에 들어온다. */
private class Recorder {
    var picture: android.graphics.Picture? = null
}

/** 내용을 화면에 그리지 않고 [rec] 에 기록만 한다. */
private fun Modifier.recordInto(rec: Recorder): Modifier = drawWithContent {
    val w = size.width.toInt()
    val h = size.height.toInt()
    if (w <= 0 || h <= 0) return@drawWithContent
    val picture = android.graphics.Picture()
    val canvas = androidx.compose.ui.graphics.Canvas(picture.beginRecording(w, h))
    draw(this, layoutDirection, canvas, size) { this@drawWithContent.drawContent() }
    picture.endRecording()
    rec.picture = picture
}

/**
 * 넘어가는 동안: 옛 쪽과 새 쪽의 그리기를 각각 기록해 두고(화면에는 직접 그리지 않는다) 효과대로 겹쳐 그린다. 말림은
 * 쪽 그림을 원통에 감아 휘어야 해서 기록을 비트맵으로 옮겨 drawBitmapMesh 로 그린다.
 *
 * 그리기 기록(Picture)을 쓰는 까닭: Compose 의 그림 층을 비트맵으로 뜨는 기능(toImageBitmap)은 그리는 스레드의 프레임을
 * 기다린다. 시험 환경에는 그 스레드가 없어 기다림이 끝나지 않았고, 화면이 "바쁨" 에서 풀리지 않아 다음 시험까지 줄줄이
 * 멈췄다(0.32.0 개발 중). 기록은 그 자리에서 곧바로 비트맵이 된다.
 */
@Composable
private fun <K> TurnFrame(t: Turn<K>, effect: PageTurn, spread: Boolean, mirrored: Boolean, progress: () -> Float, content: @Composable (K) -> Unit) {
    val fromRec = remember(t) { Recorder() }
    val toRec = remember(t) { Recorder() }
    // 읽는 순서로 앞 쪽(earlier) · 뒤 쪽(later). 뒤로 넘기면 같은 그림을 거꾸로 돌린다 — 앞 쪽이 다시 펴지며 덮는다.
    val earlier = if (t.ahead) fromRec else toRec
    val later = if (t.ahead) toRec else fromRec
    val paper = CpTheme.colors.paper
    // 말림 비트맵은 그리기 단계에서 만든다 — 같은 프레임에 쪽 기록(위의 숨은 상자)이 먼저 끝나 있어 첫 장면부터 말린다.
    // PDF 쪽 그림은 늦게 도착하기도 해서 조금 뒤 몇 번 다시 만든다.
    val sheets = remember(t) { SheetCache() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().recordInto(fromRec)) { content(t.from) }
        Box(Modifier.fillMaxSize().recordInto(toRec)) { content(t.to) }
        Canvas(Modifier.fillMaxSize()) {
            val p = progress()
            val tt = if (t.ahead) p else 1f - p
            val a = if (mirrored) flipped(earlier.picture) else earlier.picture
            val b = if (mirrored) flipped(later.picture) else later.picture
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                if (mirrored) {
                    nc.save()
                    nc.scale(-1f, 1f, size.width / 2f, 0f)
                }
                when (effect) {
                    PageTurn.Fade -> {
                        a?.let { nc.drawPicture(it) }
                        b?.let {
                            nc.saveLayerAlpha(0f, 0f, size.width, size.height, (tt * 255).toInt())
                            nc.drawPicture(it)
                            nc.restore()
                        }
                    }
                    PageTurn.Slide -> {
                        a?.let { nc.save(); nc.translate(-tt * size.width, 0f); nc.drawPicture(it); nc.restore() }
                        b?.let { nc.save(); nc.translate((1f - tt) * size.width, 0f); nc.drawPicture(it); nc.restore() }
                    }
                    PageTurn.Curl -> {
                        val s = sheets.get(a, b, spread, paper)
                        if (s != null) drawCurl(nc, a, b, s, spread, tt, size.width, size.height) else drawCover(nc, a, b, tt, size.width, size.height, density)
                    }
                    else -> drawCover(nc, a, b, tt, size.width, size.height, density)
                }
                if (mirrored) nc.restore()
            }
        }
    }
}

/** 좌우로 뒤집은 기록. 화면 전체를 한 번 더 뒤집으면 쪽 그림은 바로 서고 효과만 거꾸로 움직인다. */
private fun flipped(p: android.graphics.Picture?): android.graphics.Picture? {
    if (p == null || p.width <= 0 || p.height <= 0) return p
    val out = android.graphics.Picture()
    val c = out.beginRecording(p.width, p.height)
    c.scale(-1f, 1f, p.width / 2f, 0f)
    c.drawPicture(p)
    out.endRecording()
    return out
}

/** 말림 비트맵을 들고, 정해진 간격으로 몇 번까지만 다시 만든다(프레임마다 만들면 화면 크기 비트맵을 초당 60장 만든다). */
private class SheetCache {
    private var sheets: CurlSheets? = null
    private var count = 0
    private var at = 0L

    fun get(earlier: android.graphics.Picture?, later: android.graphics.Picture?, spread: Boolean, paper: Color): CurlSheets? {
        val now = SystemClock.uptimeMillis()
        if (sheets == null || (count < CAPTURES && now - at >= CAPTURE_GAP_MS)) {
            runCatching { captureSheets(earlier, later, spread, paper) }.getOrNull()?.let {
                sheets = it
                count++
                at = now
            }
        }
        return sheets
    }
}

private const val CAPTURES = 3
private const val CAPTURE_GAP_MS = 90L

/** 덮기: 뒤 쪽이 오른쪽에서 들어와 앞 쪽을 덮는다. 왼쪽 가장자리에 옅은 그림자 — 종이 한 장이 위에 얹힌 것처럼. */
private fun drawCover(nc: android.graphics.Canvas, a: android.graphics.Picture?, b: android.graphics.Picture?, t: Float, w: Float, h: Float, density: Float) {
    a?.let { nc.drawPicture(it) }
    val x = (1f - t) * w
    val shade = COVER_SHADOW_DP * density
    nc.drawRect(x - shade, 0f, x, h, Paint().apply {
        shader = LinearGradient(x - shade, 0f, x, 0f, 0, android.graphics.Color.argb(70, 0, 0, 0), Shader.TileMode.CLAMP)
    })
    b?.let {
        nc.save()
        nc.translate(x, 0f)
        nc.drawPicture(it)
        nc.restore()
    }
}

private const val COVER_SHADOW_DP = 16f

// ── 말림 ─────────────────────────────────────────────────────────────

/** 말림에 쓰는 비트맵: 말리는 종이(앞면), 그 뒷면. 넘어가는 동안만 들고 있다. */
private class CurlSheets(val front: Bitmap, val back: Bitmap)

private fun pictureBitmap(p: android.graphics.Picture): Bitmap? {
    if (p.width <= 0 || p.height <= 0) return null
    val b = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(b).drawPicture(p)
    return b
}

/**
 * 한 쪽 보기: 앞면 = 앞 쪽 전체, 뒷면 = 앞 쪽을 종이색으로 거의 덮은 것(뒤로 비친 글자).
 * 두 쪽 보기: 앞면 = 앞 펼침의 오른쪽 쪽, 뒷면 = 뒤 펼침의 왼쪽 쪽을 좌우로 뒤집은 것 — 원통을 넘어간 종이는 좌우가
 * 뒤집혀 놓이므로, 미리 뒤집어 두면 다 넘어갔을 때 뒤 펼침의 왼쪽 쪽이 바로 놓인다.
 */
private fun captureSheets(earlier: android.graphics.Picture?, later: android.graphics.Picture?, spread: Boolean, paper: Color): CurlSheets? {
    val a = earlier?.let(::pictureBitmap) ?: return null
    if (!spread) {
        val back = Bitmap.createBitmap(a.width, a.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(back).apply {
            drawBitmap(a, 0f, 0f, null)
            drawColor(paper.copy(alpha = BACK_WASH).toArgb())
        }
        return CurlSheets(a, back)
    }
    val b = later?.let(::pictureBitmap) ?: return null
    val half = a.width / 2
    if (half <= 0 || b.width < 2 * half) return null
    val front = Bitmap.createBitmap(a, half, 0, a.width - half, a.height)
    val back = Bitmap.createBitmap(b.width - half, b.height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(back).apply {
        scale(-1f, 1f, back.width / 2f, 0f)
        drawBitmap(b, android.graphics.Rect(0, 0, half, b.height), android.graphics.Rect(0, 0, back.width, back.height), null)
    }
    return CurlSheets(front, back)
}

/** 뒷면을 덮는 종이색의 짙기. 글자가 흐릿하게 비쳐야 종이 뒷면 같다 — 너무 옅으면 거울 글자가 읽혀 거슬린다. */
private const val BACK_WASH = 0.89f

private fun drawCurl(
    nc: android.graphics.Canvas,
    earlier: android.graphics.Picture?,
    later: android.graphics.Picture?,
    sheets: CurlSheets,
    spread: Boolean,
    t: Float,
    width: Float,
    height: Float,
) {
    val x0 = if (spread) width / 2f else 0f
    if (spread) {
        // 왼쪽은 아직 앞 펼침, 오른쪽은 뒤 펼침이 드러난다. 넘어가는 종이가 왼쪽에 내려앉으며 덮는다.
        earlier?.let { nc.drawPicture(it) }
        later?.let { nc.save(); nc.clipRect(x0, 0f, width, height); nc.drawPicture(it); nc.restore() }
    } else {
        later?.let { nc.drawPicture(it) }
    }
    val w = width - x0
    val g = curlGeometry(w, height, t)
    nc.save()
    nc.translate(x0, 0f)
    val lift = sin(PI.toFloat() * t)
    val angle = Math.toDegrees(kotlin.math.atan2(g.ny, g.nx).toDouble()).toFloat()
    // 말린 종이 아래 그림자(드러난 쪽 위). 넘김 처음 · 끝에는 옅게.
    if (g.radius > 0f) {
        val crestX = g.foldX + g.radius * g.nx
        val crestY = g.foldY + g.radius * g.ny
        val shadow = w * SHADOW_RATIO
        nc.save()
        nc.rotate(angle, crestX, crestY)
        nc.drawRect(crestX, -2 * height, crestX + shadow, 2 * height, Paint().apply {
            shader = LinearGradient(crestX, 0f, crestX + shadow, 0f, android.graphics.Color.argb((90 * lift).toInt(), 0, 0, 0), 0, Shader.TileMode.CLAMP)
        })
        nc.restore()
    }
    nc.drawBitmapMesh(sheets.front, CURL_COLS, CURL_ROWS, g.front, 0, null, 0, null)
    // 원통 앞면 음영: 접힌 줄(밝음) → 꼭대기(어둡게). 꼭짓점 색을 쓰면 쪽 전체가 흐려져 따로 덧칠한다.
    if (g.radius > 0f) {
        nc.save()
        nc.rotate(angle, g.foldX, g.foldY)
        nc.drawRect(g.foldX, -2 * height, g.foldX + g.radius, 2 * height, Paint().apply {
            shader = LinearGradient(g.foldX, 0f, g.foldX + g.radius, 0f, 0, android.graphics.Color.argb(60, 0, 0, 0), Shader.TileMode.CLAMP)
        })
        nc.restore()
    }
    nc.drawBitmapMesh(sheets.back, CURL_COLS, CURL_ROWS, g.back, 0, null, 0, null)
    nc.restore()
}

private const val SHADOW_RATIO = 0.08f

/** 말림 그물의 칸 수. 촘촘할수록 원통이 매끈하지만 프레임마다 점이 늘어난다. 36 × 72 면 1080p 에서 계단이 안 보였다. */
internal const val CURL_COLS = 36
internal const val CURL_ROWS = 72

/** 원통 반지름(종이 폭에 대한 비율) · 접힌 줄 기울기(라디안). 구상안에서 확정한 모양. */
private const val CURL_RADIUS = 0.075f
private const val CURL_TILT = 0.22f

/**
 * 말림 그물(순수 계산 — 시험이 기기 없이 모양을 잰다). [w] × [h] 종이의 오른쪽 아래 모서리를 잡아 끈다: 접힌 줄은 아래
 * 가장자리의 x = w(1 − t) 를 지나고, 줄 너머의 종이는 반지름 r 의 원통에 감긴다. 반 바퀴(πr)를 넘은 종이는 뒤집혀 접힌 줄
 * 너머(왼쪽)에 눕는다. 끝(t = 1)에서는 반지름 · 기울기가 0 이 되어 종이가 x = 0 을 축으로 정확히 뒤집힌다 — 두 쪽 보기에서
 * 넘어간 종이가 왼쪽 쪽 자리에 딱 맞게 내려앉는다.
 *
 * [CurlGeometry.front] 는 앞면(원통의 앞쪽 반까지), [CurlGeometry.back] 는 뒷면(그 너머) 꼭짓점. 나머지 점은 원통 꼭대기
 * 한 줄에 모아 보이지 않게 한다 — 한 그물에 두 면을 그리면 겹치는 차례를 정할 수 없다.
 */
internal fun curlGeometry(w: Float, h: Float, t: Float, cols: Int = CURL_COLS, rows: Int = CURL_ROWS): CurlGeometry {
    // 깨진 값(크기 0 · NaN)에도 그물은 만든다 — 숫자가 아닌 점을 넘기면 그리기가 통째로 실패한다.
    val ww = if (w.isFinite() && w > 0f) w else 1f
    val hh = if (h.isFinite() && h > 0f) h else 1f
    val tt = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
    val a = CURL_TILT * (1f - tt)
    val nx = cos(a)
    val ny = sin(a)
    val r = ww * CURL_RADIUS * (1f - tt)
    val fx = ww * (1f - tt)
    val fy = hh
    val half = (PI * r / 2).toFloat()
    val front = FloatArray((cols + 1) * (rows + 1) * 2)
    val back = FloatArray(front.size)
    var i = 0
    for (y in 0..rows) for (x in 0..cols) {
        val px = ww * x / cols
        val py = hh * y / rows
        val d0 = (px - fx) * nx + (py - fy) * ny
        val df = minOf(d0, half)
        val db = maxOf(d0, half)
        val mf = wrap(df, r)
        val mb = wrap(db, r)
        // 접힌 줄로 내린 뒤(원래 거리 d0) 원통 위의 자리로.
        front[i] = px - (d0 - mf) * nx
        front[i + 1] = py - (d0 - mf) * ny
        back[i] = px - (d0 - mb) * nx
        back[i + 1] = py - (d0 - mb) * ny
        i += 2
    }
    return CurlGeometry(front, back, fx, fy, nx, ny, r)
}

/** 접힌 줄에서 d 만큼 떨어진 종이가 놓이는 자리(접힌 줄에서의 거리). 원통에 감기다가 반 바퀴를 넘으면 반대로 눕는다. */
private fun wrap(d: Float, r: Float): Float = when {
    d <= 0f -> d
    r > 1e-3f && d <= PI * r -> r * sin(d / r)
    else -> -(d - (PI * r).toFloat())
}

internal class CurlGeometry(
    val front: FloatArray,
    val back: FloatArray,
    val foldX: Float,
    val foldY: Float,
    val nx: Float,
    val ny: Float,
    val radius: Float,
)

/** 휴대폰 접근성의 "애니메이션 제거"(애니메이터 길이 배율 0). 움직임에 어지러운 사람이 켠다. */
@Composable
private fun rememberAnimationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

// ── 소리 · 진동 ──────────────────────────────────────────────────────

/** 넘김 소리 · 진동을 내는 곳. 시험은 [LocalTurnFeedback] 으로 가짜를 넣는다. */
interface TurnFeedback {
    /** 사람이 넘겼다. 낼지 말지(무음 · 다른 앱 소리)는 구현이 정한다. */
    fun turned(sound: TurnSound, haptic: Boolean, view: View)

    /** 설정에서 소리를 골랐다 — 한 번 들려 준다. */
    fun preview(sound: TurnSound)
}

val LocalTurnFeedback = staticCompositionLocalOf<TurnFeedback?> { null }

@Composable
fun rememberTurnFeedback(): TurnFeedback {
    val given = LocalTurnFeedback.current
    if (given != null) return given
    val context = LocalContext.current.applicationContext
    return remember(context) { SystemTurnFeedback.of(context) }
}

/**
 * 소리를 낼지(순수 — 시험이 표로 잰다). 휴대폰이 무음 · 진동이면 내지 않는다(도서관 · 지하철). 다른 앱이 소리를 내는
 * 중(음악 · 영상)에도 내지 않는다 — 음악 위로 바스락거리면 거슬린다.
 */
internal fun turnSoundAllowed(sound: TurnSound, ringerNormal: Boolean, otherAudio: Boolean): Boolean =
    sound != TurnSound.Off && ringerNormal && !otherAudio

/**
 * 안드로이드 "UI 효과음" 통로(USAGE_ASSISTANCE_SONIFICATION)로 짧은 소리를 낸다 — 키보드 소리와 같은 통로라 휴대폰의
 * 시스템 음량을 따른다. 소리 세 개를 앱 전체에서 한 번만 싣는다.
 */
private class SystemTurnFeedback private constructor(private val context: Context) : TurnFeedback {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = HashMap<TurnSound, Int>()
    private val loaded = HashSet<Int>()
    /** 싣기가 끝나기 전에 부른 소리 — 설정에서 처음 고른 소리가 안 들리면 고장 난 줄 안다. */
    private var waiting: Int? = null

    init {
        pool.setOnLoadCompleteListener { p, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loaded += id
            if (waiting == id) {
                waiting = null
                p.play(id, VOLUME, VOLUME, 1, 0, 1f)
            }
        }
    }

    private fun idOf(sound: TurnSound): Int? {
        val res = when (sound) {
            TurnSound.Off -> return null
            TurnSound.Rustle -> R.raw.turn_rustle
            TurnSound.Swish -> R.raw.turn_swish
            TurnSound.Tap -> R.raw.turn_tap
        }
        return ids.getOrPut(sound) { pool.load(context, res, 1) }
    }

    private fun play(sound: TurnSound) {
        val id = idOf(sound) ?: return
        if (id in loaded) pool.play(id, VOLUME, VOLUME, 1, 0, 1f) else waiting = id
    }

    override fun turned(sound: TurnSound, haptic: Boolean, view: View) {
        val normal = audio?.ringerMode?.let { it == AudioManager.RINGER_MODE_NORMAL } ?: true
        if (turnSoundAllowed(sound, normal, audio?.isMusicActive == true)) play(sound)
        // 진동은 안드로이드 기본 "가벼운 톡" — 휴대폰의 터치 진동 설정을 따른다.
        if (haptic) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun preview(sound: TurnSound) = play(sound)

    companion object {
        /** 시스템 음량 그대로는 크다 — 넘길 때마다 듣는 소리라 한발 물러선다. */
        private const val VOLUME = 0.6f
        @Volatile private var shared: SystemTurnFeedback? = null
        fun of(context: Context): SystemTurnFeedback =
            shared ?: synchronized(this) { shared ?: SystemTurnFeedback(context).also { shared = it } }
    }
}
