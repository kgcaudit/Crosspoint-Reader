package io.github.kgcaudit.reader.archive

import java.io.File
import java.io.IOException

/** 압축 안의 항목 하나. */
data class NativeEntry(
    /** 압축 안 경로, `/` 로 나눈다. 폴더는 `/` 로 끝난다. */
    val path: String,
    /** 풀었을 때 크기. 모르면 -1. */
    val size: Long,
    val isDirectory: Boolean,
    /** 암호가 걸렸다. 만화 뷰어는 암호를 묻지 않는다 — 걸린 쪽은 못 그린 쪽으로 보인다. */
    val encrypted: Boolean,
)

enum class NativeKind { RAR, SEVEN_Z }

/**
 * RAR · 7z 를 읽는다(0.37.0, docs/COMIC_PLAN.md C4 — cbr · cb7). 해제는 C++ 기준 구현(UnRAR · LZMA SDK)이 한다:
 * RAR5 의 LZ · PPMd · 필터 VM 과 7z 의 LZMA2 · PPMd · 분기 필터는 Kotlin 으로 옮기다 틀리면 그림이 조용히 깨진다.
 * 원본과 JNI 다리는 OLO Explorer 의 것을 그대로 가져왔다(읽기만 한다 — UnRAR 허가는 RAR 를 만드는 데 쓰지 못하게 한다).
 *
 * 해제기는 **파일 경로**를 받아 **파일로** 푼다. 그래서 부르는 쪽이 경로(문서 제공자의 열린 파일이면 `/proc/self/fd/N`)를
 * 주고, 풀 곳(캐시 폴더)을 준다. 휴대폰은 arm64 만 싣는다 — 다른 기기에서는 [available] 이 false 이고 이 형식만 열리지 않는다.
 */
object NativeArchives {

    private val RAR = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
    private val SEVEN_Z = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    /** 파일 머리(앞 6바이트 이상)로 종류를 가린다. 확장자를 믿지 않는다 — cbr 이라는 이름의 zip 도 흔하다. */
    fun kindOf(head: ByteArray): NativeKind? = when {
        head.size >= 6 && head.copyOf(6).contentEquals(RAR) -> NativeKind.RAR
        head.size >= 6 && head.copyOf(6).contentEquals(SEVEN_Z) -> NativeKind.SEVEN_Z
        else -> null
    }

    fun available(kind: NativeKind): Boolean = when (kind) {
        NativeKind.RAR -> RarNative.available
        NativeKind.SEVEN_Z -> SevenZipNative.available
    }

    /** 항목들. 읽지 못하면(깨짐 · 머리까지 암호) [IOException]. */
    fun list(kind: NativeKind, path: String): List<NativeEntry> {
        if (!available(kind)) throw IOException("no native reader for $kind on this device")
        val out = ArrayList<NativeEntry>()
        val code = when (kind) {
            NativeKind.RAR -> RarNative.nativeList(path, object : RarNative.Sink {
                override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean) {
                    out += NativeEntry(if (isDirectory) name.trimEnd('/') + "/" else name, if (isDirectory) -1 else size, isDirectory, encrypted)
                }
                override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
                override fun cancelled() = false
            })
            NativeKind.SEVEN_Z -> SevenZipNative.nativeList(arrayOf(path), object : SevenZipNative.Sink {
                override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean, unsupported: Boolean) {
                    // 해제기가 모르는 방식의 항목은 암호 걸린 것과 같이 다룬다 — 어느 쪽이든 풀 수 없다.
                    out += NativeEntry(if (isDirectory) name.trimEnd('/') + "/" else name, if (isDirectory) -1 else size, isDirectory, encrypted || unsupported)
                }
                override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
                override fun cancelled() = false
            })
        }
        if (code != 0) throw IOException("cannot list $kind archive (code $code)")
        return out
    }

    /**
     * [picks](압축 안 경로)를 [into] 아래에 푼다. null 이면 전부. 다 풀었으면 true.
     *
     * RAR 은 흔히 "통짜(solid)" 라 한 쪽을 풀려면 그 앞 쪽들을 모두 풀어야 한다 — 쪽마다 따로 풀면 n² 이다. 그래서 만화를 열
     * 때는 한 번에 다 푼다(부르는 쪽). 표지 하나만 풀 때는 [picks] 로.
     */
    fun extract(kind: NativeKind, path: String, into: File, picks: Set<String>?): Boolean {
        if (!available(kind)) throw IOException("no native reader for $kind on this device")
        into.mkdirs()
        val code = when (kind) {
            NativeKind.RAR -> RarNative.nativeExtract(path, into.path, picks?.toTypedArray(), null, 0, false, object : RarNative.Sink {
                override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean) = Unit
                override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
                override fun cancelled() = false
            })
            NativeKind.SEVEN_Z -> SevenZipNative.nativeExtract(arrayOf(path), into.path, picks?.toTypedArray(), null, 0, false, object : SevenZipNative.Sink {
                override fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean, unsupported: Boolean) = Unit
                override fun progress(doneBytes: Long, totalBytes: Long, name: String) = Unit
                override fun cancelled() = false
            })
        }
        return code == 0
    }
}

/**
 * UnRAR(알렉산드르 로샬의 무료 UnRAR 원본, `cpp/unrar/license.txt`). 읽기만 한다. JNI 가 이 이름 · 모양을 그대로 찾으므로
 * (`Java_io_github_kgcaudit_reader_archive_RarNative_*`, 받는 쪽 메서드 이름 · 인자) 바꾸면 C++ 쪽도 함께 바꿔야 한다.
 */
internal object RarNative {
    interface Sink {
        fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean)
        fun progress(doneBytes: Long, totalBytes: Long, name: String)
        fun cancelled(): Boolean
    }

    val available: Boolean = runCatching { System.loadLibrary("olorar"); nativeVersion() > 0 }.getOrDefault(false)

    private external fun nativeVersion(): Int
    external fun nativeList(path: String, sink: Sink): Int
    external fun nativeExtract(
        path: String, destDir: String, picks: Array<String>?, password: String?, totalBytes: Long, skipExisting: Boolean, sink: Sink,
    ): Int
}

/** LZMA SDK(이고르 파블로프, 공개 영역 — `cpp/sevenz/lzma-sdk-license.txt`)의 7z 해제. 이름 규칙은 [RarNative] 와 같다. */
internal object SevenZipNative {
    interface Sink {
        fun entry(name: String, size: Long, isDirectory: Boolean, modifiedMillis: Long, encrypted: Boolean, unsupported: Boolean)
        fun progress(doneBytes: Long, totalBytes: Long, name: String)
        fun cancelled(): Boolean
    }

    val available: Boolean = runCatching { System.loadLibrary("olo7z"); nativeVersion() > 0 }.getOrDefault(false)

    private external fun nativeVersion(): Int
    external fun nativeList(volumes: Array<String>, sink: Sink): Int
    external fun nativeExtract(
        volumes: Array<String>, destDir: String, picks: Array<String>?, password: String?, totalBytes: Long, skipExisting: Boolean, sink: Sink,
    ): Int
}
