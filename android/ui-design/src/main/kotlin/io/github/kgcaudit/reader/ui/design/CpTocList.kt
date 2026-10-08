package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * 목차 한 줄. [depth] 단만큼 들인다 — 절은 장에 속한다(UI 규칙 1, 글자만 있는 줄은 한 단에 levelIndent).
 * [value] 는 오른쪽 끝의 글(PDF 의 인쇄된 쪽 번호). 이 모듈은 문서 모델(TocEntry)을 모르므로 리더가 이것으로 옮겨 준다.
 */
data class CpTocRow(val label: String, val depth: Int = 0, val value: String? = null)

/**
 * 목차 목록. EPUB · TXT 와 PDF 가 같은 것을 쓴다 — 사본이 둘이면 들여쓰기 · 열 때의 스크롤 같은 규칙을 한쪽만 고치게 된다.
 *
 * - [rows] 가 null 이면 아직 읽는 중이라 아무것도 그리지 않는다. 그동안 [empty] 를 보이면 목차가 있는 책도 "없다" 고 잠깐 말한다.
 * - 열면 지금 항목([current])이 화면 위쪽 3분의 1 쯤 오게 스크롤한다. 맨 위에 붙이면 앞 항목이 안 보여 "어디쯤인지" 가 안 읽힌다.
 *
 * @param current 지금 읽는 곳의 항목 번호(불이 켜진다). 없으면 -1. 어느 항목이 "지금" 인지는 위치 모양(Locator)이 리더마다
 *   달라 리더가 정한다.
 */
@Composable
fun CpTocList(rows: List<CpTocRow>?, current: Int, empty: String, onOpen: (Int) -> Unit) {
    when {
        rows == null -> Unit
        rows.isEmpty() -> CpEmptyMessage(empty)
        else -> {
            val list = rememberLazyListState()
            LaunchedEffect(current) { if (current > 0) list.scrollToItem((current - 3).coerceAtLeast(0)) }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                itemsIndexed(rows) { i, row ->
                    CpListRow(
                        title = row.label,
                        onClick = { onOpen(i) },
                        modifier = Modifier.padding(start = CpTheme.metrics.levelIndent * row.depth),
                        value = row.value,
                        selected = i == current,
                        compact = true,
                    )
                }
            }
        }
    }
}
