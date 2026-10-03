package io.github.kgcaudit.reader.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.kgcaudit.reader.ui.design.CpReaderKind
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpViewSettingsScreen
import io.github.kgcaudit.reader.ui.design.PdfFit
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * 모든 보기 설정이 리더마다 그 리더가 따르는 줄만 보이는지(0.42.0). 0.41 까지 만화 · 웹툰은 PDF 인 척 열어, 바꿔도 아무 일이
 * 없는 줄(두 쪽 보기 · 넘김 효과 · 자동 넘김 · 왼쪽 끝 밝기)이 보였다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h1400dp-xhdpi")
class ViewSettingsRowsTest {

    /** 부품만 띄우는 빈 화면은 시험용 매니페스트가 디버그에만 있다(PageTurnTest 와 같다). */
    @get:Rule(order = 0)
    val debugOnly = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                org.junit.Assume.assumeTrue(BuildConfig.DEBUG)
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun rows(reader: CpReaderKind, prefs: ScreenPrefs = ScreenPrefs()): Set<String> {
        compose.setContent { CpTheme { CpViewSettingsScreen(prefs, {}, {}, highlights = false, reader = reader) } }
        compose.waitForIdle()
        return compose.onAllNodes(SemanticsMatcher("text") { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) }, useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { n -> n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } }.toSet()
    }

    private val turnRows = listOf("넘김 효과", "넘김 소리", "넘김 진동", "자동 넘김")
    private val twoPageRows = listOf("가로에서 두 쪽 보기", "세로에서 두 쪽 보기", "두 쪽 보기에서 표지")

    private fun assertRows(shown: Set<String>, expected: Map<String, Boolean>) {
        val wrong = expected.filter { (row, want) -> (row in shown) != want }
        assertEquals(emptyMap(), wrong, "보여야 할 줄(true) · 없어야 할 줄(false)이 어긋났다")
    }

    @Test
    fun `comics show two page rows and the cover row but no page turn effect`() {
        val shown = rows(CpReaderKind.Comic)
        assertRows(shown, mapOf("넘김 효과" to false, "PDF" to false, "넘김 소리" to true, "자동 넘김" to true, "왼쪽 끝을 밀어 밝기 조절" to true) + twoPageRows.associateWith { true })
    }

    @Test
    fun `webtoons hide every row about turning pages and two pages`() {
        val shown = rows(CpReaderKind.Webtoon)
        assertRows(shown, (turnRows + twoPageRows + "왼쪽 끝을 밀어 밝기 조절").associateWith { false } + mapOf("하단 정보" to true, "화면 켜짐 유지" to true))
    }

    @Test
    fun `books and pdf keep every turning row`() {
        assertRows(rows(CpReaderKind.Book), (turnRows + "가로에서 두 쪽 보기").associateWith { true } + mapOf("두 쪽 보기에서 표지" to false))
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-xhdpi")
    fun `on a landscape phone the comic rows are all there too`() {
        val shown = rows(CpReaderKind.Comic)
        assertRows(shown, twoPageRows.associateWith { true } + mapOf("넘김 효과" to false))
    }

    @Test
    fun `pdf fit to width explains why two pages are off`() {
        val shown = rows(CpReaderKind.Pdf, ScreenPrefs(pdfFit = PdfFit.Width))
        assertRows(shown, mapOf("폭 맞춤에서는 한 쪽씩 보입니다" to true, "두 쪽 보기에서 표지" to true))
    }
}
