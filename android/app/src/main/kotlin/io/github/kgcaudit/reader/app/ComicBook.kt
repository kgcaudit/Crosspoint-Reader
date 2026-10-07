package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.kgcaudit.reader.data.ComicPages
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.MarginTrim
import io.github.kgcaudit.reader.document.image.ImageSize
import io.github.kgcaudit.reader.ui.design.sampleKeeping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil

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
    /** 그림 저장소(LRU) 한도(바이트). 시험이 아주 작게 줘서 저장소가 쪽을 내보내는 상황을 만든다. */
    budget: Int = memoryBudget(),
    /** 띠를 풀기 전에 기다리는 시간(ms). 시험만 쓴다(`AppContainer.comicStripDelayMs`). */
    private val stripDelayMs: () -> Long = { 0L },
) : AutoCloseable {
    val pageCount: Int get() = pages.count

    /** 압축 읽기는 한 번에 하나(같은 파일 위치를 옮겨 다닌다). 풀기도 함께 세워 메모리 꼭대기를 낮춘다. */
    private val lock = Mutex()
    private val bitmaps = object : LruCache<String, Bitmap>(budget) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val aspects = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val broken = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun cached(index: Int, width: Int, height: Int, trim: Boolean = false): Bitmap? = key(index, width, height, trim).let { k -> bitmaps.get(k) ?: pinned[k] }

    /**
     * 붙잡아 둘 쪽(지금 판과 앞뒤 판). 저장소(LRU)가 내보내도 이 쪽들의 그림은 놓지 않는다 — 넘김 효과가 끝나 화면을 다시
     * 짤 때 그 쪽이 저장소에 없으면 다시 풀릴 때까지 한 장면 동안 비어 깜박였다(0.45.1, 태블릿 두 쪽).
     */
    fun pin(indices: Set<Int>) {
        pinnedPages = indices
        pinned.keys.removeAll { k -> pageOf(k) !in indices }
        bitmaps.snapshot().forEach { (k, v) -> if (pageOf(k) in indices) pinned[k] = v }
    }

    @Volatile private var pinnedPages: Set<Int> = emptySet()
    private val pinned = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    /** 이미 아는 쪽 가로/세로 비. 모르면 null(아직 열지 않은 쪽). */
    fun knownAspect(index: Int): Float? = aspects[index]

    /**
     * 여백을 걷어 낸 쪽의 비(0.49.0). 아직 풀지 않았으면 null. 걷을 것이 없던 쪽은 원래 비와 같다 — 판 배치가 원본 머리의
     * 비를 쓰면 잘린 그림이 옆으로 늘어나 보인다.
     */
    fun trimmedAspect(index: Int): Float? = trimmedAspects[index]
    private val trimmedAspects = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    /** 그림으로 읽히지 않는 쪽(깨진 그림 · 사라진 항목). 다시 풀려고 애쓰지 않는다. */
    fun isBroken(index: Int): Boolean = index in broken

    /**
     * [index] 쪽을 [width]×[height] 안에 들어가게 푼다. 그림이 아니면 null — 화면은 "이 쪽을 그리지 못했습니다" 를 보이고
     * 다음 쪽으로 넘어갈 수 있다(깨진 쪽 하나가 권 전체를 막지 않는다, 규칙 6).
     */
    suspend fun page(index: Int, width: Int, height: Int, trim: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in 0 until pageCount || width <= 0 || height <= 0) return@withContext null
        cached(index, width, height, trim)?.let { return@withContext it }
        if (index in broken) return@withContext null
        lock.withLock {
            cached(index, width, height, trim)?.let { return@withLock it }
            val renderer = pages.renderer
            // 그려 내는 쪽(스캔 PDF, 0.50.0)은 바이트를 거치지 않고 화면 크기로 바로 그린다.
            val bitmap = if (renderer != null) {
                runCatching { renderer.render(index, width, height) }.getOrNull()
                    ?.also { aspects[index] = it.width.toFloat() / it.height }
                    ?.let { if (trim) trimmed(it, index) else it }
            } else {
                val bytes = try {
                    pages.read(index)
                } catch (e: java.io.IOException) {
                    android.util.Log.w("OloComic", "page $index of ${unit.name} unreadable", e)
                    null
                }
                // 메모리가 모자라 못 푼 것(OutOfMemoryError)도 깨진 쪽처럼 넘긴다 — 쪽 하나 때문에 앱이 닫히면 안 된다.
                bytes?.let { runCatching { decode(it, width, height, index) }.getOrNull() }
                    ?.let { if (trim) trimmed(it, index) else it }
            }
            if (bitmap == null) broken += index else {
                bitmaps.put(key(index, width, height, trim), bitmap)
                if (index in pinnedPages) pinned[key(index, width, height, trim)] = bitmap
            }
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
        val sample = sampleKeeping(bounds.outWidth, bounds.outHeight, ceil(bounds.outWidth * scale).toInt(), ceil(bounds.outHeight * scale).toInt())
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * 바깥 여백을 걷어 낸 그림. 여백은 줄여 본 사본(가로 [TRIM_PROBE] 픽셀)에서 찾는다 — 화면 크기 그림의 화소를 통째로
     * 꺼내면 쪽마다 수 MB 를 잠깐 더 쓴다. 자를 것이 없거나 찾다가 실패하면 원래 그림 그대로(규칙 6).
     */
    private fun trimmed(bitmap: Bitmap, index: Int): Bitmap {
        val cut = runCatching {
            val scale = minOf(1f, TRIM_PROBE.toFloat() / bitmap.width)
            val pw = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val ph = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val probe = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, pw, ph, true) else bitmap
            val pixels = IntArray(pw * ph).also { probe.getPixels(it, 0, pw, 0, 0, pw, ph) }
            if (probe !== bitmap) probe.recycle()
            MarginTrim.find(pw, ph, pixels)?.let { box ->
                val l = (box.left / scale).toInt().coerceIn(0, bitmap.width - 1)
                val t = (box.top / scale).toInt().coerceIn(0, bitmap.height - 1)
                val r = (box.right / scale).toInt().coerceIn(l + 1, bitmap.width)
                val b = (box.bottom / scale).toInt().coerceIn(t + 1, bitmap.height)
                Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
            }
        }.getOrNull()
        val result = cut ?: bitmap
        trimmedAspects[index] = result.width.toFloat() / result.height
        return result
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
        stripDelayMs().takeIf { it > 0 }?.let { kotlinx.coroutines.delay(it) }
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
        val sample = sampleKeeping(srcW, srcH, width, 0)
        val top = rows.first.coerceIn(0, srcH - 1)
        val bottom = (rows.last + 1).coerceIn(top + 1, srcH)
        return decodeRect(bytes, srcW, srcH, android.graphics.Rect(0, top, srcW, bottom), sample, regions, MAX_WHOLE_HEIGHT)
    }

    /**
     * 그림 [index] 의 [rect](원본 픽셀)만 높이 [height] 쯤으로 풀어 낸다 — 장면에서 표지 고르기(0.47.0). 긴 웹툰 그림을 통째로
     * 풀지 않는다. 띠 풀기가 안 되는 그림은 전체를 줄여 풀어 자른다. 못 읽으면 null.
     */
    suspend fun crop(index: Int, rect: android.graphics.Rect, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in 0 until pageCount || rect.isEmpty || height <= 0) return@withContext null
        lock.withLock {
            val bytes = sourceOf(index) ?: return@withLock null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val sample = sampleKeeping(rect.width(), rect.height(), 0, height)
                // 쪽 전체(쪽 넘김 만화의 표지 고르기)는 띠 풀기가 필요 없다 — 그냥 줄여 푼다.
                val whole = rect.left <= 0 && rect.top <= 0 && rect.right >= bounds.outWidth && rect.bottom >= bounds.outHeight
                decodeRect(bytes, bounds.outWidth, bounds.outHeight, rect, sample, regions && !whole, MAX_WHOLE_HEIGHT)
            }.getOrNull()
        }
    }

    /**
     * 풀어 둔 그림을 모두 내려놓는다(파일은 열린 채). 웹툰 이어 보기에서 지나온 화가 화면을 떠나도 제 몫(앱 힙의 1/8)을
     * 쥐고 있으면 몇 화 만에 메모리가 넘친다.
     */
    fun trim() {
        bitmaps.evictAll()
        // 붙잡아 둔 쪽(쪽 넘김에서 앞뒤 판)도 놓는다 — 웹툰으로 바꾼 뒤에는 다시 붙잡을 일이 없어 두 쪽 판 몇 장이 남았다.
        pinnedPages = emptySet()
        pinned.clear()
        lastSource = null
    }

    override fun close() {
        trim()
        pages.close()
    }

    // 자른 그림과 원래 그림은 다른 칸에 둔다 — 같은 칸이면 여백 자르기를 끄고 켜도 저장소에 남은 쪽이 그대로 보였다.
    private fun key(index: Int, width: Int, height: Int, trim: Boolean = false) = "$index|$width|$height" + if (trim) "|t" else ""

    /** 쪽 그림 열쇠의 쪽 번호. 띠(웹툰) 열쇠는 붙잡지 않는다 — -1. */
    private fun pageOf(key: String): Int = if (key.startsWith("s")) -1 else key.substringBefore('|').toIntOrNull() ?: -1

    private fun stripKey(index: Int, rows: IntRange, width: Int) = "s$index|${rows.first}-${rows.last}|$width"

    companion object {
        /** 띠 풀기가 안 될 때 전체를 풀 최대 높이(픽셀). 텍스처 한도(4096) 안. */
        private const val MAX_WHOLE_HEIGHT = 4096

        /** 여백을 찾을 때 줄여 보는 폭. 쪽 폭의 1% 여유와 먼지 한도를 가를 만큼은 된다. */
        private const val TRIM_PROBE = 360

        /** 앱 힙의 8분의 1, 많아야 96MB. 화면 크기 쪽(1080×2340 ARGB ≈ 10MB) 여러 장 — 앞뒤 쪽을 미리 풀어 둘 만큼. */
        internal fun memoryBudget(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L * 1024 * 1024).toInt()
    }
}
