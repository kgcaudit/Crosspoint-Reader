package io.github.kgcaudit.reader.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.text.FontCatalog
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
    fun `the chosen font survives a restart`() {
        val store = PrefsStore(context)
        store.save(ReaderPrefs(font = FontCatalog.SANS, fontSizeSp = 20))
        val loaded = PrefsStore(context).load()
        assertEquals(FontCatalog.SANS, loaded.font)
        assertEquals(20, loaded.fontSizeSp)
    }
}
