package io.github.kgcaudit.reader.layout.html

import io.github.kgcaudit.reader.layout.Block
import io.github.kgcaudit.reader.layout.BlockStyle
import io.github.kgcaudit.reader.layout.InlineRun
import java.io.Reader

/**
 * 평문(TXT)을 조판 입력으로 바꾼다.
 *
 * 규칙은 한 줄이다: **한 줄이 한 문단.** 한국어 TXT 소설은 거의 예외 없이 문단마다
 * 줄바꿈 하나로 적혀 있고, 빈 줄은 문단을 띄우려는 뜻이지 내용이 아니다. 여기서 빈 줄을
 * 문단으로 만들면 지면 위쪽이 빈 띠로 시작하는 페이지가 생긴다.
 *
 * 줄바꿈 글자는 텍스트에 **남겨 둔다.** 지우면 파일의 글자 오프셋과 어긋나서, 같은
 * 파일을 다시 열 때 책갈피가 밀린다. 블록이 줄바꿈을 덮지 않으므로 화면에는 보이지
 * 않는다.
 *
 * 들여쓰기는 블록에 적지 않는다(null = 사용자 설정을 쓴다). 정렬은 사용자 설정을 그대로
 * 박아 넣는다 — 조판기는 블록에 적힌 정렬만 보므로, 여기서 비우면 TXT 는 설정과 무관하게
 * 늘 왼쪽 정렬로 조판된다.
 */
object TextChapter {

    fun parse(reader: Reader, context: StyleContext = StyleContext()): Chapter =
        parse(reader.readText(), context)

    fun parse(content: String, context: StyleContext = StyleContext()): Chapter {
        val style = BlockStyle(align = context.defaultAlign)
        // \r\n 과 \r 을 \n 하나로 맞춘다. 길이가 달라지므로 오프셋 기준을 여기서 고정한다.
        val text = content.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = ArrayList<Block>()

        var cursor = 0
        while (cursor < text.length) {
            val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
            val trimmed = trimmedRange(text, cursor, lineEnd)
            if (trimmed != null) {
                blocks.add(Block.Paragraph(listOf(InlineRun(trimmed.first, trimmed.second)), style))
            }
            cursor = lineEnd + 1
        }
        return Chapter(text, blocks)
    }

    /**
     * [start, endExclusive) 에서 앞뒤 공백을 뺀 구간. 전부 공백이면 null.
     *
     * 잘라 낸 글자를 텍스트에서 지우지는 않는다 — 블록이 가리키지 않을 뿐이다. 그래야
     * 오프셋이 파일과 같은 좌표계에 남는다.
     */
    private fun trimmedRange(text: String, start: Int, endExclusive: Int): Pair<Int, Int>? {
        var from = start
        var to = endExclusive
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        return if (from < to) from to to else null
    }
}
