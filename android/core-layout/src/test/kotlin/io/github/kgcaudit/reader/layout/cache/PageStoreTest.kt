package io.github.kgcaudit.reader.layout.cache

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.FakeMeasurer
import io.github.kgcaudit.reader.layout.InlineRun
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.Paginator
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.TextStyle
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageStoreTest {

    private val root: File = File.createTempFile("pagestore", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private val store = PageStore(root)
    private val book = BookId("content://media/external/file/1234")
    private val other = BookId("content://media/external/file/9999")

    private val spec = LayoutSpec(
        viewportWidthPx = 360f,
        viewportHeightPx = 200f,
        margin = Insets.all(20f),
        baseSizePx = 10f,
        align = TextAlign.Justify,
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** 한 문단이 여러 페이지로 나뉘는 정도의 본문. */
    private val text = (1..30).joinToString("\n") {
        "$it 번째 줄, 어린 왕자는 사막에 떨어진 조종사를 만났다."
    }

    private fun blocks(): List<Block> {
        val result = ArrayList<Block>()
        var offset = 0
        text.split('\n').forEach { line ->
            result.add(Block.Paragraph(listOf(InlineRun(offset, offset + line.length))))
            offset += line.length + 1
        }
        return result
    }

    private fun paginate(layout: LayoutSpec = spec): List<Page> =
        Paginator(layout, FakeMeasurer(baseSizePx = layout.baseSizePx))
            .paginate(text, blocks())
            .toList()

    // ── 왕복 ────────────────────────────────────────────────────────

    @Test
    fun `a chapter written to disk comes back identical`() {
        val pages = paginate()
        assertTrue(pages.size > 2, "이 테스트가 뜻을 가지려면 여러 페이지여야 한다")

        val cache = store.chapter(book, spec, spineIndex = 0)
        cache.write(text, pages)

        assertTrue(cache.exists)
        assertEquals(text, cache.readText())
        val header = cache.readIndex()!!
        assertEquals(pages.size, header.pageCount)
        assertEquals(text.length, header.textLength)
        assertTrue(header.complete)

        pages.forEach { expected ->
            assertEquals(expected, cache.readPage(expected.index), "페이지 ${expected.index} 가 달라졌다")
        }
    }

    @Test
    fun `styles and objects survive the round trip`() {
        val page = Page(
            index = 0,
            startChar = 0,
            endCharExclusive = 5,
            runs = listOf(
                io.github.kgcaudit.reader.layout.PlacedRun(
                    start = 0,
                    endExclusive = 5,
                    style = TextStyle(bold = true, italic = true, sizeScale = 1.5f, underline = true),
                    xPx = 12.5f,
                    baselineYPx = 33.25f,
                ),
            ),
            images = listOf(
                io.github.kgcaudit.reader.layout.PlacedImage("이미지/장미.png", 1f, 2f, 3f, 4f),
            ),
            rules = listOf(io.github.kgcaudit.reader.layout.PlacedRule(5f, 6f, 7f, 1f)),
        )
        val cache = store.chapter(book, spec, spineIndex = 3)
        cache.write("가나다라마", listOf(page))
        assertEquals(page, cache.readPage(0))
    }

    @Test
    fun `an absent chapter reads as nothing rather than as an error`() {
        val cache = store.chapter(book, spec, spineIndex = 7)
        assertFalse(cache.exists)
        assertNull(cache.readIndex())
        assertNull(cache.readText())
        assertNull(cache.readPage(0))
        assertNull(cache.pageOf(0))
        assertNull(cache.readPageStarts())
    }

    // ── 위치 찾기 ───────────────────────────────────────────────────

    @Test
    fun `an offset resolves to the page that holds it`() {
        val pages = paginate()
        val cache = store.chapter(book, spec, spineIndex = 0)
        cache.write(text, pages)

        pages.forEach { page ->
            assertEquals(page.index, cache.pageOf(page.startChar), "시작 글자가 제 페이지를 못 찾았다")
            val middle = (page.startChar + page.endCharExclusive) / 2
            assertEquals(page.index, cache.pageOf(middle), "중간 글자가 제 페이지를 못 찾았다")
        }
    }

    @Test
    fun `offsets outside the chapter clamp instead of failing`() {
        // 조판이 바뀌어 글자 수가 줄어든 캐시에서도 화면이 비면 안 된다.
        val pages = paginate()
        val cache = store.chapter(book, spec, spineIndex = 0)
        cache.write(text, pages)

        assertEquals(0, cache.pageOf(-100))
        assertEquals(pages.lastIndex, cache.pageOf(text.length + 10_000))
    }

    @Test
    fun `page starts come from the index alone`() {
        val pages = paginate()
        val cache = store.chapter(book, spec, spineIndex = 0)
        cache.write(text, pages)
        assertEquals(pages.map { it.startChar }, cache.readPageStarts()!!.toList())
    }

    // ── 부분 캐시 ───────────────────────────────────────────────────

    @Test
    fun `a flush leaves a partial cache that a later run can finish`() {
        val pages = paginate()
        val cache = store.chapter(book, spec, spineIndex = 0)

        cache.writer(text).use { writer ->
            pages.take(2).forEach(writer::add)
            writer.flush()
        }

        val partial = cache.readIndex()!!
        assertEquals(2, partial.pageCount)
        assertFalse(partial.complete, "부분 캐시가 완료로 표시됐다 — 책이 여기서 끝난 것처럼 보인다")
        assertEquals(pages[1], cache.readPage(1))
        assertNull(cache.readPage(2))

        // 다시 조판해 끝까지 쓴다.
        cache.write(text, pages)
        assertTrue(cache.readIndex()!!.complete)
        assertEquals(pages.size, cache.readIndex()!!.pageCount)
    }

    @Test
    fun `closing without finishing keeps the cache partial`() {
        val pages = paginate()
        val cache = store.chapter(book, spec, spineIndex = 0)
        cache.writer(text).use { writer -> pages.take(3).forEach(writer::add) }

        assertFalse(cache.readIndex()!!.complete)
        assertEquals(3, cache.readIndex()!!.pageCount)
    }

    @Test
    fun `a writer with no pages writes nothing`() {
        val cache = store.chapter(book, spec, spineIndex = 0)
        cache.writer(text).use { it.flush() }
        assertFalse(cache.exists)
    }

    // ── 캐시 분리 ───────────────────────────────────────────────────

    @Test
    fun `a different layout is a different cache`() {
        val pages = paginate()
        store.chapter(book, spec, 0).write(text, pages)

        val bigger = spec.copy(baseSizePx = 14f)
        assertNotEquals(spec.cacheKey, bigger.cacheKey)
        // 설정을 바꾸면 캐시가 없는 상태여야 한다. 있으면 옛 페이지가 화면에 섞인다.
        assertFalse(store.chapter(book, bigger, 0).exists)

        store.chapter(book, bigger, 0).write(text, paginate(bigger))
        assertTrue(store.chapter(book, spec, 0).exists, "다른 설정 캐시가 이걸 덮어썼다")
    }

    @Test
    fun `books and chapters do not collide`() {
        store.chapter(book, spec, 0).write(text, paginate())
        assertFalse(store.chapter(other, spec, 0).exists)
        assertFalse(store.chapter(book, spec, 1).exists)
    }

    @Test
    fun `a content uri is usable as a cache directory name`() {
        // 책 식별자에 / 와 : 가 들어 있다. 그대로 쓰면 디렉터리를 만들 수 없다.
        store.chapter(book, spec, 0).write(text, paginate())
        assertTrue(store.chapter(book, spec, 0).exists)
        assertTrue(root.walkTopDown().any { it.name.endsWith(".idx") })
    }

    // ── 정리 ────────────────────────────────────────────────────────

    @Test
    fun `deleting a book removes all of its layouts at once`() {
        val bigger = spec.copy(baseSizePx = 14f)
        store.chapter(book, spec, 0).write(text, paginate())
        store.chapter(book, bigger, 0).write(text, paginate(bigger))
        store.chapter(other, spec, 0).write(text, paginate())

        store.delete(book)
        assertFalse(store.chapter(book, spec, 0).exists)
        assertFalse(store.chapter(book, bigger, 0).exists)
        assertTrue(store.chapter(other, spec, 0).exists, "다른 책까지 지워졌다")
    }

    @Test
    fun `pruning keeps the current layout and drops the rest`() {
        val bigger = spec.copy(baseSizePx = 14f)
        store.chapter(book, spec, 0).write(text, paginate())
        store.chapter(book, bigger, 0).write(text, paginate(bigger))

        store.pruneOtherLayouts(book, keep = bigger)
        assertTrue(store.chapter(book, bigger, 0).exists)
        assertFalse(store.chapter(book, spec, 0).exists)
    }

    @Test
    fun `size and clear-all work`() {
        store.chapter(book, spec, 0).write(text, paginate())
        assertTrue(store.sizeBytes() > 0)
        store.deleteAll()
        assertEquals(0, store.sizeBytes())
        assertFalse(store.chapter(book, spec, 0).exists)
    }

    @Test
    fun `a chapter deletes itself without touching its neighbours`() {
        store.chapter(book, spec, 0).write(text, paginate())
        store.chapter(book, spec, 1).write(text, paginate())
        store.chapter(book, spec, 0).delete()
        assertFalse(store.chapter(book, spec, 0).exists)
        assertTrue(store.chapter(book, spec, 1).exists)
    }

    // ── 깨진 캐시 ───────────────────────────────────────────────────

    @Test
    fun `a text that does not match the index makes the cache invalid`() {
        val cache = store.chapter(book, spec, 0)
        cache.write(text, paginate())

        // 텍스트만 바뀌었다(조판이 바뀌어 캐시가 반만 갱신된 상황을 흉내 낸다).
        root.walkTopDown().first { it.name.endsWith(".txt") }.writeText("짧아진 본문")

        assertNull(cache.readIndex(), "짝이 안 맞는 캐시를 유효하다고 읽었다")
        assertNull(cache.readPage(0))
        assertNull(cache.pageOf(0))
    }

    @Test
    fun `a truncated index is treated as no cache`() {
        val cache = store.chapter(book, spec, 0)
        cache.write(text, paginate())

        val indexFile = root.walkTopDown().first { it.name.endsWith(".idx") }
        indexFile.writeBytes(indexFile.readBytes().copyOf(8))

        assertNull(cache.readIndex())
        assertNull(cache.readPage(0))
    }

    @Test
    fun `garbage in place of the index is treated as no cache`() {
        val cache = store.chapter(book, spec, 0)
        cache.write(text, paginate())
        root.walkTopDown().first { it.name.endsWith(".idx") }.writeBytes(ByteArray(64) { 0x7F })
        assertNull(cache.readIndex())
    }

    @Test
    fun `no half-written temp files are left behind`() {
        store.chapter(book, spec, 0).write(text, paginate())
        assertTrue(
            root.walkTopDown().none { it.name.endsWith(".tmp") },
            "임시 파일이 남았다 — 다음 실행이 이걸 캐시로 볼 수 있다",
        )
    }

    @Test
    fun `rewriting a chapter replaces it instead of appending`() {
        val cache = store.chapter(book, spec, 0)
        cache.write(text, paginate())
        val firstCount = cache.readIndex()!!.pageCount

        val shorter = "한 줄뿐인 본문"
        cache.write(shorter, listOf(Page(0, 0, shorter.length)))
        assertEquals(1, cache.readIndex()!!.pageCount)
        assertEquals(shorter, cache.readText())
        assertTrue(firstCount > 1)
    }
}
