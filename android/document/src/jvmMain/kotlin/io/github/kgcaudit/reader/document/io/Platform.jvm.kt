package io.github.kgcaudit.reader.document.io

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

actual typealias Inflater = java.util.zip.Inflater
actual typealias DataFormatException = java.util.zip.DataFormatException

actual object NamedCharsets {
    private fun charset(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    actual fun isSupported(name: String): Boolean = charset(name) != null

    actual fun decode(bytes: ByteArray, off: Int, len: Int, name: String): String? =
        charset(name)?.let { String(bytes, off, len, it) }

    actual fun decodeStrict(bytes: ByteArray, name: String): String? {
        val cs = charset(name) ?: return null
        return runCatching {
            cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    actual fun reader(input: InputStream, name: String): Reader? =
        charset(name)?.let { java.io.InputStreamReader(input, it) }
}

actual object Crypto {
    actual fun digest(algorithm: String, data: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(data)

    actual fun aesCbcNoPadding(encrypt: Boolean, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}

actual object Unicode {
    actual fun nfc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
    actual fun nfkc(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
}

actual abstract class ControlFlowException actual constructor() : RuntimeException(null, null, false, false)

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
