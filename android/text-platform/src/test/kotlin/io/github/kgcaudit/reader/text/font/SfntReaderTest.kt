package io.github.kgcaudit.reader.text.font

import io.github.kgcaudit.reader.text.TestFonts
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 폰트 머리 판독. 순수 JVM — 안드로이드가 필요 없다. */
class SfntReaderTest {

    private fun bytes(name: String) = TestFonts.file(name).readBytes()

    @Test
    fun `a regular and a bold file of one family are told apart by weight`() {
        // 사용자가 보통·굵게 파일을 따로 넣어도 한 가족으로 묶여야 한다. 가족 이름이 같고
        // 굵기만 달라야 짝이 지어진다.
        val regular = SfntReader.read(bytes("olo-test-regular.ttf")).single()
        val bold = SfntReader.read(bytes("olo-test-bold.ttf")).single()
        assertEquals("Olo Test Sans", regular.family)
        assertEquals(regular.family, bold.family)
        assertEquals(400, regular.weight)
        assertEquals(700, bold.weight)
        assertFalse(regular.italic)
    }

    @Test
    fun `a font without Hangul is recognised as such`() {
        // Paint.hasGlyph 는 대체 글꼴까지 뒤져 "있다" 고 답한다. 직접 cmap 을 봐야 한다.
        assertTrue(SfntReader.read(bytes("olo-test-regular.ttf")).single().hasHangul)
        assertFalse(SfntReader.read(bytes("olo-test-latin.ttf")).single().hasHangul)
    }

    @Test
    fun `a collection lists every face with its Korean name`() {
        // 0번은 한국어 이름이 있고 cmap 이 형식 12 뿐이다. 1번은 영문 폰트.
        val faces = SfntReader.read(bytes("olo-test-collection.ttc"))
        assertEquals(listOf(0, 1), faces.map { it.index })
        val korean = faces[0]
        assertEquals("Olo Test Sans", korean.family, "짝짓기 이름은 영어 이름")
        assertEquals("올로 테스트 산스", korean.label, "보이는 이름은 한국어 이름")
        assertTrue(korean.hasKoreanName)
        assertTrue(korean.hasHangul, "형식 12 cmap 에서 한글을 찾아야 한다")
        assertFalse(faces[1].hasHangul)
        assertFalse(faces[1].hasKoreanName)
    }

    @Test
    fun `a variable font reports its weight range and a static one does not`() {
        // 가변 폰트를 그냥 읽으면 기본 인스턴스로 그려진다. 범위를 알아야 보통·굵게를 축으로 고른다.
        assertEquals(400..700, SfntReader.read(bytes("olo-test-variable.ttf")).single().variableWeights)
        assertEquals(null, SfntReader.read(bytes("olo-test-regular.ttf")).single().variableWeights)
    }

    @Test
    fun `a web font is refused with its own reason`() {
        // 책에서 뽑은 WOFF 를 넣는 사람이 있다. "폰트가 아닙니다" 보다 정확한 말을 해 줘야 한다.
        for (tag in listOf("wOFF", "wOF2")) {
            val file = tag.toByteArray() + ByteArray(60)
            assertEquals(FontFormatException.Reason.Woff, assertFailsWith<FontFormatException> { SfntReader.read(file) }.reason)
        }
    }

    @Test
    fun `files that are not fonts are refused, not crashed on`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(100)
        assertEquals(FontFormatException.Reason.NotAFont, assertFailsWith<FontFormatException> { SfntReader.read(png) }.reason)
        assertEquals(FontFormatException.Reason.NotAFont, assertFailsWith<FontFormatException> { SfntReader.read(ByteArray(3)) }.reason)
    }

    @Test
    fun `a truncated or scrambled font is refused as broken`() {
        // 덜 받은 파일. 머리는 멀쩡하고 표가 파일 끝을 넘는다. 범위 확인이 없으면
        // ArrayIndexOutOfBounds 로 앱이 죽는다.
        val whole = bytes("olo-test-regular.ttf")
        // 마지막 몇 바이트는 표 정렬용 채움일 수 있어 잘려도 무해하다. 64바이트면 실제 데이터다.
        for (cut in listOf(12, 100, 2_000, whole.size / 2, whole.size - 64)) {
            val e = assertFailsWith<FontFormatException>("$cut 바이트") { SfntReader.read(whole.copyOf(cut)) }
            assertEquals(FontFormatException.Reason.Broken, e.reason, "$cut 바이트")
        }
        // 머리 뒤를 무작위로 뒤섞어도 예외는 FontFormatException 하나뿐이어야 한다.
        val random = Random(7)
        repeat(200) {
            val scrambled = whole.copyOf()
            repeat(40) { scrambled[12 + random.nextInt(600)] = random.nextInt().toByte() }
            runCatching { SfntReader.read(scrambled) }.exceptionOrNull()?.let {
                assertTrue(it is FontFormatException, "뒤섞인 폰트에서 ${it::class.simpleName}: ${it.message}")
            }
        }
    }
}
