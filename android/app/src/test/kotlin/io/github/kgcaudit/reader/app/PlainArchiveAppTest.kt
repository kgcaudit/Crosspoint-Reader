package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.archive.StoredArchives
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 이름이 만화라고 말하지 않는 rar · 7z(0.49.0): zip 처럼 살펴서 그림만 들었으면 만화로 보이고, 문서가 섞였으면 숨는다.
 * 예전에는 서재 훑기가 .rar · .7z 를 아예 보지 않아, 받은 그대로 둔 만화가 서재에 없었다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class PlainArchiveAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private val red = 0xFFD03030.toInt()

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun place() {
        val root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        val pages = listOf("001.png" to png(red), "002.png" to png(red))
        File(root, "C").mkdirs()
        File(root, "C/달 01권.rar").writeBytes(StoredArchives.rar4(pages))
        // 사진 몇 장과 문서가 함께 든 rar — 만화가 아니다.
        File(root, "C/여행 사진.rar").writeBytes(StoredArchives.rar4(pages + ("일정.docx" to ByteArray(300) { 1 })))
        File(root, "C/해 02권.7z").writeBytes(javaClass.getResource("/comic.cb7")!!.readBytes())
        // 그림 한 장뿐인 rar — 그냥 압축이면 둘 이상이어야 만화다.
        File(root, "C/한 장.rar").writeBytes(StoredArchives.rar4(pages.take(1)))
    }

    private fun visibleNames(): Set<String> = runBlocking { app.container.data.comics.units().first().map { it.name }.toSet() }

    @Test
    fun `plain rar and 7z of pictures show as comics while mixed ones stay hidden`() {
        place()
        val data = app.container.data
        runBlocking {
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
        }
        // 살피기 전에는 하나도 보이지 않는다 — 사진 묶음이 잠깐 만화 칸에 떴다 사라지면 안 된다.
        assertEquals(emptySet(), visibleNames())
        runBlocking { data.probeComics() }
        assertEquals(setOf("달 01권.rar", "해 02권.7z"), visibleNames())
    }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { has(matcher) }
    private fun click(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onAllNodes(matcher, useUnmergedTree = true).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
    }

    private fun screen(): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    @Test
    fun `a plain rar comic opens and its first page is drawn`() {
        place()
        runBlocking {
            val data = app.container.data
            data.folders.register(FolderProvider.treeUri)
            data.rescanAll()
            data.probeComics()
        }
        compose.activityRule.scenario.recreate()
        click(hasText("만화 2"))
        click(hasContentDescription("달 작품"))
        click(hasText("1권"))
        waitFor(hasContentDescription("만화 1쪽"))
        val ok = runCatching {
            compose.waitUntil(30_000) {
                screen().let { val p = it.getPixel(it.width / 2, it.height / 2); android.graphics.Color.red(p) > 180 && android.graphics.Color.blue(p) < 90 }
            }
        }.isSuccess
        assertTrue(ok, "rar 1쪽(빨강)이 그려지지 않았다")
    }
}
