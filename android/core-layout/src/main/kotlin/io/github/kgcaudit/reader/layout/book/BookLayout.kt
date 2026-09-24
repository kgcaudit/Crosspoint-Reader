package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.document.SpineItem
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.Paginator
import io.github.kgcaudit.reader.layout.TextMeasurer
import io.github.kgcaudit.reader.layout.cache.ChapterCache
import io.github.kgcaudit.reader.layout.cache.PageStore
import io.github.kgcaudit.reader.layout.html.StyleContext

/** 책 안의 한 자리. 화면을 그리는 데 필요한 최소한이다. */
data class ReadingPosition(
    val spineIndex: Int,
    val pageIndex: Int,
    val pageCount: Int,
) {
    val isFirstPageOfChapter: Boolean get() = pageIndex == 0
    val isLastPageOfChapter: Boolean get() = pageIndex >= pageCount - 1
}

/**
 * 책 한 권을 페이지로 다루는 창구.
 *
 * 리더 화면은 이 클래스만 쓴다. 조판이 필요한지 캐시에 있는지, 챕터 경계를 넘는지를
 * 여기서 흡수하므로 위쪽에는 "다음 페이지" 와 "이 위치로 가라" 만 남는다.
 *
 * 저장하는 위치는 언제나 [Locator.Reflow] — 즉 **글자 오프셋**이다. 페이지 번호를
 * 저장하면 글자 크기를 한 번 바꾸는 순간 위치가 미끄러진다. 페이지 번호는 그때그때
 * 글자 오프셋에서 다시 구한다(색인 파일 이분 탐색이라 싸다).
 */
class BookLayout(
    private val document: ReflowDocument,
    private val spec: LayoutSpec,
    private val store: PageStore,
    private val measurer: TextMeasurer,
    styleContext: StyleContext = StyleContext.of(spec),
    /** 책 글꼴표. 측정기가 같은 표로 글꼴을 골라야 한다 — 번호가 같은 가족을 가리켜야 폭이 맞는다. */
    fonts: BookFontTable = BookFontTable.EMPTY,
) {

    /** 이 책의 식별자. 책갈피·진도가 같은 값을 쓰도록 여기서 한 번만 꺼낸다. */
    val bookId: BookId get() = document.meta.id

    private val loader = ChapterLoader(document, styleContext, fonts)
    private var spineCache: List<SpineItem>? = null

    suspend fun spine(): List<SpineItem> = spineCache ?: document.spine().also { spineCache = it }

    // ── 페이지 ──────────────────────────────────────────────────────

    /**
     * 챕터 하나를 조판해 캐시에 넣는다. 이미 끝까지 캐시돼 있으면 아무것도 하지 않는다.
     *
     * [onFirstPages] 는 앞쪽 몇 페이지가 나온 직후에 한 번 불린다. 화면을 즉시 띄우려면
     * 챕터 전체가 끝날 때까지 기다릴 이유가 없다 — 그게 이 콜백이 있는 이유다.
     */
    suspend fun ensurePaginated(
        spineIndex: Int,
        firstPagesThreshold: Int = FIRST_PAGES,
        onFirstPages: (() -> Unit)? = null,
    ): Int {
        val cache = cache(spineIndex)
        cache.readIndex()?.let { header ->
            if (header.complete) return header.pageCount
        }

        val chapter = loader.load(spineIndex)
        val paginator = Paginator(spec, measurer)
        var notified = false

        cache.writer(chapter.text).use { writer ->
            for (page in paginator.paginate(chapter.text, chapter.blocks)) {
                writer.add(page)
                if (!notified && writer.pageCount >= firstPagesThreshold) {
                    writer.flush()
                    onFirstPages?.invoke()
                    notified = true
                }
            }
            writer.finish()
            return writer.pageCount
        }
    }

    suspend fun pageCount(spineIndex: Int): Int =
        cache(spineIndex).readIndex()?.takeIf { it.complete }?.pageCount
            ?: ensurePaginated(spineIndex)

    suspend fun page(spineIndex: Int, pageIndex: Int): Page? {
        ensurePaginated(spineIndex)
        return cache(spineIndex).readPage(pageIndex)
    }

    /** 챕터의 조판 텍스트. 책갈피 미리보기와 검색이 쓴다. */
    suspend fun chapterText(spineIndex: Int): String? {
        ensurePaginated(spineIndex)
        return cache(spineIndex).readText()
    }

    // ── 위치 ────────────────────────────────────────────────────────

    /** 글자 오프셋을 페이지 번호로 바꾼다. */
    suspend fun resolve(locator: Locator.Reflow): ReadingPosition {
        val spineIndex = locator.spine.coerceIn(0, (spine().size - 1).coerceAtLeast(0))
        val count = pageCount(spineIndex)
        val pageIndex = cache(spineIndex).pageOf(locator.charOffset) ?: 0
        return ReadingPosition(spineIndex, pageIndex, count)
    }

    /** 페이지 번호를 저장할 위치로 바꾼다. */
    suspend fun locatorAt(spineIndex: Int, pageIndex: Int): Locator.Reflow {
        val start = cache(spineIndex).readPageStarts()?.getOrNull(pageIndex) ?: 0
        return Locator.Reflow(spineIndex, start)
    }

    /**
     * 다음 페이지. 챕터 끝이면 다음 챕터의 첫 페이지로 넘어간다. 책 끝이면 null.
     *
     * 챕터 경계를 여기서 넘기므로 화면 쪽에는 "끝인가?" 판단이 남지 않는다. 그 판단이
     * 두 군데 있으면 마지막 챕터 끝에서 한 번 더 넘어가는 버그가 생긴다.
     */
    suspend fun next(position: ReadingPosition): ReadingPosition? {
        if (position.pageIndex + 1 < position.pageCount) {
            return position.copy(pageIndex = position.pageIndex + 1)
        }
        val nextChapter = position.spineIndex + 1
        if (nextChapter >= spine().size) return null
        return ReadingPosition(nextChapter, 0, pageCount(nextChapter))
    }

    /** 이전 페이지. 챕터 처음이면 앞 챕터의 마지막 페이지. 책 처음이면 null. */
    suspend fun previous(position: ReadingPosition): ReadingPosition? {
        if (position.pageIndex > 0) return position.copy(pageIndex = position.pageIndex - 1)

        val previousChapter = position.spineIndex - 1
        if (previousChapter < 0) return null
        val count = pageCount(previousChapter)
        return ReadingPosition(previousChapter, (count - 1).coerceAtLeast(0), count)
    }

    // ── 두쪽보기 ────────────────────────────────────────────────────

    /**
     * 두쪽보기에서 [position] 이 들어 있는 펼침의 왼쪽 쪽. 장마다 0 · 2 · 4 … 쪽이 왼쪽이다.
     *
     * 짝을 책 전체가 아니라 **장마다** 센다 — 새 장은 언제나 왼쪽에서 시작하고, 홀수 쪽으로 끝난 장은 오른쪽이
     * 빈다(종이책과 같다). 책 전체로 세면 장 경계를 넘을 때마다 짝이 한 칸씩 밀려, 같은 쪽이 글자 크기에 따라
     * 왼쪽에 왔다 오른쪽에 왔다 한다.
     */
    fun spreadStart(position: ReadingPosition): ReadingPosition =
        position.copy(pageIndex = position.pageIndex - position.pageIndex % 2)

    /** 펼침의 오른쪽 쪽. 장이 홀수 쪽으로 끝나 비어 있으면 null. */
    fun spreadRight(position: ReadingPosition): ReadingPosition? {
        val left = spreadStart(position)
        return if (left.pageIndex + 1 < left.pageCount) left.copy(pageIndex = left.pageIndex + 1) else null
    }

    /** 다음 펼침(두 쪽 넘김). 장 끝이면 다음 장의 첫 펼침. 책 끝이면 null. */
    suspend fun nextSpread(position: ReadingPosition): ReadingPosition? {
        val left = spreadStart(position)
        if (left.pageIndex + 2 < left.pageCount) return left.copy(pageIndex = left.pageIndex + 2)
        val nextChapter = position.spineIndex + 1
        if (nextChapter >= spine().size) return null
        return ReadingPosition(nextChapter, 0, pageCount(nextChapter))
    }

    /** 앞 펼침. 장 처음이면 앞 장의 마지막 펼침(그 장이 홀수 쪽이면 마지막 쪽 하나). 책 처음이면 null. */
    suspend fun previousSpread(position: ReadingPosition): ReadingPosition? {
        val left = spreadStart(position)
        if (left.pageIndex >= 2) return left.copy(pageIndex = left.pageIndex - 2)
        val previousChapter = position.spineIndex - 1
        if (previousChapter < 0) return null
        val count = pageCount(previousChapter)
        return spreadStart(ReadingPosition(previousChapter, (count - 1).coerceAtLeast(0), count))
    }

    /**
     * 목차 항목이 가리키는 위치. 앵커(`#절2`)가 있으면 그 자리까지 찾아간다.
     *
     * 앵커를 무시하고 챕터 첫 페이지로 보내는 리더가 흔하지만, 한 파일에 여러 절이
     * 들어 있는 책에서는 목차를 눌러도 늘 같은 데로 가는 것처럼 보인다.
     */
    suspend fun locatorForAnchor(spineIndex: Int, anchor: String?): Locator.Reflow {
        if (anchor == null) return Locator.Reflow(spineIndex, 0)
        val offset = loader.load(spineIndex).anchors[anchor] ?: 0
        return Locator.Reflow(spineIndex, offset)
    }

    // ── 진도 ────────────────────────────────────────────────────────

    /**
     * 책 전체에서의 진도(0~100).
     *
     * 챕터 무게를 **파일 크기**로 잡는다. 정확한 글자 수는 그 챕터를 조판해 봐야 알고,
     * 진도 막대를 그리려고 책 전체를 조판하는 것은 말이 안 된다. 압축 전 바이트 수는
     * 글자 수에 거의 비례하므로 막대의 쓸모에는 충분하다.
     *
     * 챕터 안의 위치는 캐시된 텍스트 길이로 나눈다. 아직 조판되지 않은 챕터면 챕터
     * 시작으로 본다 — 진도가 뒤로 가는 것보다 조금 적게 나오는 편이 낫다.
     */
    suspend fun percent(locator: Locator.Reflow): Float {
        val items = spine()
        if (items.isEmpty()) return 0f

        val weights = items.map { it.sizeBytes.coerceAtLeast(1L).toDouble() }
        val total = weights.sum()
        val index = locator.spine.coerceIn(0, items.size - 1)

        val before = weights.take(index).sum()
        val within = chapterFraction(index, locator.charOffset)
        val percent = (before + within * weights[index]) / total * 100.0
        return percent.coerceIn(0.0, 100.0).toFloat()
    }

    /**
     * 진도(0~100)에 해당하는 위치. [percent] 의 역이다 — 진행 막대로 옮길 때 쓴다.
     *
     * 같은 무게(챕터 파일 크기)로 챕터를 고르고, 그 챕터 안에서는 글자 비율로 자리를 잡는다.
     * 목차가 챕터보다 훨씬 적은 책(항목 7개가 챕터 113개를 덮는다)에서는 목차로는 가운데로
     * 갈 수 없어, 이 길이 사실상 유일한 "멀리 가기" 다.
     *
     * 고른 챕터만 조판한다. 범위를 벗어난 값은 처음·끝으로 자른다.
     */
    suspend fun locatorAtPercent(percent: Float): Locator.Reflow {
        val items = spine()
        if (items.isEmpty()) return Locator.Reflow(0, 0)

        val weights = items.map { it.sizeBytes.coerceAtLeast(1L).toDouble() }
        val target = percent.coerceIn(0f, 100f) / 100.0 * weights.sum()

        // 목표가 든 챕터를 찾는다. 끝(100%)은 어느 챕터의 "앞" 에도 들지 않으므로 마지막
        // 챕터의 끝으로 둔다 — 누적값을 그대로 쓰면 마지막 챕터의 **처음**으로 떨어진다.
        var before = 0.0
        var index = -1
        for (i in items.indices) {
            if (target < before + weights[i]) {
                index = i
                break
            }
            before += weights[i]
        }
        val within = if (index < 0) {
            index = items.size - 1
            1.0
        } else {
            ((target - before) / weights[index]).coerceIn(0.0, 1.0)
        }

        ensurePaginated(index)
        val length = cache(index).readIndex()?.textLength ?: 0
        val offset = (within * length).toInt().coerceIn(0, (length - 1).coerceAtLeast(0))
        return Locator.Reflow(index, offset)
    }

    private fun chapterFraction(spineIndex: Int, charOffset: Int): Double {
        val length = cache(spineIndex).readIndex()?.textLength ?: return 0.0
        if (length <= 0) return 0.0
        return (charOffset.toDouble() / length).coerceIn(0.0, 1.0)
    }

    // ── 캐시 ────────────────────────────────────────────────────────

    private fun cache(spineIndex: Int): ChapterCache =
        store.chapter(document.meta.id, spec, spineIndex)

    /** 이 책의 다른 조판 캐시를 정리한다. 설정을 바꾼 직후에 부른다. */
    fun pruneStaleCaches() = store.pruneOtherLayouts(document.meta.id, spec)

    private companion object {
        /** 이만큼 나오면 화면을 띄운다. 한 화면 + 앞뒤 한 장이면 넘김이 끊기지 않는다. */
        const val FIRST_PAGES = 3
    }
}
