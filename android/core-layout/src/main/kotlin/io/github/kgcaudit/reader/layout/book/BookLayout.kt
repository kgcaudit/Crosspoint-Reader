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
import io.github.kgcaudit.reader.layout.html.TextChapter

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
     * 챕터 전체가 끝날 때까지 기다릴 이유가 없다 — 그게 이 콜백이 있는 이유다. 앞쪽을 부분 캐시로 내리는 것도
     * 받을 쪽이 있을 때만 한다. 아무도 기다리지 않는데 내리면 장 텍스트를 한 번 더 써서 장마다 쓰기만 두 배가 된다.
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

        val chapter = try {
            loader.load(spineIndex)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 이 장만 버리고 계속 읽는다(깨진 입력에 관대하게). 예외를 올리면 다음 장의 쪽 수를 구하는 넘기기 · 목차 ·
            // 진행 막대가 모두 같은 오류로 끝나, 깨진 장 너머로는 갈 수 없었다. 안내 한 쪽을 두되 끝났다고 적지 않는다
            // — 저장소가 잠깐 빠져 못 읽은 것이면 다음에 다시 읽는다.
            val notice = TextChapter.parse(UNREADABLE_CHAPTER)
            cache.writer(notice.text).use { writer ->
                // 끝내지 않고 닫으면 부분 캐시로 남는다 — 쪽은 보이되 다음에 다시 읽어 본다.
                Paginator(spec, measurer).paginate(notice.text, notice.blocks).forEach(writer::add)
                return writer.pageCount
            }
        }
        val paginator = Paginator(spec, measurer)
        var notified = false

        cache.writer(chapter.text).use { writer ->
            for (page in paginator.paginate(chapter.text, chapter.blocks)) {
                writer.add(page)
                if (onFirstPages != null && !notified && writer.pageCount >= firstPagesThreshold) {
                    writer.flush()
                    onFirstPages()
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

    // ── 링크 · 각주 · 검색 ─────────────────────────────────────────

    /**
     * 최근에 읽은 챕터 몇 개. 링크를 누르거나 각주를 찾을 때마다 XHTML 을 다시 해석하지 않게 둔다. 조판
     * 스레드 하나에서만 쓴다(BookReader).
     */
    private val recent = object : LinkedHashMap<Int, io.github.kgcaudit.reader.layout.html.Chapter>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, io.github.kgcaudit.reader.layout.html.Chapter>?) = size > 3
    }

    private suspend fun chapter(spineIndex: Int) = recent[spineIndex] ?: loader.load(spineIndex).also { recent[spineIndex] = it }

    /** [anchor] 가 가리키는 글자 자리(없으면 장 처음). */
    suspend fun anchorOffset(spineIndex: Int, anchor: String?): Int =
        anchor?.let { runCatching { chapter(spineIndex).anchors[it] }.getOrNull() } ?: 0

    /** 이 장의 링크. 각주 표시를 칠하고 누른 자리를 찾는 데 쓴다. */
    suspend fun links(spineIndex: Int): List<io.github.kgcaudit.reader.layout.html.Link> =
        runCatching { chapter(spineIndex).links }.getOrDefault(emptyList())

    /**
     * [spineIndex] 장의 [link] 를 누르면 어디로 가는가.
     *
     * 규칙 6: 가리키는 파일 · id 가 없으면 [LinkTarget.Missing] — 책을 닫거나 엉뚱한 곳으로 가지 않는다.
     */
    suspend fun resolveLink(spineIndex: Int, link: io.github.kgcaudit.reader.layout.html.Link): LinkTarget {
        if (link.isExternal) return LinkTarget.External(link.href)
        val items = spine()
        val path = link.href.substringBefore('#')
        val anchor = link.href.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val target = if (path.isEmpty()) {
            spineIndex
        } else {
            val from = items.getOrNull(spineIndex)?.href.orEmpty()
            val resolved = document.resolveHref(from, decode(path))
            items.indexOfFirst { it.href == resolved || decode(it.href) == resolved }.takeIf { it >= 0 }
                ?: return LinkTarget.Missing
        }
        val here = runCatching { chapter(spineIndex) }.getOrNull() ?: return LinkTarget.Missing
        val there = runCatching { chapter(target) }.getOrNull() ?: return LinkTarget.Missing
        if (anchor != null && anchor !in there.anchors) return LinkTarget.Missing
        val footnote = anchor != null && link.isFootnote(here.text, targetIsNote = anchor in there.noteIds)
        if (!footnote) return LinkTarget.Jump(target, anchor)
        val offset = there.anchors.getValue(anchor!!)
        val text = noteText(there.text, there.blocks, there.anchors.values, offset)
        return if (text.isBlank()) LinkTarget.Jump(target, anchor) else LinkTarget.Footnote(text, target, anchor)
    }

    private fun decode(path: String): String = runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)

    /** 장의 [from]..[to] 를 한 줄 글로(문단 사이 한 칸). 장을 읽지 못하면 빈 문자열. */
    suspend fun excerpt(spineIndex: Int, from: Int, to: Int): String =
        runCatching {
            val c = chapter(spineIndex)
            excerpt(c.text, from, to, paragraphStarts(c))
        }.getOrDefault("")

    /** 장의 문단이 시작하는 글자 자리들. 고른 글을 복사 · 공유할 때 문단 사이를 띄운다. */
    suspend fun paragraphStarts(spineIndex: Int): Set<Int> = runCatching { paragraphStarts(chapter(spineIndex)) }.getOrDefault(emptySet())

    private fun paragraphStarts(c: io.github.kgcaudit.reader.layout.html.Chapter): Set<Int> =
        c.blocks.mapNotNull { (it as? io.github.kgcaudit.reader.layout.Block.Paragraph)?.runs?.firstOrNull()?.start }.toHashSet()

    /** 장 하나에서 찾기. 리더가 장마다 따로 불러, 찾는 동안에도 쪽을 넘길 수 있게 한다. */
    suspend fun searchChapter(spineIndex: Int, query: String): List<SearchHit> =
        runCatching {
            val c = chapter(spineIndex)
            findAll(c.text, query, spineIndex, paragraphStarts(c))
        }.getOrDefault(emptyList())

    /** 장 [spineIndex] 의 [offset] 자리가 책의 몇 % 인가(조판 전에도 — 찾기 결과 목록에 보인다). */
    suspend fun percentAt(spineIndex: Int, offset: Int): Float {
        val items = spine()
        if (items.isEmpty()) return 0f
        val index = spineIndex.coerceIn(0, items.size - 1)
        // [percent] 와 달리 조판 전 장도 읽어 길이를 잰다 — 찾기 결과는 아직 펴 보지 않은 장에 많고, 그 장의 처음으로
        // 치면 한 장 안의 결과가 모두 같은 % 로 보인다.
        val length = chapterLength(index).coerceAtLeast(1)
        return bookPercent(weights(items), index, offset.toDouble() / length)
    }

    /** 장의 글자 수(조판 전에도). 남은 시간을 셀 때 쓴다. */
    suspend fun chapterLength(spineIndex: Int): Int =
        cache(spineIndex).readIndex()?.textLength ?: runCatching { chapter(spineIndex).text.length }.getOrDefault(0)

    /**
     * 책에서 [spineIndex] 장 뒤에 남은 글자 수(추정). 뒤 장들은 파일 크기에 이 장의 "바이트당 글자" 를 곱한다 —
     * 진도 막대와 같은 무게다. 모든 장을 읽어 세면 정확하지만 큰 책에서 몇 초가 걸린다.
     */
    suspend fun charsAfterChapter(spineIndex: Int): Long {
        val items = spine()
        if (spineIndex !in items.indices) return 0
        // 비율은 글이 충분한 장에서 잰다. 표지 · 속표지(글자 몇 개에 파일 수백 바이트)의 비율로 어림하면 1MB 책의 남은
        // 글이 천여 자로 잡혀 "1분 남음" 이 떴다가 1장에 들어서면 몇 시간으로 튀었다. 몇 장 앞까지만 본다(장마다 읽는 값).
        var perByte = 0.0
        for (i in spineIndex until minOf(items.size, spineIndex + RATIO_PROBE)) {
            val length = chapterLength(i)
            perByte = length.toDouble() / items[i].sizeBytes.coerceAtLeast(1L)
            if (length >= RATIO_MIN_CHARS) break
        }
        return items.drop(spineIndex + 1).sumOf { (it.sizeBytes * perByte).toLong() }
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
        val offset = runCatching { chapter(spineIndex).anchors[anchor] }
            .onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
            .getOrNull() ?: 0
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

        val index = locator.spine.coerceIn(0, items.size - 1)
        return bookPercent(weights(items), index, chapterFraction(index, locator.charOffset))
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

        val weights = weights(items)
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

    /**
     * 장마다의 무게(파일 크기, 0 이면 1). [percent] · [percentAt] · [locatorAtPercent] 가 **같은** 무게를 써야 한다 —
     * 따로 적으면 하나만 고쳐질 때 찾기 목록의 % 로 막대를 옮겨도 그 자리에 가지 않는다.
     */
    private fun weights(items: List<SpineItem>): DoubleArray =
        DoubleArray(items.size) { items[it].sizeBytes.coerceAtLeast(1L).toDouble() }

    /** [index] 장 앞 무게 + 장 안 비율([within], 0~1 로 자른다) → 책의 몇 %. */
    private fun bookPercent(weights: DoubleArray, index: Int, within: Double): Float {
        var before = 0.0
        for (i in 0 until index) before += weights[i]
        val percent = (before + within.coerceIn(0.0, 1.0) * weights[index]) / weights.sum() * 100.0
        return percent.coerceIn(0.0, 100.0).toFloat()
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

    companion object {
        /** 이만큼 나오면 화면을 띄운다. 한 화면 + 앞뒤 한 장이면 넘김이 끊기지 않는다. */
        private const val FIRST_PAGES = 3

        /** 읽지 못한 장 자리에 보이는 쪽. */
        const val UNREADABLE_CHAPTER = "이 장을 읽지 못했습니다. 파일의 이 부분이 깨졌을 수 있습니다. 다음 쪽으로 넘기면 이어서 읽습니다."
    }
}

/** 남은 시간 비율을 재려고 앞으로 볼 장 수와, 비율을 믿을 만한 장의 글자 수. */
private const val RATIO_PROBE = 4
private const val RATIO_MIN_CHARS = 300
