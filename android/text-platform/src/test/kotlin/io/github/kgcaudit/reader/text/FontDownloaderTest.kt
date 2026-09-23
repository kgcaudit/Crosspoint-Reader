package io.github.kgcaudit.reader.text

import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 추천 글꼴 받기. 실제 제공자(Google Play 서비스)는 테스트 환경에 없으므로 가짜 제공자가 테스트 폰트를
 * 내준다 — 받은 뒤의 길(사용자 글꼴로 넣기·짝짓기·실패 처리)은 실제와 같다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FontDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val user by lazy { UserFonts(temp.newFolder("fonts")) }

    /** 파일 안의 가족 이름이 "Olo Test Sans" 인 가상의 추천 글꼴. */
    private val font = RecommendedFont("Olo Test Sans", "olo test sans", "올로 산스", serif = false, weights = listOf(400, 700))

    private fun serving(vararg byWeight: Pair<Int, Fetched>) = FontSource { _, weight ->
        byWeight.toMap()[weight] ?: Fetched.Failed(DownloadFailure.NotFound)
    }

    private fun file(name: String) = Fetched.File { TestFonts.file(name).inputStream() }

    @Test
    fun `a downloaded font arrives as one family with its bold`() {
        val downloader = FontDownloader(user, serving(400 to file("olo-test-regular.ttf"), 700 to file("olo-test-bold.ttf")))
        assertFalse(downloader.isInstalled(font))
        val result = downloader.download(font)
        assertEquals(FontDownloader.Result.Done(font.key), result)
        assertTrue(downloader.isInstalled(font), "받은 글꼴은 목록에서 \"받을 수 있는 글꼴\" 에서 빠진다")
        val family = assertNotNull(user.family(font.key))
        assertNotNull(family.bold, "굵게도 받아 짝지어야 한다")
        // 받은 글꼴은 앱 안에 복사돼 있다 — 제공자의 캐시가 비워져도, 인터넷이 없어도 남는다.
        assertEquals(2, family.files.size)
    }

    @Test
    fun `a missing bold still gives a usable font`() {
        val downloader = FontDownloader(user, serving(400 to file("olo-test-regular.ttf"), 700 to Fetched.Failed(DownloadFailure.Network)))
        assertEquals(FontDownloader.Result.Done(font.key), downloader.download(font))
        assertEquals(null, user.family(font.key)!!.bold, "굵게는 합성한다")
    }

    @Test
    fun `failures say why and leave nothing half installed`() {
        for (reason in DownloadFailure.entries) {
            val result = FontDownloader(user, serving(400 to Fetched.Failed(reason))).download(font)
            assertEquals(FontDownloader.Result.Failed(reason), result)
        }
        // 제공자가 파일을 주지 못함(열기 실패) · 글꼴이 아닌 파일.
        assertEquals(FontDownloader.Result.Failed(DownloadFailure.Network), FontDownloader(user, serving(400 to Fetched.File { null })).download(font))
        val garbage = Fetched.File { ByteArray(500) { 7 }.inputStream() }
        assertEquals(FontDownloader.Result.Failed(DownloadFailure.Broken), FontDownloader(user, serving(400 to garbage)).download(font))
        assertTrue(user.families().isEmpty())
    }

    @Test
    fun `without Google Play services the phone says so instead of failing silently`() {
        // 테스트 환경은 Play 서비스가 없는 기기와 같다(중국판·일부 태블릿).
        val source = GmsFontSource(ApplicationProvider.getApplicationContext())
        assertEquals(Fetched.Failed(DownloadFailure.NoProvider), source.fetch("Nanum Myeongjo", 400))
    }

    @Test
    fun `recommended names match the files Google Fonts serves`() {
        // 나눔 글꼴은 Google 이름("Nanum Myeongjo")과 파일 안 이름("NanumMyeongjo")이 다르다. 파일 이름으로
        // 키를 만들어야 받은 뒤 "이미 받음" 이 된다(값은 google/fonts 저장소 파일에서 확인했다).
        val keys = RecommendedFonts.all.associate { it.family to it.key }
        assertEquals("user:nanummyeongjo", keys["Nanum Myeongjo"])
        assertEquals("user:gowun batang", keys["Gowun Batang"])
        assertTrue(RecommendedFonts.all.first().serif, "명조가 앞에 온다 — 명조가 없는 기기가 이 기능이 필요한 이유다")
        assertEquals(RecommendedFonts.all.size, RecommendedFonts.all.map { it.key }.distinct().size)
    }
}
