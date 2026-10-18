package io.github.kgcaudit.reader.document

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 안드로이드에서만 깨지는 정규식을 PC 시험에서 잡는다.
 *
 * 안드로이드의 정규식 엔진(ICU)은 PC(JVM)보다 엄격하다: 문자 묶음 `[...]` 밖의 짝 없는 `}` · `]` · `{` 를 문법 오류로 본다.
 * JVM 은 글자 그대로 받아 시험이 모두 통과했는데, 0.33.0 은 휴대폰에서 켜자마자 죽었다 — 만화 이름 읽기(`ComicName`)의
 * `\{[^}]*}` 가 처음 쓰이는 순간(서재가 작품을 묶을 때) 클래스 초기화가 실패했다. 시험은 기기 없이 돌므로 원본의 정규식
 * 글자를 직접 읽어 본다.
 */
class RegexPortabilityTest {

    /** 문자 묶음 밖의 짝 없는 괄호. 고쳐야 할 곳 목록(빈 목록이면 통과). */
    internal fun problems(pattern: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var classDepth = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> { i += 2; continue }
                classDepth > 0 -> when (c) {
                    '[' -> classDepth++
                    ']' -> classDepth--
                }
                c == '[' -> {
                    classDepth = 1
                    // "[]" · "[^]" 의 첫 "]" 는 글자다(JVM). ICU 도 같지만 쓰지 않는 편이 안전하다 — 그대로 둔다.
                }
                c == '{' -> {
                    val quantifier = Regex("""\{\d+(,\d*)?\}""").matchAt(pattern, i)
                    if (quantifier == null) out += "unescaped { at $i" else { i += quantifier.value.length; continue }
                }
                c == '}' -> out += "unescaped } at $i"
                c == ']' -> out += "unescaped ] at $i"
            }
            i++
        }
        return out
    }

    @Test
    fun `the check itself catches the pattern that crashed 0_33_0`() {
        assertTrue(problems("""\([^)]*\)|\[[^\]]*]|\{[^}]*}""").isNotEmpty())
        assertEquals(emptyList(), problems("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}"""))
        assertEquals(emptyList(), problems("""(?<![\d.])(\d{1,4}(?:\.\d+)?)\s*$"""))
        assertEquals(emptyList(), problems("""[\[(【{][^\])】}]*"""))
    }

    @Test
    fun `no regex literal in the app has a brace or bracket android cannot read`() {
        val root = File("..").canonicalFile
        val sources = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && "${File.separator}src${File.separator}main${File.separator}" in it.path }
            .toList()
        assertTrue(sources.size > 50, "원본을 찾지 못했다: $root")
        val literal = Regex("""Regex\(\s*"{3}(.*?)"{3}""", RegexOption.DOT_MATCHES_ALL)
        val found = sources.flatMap { file ->
            literal.findAll(file.readText()).flatMap { m -> problems(m.groupValues[1]).map { "${file.relativeTo(root)}: ${m.groupValues[1]} — $it" } }
        }
        assertEquals(emptyList(), found)
    }
}
