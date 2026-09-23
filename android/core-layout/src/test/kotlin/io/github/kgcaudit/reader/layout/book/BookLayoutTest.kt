package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.ByteSource
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.layout.FakeMeasurer
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.cache.PageStore
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 리더 화면이 실제로 부르는 것만 검증한다.
 *
 * 여기서 지키는 성질은 사용자가 바로 느끼는 것들이다 — 설정을 바꿔도 읽던 자리로
 * 돌아오는가, 챕터 경계에서 페이지 넘김이 끊기지 않는가, 진도가 뒤로 가지 않는가.
 */
class BookLayoutTest {

    private val root: File = File.createTempFile("booklayout", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val store = PageStore(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val spec = LayoutSpec(
        viewportWidthPx = 300f,
        viewportHeightPx = 160f,
        margin = Insets.all(20f),
        baseSizePx = 10f,
        align = TextAlign.Justify,
    )

    private fun measurer(layout: LayoutSpec = spec) = FakeMeasurer(baseSizePx = layout.baseSizePx)

    // ── EPUB 세 챕터 ────────────────────────────────────────────────

    private fun chapterBody(label: String, lines: Int) = buildString {
        append("<html><body><h1>$label</h1>")
        repeat(lines) { append("<p>$label $it 번째 문단, 어린 왕자는 사막에서 조종사를 만났다.</p>") }
        append("</body></html>")
    }

    private fun epub(): ByteArray {
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>세 장</dc:title></metadata>
              <manifest>
                <item id="n" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="c3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
            </package>
        """.trimIndent()
        val nav = """
            <html><body><nav epub:type="toc"><ol>
              <li><a href="ch2.xhtml">제2장</a></li>
              <li><a href="ch2.xhtml#mid">제2장 가운데</a></li>
            </ol></nav></body></html>
        """.trimIndent()
        val ch2 = """
            <html><body><h1>제2장</h1>
              <p>앞부분 문단이다. 여기는 챕터의 처음이다.</p>
              <p id="mid">가운데 문단이다. 목차가 이 자리를 가리킨다.</p>
              <p>뒷부분 문단이다.</p>
            </body></html>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf(
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                "content.opf" to opf,
                "nav.xhtml" to nav,
                "ch1.xhtml" to chapterBody("제1장", 6),
                "ch2.xhtml" to ch2,
                "ch3.xhtml" to chapterBody("제3장", 10),
            ).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun openEpub() = EpubDocument.open(BookId("book-1"), "book.epub", SeekableSource.of(epub()))

    private fun layout(
        document: io.github.kgcaudit.reader.document.ReflowDocument,
        layoutSpec: LayoutSpec = spec,
    ) = BookLayout(document, layoutSpec, store, measurer(layoutSpec))

    // ── 조판과 캐시 ─────────────────────────────────────────────────

    @Test
    fun `pagination writes a complete cache and is not repeated`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            val count = book.ensurePaginated(0)
            assertTrue(count > 1, "여러 페이지로 나뉘어야 이 테스트가 뜻을 가진다")

            val cache = store.chapter(doc.meta.id, spec, 0)
            assertTrue(cache.readIndex()!!.complete)

            // 두 번째 호출은 캐시를 그대로 쓴다. 파일 수정 시각으로 확인한다.
            val stamp = root.walkTopDown().filter { it.isFile }.map { it.lastModified() }.toList()
            assertEquals(count, book.ensurePaginated(0))
            assertEquals(stamp, root.walkTopDown().filter { it.isFile }.map { it.lastModified() }.toList())
        }
    }

    @Test
    fun `the first pages are announced before the chapter is finished`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            var pagesWhenCalled = -1
            val total = book.ensurePaginated(2, firstPagesThreshold = 1) {
                pagesWhenCalled = store.chapter(doc.meta.id, spec, 2).readIndex()?.pageCount ?: -1
            }
            assertTrue(pagesWhenCalled >= 1, "첫 페이지 알림이 오지 않았다")
            assertTrue(pagesWhenCalled < total, "챕터가 다 끝난 뒤에 알렸다 — 화면이 그만큼 늦게 뜬다")
        }
    }

    @Test
    fun `a page comes back with its text`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            val page = book.page(1, 0)!!
            val text = book.chapterText(1)!!
            val drawn = page.runs.joinToString("") { text.substring(it.start, it.endExclusive) }
            assertTrue(drawn.contains("제2장"), "첫 페이지에 제목이 없다: $drawn")
        }
    }

    // ── 위치 ────────────────────────────────────────────────────────

    @Test
    fun `a locator survives a change of font size`() = runTest {
        openEpub().use { doc ->
            val small = layout(doc)
            val position = small.resolve(Locator.Reflow(spine = 2, charOffset = 0))
            val later = small.next(position)!!
            val saved = small.locatorAt(later.spineIndex, later.pageIndex)

            val text = small.chapterText(2)!!
            val firstCharAtSave = text[saved.charOffset]

            // 글자를 키우면 페이지 수가 달라진다. 그래도 같은 글자로 돌아와야 한다.
            val bigger = spec.copy(baseSizePx = 14f)
            val large = layout(doc, bigger)
            val restored = large.resolve(saved)
            val page = large.page(restored.spineIndex, restored.pageIndex)!!

            assertNotEquals(position.pageCount, restored.pageCount)
            assertTrue(
                page.startChar <= saved.charOffset && saved.charOffset < page.endCharExclusive,
                "저장한 글자(${saved.charOffset}, '$firstCharAtSave')가 복원된 페이지 " +
                    "${page.startChar}..${page.endCharExclusive} 밖에 있다",
            )
        }
    }

    @Test
    fun `paging forward crosses chapter boundaries and stops at the end`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            var position = book.resolve(Locator.Reflow(0, 0))
            val visited = ArrayList<Int>()
            var guard = 0

            while (guard++ < 500) {
                visited.add(position.spineIndex)
                position = book.next(position) ?: break
            }
            assertTrue(guard < 500, "끝나지 않았다 — 마지막 페이지에서 멈추지 않는다")
            assertEquals(listOf(0, 1, 2), visited.distinct(), "챕터를 건너뛰거나 되돌아갔다")
            assertTrue(position.spineIndex == 2 && position.isLastPageOfChapter)
        }
    }

    @Test
    fun `paging backward returns to the last page of the previous chapter`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            val startOfSecond = book.resolve(Locator.Reflow(1, 0))
            assertTrue(startOfSecond.isFirstPageOfChapter)

            val back = book.previous(startOfSecond)!!
            assertEquals(0, back.spineIndex)
            assertTrue(back.isLastPageOfChapter, "앞 챕터의 마지막 페이지가 아니다")

            // 책의 맨 앞에서는 더 갈 곳이 없다.
            assertNull(book.previous(book.resolve(Locator.Reflow(0, 0))))
        }
    }

    @Test
    fun `an out-of-range locator lands somewhere real`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            val past = book.resolve(Locator.Reflow(spine = 99, charOffset = 999_999))
            assertEquals(2, past.spineIndex)
            assertTrue(past.pageIndex in 0 until past.pageCount)
            assertNotNull(book.page(past.spineIndex, past.pageIndex))
        }
    }

    // ── 목차 앵커 ───────────────────────────────────────────────────

    @Test
    fun `a table of contents anchor lands on the right page`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            val entry = doc.outline().first { it.anchor == "mid" }
            val spineIndex = (entry.locator as Locator.Reflow).spine

            val locator = book.locatorForAnchor(spineIndex, entry.anchor)
            assertTrue(locator.charOffset > 0, "앵커를 무시하고 챕터 처음으로 보냈다")

            val text = book.chapterText(spineIndex)!!
            assertTrue(text.startsWith("가운데", locator.charOffset), "앵커가 엉뚱한 글자를 가리킨다")

            val position = book.resolve(locator)
            val page = book.page(position.spineIndex, position.pageIndex)!!
            assertTrue(locator.charOffset in page.startChar until page.endCharExclusive)
        }
    }

    @Test
    fun `no anchor means the start of the chapter`() = runTest {
        openEpub().use { doc ->
            assertEquals(Locator.Reflow(1, 0), layout(doc).locatorForAnchor(1, null))
            // 없는 앵커도 챕터 처음으로 보낸다 — 눌러도 아무 일이 없는 게 최악이다.
            assertEquals(Locator.Reflow(1, 0), layout(doc).locatorForAnchor(1, "없는앵커"))
        }
    }

    // ── 진도 ────────────────────────────────────────────────────────

    @Test
    fun `progress rises monotonically from front to back`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            var position = book.resolve(Locator.Reflow(0, 0))
            var previous = -1f
            var guard = 0

            while (guard++ < 500) {
                val percent = book.percent(book.locatorAt(position.spineIndex, position.pageIndex))
                assertTrue(percent >= previous, "진도가 뒤로 갔다: $previous → $percent")
                assertTrue(percent in 0f..100f)
                previous = percent
                position = book.next(position) ?: break
            }
            assertTrue(previous > 50f, "마지막 페이지의 진도가 $previous% 다")
        }
    }

    @Test
    fun `progress at the very start is zero`() = runTest {
        openEpub().use { doc ->
            assertEquals(0f, layout(doc).percent(Locator.Reflow(0, 0)))
        }
    }

    @Test
    fun `seeking to a percentage lands where the progress bar says`() = runTest {
        // 진행 막대로 옮긴 자리의 진도가 막대 위치와 같아야 한다. 어긋나면 막대를 놓는 순간
        // 손잡이가 다른 자리로 튄다.
        openEpub().use { doc ->
            val book = layout(doc)
            for (target in listOf(10f, 33f, 50f, 72f, 95f)) {
                val landed = book.percent(book.locatorAtPercent(target))
                assertTrue(kotlin.math.abs(landed - target) < 2f, "$target% 로 옮겼는데 $landed% 다")
            }
        }
    }

    @Test
    fun `seeking to the ends and beyond stays inside the book`() = runTest {
        openEpub().use { doc ->
            val book = layout(doc)
            val chapters = book.spine().size
            assertEquals(Locator.Reflow(0, 0), book.locatorAtPercent(0f))
            assertEquals(Locator.Reflow(0, 0), book.locatorAtPercent(-40f))
            // 100% 와 그 너머는 마지막 챕터 안의 실제 글자여야 한다(글자 수를 넘으면 빈 페이지).
            for (p in listOf(100f, 250f, Float.MAX_VALUE)) {
                val end = book.locatorAtPercent(p)
                assertEquals(chapters - 1, end.spine)
                val length = book.chapterText(end.spine)!!.length
                assertTrue(end.charOffset in 0 until length, "끝 위치 ${end.charOffset} / $length")
                // 끝까지 민 막대는 마지막 페이지로 가야 한다(처음으로 떨어지면 안 된다).
                val page = book.resolve(end)
                assertEquals(page.pageCount - 1, page.pageIndex, "$p% 가 마지막 페이지가 아니다")
            }
        }
    }

    // ── TXT ─────────────────────────────────────────────────────────

    @Test
    fun `a txt book works the same way`() = runTest {
        val content = (1..60).joinToString("\n") { "$it 번째 줄, 어린 왕자는 장미를 두고 왔다." }
        val doc = TxtDocument.open(BookId("txt-1"), "책.txt", ByteSource.of(content.toByteArray()))
        val book = layout(doc)

        val count = book.ensurePaginated(0)
        assertTrue(count > 1)

        var last = book.resolve(Locator.Reflow(0, 0))
        while (true) last = book.next(last) ?: break
        assertEquals(0, last.spineIndex)
        assertTrue(last.isLastPageOfChapter)
        // 한 챕터짜리 책은 마지막 페이지에서 100% 에 가까워야 한다.
        assertTrue(book.percent(book.locatorAt(0, last.pageIndex)) > 80f)
    }

    // ── 캐시 정리 ───────────────────────────────────────────────────

    @Test
    fun `pruning drops the caches of other layouts`() = runTest {
        openEpub().use { doc ->
            val bigger = spec.copy(baseSizePx = 14f)
            layout(doc).ensurePaginated(0)
            layout(doc, bigger).ensurePaginated(0)

            layout(doc, bigger).pruneStaleCaches()
            assertTrue(store.chapter(doc.meta.id, bigger, 0).exists)
            assertFalse(store.chapter(doc.meta.id, spec, 0).exists)
        }
    }
}
