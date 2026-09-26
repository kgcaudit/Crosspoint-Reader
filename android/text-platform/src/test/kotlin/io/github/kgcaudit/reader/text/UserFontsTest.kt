package io.github.kgcaudit.reader.text

import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.layout.TextStyle
import io.github.kgcaudit.reader.text.UserFonts.ImportResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 사용자가 폰트 파일을 넣고, 고르고, 지우는 흐름. 실제 폰트를 실제 Paint 로 잰다. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UserFontsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dir: File by lazy { temp.newFolder("fonts") }
    private val fonts by lazy { UserFonts(dir) }
    private val catalog by lazy { FontCatalog(fonts) }

    private fun add(name: String): ImportResult = TestFonts.file(name).inputStream().use { fonts.import(it) }

    private fun width(key: String?, style: TextStyle = TextStyle.Default): Float {
        val spec = LayoutSpec(
            viewportWidthPx = 1080f, viewportHeightPx = 1600f, margin = Insets.all(48f), baseSizePx = 42f,
            lineHeightMultiplier = 1.5f, align = TextAlign.Justify, paragraphIndentEm = 1f,
            fontId = catalog.layoutFontId(key),
        )
        return AndroidTextMeasurer.forSpec(catalog, spec).advance(SAMPLE, 0, SAMPLE.length, style)
    }

    @Test
    fun `an added font shows up in the list and is what gets measured`() {
        val added = assertIs<ImportResult.Added>(add("olo-test-regular.ttf"))
        val family = added.families.single()
        assertEquals("Olo Test Sans", family.label)
        assertFalse(added.withoutHangul)

        val option = catalog.options().single { it.kind == FontOption.Kind.User }
        assertEquals(family.key, option.key)
        assertEquals(family.key, catalog.effectiveKey(family.key))
        // 목록에 올랐는데 재는 건 시스템 글꼴이면, 골라도 아무것도 바뀌지 않는다.
        assertNotEquals(width(FontCatalog.SANS), width(family.key))
    }

    @Test
    fun `a bold file joins its family and changes the cache key`() {
        val key = assertIs<ImportResult.Added>(add("olo-test-regular.ttf")).families.single().key
        val before = catalog.layoutFontId(key)
        assertNull(fonts.family(key)!!.bold, "굵은 파일이 없으면 합성한다")
        val fakeBold = width(key, TextStyle(bold = true))

        val joined = assertIs<ImportResult.Added>(add("olo-test-bold.ttf")).families.single()
        assertEquals(key, joined.key, "보통과 굵게가 두 가족으로 갈라졌다")
        assertEquals(1, catalog.options().count { it.kind == FontOption.Kind.User })
        assertNotNull(joined.bold)
        assertEquals(700, joined.bold!!.info.weight)
        // 굵은 글자의 폭이 바뀌었으니 캐시 키도 바뀌어야 한다. 같으면 합성 굵기로 잰 페이지가
        // 진짜 굵은 서체로 그려진다.
        assertNotEquals(before, catalog.layoutFontId(key))
        assertNotEquals(fakeBold, width(key, TextStyle(bold = true)))
    }

    @Test
    fun `adding the same file twice keeps one copy`() {
        add("olo-test-regular.ttf")
        val again = assertIs<ImportResult.Added>(add("olo-test-regular.ttf"))
        assertTrue(again.alreadyThere)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun `a font without Hangul is added with a warning`() {
        // 영문 책을 위해 영문 폰트를 넣는 건 정당하다. 거절하지 않고, 한글은 기본 글꼴로
        // 보인다고 알린다.
        val added = assertIs<ImportResult.Added>(add("olo-test-latin.ttf"))
        assertTrue(added.withoutHangul)
        assertFalse(catalog.options().single { it.kind == FontOption.Kind.User }.hasHangul)
    }

    @Test
    fun `a collection offers only its Korean family`() {
        // Noto CJK 처럼 한 파일에 여러 나라 가족이 든 TTC. 한국어 이름이 있는 가족만 올린다.
        val added = assertIs<ImportResult.Added>(add("olo-test-collection.ttc"))
        assertEquals(listOf("올로 테스트 산스"), added.families.map { it.label })
        assertTrue(dir.listFiles()!!.single().name.endsWith(".ttc"))
        assertNotEquals(width(FontCatalog.SANS), width(added.families.single().key))
    }

    @Test
    fun `broken, foreign and oversized files are refused and leave nothing behind`() {
        val regular = TestFonts.file("olo-test-regular.ttf").readBytes()
        val cases = mapOf(
            "그림" to (byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(200)) to ImportResult.Reason.NotAFont,
            "웹 폰트" to ("wOFF".toByteArray() + ByteArray(200)) to ImportResult.Reason.WebFont,
            "덜 받은 폰트" to regular.copyOf(regular.size / 2) to ImportResult.Reason.Broken,
        )
        for ((case, reason) in cases) {
            val (label, bytes) = case
            val result = fonts.import(ByteArrayInputStream(bytes))
            assertEquals(ImportResult.Rejected(reason), result, label)
        }
        assertEquals(ImportResult.Rejected(ImportResult.Reason.TooLarge), fonts.import(ByteArrayInputStream(regular), maxBytes = 1000))
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("SD card removed")
        }
        assertEquals(ImportResult.Rejected(ImportResult.Reason.Unreadable), fonts.import(failing))

        // 임시 파일이 남으면 넣기에 실패할 때마다 저장 공간이 줄어든다.
        assertTrue(dir.listFiles()!!.isEmpty(), "남은 파일: ${dir.listFiles()!!.map { it.name }}")
        assertTrue(catalog.options().none { it.kind == FontOption.Kind.User })
    }

    @Test
    fun `removing the chosen font falls back to the default and the files go`() {
        val key = assertIs<ImportResult.Added>(add("olo-test-regular.ttf")).families.single().key
        add("olo-test-bold.ttf")
        fonts.remove(key)
        assertTrue(dir.listFiles()!!.isEmpty())
        assertEquals(catalog.defaultKey, catalog.effectiveKey(key), "지운 글꼴을 고른 설정도 책은 열려야 한다")
        assertEquals(width(catalog.defaultKey), width(key))
    }

    @Test
    fun `added fonts are still there after a restart`() {
        val key = assertIs<ImportResult.Added>(add("olo-test-regular.ttf")).families.single().key
        val id = catalog.layoutFontId(key)
        val restarted = FontCatalog(UserFonts(dir))
        assertEquals(key, restarted.effectiveKey(key))
        assertEquals(id, restarted.layoutFontId(key), "재시작 후 캐시 키가 바뀌면 책마다 다시 조판한다")
    }

    @Test
    fun `a single variable font gives a real regular and bold`() {
        // Pretendard Variable·Noto Serif KR 처럼 파일 하나에 모든 굵기가 든 글꼴. 기본 인스턴스를 그대로
        // 쓰면 Noto Serif KR 은 200(아주 가늘게)으로 나오고, 굵게는 합성된다.
        val family = assertIs<ImportResult.Added>(add("olo-test-variable.ttf")).families.single()
        val pair = fonts.pair(family)
        assertNotNull(pair.bold, "굵기 축으로 굵게를 만든다")
        val statics = UserFonts(temp.newFolder("statics"))
        TestFonts.file("olo-test-regular.ttf").inputStream().use { statics.import(it) }
        TestFonts.file("olo-test-bold.ttf").inputStream().use { statics.import(it) }
        val staticPair = statics.pair(statics.families().single())
        // 축 400 은 보통 마스터, 700 은 굵은 마스터와 같은 폭이어야 한다(폭이 변하는 라틴 글자로 잰다).
        assertEquals(latinWidth(staticPair.regular), latinWidth(pair.regular))
        assertEquals(latinWidth(staticPair.bold!!), latinWidth(pair.bold!!))
        assertNotEquals(latinWidth(pair.regular), latinWidth(pair.bold!!))
    }

    private fun latinWidth(typeface: android.graphics.Typeface): Float =
        android.graphics.Paint().apply { this.typeface = typeface; textSize = 100f }.measureText("er1 er1 er1")

    private companion object {
        const val SAMPLE = "어린 왕자는 사막에서 조종사를 만났다. Chapter 1 — 1943년."
    }
}
