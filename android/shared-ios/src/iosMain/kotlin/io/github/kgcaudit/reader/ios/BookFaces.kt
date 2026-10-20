@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.layout.book.BookFontTable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.coroutines.cancellation.CancellationException
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreText.CTFontCopyTable
import platform.CoreText.CTFontCreateWithFontDescriptor
import platform.CoreText.CTFontDescriptorRef
import platform.CoreText.kCTFontTableOS2
import platform.CoreText.kCTFontTableOptionNoOptions
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.math.abs

/**
 * 책에 든 글꼴(출판사 글꼴)을 꺼내 읽어 둔 것 — 안드로이드 `BookTypefaces` 의 짝. [CoreTextMeasurer] 가
 * [TextStyle.face][io.github.kgcaudit.reader.layout.TextStyle.face] 번호로 꼴을 고른다.
 *
 * EPUB 안의 글꼴은 대개 압축 · 난독화돼 있어 CoreText 가 바로 읽지 못한다. 그래서 캐시 폴더에 파일로 꺼낸다 — 공간이
 * 모자라 iOS 가 지우면 다음에 열 때 다시 꺼낸다. 꼴(CTFontDescriptor)은 리더가 닫힐 때 [release] 로 놓는다.
 */
class BookFaces private constructor(private val families: List<Family?>) {

    internal class Face(val descriptor: CTFontDescriptorRef, val weight: Int, val italic: Boolean, val path: String)

    internal class Family(val faces: List<Face>)

    /** 읽을 수 있는 글꼴이 하나도 없다. 목록에 "출판사 글꼴" 을 내놓을 이유가 없다. */
    val isEmpty: Boolean get() = families.all { it == null }

    /**
     * 읽어 낸 가족들의 지문(안드로이드와 같은 셈). 조판 캐시 키([LayoutSpec.bookFontsKey][io.github.kgcaudit.reader.layout.LayoutSpec.bookFontsKey])에
     * 넣는다 — 같은 책도 어떤 가족을 꺼냈는지에 따라 폭이 다르다. 하나를 못 꺼낸 날 모두 꺼낸 날의 쪽을 쓰면 줄 끝이 어긋난다.
     */
    val fingerprint: String = fnv1aHex(
        families.joinToString(";") { family ->
            family?.faces?.joinToString(",") { "${it.weight}${if (it.italic) "i" else ""}" } ?: "-"
        },
    )

    /** 목록에서 "출판사 글꼴" 이름을 그릴 파일(본문에 가장 많이 쓰일 첫 가족의 보통 꼴). */
    val previewPath: String? get() = families.firstNotNullOfOrNull { it }?.let { pick(it, bold = false).first.path }

    /** 번호 [face] 의 꼴과 **굵기를 합성해야 하는지.** 모르는 번호 · 읽지 못한 가족이면 null — 측정기가 본문 글꼴을 쓴다. */
    internal fun select(face: Int, bold: Boolean): Pair<Face, Boolean>? {
        val family = families.getOrNull(face - 1) ?: return null
        return pick(family, bold)
    }

    /**
     * 보통 = 400 에 가장 가까운 것(같으면 가는 쪽), 굵게 = 보통보다 굵고 600 이상인 것 중 700 에 가장 가까운 것(안드로이드
     * `chooseRegularAndBold`). 굵은 파일 하나뿐인 가족(보통이 곧 굵음)은 다시 굵히지 않는다 — 한 번 더 굵히면 획이 뭉개진다.
     */
    private fun pick(family: Family, bold: Boolean): Pair<Face, Boolean> {
        val upright = family.faces.filter { !it.italic }.ifEmpty { family.faces }
        val regular = upright.minWith(compareBy<Face> { abs(it.weight - 400) }.thenBy { it.weight })
        val heavy = upright.filter { it !== regular && it.weight >= 600 && it.weight > regular.weight }.minByOrNull { abs(it.weight - 700) }
        if (bold && heavy != null) return heavy to false
        return regular to (bold && regular.weight < 600)
    }

    fun release() {
        families.forEach { family -> family?.faces?.forEach { CFRelease(it.descriptor) } }
    }

    companion object {
        val EMPTY = BookFaces(emptyList())

        /**
         * [table] 의 글꼴 파일을 [dir] 에 꺼내 읽는다. 없는 파일 · 웹 글꼴 · 깨진 파일은 빼고 간다(그 가족은 본문 글꼴로
         * 그려진다). 이미 꺼내 둔 파일은 다시 꺼내지 않는다.
         */
        suspend fun prepare(document: ReflowDocument, table: BookFontTable, dir: String): BookFaces {
            if (table.isEmpty) return EMPTY
            val fm = NSFileManager.defaultManager
            fm.createDirectoryAtPath(dir, true, null, null)
            val families = table.families.map { family ->
                val faces = family.files.mapNotNull { file ->
                    val target = "$dir/${fnv1aHex(file.path)}.font"
                    if (!fm.fileExistsAtPath(target) && !extract(document, file.path, target)) return@mapNotNull null
                    val descriptor = UserFonts.descriptor(target)
                    if (descriptor == null) {
                        // 읽지 못하는 파일을 남겨 두면 다음에도 꺼냈다고 믿고 또 실패한다.
                        fm.removeItemAtPath(target, null)
                        return@mapNotNull null
                    }
                    val (weight, italic) = os2(descriptor)
                    Face(descriptor, file.weight ?: weight, file.italic ?: italic, target)
                }
                faces.takeIf { it.isNotEmpty() }?.let(::Family)
            }
            return BookFaces(families)
        }

        private suspend fun extract(document: ReflowDocument, path: String, target: String): Boolean {
            val temp = "$target.part"
            val fm = NSFileManager.defaultManager
            return try {
                val bytes = document.openFont(path)?.use { it.readAllBytes() } ?: return false
                if (bytes.isEmpty()) return false
                val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
                data.writeToFile(temp, true) && fm.moveItemAtPath(temp, target, null)
            } catch (e: CancellationException) {
                // 취소는 실패가 아니다. 삼키면 취소된 준비가 남은 글꼴(각 수 MB)을 끝까지 꺼낸다.
                throw e
            } catch (e: Exception) {
                false
            } finally {
                fm.removeItemAtPath(temp, null)
            }
        }

        /**
         * 파일이 밝힌 굵기(OS/2 usWeightClass)와 기울임(fsSelection 0번 비트). `@font-face` 에 적힌 값이 먼저다 — 이건 적히지
         * 않았을 때. "바탕B" 처럼 굵은 파일 하나뿐인 가족을 제목에 쓰는 책이 흔해, 파일을 보지 않으면 한 번 더 굵힌다.
         */
        private fun os2(descriptor: CTFontDescriptorRef): Pair<Int, Boolean> {
            val font = CTFontCreateWithFontDescriptor(descriptor, 12.0, null) ?: return 400 to false
            try {
                val table = CTFontCopyTable(font, kCTFontTableOS2, kCTFontTableOptionNoOptions) ?: return 400 to false
                try {
                    val length = CFDataGetLength(table)
                    val bytes = CFDataGetBytePtr(table)?.reinterpret<kotlinx.cinterop.ByteVar>() ?: return 400 to false
                    fun u8(i: Int) = bytes[i].toInt() and 0xFF
                    val weight = if (length >= 6) ((u8(4) shl 8) or u8(5)).takeIf { it in 1..1000 } ?: 400 else 400
                    val italic = length >= 64 && (u8(63) and 1) != 0
                    return weight to italic
                } finally {
                    CFRelease(table)
                }
            } finally {
                CFRelease(font)
            }
        }
    }
}

/** 64비트 FNV-1a 의 16진수. 파일 이름 · 지문에 쓴다 — 경로에 한글 · 공백 · `../` 가 섞여도 안전한 이름이 된다. */
internal fun fnv1aHex(text: String): String {
    var hash = 0xcbf29ce484222325uL
    for (b in text.encodeToByteArray()) {
        hash = hash xor (b.toULong() and 0xFFuL)
        hash *= 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0')
}
