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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
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
 * 앱 정보 · 라이선스(0.21.0). 판 번호를 폰에서 바로 보고, 앱에 들어간 남의 것의 고지문을 원문 그대로 싣는다 —
 * 고지문이 없으면 0.20.4 부터 넣은 Adobe-KR 표를 허가 조건 밖에서 배포하는 셈이다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class AboutTest {

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

    private fun hasNode(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(30_000) { hasNode(matcher) }

    private fun tap(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true)[0].performClick()

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun shot(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(shots, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun withBooks() {
        val folder = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(folder, "소설").mkdirs()
        File(folder, "소설/어린 왕자.epub").writeBytes(SampleBooks.epub())
        runBlocking { app.container.data.folders.register(FolderProvider.treeUri) }
    }

    @Test
    fun `the version and the licenses are a tap away from the library and back returns step by step`() {
        withBooks()
        compose.activityRule.scenario.recreate()
        waitFor(hasText("어린 왕자", substring = true))
        shot("90-library-info-button")
        tap(hasContentDescription("앱 정보"))
        // 폰에 깔린 판을 여기서 본다 — 빌드 파일의 판 번호가 그대로 나와야 한다.
        waitFor(hasText("판 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", substring = true))
        shot("91-about")

        tap(hasText("Adobe-KR 글자 표"))
        waitFor(hasText("Copyright 1990-2022 Adobe", substring = true))
        shot("92-license-adobe")

        // 뒤로는 한 단씩: 본문 → 앱 정보 → 라이브러리. 곧장 라이브러리로 가면 라이선스를 차례로 볼 때마다 다시 들어와야 한다.
        back()
        waitFor(hasText("오픈소스 라이선스"))
        back()
        waitFor(hasText("어린 왕자", substring = true))
        assertTrue(!hasNode(hasText("오픈소스 라이선스")))
    }

    @Test
    fun `the about button is there even before any folder is added`() {
        // 판 번호를 찾는 일은 무언가 안 될 때 생긴다 — 책이 하나도 없는 첫 화면에서도 닿아야 한다.
        FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        waitFor(hasText("아직 책이 없습니다"))
        tap(hasContentDescription("앱 정보"))
        waitFor(hasText("오픈소스 라이선스"))
    }

    @Test
    fun `every license shown carries its whole original text`() {
        val texts = OPEN_LICENSES.associate { it.title to app.resources.openRawResource(it.text).bufferedReader().use { r -> r.readText() } }
        val adobe = texts.getValue("Adobe-KR 글자 표")
        // BSD 의 세 조건과 면책 문구가 빠지면 허가 조건을 지키지 못한다. 잘린 파일이 여기서 걸린다.
        listOf("Redistributions of source code", "Redistributions in binary form", "Neither the name of Adobe", "EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE")
            .forEach { assertTrue(it in adobe, "Adobe 고지문에 \"$it\" 이 없다") }
        // 앱에 싣는 고지문은 표를 넣은 코드(AdobeKr.kt) 머리의 것과 같아야 한다 — 표를 새 판으로 바꾸고 이 파일을 잊으면 걸린다.
        val source = File("../reader-pdf/src/main/kotlin/io/github/kgcaudit/reader/pdf/AdobeKr.kt").readText()
            .substringAfter("-----------------------------------------------------------").substringBefore("-----------------------------------------------------------")
            .lines().joinToString(" ") { it.removePrefix(" *").trim() }
        fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        assertEquals(words(source), words(adobe))
        // UnRAR 허가의 2항(이 문단을 그대로 실어야 고친 원본을 함께 배포할 수 있다)과 RAR 을 만드는 데 쓰지 말라는 조건.
        val unrar = texts.getValue("UnRAR")
        listOf("UnRAR source code may be used in any software to handle", "cannot be", "used to develop RAR (WinRAR) compatible archiver", "full text of this paragraph")
            .forEach { assertTrue(it in unrar, "UnRAR 고지문에 \"$it\" 이 없다") }
        // 우리가 넣은 원본의 것과 같아야 한다 — 원본을 새 판으로 바꾸고 이 파일을 잊으면 걸린다.
        assertEquals(File("../archive/src/main/cpp/unrar/license.txt").readText(), unrar)
        assertEquals(File("../archive/src/main/cpp/sevenz/lzma-sdk-license.txt").readText(), texts.getValue("LZMA SDK (7-Zip)"))
        OPEN_LICENSES.filter { it.badge == "Apache 2.0" }.forEach {
            val text = texts.getValue(it.title)
            assertTrue("Version 2.0, January 2004" in text && "END OF TERMS AND CONDITIONS" in text, "${it.title}: Apache 원문이 온전하지 않다")
        }
    }

    @Test
    fun `the build date reads as a korean date and an odd one is shown as it is`() {
        assertEquals("2026년 9월 27일", koreanDate("2026-09-27"))
        // 망가진 값은 그대로 보인다 — 날짜 한 줄 때문에 앱 정보가 열리지 않는 것보다 낫다.
        assertEquals("dev", koreanDate("dev"))
        assertEquals("2026-9", koreanDate("2026-9"))
    }
}
