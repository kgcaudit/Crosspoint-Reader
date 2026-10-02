package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.kgcaudit.reader.ui.design.CpPageTurn
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.LocalTurnFeedback
import io.github.kgcaudit.reader.ui.design.PageTurn
import io.github.kgcaudit.reader.ui.design.PageTurnState
import io.github.kgcaudit.reader.ui.design.TurnFeedback
import io.github.kgcaudit.reader.ui.design.TurnSound
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 책장 넘김(0.32.0): 사람이 넘길 때만 효과 · 소리, 말림은 접힌 줄이 기울어 오른쪽 아래부터 넘어가고, 덜 끌고 놓으면
 * 되돌아간다. 쪽 대신 단색 판 둘을 넘겨 화소로 잰다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class PageTurnTest {

    /** 부품만 띄우는 빈 화면은 시험용 매니페스트가 디버그에만 있다(DesignRulesTest 와 같다). */
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

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    /** 소리 · 진동을 부른 횟수만 센다. */
    private class Counting : TurnFeedback {
        var turned = 0
        var previewed = 0
        override fun turned(sound: TurnSound, haptic: Boolean, view: View) { turned++ }
        override fun preview(sound: TurnSound) { previewed++ }
    }

    private val red = Color(0xFFD03030)
    private val blue = Color(0xFF2040C0)

    private var page by mutableIntStateOf(0)
    private var effect by mutableStateOf(PageTurn.Curl)
    private val turns = PageTurnState()
    private val feedback = Counting()

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalTurnFeedback provides feedback) {
                CpTheme(dark = false) {
                    CpPageTurn(page, effect, forward = { from, to -> to > from }, turns = turns, sound = TurnSound.Rustle) { p ->
                        Box(Modifier.fillMaxSize().background(if (p % 2 == 0) red else blue))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** 화면 쪽 상태를 바꾸고 화면이 따라올 때까지. 끄는 중이면 손을 뗄 때까지 멈춰 있으므로 그대로 쉰다. */
    private fun act(block: () -> Unit) {
        compose.runOnUiThread(block)
        compose.waitForIdle()
    }

    private fun frame(): Bitmap {
        val view = compose.activity.window.decorView
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(b)) }
        return b
    }

    private fun Bitmap.isNear(x: Float, y: Float, c: Color): Boolean {
        val p = getPixel((x * width).toInt(), (y * height).toInt())
        val dr = android.graphics.Color.red(p) - (c.red * 255)
        val dg = android.graphics.Color.green(p) - (c.green * 255)
        val db = android.graphics.Color.blue(p) - (c.blue * 255)
        return dr * dr + dg * dg + db * db < 40f * 40f
    }

    private fun save(b: Bitmap, name: String) = File(shots, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test
    fun `a curl turns from the bottom right corner while the top of the page is still unturned`() {
        show()
        // 끌기로 진행도를 정확히 20% 에 세운다.
        act { turns.dragStart(); page = 1 }
        act { turns.drag(0.2f) }
        val f = frame()
        save(f, "120-curl-20")
        // 오른쪽 아래는 말려 올라가 다음 쪽(파랑)이 드러났고, 오른쪽 위는 아직 지금 쪽(빨강)이다. 덮기 · 밀기라면 위아래가 같다.
        assertTrue(f.isNear(0.97f, 0.97f, blue), "오른쪽 아래에 다음 쪽이 드러나지 않았다")
        assertTrue(f.isNear(0.97f, 0.03f, red), "오른쪽 위가 먼저 넘어갔다 — 말림이 아니다")
        assertTrue(f.isNear(0.2f, 0.5f, red), "아직 넘어가지 않은 왼쪽이 바뀌었다")
        act { turns.dragEnd(true) { } }
        assertTrue(frame().isNear(0.5f, 0.5f, blue), "끝까지 넘겼는데 다음 쪽이 아니다")
    }

    @Test
    fun `letting go too early curls the page back and returns to it`() {
        show()
        var reverted = 0
        act { turns.dragStart(); page = 1 }
        act { turns.drag(0.1f) }
        act { turns.dragEnd(false) { reverted++; page = 0 } }
        assertEquals(1, reverted, "덜 끌고 놓았는데 앞 쪽으로 되돌리지 않았다")
        assertTrue(frame().isNear(0.5f, 0.5f, red), "되돌렸는데 지금 쪽이 아니다")
        assertEquals(0, feedback.turned, "넘기지 않았는데 넘김 소리가 났다")
    }

    @Test
    fun `only a page the reader turned gets the effect and the sound`() {
        show()
        // 다시 짠 쪽 · 목차로 건너뛴 쪽(요청 없음): 그 자리에서 바뀌고 소리도 없다.
        act { page = 1 }
        val jumped = frame()
        assertTrue(jumped.isNear(0.97f, 0.03f, blue) && jumped.isNear(0.5f, 0.5f, blue), "요청 없이 바뀐 쪽에 효과가 났다")
        assertEquals(0, feedback.turned, "건너뛴 쪽에서 넘김 소리가 났다")

        // 사람이 넘긴 쪽: 효과가 진행 중인 장면이 있고, 소리가 한 번. 진행 중 장면은 시계를 멈추고 조금씩 돌려 본다.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { turns.request(); page = 2 }
        var sawTurning = false
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            val f = frame()
            // 아래쪽 한가운데와 오른쪽 아래가 서로 다른 쪽이면 넘어가는 중이다.
            if (f.isNear(0.97f, 0.97f, red) != f.isNear(0.3f, 0.97f, red)) { sawTurning = true; save(f, "121-curl-tap") }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(sawTurning, "누른 넘김에 효과가 보이지 않는다")
        assertTrue(frame().isNear(0.5f, 0.5f, red), "넘김이 끝나지 않았다")
        assertEquals(1, feedback.turned, "넘김 소리가 한 번이 아니다")

        // 자동 넘김(조용한 요청): 효과는 있어도 소리는 없다.
        act { turns.request(quiet = true); page = 3 }
        assertEquals(1, feedback.turned, "자동 넘김에서 소리가 났다")
    }

    @Test
    fun `with no effect the page changes at once but a turned page still sounds`() {
        effect = PageTurn.None
        show()
        act { turns.request(); page = 1 }
        assertTrue(frame().isNear(0.97f, 0.97f, blue) && frame().isNear(0.03f, 0.03f, blue), "효과 없음인데 바뀌지 않았다")
        assertEquals(1, feedback.turned, "효과 없음에서 넘김 소리를 빼먹었다")
    }

    @Test
    fun `cover slides the next page in over the current one`() {
        effect = PageTurn.Cover
        show()
        act { turns.dragStart(); page = 1 }
        act { turns.drag(0.5f) }
        val f = frame()
        save(f, "122-cover-50")
        // 오른쪽 절반은 위아래 모두 다음 쪽, 왼쪽은 지금 쪽.
        assertTrue(f.isNear(0.8f, 0.05f, blue) && f.isNear(0.8f, 0.95f, blue), "다음 쪽이 오른쪽에서 덮지 않았다")
        assertTrue(f.isNear(0.2f, 0.5f, red), "왼쪽이 벌써 바뀌었다")
        act { turns.dragEnd(true) { } }
    }
}
