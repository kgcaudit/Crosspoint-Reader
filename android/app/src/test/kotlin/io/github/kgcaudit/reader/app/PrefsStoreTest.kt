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
    fun `a font chosen before the bundled fonts were removed keeps its look`() {
        // 0.3.0 은 "batang@kopubworld-1.0.3" 처럼 번들 폰트 ID 를 저장했다. 옮기지 않으면
        // 모르는 키가 되어 기기 기본으로 떨어진다 — 명조 있는 기기에서 고딕을 고른 사람이
        // 업데이트 한 번에 명조로 바뀐다.
        saveRaw("gothic")
        assertEquals(FontCatalog.SANS, PrefsStore(context).load().font)
        saveRaw("batang@kopubworld-1.0.3")
        assertEquals(FontCatalog.SERIF, PrefsStore(context).load().font)
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
