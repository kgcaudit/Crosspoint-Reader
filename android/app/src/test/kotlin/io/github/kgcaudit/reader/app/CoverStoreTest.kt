package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.library.LibraryBook
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CoverStoreTest {

    private val dir = File(ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir, "covers-test").apply { deleteRecursively() }
    private val book = LibraryBook(BookId("content://x/옛 일기.txt"), BookFormat.TXT, "옛 일기.txt", null, null, null)

    // 표지는 JPEG 로 줄여 둬 색이 한두 값 흔들린다 — 어느 색인지만 본다.
    private fun isBlue(p: Int) = Color.blue(p) > 200 && Color.red(p) < 50
    private fun isRed(p: Int) = Color.red(p) > 200 && Color.blue(p) < 50

    private fun red() = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    @Test
    fun `a book found to have no cover is not searched again, an unreadable one is`() = runBlocking {
        // 표지를 찾으려면 책을 열어야 한다(EPUB 풀기 · PDF 그리기). 없다고 확인된 책은 다음 실행에도 다시 열지 않는다.
        var asked = 0
        CoverStore(dir) { asked++; null }.cover(book)
        CoverStore(dir) { asked++; null }.cover(book)
        assertEquals(1, asked)
        // 망가뜨린 경우: 파일을 못 읽었다(저장소가 빠짐). "표지 없음" 으로 굳히지 않고 다음에 다시 찾는다.
        val other = book.copy(id = BookId("content://x/빠진 책.epub"))
        CoverStore(dir) { asked++; throw IOException("gone") }.cover(other)
        val found = CoverStore(dir) { asked++; red() }.cover(other)
        assertEquals(3, asked)
        assertNotNull(found.image)
        assertTrue(found.hasOwn)
    }

    @Test
    fun `a cover that runs out of memory falls back to the stand-in instead of closing the library`() = runBlocking {
        // 망가뜨린 경우: 엔진이 오류(Error)로 무너짐 — 큰 표지 그림의 메모리 부족, 깨진 PDF. 예외만 받던 첫 판은 이것이
        // 책장 화면까지 올라가 앱이 닫혔다(시험에서 깨진 PDF 로 재현).
        val cover = CoverStore(dir) { throw OutOfMemoryError("too big") }.cover(book)
        assertNull(cover.image)
        assertFalse(cover.hasOwn)
    }

    @Test
    fun `a picked picture wins over the book's own cover until it is reverted`() = runBlocking {
        val store = CoverStore(dir) { red() }
        assertFalse(store.cover(book).custom)
        val version = store.version.value
        val photo = CoverTest.png(Color.BLUE, width = 1200, height = 1800)
        assertTrue(store.setCustom(book.id) { photo.inputStream() })
        assertTrue(store.version.value > version, "책장이 다시 그리지 않는다")
        val custom = store.cover(book)
        assertTrue(custom.custom)
        assertTrue(isBlue(custom.image!!.asAndroidBitmap().getPixel(10, 10)), "고른 그림이 표지가 아니다")
        store.clearCustom(book.id)
        val own = store.cover(book)
        assertFalse(own.custom)
        assertTrue(isRed(own.image!!.asAndroidBitmap().getPixel(10, 10)), "되돌린 뒤 책의 표지가 아니다")
    }

    @Test
    fun `a phone photo is kept at cover size and a non picture is refused`() = runBlocking {
        // 폰 사진을 통째로 두면 표지 한 장이 수 MB — 책장이 느려진다. 표지 높이로 줄여 둔다.
        val big = CoverStore.decodeScaled(CoverTest.png(Color.GREEN, width = 2000, height = 3000))!!
        assertEquals(CoverStore.COVER_HEIGHT, big.height)
        assertEquals(320, big.width)
        assertNull(CoverStore.decodeScaled("그림이 아니다".toByteArray()))
        assertFalse(CoverStore(dir) { null }.setCustom(book.id) { "그림이 아니다".byteInputStream() })
        assertFalse(File(dir, "custom").listFiles().orEmpty().isNotEmpty())
    }
}
