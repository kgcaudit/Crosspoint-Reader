package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.extensionOf

/**
 * 만화로 볼 수 있는 압축의 확장자 — 서재 훑기 · 살피기 · 압축 속 권 펼치기가 모두 이 목록을 본다. 곳마다 따로 적던 때는
 * 0.49.0 에서 rar · 7z 를 훑기에만 더하고 압축 속 권 목록에는 빠뜨려, zip 으로 묶은 "1권.rar · 2권.7z" 가 권으로 펼쳐지지
 * 않고 묶음째 "만화 아님" 이 됐다.
 *
 * 데이터 층의 SQL(`ComicDao`)은 글로 적힌 목록이라 이것을 참조하지 못한다 — 시험이 둘이 같은지 본다.
 */
object ArchiveExtensions {

    /** 이름만으로 만화라고 말하는 압축. 열지 못해도 서재에서 숨기지 않는다. */
    val COMIC: Set<String> = setOf("cbz", "cbr", "cb7", "cbt")

    /**
     * 이름이 만화라고 말하지 않는 압축 — 살펴서 그림만 들었을 때만 만화다(0.49.0 부터 rar · 7z 도). 만화를 cbr 로 바꾸지 않고
     * 받은 그대로(.rar · .7z) 두는 사람이 많은데, 안 보면 그 만화들이 서재에 아예 없었다.
     */
    val PLAIN: Set<String> = setOf("zip", "rar", "7z")

    val ALL: Set<String> = COMIC + PLAIN

    /** 만화일 수 있는 압축 이름인가. 점 없는 "zip" · 점으로 시작하는 ".zip" 은 아니다. */
    fun isArchiveName(name: String): Boolean = extensionOf(name) in ALL
}
