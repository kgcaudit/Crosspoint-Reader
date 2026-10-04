package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpReaderBar
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToolButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 읽기 메뉴가 쪽을 얼마나 가리는지(0.45.0, 2026-10-04 사용자 결정 — 위 64 · 아래 112dp 가 과했다). 높이는 그린 화면에서
 * 판 색이 이어지는 줄 수로 잰다 — 부품의 크기 값을 읽으면 안쪽 여백이 늘어난 것을 놓친다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h640dp-xhdpi")
class ReaderBarTest {

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

    @Test
    fun `the reading menu covers at most 52dp above and 86dp below the page`() {
        compose.setContent {
            CpTheme {
                // 쪽 자리는 판과 확 다른 색 — 판이 끝나는 줄을 색으로 찾는다.
                Box(Modifier.fillMaxSize().background(Color(0xFF2050C0))) {
                    CpReaderBar("시티 오브 걸스", "2 / 39장", false, {}, {}, {}, 0f, { "0%" }, {}, onSearch = {}, onListen = {}) {
                        CpToolButton(CpIcons.Toc, "목차", {}); CpToolButton(CpIcons.Note, "독서노트", {}); CpToolButton(CpIcons.View, "보기", {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        val density = compose.activity.resources.displayMetrics.density
        // 오른쪽 끝 한 줄을 따라 쪽 색(파랑)이 아닌 줄을 위 · 아래에서 센다.
        val x = bitmap.width - 2
        fun page(y: Int) = android.graphics.Color.blue(bitmap.getPixel(x, y)) > 150 && android.graphics.Color.red(bitmap.getPixel(x, y)) < 80
        val top = (0 until bitmap.height).first { page(it) } / density
        val bottom = (bitmap.height - 1 - (bitmap.height - 1 downTo 0).first { page(it) }) / density
        assertEquals(52f, top, 1f, "위 판 높이")
        assertEquals(86f, bottom, 1f, "아래 판 높이")
        // 줄였어도 다 있다: 제목 · 부제 · 찾기 · 듣기 · 책갈피 · 진행 막대 · 세 단추와 그 이름.
        for (t in listOf("시티 오브 걸스", "2 / 39장", "0%", "목차", "독서노트", "보기")) {
            assertTrue(compose.onAllNodes(hasText(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "$t 가 없다")
        }
        for (d in listOf("뒤로", "책에서 찾기", "듣기", "책갈피 꽂기", "지금 위치")) {
            assertTrue(compose.onAllNodes(hasContentDescription(d), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "$d 단추가 없다")
        }
    }

    @Test
    fun `a very long title stays on one line`() {
        compose.setContent {
            CpTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF2050C0))) {
                    CpReaderBar("아주 긴 제목이 붙은 책 — 부제까지 제목에 들어간 출판사 파일의 이름 그대로", "2 / 39장", false, {}, {}, {}, 0f, { "0%" }, {}) {
                        CpToolButton(CpIcons.Toc, "목차", {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val title = compose.onAllNodes(hasText("아주 긴 제목", substring = true), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(title.height / density < 30f, "긴 제목이 두 줄로 늘었다: ${title.height / density}dp")
    }
}
