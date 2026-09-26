package io.github.kgcaudit.reader.document.image

import io.github.kgcaudit.reader.document.readUpTo
import java.io.IOException
import java.io.InputStream

/** 그림 한 장의 픽셀 크기. */
data class ImageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "image size must be positive, was ${width}x$height" }
    }
}

/**
 * 그림 파일의 **머리만** 읽어 픽셀 크기를 알아낸다.
 *
 * 왜 필요한가: 상용 EPUB 은 `<img>` 에 `width`/`height` 를 거의 적지 않는다. 크기를 모르면
 * 조판기는 모든 그림에 같은 상자를 줄 수밖에 없고, 세로형 표지가 가로로 늘어나고 작은
 * 로고가 페이지 폭으로 부풀어 흐려진다(실제 책 세 권에서 그림 172개 중 171개가 그랬다).
 *
 * 왜 디코드하지 않는가: 조판은 챕터마다 그림 수십 장을 지나간다. 픽셀까지 풀면 느리고
 * 메모리를 먹으며, 무엇보다 이 모듈은 순수 Kotlin 이라 플랫폼 디코더를 쓸 수 없다.
 * PNG·GIF·WebP 는 앞쪽 30바이트 안에, JPEG 는 세그먼트를 건너뛰며 찾는 SOF 마커 안에
 * 크기가 있다.
 *
 * 모르는 형식이거나 깨진 파일이면 null. 예외를 던지지 않는다 — 그림 하나 때문에 챕터가
 * 안 열리면 안 된다.
 */
object ImageHeader {

    /** JPEG 에서 SOF 를 찾으며 읽을 최대 바이트. EXIF 썸네일이 커도 이 안에 끝난다. */
    private const val JPEG_SCAN_LIMIT: Long = 4L * 1024 * 1024

    fun read(input: InputStream): ImageSize? = try {
        val head = ByteArray(30)
        val n = input.readUpTo(head)
        when {
            n >= 24 && isPng(head) -> ImageSize(u32be(head, 16), u32be(head, 20))
            n >= 10 && isGif(head) -> ImageSize(u16le(head, 6), u16le(head, 8))
            n >= 30 && isWebp(head) -> webp(head)
            n >= 4 && isJpeg(head) -> jpeg(head, n, input)
            else -> null
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        // 크기가 0 이거나 음수로 적힌 파일. 모르는 것과 같다.
        null
    }

    // ── 형식별 ──────────────────────────────────────────────────────

    private fun isPng(b: ByteArray) =
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() &&
            // 첫 청크가 IHDR 이어야 폭·높이가 그 자리에 있다.
            b[12] == 'I'.code.toByte() && b[13] == 'H'.code.toByte() && b[14] == 'D'.code.toByte() && b[15] == 'R'.code.toByte()

    private fun isGif(b: ByteArray) = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    private fun isWebp(b: ByteArray) =
        ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP"

    private fun isJpeg(b: ByteArray) = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun webp(b: ByteArray): ImageSize? = when (ascii(b, 12, 4)) {
        // 손실 압축: 프레임 머리(시작 코드 9D 01 2A) 뒤 14비트씩.
        "VP8 " -> if (b[23] == 0x9D.toByte() && b[24] == 0x01.toByte() && b[25] == 0x2A.toByte()) {
            ImageSize(u16le(b, 26) and 0x3FFF, u16le(b, 28) and 0x3FFF)
        } else {
            null
        }
        // 무손실: 서명 0x2F 뒤 14비트씩, 값은 (크기 - 1).
        "VP8L" -> if (b[20] == 0x2F.toByte()) {
            val bits = u32le(b, 21)
            ImageSize((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
        } else {
            null
        }
        // 확장: 24비트씩, 값은 (크기 - 1).
        "VP8X" -> ImageSize(u24le(b, 24) + 1, u24le(b, 27) + 1)
        else -> null
    }

    /**
     * JPEG: 마커를 따라가며 SOF(프레임 시작)를 찾는다.
     *
     * 세그먼트마다 길이가 적혀 있으므로 내용은 읽지 않고 건너뛴다. EXIF(APP1)에 썸네일이
     * 들어 있어도 수십 KB 를 건너뛸 뿐이다. 길이가 거짓말을 하는 깨진 파일도 [JPEG_SCAN_LIMIT]
     * 에서 멈춘다 — 끝없이 읽지 않는다.
     */
    private fun jpeg(head: ByteArray, headLength: Int, rest: InputStream): ImageSize? {
        val input = PrefixedStream(head, headLength, rest)
        input.skipExactly(2) // SOI
        while (input.position < JPEG_SCAN_LIMIT) {
            // 마커 앞의 채움 바이트(FF 여러 개)를 건너뛴다.
            var marker = input.read()
            if (marker != 0xFF) return null
            while (marker == 0xFF) marker = input.read()
            if (marker < 0) return null

            when (marker) {
                // 길이가 없는 마커
                0x01, in 0xD0..0xD7 -> continue
                // 영상 데이터가 시작됐는데 SOF 를 못 봤다.
                0xD9, 0xDA -> return null
            }
            val length = input.u16be()
            if (length < 2) return null
            // SOF0~SOF15 (C4 허프만, C8 예약, CC 산술부호 표는 제외)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                input.skipExactly(1) // 정밀도
                val height = input.u16be()
                val width = input.u16be()
                return ImageSize(width, height)
            }
            input.skipExactly(length - 2L)
        }
        return null
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private fun ascii(b: ByteArray, at: Int, len: Int) = String(b, at, len, Charsets.US_ASCII)
    private fun u16le(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u24le(b: ByteArray, at: Int) = u16le(b, at) or ((b[at + 2].toInt() and 0xFF) shl 16)
    private fun u32le(b: ByteArray, at: Int) = u24le(b, at) or ((b[at + 3].toInt() and 0xFF) shl 24)
    private fun u32be(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** 이미 읽은 머리 바이트를 앞에 붙인 스트림. 읽은 위치를 센다. */
    private class PrefixedStream(
        private val head: ByteArray,
        private val headLength: Int,
        private val rest: InputStream,
    ) {
        var position: Long = 0
            private set

        fun read(): Int {
            val value = if (position < headLength) head[position.toInt()].toInt() and 0xFF else rest.read()
            if (value >= 0) position++
            return value
        }

        fun u16be(): Int {
            val hi = read()
            val lo = read()
            if (hi < 0 || lo < 0) throw IOException("truncated JPEG")
            return (hi shl 8) or lo
        }

        fun skipExactly(count: Long) {
            var left = count
            while (left > 0 && position < headLength) {
                position++
                left--
            }
            while (left > 0) {
                val skipped = rest.skip(left)
                if (skipped <= 0) {
                    // skip 이 0 을 돌려주는 스트림(압축 해제 중인 zip 등)은 한 바이트씩 읽는다.
                    if (rest.read() < 0) throw IOException("truncated JPEG")
                    position++
                    left--
                } else {
                    position += skipped
                    left -= skipped
                }
            }
        }
    }
}
