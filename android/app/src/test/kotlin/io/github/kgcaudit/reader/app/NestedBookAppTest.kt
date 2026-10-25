package io.github.kgcaudit.reader.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.comic.NestedArchives
import io.github.kgcaudit.reader.document.epub.EpubDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 압축 속 책(0.61.0, 사용자 결정: 권처럼 펼침 · 열 때 그 권만 · 만화와 섞이면 둘 다). 소설을 권별 TXT 로 zip 하나에 묶은
 * 「[윤현승] 하얀늑대들.zip」 이 그림이 없어 "만화 아님" 으로 서재에서 통째로 빠졌다(iOS 실기기 보고).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-xhdpi")
class NestedBookAppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>(StandardTestDispatcher())

    @After
    fun settleTurn() = compose.waitForIdle()

    private val app = ApplicationProvider.getApplicationContext<OloApp>()
    private lateinit var root: File
    private val wolves = "[윤현승] 하얀늑대들"

    /** 권마다 첫 줄이 다른 TXT(작은 쪽지로 걸러지지 않게 4KB 넘게). */
    private fun volume(n: Int): ByteArray =
        ("$n 권 첫 줄 — 카셀은 늑대들 앞에 섰다.\n" + "눈이 내렸다. 하얀 늑대들이 언덕을 넘었다.\n".repeat(120)).toByteArray()

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(60, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFD03030.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Before
    fun setUp() {
        root = FolderProvider.install(File(app.cacheDir, "sdcard").apply { deleteRecursively(); mkdirs() })
        File(root, "소설").mkdirs()
        // 압축해 담은 소설 묶음(가장 흔한 꼴): 권 셋 + 쪽지 하나 + 맥이 남긴 찌꺼기.
        File(root, "소설/$wolves.zip").writeBytes(
            zip(
                listOf(
                    "$wolves/하얀늑대들 1.txt" to volume(1),
                    "$wolves/하얀늑대들 2.txt" to volume(2),
                    "$wolves/하얀늑대들 10.txt" to volume(10),
                    "$wolves/읽어주세요.txt" to "받은 곳: 어딘가".toByteArray(),
                    "__MACOSX/$wolves/._하얀늑대들 1.txt" to ByteArray(5000),
                ),
                stored = false,
            ),
        )
        // EPUB 하나는 압축해, 하나는 압축 없이 담았다.
        File(root, "소설/단편 묶음.zip").writeBytes(zip(listOf("어린 왕자.epub" to SampleBooks.epub()), stored = false))
        File(root, "소설/그대로 묶음.zip").writeBytes(zip(listOf("데미안.epub" to SampleBooks.epub()), stored = true))
        // 만화와 책이 섞인 cbz: 둘 다 보인다(사용자 결정).
        File(root, "소설/그림책 묶음.cbz").writeBytes(
            zip(listOf("001.png" to png(), "002.png" to png(), "003.png" to png(), "작가의 말.epub" to SampleBooks.epub()), stored = false),
        )
        scan()
        compose.activityRule.scenario.recreate()
    }

    private fun scan() = runBlocking {
        val data = app.container.data
        data.folders.register(FolderProvider.treeUri)
        data.rescanAll()
        data.probeComics()
    }

    private fun names(): List<String> = runBlocking { app.container.data.library.books().first().map { it.displayName }.sorted() }

    private fun idOf(name: String): String = runBlocking { app.container.data.library.books().first().first { it.displayName == name }.id.value }

    private fun has(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `novels packed in one zip show up as books and a short note does not`() {
        val books = names()
        for (n in listOf(1, 2, 10)) assertTrue("하얀늑대들 $n.txt" in books, "$n 권이 서재에 없다: $books")
        // 만화 압축에 흔한 쪽지 · 맥이 남긴 찌꺼기는 책이 아니다.
        assertTrue("읽어주세요.txt" !in books)
        assertTrue(books.none { it.startsWith("._") })
        assertTrue(NestedArchives.split(idOf("하얀늑대들 2.txt")) != null, "책 id 가 압축 속 자리 꼴이 아니다")
        // 서재 목록의 폴더 자리에는 압축 이름(확정 구상안 A).
        assertEquals("$wolves.zip", bookFolder(BookId(idOf("하얀늑대들 2.txt"))))
    }

    @Test
    fun `a txt inside the zip reads back its own volume and opens in the reader`() {
        val id = idOf("하얀늑대들 2.txt")
        val bytes = app.container.data.sources.byteSource(Uri.parse(id)).openStream().use { it.readBytes() }
        assertContentEquals(volume(2), bytes)
        assertEquals(volume(2).size.toLong(), app.container.data.sources.size(Uri.parse(id)))

        compose.waitUntil(30_000) { has(hasText("하얀늑대들 2", substring = true)) }
        compose.onAllNodes(hasText("하얀늑대들 2", substring = true), useUnmergedTree = true)[0].performClick()
        compose.waitUntil(30_000) { has(hasText("1 / ", substring = true)) }
        // 읽은 자리는 압축 속 책 id 로 — 같은 압축의 다른 권과 섞이지 않는다.
        compose.waitUntil(10_000) { runBlocking { app.container.data.progress.get(BookId(id)) } != null }
        assertEquals(null, runBlocking { app.container.data.progress.get(BookId(idOf("하얀늑대들 1.txt"))) })
    }

    @Test
    fun `an epub packed with compression is copied out once and a stored one opens in place`() {
        val copies = File(app.cacheDir, "nested-books")
        copies.deleteRecursively()
        val stored = idOf("데미안.epub")
        EpubDocument.open(BookId(stored), "데미안.epub", app.container.data.sources.seekableSource(Uri.parse(stored))).use { assertNotNull(it.meta.title) }
        // 압축 없이 담은 것은 그 자리에서 — 꺼낸 사본이 없다.
        assertEquals(emptyList(), copies.walkTopDown().filter { it.isFile }.toList())

        val packed = idOf("어린 왕자.epub")
        EpubDocument.open(BookId(packed), "어린 왕자.epub", app.container.data.sources.seekableSource(Uri.parse(packed))).use { assertNotNull(it.meta.title) }
        val first = copies.walkTopDown().filter { it.isFile && it.name.startsWith("book.") }.toList()
        assertEquals(1, first.size, "압축해 담은 EPUB 은 한 번 꺼내 둔다")
        val made = first.single().lastModified()
        // 다시 열면 꺼내 둔 사본을 그대로 쓴다.
        EpubDocument.open(BookId(packed), "어린 왕자.epub", app.container.data.sources.seekableSource(Uri.parse(packed))).use { }
        assertEquals(made, first.single().lastModified())
    }

    @Test
    fun `a comic archive with a book inside shows both`() {
        assertTrue("작가의 말.epub" in names())
        val works = runBlocking { app.container.data.comics.works().first().map { it.title } }
        assertTrue(works.any { it.contains("그림책 묶음") }, "만화도 그대로 보여야 한다: $works")
    }

    @Test
    fun `removing the zip hides its books until it returns and a removed volume leaves`() {
        scan()
        assertTrue("하얀늑대들 1.txt" in names(), "새로고침에서 압축 속 책이 사라졌다")
        val outer = File(root, "소설/$wolves.zip")
        val kept = File(app.cacheDir, "kept.zip").also { outer.copyTo(it, overwrite = true); it.setLastModified(outer.lastModified()) }
        outer.delete()
        scan()
        assertTrue(names().none { it.startsWith("하얀늑대들") })
        kept.copyTo(outer)
        outer.setLastModified(kept.lastModified())
        scan()
        assertTrue("하얀늑대들 1.txt" in names())
        // 압축에서 한 권을 뺐다 — 그 권만 서재에서 빠진다.
        outer.writeBytes(zip(listOf("$wolves/하얀늑대들 1.txt" to volume(1)), stored = false))
        scan()
        assertEquals(listOf("하얀늑대들 1.txt"), names().filter { it.startsWith("하얀늑대들") })
    }

    @Test
    fun `a zip an older version called not a comic is looked at again after the update`() {
        // 0.60 까지의 휴대폰: 소설 묶음은 "만화 아님" 으로 살펴져 있고 안의 책은 없다. 파일이 그대로라 다시 살필 까닭이 없었다.
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("reader.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DELETE FROM books WHERE id LIKE ?", arrayOf<Any>("%" + NestedArchives.SEPARATOR + "%"))
        }
        File(app.noBackupFilesDir, "comic-probe-generation").writeText("4")
        assertTrue(names().none { it.startsWith("하얀늑대들") })
        scan()
        assertTrue("하얀늑대들 2.txt" in names())
        assertEquals("5", File(app.noBackupFilesDir, "comic-probe-generation").readText())
    }
}
