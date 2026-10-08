package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue

/**
 * 문장 속 그림(로고 · 외자)이 실제 화면에서 글줄 안에 그려지는가. 조판 시험은 좌표만 본다 — 그리는 쪽이 그 그림을
 * 빠뜨리거나 그림 글자를 글꼴로 찍어도 거기서는 모른다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class InlineImageAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    /** 넘김 효과가 도는 채로 끝나면 다음 시험 화면이 쉬지 못한다(AppWalkthroughTest 와 같은 이유). */
    @After
    fun settleTurn() = compose.waitForIdle()

    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<OloApp>()
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "로고 책.epub").writeBytes(logoBook())
        compose.activity.container.data.folders.register(FolderProvider.treeUri)
        compose.activityRule.scenario.recreate()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a logo inside a sentence is drawn on the text line, between its words`() {
        compose.waitUntil(30_000) { has(hasText("로고 책.epub")) }
        compose.onAllNodes(hasText("로고 책.epub"), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(30_000) { has(hasText("1 / ", substring = true)) }
        // 그림은 조판 뒤 따로 풀린다. 칠해질 때까지 기다린다.
        compose.waitUntil(15_000) { blueBox(screen()) != null }
        val screen = screen()
        File(shots, "inline-logo.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val box = blueBox(screen)!!
        val density = compose.activity.resources.displayMetrics.density
        // 크기를 적지 않은 40px 로고는 글자 높이로 줄어든다. 예전 판은 40dp 블록으로 혼자 섰다.
        assertTrue(box.height() < 30 * density, "로고가 글자만 하게 줄지 않았다: ${box.height()}px")
        // 같은 줄: 로고 높이의 한가운데 행에, 로고 왼쪽에도 오른쪽에도 글자(어두운 점)가 있다. 블록이면 그 행은 비어 있다.
        val row = box.centerY()
        val left = (0 until box.left - 2).count { ink(screen.getPixel(it, row)) }
        val right = (box.right + 3 until screen.width).count { ink(screen.getPixel(it, row)) }
        assertTrue(left > 0 && right > 0, "로고가 글줄 안에 있지 않다: 왼쪽 글 점 $left, 오른쪽 글 점 $right")
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun ink(p: Int) = Color.red(p) < 110 && Color.green(p) < 110 && Color.blue(p) < 110

    /** 화면에서 파란 로고가 칠해진 영역. 없으면 null. */
    private fun blueBox(bitmap: Bitmap): android.graphics.Rect? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            if (Color.blue(p) > 170 && Color.red(p) < 60) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r, b)
    }

    /** 한 장짜리 책: 문장 한가운데 크기 없는 40×40 파란 로고. 실제로 올라온 책의 모양(외자 · 회사 로고)이다. */
    private fun logoBook(): ByteArray {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 60, 200)) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val text = linkedMapOf(
            "META-INF/container.xml" to
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>로고 책</dc:title></metadata>
                  <manifest>
                    <item id="c" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="i" href="Images/logo.png" media-type="image/png"/>
                  </manifest>
                  <spine><itemref idref="c"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/Text/c1.xhtml" to """
                <html><body>
                <p>우리 회사 <img src="../Images/logo.png"/> 는 작은 출판사에서 시작했다. 로고는 글자처럼 문장 안에 선다.</p>
                <p>다음 문단은 그림이 없다. 여기까지 읽으면 줄 간격이 고른지 볼 수 있다.</p>
                </body></html>
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                },
            )
            zip.write(mime)
            text.forEach { (name, body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()) }
            zip.putNextEntry(ZipEntry("OEBPS/Images/logo.png"))
            zip.write(png)
        }
        return out.toByteArray()
    }
}
