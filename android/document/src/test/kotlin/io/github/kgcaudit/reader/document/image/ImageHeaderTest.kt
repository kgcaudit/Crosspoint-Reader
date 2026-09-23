package io.github.kgcaudit.reader.document.image

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 그림 파일 머리에서 크기를 읽는다.
 *
 * PNG·GIF·JPEG 는 JVM 의 ImageIO 로 **진짜 파일**을 만들어 본다. 손으로 짠 바이트만 보면
 * 실제 인코더가 쓰는 모양(JFIF 머리, 채움 바이트)을 놓친다.
 */
class ImageHeaderTest {

    private fun encode(format: String, w: Int, h: Int, type: Int = BufferedImage.TYPE_INT_RGB): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, type), format, it) }.toByteArray()

    private fun read(bytes: ByteArray) = ImageHeader.read(ByteArrayInputStream(bytes))

    // ── 형식 ────────────────────────────────────────────────────────

    @Test
    fun `sizes of real png, gif and jpeg files are read from their headers`() {
        // 세 책에서 나온 실제 크기들이다: 세로 표지, 장 제목 띠, 문장 속 로고.
        assertEquals(ImageSize(591, 839), read(encode("png", 591, 839)))
        assertEquals(ImageSize(600, 244), read(encode("jpg", 600, 244)))
        assertEquals(ImageSize(118, 23), read(encode("gif", 118, 23, BufferedImage.TYPE_BYTE_INDEXED)))
    }

    @Test
    fun `a jpeg whose size comes after a large exif block is still read`() {
        // 휴대폰 사진·스캔본은 SOF 앞에 수십 KB 짜리 EXIF(썸네일 포함)가 있다.
        val jpeg = encode("jpg", 1000, 1497)
        val app1 = ByteArray(4 + 60_000).also {
            it[0] = 0xFF.toByte(); it[1] = 0xE1.toByte()
            val len = 60_002
            it[2] = (len shr 8).toByte(); it[3] = (len and 0xFF).toByte()
        }
        val withExif = jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
        assertEquals(ImageSize(1000, 1497), read(withExif))
    }

    @Test
    fun `a progressive jpeg is read too`() {
        // 웹에서 온 그림은 점진적(SOF2) JPEG 가 흔하다. SOF0 만 보면 크기를 놓친다.
        val jpeg = encode("jpg", 640, 480)
        val sof = (2 until jpeg.size - 1).first { jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == 0xC0.toByte() }
        jpeg[sof + 1] = 0xC2.toByte()
        assertEquals(ImageSize(640, 480), read(jpeg))
    }

    @Test
    fun `webp in all three variants is read`() {
        assertEquals(ImageSize(300, 200), read(webpLossy(300, 200)))
        assertEquals(ImageSize(300, 200), read(webpLossless(300, 200)))
        assertEquals(ImageSize(3000, 2000), read(webpExtended(3000, 2000)))
    }

    @Test
    fun `a stream that cannot skip is read byte by byte`() {
        // zip 엔트리를 압축 해제하는 스트림은 skip() 이 0 을 돌려주곤 한다. 그걸 "끝" 으로
        // 읽으면 EPUB 안의 JPEG 만 크기를 못 읽는다.
        val jpeg = encode("jpg", 800, 600)
        val noSkip = object : InputStream() {
            val inner = ByteArrayInputStream(jpeg)
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
            override fun skip(n: Long) = 0L
        }
        assertEquals(ImageSize(800, 600), ImageHeader.read(noSkip))
    }

    // ── 깨진 입력 ───────────────────────────────────────────────────

    @Test
    fun `broken or unknown files give no size instead of failing`() {
        val png = encode("png", 10, 10)
        assertNull(read(png.copyOfRange(0, 12)), "잘린 PNG")
        assertNull(read(ByteArray(0)), "빈 파일")
        assertNull(read("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()), "SVG 는 머리로 알 수 없다")
        assertNull(read(ByteArray(64) { (it * 37).toByte() }), "알 수 없는 바이트")

        // 크기가 0 으로 적힌 PNG — 0 으로 나누는 조판을 막는다.
        val zero = png.copyOf().also { for (i in 16..23) it[i] = 0 }
        assertNull(read(zero))
    }

    @Test
    fun `a jpeg that lies about its segment lengths does not hang`() {
        // 길이 0 인 세그먼트: 제자리를 맴돌면 앱이 멈춘다.
        assertNull(read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)))

        // SOF 없이 APP 세그먼트만 끝없이 이어지는 파일(가짜 스트림 — 실제로 끝이 없다).
        val endless = object : InputStream() {
            var i = 0L
            override fun read(): Int {
                val pattern = intArrayOf(0xFF, 0xD8)
                if (i < 2) return pattern[(i++).toInt()]
                // FF E1 00 10 + 14바이트 채움, 반복
                val k = ((i++ - 2) % 18).toInt()
                return when (k) { 0 -> 0xFF; 1 -> 0xE1; 2 -> 0x00; 3 -> 0x10; else -> 0 }
            }
        }
        assertNull(ImageHeader.read(endless))

        // 크기 정보 전에 영상 데이터(SOS)가 시작하는 파일
        assertNull(read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2)))
    }

    // ── WebP 조립 ───────────────────────────────────────────────────

    private fun riff(chunk: String, payload: ByteArray): ByteArray {
        val body = "WEBP".toByteArray() + chunk.toByteArray() + le32(payload.size) + payload
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun webpLossy(w: Int, h: Int) = riff(
        "VP8 ",
        byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(w) + le16(h) + ByteArray(8),
    )

    private fun webpLossless(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        return riff("VP8L", byteArrayOf(0x2F) + le32(bits) + ByteArray(8))
    }

    private fun webpExtended(w: Int, h: Int) =
        riff("VP8X", ByteArray(4) + le24(w - 1) + le24(h - 1) + ByteArray(8))

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
