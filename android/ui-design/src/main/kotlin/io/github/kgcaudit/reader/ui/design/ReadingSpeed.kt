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

/**
 * 리더 화면의 읽는 시간 재기. 쪽 열쇠([key])나 [driven] 이 바뀔 때마다 [TurnSampler] 에 알리고, 사람이 읽은 쪽이면 [onDwell].
 *
 * 시각은 화면 그림 시계(withFrameMillis)다 — 쪽을 그린 때가 사람이 보기 시작한 때이고, 시험에서도 손으로 돌릴 수 있다.
 * [book] 이 바뀌면(다른 책) 새로 센다 — 앞 책의 마지막 쪽 시간이 새 책의 첫 넘김에 붙지 않게.
 */
@Composable
fun <K : Any> ReadingDwellEffect(
    book: Any,
    key: K?,
    units: Double,
    driven: Boolean,
    isNext: (before: K, after: K) -> Boolean,
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
}
