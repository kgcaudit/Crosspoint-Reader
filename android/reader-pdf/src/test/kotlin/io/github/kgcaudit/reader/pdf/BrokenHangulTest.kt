package io.github.kgcaudit.reader.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrokenHangulTest {

    /**
     * 글자 대응표 없는 글꼴이 엔진에서 나오는 모양 그대로 만든다(좋은생각 2026년 8월호에서 잰 것): 한글은
     * 모양 번호(97 + 음절 순번), ASCII 는 1–95(공백 = U+0001, 마침표 = U+000F).
     */
    private fun broken(s: String): String = s.map { c ->
        when (c) {
            in '가'..'힣' -> (97 + (c - '가')).toChar()
            in ' '..'~' -> (c.code - 31).toChar()
            else -> c
        }
    }.joinToString("")

    @Test
    fun `a page set in a font without a character map reads as korean again`() {
        val original = "나는 오래전 잊힌 독립운동가를 찾아 세상에 알리는 일을 소명으로 여기고 있다."
        val page = broken(original)
        // 엔진이 내주는 글은 읽을 수 없는 글이라 듣기가 이 쪽을 건너뛰었다.
        assertFalse(PageText.isReadable(page))
        val fixed = BrokenHangul.repair(page)
        assertEquals(original, fixed)
        assertTrue(PageText.isReadable(fixed))
        // 글 길이는 같다 — 글자마다의 네모 · 저장한 형광펜 자리가 같은 글자를 가리킨다.
        assertEquals(page.length, fixed.length)
    }

    @Test
    fun `only the broken words change when a sound font sits on the same page`() {
        // 제목 · 이름 · 숫자는 멀쩡한 글꼴, 본문만 망가진 글꼴. "2024" 뒤의 "년" 만 망가져 있다.
        val page = "김형철 님 | 철학자\r\n90\r\n2024" + broken("년 많은 이가 무언가 부족하다고 느끼며 산다. 시간과 돈 곁에 사람이 없다고 말한다.")
        val fixed = BrokenHangul.repair(page)
        assertEquals("김형철 님 | 철학자\r\n90\r\n2024년 많은 이가 무언가 부족하다고 느끼며 산다. 시간과 돈 곁에 사람이 없다고 말한다.", fixed)
    }

    @Test
    fun `sound text with symbols or foreign letters is left alone`() {
        // 망가진 글이 아니면 한 글자도 바꾸지 않는다: 화살표 · 가운뎃점 · 영어 · 그리스 문자.
        for (sound in listOf(
            "어머니 → 누에고치 → 질경이 꽃, 이처럼 이질적인 소재를 연결하는 것은 결코 쉬운 일이 아니다.",
            "The quick brown fox jumps over the lazy dog. αβγδ εζηθ ικλμ ΑΒΓΔ ΕΖΗΘ",
            "Ωμέγα Ελληνικά ΣΣΣΣ ΦΦΦΦ ΨΨΨΨ Ώ Ά Έ Ή",
            "",
        )) assertEquals(sound, BrokenHangul.repair(sound), sound)
    }

    @Test
    fun `an english word inside a broken line stays english`() {
        // 망가진 줄 사이에 낀 진짜 영어("‘oppa’"). 소문자는 "가 · 각 …" 의 번호이기도 해서, 그대로 풀면 "갎갏갏가".
        val page = broken("사전에 등록된 ") + "‘oppa’" + broken("를 당당히 사용할 수 있기 때문이다. 이를 널리 알리는 데 큰 몫을 했다.")
        assertEquals("사전에 등록된 ‘oppa’를 당당히 사용할 수 있기 때문이다. 이를 널리 알리는 데 큰 몫을 했다.", BrokenHangul.repair(page))
    }

    @Test
    fun `words from a font numbered some other way are hidden instead of read as nonsense`() {
        // 같은 쪽에 두 체계 어느 쪽으로 풀어도 우리말이 안 되는 글꼴(쪽 머리 장식 글씨 등)이 섞였다. 억지로 풀면 "댦뗴꿹"
        // 같은 글자를 읽는다 — 본문은 되살리고 그 낱말은 소리 나지 않게 가린다.
        val table = AdobeKr.table
        fun ks(c: Char) = c in '가'..'힣' && String("$c".toByteArray(charset("EUC-KR")), charset("EUC-KR")) == "$c" &&
            "$c".toByteArray(charset("EUC-KR")).size == 2
        val junk = (2000 until 11000).asSequence().map { it.toChar() }
            .filter { c -> !ks('가' + (c.code - 97)) && table.getOrNull(c.code)?.let { ks(it) } != true }
            .take(12).toList()
        val nonsense = junk.chunked(3).joinToString("\u0001") { it.joinToString("") }
        val body = "나는 오래전 잊힌 독립운동가를 찾아 세상에 알리는 일을 소명으로 여기고 있다."
        val fixed = BrokenHangul.repair(broken(body) + "\r\n" + nonsense)
        assertEquals(body, fixed.substringBefore("\r\n"))
        val tail = fixed.substringAfter("\r\n")
        assertTrue(tail.none { it in '가'..'힣' }, tail)
        assertEquals(nonsense.length, tail.length)
    }

    /** Adobe-KR 번호 체계의 글꼴이 엔진에서 나오는 모양(표를 거꾸로 써서 만든다). */
    private fun brokenAdobe(s: String): String {
        val table = AdobeKr.table
        return s.map { c -> if (c in ' '..'~') (c.code - 31).toChar() else table.indexOf(c).also { require(it > 0) { "$c" } }.toChar() }.joinToString("")
    }

    @Test
    fun `a line in an adobe-kr font on the same page is read too, not hidden`() {
        // 좋은생각 109쪽: 본문은 유니코드 순 번호, 아래 글귀(SDGretaSans2)는 Adobe-KR 번호. 앞 규칙으로만 풀면 글귀가
        // "냖뎚댛 깾눧띎" 이 되어 가려졌고, 듣기가 그 글귀를 읽지 않았다.
        val body = "나는 오래전 잊힌 독립운동가를 찾아 세상에 알리는 일을 소명으로 여기고 있다."
        val quote = "번역은 단순히 언어를 바꾸는 것이 아니라, 한 문화를 다른 문화로 이동시키는 대담한 여정이다."
        val fixed = BrokenHangul.repair(broken(body) + "\r\n" + brokenAdobe(quote))
        assertEquals("$body\r\n$quote", fixed)
        // Adobe-KR 글꼴만 있는 쪽도(앞 규칙의 흔한 글자 비율이 낮아도) 되살린다.
        assertEquals(quote, BrokenHangul.repair(brokenAdobe(quote)))
    }

    @Test
    fun `lines without line break characters are still repaired line by line`() {
        // 폰의 PDF 엔진은 줄바꿈 글자를 주지 않는다. 본문 끝줄(유니코드 순 번호)과 아래 글귀(Adobe-KR 번호)가 붙어 오면,
        // 줄바꿈 글자로만 낱말을 끊던 때는 둘이 한 덩이로 한 체계로 풀려 "있지" 다음부터 깨졌다(좋은생각 109쪽, 폰에서).
        val body = "여기에도 누군가의 숨은 노력이 있지 않았을까."
        val quote = "번역은 단순히 언어를 바꾸는 것이 아니라, 한 문화를 다른 문화로 이동시키는 대담한 여정이다."
        val joined = broken(body) + brokenAdobe(quote)
        assertEquals(body + quote, BrokenHangul.repair(joined, listOf(0, body.length)))
        // 줄 자리를 모르면(예전) 둘 중 하나는 깨진다 — 이 시험이 무엇을 지키는지 보인다.
        assertTrue(BrokenHangul.repair(joined) != body + quote)
    }

    @Test
    fun `a lone mark between broken spaces is the broken font's own letter`() {
        // "_ 안소니 버제스" 의 "_" 는 번호 64 로 와서 "@" 로 보였다(골뱅이로 읽힘). 홀로 선 것만 푼다 — 낱말 속 숫자는 그대로.
        val line = "번역은 대담한 여정이다. _ 안소니 버제스"
        assertEquals(line, BrokenHangul.repair(brokenAdobe(line)))
        // 폰처럼 오른쪽에서 왼쪽 순서로 와서 "_" 가 망가진 글자 바로 옆에 붙어도 푼다.
        assertEquals(line.reversed(), BrokenHangul.repair(brokenAdobe(line).reversed()))
        // 멀쩡한 영문자 옆의 "@" 는 그대로(메일 주소).
        assertEquals("메일 user@mail.com 으로", BrokenHangul.repair("메일 user@mail.com 으로"))
    }

    @Test
    fun `glyph numbers that do not read as korean are not forced into hangul`() {
        // 망가진 글꼴이라도 번호 체계가 다르면(흔한 글자가 나오지 않으면) 억지로 바꾸지 않는다 — 엉뚱한 한글을
        // 읽어 주는 것보다 "읽을 글이 없다" 가 낫다.
        val other = (0 until 60).map { (3000 + it * 37).toChar() }.joinToString("\u0001")
        assertEquals(other, BrokenHangul.repair(other))
    }
}
