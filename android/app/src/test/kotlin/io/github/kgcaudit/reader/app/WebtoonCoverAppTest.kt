package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.ui.design.COVER_ASPECT
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertTrue

/**
 * 웹툰 작품의 표지(0.47.0, 사용자 결정 ⑩): 1화 첫 그림이 긴 띠여도 표지 칸을 채우고, 위쪽 빈 바탕이 아니라 첫 칸이 보인다.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class WebtoonCoverAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private val pink = 0xFFE8B4C0.toInt()

    /** 60×900: 위 200줄은 흰 바탕, 그 아래가 분홍 첫 칸. 통째로 표지에 넣으면 칸 높이에 맞춰 줄어 폭 1/10 막대가 된다. */
    private fun strip(): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        Canvas(bitmap).drawRect(0f, 200f, 60f, 900f, android.graphics.Paint().apply { color = pink })
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before
    fun setUp() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        for (ep in listOf("001화", "002화")) {
            val dir = File(root, "Webtoon/전학생/$ep").apply { mkdirs() }
            for (i in 1..3) File(dir, "$i.png").writeBytes(strip())
        }
        app.container.comicRegions = false
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        compose.activityRule.scenario.recreate()
    }

    @Test
    fun `a webtoon cover fills the cover slot and shows the first panel, not the blank top`() {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("만화 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("만화 1"), useUnmergedTree = true)[0].performClick()
        val cover = hasContentDescription("전학생 표지")
        compose.waitUntil(30_000) { compose.onAllNodes(cover, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val box = compose.onAllNodes(cover, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val aspect = box.width / box.height
        assertTrue(kotlin.math.abs(aspect - COVER_ASPECT) < 0.08f, "표지가 칸 모양이 아니다(긴 띠를 통째로 넣었다): ${box.width}×${box.height}")
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "webtoon-cover.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 표지 위쪽 1/4 지점이 분홍 — 흰 바탕부터 자르면 위 1/4 이 하얗다(200/870 줄).
        val px = bitmap.getPixel(box.center.x.toInt(), (box.top + box.height * 0.2f).toInt())
        fun d(f: (Int) -> Int) = (f(px) - f(pink)).let { it * it }
        val far = d(android.graphics.Color::red) + d(android.graphics.Color::green) + d(android.graphics.Color::blue)
        assertTrue(far < 30 * 30 * 3, "표지 위쪽이 첫 칸이 아니다(빈 바탕부터 잘랐다): #${Integer.toHexString(px)}")
    }
}
