package io.github.kgcaudit.reader.data.saf

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.library.LibraryScanner
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.TxtDocument
import io.github.kgcaudit.reader.document.epub.EpubDocument
import io.github.kgcaudit.reader.document.readFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 진짜 `DocumentsProvider` 를 통해 폴더 등록 → 스캔 → 책 열기까지.
 *
 * URI 를 손으로 조립하는 곳(트리 → 하위 목록 → 문서)이 틀리면 기기에서는 "폴더는
 * 등록됐는데 책이 하나도 안 보인다" 로만 나타난다. 여기서는 플랫폼의 URI 규칙과 트리
 * 검사를 그대로 거친다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var db: ReaderDatabase
    private lateinit var data: ReaderData

    @Before
    fun setUp() {
        base = File(context.cacheDir, "saf-test").apply { deleteRecursively(); mkdirs() }
        root = TestDocumentsProvider.install(base)
        db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        data = ReaderData(context, db, io = Dispatchers.Unconfined, clock = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
        base.deleteRecursively()
    }

    private fun put(path: String, bytes: ByteArray) =
        File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private val tree: Uri get() = TestDocumentsProvider.treeUri

    // ── 폴더 → 라이브러리 ───────────────────────────────────────────

    @Test
    fun `a registered folder shows its books in the library`() = runTest {
        put("어린 왕자.epub", epub("어린 왕자", "사막에서 조종사를 만났다."))
        put("소설/메모.txt", "첫 줄".toByteArray())
        put("소설/깊이/문서.pdf", ByteArray(10))
        put("소설/사진.jpg", ByteArray(10))

        data.folders.register(tree)
        assertEquals(listOf(tree), data.folders.folders())

        val results = data.rescanAll()
        assertTrue(results.getValue(tree).complete)
        assertEquals(
            listOf("메모.txt", "문서.pdf", "어린 왕자.epub"),
            data.library.books().first().map { it.displayName }.sorted(),
        )
    }

    @Test
    fun `every scanned book can actually be opened through its id`() = runTest {
        // 스캔이 만든 URI 가 트리 밖 문서 URI 면 목록에는 보이는데 누르면 권한 오류가 난다.
        put("소설/책.epub", epub("책", "본문"))
        data.rescan(tree)

        val book = data.library.books().first().single()
        val bytes = resolver.openInputStream(Uri.parse(book.id.value))!!.use { it.readBytes() }
        assertEquals(File(root, "소설/책.epub").length(), bytes.size.toLong())
        assertEquals(File(root, "소설/책.epub").length(), book.sizeBytes)
    }

    @Test
    fun `a file deleted from the folder disappears after the next scan`() = runTest {
        put("a.epub", epub("A", "가"))
        val b = put("b.epub", epub("B", "나"))
        data.rescan(tree)
        b.delete()

        data.rescan(tree)
        assertEquals(listOf("a.epub"), data.library.books().first().map { it.displayName })
    }

    @Test
    fun `a provider that crashes on one folder does not hide anything`() = runTest {
        // 클라우드 제공자의 버그가 바인더를 건너 런타임 예외로 올라오는 경우. 스캔이 죽거나,
        // 그 폴더의 책이 "사라진" 것으로 처리되면 안 된다.
        put("a/하나.epub", epub("하나", "가"))
        put("b/둘.epub", epub("둘", "나"))
        data.rescan(tree)

        TestDocumentsProvider.failing = setOf("root/b")
        val result = data.rescan(tree)

        assertFalse(result.complete)
        assertEquals(listOf("둘.epub", "하나.epub"), data.library.books().first().map { it.displayName }.sorted())
    }

    @Test
    fun `a folder the provider does not answer for is not treated as empty`() = runTest {
        // null 커서를 빈 목록으로 읽으면 그 폴더의 책이 전부 "사라진" 것이 된다.
        put("a/하나.epub", epub("하나", "가"))
        put("b/둘.epub", epub("둘", "나"))
        data.rescan(tree)

        TestDocumentsProvider.unanswered = setOf("root/b")
        val result = data.rescan(tree)

        assertFalse(result.complete)
        assertEquals(listOf("둘.epub", "하나.epub"), data.library.books().first().map { it.displayName }.sorted())
    }

    @Test
    fun `removing a folder releases its permission and its books`() = runTest {
        put("a.epub", epub("A", "가"))
        data.folders.register(tree)
        data.rescan(tree)

        data.removeFolder(tree)
        assertTrue(data.folders.folders().isEmpty())
        assertTrue(data.library.books().first().isEmpty())
    }

    // ── 책 열기 ─────────────────────────────────────────────────────

    @Test
    fun `an epub opens with random access straight from the provider`() = runTest {
        put("책.epub", epub("어린 왕자", "사막에서 조종사를 만났다."))
        data.rescan(tree)
        val book = data.library.books().first().single()

        data.sources.seekableSource(Uri.parse(book.id.value)).use { source ->
            // 파일 끝(zip 중앙 디렉터리)과 앞을 번갈아 읽어도 같은 바이트여야 한다.
            val file = File(root, "책.epub").readBytes()
            assertEquals(file.size.toLong(), source.size)
            assertContentEquals(file.copyOfRange(file.size - 22, file.size), source.readFully(file.size - 22L, 22))
            assertContentEquals(file.copyOfRange(0, 30), source.readFully(0, 30))

            val doc = EpubDocument.open(book.id, book.displayName, source)
            assertEquals("어린 왕자", doc.meta.title)
            val chapter = doc.openChapter(0).use { it.readText() }
            assertTrue("사막에서 조종사를 만났다." in chapter, chapter)
        }
    }

    @Test
    fun `an epub copied aside for a provider without random access still opens`() = runTest {
        // 일부 클라우드 제공자는 되감을 수 없는 파이프를 준다. zip 은 끝부터 읽어야 하므로
        // 그대로는 못 연다 — 캐시로 옮겨서라도 열어야 하고, 닫으면 사본이 남지 않아야
        // 한다(남으면 책을 열 때마다 캐시가 쌓인다). 파이프 판정(statSize < 0) 자체는
        // Robolectric 이 재현하지 못해 실기기 확인 항목이다(HANDOFF §3.2).
        put("책.epub", epub("파이프", "파이프로 온 책"))
        data.rescan(tree)
        val book = data.library.books().first().single()

        val spool = File(context.cacheDir, "spool")
        data.sources.spooledSource(Uri.parse(book.id.value)).use { source ->
            val doc = EpubDocument.open(book.id, book.displayName, source)
            assertEquals("파이프", doc.meta.title)
            assertEquals(1, spool.listFiles().orEmpty().size, "사본이 캐시에 있어야 한다")
        }
        assertEquals(0, spool.listFiles().orEmpty().size, "닫으면 사본을 지워야 한다")
    }

    @Test
    fun `a book that vanished before opening fails cleanly and leaves no copy behind`() = runTest {
        // 스캔과 열기 사이에 파일이 지워지는 경우. 반쯤 쓴 사본이 캐시에 남으면 안 된다.
        put("책.epub", epub("A", "가"))
        data.rescan(tree)
        val book = data.library.books().first().single()
        File(root, "책.epub").delete()

        assertFailsWith<FileNotFoundException> { data.sources.seekableSource(Uri.parse(book.id.value)) }
        assertFailsWith<FileNotFoundException> { data.sources.spooledSource(Uri.parse(book.id.value)) }
        assertEquals(0, File(context.cacheDir, "spool").listFiles().orEmpty().size)
    }

    @Test
    fun `a euc-kr txt opens through the provider`() = runTest {
        put("옛글.txt", "한글 옛 파일입니다.\n둘째 줄".toByteArray(Charset.forName("EUC-KR")))
        data.rescan(tree)
        val book = data.library.books().first().single()

        val doc = TxtDocument.open(book.id, book.displayName, data.sources.byteSource(Uri.parse(book.id.value)))
        val text = doc.openChapter(0).use { it.readText() }
        assertTrue(text.startsWith("한글 옛 파일입니다."), text)
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private fun epub(title: String, body: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mime.size.toLong()
                    compressedSize = mime.size.toLong()
                    crc = CRC32().apply { update(mime) }.value
                },
            )
            zip.write(mime)
            val entries = mapOf(
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                "OEBPS/content.opf" to """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title></metadata>
                      <manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="c1"/></spine>
                    </package>
                """.trimIndent(),
                "OEBPS/ch1.xhtml" to "<html><body><p>$body</p></body></html>",
            )
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
            }
        }
        return out.toByteArray()
    }
}
