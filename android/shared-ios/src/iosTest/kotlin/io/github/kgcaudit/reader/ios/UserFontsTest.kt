@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.TextStyle
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFURLCopyFileSystemPath
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFURLPOSIXPathStyle
import platform.CoreText.CTFontCopyAttribute
import platform.CoreText.CTFontCreateWithName
import platform.CoreText.kCTFontURLAttribute
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserFontsTest {
    private val dir = NSTemporaryDirectory() + "olo-user-fonts-test"

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        UserFonts.directory = dir
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
        UserFonts.directory = null
    }

    private val body = TextStyle()
    private val sample = "iiiiiiiiii 가나다라마"

    @Test
    fun `a user font file changes widths so the chosen font really sets the type`() {
        // 고정폭 Courier 는 i 가 m 만큼 넓다. 시스템 글꼴과 폭이 같다면 글꼴을 골라도 아무것도 바뀌지 않은 것이다.
        val courier = copySystemFont("Courier", "courier.ttf")
        val system = CoreTextMeasurer(18f)
        val user = CoreTextMeasurer(18f, fontFile = courier)
        assertTrue(user.usesFontFile)
        assertNotEquals(system.advance("iiiiiiiiii", 0, 10, body), user.advance("iiiiiiiiii", 0, 10, body))
        // 한글이 없는 글꼴에서도 한글은 0 폭이 아니라 시스템 글꼴로 채워진다(그리지 못한 빈칸이 아니다).
        assertTrue(user.advance("가나다", 0, 3, body) > 18f * 2)
    }

    @Test
    fun `the layout spec font id picks the font file so the cache key and the measured font agree`() {
        copySystemFont("Courier", "courier.ttf")
        val id = UserFonts.fontId("courier.ttf")
        val spec = spec(id)
        assertTrue(CoreTextMeasurer.forSpec(spec).usesFontFile)
        assertFalse(CoreTextMeasurer.forSpec(spec(UserFonts.SYSTEM_FONT_ID)).usesFontFile)
        // 글꼴이 다르면 캐시 키도 달라야 한다 — 같으면 바꾼 뒤에도 옛 글꼴로 짠 쪽이 보인다.
        assertNotEquals(spec.cacheKey, spec(UserFonts.SYSTEM_FONT_ID).cacheKey)
    }

    @Test
    fun `a missing or broken font file falls back to the system font instead of failing the book`() {
        val system = CoreTextMeasurer(18f).advance(sample, 0, sample.length, body)
        val missing = CoreTextMeasurer(18f, fontFile = "$dir/none.ttf")
        val broken = write("broken.ttf", byteArrayOf(0, 1, 0, 0) + ByteArray(200) { 7 })
        val garbage = CoreTextMeasurer(18f, fontFile = broken)
        assertFalse(missing.usesFontFile)
        assertFalse(garbage.usesFontFile)
        assertEquals(system, missing.advance(sample, 0, sample.length, body))
        assertEquals(system, garbage.advance(sample, 0, sample.length, body))
    }

    @Test
    fun `a font id with a path in it never reads outside the font folder`() {
        assertNull(UserFonts.path("user:../../etc/passwd"))
        assertNull(UserFonts.path("user:"))
        assertNull(UserFonts.path(UserFonts.SYSTEM_FONT_ID))
        assertEquals("$dir/a.ttf", UserFonts.path(UserFonts.fontId("a.ttf")))
    }

    @Test
    fun `checking a real font gives its family name and whether it has hangul`() {
        val courier = UserFonts.check(copySystemFont("Courier", "c.ttf"))
        assertEquals(UserFonts.Result.Ok, courier.result)
        assertNotNull(courier.label)
        assertFalse(courier.hasHangul)
        // 산돌고딕은 TTC(글꼴 모음) — 모음 파일도 넣을 수 있어야 한다.
        val gothic = UserFonts.check(copySystemFont("AppleSDGothicNeo-Regular", "g.ttc"))
        assertEquals(UserFonts.Result.Ok, gothic.result)
        assertTrue(gothic.hasHangul)
    }

    @Test
    fun `broken inputs are refused with a reason the person can act on`() {
        // 웹 글꼴은 "글꼴이 아님" 이 아니다 — 같은 글꼴의 TTF 를 고르라고 말해 줄 수 있다.
        assertEquals(UserFonts.Result.WebFont, UserFonts.check(write("w.woff", "wOFF".encodeToByteArray() + ByteArray(64))).result)
        assertEquals(UserFonts.Result.WebFont, UserFonts.check(write("w.woff2", "wOF2".encodeToByteArray() + ByteArray(64))).result)
        assertEquals(UserFonts.Result.NotAFont, UserFonts.check(write("a.txt", "hello, not a font".encodeToByteArray())).result)
        // 머리만 TrueType 이고 속은 깨진 파일(내려받다 끊긴 파일).
        assertEquals(UserFonts.Result.Broken, UserFonts.check(write("cut.ttf", byteArrayOf(0, 1, 0, 0) + ByteArray(40))).result)
        assertEquals(UserFonts.Result.Unreadable, UserFonts.check("$dir/nothing-here.ttf").result)
        assertEquals(UserFonts.Result.Unreadable, UserFonts.check(write("tiny.ttf", byteArrayOf(0, 1))).result)
    }

    private fun spec(fontId: String) = LayoutSpec(
        viewportWidthPx = 360f, viewportHeightPx = 640f, margin = Insets.all(24f), baseSizePx = 18f,
        lineHeightMultiplier = 1.6f, align = TextAlign.Justify, fontId = fontId,
    )

    /** 시뮬레이터의 시스템 글꼴 파일을 넣은 글꼴 폴더로 복사한다(실제 사용자가 넣는 것과 같은 파일). */
    private fun copySystemFont(postScriptName: String, as_: String): String {
        val cfName = CFStringCreateWithCString(null, postScriptName, kCFStringEncodingUTF8)
        val font = CTFontCreateWithName(cfName, 12.0, null)!!
        CFRelease(cfName)
        @Suppress("UNCHECKED_CAST")
        val url = CTFontCopyAttribute(font, kCTFontURLAttribute) as CFURLRef
        CFRelease(font)
        val path = CFBridgingRelease(CFURLCopyFileSystemPath(url, kCFURLPOSIXPathStyle)) as String
        CFRelease(url)
        val target = "$dir/$as_"
        NSFileManager.defaultManager.removeItemAtPath(target, null)
        assertTrue(NSFileManager.defaultManager.copyItemAtPath(path, target, null), "copy $path")
        return target
    }

    private fun write(name: String, bytes: ByteArray): String {
        val path = "$dir/$name"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile(path, true))
        return path
    }
}
