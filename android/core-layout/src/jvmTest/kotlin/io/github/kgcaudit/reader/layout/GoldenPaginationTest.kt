package io.github.kgcaudit.reader.layout

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 페이지 분할 골든 테스트.
 *
 * 조판 코드를 건드릴 때 **영향 범위를 눈으로 보는** 장치다. 기대값이 테스트 소스에
 * 박혀 있으므로, 줄바꿈이나 페이지 채우기를 바꾸면 diff 로 드러나고 사람이 검토한다.
 * 의도한 변경이면 기대값을 갱신하고, 의도하지 않은 변경이면 버그를 잡은 것이다.
 *
 * [FakeMeasurer] 로 측정하므로 결과가 완전히 결정적이다. 실제 `Paint` 로 하면 폰트
 * 버전이 바뀔 때마다 기대값이 흔들려 회귀 감지 기능을 잃는다.
 */
class GoldenPaginationTest {

    private val measurer = FakeMeasurer(baseSizePx = 10f, lineHeightRatio = 1f)

    private val spec = LayoutSpec(
        viewportWidthPx = 360f,
        viewportHeightPx = 120f,
        margin = Insets.all(20f),
        baseSizePx = 10f,
        lineHeightMultiplier = 1.2f,
        align = TextAlign.Justify,
        paragraphIndentEm = 1f,
        paragraphSpacingEm = 0.5f,
        breakBetweenCjk = true,
    )

    /** 세 문단짜리 한국어 본문. 한글·라틴·숫자·구두점이 섞여 있다. */
    private val chapter = buildString {
        append("어린 왕자는 사막에 떨어진 조종사를 만났다. ")
        append("그는 양 한 마리를 그려 달라고 부탁했고, 조종사는 세 번을 다시 그렸다.\n")
        append("별에서 온 아이는 장미 한 송이를 두고 왔다고 했다. ")
        append("그 장미는 까다롭고 허영심이 많았지만 그에게는 하나뿐인 꽃이었다.\n")
        append("여우는 말했다. \"가장 중요한 것은 눈에 보이지 않아.\" ")
        append("길들인다는 것은 관계를 만드는 일이라고, 1943년에 쓰인 그 문장은 말한다.")
    }

    private fun blocks(): List<Block> {
        val result = ArrayList<Block>()
        var offset = 0
        chapter.split('\n').forEach { para ->
            result.add(Block.Paragraph(listOf(InlineRun(offset, offset + para.length))))
            offset += para.length + 1 // 개행 한 글자
        }
        return result
    }

    @Test
    fun `page boundaries match the golden snapshot`() {
        val pages = Paginator(spec, measurer).paginate(chapter, blocks()).toList()
        val actual = pages.map { it.startChar to it.endCharExclusive }
        assertEquals(GOLDEN_BOUNDARIES, actual, describe(pages))
    }

    @Test
    fun `line counts per page match the golden snapshot`() {
        val pages = Paginator(spec, measurer).paginate(chapter, blocks()).toList()
        val actual = pages.map { page -> page.runs.map { it.baselineYPx }.distinct().size }
        assertEquals(GOLDEN_LINE_COUNTS, actual, describe(pages))
    }

    @Test
    fun `the whole chapter is covered with no gap or overlap`() {
        // 골든이 갱신될 때도 이 성질은 깨지면 안 된다. 깨지면 이어읽기가 어긋난다.
        val pages = Paginator(spec, measurer).paginate(chapter, blocks()).toList()
        assertEquals(0, pages.first().startChar)
        pages.zipWithNext { a, b -> assertEquals(a.endCharExclusive, b.startChar) }
    }

    /** 실패했을 때 무엇이 어떻게 갈렸는지 바로 보이게 한다. */
    private fun describe(pages: List<Page>): String = buildString {
        appendLine("실제 조판 결과 ${pages.size}페이지:")
        pages.forEach { page ->
            appendLine("  p${page.index} [${page.startChar}, ${page.endCharExclusive})")
            page.runs.groupBy { it.baselineYPx }.toSortedMap().forEach { (_, runs) ->
                // 조각이 아니라 구간으로 복원한다 — 조각은 줄 끝 공백을 제외하므로
                // 이어 붙이면 공백이 사라진 것처럼 보인다.
                val line = chapter.substring(runs.first().start, runs.last().endExclusive)
                appendLine("    | $line")
            }
        }
    }

    private companion object {
        // ── GOLDEN ──────────────────────────────────────────────────
        // 조판을 바꾸면 여기가 깨진다. 실패 메시지가 아래 형태로 실제 조판을 찍어
        // 주므로, diff 를 눈으로 보고 의도한 변경이면 갱신하고 아니면 버그를 잡는다.
        //
        //   p0 [0, 108)
        //     | 어린 왕자는 사막에 떨어진 조종사
        //     | 를 만났다. 그는 양 한 마리를 그려
        //     | 달라고 부탁했고, 조종사는 세 번을
        //     | 다시 그렸다.
        //     | 별에서 온 아이는 장미 한 송이를 두
        //     | 고 왔다고 했다. 그 장미는 까다롭고
        //   p1 [108, 209)
        //     | 허영심이 많았지만 그에게는 하나뿐
        //     | 인 꽃이었다.
        //     | 여우는 말했다. "가장 중요한 것은
        //     | 눈에 보이지 않아." 길들인다는 것은
        //     | 관계를 만드는 일이라고, 1943년에
        //     | 쓰인 그 문장은 말한다.
        //
        // 여기서 확인되는 것: 한글이 글자 단위로 끊기고, 어절 사이 공백이 보존되며,
        // 문단 첫 줄이 들여써지고, 라틴·숫자가 섞여도 줄이 폭을 채운다.
        val GOLDEN_BOUNDARIES = listOf(0 to 108, 108 to 209)
        val GOLDEN_LINE_COUNTS = listOf(6, 6)
    }
}
