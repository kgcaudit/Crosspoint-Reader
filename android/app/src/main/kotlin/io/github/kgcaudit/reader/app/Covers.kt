package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kgcaudit.reader.data.library.LibraryBook
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.comic.ComicUnit
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
    /** 만화 단위(압축 · 그림 폴더)에서 표지를 꺼낸다. 규칙은 [extract] 와 같다. 책 꺼내기를 끝에 두어 뒤따르는 람다로 받는다. */
    private val extractComic: suspend (ComicUnit) -> Bitmap? = { null },
    /** 책에서 표지를 꺼낸다. 표지가 없으면 null, 파일을 못 읽으면 예외. */
    private val extract: suspend (LibraryBook) -> Bitmap?,
) {
    private val memory = LruCache<String, Cover>(MEMORY_COVERS)
    private val extracting = Mutex()
    private val _version = MutableStateFlow(0)

    /** 고른 표지가 바뀔 때마다 는다. 책장이 이것을 보고 표지를 다시 읽는다. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** 메모리에 있으면 기다리지 않고 준다(목록을 다시 그릴 때 깜빡이지 않게). */
    fun cached(book: LibraryBook): Cover? = memory.get(memoryKey(book))

    suspend fun cover(book: LibraryBook): Cover = withContext(Dispatchers.IO) {
        val key = memoryKey(book)
        memory.get(key)?.let { return@withContext it }
        // 한 번에 한 권씩 꺼낸다. PDF 엔진 · 큰 EPUB 을 여러 권 동시에 열면 메모리가 모자란다.
        extracting.withLock {
            memory.get(key)?.let { return@withLock it }
            val version = _version.value
            val custom = File(dir, "custom/${key(book.id)}.jpg").takeIf { it.isFile }?.let(::decodeFile)
            val own = ownCover(book.id.value, book.sizeBytes, book.displayName) { extract(book) }
            val cover = Cover(custom ?: own.image, custom = custom != null, hasOwn = own.image != null)
            // 꺼내는 사이에 표지를 고르거나 되돌렸으면 담지 않는다 — 고르기 전에 읽은 옛 표지가 메모리에 남아, 바꿨다는
            // 알림이 뜬 뒤에도 책장에 옛 표지가 보였다. 못 읽은 책도 담지 않는다 — 저장소가 돌아오면 다시 찾아야 한다.
            // 담은 뒤에 다시 본다: [changed] 가 판을 먼저 올리고 비우므로, 어느 순서로 겹쳐도 옛 표지가 남지 않는다.
            if (own.settled) {
                memory.put(key, cover)
                if (version != _version.value) memory.remove(key)
            }
            cover
        }
    }

    /** 만화 단위의 표지(메모리에 있을 때만). 만화는 사람이 고른 표지가 없다 — 그림이 곧 표지다. */
    fun cachedComic(unit: ComicUnit): ImageBitmap? = memory.get(comicKey(unit))?.image

    /** 만화 단위의 표지. 없거나 못 읽으면 null(대신 표지). 책 표지와 같은 줄에 서서 한 번에 하나씩 꺼낸다. */
    suspend fun comic(unit: ComicUnit): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = comicKey(unit)
        memory.get(key)?.let { return@withContext it.image }
        extracting.withLock {
            memory.get(key)?.let { return@withLock it.image }
            // 표지 항목을 열쇠에 넣는다 — 살피기 전(표지 모름)에 "없음" 으로 굳힌 표시가 살핀 뒤에도 남지 않게.
            val own = ownCover("comic|${unit.id}|${unit.contents?.cover}", unit.sizeBytes, unit.name) { extractComic(unit) }
            if (own.settled) memory.put(key, Cover(own.image, custom = false, hasOwn = own.image != null))
            own.image
        }
    }

    /**
     * 사람이 고른 그림을 표지로 둔다. 그림으로 읽히지 않으면(그림이 아닌 파일 · 깨진 파일) · 저장하지 못하면(저장 공간
     * 부족) false 이고 아무것도 바꾸지 않는다 — 깨진 그림으로 멀쩡한 표지를 덮지 않는다.
     */
    suspend fun setCustom(id: BookId, input: () -> InputStream?): Boolean = withContext(Dispatchers.IO) {
        // runCatching 은 Error(메모리 부족)까지 받는다 — 고른 그림이 너무 커도 "쓸 수 없습니다" 로 끝난다.
        val bitmap = runCatching { input()?.use { decodeScaled(it.readBytes()) } }.getOrNull() ?: return@withContext false
        if (!writeJpeg(bitmap, File(dir, "custom/${key(id)}.jpg"))) return@withContext false
        changed()
        true
    }

    suspend fun clearCustom(id: BookId) = withContext(Dispatchers.IO) {
        File(dir, "custom/${key(id)}.jpg").delete()
        changed()
    }

    private fun changed() {
        // 어느 책의 메모리 항목인지는 크기까지 붙은 열쇠라 여기서 모른다. 표지 고르기는 드물어 통째로 비운다.
        _version.value++
        memory.evictAll()
    }

    /** 책에서 꺼낸 표지. [settled] 가 false 면 책을 못 읽어 모르는 것이다 — 담아 두지 않고 다음에 다시 찾는다. */
    private class Own(val image: ImageBitmap?, val settled: Boolean)

    private suspend fun ownCover(id: String, sizeBytes: Long?, name: String, extract: suspend () -> Bitmap?): Own {
        // 파일 크기를 열쇠에 넣는다 — 같은 이름으로 개정판을 덮어쓰면 옛 판의 표지가 계속 보였다.
        val key = key(id, sizeBytes)
        val file = File(dir, "auto/$key.jpg")
        if (file.isFile) return Own(decodeFile(file), settled = true)
        val none = File(dir, "auto/$key.none")
        if (none.exists()) return Own(null, settled = true)
        val bitmap = try {
            extract()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 오류(Error)까지 받는다: 큰 표지 그림이 메모리를 넘기거나(OutOfMemoryError) 엔진이 깨진 PDF 에서 무너져도,
            // 표지 하나 때문에 책장이 닫히면 안 된다 — 대신 표지를 보이면 된다.
            android.util.Log.w("OloCovers", "cover of $name unreadable", e)
            return Own(null, settled = false)
        }
        if (bitmap == null) {
            // 표시를 못 남겨도(저장 공간 부족) 표지가 없다는 것은 안다. 다음 실행에 한 번 더 찾을 뿐이다.
            runCatching { none.parentFile?.mkdirs(); none.createNewFile() }
            return Own(null, settled = true)
        }
        val scaled = scaleToHeight(bitmap)
        writeJpeg(scaled, file)
        return Own(scaled.asImageBitmap(), settled = true)
    }

    private fun decodeFile(file: File): ImageBitmap? = BitmapFactory.decodeFile(file.path)?.asImageBitmap()

    /** 줄인 표지를 쓴다. 저장 공간이 모자라 못 쓰면 false — 표지 하나 때문에 앱이 닫히면 안 된다. */
    private fun writeJpeg(bitmap: Bitmap, file: File): Boolean {
        val tmp = File(file.path + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            // 다 쓴 뒤 바꿔 끼운다 — 쓰다 멈추면(앱이 닫힘) 반쪽 그림이 표지로 남는다.
            tmp.renameTo(file)
        } catch (e: java.io.IOException) {
            android.util.Log.w("OloCovers", "cannot write ${file.name}", e)
            tmp.delete()
            false
        }
    }

    companion object {
        /** 표지를 둘 높이(픽셀). 책장의 큰 표지(약 150dp)를 xxhdpi 에서 흐리지 않게 그리는 만큼. */
        const val COVER_HEIGHT = 480
        private const val JPEG_QUALITY = 85
        private const val MEMORY_COVERS = 64

        /** 고른 표지는 책마다 하나(파일을 바꿔도 사람이 고른 것은 남는다), 책의 표지는 파일 크기마다 하나. */
        private fun key(id: BookId): String = key(id.value, null)

        private fun key(id: String, sizeBytes: Long?): String =
            java.security.MessageDigest.getInstance("SHA-1")
                .digest((if (sizeBytes == null) id else "$id|$sizeBytes").toByteArray())
                .joinToString("") { "%02x".format(it) }.take(20)

        private fun memoryKey(book: LibraryBook) = "${book.id.value}|${book.sizeBytes}"

        private fun comicKey(unit: ComicUnit) = "comic|${unit.id}|${unit.sizeBytes}|${unit.contents?.cover}"

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
