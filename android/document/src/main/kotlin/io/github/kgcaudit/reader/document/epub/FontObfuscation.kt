package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * EPUB 글꼴 난독화. 암호가 아니다 — 글꼴 파일을 책에서 그냥 꺼내 쓰지 못하게 앞부분을
 * 책 식별자로 XOR 해 둔 것이다. 규격이 둘 있다.
 *
 * - IDPF (`http://www.idpf.org/2008/embedding`): 열쇠 = 고유 식별자(공백 제거)의 SHA-1,
 *   앞 1040 바이트.
 * - Adobe (`http://ns.adobe.com/pdf/enc#RC`): 열쇠 = `urn:uuid:` 의 UUID 16바이트, 앞 1024 바이트.
 *
 * 풀지 않으면 안드로이드가 폰트를 읽지 못해 조용히 기본 글꼴로 그린다. 출판사 글꼴이
 * "어떤 책에서만" 안 나오는 원인이 이것이다.
 */
object FontObfuscation {

    const val ENCRYPTION_PATH = "META-INF/encryption.xml"

    enum class Method(val algorithm: String, val headerBytes: Int) {
        Idpf("http://www.idpf.org/2008/embedding", 1040),
        Adobe("http://ns.adobe.com/pdf/enc#RC", 1024),
    }

    /**
     * `encryption.xml` 에서 난독화된 파일 경로 → 방식. 모르는 방식(진짜 DRM)은 넣지 않는다 —
     * 그런 파일은 풀 수 없으니 그대로 두고, 글꼴이면 기본 글꼴이 대신한다.
     */
    fun parse(xml: ByteArray): Map<String, Method> {
        val result = HashMap<String, Method>()
        var method: Method? = null
        var uri: String? = null
        for (event in XmlScanner(xml.inputStream().reader(Charsets.UTF_8)).events()) {
            when (event) {
                is XmlEvent.StartElement -> when {
                    event.isLocal("EncryptedData") -> { method = null; uri = null }
                    event.isLocal("EncryptionMethod") ->
                        method = Method.entries.firstOrNull { it.algorithm == event.attribute("Algorithm")?.trim() }
                    event.isLocal("CipherReference") -> uri = event.attribute("URI")
                }
                is XmlEvent.EndElement -> if (event.isLocal("EncryptedData")) {
                    val m = method
                    val u = uri
                    // URI 는 컨테이너 루트 기준이다(OPF 기준이 아니다).
                    if (m != null && !u.isNullOrBlank()) result[Hrefs.resolve("", u)] = m
                }
                is XmlEvent.Text -> Unit
            }
        }
        return result
    }

    /** 방식에 맞는 열쇠. 식별자가 없거나(IDPF) UUID 가 아니면(Adobe) null. */
    fun key(method: Method, identifier: String?): ByteArray? {
        val id = identifier?.takeIf { it.isNotBlank() } ?: return null
        return when (method) {
            Method.Idpf -> MessageDigest.getInstance("SHA-1")
                .digest(id.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }.toByteArray(Charsets.UTF_8))
            Method.Adobe -> {
                val hex = id.removePrefix("urn:uuid:").filter { it.isLetterOrDigit() }
                if (hex.length != 32 || hex.any { it.digitToIntOrNull(16) == null }) return null
                ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }
        }
    }

    /** 앞 [length] 바이트를 [key] 로 XOR 하며 읽는다. 같은 연산이 난독화이자 풀기다. */
    class Deobfuscating(input: InputStream, private val key: ByteArray, private val length: Int) : FilterInputStream(input) {
        private var position = 0L

        override fun read(): Int {
            val b = super.read()
            if (b < 0) return b
            val out = if (position < length) b xor (key[(position % key.size).toInt()].toInt() and 0xFF) else b
            position++
            return out
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n <= 0) return n
            var i = 0
            while (i < n && position + i < length) {
                b[off + i] = (b[off + i].toInt() xor key[((position + i) % key.size).toInt()].toInt()).toByte()
                i++
            }
            position += n
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            position += skipped
            return skipped
        }

        override fun markSupported(): Boolean = false
    }
}
