@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 넘김 소리의 자리(구상안 23A, 안드로이드 `PageTurnFx` 의 `spatialTurnSound` · `turnSweep` · `sweepStereo` 와 같은 셈, 디자인
 * 인계 §6-7 "소리는 움직임이 일어난 자리에서 난다"). 원본은 안드로이드와 바이트까지 같은 모노 WAV 한 벌이고, 쓸린 스테레오는
 * 처음 쓸 때 여기서 만든다 — 앱을 키우지 않고, 소리를 바꿀 때 고칠 파일이 하나다.
 */
object TurnFx {
    const val SWEEP_START = 0.7f
    const val SWEEP_END = 0.3f

    /**
     * 좌우가 진짜 좌우일 때만 쓸린다: 이어폰 · 가로 화면 · 폭 600 이상. 세로로 든 휴대폰 스피커는 위 · 아래라 좌우로 나눈 소리가
     * 위아래로 갈라진다 — 그때는 가운데. 따로 설정을 두지 않는다(모노가 필요한 사람은 휴대폰의 "모노 오디오" 가 합친다).
     */
    fun spatial(headphones: Boolean, landscape: Boolean, widthPt: Float): Boolean = headphones || landscape || widthPt >= 600f

    /**
     * 어디서 어디로 쓸리는지(-1 왼쪽 · +1 오른쪽): 넘어가는 종이 쪽에서 시작해 가운데를 조금 지나 끝난다. 왼→오 책의 다음 쪽은 오른쪽
     * 종이가 넘어가니 오른쪽에서(움직이는 것을 따른다, 누른 자리가 아니다). 쓸지 않으면 null(가운데).
     */
    fun sweep(forward: Boolean, spatial: Boolean): FloatArray? = when {
        !spatial -> null
        forward -> floatArrayOf(SWEEP_START, -SWEEP_END)
        else -> floatArrayOf(-SWEEP_START, SWEEP_END)
    }

    /**
     * 모노를 [from] 에서 [to] 로 쓸려 가는 스테레오로. 같은 세기 법칙(cos/sin)에 √2 를 곱해 가운데가 원래 크기와 같고, 큰 쪽은 1 에서
     * 멈춰 소리가 잘리지 않는다.
     */
    fun sweepStereo(mono: ShortArray, from: Float, to: Float): ShortArray {
        val out = ShortArray(mono.size * 2)
        val last = (mono.size - 1).coerceAtLeast(1)
        for (i in mono.indices) {
            val pan = from + (to - from) * i / last
            val angle = (pan.coerceIn(-1f, 1f) + 1f) * PI / 4
            val left = minOf(1.0, cos(angle) * sqrt(2.0))
            val right = minOf(1.0, sin(angle) * sqrt(2.0))
            out[2 * i] = (mono[i] * left).toInt().toShort()
            out[2 * i + 1] = (mono[i] * right).toInt().toShort()
        }
        return out
    }

    /** 모노 16비트 WAV → 쓸린 스테레오 WAV. 다른 꼴(스테레오 · 8비트 · 깨짐)이면 null — 그때는 원본 그대로(가운데) 낸다. */
    fun sweptWav(wav: NSData, forward: Boolean): NSData? {
        val bytes = ByteArray(wav.length.toInt())
        if (bytes.isEmpty()) return null
        bytes.usePinned { memcpy(it.addressOf(0), wav.bytes, wav.length) }
        val (channels, rate, samples) = readPcm16(bytes) ?: return null
        if (channels != 1) return null
        val (from, to) = sweep(forward, true)!!.let { it[0] to it[1] }
        val out = writeStereoPcm16(sweepStereo(samples, from, to), rate)
        return out.usePinned { NSData.create(bytes = it.addressOf(0), length = out.size.toULong()) }
    }

    /** 16비트 PCM WAV 의 (채널 수, 표본 빈도, 표본). 다른 꼴이면 null. */
    internal fun readPcm16(wav: ByteArray): Triple<Int, Int, ShortArray>? {
        fun u16(i: Int) = (wav[i].toInt() and 0xFF) or ((wav[i + 1].toInt() and 0xFF) shl 8)
        fun i32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        fun tag(i: Int) = wav.copyOfRange(i, i + 4).decodeToString()
        if (wav.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") return null
        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (at + 8 <= wav.size) {
            val id = tag(at)
            val size = i32(at + 4)
            if (size < 0 || at + 8 + size > wav.size) return null
            if (id == "fmt " && size >= 16) {
                if (u16(at + 8) != 1) return null
                channels = u16(at + 10)
                rate = i32(at + 12)
                bits = u16(at + 22)
            } else if (id == "data") {
                if (bits != 16 || channels <= 0) return null
                return Triple(channels, rate, ShortArray(size / 2) { u16(at + 8 + it * 2).toShort() })
            }
            at += 8 + size + (size and 1)
        }
        return null
    }

    internal fun writeStereoPcm16(samples: ShortArray, rate: Int): ByteArray {
        val data = samples.size * 2
        val out = ByteArray(44 + data)
        var at = 0
        fun put(s: String) { s.encodeToByteArray().copyInto(out, at); at += 4 }
        fun i16(v: Int) { out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte(); at += 2 }
        fun i32(v: Int) { i16(v and 0xFFFF); i16(v ushr 16) }
        put("RIFF"); i32(36 + data); put("WAVE")
        put("fmt "); i32(16); i16(1); i16(2); i32(rate); i32(rate * 4); i16(4); i16(16)
        put("data"); i32(data)
        for (s in samples) i16(s.toInt())
        return out
    }
}
