package io.github.kgcaudit.reader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.text.FontCatalog
import io.github.kgcaudit.reader.ui.design.ScreenRotation
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun saveRaw(font: String?) {
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putString("font", font).commit()
    }

    @Test
    fun `fonts that no longer exist are cleared to the phone font`() {
        // 0.3.0 의 번들 폰트("batang@kopubworld-1.0.3", "gothic")와 0.7.0 까지의 시스템 명조.
        // 남겨 두면 나중에 같은 이름의 키가 생겼을 때 옛 설정이 엉뚱한 글꼴을 가리킨다.
        for (old in listOf("gothic", "batang@kopubworld-1.0.3", "system-serif")) {
            saveRaw(old)
            assertNull(PrefsStore(context).load().font, old)
        }
    }

    @Test
    fun `a fresh install and a new key are left as they are`() {
        // 처음 쓰는 사람은 null(기기 기본). 새 키는 그대로 둔다 — 사용자 글꼴 키까지 바꾸면 안 된다.
        saveRaw(null)
        assertNull(PrefsStore(context).load().font)
        saveRaw("user:abc")
        assertEquals("user:abc", PrefsStore(context).load().font)
    }

    @Test
    fun `the screen rotation choice survives a restart and defaults to auto`() {
        // 처음 쓰는 사람은 "자동"(잠금과 상관없이 돈다). 모르는 값(나중 판에서 빠진 이름)도 자동으로.
        assertEquals(ScreenRotation.Auto, PrefsStore(context).load().screen.rotation)
        PrefsStore(context).save(ReaderPrefs(screen = io.github.kgcaudit.reader.ui.design.ScreenPrefs(rotation = ScreenRotation.Portrait)))
        assertEquals(ScreenRotation.Portrait, PrefsStore(context).load().screen.rotation)
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putString("rotation", "Sideways").commit()
        assertEquals(ScreenRotation.Auto, PrefsStore(context).load().screen.rotation)
    }

    @Test
    fun `the chosen font survives a restart`() {
        val store = PrefsStore(context)
        store.save(ReaderPrefs(font = FontCatalog.SANS, fontSizeSp = 20))
        val loaded = PrefsStore(context).load()
        assertEquals(FontCatalog.SANS, loaded.font)
        assertEquals(20, loaded.fontSizeSp)
    }

    @Test
    fun `every reading setting survives a restart and a fresh install keeps the old look`() {
        // 처음 쓰는 사람(또는 0.11.0 에서 올린 사람)은 0.11.0 과 같은 모양: 보통 여백 · 원본 정렬 · 좁은 문단 간격 ·
        // 시스템 배경 · 시스템 밝기 · 책 제목/쪽/%.
        val fresh = PrefsStore(context).load()
        assertEquals(ReaderPrefs(), fresh)

        val chosen = ReaderPrefs(
            margin = ReaderPrefs.Margin.Wide,
            align = ReaderPrefs.ParagraphAlign.Left,
            indent = ReaderPrefs.Indent.Off,
            paragraphSpacing = ReaderPrefs.ParagraphSpacing.Loose,
            screen = io.github.kgcaudit.reader.ui.design.ScreenPrefs(
                theme = io.github.kgcaudit.reader.ui.design.PaperTheme.Ivory,
                brightness = 0.4f,
                keepScreenOn = io.github.kgcaudit.reader.ui.design.KeepScreenOn.Always,
                volumeKeys = true,
                touch = io.github.kgcaudit.reader.ui.design.TouchZones.OneHand,
                footer = io.github.kgcaudit.reader.ui.design.Footer(
                    io.github.kgcaudit.reader.ui.design.FooterItem.Clock,
                    io.github.kgcaudit.reader.ui.design.FooterItem.None,
                    io.github.kgcaudit.reader.ui.design.FooterItem.Battery,
                ),
                rotation = ScreenRotation.Landscape,
                twoPagesLandscape = false,
                twoPagesPortrait = true,
                pdfCoverAlone = false,
                autoTurn = io.github.kgcaudit.reader.ui.design.AutoTurn.S30,
                showHighlights = false,
                pdfFit = io.github.kgcaudit.reader.ui.design.PdfFit.Width,
            ),
            listen = io.github.kgcaudit.reader.reflow.ListenPrefs(rate = 1.3f, engine = "com.samsung.SMT", voice = "ko-kr-x-1", voiceLabel = "Samsung TTS · 한국어 1"),
        )
        PrefsStore(context).save(chosen)
        assertEquals(chosen, PrefsStore(context).load())

        // "시스템 밝기" 로 돌리면 저장된 값도 지운다 — 남기면 다음에 열 때 어두운 채로 뜬다.
        PrefsStore(context).save(chosen.copy(screen = chosen.screen.copy(brightness = null)))
        assertNull(PrefsStore(context).load().screen.brightness)
    }

    @Test
    fun `a value from a later version falls back to the default instead of failing`() {
        // 나중 판에서 없어진 이름이 남아 있어도 책은 열려야 한다(규칙 6).
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit()
            .putString("theme", "Sepia").putString("footerLeft", "Weather").putString("margin", "Huge")
            .putString("pdfFit", "Height").commit()
        val loaded = PrefsStore(context).load()
        assertEquals(io.github.kgcaudit.reader.ui.design.PaperTheme.System, loaded.screen.theme)
        assertEquals(io.github.kgcaudit.reader.ui.design.FooterItem.BookTitle, loaded.screen.footer.left)
        assertEquals(ReaderPrefs.Margin.Normal, loaded.margin)
        assertEquals(io.github.kgcaudit.reader.ui.design.PdfFit.Page, loaded.screen.pdfFit)
    }

    @Test
    fun `a broken reading speed is brought back into range instead of stopping the voice`() {
        // 0 배속 · 무한대가 저장돼 있으면 듣기가 멈추거나 알아들을 수 없게 빠르다.
        val sp = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
        sp.edit().putFloat("listenRate", 0f).commit()
        assertEquals(0.5f, PrefsStore(context).load().listen.rate)
        sp.edit().putFloat("listenRate", Float.POSITIVE_INFINITY).commit()
        assertEquals(1f, PrefsStore(context).load().listen.rate)
        sp.edit().putFloat("listenRate", 9f).putString("autoTurn", "S5").commit()
        assertEquals(2f, PrefsStore(context).load().listen.rate)
        assertEquals(io.github.kgcaudit.reader.ui.design.AutoTurn.Off, PrefsStore(context).load().screen.autoTurn)
    }
}
