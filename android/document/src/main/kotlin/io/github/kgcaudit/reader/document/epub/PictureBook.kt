package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.xml.XmlEvent
import io.github.kgcaudit.reader.document.xml.XmlScanner
import java.io.Reader

/**
 * 그림만 든 EPUB(만화 · 그림책을 EPUB 으로 판 것)의 쪽들(0.50.0). 이런 책은 장마다 그림 한 장이라, 책 뷰어로 열면 여백 자르기
 * · 두 쪽 펼침 · 오→왼 넘김을 쓸 수 없다. 만화 뷰어로 열 쪽 목록을 만든다.
 *
 * @param rightToLeft OPF 의 넘기는 방향. null 은 적혀 있지 않음.
 */
data class PictureBook(val pages: List<Page>, val rightToLeft: Boolean?) {
    /** 쪽 하나: 그 그림이 든 장(진도 · 책갈피를 장 자리로 적는다)과 zip 안의 그림 경로. */
    data class Page(val spineIndex: Int, val path: String)
}

object PictureBooks {

    /**
     * 그림이 든 장에 이보다 많은 글자가 있으면 그림책이 아니다 — 그림 위에 글을 얹은 그림책 · 삽화가 든 소설을 그림만으로
     * 보이면 글이 사라진다. 쪽 번호 · 짧은 제목("제1화")은 봐준다.
     */
    const val MAX_TEXT = 40

    /** 장 하나에서 본 것: 그림(zip 경로, 나온 차례)과 보이는 글자 수(공백 뺌). */
    data class Chapter(val images: List<String>, val textLength: Int)

    /**
     * XHTML 장 하나를 훑는다. 그림은 `<img src>` 와 SVG 표지에 흔한 `<image xlink:href>`. 글자는 `<head>` · `<script>` ·
     * `<style>` 밖의 것만 센다. `data:` 그림은 꺼낼 수 없어 세지 않는다. 깨진 마크업도 읽을 수 있는 데까지 읽는다(규칙 6).
     */
    fun scan(chapterPath: String, reader: Reader): Chapter {
        val dir = Hrefs.dirOf(chapterPath)
        val images = ArrayList<String>()
        var text = 0
        var hidden = 0
        for (event in XmlScanner(reader, rawText = setOf("script", "style")).events()) {
            when (event) {
                is XmlEvent.StartElement -> {
                    if (event.isLocal("head") || event.isLocal("script") || event.isLocal("style") || event.isLocal("title")) hidden++
                    val src = when {
                        event.isLocal("img") -> event.attribute("src")
                        event.isLocal("image") -> event.attribute("href")
                        else -> null
                    }?.trim()
                    if (!src.isNullOrEmpty() && !src.startsWith("data:", ignoreCase = true)) images += Hrefs.resolve(dir, src)
                }
                is XmlEvent.EndElement ->
                    if (event.isLocal("head") || event.isLocal("script") || event.isLocal("style") || event.isLocal("title")) hidden = (hidden - 1).coerceAtLeast(0)
                is XmlEvent.Text -> if (hidden == 0) text += event.value.count { !it.isWhitespace() }
            }
        }
        return Chapter(images, text)
    }

    /**
     * 장들을 보고 그림책인지 정한다. 아니면 null.
     * - 그림이 든 장에 글이 [MAX_TEXT] 를 넘으면 아니다(글이 사라진다).
     * - 그림 없이 글만 있는 장(판권 · 작가의 말)은 열에 하나까지 봐주고 건너뛴다. 그보다 많으면 글 책이다.
     * - 그림이 둘 이상이어야 한다. 표지 한 장뿐인 소설을 그림책으로 보지 않는다.
     */
    fun decide(chapters: List<Chapter>, rightToLeft: Boolean? = null): PictureBook? {
        if (chapters.isEmpty()) return null
        val allowedText = maxOf(1, chapters.size / 10)
        var textOnly = 0
        val pages = ArrayList<PictureBook.Page>()
        chapters.forEachIndexed { i, c ->
            if (c.images.isEmpty()) {
                if (c.textLength > MAX_TEXT && ++textOnly > allowedText) return null
            } else {
                if (c.textLength > MAX_TEXT) return null
                // 같은 그림을 한 장에서 두 번 부르는 마크업(SVG 와 대체 img)은 한 쪽이다.
                for (path in c.images.distinct()) pages += PictureBook.Page(i, path)
            }
        }
        return if (pages.size >= 2) PictureBook(pages, rightToLeft) else null
    }

    /** 이 장까지 본 것으로 이미 그림책이 아님이 정해졌는가 — 글 책을 끝까지 다 읽지 않으려고. */
    fun alreadyRejected(seen: List<Chapter>, total: Int): Boolean {
        val allowedText = maxOf(1, total / 10)
        return seen.any { it.images.isNotEmpty() && it.textLength > MAX_TEXT } ||
            seen.count { it.images.isEmpty() && it.textLength > MAX_TEXT } > allowedText
    }
}
