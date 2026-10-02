package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.kgcaudit.reader.data.ComicPages
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.image.ImageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 연 만화 한 권: 쪽 그림을 화면 크기로 줄여 풀고 몇 장을 메모리에 둔다(0.34.0).
 *
 * 쪽은 늘 **화면에 맞는 크기로** 푼다. 스캔본 한 쪽(2000×3000)을 통째로 풀면 24MB 라 서너 장이면 메모리가 모자란다.
 * 확대는 푼 그림을 늘린다 — 화면 크기로 풀어도 두 배까지는 또렷하고, 더 크게 보는 일은 드물다.
 */
class ComicBook(
    val unit: ComicUnit,
    private val pages: ComicPages,
    /** 띠 풀기(BitmapRegionDecoder)를 쓸지. 시험이 끈다 — Robolectric 의 띠 풀기는 빈 그림을 돌려준다. */
    private val regions: Boolean = true,
) : AutoCloseable {
    val pageCount: Int get() = pages.count

    /** 압축 읽기는 한 번에 하나(같은 파일 위치를 옮겨 다닌다). 풀기도 함께 세워 메모리 꼭대기를 낮춘다. */
    private val lock = Mutex()
    private val bitmaps = object : LruCache<String, Bitmap>(memoryBudget()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val aspects = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val broken = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun cached(index: Int, width: Int, height: Int): Bitmap? = bitmaps.get(key(index, width, height))

    /** 이미 아는 쪽 가로/세로 비. 모르면 null(아직 열지 않은 쪽). */
    fun knownAspect(index: Int): Float? = aspects[index]

    /** 그림으로 읽히지 않는 쪽(깨진 그림 · 사라진 항목). 다시 풀려고 애쓰지 않는다. */
    fun isBroken(index: Int): Boolean = index in broken

    /**
     * [index] 쪽을 [width]×[height] 안에 들어가게 푼다. 그림이 아니면 null — 화면은 "이 쪽을 그리지 못했습니다" 를 보이고
     * 다음 쪽으로 넘어갈 수 있다(깨진 쪽 하나가 권 전체를 막지 않는다, 규칙 6).
     */
    suspend fun page(index: Int, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in 0 until pageCount || width <= 0 || height <= 0) return@withContext null
        cached(index, width, height)?.let { return@withContext it }
        if (index in broken) return@withContext null
        lock.withLock {
            cached(index, width, height)?.let { return@withLock it }
            val bytes = try {
                pages.read(index)
            } catch (e: java.io.IOException) {
                android.util.Log.w("OloComic", "page $index of ${unit.name} unreadable", e)
                null
            }
            // 메모리가 모자라 못 푼 것(OutOfMemoryError)도 깨진 쪽처럼 넘긴다 — 쪽 하나 때문에 앱이 닫히면 안 된다.
            val bitmap = bytes?.let { runCatching { decode(it, width, height, index) }.getOrNull() }
            if (bitmap == null) broken += index else bitmaps.put(key(index, width, height), bitmap)
            bitmap
        }
    }

    private fun decode(bytes: ByteArray, width: Int, height: Int, index: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        aspects[index] = bounds.outWidth.toFloat() / bounds.outHeight
        // 화면 상자에 맞춘 크기보다 작아지지 않는 만큼만 솎는다(2의 거듭제곱 — 풀기가 가장 싸다).
        val scale = minOf(width.toFloat() / bounds.outWidth, height.toFloat() / bounds.outHeight)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= bounds.outWidth * scale && bounds.outHeight / (sample * 2) >= bounds.outHeight * scale) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 쪽들의 픽셀 크기(머리만 읽음). 한 번 읽으면 둔다. 깨진 쪽은 null. */
    suspend fun sizes(): List<ImageSize?> = withContext(Dispatchers.IO) {
        knownSizes ?: lock.withLock {
            knownSizes ?: (0 until pageCount).map { i -> pages.size(i).also { s -> if (s != null) aspects[i] = s.width.toFloat() / s.height } }
                .also { knownSizes = it }
        }
    }

    @Volatile private var knownSizes: List<ImageSize?>? = null

    fun cachedStrip(index: Int, rows: IntRange, width: Int): Bitmap? = bitmaps.get(stripKey(index, rows, width))

    /**
     * 긴 그림의 띠 하나([rows] 원본 행)를 [width] 폭에 맞게 푼다(웹툰). 그림 전체를 풀지 않는다 — 800×30000 한 장이
     * 96MB 다. 띠 풀기가 안 되는 그림(일부 JPEG · GIF)은 그림 전체를 줄여 풀어 그 띠만 잘라 낸다.
     */
    suspend fun strip(index: Int, rows: IntRange, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in 0 until pageCount || width <= 0 || rows.isEmpty()) return@withContext null
        cachedStrip(index, rows, width)?.let { return@withContext it }
        if (index in broken) return@withContext null
        lock.withLock {
            cachedStrip(index, rows, width)?.let { return@withLock it }
            val bytes = sourceOf(index) ?: run { broken += index; return@withLock null }
            val bitmap = runCatching { decodeStrip(bytes, rows, width) }.getOrNull()
            if (bitmap == null) broken += index else bitmaps.put(stripKey(index, rows, width), bitmap)
            bitmap
        }
    }

    /** 마지막으로 띠를 푼 그림의 바이트. 한 그림의 띠 열댓 개를 풀 때마다 압축에서 다시 꺼내지 않게. */
    private var lastSource: Pair<Int, ByteArray>? = null

    private fun sourceOf(index: Int): ByteArray? {
        lastSource?.takeIf { it.first == index }?.let { return it.second }
        val bytes = try {
            pages.read(index)
        } catch (e: java.io.IOException) {
            android.util.Log.w("OloComic", "page $index of ${unit.name} unreadable", e)
            null
        }
        lastSource = bytes?.let { index to it }
        return bytes
    }

    private fun decodeStrip(bytes: ByteArray, rows: IntRange, width: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null
        var sample = 1
        while (srcW / (sample * 2) >= width) sample *= 2
        val top = rows.first.coerceIn(0, srcH - 1)
        val bottom = (rows.last + 1).coerceIn(top + 1, srcH)
        val region = if (!regions) null else runCatching {
            @Suppress("DEPRECATION")
            val decoder = android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            try {
                decoder?.decodeRegion(android.graphics.Rect(0, top, srcW, bottom), BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                decoder?.recycle()
            }
        }.getOrNull()
        if (region != null) return region
        // 띠 풀기가 안 되는 그림: 전체를 더 줄여 풀고(높이 한도 안으로) 그 띠만 자른다.
        while (srcH / sample > MAX_WHOLE_HEIGHT) sample *= 2
        val whole = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val y0 = (top.toLong() * whole.height / srcH).toInt().coerceIn(0, whole.height - 1)
        val y1 = (bottom.toLong() * whole.height / srcH).toInt().coerceIn(y0 + 1, whole.height)
        return Bitmap.createBitmap(whole, 0, y0, whole.width, y1 - y0)
    }

    override fun close() {
        bitmaps.evictAll()
        lastSource = null
        pages.close()
    }

    private fun key(index: Int, width: Int, height: Int) = "$index|$width|$height"

    private fun stripKey(index: Int, rows: IntRange, width: Int) = "s$index|${rows.first}-${rows.last}|$width"

    private companion object {
        /** 띠 풀기가 안 될 때 전체를 풀 최대 높이(픽셀). 텍스처 한도(4096) 안. */
        const val MAX_WHOLE_HEIGHT = 4096

        /** 앱 힙의 8분의 1, 많아야 96MB. 화면 크기 쪽(1080×2340 ARGB ≈ 10MB) 여러 장 — 앞뒤 쪽을 미리 풀어 둘 만큼. */
        fun memoryBudget(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L * 1024 * 1024).toInt()
    }
}
