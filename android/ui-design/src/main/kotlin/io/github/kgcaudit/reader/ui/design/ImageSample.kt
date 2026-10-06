package io.github.kgcaudit.reader.ui.design

// 그림을 솎아 푸는 배수(BitmapFactory 의 inSampleSize) 셈. 책 그림(리플로우) · 만화 쪽 · 띠 · 표지가 같은 셈을 따로
// 적고 있었다 — 고칠 때 한 곳만 바뀌면 같은 그림이 어디서는 흐리고 어디서는 메모리를 넘친다. 기기 없이 시험하려고
// 순수 함수로 둔다. 배수는 늘 2의 거듭제곱이다(풀기가 가장 싸고, 풀개가 그 밖의 값은 내려 맞춘다).

/**
 * 솎아 푼 그림이 [minWidth] × [minHeight] 보다 작아지지 않는 가장 큰 배수. 한 변만 따지려면 다른 쪽을 0 으로 준다.
 * [from] 에서 시작한다 — 앞의 셈에 이어 다른 한도로 더 솎을 때.
 *
 * 둘 다 0 이하면 [from] 그대로다(무엇에도 맞출 것이 없다 — 그대로 두면 끝없이 두 배가 된다).
 */
fun sampleKeeping(width: Int, height: Int, minWidth: Int, minHeight: Int, from: Int = 1): Int {
    if (minWidth <= 0 && minHeight <= 0) return from
    var sample = from
    while (width / (sample * 2) >= minWidth && height / (sample * 2) >= minHeight) sample *= 2
    return sample
}

/** 솎아 푼 길이(또는 화소 수)가 [max] 이하가 될 때까지 [from] 을 두 배로. 메모리 한도를 지킬 때 쓴다. */
fun sampleWithin(length: Long, max: Long, from: Int = 1): Int {
    var sample = from
    while (length / sample > max) sample *= 2
    return sample
}

/** 솎아 푼 화소 수(가로 × 세로, 변마다 내림)가 [maxPixels] 이하가 될 때까지 [from] 을 두 배로. */
fun sampleWithinPixels(width: Int, height: Int, maxPixels: Long, from: Int = 1): Int {
    var sample = from
    while (width.toLong() / sample * (height / sample) > maxPixels) sample *= 2
    return sample
}
