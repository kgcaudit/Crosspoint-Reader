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
import org.junit.After
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

    /**
     * 넘김 효과(0.32.0 부터 기본 말림)가 도는 채로 시험이 끝나면, 같은 JVM 의 다음 시험 화면이 60초 동안 쉬지 못하고
     * 줄줄이 실패했다(AppNotIdleException). 쪽을 넘기고 끝나는 시험이 많아 시험마다 붙이지 않고 여기서 기다린다.
     */
    @After
    fun settleTurn() = compose.waitForIdle()

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
        // 연 책은 책장에만 있다 — 읽을 책에서는 빠진다(0.25.0). 큰 표지는 화면 폭의 1/3 가까이다.
        compose.waitUntil(30_000) { compose.onAllNodes(standIn("어린 왕자"), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        val widths = compose.onAllNodes(standIn("어린 왕자"), useUnmergedTree = true).fetchSemanticsNodes().map { it.size.width }
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(widths.max() / density > 90f, "책장의 표지가 작다: ${widths.map { it / density }}")
        assertTrue(has(hasText("0%")), "책장에 진도가 없다")
        shot("95-shelf")
    }

    @Test
    fun `a cover of any shape is shown whole and stands on the shelf line`() {
        // 씨네21(약 1 : 1.25)의 표지가 책장 칸(1 : 1.45)에 맞춰 양옆이 잘렸다. 표지는 원래 비율 그대로, 칸의 밑면에 선다.
        File(folder, "소설/바다 그림책.epub").writeBytes(coverEpub(png(Color.rgb(40, 110, 140), width = 400, height = 300), title = "바다"))
        compose.activityRule.scenario.recreate()
        listOf("바다 그림책.epub", "그림 표지.epub").forEachIndexed { i, name ->
            waitFor(hasText(name))
            compose.onAllNodes(hasText(name), useUnmergedTree = true)[0].performClick()
            waitFor(hasText("1 / ", substring = true))
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            // 한 쪽짜리 책이라 열면 바로 "다 읽은 책" 줄에 선다 — 어느 줄이든 책장이다.
            waitFor(hasText("읽은 책 · ${i + 1}권"))
        }
        // 한 번 열면 목록 이름이 책 제목이 된다.
        compose.waitUntil(30_000) { compose.onAllNodes(cover("바다"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        fun bounds(label: String) = compose.onAllNodes(cover(label), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }.maxBy { it.width }
        val wide = bounds("바다")
        val tall = bounds("그림 표지")
        assertEquals(4f / 3f, wide.width / wide.height, 0.03f, "넓은 표지가 잘리거나 늘어났다")
        assertEquals(300f / 450f, tall.width / tall.height, 0.03f, "세로 표지가 잘리거나 늘어났다")
        assertEquals(tall.bottom, wide.bottom, 2f, "표지의 밑면이 맞지 않는다")
        // 세로 표지(1 : 1.5)는 칸 높이를 채운다 — 칸 폭은 그 높이 / 1.45. 넓은 표지는 그 폭을 딱 채운다.
        assertEquals(tall.height / 1.45f, wide.width, 2f, "넓은 표지가 칸 폭을 넘거나 모자란다")
        shot("96-cover-shapes")
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
        fun coverEpub(cover: ByteArray, title: String = "그림 표지"): ByteArray {
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
                          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title></metadata>
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
