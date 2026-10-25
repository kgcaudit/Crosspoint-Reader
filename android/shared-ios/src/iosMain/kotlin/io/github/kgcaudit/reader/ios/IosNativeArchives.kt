@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.io.IOException
import io.github.kgcaudit.reader.document.io.InputStream
import io.github.kgcaudit.reader.document.io.inputStream
import io.github.kgcaudit.reader.ios.archive.OLO_SKIPPED_SOME
import io.github.kgcaudit.reader.ios.archive.OLO_UNPACKED
import io.github.kgcaudit.reader.ios.archive.olo_7z_extract
import io.github.kgcaudit.reader.ios.archive.olo_7z_list
import io.github.kgcaudit.reader.ios.archive.olo_rar_extract
import io.github.kgcaudit.reader.ios.archive.olo_rar_list
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * RAR · 7z(cbr · cb7, 안드로이드 0.37.0 `NativeArchives` 의 iOS 쪽). 해제기는 안드로이드와 같은 C 원본(UnRAR · LZMA SDK)을 대상마다
 * 정적 라이브러리로 빌드한 것이다(`shared-ios/build.gradle.kts` 의 NativeArchiveLib, 다리 `src/nativeInterop/cpp`).
 * 둘 다 파일로 풀어야 읽을 수 있어 zip · tar 처럼 그 자리에서 읽지 못한다.
 */
internal object IosNativeArchives {
    enum class Kind { RAR, SEVEN_Z }

    class Entry(val name: String, val size: Long, val isDirectory: Boolean, val encrypted: Boolean)

    /** 파일 머리로 가린다 — 이름을 믿으면 cbr 이라는 이름의 zip(흔하다)이 열리지 않는다. */
    fun kindOf(head: ByteArray, length: Int): Kind? = when {
        length >= 6 && head[0] == 0x52.toByte() && head[1] == 0x61.toByte() && head[2] == 0x72.toByte() && head[3] == 0x21.toByte() &&
            head[4] == 0x1A.toByte() && head[5] == 0x07.toByte() -> Kind.RAR
        length >= 6 && head[0] == 0x37.toByte() && head[1] == 0x7A.toByte() && head[2] == 0xBC.toByte() && head[3] == 0xAF.toByte() &&
            head[4] == 0x27.toByte() && head[5] == 0x1C.toByte() -> Kind.SEVEN_Z
        else -> null
    }

    /** 항목들. 머리를 읽지 못하면 [IOException]. */
    fun list(kind: Kind, path: String): List<Entry> {
        val found = ArrayList<Entry>()
        val ref = StableRef.create(found)
        try {
            val sink = staticCFunction { ctx: kotlinx.cinterop.COpaquePointer?, name: CPointer<ByteVar>?, size: Long, dir: Int, encrypted: Int ->
                ctx!!.asStableRef<ArrayList<Entry>>().get().add(Entry(name?.toKString() ?: "", size, dir != 0, encrypted != 0))
                Unit
            }
            val code = when (kind) {
                Kind.RAR -> olo_rar_list(path, sink, ref.asCPointer())
                Kind.SEVEN_Z -> olo_7z_list(path, sink, ref.asCPointer())
            }
            if (code < 0) throw IOException("cannot list $path ($code)")
        } finally {
            ref.dispose()
        }
        return found
    }

    /**
     * [into] 아래에 푼다([picks] 가 null 이면 전부). 끝까지 돌았으면 true — 풀 수 없는 항목(암호 · 깨짐)을 건너뛰었어도 다시 풀면
     * 같으니 끝난 것이다. 도중에 멈췄으면(공간 부족 · 못 읽음) false.
     */
    fun extract(kind: Kind, path: String, into: String, picks: Collection<String>?): Boolean = memScoped {
        val list = picks.orEmpty().toList()
        val array = allocArrayOf(list.map { it.cstr.ptr })
        val code = when (kind) {
            Kind.RAR -> olo_rar_extract(path, into, array, list.size)
            Kind.SEVEN_Z -> olo_7z_extract(path, into, array, list.size)
        }
        code == OLO_UNPACKED || code == OLO_SKIPPED_SOME
    }

    /**
     * 풀어도 되는 이름인가: 절대 경로 · ".." 마디는 풀 곳 밖에 쓰인다("zip slip"). 해제기(7z)도 거르지만 쪽 목록에서도 뺀다
     * (안드로이드 `ComicArchive.safeEntryName`).
     */
    fun safeName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && !name.startsWith("\\") && name.replace('\\', '/').split('/').none { it == ".." }
}

/**
 * RAR · 7z 를 만화 압축 모양으로(살피기 · 표지 · ComicInfo). 항목은 볼 때 하나씩 임시 폴더에 풀어 읽는다 — 권 전체를 읽는 데는 쓰지
 * 않는다(통짜 RAR 은 한 쪽을 풀려면 앞 쪽들을 다 풀어야 해서 쪽마다 풀면 n² 이다). 권을 열 때는 [unpackAll] 로 한 번에.
 */
internal class NativeArchive(private val kind: IosNativeArchives.Kind, private val path: String) : IosComics.Archive {
    private val listed = IosNativeArchives.list(kind, path).filter { !it.isDirectory && !it.encrypted && IosNativeArchives.safeName(it.name) }
    override val names: List<String> = listed.map { it.name }
    override fun sizeOf(name: String): Long? = listed.firstOrNull { it.name == name }?.size?.takeIf { it >= 0 }
    override fun bytes(name: String): ByteArray? = extractOne(name) { FileBytes.file(it).openStream().use { s -> s.readAllBytes() } }
    override fun stream(name: String): InputStream? = bytes(name)?.inputStream()

    /** 항목 하나를 [target] 파일로(압축 속 권을 꺼낼 때). 메모리에 올리지 않고 풀린 파일을 옮긴다. */
    fun copyEntry(name: String, target: String): Boolean =
        extractOne(name) { NSFileManager.defaultManager.moveItemAtPath(it, target, null) } ?: false

    /** 전부 [into] 아래에. 끝까지 돌았으면 true(건너뛴 쪽이 있어도). */
    fun unpackAll(into: String): Boolean = IosNativeArchives.extract(kind, path, into, null)

    private inline fun <T> extractOne(name: String, use: (String) -> T): T? {
        if (name !in names) return null
        val dir = NSTemporaryDirectory() + "olo-one-" + NSUUID().UUIDString
        try {
            if (!IosNativeArchives.extract(kind, path, dir, listOf(name))) return null
            val file = "$dir/$name"
            // 해제기는 깨진 항목을 파일로 남기지 않는다 — 파일이 있으면 온전하다.
            if (!NSFileManager.defaultManager.fileExistsAtPath(file)) return null
            return use(file)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(dir, null)
        }
    }

    override fun close() = Unit
}

/** 풀어 둔 폴더를 만화 압축 모양으로(RAR · 7z 를 연 뒤). [names] 는 압축 안의 이름 그대로(하위 폴더 포함). */
internal class UnpackedArchive(private val dir: String, override val names: List<String>) : IosComics.Archive {
    override fun bytes(name: String): ByteArray? =
        if (name in names) runCatching { FileBytes.file("$dir/$name").openStream().use { it.readAllBytes() } }.getOrNull() else null
    override fun stream(name: String): InputStream? = if (name in names) runCatching { FileBytes.file("$dir/$name").openStream() }.getOrNull() else null
    override fun sizeOf(name: String): Long? = null
    override fun close() = Unit
}

/** 파일의 판(크기 · 고친 때). 같은 이름으로 바꿔 넣은 압축이 옛 풀린 쪽을 보이지 않게 열쇠에 넣는다. 못 읽으면 빈 글. */
internal object FileStamp {
    fun of(path: String): String {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return ""
        val size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: -1L
        val modified = (attrs[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
        return "$size|$modified"
    }
}
