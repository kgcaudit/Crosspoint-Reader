package io.github.kgcaudit.reader.document.image

import io.github.kgcaudit.reader.document.io.Text
import io.github.kgcaudit.reader.document.readUpTo
import io.github.kgcaudit.reader.document.io.IOException
import io.github.kgcaudit.reader.document.io.InputStream

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
 * PNG·GIF·WebP·BMP 는 앞쪽 30바이트 안에, JPEG 는 세그먼트를 건너뛰며 찾는 SOF 마커 안에
 * 크기가 있다. AVIF · HEIC 는 아직 읽지 않는다(상자를 따라가야 한다) — 크기를 모르는 쪽으로 남는다.
 *
 * 모르는 형식이거나 깨진 파일이면 null. 예외를 던지지 않는다 — 그림 하나 때문에 챕터가
 * 안 열리면 안 된다.
 */
object ImageHeader {

    /** JPEG 에서 SOF 를 찾으며 읽을 최대 바이트. EXIF 썸네일이 커도 이 안에 끝난다. */
    private const val JPEG_SCAN_LIMIT: Long = 4L * 1024 * 1024

    /** AVIF · HEIC 에서 "ispe" 를 찾으며 읽을 바이트. 메타 상자는 파일 앞쪽 몇 KB 안에 있다(그림 데이터는 그 뒤). */
    private const val ISO_SCAN: Int = 64 * 1024

    fun read(input: InputStream): ImageSize? = try {
        val head = ByteArray(30)
        val n = input.readUpTo(head)
        when {
            n >= 24 && isPng(head) -> ImageSize(u32be(head, 16), u32be(head, 20))
            n >= 10 && isGif(head) -> ImageSize(u16le(head, 6), u16le(head, 8))
            n >= 30 && isWebp(head) -> webp(head)
            n >= 4 && isJpeg(head) -> jpeg(head, n, input)
            // 만화 쪽으로 bmp 도 받는다(ComicContents) — 크기를 모르면 웹툰 판별 · 기둥 배치에서 그 쪽만 빠졌다.
            n >= 26 && isBmp(head) -> bmp(head)
            n >= 12 && isIsoMedia(head) -> isoMedia(head, n, input)
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

    private fun isBmp(b: ByteArray) = b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()

    /** AVIF · HEIC(ISO 미디어 파일): 4번째 바이트부터 "ftyp" 상자. */
    private fun isIsoMedia(b: ByteArray) = ascii(b, 4, 4) == "ftyp"

    /**
     * BMP: 파일 머리(14바이트) 뒤 DIB 머리. 옛 OS/2 머리(12바이트)는 16비트, 나머지(40바이트 이상)는 32비트 부호 있는
     * 값이다. 높이가 음수면 위에서 아래로 적은 그림일 뿐 크기는 같다.
     */
    private fun bmp(b: ByteArray): ImageSize? = when (u32le(b, 14)) {
        12 -> ImageSize(u16le(b, 18), u16le(b, 20))
        in 40..1024 -> ImageSize(u32le(b, 18), kotlin.math.abs(u32le(b, 22)))
        else -> null
    }

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
     * AVIF · HEIC: 크기는 "ispe"(그림 크기) 상자에 있다 — 상자 머리(크기 4 + "ispe") 뒤에 판 · 깃발 4바이트, 가로 · 세로 각 4바이트.
     * 0.55.1 까지는 이 형식을 만화 쪽으로 받으면서 크기를 읽지 못해(null), 펼침면이 넓은 그림으로 판정되지 않고 반으로 나뉘지
     * 않았다 — 두 쪽 그림의 오른쪽 반을 건너뛰고 다음 그림으로 가는 것처럼 보였다(사용자 지적 "b 쪽을 건너뛴다").
     *
     * 상자 나무를 다 풀지 않고 앞 [ISO_SCAN] 바이트에서 "ispe" 를 찾는다. 휴대폰 HEIC 는 512 칸 조각마다 ispe 가 있고 전체 그림의
     * ispe 가 따로 있어, 가장 넓은 것이 전체 그림이다. "irot"(돌림)이 90° · 270° 면 가로 · 세로를 바꾼다 — 화면에 서는 모양이 기준이다.
     */
    private fun isoMedia(head: ByteArray, headLength: Int, rest: InputStream): ImageSize? {
        val buf = ByteArray(ISO_SCAN)
        head.copyInto(buf, 0, 0, headLength)
        val n = headLength + rest.readUpTo(buf, headLength, ISO_SCAN - headLength)
        var best: ImageSize? = null
        var turned = false
        var i = 8
        while (i + 16 <= n) {
            when (ascii(buf, i, 4)) {
                "ispe" -> {
                    val w = u32be(buf, i + 8)
                    val h = u32be(buf, i + 12)
                    if (w > 0 && h > 0 && (best == null || w.toLong() * h > best.width.toLong() * best.height)) best = ImageSize(w, h)
                }
                "irot" -> turned = (buf[i + 4].toInt() and 3) % 2 == 1
            }
            i++
        }
        return best?.let { if (turned) ImageSize(it.height, it.width) else it }
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

    private fun ascii(b: ByteArray, at: Int, len: Int) = Text.ascii(b, at, len)
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
