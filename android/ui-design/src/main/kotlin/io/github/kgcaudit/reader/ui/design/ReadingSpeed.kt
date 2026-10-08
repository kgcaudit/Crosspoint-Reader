package io.github.kgcaudit.reader.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis

/**
 * 읽는 속도(단위/분). EPUB · TXT 는 글자, PDF 는 쪽이 단위다. 하단 정보의 "남은 시간" 이 이 값으로 센다.
 *
 * 쪽을 **앞으로** 넘길 때마다 그 쪽에 머문 시간과 분량으로 하나씩 잰다. 너무 짧게(훑어 넘김) 또는 너무 길게
 * (책을 펴 둔 채 자리를 뜸) 머문 쪽은 버린다 — 넣으면 남은 시간이 몇 분에서 몇 시간으로 튄다. 최근 값에 무게를
 * 더 준다(지수 평균): 오늘 컨디션이 한 달 전 평균보다 맞다.
 *
 * [MIN_SAMPLES] 개가 모이기 전에는 모른다(null) — 첫 쪽 하나로 센 남은 시간은 거의 틀린다(E5).
 */
class ReadingSpeed(unitsPerMinute: Double? = null, samples: Int = 0) {
    var unitsPerMinute: Double? = unitsPerMinute
        private set
    var samples: Int = samples
        private set

    /** 한 쪽을 읽었다: [units] 만큼을 [millis] 동안. 쓸 수 있는 값이면 true(저장할 때가 됐다). */
    fun record(units: Double, millis: Long): Boolean {
        if (units <= 0 || millis !in MIN_MILLIS..MAX_MILLIS) return false
        val speed = units / (millis / 60_000.0)
        val old = unitsPerMinute
        unitsPerMinute = if (old == null) speed else old + ALPHA * (speed - old)
        samples++
        return true
    }

    /** [units] 를 읽는 데 걸릴 분. 아직 모르면 null. */
    fun minutesFor(units: Double): Int? {
        val speed = unitsPerMinute?.takeIf { samples >= MIN_SAMPLES && it > 0 } ?: return null
        return kotlin.math.ceil(units.coerceAtLeast(0.0) / speed).toInt()
    }

    companion object {
        const val MIN_SAMPLES = 3
        const val MIN_MILLIS = 2_000L
        const val MAX_MILLIS = 10 * 60_000L
        private const val ALPHA = 0.2
    }
}

/**
 * 사람이 한 쪽(두쪽보기면 펼침)을 읽고 앞으로 넘긴 것 하나: [units] 를 [millis] 동안. 읽는 속도가 받고, 나중에 읽은 시간
 * 통계를 만들면 같은 자리([ReadingDwellEffect] 의 onDwell)에서 받는다 — 둘이 "사람이 읽은 시간" 을 따로 가리면 속도와
 * 통계가 서로 다른 시간을 센다.
 */
data class ReadDwell(val units: Double, val millis: Long)

/**
 * 쪽이 바뀔 때마다 앞 쪽에 머문 시간을 재어, **사람이 읽고 한 쪽 넘긴 것**만 [ReadDwell] 로 내놓는다. EPUB · TXT · PDF 가
 * 함께 쓴다 — 리더마다 사본을 두었을 때 둘 다 같은 구멍이 있었다.
 *
 * 걸러 내는 것:
 * - 기계가 넘긴 쪽(자동 넘김 · 듣기가 따라 넘김). 머문 시간이 읽은 시간이 아니라 고른 초 · 목소리 빠르기다 — 넣으면 15초
 *   자동 넘김을 켜 둔 사람의 속도가 "쪽당 15초" 로 굳는다.
 * - 건너뛴 쪽(목차 · 찾기 · 책갈피 · 진행 막대 · 링크). 앞으로 건너뛰어도 앞 쪽을 다 읽은 것이 아니다 — 목차를 고르는 동안의
 *   시간까지 한 쪽 분량으로 셌다. 바로 다음 쪽([isNext])으로 간 것만 센다.
 * - 너무 짧게(훑어 넘김) · 너무 길게(펴 둔 채 자리를 뜸) 머문 쪽([ReadingSpeed.MIN_MILLIS] · [ReadingSpeed.MAX_MILLIS]).
 *
 * 기계가 넘기다 사람이 이어받으면(듣기를 끔 · 자동 넘김을 멈춤) 그 쪽의 시간은 그때부터 다시 센다 — 앞부분은 들은 시간이다.
 *
 * @param isNext [after] 가 [before] 의 바로 다음 쪽(펼침)인가. 쪽 열쇠의 모양이 리더마다 달라 리더가 정한다.
 */
class TurnSampler<K : Any>(private val isNext: (before: K, after: K) -> Boolean) {
    private var key: K? = null
    private var units = 0.0
    private var since = 0L
    /** 지금 쪽을 사람이 넘기는 동안 보여 왔다(기계가 넘기는 중에 들어온 쪽이 아니다). */
    private var human = false

    /**
     * 지금 보이는 쪽. [units] 는 이 쪽의 분량, [driven] 은 지금 기계가 넘기는 중인가(자동 넘김 · 듣기), [now] 는 단조 시각(ms).
     * 앞 쪽이 사람이 읽고 넘긴 쪽이면 그 쪽의 [ReadDwell], 아니면 null.
     */
    fun shown(key: K?, units: Double, driven: Boolean, now: Long): ReadDwell? {
        val before = this.key
        if (key == before) {
            this.units = units
            // 쪽은 그대로인데 넘기는 주체가 바뀌었다 — 이 쪽의 시간은 여기서 새로 센다.
            if (human == driven) {
                human = !driven
                since = now
            }
            return null
        }
        val read = this.units
        val started = since
        val wasHuman = human
        this.key = key
        this.units = units
        since = now
        human = !driven
        if (before == null || key == null || !wasHuman || driven || !isNext(before, key)) return null
        val millis = now - started
        if (read <= 0 || millis !in ReadingSpeed.MIN_MILLIS..ReadingSpeed.MAX_MILLIS) return null
        return ReadDwell(read, millis)
    }
}

/** 쪽을 누가 넘기고 있나. 독서 기록(0.51.0)이 셋을 따로 센다(사용자 결정 1-2). */
enum class TurnBy { HAND, AUTO, LISTEN }

/**
 * 독서 기록의 한 칸: 한 쪽(펼침)에 머문 시간. [by] 는 [TurnBy.HAND](손으로 넘기며 읽음) 또는 [TurnBy.AUTO](자동 넘김) —
 * 듣기는 화면이 꺼져도 이어져 쪽 시계로 잴 수 없어 앱이 듣기 상태로 따로 잰다.
 */
data class PageTime(val by: TurnBy, val millis: Long)

/**
 * 독서 기록(0.51.0)의 쪽 시계. 읽는 속도([TurnSampler])와 규칙이 다르다 — 속도는 "한 쪽을 다 읽는 데 걸린 시간" 이 필요해 짧은
 * 쪽 · 긴 쪽을 **버리지만**, 통계는 "얼마나 읽었나" 라서 버리면 안 된다:
 * - 한 쪽에 머문 시간은 [CAP_MS](5분)까지만 센다(자르기 — 버리지 않는다). 책을 펴 둔 채 자리를 떠도 한 쪽에 5분이다.
 * - 건너뛴 쪽(목차 · 찾기 · 책갈피 · 진행 막대 · 링크로 다음 · 앞 쪽이 아닌 곳에 감)은 세지 않는다 — 목차를 고르던 시간이다.
 *   다음 쪽 · 앞 쪽으로 넘긴 것, 책을 닫거나 화면을 끈 것(거기까지 읽고 있었다)은 센다.
 * - 화면이 꺼지거나 앱이 뒤로 가면([pause]) 시계가 멈춘다. 돌아오면([resume]) 그때부터 다시 센다.
 * - 듣는 동안의 쪽([TurnBy.LISTEN])은 여기서 세지 않는다 — 앱이 듣기 상태로 잰다(화면을 끄고 들어도 들은 시간이다).
 */
class PageClock<K : Any>(private val isNext: (before: K, after: K) -> Boolean) {
    private var key: K? = null
    private var by = TurnBy.HAND
    private var since = 0L
    private var paused = false

    /** 지금 보이는 쪽과 넘기는 주체. 앞 칸이 끝났으면 그 칸. */
    fun shown(key: K?, by: TurnBy, now: Long): PageTime? {
        val before = this.key
        val beforeBy = this.by
        if (key == before && by == beforeBy) return null
        this.key = key
        this.by = by
        // 멈춘 동안(화면을 끄고 듣는 중 쪽이 따라 넘어감) 바뀐 쪽은 적어만 두고, 시간은 돌아온 때부터 센다.
        if (paused) return null
        val started = since
        since = now
        // 쪽이 그대로이고 주체만 바뀌었으면(자동 넘김을 켬 · 끔) 여기까지가 앞 주체의 시간이다. 쪽이 바뀌었으면 다음 · 앞 쪽일 때만.
        if (key != before && before != null && key != null && !isNext(before, key) && !isNext(key, before)) return null
        return credit(before, beforeBy, now - started)
    }

    /** 화면이 꺼짐 · 앱이 뒤로 감 · 책을 닫음. 거기까지 보던 쪽의 시간을 내놓고 시계를 멈춘다. */
    fun pause(now: Long): PageTime? {
        if (paused) return null
        paused = true
        return credit(key, by, now - since)
    }

    /** 다시 앞에 왔다. 멈춘 동안은 세지 않는다. */
    fun resume(now: Long) {
        if (!paused) return
        paused = false
        since = now
    }

    private fun credit(key: K?, by: TurnBy, millis: Long): PageTime? {
        // 열쇠가 없는 동안(아직 쪽을 짜는 중)은 읽을 것이 없었다. 시계가 뒤로 갔으면(음수) 버린다.
        if (key == null || by == TurnBy.LISTEN || millis <= 0) return null
        return PageTime(by, millis.coerceAtMost(CAP_MS))
    }

    companion object {
        /** 한 쪽에 머문 시간은 여기까지만 센다(사용자 결정 1-2). */
        const val CAP_MS: Long = 5 * 60_000L
    }
}

/**
 * 독서 기록의 시계. 쪽 시계([PageClock]) · 웹툰 시계([ScrollClock])가 쓴다 — 화면 그림 시계(withFrameMillis)는 화면이 꺼지면
 * 멈춰 "화면을 끈 때" 를 잴 수 없어, 앱은 부팅 뒤 흐른 시간(잠든 시간 포함)을 준다. 시험은 화면 시험 시계를 준다.
 */
val LocalReadingClock = androidx.compose.runtime.staticCompositionLocalOf<() -> Long> { { android.os.SystemClock.elapsedRealtime() } }

/**
 * 리더 화면의 읽는 시간 재기. 쪽 열쇠([key])나 [driven] 이 바뀔 때마다 [TurnSampler] 에 알리고, 사람이 읽은 쪽이면 [onDwell].
 *
 * 시각은 화면 그림 시계(withFrameMillis)다 — 쪽을 그린 때가 사람이 보기 시작한 때이고, 시험에서도 손으로 돌릴 수 있다.
 * [book] 이 바뀌면(다른 책) 새로 센다 — 앞 책의 마지막 쪽 시간이 새 책의 첫 넘김에 붙지 않게.
 *
 * 독서 기록(0.51.0)도 여기서 잰다([onTime], [PageClock]) — 읽는 속도와 같은 "보이는 쪽 · 넘기는 주체" 를 보되 규칙은 다르다.
 * [autoTurning] 은 [driven] 가운데 자동 넘김이 넘기는 중인가(아니면 듣기).
 */
@Composable
fun <K : Any> ReadingDwellEffect(
    book: Any,
    key: K?,
    units: Double,
    driven: Boolean,
    isNext: (before: K, after: K) -> Boolean,
    autoTurning: Boolean = false,
    onTime: (PageTime) -> Unit = {},
    onDwell: (ReadDwell) -> Unit,
) {
    val next by rememberUpdatedState(isNext)
    val sampler = remember(book) { TurnSampler<K> { a, b -> next(a, b) } }
    val latestUnits by rememberUpdatedState(units)
    val report by rememberUpdatedState(onDwell)
    LaunchedEffect(sampler, key, driven) {
        val now = withFrameMillis { it }
        sampler.shown(key, latestUnits, driven, now)?.let { report(it) }
    }
    PageTimeEffect(book, key, if (!driven) TurnBy.HAND else if (autoTurning) TurnBy.AUTO else TurnBy.LISTEN, isNext, onTime)
}

/**
 * 독서 기록의 쪽 시계를 화면에 잇는다: 쪽 · 주체가 바뀔 때, 화면이 꺼지고 켜질 때(ON_STOP · ON_START), 리더를 닫을 때. 만화
 * 뷰어도 이것만 쓴다(읽는 속도는 재지 않는다).
 */
@Composable
fun <K : Any> PageTimeEffect(book: Any, key: K?, by: TurnBy, isNext: (before: K, after: K) -> Boolean, onTime: (PageTime) -> Unit) {
    val next by rememberUpdatedState(isNext)
    val clock = LocalReadingClock.current
    val timer = remember(book) { PageClock<K> { a, b -> next(a, b) } }
    val report by rememberUpdatedState(onTime)
    // 쪽을 그린 다음 시각을 잰다 — 넘김 효과가 도는 동안은 아직 앞 쪽을 보고 있다.
    LaunchedEffect(timer, key, by) {
        withFrameMillis { }
        timer.shown(key, by, clock())?.let { report(it) }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, timer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // 화면을 끄거나 홈으로 갔다 — 여기서 멈추지 않으면 밤새 켜 둔 책 한 쪽이 다음 날 아침 넘길 때 5분으로 들어간다.
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> timer.pause(clock())?.let { report(it) }
                androidx.lifecycle.Lifecycle.Event.ON_START -> timer.resume(clock())
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // 책을 닫았다: 마지막 쪽도 거기까지 읽고 있었다.
            timer.pause(clock())?.let { report(it) }
        }
    }
}

/**
 * 웹툰(이어 내려 읽기)의 독서 기록 시계(0.51.0). 웹툰에는 넘기는 쪽이 없어 [PageClock] 을 쓸 수 없다 — 대신 **화면이 움직인
 * 사이**를 센다: 움직임과 다음 움직임 사이의 시간을 [WINDOW_MS](1분)까지. 1분 넘게 손을 대지 않았으면 마지막 1분만 읽은
 * 것으로 본다(긴 그림 한 칸을 들여다보는 데 1분이면 넉넉하고, 펴 둔 채 자리를 뜬 시간은 들어가지 않는다).
 *
 * 시간은 앞 움직임 때 화면에 있던 화([moved] 의 item)에 붙는다 — 그 화를 보고 있었다.
 */
class ScrollClock<T : Any> {
    private var item: T? = null
    private var last = 0L
    private var paused = false

    /** 화면이 움직였다. 앞 움직임부터 지금까지(최대 1분)를 앞 화에. */
    fun moved(item: T, now: Long): Pair<T, Long>? {
        val before = this.item
        val gap = now - last
        this.item = item
        last = now
        if (paused || before == null || gap <= 0) return null
        return before to gap.coerceAtMost(WINDOW_MS)
    }

    /** 화면이 꺼짐 · 앱이 뒤로 감 · 닫음: 마지막 움직임부터 지금까지(최대 1분). */
    fun pause(now: Long): Pair<T, Long>? {
        if (paused) return null
        paused = true
        val before = item ?: return null
        val gap = now - last
        return if (gap > 0) before to gap.coerceAtMost(WINDOW_MS) else null
    }

    /** 다시 앞에 왔다. 다음 움직임까지의 시간은 돌아온 때부터 센다. */
    fun resume(now: Long) {
        if (!paused) return
        paused = false
        last = now
    }

    companion object {
        const val WINDOW_MS: Long = 60_000L
    }
}

/**
 * 웹툰 화면에 [ScrollClock] 을 잇는다. 돌려준 함수를 화면이 움직일 때마다 지금 화로 부른다. 화면이 꺼지고 켜질 때 · 닫을 때는
 * 여기서 멈추고 다시 센다.
 */
@Composable
fun <T : Any> rememberScrollTime(onTime: (item: T, millis: Long) -> Unit): (T) -> Unit {
    val clock = LocalReadingClock.current
    val timer = remember { ScrollClock<T>() }
    val report by rememberUpdatedState(onTime)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, timer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> timer.pause(clock())?.let { (item, ms) -> report(item, ms) }
                androidx.lifecycle.Lifecycle.Event.ON_START -> timer.resume(clock())
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            timer.pause(clock())?.let { (item, ms) -> report(item, ms) }
        }
    }
    return remember(timer) { { item: T -> timer.moved(item, clock())?.let { (before, ms) -> report(before, ms) } } }
}
