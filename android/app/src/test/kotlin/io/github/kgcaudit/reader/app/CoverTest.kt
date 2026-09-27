package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.DocumentsContract
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 책장 표지(0.22.0, 구상안 가안 확정). 책의 표지 · 대신 표지 · 사람이 고른 표지를 실제 앱 화면에서 본다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class CoverTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val shots = File(System.getProperty("reader.screenshots") ?: "build/screenshots").apply { mkdirs() }
    private lateinit var folder: File

    @Before
    fun setUp() {
        folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(app.filesDir, "covers").deleteRecursively()
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        File(folder, "소설/그림 표지.epub").writeBytes(coverEpub(png(Color.rgb(200, 60, 40))))
        File(folder, "메모").mkdirs()
        File(folder, "메모/옛 일기.txt").writeBytes(SampleBooks.txt())
        File(folder, "문서").mkdirs()
        File(folder, "문서/계약서.pdf").writeBytes(ByteArray(64))
        app.container.pdfEngine = { DrawnPdf(it, pageCount = 2) }
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
        compose.activityRule.scenario.recreate()
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun cover(label: String) = hasContentDescription("$label 표지")
    private fun standIn(label: String) = hasContentDescription("$label 대신 표지")

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 사진 고르기 흉내: 앱이 띄운 요청에 고른 그림의 URI 로 답한다. */
    private fun pick(path: String) {
        compose.waitForIdle()
        val activity = shadowOf(compose.activity)
        val request = checkNotNull(activity.nextStartedActivityForResult) { "사진 고르기를 띄우지 않았다" }
        val uri = DocumentsContract.buildDocumentUriUsingTree(FolderProvider.treeUri, "Books/$path")
        compose.runOnUiThread {
            activity.receiveResult(request.intent, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
        }
    }

    private fun longPress(label: String) {
        compose.onAllNodes(hasText(label), useUnmergedTree = true)[0].performTouchInput { longClick() }
        waitFor(hasText("사진 · 파일에서 표지 고르기"))
    }

    @Test
    fun `every book shows its own cover and a book without one shows a stand-in with its title`() {
        // EPUB 은 책에 든 표지 그림, PDF 는 첫 쪽. 표지 없는 EPUB · TXT 는 종류 색 위에 제목을 얹은 대신 표지.
        waitFor(cover("그림 표지.epub"))
        waitFor(cover("계약서.pdf"))
        waitFor(standIn("옛 일기.txt"))
        waitFor(standIn("어린 왕자.epub"))
        assertFalse(has(standIn("그림 표지.epub")))
        shot("93-library-covers")
    }

    @Test
    fun `recently read books sit on the shelf with a large cover and their progress`() {
        waitFor(hasText("어린 왕자.epub"))
        compose.onAllNodes(hasText("어린 왕자.epub"), useUnmergedTree = true)[0].performClick()
        waitFor(hasText("1 / ", substring = true))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor(hasText("읽는 중 · 1권"))
        // 책장(큰 표지)과 모든 책 목록(작은 표지)에 한 번씩. 큰 표지는 화면 폭의 1/3 가까이다.
        compose.waitUntil(30_000) { compose.onAllNodes(standIn("어린 왕자"), useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        val widths = compose.onAllNodes(standIn("어린 왕자"), useUnmergedTree = true).fetchSemanticsNodes().map { it.size.width }
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(widths.max() / density > 90f, "책장의 표지가 작다: ${widths.map { it / density }}")
        assertTrue(has(hasText("0%")), "책장에 진도가 없다")
        shot("95-shelf")
    }

    @Test
    fun `a picture picked from the phone becomes the cover and reverting brings back the stand-in`() {
        File(folder, "문서/사진.png").writeBytes(png(Color.rgb(40, 60, 160), width = 1200, height = 1800))
        waitFor(standIn("옛 일기.txt"))
        longPress("옛 일기.txt")
        compose.onAllNodes(hasText("사진 · 파일에서 표지 고르기"), useUnmergedTree = true)[0].performClick()
        compose.seeBriefly(hasText("‘옛 일기.txt’ 표지를 바꿨습니다")) { pick("문서/사진.png") }
        waitFor(cover("옛 일기.txt"))
        shot("94-custom-cover")
        // 원본을 지워도 표지는 남는다 — 줄인 사본을 앱 안에 둔다.
        File(folder, "문서/사진.png").delete()
        assertTrue(File(app.filesDir, "covers/custom").listFiles().orEmpty().size == 1)

        longPress("옛 일기.txt")
        waitFor(hasText("직접 고른 표지를 쓰고 있습니다."))
        compose.onAllNodes(hasText("대신 표지로 되돌리기"), useUnmergedTree = true)[0].performClick()
        waitFor(standIn("옛 일기.txt"))
        assertTrue(File(app.filesDir, "covers/custom").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a file that is not a picture is refused and the cover stays as it was`() {
        // 망가뜨린 입력: 그림이 아닌 파일. 멀쩡한 표지를 깨진 그림으로 덮지 않고 왜 안 되는지 알린다.
        File(folder, "문서/깨진.png").writeBytes("이건 그림이 아니다".toByteArray())
        waitFor(cover("그림 표지.epub"))
        longPress("그림 표지.epub")
        waitFor(hasText("지금 표지는 책에 든 표지 그림입니다."))
        compose.onAllNodes(hasText("사진 · 파일에서 표지 고르기"), useUnmergedTree = true)[0].performClick()
        pick("문서/깨진.png")
        waitFor(hasText("이 그림을 표지로 쓸 수 없습니다"))
        assertTrue(has(cover("그림 표지.epub")))
        assertEquals(0, File(app.filesDir, "covers/custom").listFiles().orEmpty().size)
    }

    companion object {
        fun png(color: Int, width: Int = 300, height: Int = 450): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }

        /** EPUB 3 의 표지 표시(`properties="cover-image"`)가 있는 책. */
        fun coverEpub(cover: ByteArray): ByteArray {
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
                val text = linkedMapOf(
                    "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                    "OEBPS/content.opf" to """
                        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>그림 표지</dc:title></metadata>
                          <manifest>
                            <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                            <item id="img" href="Images/front.png" media-type="image/png" properties="cover-image"/>
                          </manifest>
                          <spine><itemref idref="c1"/></spine>
                        </package>
                    """.trimIndent(),
                    "OEBPS/c1.xhtml" to "<html><body><p>표지가 있는 책.</p></body></html>",
                )
                text.forEach { (name, body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()) }
                zip.putNextEntry(ZipEntry("OEBPS/Images/front.png"))
                zip.write(cover)
            }
            return out.toByteArray()
        }
    }
}
