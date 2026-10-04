package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import io.github.kgcaudit.reader.data.ComicPages
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 지금 판과 앞뒤 판의 그림은 저장소가 내보내도 남는다(0.45.1). 태블릿 두 쪽에서 쪽 그림이 커서 저장소가 넘김 도중 지금 쪽을
 * 내보냈고, 넘김이 끝나 화면을 다시 짜는 한 장면 동안 그 쪽이 검게 비었다(사용자 동영상의 깜박임).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ComicBookPinTest {

    private fun png(): ByteArray {
        val b = Bitmap.createBitmap(30, 45, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** 저장소 한도 1바이트 — 무엇을 넣어도 곧바로 내보낸다. 붙잡은 쪽만 남아야 한다. */
    private fun book(): ComicBook {
        val bytes = png()
        val names = listOf("001.png", "002.png", "003.png")
        val unit = ComicUnit("u", "별 01권.cbz", listOf("C"), ComicUnitKind.ARCHIVE)
        return ComicBook(unit, ComicPages(names, { bytes.inputStream() }, null), regions = false, budget = 1)
    }

    @Test
    fun `pinned pages stay drawable when the cache lets them go, and are released when no longer near`() {
        val book = book()
        book.pin(setOf(0))
        runBlocking { book.page(0, 300, 400); book.page(1, 300, 400) }
        assertNotNull(book.cached(0, 300, 400), "붙잡은 지금 쪽을 저장소가 내보내자 그림이 사라졌다")
        // 붙잡지 않은 쪽은 저장소를 따른다 — 모든 쪽을 붙잡으면 긴 권에서 메모리가 넘친다.
        assertNull(book.cached(1, 300, 400))
        // 판이 옮겨 가면 멀어진 쪽은 놓는다.
        book.pin(setOf(1))
        assertNull(book.cached(0, 300, 400), "멀어진 쪽을 놓지 않았다")
        runBlocking { book.page(1, 300, 400) }
        assertNotNull(book.cached(1, 300, 400))
    }
}
