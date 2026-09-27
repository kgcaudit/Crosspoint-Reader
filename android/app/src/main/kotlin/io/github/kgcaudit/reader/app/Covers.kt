package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kgcaudit.reader.data.library.LibraryBook
import io.github.kgcaudit.reader.document.BookId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** 책장에 보일 표지. [image] 가 null 이면 대신 표지. */
class Cover(val image: ImageBitmap?, val custom: Boolean, val hasOwn: Boolean)

/**
 * 책 표지 보관소. 표지는 한 번 만들어 앱 파일 영역에 줄여 둔다 — 책장을 열 때마다 EPUB 을 풀거나 PDF 첫 쪽을 그리면
 * 목록이 늦게 채워진다.
 *
 * - `auto/`: 책에서 꺼낸 표지(EPUB 의 표지 그림 · PDF 첫 쪽). 표지가 없다고 확인된 책은 `.none` 표시만 남겨 다시 찾지
 *   않는다. 파일을 못 읽은 때(저장소가 빠짐)는 표시를 남기지 않는다 — 다음에 다시 찾는다.
 * - `custom/`: 사람이 고른 그림. 원본을 가리키지 않고 **줄인 사본**을 둔다 — 사진 앱에서 원본을 지우거나 옮겨도 표지가
 *   남아야 하고, 사진 고르기로 받은 읽기 허락은 앱을 다시 켜면 풀린다.
 *
 * 고른 표지가 있으면 책의 표지보다 먼저다. 되돌리면 고른 것만 지운다.
 */
class CoverStore(
    private val dir: File,
    /** 책에서 표지를 꺼낸다. 표지가 없으면 null, 파일을 못 읽으면 예외. */
    private val extract: suspend (LibraryBook) -> Bitmap?,
) {
    private val memory = LruCache<String, Cover>(MEMORY_COVERS)
    private val extracting = Mutex()
    private val _version = MutableStateFlow(0)

    /** 고른 표지가 바뀔 때마다 는다. 책장이 이것을 보고 표지를 다시 읽는다. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** 메모리에 있으면 기다리지 않고 준다(목록을 다시 그릴 때 깜빡이지 않게). */
    fun cached(id: BookId): Cover? = memory.get(key(id))

    suspend fun cover(book: LibraryBook): Cover = withContext(Dispatchers.IO) {
        val key = key(book.id)
        memory.get(key)?.let { return@withContext it }
        // 한 번에 한 권씩 꺼낸다. PDF 엔진 · 큰 EPUB 을 여러 권 동시에 열면 메모리가 모자란다.
        extracting.withLock {
            memory.get(key)?.let { return@withLock it }
            val custom = File(dir, "custom/$key.jpg").takeIf { it.isFile }?.let(::decodeFile)
            val own = ownCover(book, key)
            Cover(custom ?: own, custom = custom != null, hasOwn = own != null).also { memory.put(key, it) }
        }
    }

    /**
     * 사람이 고른 그림을 표지로 둔다. 그림으로 읽히지 않으면(그림이 아닌 파일 · 깨진 파일) false 이고 아무것도 바꾸지
     * 않는다 — 깨진 그림으로 멀쩡한 표지를 덮지 않는다.
     */
    suspend fun setCustom(id: BookId, input: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        // runCatching 은 Error(메모리 부족)까지 받는다 — 고른 그림이 너무 커도 "쓸 수 없습니다" 로 끝난다.
        val bitmap = runCatching { input()?.use { decodeScaled(it.readBytes()) } }.getOrNull() ?: return@withContext false
        val file = File(dir, "custom/${key(id)}.jpg")
        file.parentFile?.mkdirs()
        writeJpeg(bitmap, file)
        changed(id)
        true
    }

    suspend fun clearCustom(id: BookId) = withContext(Dispatchers.IO) {
        File(dir, "custom/${key(id)}.jpg").delete()
        changed(id)
    }

    private fun changed(id: BookId) {
        memory.remove(key(id))
        _version.value++
    }

    private suspend fun ownCover(book: LibraryBook, key: String): ImageBitmap? {
        val file = File(dir, "auto/$key.jpg")
        if (file.isFile) return decodeFile(file)
        val none = File(dir, "auto/$key.none")
        if (none.exists()) return null
        val bitmap = try {
            extract(book)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 오류(Error)까지 받는다: 큰 표지 그림이 메모리를 넘기거나(OutOfMemoryError) 엔진이 깨진 PDF 에서 무너져도,
            // 표지 하나 때문에 책장이 닫히면 안 된다 — 대신 표지를 보이면 된다.
            android.util.Log.w("OloCovers", "cover of ${book.displayName} unreadable", e)
            return null
        }
        file.parentFile?.mkdirs()
        if (bitmap == null) {
            none.createNewFile()
            return null
        }
        val scaled = scaleToHeight(bitmap)
        writeJpeg(scaled, file)
        return scaled.asImageBitmap()
    }

    private fun decodeFile(file: File): ImageBitmap? = BitmapFactory.decodeFile(file.path)?.asImageBitmap()

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        // 다 쓴 뒤 바꿔 끼운다 — 쓰다 멈추면(앱이 닫힘) 반쪽 그림이 표지로 남는다.
        tmp.renameTo(file)
    }

    companion object {
        /** 표지를 둘 높이(픽셀). 책장의 큰 표지(약 150dp)를 xxhdpi 에서 흐리지 않게 그리는 만큼. */
        const val COVER_HEIGHT = 480
        private const val JPEG_QUALITY = 85
        private const val MEMORY_COVERS = 64

        private fun key(id: BookId): String =
            java.security.MessageDigest.getInstance("SHA-1").digest(id.value.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(20)

        /**
         * 그림 바이트를 표지 크기로 읽는다. 먼저 크기만 재고 알맞게 솎아 읽는다 — 폰 사진(4000×3000)을 통째로 풀면
         * 48MB 라 메모리가 모자란다. 그림이 아니면 null.
         */
        fun decodeScaled(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= COVER_HEIGHT) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            return scaleToHeight(bitmap)
        }

        private fun scaleToHeight(bitmap: Bitmap): Bitmap {
            if (bitmap.height <= COVER_HEIGHT) return bitmap
            val width = (bitmap.width.toLong() * COVER_HEIGHT / bitmap.height).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, width, COVER_HEIGHT, true)
        }
    }
}
