package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.SeekableSource
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 난독화된 출판사 글꼴. 풀지 못하면 안드로이드가 폰트를 읽지 못해 조용히 기본 글꼴이 된다 —
 * "이 책만 출판사 글꼴이 안 나온다" 의 원인이다.
 */
class FontObfuscationTest {

    private val font = Random(3).nextBytes(5000)
    private val uuid = "urn:uuid:12345678-9abc-def0-1234-56789abcdef0"

    private fun xor(bytes: ByteArray, key: ByteArray, length: Int) =
        bytes.copyOf().also { for (i in 0 until minOf(length, it.size)) it[i] = (it[i].toInt() xor key[i % key.size].toInt()).toByte() }

    private fun idpfKey(id: String) = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())

    private fun adobeKey() = ByteArray(16) { i -> "123456789abcdef0123456789abcdef0".substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun book(opfIdentifiers: String, encryption: String?, fontBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            put(
                "OEBPS/content.opf",
                """<package version="3.0" unique-identifier="BookId"><metadata>$opfIdentifiers<dc:title>t</dc:title></metadata>
                  <manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/>
                  <item id="s" href="Styles/s.css" media-type="text/css"/><item id="f" href="Fonts/책 글꼴.ttf" media-type="font/ttf"/></manifest>
                  <spine><itemref idref="c"/></spine></package>""".toByteArray(),
            )
            put("OEBPS/c.xhtml", "<html><body><p>x</p></body></html>".toByteArray())
            put("OEBPS/Styles/s.css", "p{}".toByteArray())
            put("OEBPS/Fonts/책 글꼴.ttf", fontBytes)
            if (encryption != null) put(FontObfuscation.ENCRYPTION_PATH, encryption.toByteArray())
        }
        return out.toByteArray()
    }

    private fun encryption(algorithm: String) = """
        <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
          <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/>
            <enc:CipherData><enc:CipherReference URI="OEBPS/Fonts/%EC%B1%85%20%EA%B8%80%EA%BC%B4.ttf"/></enc:CipherData>
          </enc:EncryptedData>
        </encryption>
    """.trimIndent()

    private suspend fun fontOf(bytes: ByteArray): ByteArray? =
        EpubDocument.open(BookId("t"), "b.epub", SeekableSource.of(bytes)).use { it.openFont("OEBPS/Fonts/책 글꼴.ttf")?.readBytes() }

    @Test
    fun `an IDPF obfuscated font comes back as the original file`() = runTest {
        // 고유 식별자는 unique-identifier 가 가리키는 것이다. 첫 identifier(ISBN)로 풀면 틀린다.
        val ids = """<dc:identifier>9788900000000</dc:identifier><dc:identifier id="BookId">$uuid</dc:identifier>"""
        val stored = xor(font, idpfKey(uuid), 1040)
        val bytes = book(ids, encryption(FontObfuscation.Method.Idpf.algorithm), stored)
        assertContentEquals(font, fontOf(bytes))
        // 다른 경로(그림 등)로 읽으면 저장된 그대로다.
        val raw = EpubDocument.open(BookId("t"), "b.epub", SeekableSource.of(bytes)).use { it.openResource("OEBPS/Fonts/책 글꼴.ttf")?.readBytes() }
        assertContentEquals(stored, raw)
    }

    @Test
    fun `an Adobe obfuscated font comes back as the original file`() = runTest {
        val stored = xor(font, adobeKey(), 1024)
        val bytes = book("""<dc:identifier id="BookId">$uuid</dc:identifier>""", encryption(FontObfuscation.Method.Adobe.algorithm), stored)
        assertContentEquals(font, fontOf(bytes))
    }

    @Test
    fun `fonts that are not listed or use real DRM are returned untouched`() = runTest {
        // 난독화 목록이 없는 책(세 권 모두 그렇다), 모르는 알고리즘(진짜 DRM — 풀 수 없다).
        assertContentEquals(font, fontOf(book("""<dc:identifier id="BookId">$uuid</dc:identifier>""", null, font)))
        val drm = book("""<dc:identifier id="BookId">$uuid</dc:identifier>""", encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc"), font)
        assertContentEquals(font, fontOf(drm))
        // 깨진 encryption.xml 도 책을 막지 않는다.
        assertContentEquals(font, fontOf(book("""<dc:identifier id="BookId">$uuid</dc:identifier>""", "<encryption><EncryptedData", font)))
    }

    @Test
    fun `keys are refused rather than guessed`() {
        assertNull(FontObfuscation.key(FontObfuscation.Method.Idpf, null))
        assertNull(FontObfuscation.key(FontObfuscation.Method.Adobe, "isbn:9788900000000"), "UUID 가 아니면 Adobe 열쇠가 없다")
        // IDPF 는 식별자의 공백을 지우고 해시한다.
        assertContentEquals(idpfKey("abc"), FontObfuscation.key(FontObfuscation.Method.Idpf, " a b\nc "))
    }

    @Test
    fun `deobfuscation is exact across read sizes`() {
        // 한 바이트씩 읽든 큰 덩이로 읽든 같아야 한다. 경계(1040)를 걸치는 읽기에서 틀리기 쉽다.
        val key = idpfKey(uuid)
        val stored = xor(font, key, 1040)
        for (chunk in listOf(1, 7, 1039, 1041, 4096)) {
            val input = FontObfuscation.Deobfuscating(stored.inputStream(), key, 1040)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(chunk)
            while (true) {
                val n = if (chunk == 1) input.read().also { if (it >= 0) out.write(it) }.let { if (it < 0) -1 else 1 } else input.read(buffer)
                if (n < 0) break
                if (chunk != 1) out.write(buffer, 0, n)
            }
            assertContentEquals(font, out.toByteArray(), "덩이 $chunk")
        }
        assertFalse(stored.contentEquals(font))
        assertTrue(stored.copyOfRange(1040, stored.size).contentEquals(font.copyOfRange(1040, font.size)))
        assertEquals(5000, font.size)
    }
}
