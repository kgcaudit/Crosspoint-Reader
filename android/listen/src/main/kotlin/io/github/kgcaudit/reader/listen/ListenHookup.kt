package io.github.kgcaudit.reader.listen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * 듣기를 시작할 자리: 단위(EPUB 은 장, PDF 는 쪽)와 그 안의 글자 번호. [pageEnd] 를 주면 보이는 쪽에서 켠 것이다 — 그 쪽에서
 * **시작하는** 첫 문장부터 읽는다([Listening.start]).
 */
data class ListenSpot(val unit: Int, val offset: Int, val pageEnd: Int? = null)

/**
 * 엔진을 바꿔 다시 열 때 읽기 시작할 자리. 듣던 것이 있으면 **그 단위와 그 문장** 을 함께 쓰고, 아직 아무 문장도 보이지 않았으면
 * (엔진을 깨우는 중) 화면의 자리([here])를 함께 쓴다.
 *
 * 단위와 글자 번호를 서로 다른 곳에서 가져오면 안 된다 — EPUB 은 단위를 듣기에서, 글자 번호는 화면의 쪽 첫 글자에서 가져와,
 * 듣기가 다른 장에 있으면 그 장의 엉뚱한 글자 번호에서 다시 읽었다.
 */
internal fun restartSpot(heard: ListenState, here: () -> ListenSpot?): ListenSpot? =
    if (heard.spine >= 0) ListenSpot(heard.spine, heard.sentence?.start ?: 0) else here()

/**
 * 리더 하나와 듣기를 잇는 것. EPUB · TXT 와 PDF 가 같은 것을 쓴다 — 두 리더가 사본을 들고 있을 때 화면을 끈 동안 쪽이 옮겨 간
 * 것을 알리는 것은 EPUB 에만 있었고, 엔진을 바꿔 다시 여는 자리는 두 리더가 다르게 셌다.
 *
 * 리더마다 다른 것(어디서 시작하는가 · 보이는 쪽을 듣기에 알리는 법 · 글을 읽을 수 있는가 · 읽는 문장을 따라 화면을 옮기는 법)은
 * 리더가 쥐고, 여기에는 듣기 쪽의 일만 둔다.
 */
class ListenHookup internal constructor(
    private val source: ListenSource,
    private val context: Context,
    /** 목소리 고르기 화면이 쓰는 엔진 · 목소리 목록. */
    val kit: ListenKit,
    private val hub: State<Listening?>,
    private val heard: State<ListenState>,
) {
    /** 이 책의 듣기. 다른 책을 듣는 중이거나 듣지 않으면 null — 다른 책의 듣기를 이 책의 조종판에 붙이지 않는다. */
    val listening: Listening? get() = hub.value?.takeIf { it.belongsTo(source) }

    /**
     * 이 책의 듣기의 지금 모습(듣지 않으면 빈 것). 화면 상태(State)에서 읽는다 — 조종판 · 판처럼 따로 다시 그려지는 부품도 바뀔 때
     * 다시 그려진다. 화면을 그릴 때 값을 옮겨 담아 두면, 같은 연결을 받은 부품은 "인자가 같다" 고 건너뛰어 멈춤을 눌러도 조종판이
     * "멈춤" 그대로였다.
     */
    val state: ListenState get() = heard.value

    /** 듣기 판(빠르기 · 목소리 · 타이머)을 열어 두었다. 듣기가 꺼져 있으면 보이지 않는다([sheetShown]). */
    var sheet by mutableStateOf(false)

    val active: Boolean get() = state.active

    /** 듣기 판이 지면 위에 떠 있다. 밝기 밀기처럼 지면을 받는 손짓이 이 동안은 비켜야 한다. */
    val sheetShown: Boolean get() = sheet && state.active

    /** [spot] 부터 새로 듣는다. 앞의 듣기(다른 책이어도)는 끈다 — 한 번에 한 책만 읽는다. */
    fun start(spot: ListenSpot, prefs: ListenPrefs) {
        val l = Listening(source, kit.speaker(prefs.engine), ListenHub.scope)
        ListenHub.attach(context, l)
        ListenHub.scope.launch { l.start(spot.unit, spot.offset, prefs.rate, prefs.voice, prefs.join, pageEnd = spot.pageEnd) }
    }

    /** 이미 이 책을 듣는 중이면 이어 듣고 true. 아니면 false — 리더가 제 자리에서 [start] 한다. */
    fun resume(): Boolean {
        val l = listening?.takeIf { it.state.value.active } ?: return false
        l.play()
        return true
    }

    /** 책을 닫을 때. 닫은 책을 화면 없이 계속 읽으면 멈출 곳이 잠금 화면뿐이다. */
    fun close() = ListenHub.detach(listening)

    /**
     * 목소리를 골랐다. 엔진이 바뀌었으면 새 엔진으로 다시 연다 — 듣던 문장부터([restartSpot]). 같은 엔진이면 목소리만 바꾼다.
     */
    internal fun pick(picked: ListenPrefs, before: ListenPrefs, here: () -> ListenSpot?) {
        val l = listening ?: return
        if (picked.engine == before.engine) {
            l.setVoice(picked.voice)
            return
        }
        val spot = restartSpot(l.state.value, here) ?: return
        ListenHub.detach(l)
        start(spot, picked)
    }
}

/**
 * 이 화면의 [source] 에 듣기를 잇는다. 듣기가 알리는 말(엔진 없음 · 책 끝)과, 화면을 끈 채 듣는 동안 쪽이 넘어간 것을 [onMessage]
 * 로 알린다 — 돌아와서 보던 쪽이 바뀐 까닭을 모르면 놀란다.
 *
 * @param kit 듣기 엔진. 앱이 주고 시험은 가짜를 준다. null 이면 휴대폰의 음성 엔진.
 * @param shownAt 지금 보이는 쪽을 가리키는 값(같으면 같은 쪽). 화면을 끈 동안 쪽이 바뀌었는지 견준다.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun rememberListenHookup(
    source: ListenSource,
    kit: ListenKit?,
    onMessage: (String) -> Unit,
    shownAt: () -> Any?,
): ListenHookup {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engines = remember(kit) { kit ?: ListenKit.android(context) }
    val hub = ListenHub.current.collectAsState()
    // 붙은 듣기가 바뀌면(새로 켬 · 다른 책 · 끔) 그 듣기의 상태를 따라간다.
    val heard = remember(source) {
        ListenHub.current.flatMapLatest { l -> l?.takeIf { it.belongsTo(source) }?.state ?: flowOf(ListenState()) }
    }.collectAsState(ListenHub.current.value?.takeIf { it.belongsTo(source) }?.state?.value ?: ListenState())
    val hookup = remember(source, engines) { ListenHookup(source, context, engines, hub, heard) }
    val listening = hookup.listening
    val say by rememberUpdatedState(onMessage)
    val here by rememberUpdatedState(shownAt)

    val message = hookup.state.message
    LaunchedEffect(message) {
        message?.let { say(it); hookup.listening?.consumeMessage() }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, listening) {
        var left: Any? = null
        var away = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    left = here()
                    away = true
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (away && here() != left && listening?.state?.value?.active == true) say("듣던 곳으로 쪽을 옮겼습니다")
                    away = false
                    left = null
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return hookup
}

/** 듣는 동안 아래에 뜨는 조종판. 빠르기 · 타이머를 누르면 듣기 판이 열린다. 듣지 않으면 그리지 않는다. */
@Composable
fun ListenPlayer(hookup: ListenHookup, modifier: Modifier = Modifier) {
    if (!hookup.active) return
    val l = hookup.listening
    ListenPlayer(
        state = hookup.state,
        onPrevious = { l?.previous() },
        onToggle = { l?.toggle() },
        onNext = { l?.next() },
        onSettings = { hookup.sheet = true },
        onClose = { ListenHub.detach(l) },
        modifier = modifier,
    )
}

/**
 * 듣기 판과 목소리 고르기 화면. 목소리 화면을 여닫는 것은 리더의 판(메뉴 상태)이라 [voices] · [onVoices] 로 받는다 — 목소리 화면이
 * 열린 동안 리더의 다른 판이 뜨지 않게 리더가 한 상태로 쥐고 있어야 한다.
 *
 * @param here 엔진을 바꿀 때 듣던 문장이 아직 없으면 다시 열 자리(지금 보이는 쪽).
 * @param chapterTimer "장 끝" 타이머를 고를 수 있는가(목차 없는 PDF 는 장이 없다).
 */
@Composable
fun ListenPanels(
    hookup: ListenHookup,
    prefs: ListenPrefs,
    onPrefsChange: (ListenPrefs) -> Unit,
    voices: Boolean,
    onVoices: (open: Boolean) -> Unit,
    here: () -> ListenSpot?,
    chapterTimer: Boolean = true,
) {
    val l = hookup.listening
    if (hookup.sheetShown) {
        ListenSheet(
            prefs = prefs,
            timer = hookup.state.timer,
            onRate = { next -> onPrefsChange(next); l?.setRate(next.rate) },
            onVoices = { hookup.sheet = false; onVoices(true) },
            onTimer = { l?.setTimer(it) },
            onClose = { hookup.sheet = false },
            onJoin = { level -> onPrefsChange(prefs.copy(join = level)); l?.setJoin(level) },
            chapterTimer = chapterTimer,
        )
    }
    if (voices) {
        VoiceScreen(
            kit = hookup.kit,
            current = prefs,
            onPick = { picked ->
                onPrefsChange(picked)
                hookup.pick(picked, prefs, here)
            },
            // 듣는 중이면 듣기 판으로 돌아온다 — 판에서 "목소리" 를 눌러 왔다.
            onBack = { onVoices(false); if (hookup.active) hookup.sheet = true },
        )
    }
}
