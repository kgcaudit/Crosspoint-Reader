package io.github.kgcaudit.reader.layout.book

import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.layout.css.CssParser

/** 책 글꼴 한 가족의 파일 하나. [path] 는 컨테이너 경로로 풀려 있다. */
data class BookFontFile(
    val path: String,
    /** `@font-face` 의 `font-weight`. 없으면 null — 폰트 파일의 굵기를 본다. */
    val weight: Int? = null,
    val italic: Boolean? = null,
)

/** `@font-face` 로 선언된 한 가족. [name] 은 CSS 에 적힌 이름(소문자). */
data class BookFontFamily(val name: String, val files: List<BookFontFile>)

/**
 * 책에 든 글꼴(출판사 글꼴)의 목록. [TextStyle.face][io.github.kgcaudit.reader.layout.TextStyle.face]
 * 가 이 목록의 번호(1부터)다.
 *
 * **실제로 쓰이는 가족만** 넣는다. 책은 흔히 쓰지도 않는 글꼴을 선언해 두는데(삼체: 8개 선언),
 * 쓰이지 않는 것까지 꺼내면 한 권에 수십 MB 를 캐시에 푼다. "쓰인다" 는 어떤 규칙의
 * `font-family` 목록에서 **첫 번째로 책에 있는** 이름이라는 뜻이다 — 조판이 고르는 것과 같다.
 */
class BookFontTable(val families: List<BookFontFamily>) {

    private val byName: Map<String, Int> = families.withIndex().associate { (i, f) -> f.name to i + 1 }

    val isEmpty: Boolean get() = families.isEmpty()

    /** 후보 이름들 중 처음으로 이 책에 있는 가족의 번호. 없으면 0(본문 글꼴). */
    fun faceFor(names: List<String>): Int = names.firstNotNullOfOrNull { byName[it.lowercase()] } ?: 0

    /** 번호 → 가족. 0 이나 범위 밖이면 null. */
    operator fun get(face: Int): BookFontFamily? = families.getOrNull(face - 1)

    companion object {
        val EMPTY = BookFontTable(emptyList())

        /**
         * 책의 스타일시트를 **모두** 읽어 만든다. 챕터별로 만들면 먼저 연 챕터에 따라 번호가 달라지고,
         * 번호는 페이지 캐시에 남으므로 다음에 열 때 다른 글꼴로 그리게 된다.
         *
         * 못 읽는 CSS 는 건너뛴다(글꼴 몇 개를 잃을 뿐 책은 열린다). 챕터 안의 `<style>` 에 적은
         * `@font-face` 는 보지 않는다 — 실제 책에서 드물고, 보려면 모든 챕터를 미리 훑어야 한다.
         */
        suspend fun load(document: ReflowDocument): BookFontTable {
            val declared = LinkedHashMap<String, MutableList<BookFontFile>>()
            val lists = ArrayList<List<String>>()
            for (path in document.stylesheets()) {
                val css = runCatching { document.openResource(path)?.use { String(it.readBytes(), Charsets.UTF_8) } }
                    .getOrNull() ?: continue
                val sheet = CssParser.parse(css.removePrefix("﻿"))
                for (face in sheet.fontFaces) {
                    val file = BookFontFile(document.resolveHref(path, face.src), face.weight, face.italic)
                    declared.getOrPut(face.family) { ArrayList() }.add(file)
                }
                sheet.rules.mapNotNullTo(lists) { it.declarations.fontFamilies }
            }
            if (declared.isEmpty()) return EMPTY
            val used = lists.mapNotNullTo(HashSet()) { list -> list.firstOrNull { it in declared } }
            return BookFontTable(
                declared.filterKeys { it in used }.map { (name, files) -> BookFontFamily(name, files.distinct()) },
            )
        }
    }
}
