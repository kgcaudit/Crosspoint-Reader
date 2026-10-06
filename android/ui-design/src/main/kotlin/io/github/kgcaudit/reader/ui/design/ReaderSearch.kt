package io.github.kgcaudit.reader.ui.design

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 한 권에서의 찾기 상태. 단위(EPUB 은 장, PDF 는 쪽)를 차례로 찾아 찾는 대로 [results] 에 쌓는다. 화면(목록 · 결과
 * 막대)이 닫혀도 남는다 — "목록" 을 누르면 같은 결과로 돌아온다.
 *
 * 두 리더가 따로 쥐던 때는 한쪽만 고쳐져 갈라졌다: × 로 지울 때 PDF 는 결과를 비웠는데 EPUB 은 멈추기만
 * 해서, 칸은 비었는데 옛 목록과 "12곳 · 3장에서" 가 남아 지운 말의 결과처럼 보였다. 그래서 하나로 둔다.
 *
 * @param unit 요약에 붙는 단위 이름("장" · "쪽").
 * @param unitOf 결과 하나가 몇 번째 단위에서 나왔나 — 요약의 "몇 장에서" 를 센다.
 */
@Stable
class ReaderSearch<T>(private val unit: String, private val unitOf: (T) -> Int) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<T>>(emptyList())
        private set

    /** 찾기를 마친 단위 수. */
    var searched by mutableIntStateOf(0)
        private set

    /** 찾을 단위 전체 수. 찾기를 시작한 뒤에 알게 된다. */
    var units by mutableIntStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set

    /** 지금 보고 있는 결과(목록에서 누른 것). -1 이면 결과 막대를 띄우지 않는다. */
    var current by mutableIntStateOf(-1)

    /** [results] 를 낸 말. 결과를 연 뒤 칸의 글을 고쳐도 칠은 찾은 말을 따른다. */
    var searchedQuery = ""
        private set

    private var job: Job? = null

    /**
     * 단위마다 찾아 나오는 대로 목록에 더한다. 새로 찾으면 앞의 찾기는 멈춘다. 리더 없이 시험하려고 단위 수 · 단위
     * 하나 찾기를 함수로 받는다.
     */
    fun start(scope: CoroutineScope, unitCount: suspend () -> Int, searchUnit: suspend (unit: Int, query: String) -> List<T>) {
        val q = query.trim()
        job?.cancel()
        results = emptyList()
        current = -1
        searched = 0
        searchedQuery = q
        if (q.isEmpty()) return
        running = true
        // 걸어 둔 뒤에 시작한다 — 곧바로 도는 디스패처에서는 [job] 에 담기 전에 끝나, 아래 finally 가 자기 일을 못 알아보고
        // "찾는 중" 을 끄지 않았다.
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                units = unitCount()
                for (i in 0 until units) {
                    ensureActive()
                    val hits = searchUnit(i, q)
                    if (hits.isNotEmpty()) results = results + hits
                    searched = i + 1
                }
            } finally {
                // 새 찾기가 이 일을 취소하고 시작했으면 "찾는 중" 은 그쪽 것이다 — 옛 일이 끄면 새 찾기가 도는데 막대가 사라졌다.
                if (job === coroutineContext[Job]) running = false
            }
        }
        job = launched
        launched.start()
    }

    /**
     * 찾기를 지운다(검색 칸의 ×). 결과 · 요약 · 결과 막대를 모두 비운다 — 멈추기만 하면 칸은 비었는데 옛 결과 목록과
     * 요약이 그대로 남아, 지운 말의 결과처럼 보인다. 쪽의 칠은 화면이 지운다.
     */
    fun stop() {
        job?.cancel()
        running = false
        results = emptyList()
        searched = 0
        units = 0
        current = -1
    }

    /** 찾기 화면 위의 한 줄. */
    val summary: String
        get() = when {
            running -> "찾는 중… $searched / $units$unit · 지금까지 ${results.size}곳"
            searched > 0 && results.isEmpty() -> "찾지 못했습니다"
            searched > 0 -> "${results.size}곳 · ${results.map(unitOf).distinct().size}${unit}에서"
            else -> ""
        }

    /** 찾는 동안의 진행(0~1). 찾지 않을 때는 null — 막대를 숨긴다. */
    val progress: Float?
        get() = if (running && units > 0) searched / units.toFloat() else null
}
