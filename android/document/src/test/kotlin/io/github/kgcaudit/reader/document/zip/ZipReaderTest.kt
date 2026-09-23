package io.github.kgcaudit.reader.document.zip

import io.github.kgcaudit.reader.document.SeekableSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry as JdkZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 우리 리더를 **JDK 가 만든 실제 zip** 으로 검증한다.
 *
 * 직접 만든 픽스처로만 테스트하면 우리가 잘못 이해한 구조를 그대로 재현해 통과시켜
 * 버린다. 표준 도구의 출력을 읽어야 이해 자체가 맞는지 확인된다.
 */
class ZipReaderTest {

    /** [entries] 를 담은 zip 바이트를 만든다. [stored] 에 든 이름은 무압축으로 저장한다. */
    private fun zipOf(
        entries: List<Pair<String, ByteArray>>,
        stored: Set<String> = emptySet(),
        comment: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            comment?.let(zip::setComment)
            entries.forEach { (name, data) ->
                val entry = JdkZipEntry(name)
                if (name in stored) {
                    entry.method = JdkZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun reader(bytes: ByteArray) = ZipReader.open(SeekableSource.of(bytes))

    private fun text(name: String, body: String) = name to body.toByteArray()

    // ── 기본 읽기 ───────────────────────────────────────────────────

    @Test
    fun `entries are listed in central directory order`() {
        // EPUB 은 mimetype 이 첫 엔트리여야 한다. 순서를 잃으면 그 검사를 못 한다.
        val bytes = zipOf(
            listOf(
                text("mimetype", "application/epub+zip"),
                text("META-INF/container.xml", "<container/>"),
                text("OEBPS/content.opf", "<package/>"),
            ),
            stored = setOf("mimetype"),
        )
        reader(bytes).use { zip ->
            assertEquals(
                listOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf"),
                zip.entries.keys.toList(),
            )
        }
    }

    @Test
    fun `deflated content round trips`() {
        // 압축이 걸리도록 반복이 많은 본문을 쓴다.
        val body = "한글 본문이 반복된다. ".repeat(500)
        reader(zipOf(listOf(text("ch1.xhtml", body)))).use { zip ->
            val entry = zip.entries.getValue("ch1.xhtml")
            assertEquals(ZipEntry.METHOD_DEFLATE, entry.method)
            assertTrue(entry.compressedSize < entry.size, "본문이 압축되지 않았다")
            assertEquals(body, String(zip.readBytes("ch1.xhtml")!!, Charsets.UTF_8))
        }
    }

    @Test
    fun `stored content round trips`() {
        val body = "application/epub+zip"
        reader(zipOf(listOf(text("mimetype", body)), stored = setOf("mimetype"))).use { zip ->
            assertEquals(ZipEntry.METHOD_STORED, zip.entries.getValue("mimetype").method)
            assertEquals(body, String(zip.readBytes("mimetype")!!, Charsets.UTF_8))
        }
    }

    @Test
    fun `an entry does not bleed into the next one`() {
        // 구간 스트림이 경계를 넘으면 뒤 엔트리 데이터가 섞여 들어온다. 무압축
        // 엔트리를 붙여 두면 그 실수가 바로 드러난다.
        val bytes = zipOf(
            listOf(text("a.txt", "AAAA"), text("b.txt", "BBBB"), text("c.txt", "CCCC")),
            stored = setOf("a.txt", "b.txt", "c.txt"),
        )
        reader(bytes).use { zip ->
            assertEquals("AAAA", String(zip.readBytes("a.txt")!!))
            assertEquals("BBBB", String(zip.readBytes("b.txt")!!))
            assertEquals("CCCC", String(zip.readBytes("c.txt")!!))
        }
    }

    @Test
    fun `entries can be read in any order and more than once`() {
        // 리더는 챕터를 앞뒤로 오가며 여러 번 읽는다.
        val bytes = zipOf((1..10).map { text("ch$it.xhtml", "제${it}장") })
        reader(bytes).use { zip ->
            listOf(7, 1, 10, 7, 3).forEach { n ->
                assertEquals("제${n}장", String(zip.readBytes("ch$n.xhtml")!!, Charsets.UTF_8))
            }
        }
    }

    @Test
    fun `a missing entry reads as null rather than throwing`() {
        reader(zipOf(listOf(text("a.txt", "A")))).use { zip ->
            assertNull(zip.readBytes("nope.txt"))
            assertNull(zip.openStream("nope.txt"))
            assertFalse("nope.txt" in zip)
            assertTrue("a.txt" in zip)
        }
    }

    // ── 이름 · 구조 ─────────────────────────────────────────────────

    @Test
    fun `utf8 entry names including korean are read correctly`() {
        // 이름을 잘못 읽으면 엔트리를 아예 못 찾는다.
        val name = "OEBPS/텍스트/제1장 서장.xhtml"
        reader(zipOf(listOf(text(name, "본문")))).use { zip ->
            assertTrue(name in zip)
            assertEquals("본문", String(zip.readBytes(name)!!, Charsets.UTF_8))
        }
    }

    @Test
    fun `directory entries are recognised`() {
        val bytes = zipOf(listOf("OEBPS/" to ByteArray(0), text("OEBPS/a.txt", "A")))
        reader(bytes).use { zip ->
            assertTrue(zip.entries.getValue("OEBPS/").isDirectory)
            assertFalse(zip.entries.getValue("OEBPS/a.txt").isDirectory)
        }
    }

    @Test
    fun `an empty entry reads as empty bytes`() {
        reader(zipOf(listOf("empty.txt" to ByteArray(0)))).use { zip ->
            assertEquals(0, zip.readBytes("empty.txt")!!.size)
        }
    }

    @Test
    fun `an archive comment does not hide the end of central directory`() {
        // EOCD 는 파일 끝에 있고 그 뒤에 최대 64KB 주석이 붙을 수 있다. 고정 위치를
        // 읽으면 주석이 있는 아카이브를 못 연다.
        val bytes = zipOf(listOf(text("a.txt", "A")), comment = "주석".repeat(100))
        reader(bytes).use { zip -> assertEquals("A", String(zip.readBytes("a.txt")!!)) }
    }

    @Test
    fun `an eocd signature inside the comment does not fool the search`() {
        // 가장 뒤의 후보를 써야 한다. 주석 안에 서명과 같은 바이트가 들어갈 수 있다.
        val fakeSignature = String(byteArrayOf(0x50, 0x4B, 0x05, 0x06), Charsets.ISO_8859_1)
        val bytes = zipOf(listOf(text("a.txt", "A")), comment = fakeSignature + "x".repeat(40))
        reader(bytes).use { zip -> assertEquals("A", String(zip.readBytes("a.txt")!!)) }
    }

    @Test
    fun `a large archive with many entries is read`() {
        // 엔트리가 많은 책(이미지가 잔뜩인 만화·잡지)에서 중앙 디렉터리 순회가
        // 어긋나지 않는지 본다.
        val count = 500
        val bytes = zipOf((0 until count).map { text("f%04d.txt".format(it), "body-$it") })
        reader(bytes).use { zip ->
            assertEquals(count, zip.entries.size)
            assertEquals("body-0", String(zip.readBytes("f0000.txt")!!))
            assertEquals("body-499", String(zip.readBytes("f0499.txt")!!))
        }
    }

    @Test
    fun `a chapter larger than the inflate buffer is read completely`() {
        // 16KB 버퍼를 여러 번 채우는 크기. 마지막 조각을 놓치면 본문 끝이 잘린다.
        val body = (0 until 40_000).joinToString("") { "가" }
        reader(zipOf(listOf(text("big.xhtml", body)))).use { zip ->
            assertEquals(body, String(zip.readBytes("big.xhtml")!!, Charsets.UTF_8))
        }
    }

    // ── 오류 처리 ───────────────────────────────────────────────────

    @Test
    fun `a non zip input fails with a clear message`() {
        val error = assertFailsWith<IOException> {
            ZipReader.open(SeekableSource.of("이건 zip 이 아니다".toByteArray()))
        }
        assertTrue(
            error.message!!.contains("zip", ignoreCase = true),
            "메시지가 원인을 설명하지 않는다: ${error.message}",
        )
    }

    @Test
    fun `a truncated input fails instead of returning garbage`() {
        val full = zipOf(listOf(text("a.txt", "A"), text("b.txt", "B")))
        assertFailsWith<IOException> { ZipReader.open(SeekableSource.of(full.copyOf(full.size / 2))) }
        assertFailsWith<IOException> { ZipReader.open(SeekableSource.of(ByteArray(0))) }
    }

    @Test
    fun `the source is closed when opening fails`() {
        // 실패한 뒤 호출부가 닫을 수 없는 원천이 남으면 파일 서술자가 샌다.
        var closed = false
        val source = object : SeekableSource {
            override val size: Long = 4
            override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int) = -1
            override fun close() { closed = true }
        }
        assertFailsWith<IOException> { ZipReader.open(source) }
        assertTrue(closed, "열기 실패 후 원천이 닫히지 않았다")
    }

    @Test
    fun `a realistic epub layout is navigable`() {
        val bytes = zipOf(
            listOf(
                text("mimetype", "application/epub+zip"),
                text("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""),
                text("OEBPS/content.opf", "<package/>"),
                text("OEBPS/toc.ncx", "<ncx/>"),
                text("OEBPS/text/ch1.xhtml", "<p>제1장</p>"),
                text("OEBPS/text/ch2.xhtml", "<p>제2장</p>"),
                "OEBPS/images/cover.jpg" to ByteArray(256) { it.toByte() },
            ),
            stored = setOf("mimetype"),
        )
        reader(bytes).use { zip ->
            assertEquals("application/epub+zip", String(zip.readBytes("mimetype")!!))
            assertEquals("<p>제2장</p>", String(zip.readBytes("OEBPS/text/ch2.xhtml")!!, Charsets.UTF_8))
            assertEquals(256, zip.readBytes("OEBPS/images/cover.jpg")!!.size)
        }
    }
}
