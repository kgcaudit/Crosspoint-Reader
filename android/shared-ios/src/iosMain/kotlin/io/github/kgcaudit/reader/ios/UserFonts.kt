@file:OptIn(ExperimentalForeignApi::class)

package io.github.kgcaudit.reader.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFCharacterSetIsLongCharacterMember
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFURLCreateWithFileSystemPath
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFURLPOSIXPathStyle
import platform.CoreText.CTFontCopyCharacterSet
import platform.CoreText.CTFontCreateWithFontDescriptor
import platform.CoreText.CTFontDescriptorCopyLocalizedAttribute
import platform.CoreText.CTFontDescriptorRef
import platform.CoreText.CTFontManagerCreateFontDescriptorsFromURL
import platform.CoreText.kCTFontFamilyNameAttribute
import platform.Foundation.CFBridgingRelease
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * 사용자가 넣은 글꼴(안드로이드 `UserFonts` 의 짝). 파일은 앱이 정한 폴더([directory])에 앱이 지은 이름으로 둔다 — 조판 설정의
 * 글꼴 ID 는 `user:<그 이름>` 이다. 파일을 바꿔 쓰지 않으므로(새로 넣으면 새 이름) 이름이 곧 폭의 지문이다: 같은 이름이면
 * 같은 폭, 캐시 키가 갈라질 일이 없다.
 */
object UserFonts {
    /** 넣은 글꼴이 있는 폴더. 앱이 시작할 때 Swift 가 정한다. null 이면 사용자 글꼴은 없다(모두 휴대폰 글꼴). */
    var directory: String? = null

    const val SYSTEM_FONT_ID = "ios-system"
    private const val PREFIX = "user:"

    /** 64MB. 안드로이드와 같다 — 이보다 큰 "글꼴" 은 대개 잘못 고른 파일이고, 읽는 데 오래 걸린다. */
    const val MAX_BYTES = 64L * 1024 * 1024

    fun fontId(fileName: String?): String = if (fileName.isNullOrEmpty()) SYSTEM_FONT_ID else PREFIX + fileName

    /** 글꼴 ID 가 가리키는 파일 경로. 휴대폰 글꼴 · 모르는 ID · 폴더를 모르면 null. */
    fun path(fontId: String): String? {
        if (!fontId.startsWith(PREFIX)) return null
        val name = fontId.removePrefix(PREFIX)
        // 이름에 경로가 섞이면(손으로 고친 설정) 폴더 밖 파일을 읽게 된다.
        if (name.isEmpty() || name.contains('/')) return null
        return directory?.let { "$it/$name" }
    }

    /**
     * 파일의 첫 글꼴 모양. 없는 파일 · 글꼴이 아닌 파일 · 깨진 파일이면 null — 부르는 쪽이 휴대폰 글꼴로 물러선다.
     * 부르는 쪽이 CFRelease 한다.
     */
    internal fun descriptor(path: String): CTFontDescriptorRef? {
        val cfPath = CFStringCreateWithCString(null, path, kCFStringEncodingUTF8) ?: return null
        val url = CFURLCreateWithFileSystemPath(null, cfPath, kCFURLPOSIXPathStyle, false)
        CFRelease(cfPath)
        url ?: return null
        val array = CTFontManagerCreateFontDescriptorsFromURL(url)
        CFRelease(url)
        array ?: return null
        return try {
            if (CFArrayGetCount(array) < 1) null
            else CFArrayGetValueAtIndex(array, 0)?.let { CFRetain(it) } as CTFontDescriptorRef?
        } finally {
            CFRelease(array)
        }
    }

    /** 검사 결과. [label] 은 목록에 보일 이름(가족 이름, 한국어 이름이 있으면 그것). */
    class Check(val result: Result, val label: String?, val hasHangul: Boolean)

    enum class Result { Ok, NotAFont, WebFont, Broken, TooLarge, Unreadable }

    /**
     * 넣기 전에 본다. 순서가 뜻이 있다: 크기(읽기 전에 거른다) → 머리 4바이트(웹 글꼴은 "글꼴이 아님" 이 아니라 따로 알린다 —
     * 같은 글꼴의 TTF 를 고르면 된다고 말해 줄 수 있다) → CoreText 로 실제로 읽어 보기(머리만 맞고 속이 깨진 파일).
     */
    fun check(path: String): Check {
        val size = fileSize(path) ?: return Check(Result.Unreadable, null, false)
        if (size > MAX_BYTES) return Check(Result.TooLarge, null, false)
        val head = readHead(path) ?: return Check(Result.Unreadable, null, false)
        when (head) {
            "wOFF", "wOF2" -> return Check(Result.WebFont, null, false)
            "OTTO", "true", "ttcf", SFNT_VERSION_1 -> Unit
            else -> return Check(Result.NotAFont, null, false)
        }
        val desc = descriptor(path) ?: return Check(Result.Broken, null, false)
        try {
            val font = CTFontCreateWithFontDescriptor(desc, 12.0, null) ?: return Check(Result.Broken, null, false)
            val hangul = try {
                CTFontCopyCharacterSet(font)?.let { set ->
                    try { CFCharacterSetIsLongCharacterMember(set, HANGUL_GA) } finally { CFRelease(set) }
                } ?: false
            } finally {
                CFRelease(font)
            }
            val name = CTFontDescriptorCopyLocalizedAttribute(desc, kCTFontFamilyNameAttribute, null)
            val label = (CFBridgingRelease(name) as? String)?.takeIf { it.isNotBlank() }
            return Check(Result.Ok, label, hangul)
        } finally {
            CFRelease(desc)
        }
    }

    /** 0x00010000(TrueType) — 글자가 아니라 숫자라 4바이트를 그대로 글자로 옮긴 모양으로 비교한다. */
    private val SFNT_VERSION_1 = charArrayOf(0.toChar(), 1.toChar(), 0.toChar(), 0.toChar()).concatToString()

    /** "가". 한글이 없는 글꼴은 넣되 알린다 — 한글은 휴대폰 글꼴로 보인다. */
    private const val HANGUL_GA: UInt = 0xAC00u

    private fun fileSize(path: String): Long? {
        val f = fopen(path, "rb") ?: return null
        return try {
            if (fseek(f, 0, SEEK_END) != 0) null else ftell(f).takeIf { it >= 0 }?.toLong()
        } finally {
            fclose(f)
        }
    }

    private fun readHead(path: String): String? {
        val f = fopen(path, "rb") ?: return null
        return try {
            val bytes = ByteArray(4)
            val read = bytes.usePinned { fread(it.addressOf(0), 1.convert(), 4.convert(), f) }
            if (read.toInt() < 4) null else CharArray(4) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
        } finally {
            fclose(f)
        }
    }
}
