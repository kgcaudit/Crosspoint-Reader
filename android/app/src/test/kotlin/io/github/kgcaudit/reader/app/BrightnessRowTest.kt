package io.github.kgcaudit.reader.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import io.github.kgcaudit.reader.ui.design.CpBrightnessRow
import io.github.kgcaudit.reader.ui.design.CpTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 보기 판의 밝기 막대(전체 점검에서 찾음). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrightnessRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `dragging the brightness bar previews it on the window and saves only once on release`() {
        // 미는 동안 저장하면 프레임마다 설정 전부를 다시 쓰고 리더 전체를 다시 그려 막대가 버벅였다.
        val saved = mutableListOf<Float?>()
        compose.setContent { CpTheme(dark = false) { CpBrightnessRow(0.2f, { saved += it }) } }
        compose.onNode(hasContentDescription("밝기"), useUnmergedTree = true).performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(1, saved.size, "미는 동안 저장했다: $saved")
        assertTrue((saved.single() ?: 0f) > 0.5f)
        // 미는 동안 창 밝기는 바뀌어 있었다(손을 떼도 저장한 값이 다시 걸릴 때까지 그대로).
        assertTrue(compose.activity.window.attributes.screenBrightness > 0.5f)
    }
}
