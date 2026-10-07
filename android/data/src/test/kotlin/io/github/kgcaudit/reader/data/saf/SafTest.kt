package io.github.kgcaudit.reader.data.saf

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReadingProgress
import io.github.kgcaudit.reader.data.db.ReaderDatabase
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
        TestDocumentsProvider.onList = null
        TestDocumentsProvider.onOpen = null
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
    fun `a folder removed while it is being scanned does not come back as ghost books`() = runTest {
        // 훑기가 도는 사이 사용자가 책 폴더에서 그 폴더를 뺐다. 훑은 결과를 그대로 넣으면 뺀 폴더의 책이 목록에 되살아나고,
        // 폴더 목록에는 없어 다시 뺄 수도 없다.
        put("소설/책.epub", epub("책", "본문"))
        data.folders.register(tree)
        TestDocumentsProvider.onList = {
            TestDocumentsProvider.onList = null
            kotlinx.coroutines.runBlocking { data.removeFolder(tree) }
        }
        data.rescan(tree)
        assertTrue(data.library.books().first().isEmpty(), "뺀 폴더의 책이 목록에 남았다")
        // 같은 폴더를 다시 등록하면 다시 훑어 보인다.
        data.folders.register(tree)
        data.rescan(tree)
        assertEquals(listOf("책.epub"), data.library.books().first().map { it.displayName })
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
    fun `a book moved into another folder keeps reading where it was after the next scan`() = runTest {
        // 0.50.0: 열쇠가 파일 주소라 옮기면 처음부터였다. 훑기가 사라진 책과 새로 보인 책을 이름 + 크기로 이어 준다.
        val bytes = epub("어린 왕자", "사막에서 조종사를 만났다.")
        val old = put("소설/어린 왕자.epub", bytes)
        data.rescan(tree)
        val before = data.library.books().first().single()
        data.progress.save(ReadingProgress(before.id, Locator.Reflow(2, 30), 40f, 900))
        val moves = ArrayList<io.github.kgcaudit.reader.data.backup.MovedRecords>()
        data.onMoved = { moves += it }

        old.delete()
        put("다 읽을 책/어린 왕자.epub", bytes)
        data.rescan(tree)

        val after = data.library.books().first().single()
        assertTrue(after.id != before.id, "같은 주소면 옮긴 시험이 아니다")
        assertEquals(Locator.Reflow(2, 30), data.progress.get(after.id)?.locator)
        assertEquals(listOf(before.id.value to after.id.value), moves.single().books)
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
    fun `a pdf from a pipe is read from a copy that leaves no file behind`() = runTest {
        // PdfRenderer 는 되감을 수 있는 디스크립터만 받는다. 사본 디스크립터는 처음부터 끝까지 같은
        // 바이트를 읽어야 하고, 캐시에 파일을 남기지 않아야 한다(닫을 쪽은 사본을 모른다).
        val bytes = ByteArray(10_000) { (it * 31).toByte() }
        put("문서.pdf", bytes)
        data.rescan(tree)
        val book = data.library.books().first().single()

        val spool = File(context.cacheDir, "spool")
        data.sources.spooledDescriptor(Uri.parse(book.id.value)).use { pfd ->
            assertEquals(0, spool.listFiles().orEmpty().size, "열자마자 사본 파일은 지워져야 한다")
            assertEquals(bytes.size.toLong(), pfd.statSize)
            val read = java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            assertTrue(read.contentEquals(bytes))
        }
        // 파이프가 아닌 제공자는 복사 없이 그 자리의 디스크립터를 준다.
        data.sources.seekableDescriptor(Uri.parse(book.id.value)).use { assertEquals(bytes.size.toLong(), it.statSize) }
        assertEquals(0, spool.listFiles().orEmpty().size)
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

    // ── 만화(0.33.0) ───────────────────────────────────────────────

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun `cbr, cb7 and cbt are probed, covered and opened page by page through the provider`() = runTest {
        // 0.37.0: RAR · 7z 는 C++ 해제기, tar 는 순수 Kotlin. 이름이 cbr 인 zip 도 머리로 가려 연다.
        val png = javaClass.getResource("/comic.cb7")!!.readBytes() // 7z 견본: 빨강 · 초록 · 파랑 쪽 + ComicInfo
        val pages = listOf("001.png" to "p1".toByteArray(), "002.png" to "p2".toByteArray(), "003.png" to "p3".toByteArray())
        put("C/별 01권.cbr", io.github.kgcaudit.reader.document.archive.StoredArchives.rar4(pages))
        put("C/별 02권.cbt", io.github.kgcaudit.reader.document.archive.StoredArchives.tar(pages))
        put("C/scan.cb7", png)
        put("C/별 03권.cbr", zip("001.png" to "z1".toByteArray(), "002.png" to "z2".toByteArray()))
        put("C/깨진 01권.cb7", png.copyOf(png.size / 2))
        data.folders.register(tree)
        data.rescanAll()
        assertEquals(5, data.probeComics())
        val works = data.comics.works().first()
        val star = works.single { it.title == "별" }
        assertEquals(listOf("1권", "2권", "3권"), star.entries.map { it.label })
        // 7z 안의 ComicInfo: 작품 이름 · 권 · 오→왼.
        val seven = works.single { it.title == "칠지" }
        assertEquals("2권", seven.entries.single().label)
        assertEquals(true, seven.rightToLeft)
        assertTrue(works.any { it.title == "깨진" }, "열리지 않는 cb7 이 서재에서 사라졌다")
        // 표지와 쪽.
        // 표지: RAR · tar 는 p1, 이름만 cbr 인 zip(3권)은 z1.
        assertEquals(listOf("p1", "p1", "z1"), star.entries.map { data.comicCover(it.unit)?.decodeToString() })
        val rar = star.entries.first().unit
        data.openComic(rar).use { book ->
            assertEquals(3, book.count)
            assertEquals("p3", book.read(2)!!.decodeToString())
            assertEquals("p1", book.read(0)!!.decodeToString())
        }
        // 다시 열면 풀어 둔 것을 쓴다(빨라야 한다 — 다시 풀지 않는다).
        data.openComic(rar).use { assertEquals("p2", it.read(1)!!.decodeToString()) }
        data.openComic(seven.entries.single().unit).use { assertEquals(3, it.count); assertEquals(0x89.toByte(), it.read(0)!![0]) }
        data.openComic(star.entries[1].unit).use { assertEquals("p2", it.read(1)!!.decodeToString()) }
        data.openComic(star.entries[2].unit).use { assertEquals("z2", it.read(1)!!.decodeToString()) }
    }

    @Test
    fun `a rar from a provider without random access is downloaded once, not twice`() = runTest {
        // 클라우드 제공자는 되감을 수 없는 파이프를 줘서 캐시로 한 번 받아 연다. RAR · 7z 는 머리를 본 뒤 그 사본을 버리고 해제기용
        // 경로를 새로 청해 같은 파일을 한 번 더 통째로 받았다(1GB 만화면 2GB). Robolectric 은 파이프를 흉내 내지 못해
        // 받아 둔 사본으로 여는 길을 직접 부른다.
        val pages = listOf("001.png" to "p1".toByteArray(), "002.png" to "p2".toByteArray())
        put("C/별 01권.cbr", io.github.kgcaudit.reader.document.archive.StoredArchives.rar4(pages))
        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "root/C/별 01권.cbr")
        val spool = File(context.cacheDir, "spool-once").apply { deleteRecursively() }
        val sources = UriSources(resolver, spool)
        TestDocumentsProvider.opens = 0
        io.github.kgcaudit.reader.data.ComicArchive.open(sources, uri, sources.spooledSource(uri), File(context.cacheDir, "scratch-once")).use { archive ->
            assertEquals(pages.map { it.first }, archive.names)
            assertEquals("p2", archive.entry("002.png")!!.readBytes().decodeToString())
        }
        assertEquals(1, TestDocumentsProvider.opens, "같은 압축을 두 번 받았다")
        assertTrue(spool.listFiles().orEmpty().isEmpty(), "받아 둔 사본이 남았다")
    }

    @Test
    fun `a picture folder opens with the same pages the shelf counted, without mac shadow files`() = runTest {
        // 뷰어만 그림 확장자로 쪽을 고르던 때는 맥이 남긴 `._001.jpg` · 숨김 그림이 쪽으로 끼어 깨진 쪽이 보였다.
        val jpg = ByteArray(16) { 1 }
        listOf("001.jpg", "002.jpg", "003.jpg", "._001.jpg", ".hidden.jpg", "Thumbs.db").forEach { put("Webtoon/1화/$it", jpg) }
        put("Webtoon/1화/부록/004.jpg", jpg)
        data.folders.register(tree)
        data.rescanAll()
        val unit = data.comics.units().first().single()
        assertEquals(3, unit.pageCount)
        data.openComic(unit).use { assertEquals(listOf("001.jpg", "002.jpg", "003.jpg"), it.names) }
        assertEquals(jpg.size, data.comicCover(unit)?.size)
    }

    @Test
    fun `a folder removed while its archives are being probed does not bring their volumes back`() = runTest {
        // 살피는 사이 사용자가 폴더를 뺐다. 살핀 결과를 그대로 적으면 뺀 폴더의 압축 속 권이 서재에 되살아나고(권은 missing = false
        // 로 적힌다), 폴더 목록에는 없어 다시 뺄 수도 없다.
        val jpg = ByteArray(16) { 1 }
        put("C/별 (1-2).zip", zip("별 1권.cbz" to zip("1.jpg" to jpg), "별 2권.cbz" to zip("1.jpg" to jpg)))
        data.folders.register(tree)
        data.rescanAll()
        TestDocumentsProvider.onOpen = {
            TestDocumentsProvider.onOpen = null
            kotlinx.coroutines.runBlocking { data.removeFolder(tree) }
        }
        data.probeComics()
        assertTrue(data.comics.units().first().isEmpty(), "뺀 폴더의 권이 서재에 남았다")
        // 같은 폴더를 다시 등록하면 다시 훑고 살펴 보인다.
        data.folders.register(tree)
        data.rescanAll()
        data.probeComics()
        assertEquals(setOf("별 1권.cbz", "별 2권.cbz"), data.comics.units().first().map { it.name }.toSet())
    }

    @Test
    fun `a rar replaced by another of the same name and size opens with its new pages`() = runTest {
        // 풀어 둔 권을 파일 · 크기로만 알아보던 때는 같은 이름 · 같은 크기로 바꿔 넣은 권(다시 받은 스캔본)이 옛 쪽을 보였다.
        val first = listOf("001.png" to "p1".toByteArray(), "002.png" to "p2".toByteArray())
        val second = listOf("001.png" to "q1".toByteArray(), "002.png" to "q2".toByteArray())
        val file = put("C/별 01권.cbr", io.github.kgcaudit.reader.document.archive.StoredArchives.rar4(first))
        data.folders.register(tree)
        data.rescanAll()
        data.probeComics()
        val unit = data.comics.units().first().single()
        data.openComic(unit).use { assertEquals("p1", it.read(0)!!.decodeToString()) }
        file.writeBytes(io.github.kgcaudit.reader.document.archive.StoredArchives.rar4(second))
        file.setLastModified(file.lastModified() + 60_000)
        // 서재를 아직 다시 훑지 않았어도(서재의 크기 · 시각은 옛 값) 새 쪽이 보여야 한다.
        data.openComic(unit).use { assertEquals("q1", it.read(0)!!.decodeToString()) }
    }

    @Test
    fun `rar and 7z volumes bundled in a zip show up as volumes and open`() = runTest {
        // 0.49.0 은 그냥 .rar · .7z 를 만화로 보면서 압축 속 권 목록에는 빠뜨려, 권 rar · 7z 를 묶은 zip 이 통째로 "만화 아님" 이 됐다.
        val pages = listOf("001.png" to "p1".toByteArray(), "002.png" to "p2".toByteArray())
        val cb7 = javaClass.getResource("/comic.cb7")!!.readBytes()
        put("C/별 (1-2).zip", zip("별 (1-2)/별 1권.rar" to io.github.kgcaudit.reader.document.archive.StoredArchives.rar4(pages), "별 (1-2)/별 2권.7z" to cb7))
        data.folders.register(tree)
        data.rescanAll()
        data.probeComics()
        val units = data.comics.units().first().associateBy { it.name }
        assertEquals(setOf("별 1권.rar", "별 2권.7z"), units.keys)
        data.openComic(units.getValue("별 1권.rar")).use { assertEquals("p2", it.read(1)!!.decodeToString()) }
        data.openComic(units.getValue("별 2권.7z")).use { assertEquals(3, it.count) }
    }

    @Test
    fun `comics are found, probed through the provider and grouped into works`() = runTest {
        val jpg = ByteArray(16) { 1 }
        put("Comics/별/별 01권.cbz", zip("001.jpg" to jpg, "002.jpg" to jpg))
        put("Comics/별/별 4-6권 합본.cbz", zip("합본/4권/1.jpg" to jpg, "합본/5권/1.jpg" to jpg, "합본/6권/1.jpg" to jpg))
        put("Download/scan.cbz", zip("1.jpg" to jpg, "ComicInfo.xml" to "<ComicInfo><Series>별</Series><Number>2</Number></ComicInfo>".toByteArray()))
        put("Download/사진.zip", zip("a.jpg" to jpg, "b.jpg" to jpg))
        put("Download/소설.zip", zip("소설.txt" to "글".toByteArray(), "표지.jpg" to jpg))
        put("Download/깨진 01권.cbz", "이것은 압축이 아니다".toByteArray())
        put("Webtoon/전학생/001화/1.jpg", jpg); put("Webtoon/전학생/001화/2.jpg", jpg); put("Webtoon/전학생/001화/3.jpg", jpg)
        put("Books/책.epub", epub("책", "본문"))

        data.folders.register(tree)
        data.rescanAll()
        // cbz 넷 + zip 둘. 다시 부르면 이미 살핀 것은 건너뛴다.
        assertEquals(6, data.probeComics())
        assertEquals(0, data.probeComics())
        val works = data.comics.works().first()
        val star = works.single { it.title == "별" }
        assertEquals(listOf("1권", "2권", "4–6권"), star.entries.map { it.label })
        assertEquals(listOf("4권", "5권", "6권"), star.entries.single { it.label == "4–6권" }.sections)
        assertTrue(works.any { it.title == "사진" }, "그림만 든 zip 이 만화로 보이지 않는다")
        assertTrue(works.none { it.title == "소설" }, "소설 zip 이 만화로 보였다")
        assertTrue(works.any { it.title == "깨진" }, "열리지 않는 cbz 가 서재에서 사라졌다")
        assertTrue(works.single { it.title == "전학생" }.webtoon)
        // 책은 책대로.
        assertEquals(listOf("책.epub"), data.library.books().first().map { it.displayName })
    }

    @Test
    fun `removing a folder takes its comics off the shelf too`() = runTest {
        put("별 01권.cbz", zip("1.jpg" to ByteArray(4)))
        data.folders.register(tree)
        data.rescanAll()
        assertEquals(listOf("별"), data.comics.works().first().map { it.title })
        data.removeFolder(tree)
        assertTrue(data.comics.works().first().isEmpty(), "뺀 폴더의 만화가 서재에 남았다")
    }
}
