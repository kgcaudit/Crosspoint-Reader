package io.github.kgcaudit.reader.document.pdf

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PDF 표준 보안 처리기(`/Filter /Standard`)를 **빈 사용자 암호**로 연다.
 *
 * 전자책·잡지 PDF 는 흔히 "소유자 암호" 만 걸어 인쇄·복사를 막는다. 사용자 암호가 비어 있으니 누구나
 * 열 수 있고(PdfRenderer 도 연다) 목차도 볼 수 있어야 하는데, 문자열이 암호화돼 있어 풀지 않으면
 * 제목이 깨진 글자다. 여기서 푸는 것은 **읽기**뿐이다 — 권한(P)은 그대로 두고 아무것도 다시 쓰지 않는다.
 *
 * 받는 판: R2–R4(RC4 40–128비트, AES-128 = AESV2), R5·R6(AES-256 = AESV3). 빈 암호로 열리지 않는
 * 파일(진짜 암호가 걸림)은 [open] 이 null — 목차 없이 연다(엔진도 그 파일은 암호를 물을 것이다).
 */
internal class PdfSecurity private constructor(
    private val fileKey: ByteArray,
    private val revision: Int,
    private val strings: Method,
    private val streams: Method,
) {
    enum class Method { None, Rc4, Aes128, Aes256 }

    fun decryptString(data: ByteArray, num: Int, gen: Int): ByteArray = decrypt(data, num, gen, strings)

    fun decryptStream(data: ByteArray, num: Int, gen: Int): ByteArray = decrypt(data, num, gen, streams)

    private fun decrypt(data: ByteArray, num: Int, gen: Int, method: Method): ByteArray = when (method) {
        Method.None -> data
        Method.Rc4 -> rc4(objectKey(num, gen, aes = false), data)
        Method.Aes128 -> aesCbc(objectKey(num, gen, aes = true), data)
        // AES-256 은 객체마다 열쇠를 만들지 않는다 — 파일 열쇠 그대로.
        Method.Aes256 -> aesCbc(fileKey, data)
    }

    /** 알고리즘 1: 파일 열쇠 + 객체 번호(3바이트) + 세대(2바이트) [+ "sAlT"] 의 MD5, 앞 (n+5) 바이트(최대 16). */
    private fun objectKey(num: Int, gen: Int, aes: Boolean): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), gen.toByte(), (gen shr 8).toByte()))
        if (aes) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
        return md.digest().copyOf(minOf(fileKey.size + 5, 16))
    }

    companion object {
        /** 32바이트 채움 문자열(명세 7.6.3.3). 빈 암호는 이것 그대로다. */
        private val PAD = intArrayOf(
            0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
        ).map { it.toByte() }.toByteArray()

        /**
         * [encrypt] 사전과 trailer 의 첫 `/ID` 로 연다. 모르는 처리기 · 빈 암호로 안 열림 · 필드가 깨짐 → null.
         *
         * 사전의 값은 암호화되지 않는다(명세) — 그대로 읽는다.
         */
        fun open(encrypt: PdfDict, firstId: ByteArray?): PdfSecurity? = runCatching {
            if ((encrypt["Filter"] as? PdfName)?.name != "Standard") return null
            val v = (encrypt["V"] as? PdfNumber)?.int ?: 0
            val r = (encrypt["R"] as? PdfNumber)?.int ?: return null
            val o = (encrypt["O"] as? PdfString)?.bytes ?: return null
            val u = (encrypt["U"] as? PdfString)?.bytes ?: return null
            val p = (encrypt["P"] as? PdfNumber)?.long?.toInt() ?: return null
            val encryptMetadata = (encrypt["EncryptMetadata"] as? PdfBool)?.value ?: true

            when (r) {
                2, 3, 4 -> {
                    val lengthBits = if (r == 2) 40 else ((encrypt["Length"] as? PdfNumber)?.int ?: 40)
                    val n = (lengthBits / 8).coerceIn(5, 16)
                    val key = legacyKey(o, p, firstId ?: ByteArray(0), r, n, encryptMetadata)
                    if (!legacyUserPasswordMatches(key, u, firstId ?: ByteArray(0), r)) return null
                    val (strF, stmF) = if (v >= 4) methods(encrypt, fallback = Method.Rc4) else Method.Rc4 to Method.Rc4
                    PdfSecurity(key, r, strF, stmF)
                }
                5, 6 -> {
                    if (u.size < 48) return null
                    val ue = (encrypt["UE"] as? PdfString)?.bytes ?: return null
                    val password = ByteArray(0)
                    val validationSalt = u.copyOfRange(32, 40)
                    val keySalt = u.copyOfRange(40, 48)
                    if (!hash(password, validationSalt, r).contentEquals(u.copyOf(32))) return null
                    val intermediate = hash(password, keySalt, r)
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(intermediate, "AES"), IvParameterSpec(ByteArray(16)))
                    val fileKey = cipher.doFinal(ue.copyOf(32))
                    val (strF, stmF) = methods(encrypt, fallback = Method.Aes256)
                    PdfSecurity(fileKey, r, strF, stmF)
                }
                else -> null
            }
        }.getOrNull()

        /** V4+ 의 암호 거르개(`/CF` 의 `/StrF` · `/StmF`). `/Identity` 는 풀 것이 없다. */
        private fun methods(encrypt: PdfDict, fallback: Method): Pair<Method, Method> {
            val filters = encrypt["CF"] as? PdfDict
            fun method(key: String): Method {
                val name = (encrypt[key] as? PdfName)?.name ?: return Method.None // 없으면 Identity(명세)
                if (name == "Identity") return Method.None
                val cf = filters?.get(name) as? PdfDict ?: return fallback
                return when ((cf["CFM"] as? PdfName)?.name) {
                    "V2" -> Method.Rc4
                    "AESV2" -> Method.Aes128
                    "AESV3" -> Method.Aes256
                    "None" -> Method.None
                    else -> fallback
                }
            }
            return method("StrF") to method("StmF")
        }

        /** 알고리즘 2(R2–R4): 빈 암호의 파일 열쇠. */
        private fun legacyKey(o: ByteArray, p: Int, id: ByteArray, r: Int, n: Int, encryptMetadata: Boolean): ByteArray {
            val md = MessageDigest.getInstance("MD5")
            md.update(PAD)
            md.update(o.copyOf(32))
            md.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
            md.update(id)
            if (r >= 4 && !encryptMetadata) md.update(byteArrayOf(-1, -1, -1, -1))
            var key = md.digest()
            if (r >= 3) repeat(50) { key = MessageDigest.getInstance("MD5").digest(key.copyOf(n)) }
            return key.copyOf(n)
        }

        /** 알고리즘 4·5: 빈 사용자 암호가 맞는지. 틀리면 진짜 암호가 걸린 파일이다. */
        private fun legacyUserPasswordMatches(key: ByteArray, u: ByteArray, id: ByteArray, r: Int): Boolean {
            if (r == 2) return rc4(key, PAD).contentEquals(u.copyOf(32))
            val md = MessageDigest.getInstance("MD5")
            md.update(PAD)
            md.update(id)
            var x = rc4(key, md.digest())
            for (i in 1..19) x = rc4(ByteArray(key.size) { (key[it].toInt() xor i).toByte() }, x)
            return x.contentEquals(u.copyOf(16))
        }

        /**
         * R5 는 SHA-256 한 번, R6 은 알고리즘 2.B(SHA-256/384/512 와 AES-128 을 64번 이상 되풀이).
         * 사용자 암호를 볼 때라 소유자 몫의 추가 바이트(udata)는 없다.
         */
        private fun hash(password: ByteArray, salt: ByteArray, r: Int): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").digest(password + salt)
            if (r == 5) return k
            var round = 0
            while (true) {
                val block = password + k
                val k1 = ByteArray(block.size * 64).also { out -> repeat(64) { System.arraycopy(block, 0, out, it * block.size, block.size) } }
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                val e = cipher.doFinal(k1)
                // 앞 16바이트를 큰 수로 보고 3으로 나눈 나머지 = 바이트 합을 3으로 나눈 나머지(256 ≡ 1 mod 3).
                val mod = e.copyOf(16).sumOf { it.toInt() and 0xFF } % 3
                k = MessageDigest.getInstance(arrayOf("SHA-256", "SHA-384", "SHA-512")[mod]).digest(e)
                round++
                if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
            }
            return k.copyOf(32)
        }

        private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                s[i] = s[j].also { s[j] = s[i] }
            }
            var i = 0
            j = 0
            return ByteArray(data.size) { n ->
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                s[i] = s[j].also { s[j] = s[i] }
                (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
        }

        /**
         * 앞 16바이트가 IV 인 AES-CBC. 채움(PKCS#5)이 틀렸으면 떼지 않고 둔다 — 제목 끝 몇 바이트가 이상한
         * 편이 제목을 통째로 잃는 것보다 낫다. 16바이트 배수가 아니면 남는 꼬리는 버린다.
         */
        private fun aesCbc(key: ByteArray, data: ByteArray): ByteArray {
            if (data.size < 32) return ByteArray(0)
            val body = data.copyOfRange(16, 16 + (data.size - 16) / 16 * 16)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data.copyOf(16)))
            val plain = cipher.doFinal(body)
            val pad = plain.last().toInt() and 0xFF
            val validPad = pad in 1..16 && pad <= plain.size &&
                (plain.size - pad until plain.size).all { (plain[it].toInt() and 0xFF) == pad }
            return if (validPad) plain.copyOf(plain.size - pad) else plain
        }
    }

    override fun toString(): String = "PdfSecurity(R$revision, strings=$strings, streams=$streams)"
}
