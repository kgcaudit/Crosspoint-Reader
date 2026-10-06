package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertTrue

/** 책장 보기의 표지 그림자(0.48.6): 칸이 아니라 표지 그림 크기로 진다. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ShelfShadowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())
    private val app = ApplicationProvider.getApplicationContext<OloApp>()

    private fun png(w: Int, h: Int, color: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun `a cover lower than its slot casts no shadow box above it`() {
        // 칸 크기로 그림자를 깔던 때는 칸보다 낮은 표지(잡지 · 화보) 위로 빈 그림자 네모가 떴다(사용자 보고).
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        for ((name, size) in listOf("가는 문고본" to (230 to 420), "낮은 화보" to (290 to 300), "넓은 잡지" to (400 to 420), "보통 판형" to (290 to 420))) {
            val dir = File(root, "Comic/$name 1권").apply { mkdirs() }
            for (i in 1..3) File(dir, "$i.png").writeBytes(png(size.first, size.second, 0xFF3A6EA5.toInt()))
        }
        app.container.comicRegions = false
        runBlocking {
            app.container.data.folders.register(FolderProvider.treeUri)
            app.container.data.rescanAll()
            app.container.data.probeComics()
        }
        compose.activityRule.scenario.recreate()
        fun has(m: androidx.compose.ui.test.SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(30_000) { has(hasText("만화 4")) }
        compose.onAllNodes(hasText("만화 4"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(30_000) { has(hasContentDescription("책장으로 보기")) }
        compose.onAllNodes(hasContentDescription("책장으로 보기"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(30_000) { has(hasContentDescription("낮은 화보 표지")) && has(hasContentDescription("보통 판형 표지")) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        fun box(name: String) = compose.onAllNodes(hasContentDescription("$name 표지"), useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val low = box("낮은 화보")
        val tall = box("가는 문고본")
        val other = box("보통 판형")
        assertTrue(low.top > tall.top + 40, "시험 준비: 낮은 표지가 칸보다 낮지 않다")
        assertTrue(other.top > low.bottom, "시험 준비: 보통 판형이 아랫줄에 있지 않다")
        val view = compose.activity.window.decorView
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(b)) }
        fun lum(x: Float, y: Float) = b.getPixel(x.toInt(), y.toInt()).let { android.graphics.Color.red(it) * 0.3f + android.graphics.Color.green(it) * 0.59f + android.graphics.Color.blue(it) * 0.11f }
        // 낮은 표지 바로 위(칸 안)와, 아랫줄의 같은 칸 자리(비어 있음)의 같은 높이 — 둘 다 그냥 나무 뒷벽이어야 한다.
        val y = (tall.top + low.top) / 2
        val above = lum(low.center.x, y)
        val empty = lum(low.center.x, other.top + (y - tall.top))
        assertTrue(above > empty * 0.85f, "낮은 표지 위에 그림자가 졌다: 위 $above · 빈 칸 $empty")
        // 표지 오른쪽 옆에는 여전히 그림자가 있다 — 그림자를 아예 없앤 것이 아니다.
        val side = lum(low.right + 8, (low.top + low.bottom) / 2)
        val wall = lum(low.right + 8, other.top + ((low.top + low.bottom) / 2 - tall.top))
        assertTrue(side < wall * 0.8f, "표지 옆 그림자가 없다: 옆 $side · 빈 칸 $wall")
    }
}
