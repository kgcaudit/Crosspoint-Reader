package io.github.kgcaudit.reader.app

import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.ByteSource
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.InMemoryAnnotations
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.cache.PageStore
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.reflow.ReaderPrefs
import io.github.kgcaudit.reader.reflow.toSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 큰 TXT 를 조각으로 나눠 읽는 리더(0.60.0, iOS 요청). 첫 쪽은 첫 조각만 짜고 뜨고, 쪽 수는 뒤에서 센다. 화면이 보는 쪽 번호 ·
 * 형광펜은 조각을 넘어 한 권으로 이어진다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BigTxtReaderTest {

    private val app get() = ApplicationProvider.getApplicationContext<OloApp>()
    private val id = BookId("big-txt-reader")
    private val content = (1..80).joinToString("\n\n") { scene ->
        (1..30).joinToString("\n") { line -> "$scene 장면 $line 번째 줄, 후치는 마차 바퀴에 등을 기대고 별을 세었다." }
    }
    private var reader: BookReader? = null

    @After
    fun tearDown() {
        reader?.close()
    }

    private object NoBookmarks : BookmarkRepository {
        override suspend fun forBook(bookId: BookId): List<Bookmark> = emptyList()
        override suspend fun add(bookmark: Bookmark) = bookmark
        override suspend fun remove(id: Long) = Unit
    }

    private class MemoryProgress : ProgressRepository {
        private val rows = HashMap<BookId, ReadingProgress>()
        override suspend fun get(bookId: BookId) = rows[bookId]
        override suspend fun save(progress: ReadingProgress) { rows[progress.bookId] = progress }
        override suspend fun remove(bookId: BookId) { rows.remove(bookId) }
    }

    private fun open(annotations: InMemoryAnnotations = InMemoryAnnotations()): Pair<BookReader, TxtDocument> {
        val doc = TxtDocument.open(id, "드래곤라자.txt", ByteSource.of(content.toByteArray()))
        val r = BookReader(
            fonts = app.container.fonts,
            document = doc,
            store = PageStore(File(app.cacheDir, "big-txt-pages").apply { deleteRecursively() }),
            bookmarkRepository = NoBookmarks,
            progressRepository = MemoryProgress(),
            annotationRepository = annotations,
        )
        reader = r
        return r to doc
    }

    private suspend fun BookReader.layOutPhone() {
        val fontId = app.container.fonts.layoutFontId(null)
        layOut(ReaderPrefs().toSpec(1080f, 1800f, Insets.all(60f), 3f, 2.5f, fontId))
    }

    @Test
    fun `page numbers run on from one piece to the next once the pieces are counted`() = runBlocking {
        val (r, doc) = open()
        r.layOutPhone()
        // 뒤에서 세기를 마치면 "… " 가 수로 바뀐다.
        withTimeout(60_000) { while (r.state.value.pageTotal == null) delay(20) }
        val total = assertNotNull(r.state.value.pageTotal)
        assertEquals(1, r.state.value.pageNumber)
        assertEquals(1, r.state.value.chapterCount, "조각을 장으로 세면 머리에 \"3 / 20장\" 이 뜬다")

        // 첫 조각의 끝을 넘어 다음 조각 첫 쪽으로 — 쪽 번호가 1 로 돌아가지 않고 이어진다.
        var last = 1
        while (r.state.value.position!!.spineIndex == 0) {
            last = assertNotNull(r.state.value.pageNumber)
            r.next()
        }
        assertEquals(last + 1, r.state.value.pageNumber)
        assertEquals(total, r.state.value.pageTotal)
        assertEquals(doc.pieceStarts()[1], r.state.value.chapterOffset)
    }

    @Test
    fun `a highlight across a piece boundary shows on both sides and leads back to its start`() = runBlocking {
        val doc = TxtDocument.open(id, "드래곤라자.txt", ByteSource.of(content.toByteArray()))
        val boundary = doc.pieceStarts()[1]
        val notes = InMemoryAnnotations()
        val saved = notes.add(
            Annotation(Annotation.NO_ID, id, Locator.Reflow(0, boundary - 8), Locator.Reflow(0, boundary + 8), HighlightColor.Yellow, null, "칠", 1L),
        )
        val (r, _) = open(notes)
        r.layOutPhone()
        // 뒤 조각의 첫 쪽: 칠의 나머지(조각 안의 0..8)가 보여야 한다. 시작 조각만 보던 때는 비었다.
        r.goTo(Bookmark(Bookmark.NO_ID, id, Locator.Reflow(1, 0), null, 0L))
        val shown = r.state.value.annotations.single()
        assertEquals(Locator.Reflow(1, 0) to Locator.Reflow(1, 8), shown.start to shown.end)

        // 그 칠을 눌러 찾아가면 칠이 시작하는 앞 조각의 쪽으로 — 잘린 사본의 시작(뒤 조각 처음)으로 가지 않는다.
        r.goTo(shown)
        val position = r.state.value.position!!
        val page = r.state.value.page!!
        assertEquals(0, position.spineIndex)
        assertEquals(true, (saved.start.charOffset) in page.startChar until page.endCharExclusive)
        // 앞 조각의 쪽에서도 같은 칠이 보인다(경계까지).
        val front = r.state.value.annotations.single()
        assertEquals(Locator.Reflow(0, boundary - 8) to Locator.Reflow(0, boundary), front.start to front.end)
    }

    @Test
    fun `a small txt keeps plain page numbers without counting`() = runBlocking {
        val doc = TxtDocument.open(id, "짧은 글.txt", ByteSource.of("첫 줄\n둘째 줄\n".toByteArray()))
        val r = BookReader(
            fonts = app.container.fonts,
            document = doc,
            store = PageStore(File(app.cacheDir, "small-txt-pages").apply { deleteRecursively() }),
            bookmarkRepository = NoBookmarks,
            progressRepository = MemoryProgress(),
        )
        reader = r
        r.layOutPhone()
        assertEquals(1, r.state.value.pageNumber)
        assertEquals(r.state.value.position!!.pageCount, r.state.value.pageTotal)
        assertNull(r.state.value.highlight)
    }
}
