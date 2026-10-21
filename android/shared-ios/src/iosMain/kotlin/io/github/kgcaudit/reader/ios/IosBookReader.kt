@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.io.currentTimeMillis
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
import io.github.kgcaudit.reader.layout.book.BookFontTable
import io.github.kgcaudit.reader.layout.book.BookLayout
import io.github.kgcaudit.reader.layout.book.LinkTarget
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
class BookmarkRow(
    val id: Long,
    val snippet: String,
    val percent: Float,
    /** 독서노트의 갈래 이름: 이 자리를 담은 목차 항목(없으면 "N장"). 안드로이드 `readingNotes` 의 section 과 같은 규칙. */
    val section: String,
    val createdAtEpochMs: Long,
)

/** 찾은 곳 한 줄(안드로이드 `Found` + `CpSearchRow`). [section] 은 목차 이름(없으면 "N장"), [percent] 는 책의 %. */
class SearchRow(
    val spine: Int,
    val start: Int,
    val endExclusive: Int,
    val context: String,
    val contextMatchStart: Int,
    val percent: Float,
    val section: String,
)

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
    /** 링크로 건너온 쪽이다 — "읽던 곳으로" 칩을 띄운다. */
    val canReturn: Boolean = false,
)

/**
 * 형광펜 한 줄(Swift 로 내는 값). [color] 는 펜 번호(0 노랑 · 1 초록 · 2 파랑 · 3 분홍, 공통 `HighlightColor` 차례).
 * [start] · [end] 는 그 장 안 글자 자리(쪽 위 상자를 묻는 데 쓴다). [percent] · [section] 은 독서노트 줄.
 */
class NoteRow(
    val id: Long,
    val spine: Int,
    val start: Int,
    val end: Int,
    val color: Int,
    val snippet: String,
    val note: String?,
    val percent: Float,
    val section: String,
    val createdAtEpochMs: Long,
    /** 쪽을 넘어 칠했으면 "5–6쪽에 걸침", 한 쪽 안이면 null. */
    val pageSpan: String? = null,
)

/**
 * 고른 구간의 처지(이어 고르기, 안드로이드 0.17). [canContinue] 면 메뉴 끝에 "이어서 ›", [charsBefore] 가 0 보다 크면 시작이 앞 쪽에
 * 있다 — 시작 손잡이를 그리지 않고 "앞 쪽에서 이어 고르는 중 · N자" 를 띄운다.
 */
class SelectionState(val canContinue: Boolean, val charsBefore: Int)

/** "이어서 ›" 를 눌렀다: 넘긴 쪽과 들고 온 구간. */
class ContinuedSelection(val frame: ReaderFrame, val start: Int, val end: Int)

/**
 * 누른 링크가 한 일(안드로이드 `openLink`). [kind] 는 "footnote"(각주 판을 띄운다: [title] · [text], 가기는 [spine] · [anchor]),
 * "jump"(이미 건너갔다: [frame]), "external"(묻고 연다: [url]), "missing"(알림: [text]).
 */
class LinkAction(
    val kind: String,
    val title: String = "",
    val text: String = "",
    val url: String = "",
    val spine: Int = 0,
    val anchor: String? = null,
    val frame: ReaderFrame? = null,
    /** kind 가 "note" 면 누른 형광펜. */
    val note: NoteRow? = null,
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
    private var accent = 0xB95B3B

    private var accentText = 0x9A4A2C
    private var showHighlights = true

    /** 이 책의 형광펜(읽는 차례). 처음 쪽을 보일 때 한 번 읽고, 고칠 때마다 보관소와 함께 다시 읽는다. */
    private var notes: List<Annotation>? = null

    private suspend fun loadNotes(): List<Annotation> =
        notes ?: runCatching { store.annotations.forBook(document.meta.id) }.getOrDefault(emptyList()).also { notes = it }

    /** 링크로 건너가기 전에 읽던 곳. 목차 · 진도 · 책갈피 · 찾기로 다른 곳에 가면 뜻을 잃어 지운다. */
    private var returnTo: Locator.Reflow? = null

    /** 지금 보이는 쪽 — 누른 자리의 링크를 찾는다. */
    private var shown: Pair<io.github.kgcaudit.reader.layout.Page, Int>? = null

    /** 칠해 둔 찾은 말(장 · 글자 구간). 그 장의 쪽을 그릴 때만 칠한다. */
    private var mark: Pair<Int, IntRange>? = null
    private var toc: List<TocEntry>? = null
    private var spec: LayoutSpec? = null
    private val images = HashMap<String, UIImage?>()
    private val pages = PageStore(cacheDir)

    /** 책의 `@font-face` 표. 처음 짤 때 한 번 읽는다(스타일시트를 모두 훑는다). */
    private var fontTable: BookFontTable? = null

    /** 꺼내 읽은 책 글꼴. 처음으로 책 글꼴로 조판할 때 준비한다 — 출판사 글꼴을 끈 사람에게 수 MB 를 풀 이유가 없다. */
    private var bookFaces: BookFaces? = null

    private suspend fun fontTable(): BookFontTable =
        fontTable ?: (runCatching { BookFontTable.load(document) }.getOrNull() ?: BookFontTable.EMPTY).also { fontTable = it }

    /**
     * 책에 **쓸 수 있는** 글꼴이 들어 있다 — 글꼴 판에 "출판사 글꼴" 을 내놓는다. 꺼내 보기 전에는 선언만 보고, 꺼내 본 뒤에는
     * 읽힌 것이 있는지를 본다(안드로이드 `hasBookFonts`). 모두 WOFF 이거나 깨진 책에서 "출판사 글꼴" 이 켜진 채 휴대폰
     * 글꼴로 그려지면 무엇을 고른 것인지 알 수 없다.
     */
    @Throws(Exception::class)
    suspend fun hasBookFonts(): Boolean = !fontTable().isEmpty && bookFaces?.isEmpty != true

    /** "출판사 글꼴" 이름을 그릴 글꼴 파일. 아직 꺼내기 전이면 null. */
    val bookFontPreviewPath: String? get() = bookFaces?.previewPath

    /**
     * [spec] 으로 재고 그리는 측정기. 조판과 그리기가 **같은 꼴**을 쓰게 하는 유일한 길이다 — 따로 만들면 책 글꼴로 잰 줄을
     * 본문 글꼴로 그려 줄 끝이 어긋난다.
     */
    private fun measurer(spec: LayoutSpec): CoreTextMeasurer = CoreTextMeasurer.forSpec(spec, bookFaces)

    /**
     * 책 글꼴로 짜라면 꺼내 두고 지문을 캐시 키에 넣는다. 꺼내 보니 쓸 글꼴이 없으면(WOFF 뿐 · 깨짐) 책 글꼴 없이 — 두면
     * "책 글꼴" 키로 짠 쪽이 휴대폰 글꼴로 그려진다.
     */
    private suspend fun withBookFonts(spec: LayoutSpec): LayoutSpec {
        if (!spec.useBookFonts) return spec
        val table = fontTable()
        if (bookFaces == null && !table.isEmpty) {
            bookFaces = try {
                BookFaces.prepare(document, table, "$cacheDir/bookfonts/${fnv1aHex(document.meta.id.value + "|" + edition)}")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                BookFaces.EMPTY
            }
        }
        val faces = bookFaces
        return if (faces == null || faces.isEmpty) spec.copy(useBookFonts = false, bookFontsKey = null) else spec.copy(bookFontsKey = faces.fingerprint)
    }

    /**
     * [spec] 으로 (다시) 짜고 지금 자리의 쪽을 낸다. 처음이면 저장된 자리에서, 이미 읽던 중이면(글자 크기 · 회전)
     * 보던 쪽의 첫 글자가 있는 쪽에서 — 쪽 번호로 이으면 설정을 바꾸는 순간 자리가 미끄러진다(CLAUDE.md 규칙 3).
     */
    @Throws(Exception::class)
    suspend fun layOut(
        spec: LayoutSpec,
        paper: Int,
        ink: Int,
        blendPictures: Boolean,
        accent: Int = 0xB95B3B,
        accentText: Int = 0x9A4A2C,
        /** 형광펜 숨김(모든 보기 설정): 그리지도, 눌러 열지도 않는다. 지우지는 않는다. */
        showHighlights: Boolean = true,
    ): ReaderFrame {
        this.showHighlights = showHighlights
        this.accent = accent
        this.accentText = accentText
        this.paper = paper
        this.ink = ink
        this.blendPictures = blendPictures
        val anchor = position?.let { p -> layout?.locatorAt(p.spineIndex, p.pageIndex) }
        val withEdition = withBookFonts(spec.copy(edition = edition))
        if (withEdition != this.spec || layout == null) {
            val newLayout = BookLayout(document, withEdition, pages, measurer(withEdition), fonts = fontTable())
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
        returnTo = null
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
        returnTo = null
        return show(l.resolve(l.locatorForAnchor(spine, entry.anchor)))
    }

    private suspend fun outline(): List<TocEntry> =
        toc ?: runCatching { document.outline() }.getOrDefault(emptyList()).also { toc = it }

    // ── 형광펜 · 메모(안드로이드 N1–N7) ───────────────────────────────────────

    /** 지면이 어두운가(검은 테마) — 칠을 옅게, 쪽지는 원래 색으로. */
    private val darkPaper: Boolean get() = PagePainter.luminanceOf(paper) < 0.18

    /** 그 장에서 보이는 형광펜(숨김이면 없다). 장 하나에 걸친 것만 — 쪽을 넘는 칠은 다음 단계. */
    private suspend fun visibleNotes(spine: Int): List<Annotation> =
        if (!showHighlights) emptyList() else loadNotes().filter { it.start.spine == spine && it.end.spine == spine }

    private suspend fun row(a: Annotation): NoteRow {
        val l = layout()
        val toc = outline()
        return NoteRow(
            a.id, a.start.spine, a.start.charOffset, a.end.charOffset, a.color.ordinal, a.snippet, a.note,
            runCatching { l.percentAt(a.start.spine, a.start.charOffset) }.getOrDefault(0f),
            toc.getOrNull(currentIndex(toc, a.start.spine))?.label ?: "${a.start.spine + 1}장",
            a.createdAtEpochMs,
            pageSpan(a),
        )
    }

    /**
     * 쪽을 넘어 칠했으면 "5–6쪽에 걸침"(안드로이드 `pagesOf`). 끝은 칠 바깥의 첫 글자라 한 글자 앞으로 찾는다 — 그대로 찾으면
     * 쪽 끝에서 멈춘 칠이 다음 쪽에 걸친 것으로 보인다.
     */
    private suspend fun pageSpan(a: Annotation): String? = runCatching {
        val l = layout()
        val first = l.resolve(a.start)
        val last = l.resolve(Locator.Reflow(a.end.spine, (a.end.charOffset - 1).coerceAtLeast(0)))
        if (first.spineIndex != last.spineIndex || first.pageIndex == last.pageIndex) null
        else "${first.pageIndex + 1}–${last.pageIndex + 1}쪽에 걸침"
    }.getOrNull()

    /** 지금 쪽의 ([x], [y]) 에 가장 가까운 글자 자리. 글자가 없는 쪽(그림뿐)이면 -1. */
    @Throws(Exception::class)
    suspend fun charAt(x: Float, y: Float, after: Boolean): Int {
        val (page, spine) = shown ?: return -1
        val text = layout().chapterText(spine) ?: return -1
        return PagePainter.charAt(page, text, measurer(spec!!), x, y, after) ?: -1
    }

    /** 길게 누른 자리의 낱말 [시작, 끝). 글자가 없으면 빈 배열. */
    @Throws(Exception::class)
    suspend fun wordAt(x: Float, y: Float): List<Int> {
        val (page, spine) = shown ?: return emptyList()
        val text = layout().chapterText(spine) ?: return emptyList()
        val at = PagePainter.charAt(page, text, measurer(spec!!), x, y) ?: return emptyList()
        val word = PagePainter.wordAt(text, at)
        return if (word.isEmpty()) emptyList() else listOf(word.first, word.last + 1)
    }

    /** 지금 쪽에서 글자 구간이 차지하는 네모들(고른 구간 칠 · 손잡이 · 메뉴 자리). 쪽 밖이면 빈 목록. */
    @Throws(Exception::class)
    suspend fun boxes(start: Int, end: Int): List<PagePainter.Box> {
        val (page, spine) = shown ?: return emptyList()
        val text = layout().chapterText(spine) ?: return emptyList()
        return PagePainter.rangeBoxes(page, text, measurer(spec!!), start, end)
    }

    /**
     * 고른 구간의 처지. "이어서" 는 고른 끝이 보이는 쪽의 마지막 글자(끝 공백 빼고)에 닿았고 이 장에 뒤 쪽이 있을 때만 — 장을
     * 넘는 칠은 저장 형식상 가능하지만 장 끝은 대개 뜻의 끝이다.
     */
    @Throws(Exception::class)
    suspend fun selectionState(start: Int, end: Int): SelectionState {
        val (page, spine) = shown ?: return SelectionState(false, 0)
        val p = position ?: return SelectionState(false, 0)
        val text = layout().chapterText(spine) ?: return SelectionState(false, 0)
        val can = end >= lastVisible(text, page.startChar, page.endCharExclusive) && p.pageIndex < p.pageCount - 1
        return SelectionState(can, (page.startChar - start).coerceAtLeast(0))
    }

    /**
     * "이어서 ›": 쪽을 넘기고 고른 것을 그대로 들고 간다. 시작은 그대로, 끝은 새 쪽 첫머리부터 시작하는 문장의 끝 — 대개 거기까지가
     * 이어 고르려던 곳이고, 아니면 끝 손잡이로 줄이거나 늘린다(빈손으로 넘어오면 무엇을 고르던 중인지 안 보인다). 장 끝이면 null.
     */
    @Throws(Exception::class)
    suspend fun continueSelection(start: Int, end: Int): ContinuedSelection? {
        val p = position ?: return null
        if (p.pageIndex >= p.pageCount - 1) return null
        val l = layout()
        val frame = show(l.next(p) ?: return null)
        val (page, spine) = shown ?: return null
        val text = l.chapterText(spine) ?: return null
        val shownEnd = page.endCharExclusive.coerceAtMost(text.length)
        val sentences = if (page.startChar >= shownEnd) emptyList() else io.github.kgcaudit.reader.layout.book.splitSentences(
            text.substring(page.startChar, shownEnd),
            l.paragraphStarts(spine).map { it - page.startChar }.filter { it > 0 }.toSet(),
        )
        val newEnd = sentences.firstOrNull()?.let { page.startChar + it.endExclusive } ?: lastVisible(text, page.startChar, shownEnd)
        return ContinuedSelection(frame, start, maxOf(newEnd, end))
    }

    /** 보이는 쪽의 마지막 글자 뒤 자리(끝의 공백은 뺀다). */
    private fun lastVisible(text: String, shownStart: Int, shownEnd: Int): Int {
        var e = shownEnd.coerceAtMost(text.length)
        while (e > shownStart && text[e - 1].isWhitespace()) e--
        return e
    }

    /** 지금 장의 글자 구간(복사 · 공유 · 사전 · 메모 판의 인용). */
    @Throws(Exception::class)
    suspend fun textOf(start: Int, end: Int): String {
        val (_, spine) = shown ?: return ""
        val text = layout().chapterText(spine) ?: return ""
        return text.substring(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
    }

    /**
     * 지금 장의 구간을 칠한다(메모가 있으면 함께). 같은 구간에 이미 칠이 있으면 색 · 메모만 바꾼다 — 두 번 칠해 같은 줄이
     * 둘 생기면 지울 때 하나가 남아 "지웠는데 그대로" 로 보인다. [note] 가 null 이면 있던 메모를 둔다, 빈 글이면 지운다.
     */
    @Throws(Exception::class)
    suspend fun highlight(start: Int, end: Int, color: Int, note: String?): ReaderFrame {
        val (_, spine) = shown ?: error("not laid out")
        val l = layout()
        val text = l.chapterText(spine) ?: return show(position!!)
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from >= to) return show(position!!)
        val pen = HighlightColor.entries.getOrElse(color) { HighlightColor.Yellow }
        val same = loadNotes().firstOrNull { it.start == Locator.Reflow(spine, from) && it.end == Locator.Reflow(spine, to) }
        if (same != null) {
            store.annotations.update(same.copy(color = pen).withNote(note ?: same.note))
        } else {
            store.annotations.add(
                Annotation(
                    Annotation.NO_ID, l.bookId, Locator.Reflow(spine, from), Locator.Reflow(spine, to), pen, null,
                    l.excerpt(spine, from, to).take(SNIPPET_MAX), currentTimeMillis(),
                ).withNote(note),
            )
        }
        notes = null
        return show(position!!)
    }

    /** 색만 바꾼다. */
    @Throws(Exception::class)
    suspend fun recolor(id: Long, color: Int): ReaderFrame {
        loadNotes().firstOrNull { it.id == id }?.let { a ->
            store.annotations.update(a.copy(color = HighlightColor.entries.getOrElse(color) { a.color }))
        }
        notes = null
        return show(position!!)
    }

    /** 메모를 고친다. 빈 글이면 메모를 지운다(칠은 남는다). */
    @Throws(Exception::class)
    suspend fun setNote(id: Long, note: String?): ReaderFrame {
        loadNotes().firstOrNull { it.id == id }?.let { store.annotations.update(it.withNote(note)) }
        notes = null
        return show(position!!)
    }

    @Throws(Exception::class)
    suspend fun removeNote(id: Long): ReaderFrame {
        store.annotations.remove(id)
        notes = null
        return show(position!!)
    }

    /** 이 책의 형광펜 전부(독서노트, 읽는 차례). */
    @Throws(Exception::class)
    suspend fun noteRows(): List<NoteRow> = loadNotes().map { row(it) }

    /** 독서노트에서 누름: 칠한 곳으로 간다. */
    @Throws(Exception::class)
    suspend fun goToNote(id: Long): ReaderFrame? {
        val a = loadNotes().firstOrNull { it.id == id } ?: return null
        returnTo = null
        return show(layout().resolve(a.start))
    }

    /** 지금 쪽에서 ([x], [y]) 의 칠(숨김이면 없다). 겹치면 나중에 칠한 것. */
    private suspend fun noteAt(x: Float, y: Float): Annotation? {
        val (page, spine) = shown ?: return null
        val text = layout().chapterText(spine) ?: return null
        val m = measurer(spec!!)
        return visibleNotes(spine).lastOrNull { a ->
            PagePainter.rangeBoxes(page, text, m, a.start.charOffset, a.end.charOffset)
                .any { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
        }
    }

    // ── 링크 · 각주(안드로이드 F1–F6) ─────────────────────────────────────────

    /**
     * 쪽의 ([x], [y])를 눌렀다. 그 자리에 링크가 없으면 null — 화면이 넘김 · 메뉴로 처리한다. 링크가 먼저다: 그 자리가 "다음
     * 쪽" 자리여도 넘기지 않는다(F2).
     */
    @Throws(Exception::class)
    suspend fun tapLink(x: Float, y: Float): LinkAction? {
        val (page, spine) = shown ?: return null
        val l = layout()
        val text = l.chapterText(spine) ?: return null
        val m = measurer(spec!!)
        val link = PagePainter.linkAt(page, text, m, l.links(spine), x, y)
        // 칠한 곳을 누르면 그 칠의 메뉴. 다만 링크 글자 바로 위를 눌렀으면 링크가 먼저 — 각주 표시가 칠 안에 있어도 열린다.
        // 링크의 넉넉한 누름 자리(48)보다는 칠한 글자가 앞선다(안드로이드와 같다).
        val onLinkText = link != null && PagePainter.rangeBoxes(page, text, m, link.start, link.endExclusive)
            .any { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
        if (!onLinkText) noteAt(x, y)?.let { return LinkAction("note", note = row(it)) }
        link ?: return null
        val label = text.substring(link.start.coerceIn(0, text.length), link.endExclusive.coerceIn(0, text.length)).trim()
        return when (val target = l.resolveLink(spine, link)) {
            is LinkTarget.Footnote -> LinkAction("footnote", title = "각주 $label".trim(), text = target.text, spine = target.spine, anchor = target.anchor)
            is LinkTarget.Jump -> LinkAction("jump", frame = jumpTo(target.spine, target.anchor, 0))
            is LinkTarget.External -> LinkAction("external", url = target.url)
            LinkTarget.Missing -> LinkAction(
                "missing",
                text = if (link.isFootnote(text)) "이 각주의 내용을 책에서 찾지 못했습니다" else "링크가 가리키는 곳을 책에서 찾지 못했습니다",
            )
        }
    }

    /**
     * 책 안 자리로 건너간다. 건너가기 전 자리를 기억한다(이미 건너온 길이면 처음 자리 그대로 — 각주에서 또 건너가도 "읽던
     * 곳" 은 처음 읽던 곳이다). [markLength] 가 있으면 그 자리를 칠한다: 주석 장에는 비슷한 문단이 줄지어 있어 어느 것인지
     * 찾아야 한다(F4).
     */
    @Throws(Exception::class)
    suspend fun jumpTo(spine: Int, anchor: String?, markLength: Int): ReaderFrame {
        val l = layout()
        if (spine !in l.spine().indices) return show(position ?: error("not laid out"))
        if (returnTo == null) returnTo = position?.let { l.locatorAt(it.spineIndex, it.pageIndex) }
        mark = if (markLength > 0) {
            val at = l.anchorOffset(spine, anchor)
            spine to (at until at + markLength)
        } else {
            null
        }
        return show(l.resolve(l.locatorForAnchor(spine, anchor)))
    }

    /** 시험용: 지금 쪽의 링크마다 (주소, 글자 네모 가운데). 시험이 그 자리를 "누른다". */
    internal suspend fun linkCenters(): List<Pair<String, Pair<Float, Float>>> {
        val (page, spine) = shown ?: return emptyList()
        val l = layout()
        val text = l.chapterText(spine) ?: return emptyList()
        return l.links(spine).mapNotNull { link ->
            PagePainter.rangeBoxes(page, text, measurer(spec!!), link.start, link.endExclusive).firstOrNull()
                ?.let { link.href to (it.centerX to it.centerY) }
        }
    }

    /** 건너뛰기 전 읽던 곳으로. 돌아갈 곳이 없으면 null. */
    @Throws(Exception::class)
    suspend fun returnBack(): ReaderFrame? {
        val back = returnTo ?: return null
        returnTo = null
        mark = null
        return show(layout().resolve(back))
    }

    // ── 찾기(안드로이드 E2 · E3) ─────────────────────────────────────────────

    /** 장 수. 찾기가 "1 / 3장" 을 보이는 데 쓴다. */
    @Throws(Exception::class)
    suspend fun chapterCount(): Int = layout().spine().size

    /**
     * 장 하나에서 찾는다(공통 `BookLayout.searchChapter`). 화면이 장마다 불러 나오는 대로 목록에 더한다 — 한 번에 다
     * 찾으면 큰 책에서 한참 아무것도 보이지 않는다. 읽을 수 없는 장은 빈 목록.
     */
    @Throws(Exception::class)
    suspend fun searchChapter(spine: Int, query: String): List<SearchRow> {
        val l = layout()
        val toc = outline()
        val section = toc.getOrNull(currentIndex(toc, spine))?.label ?: "${spine + 1}장"
        return l.searchChapter(spine, query).map { hit ->
            SearchRow(hit.spine, hit.start, hit.endExclusive, hit.context, hit.contextMatchStart,
                runCatching { l.percentAt(hit.spine, hit.start) }.getOrDefault(0f), section)
        }
    }

    /** 찾은 곳으로 가서 그 말을 칠한다. */
    @Throws(Exception::class)
    suspend fun goToHit(spine: Int, start: Int, endExclusive: Int): ReaderFrame {
        val l = layout()
        mark = spine to (start until endExclusive)
        returnTo = null
        return show(l.resolve(Locator.Reflow(spine, start)))
    }

    /** 찾기를 끝냈다 — 칠을 지우고 지금 쪽을 다시 낸다. */
    @Throws(Exception::class)
    suspend fun clearMark(): ReaderFrame {
        mark = null
        return show(position ?: error("not laid out"))
    }

    // ── 책갈피 ────────────────────────────────────────────────────────────

    /** 꽂은 책갈피(책 차례대로). */
    @Throws(Exception::class)
    suspend fun bookmarks(): List<BookmarkRow> {
        val s = session ?: return emptyList()
        val l = layout()
        val toc = outline()
        return s.bookmarks().map { b ->
            val at = b.locator as? Locator.Reflow
            val percent = at?.let { runCatching { l.percent(it) }.getOrDefault(0f) } ?: 0f
            val spine = at?.spine ?: 0
            val section = toc.getOrNull(currentIndex(toc, spine))?.label ?: "${spine + 1}장"
            BookmarkRow(b.id, b.snippet?.takeIf { it.isNotBlank() } ?: "(빈 쪽)", percent, section, b.createdAtEpochMs)
        }
    }

    /**
     * 독서노트에서 책갈피를 지운다. 지금 쪽을 다시 낸다 — 지운 책갈피가 보던 쪽 것이면 리본이 사라져야 한다(두면 리본을 눌러
     * 빼려다 하나 더 꽂는다).
     */
    @Throws(Exception::class)
    suspend fun removeBookmark(id: Long): ReaderFrame {
        store.remove(id)
        return show(position ?: error("not laid out"))
    }

    /** 책갈피 자리로 간다. 지워진 책갈피면 null. */
    @Throws(Exception::class)
    suspend fun goToBookmark(id: Long): ReaderFrame? {
        val s = session ?: return null
        val bookmark = s.bookmarks().firstOrNull { it.id == id } ?: return null
        returnTo = null
        return show(s.goTo(bookmark) ?: return null)
    }

    fun close() {
        (document as? Closeable)?.close()
        bookFaces?.release()
        bookFaces = null
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
                page, text, measurer(spec), spec.viewportWidthPx.toDouble(), spec.viewportHeightPx.toDouble(),
                paper, ink, pictures(target.spineIndex, page), PictureBlend.forPaper(paper, blendPictures),
                mark = mark?.takeIf { it.first == target.spineIndex }?.second, markColor = accent,
                noteMarks = l.links(target.spineIndex).filter { it.isFootnote(text) }.map { it.start until it.endExclusive },
                // 각주 번호는 작은 위첨자 — 지면마다 본문과 대비 4.5 를 넘게 본문 색 쪽으로 짙게(안드로이드 0.29).
                noteColor = PagePainter.readableOn(accentText, paper, ink),
                tints = visibleNotes(target.spineIndex).map { (it.start.charOffset until it.end.charOffset) to PEN_BASE[it.color.ordinal] },
                tintAlpha = if (darkPaper) 0.38 else 0.45,
                memos = visibleNotes(target.spineIndex).filter { it.note != null }
                    .map { it.end.charOffset to if (darkPaper) PEN_BASE[it.color.ordinal] else PEN_DEEP[it.color.ordinal] },
            )
        }
        shown = page?.let { it to target.spineIndex }
        return ReaderFrame(
            image = image,
            spineIndex = target.spineIndex,
            chapterCount = l.spine().size,
            pageIndex = target.pageIndex,
            pageCount = target.pageCount,
            percent = saved.percent,
            bookmarked = s.isBookmarked(target),
            canReturn = returnTo != null,
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

        /** 독서노트 · 내보내기에 떠 두는 칠한 글의 최대 길이(안드로이드 SNIPPET_MAX). */
        private const val SNIPPET_MAX = 400

        /** 펜 색(안드로이드 `Pen`): 칠하는 바탕 · 밝은 지면의 메모 쪽지(진하게 — 노랑 쪽지가 노랑 칠에 묻히지 않게). */
        internal val PEN_BASE = intArrayOf(0xF2C94C, 0x6FCF97, 0x56B4F2, 0xF28DB2)
        internal val PEN_DEEP = intArrayOf(0xB88A10, 0x2E9B5E, 0x2A7FC0, 0xD2537F)

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

/** 스트림 전부. 그림 · 책 글꼴을 꺼낼 때 쓴다. */
internal fun InputStream.readAllBytes(): ByteArray {
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
