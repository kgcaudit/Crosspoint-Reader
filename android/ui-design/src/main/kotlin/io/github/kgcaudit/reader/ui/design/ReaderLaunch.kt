package io.github.kgcaudit.reader.ui.design

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 리더 화면의 동작 하나를 띄운다. 실패는 로그로 — 아무 흔적 없이 삼키면 "단추가 먹통" 인 원인을 기기에서 찾을 수 없다.
 * 화면에 알릴 실패는 리더가 제 상태(error)로 따로 낸다.
 */
fun CoroutineScope.go(block: suspend () -> Unit) {
    launch {
        runCatching { block() }.onFailure { if (it !is CancellationException) Log.w("OloReader", "reader action failed", it) }
    }
}

/**
 * 진행 막대의 0..1 → 쪽(0부터). 막대 위 숫자와 가는 곳이 같아야 한다 — PDF 의 seek 과 만화가 이 셈 하나를 쓴다.
 * 둘이 따로 셈하면 막대에 "12쪽" 이라 보이고 11쪽으로 간다.
 */
fun pageAt(fraction: Float, pageCount: Int): Int =
    if (pageCount <= 1) 0 else (fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt()
