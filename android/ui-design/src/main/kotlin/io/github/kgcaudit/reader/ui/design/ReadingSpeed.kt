package io.github.kgcaudit.reader.ui.design

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
