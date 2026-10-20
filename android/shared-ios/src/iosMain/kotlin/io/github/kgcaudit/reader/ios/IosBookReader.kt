@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.document.io.Closeable
import io.github.kgcaudit.reader.document.io.InputStream
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.book.BookLayout
import io.github.kgcaudit.reader.layout.book.ReadingPosition
import io.github.kgcaudit.reader.layout.book.ReadingSession
import io.github.kgcaudit.reader.layout.cache.PageStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage

/** 목차 한 줄. */
class ContentsRow(val label: String, val depth: Int)

/** 책갈피 한 줄. [snippet] 은 꽂은 쪽의 첫 80자. */
class BookmarkRow(val id: Long, val snippet: String, val percent: Float)

/** 화면에 낼 한 쪽과 그 쪽의 자리. */
class ReaderFrame(
    val image: UIImage,
    /** 0 부터. 위 막대의 "n / m장" 은 spine 차례다(안드로이드와 같다 — 목차가 아니다). */
    val spineIndex: Int,
    val chapterCount: Int,
    /** 장 안의 쪽(0 부터). 아래 줄의 "n / m" 은 장 안의 쪽이다. */
    val pageIndex: Int,
    val pageCount: Int,
    /** 책 전체 진도(0~100). */
    val percent: Float,
    val bookmarked: Boolean,
)

/**
 * 한 권을 여는 리더(안드로이드 `BookReader` 의 iOS 판). 쪽 넘김 · 자리 저장 · 책갈피 · 진도는 공통 [BookLayout] ·
 * [ReadingSession] 이 한다 — 여기는 문서를 열고, 쪽을 그림으로 내고, 설정이 바뀌면 같은 자리에서 다시 짜는 일만 한다.
 *
 * **한 번에 하나씩 부른다**(Swift 쪽이 actor 로 줄 세운다). 위치(position)를 고치는 호출이 겹치면 넘김 두 번이 한 쪽만
 * 넘어가거나 저장 순서가 뒤바뀐다.
 */
class IosBookReader private constructor(
    private val document: ReflowDocument,
    private val store: ReadingStore,
    private val cacheDir: String,
    /** 원본 파일의 판(크기 · 고친 때). 조판 캐시 키에 넣는다 — 같은 이름으로 고친 책에 옛 쪽이 보이지 않게. */
    private val edition: String?,
) {
    val title: String get() = document.meta.title
    val author: String? get() = document.meta.author

    private var layout: BookLayout? = null
    private var session: ReadingSession? = null
    private var position: ReadingPosition? = null
    private var paper = 0xFBF9F5
    private var ink = 0x1D1A16
    private var blendPictures = true
    private var toc: List<TocEntry>? = null
    private var spec: LayoutSpec? = null
    private val images = HashMap<String, UIImage?>()
    private val pages = PageStore(cacheDir)

    /**
     * [spec] 으로 (다시) 짜고 지금 자리의 쪽을 낸다. 처음이면 저장된 자리에서, 이미 읽던 중이면(글자 크기 · 회전)
     * 보던 쪽의 첫 글자가 있는 쪽에서 — 쪽 번호로 이으면 설정을 바꾸는 순간 자리가 미끄러진다(CLAUDE.md 규칙 3).
     */
    @Throws(Exception::class)
    suspend fun layOut(spec: LayoutSpec, paper: Int, ink: Int, blendPictures: Boolean): ReaderFrame {
        this.paper = paper
        this.ink = ink
        this.blendPictures = blendPictures
        val anchor = position?.let { p -> layout?.locatorAt(p.spineIndex, p.pageIndex) }
        val withEdition = spec.copy(edition = edition)
        if (withEdition != this.spec || layout == null) {
            val newLayout = BookLayout(document, withEdition, pages, CoreTextMeasurer.forSpec(withEdition))
            layout = newLayout
            session = ReadingSession(newLayout, store, store)
            this.spec = withEdition
            position = anchor?.let { newLayout.resolve(it) } ?: session!!.restore()
            newLayout.pruneStaleCaches()
        }
        return show(position!!)
    }

    /** 다음 쪽. 책 끝이면 null. */
    @Throws(Exception::class)
    suspend fun next(): ReaderFrame? {
        val next = layout().next(position ?: return null) ?: return null
        return show(next)
    }

    /** 앞 쪽. 책 처음이면 null. */
    @Throws(Exception::class)
    suspend fun previous(): ReaderFrame? {
        val previous = layout().previous(position ?: return null) ?: return null
        return show(previous)
    }

    /** 진도 막대로 옮긴다. */
    @Throws(Exception::class)
    suspend fun seek(percent: Float): ReaderFrame {
        val l = layout()
        return show(l.resolve(l.locatorAtPercent(percent.coerceIn(0f, 100f))))
    }

    /** 이 쪽에 책갈피가 있으면 빼고, 없으면 꽂는다. */
    @Throws(Exception::class)
    suspend fun toggleBookmark(): ReaderFrame {
        val s = session ?: error("not laid out")
        val p = position ?: error("not laid out")
        if (!s.removeBookmarkAt(p)) s.addBookmark(p)
        return show(p)
    }

    // ── 목차 ──────────────────────────────────────────────────────────────

    /** 목차(책이 적은 차례 그대로). TXT 처럼 없으면 빈 목록. 읽을 수 없는 목차도 빈 목록 — 목차 때문에 책이 안 열리면 안 된다. */
    @Throws(Exception::class)
    suspend fun contents(): List<ContentsRow> = outline().map { ContentsRow(it.label, it.depth) }

    /**
     * 지금 읽는 곳의 목차 항목(없으면 −1). "지금 장과 같은 항목" 이 아니라 **지금 자리 이전의 마지막 항목** — 실제 책은 목차가
     * 장보다 훨씬 적어(항목 7개가 장 113개를 덮는다) 같은 것만 찾으면 대부분의 쪽에서 아무것도 강조되지 않는다. 안드로이드
     * `currentTocIndex` 와 같은 규칙이다.
     */
    @Throws(Exception::class)
    suspend fun currentContentsIndex(): Int {
        val spine = position?.spineIndex ?: return -1
        return currentIndex(outline(), spine)
    }

    /** 목차 항목으로 간다. 없는 항목 · 가리키는 곳이 없는 항목은 null(아무 일도 없다). */
    @Throws(Exception::class)
    suspend fun goToContents(index: Int): ReaderFrame? {
        val entry = outline().getOrNull(index) ?: return null
        val spine = (entry.locator as? Locator.Reflow)?.spine ?: return null
        val l = layout()
        if (spine !in l.spine().indices) return null // 어긋난 목차 링크: 그 항목만 버린다(CLAUDE.md 규칙 6).
        return show(l.resolve(l.locatorForAnchor(spine, entry.anchor)))
    }

    private suspend fun outline(): List<TocEntry> =
        toc ?: runCatching { document.outline() }.getOrDefault(emptyList()).also { toc = it }

    // ── 책갈피 ────────────────────────────────────────────────────────────

    /** 꽂은 책갈피(책 차례대로). */
    @Throws(Exception::class)
    suspend fun bookmarks(): List<BookmarkRow> {
        val s = session ?: return emptyList()
        val l = layout()
        return s.bookmarks().map { b ->
            val percent = (b.locator as? Locator.Reflow)?.let { runCatching { l.percent(it) }.getOrDefault(0f) } ?: 0f
            BookmarkRow(b.id, b.snippet?.takeIf { it.isNotBlank() } ?: "(빈 쪽)", percent)
        }
    }

    /** 책갈피 자리로 간다. 지워진 책갈피면 null. */
    @Throws(Exception::class)
    suspend fun goToBookmark(id: Long): ReaderFrame? {
        val s = session ?: return null
        val bookmark = s.bookmarks().firstOrNull { it.id == id } ?: return null
        return show(s.goTo(bookmark) ?: return null)
    }

    fun close() {
        (document as? Closeable)?.close()
    }

    private fun layout(): BookLayout = layout ?: error("layOut first")

    private suspend fun show(target: ReadingPosition): ReaderFrame {
        val l = layout()
        val s = session!!
        val page = l.page(target.spineIndex, target.pageIndex)
        val text = l.chapterText(target.spineIndex) ?: ""
        val saved = s.saveProgress(target)
        position = target
        val spec = spec!!
        val image = if (page == null) {
            PagePainter.blank(spec.viewportWidthPx.toDouble(), spec.viewportHeightPx.toDouble(), paper)
        } else {
            PagePainter.render(
                page, text, CoreTextMeasurer.forSpec(spec), spec.viewportWidthPx.toDouble(), spec.viewportHeightPx.toDouble(),
                paper, ink, pictures(target.spineIndex, page), PictureBlend.forPaper(paper, blendPictures),
            )
        }
        return ReaderFrame(
            image = image,
            spineIndex = target.spineIndex,
            chapterCount = l.spine().size,
            pageIndex = target.pageIndex,
            pageCount = target.pageCount,
            percent = saved.percent,
            bookmarked = s.isBookmarked(target),
        )
    }

    /** 쪽의 그림. 읽을 수 없는 그림은 빈 칸으로 둔다 — 그림 하나 때문에 쪽이 안 열리면 안 된다(CLAUDE.md 규칙 6). */
    private suspend fun pictures(spine: Int, page: Page): Map<String, UIImage> {
        if (page.images.isEmpty()) return emptyMap()
        val out = HashMap<String, UIImage>()
        for (placed in page.images) {
            val key = "$spine:${placed.href}"
            val image = images.getOrPut(key) {
                runCatching { document.openChapterResource(spine, placed.href)?.use { it.readAllBytes() }?.toUIImage() }.getOrNull()
            }
            if (image != null) out[placed.href] = image
        }
        // 그림 캐시는 몇 장만 — 그림책 한 권을 다 넘기면 메모리가 다 찬다.
        if (images.size > IMAGE_CACHE) images.keys.take(images.size - IMAGE_CACHE).forEach(images::remove)
        return out
    }

    companion object {
        private const val IMAGE_CACHE = 12

        internal fun currentIndex(entries: List<TocEntry>, spineIndex: Int): Int {
            var best = -1
            var bestSpine = -1
            entries.forEachIndexed { i, entry ->
                val spine = (entry.locator as? Locator.Reflow)?.spine ?: return@forEachIndexed
                // 같은 장을 가리키는 항목이 여럿이면(한 파일에 여러 절) 그 장의 첫 항목. 앵커의 글자 자리는 조판해야 안다.
                if (spine <= spineIndex && spine > bestSpine) {
                    best = i
                    bestSpine = spine
                }
            }
            return best
        }

        /**
         * [path] 의 책을 연다. [bookId] 는 서재가 정한 변하지 않는 이름(앱 폴더 안의 상대 경로 등) — 앱을 고쳐 깔면 앱
         * 폴더의 절대 경로가 바뀌므로 경로를 그대로 쓰면 읽던 자리를 잃는다. [cacheDir] 는 쪽 캐시 폴더.
         */
        @Throws(Exception::class)
        fun open(path: String, bookId: String, displayName: String, store: ReadingStore, cacheDir: String, edition: String?): IosBookReader {
            val id = BookId(bookId)
            val document: ReflowDocument = when (BookFormat.fromFileName(displayName)) {
                BookFormat.EPUB -> EpubDocument.open(id, displayName, FileSeekableSource(path))
                BookFormat.TXT -> TxtDocument.open(id, displayName, FileBytes.file(path))
                else -> throw IllegalArgumentException("unsupported book: $displayName")
            }
            store.markOpened(bookId)
            return IosBookReader(document, store, cacheDir, edition)
        }

        /** EPUB 표지 그림. 없거나 읽을 수 없으면 null(서재는 이름 표지를 그린다). */
        fun cover(path: String): UIImage? = runCatching {
            val document = EpubDocument.open(BookId("cover"), "cover.epub", FileSeekableSource(path))
            try {
                document.openCoverImage()?.use { it.readAllBytes() }?.toUIImage()
            } finally {
                document.close()
            }
        }.getOrNull()
    }
}

private fun InputStream.readAllBytes(): ByteArray {
    val chunks = ArrayList<ByteArray>()
    var total = 0
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        chunks.add(buffer.copyOf(n))
        total += n
    }
    val out = ByteArray(total)
    var at = 0
    for (c in chunks) {
        c.copyInto(out, at)
        at += c.size
    }
    return out
}

private fun ByteArray.toUIImage(): UIImage? {
    if (isEmpty()) return null
    val data = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    return UIImage.imageWithData(data)
}
