package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.comic.CoverCrop
import io.github.kgcaudit.reader.ui.design.COVER_ASPECT
import io.github.kgcaudit.reader.ui.design.Pen
import org.junit.Test
import kotlin.test.assertEquals

/**
 * ui-design 은 :document 를 모른다(다른 앱에 넘기는 디자인 시스템이다). 그래서 두 모듈이 같아야 하는 값을 각자
 * 적는다 — 둘을 다 보는 이곳에서 같은지 지킨다.
 */
class DesignDocumentAgreementTest {

    @Test
    fun `a cover is cropped to the same shape as the slot it is shown in`() {
        // 자르는 비율(document)과 칸의 비율(ui-design)이 어긋나면 잘라 낸 표지가 칸에서 다시 위아래나 옆이 빈다.
        assertEquals(1f, CoverCrop.ASPECT * COVER_ASPECT, 1e-6f)
    }

    @Test
    fun `a highlight keeps its color between the database and the pen`() {
        // 리더는 순번으로 오간다(HighlightColor.ordinal ↔ Pen.entries). 순서가 어긋나면 노랑으로 칠한 곳이 초록으로 보인다.
        assertEquals(HighlightColor.entries.map { it.name }, Pen.entries.map { it.name })
    }
}
