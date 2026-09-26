package io.github.kgcaudit.reader.pdf

/**
 * 엔진에 **글자 번호 하나씩** 물어 글자 층(글 + 글자마다의 네모)을 맞춘다.
 *
 * 쪽 전체 글(PdfRenderer 의 textContents)과 글자 번호로 고른 결과(selectContent)는 번호가 서로 다르다. 안드로이드의
 * PDF 엔진(pdfClient)은 쪽 전체 글을 넘기기 전에 ① 맨 앞뒤의 공백 · 하이픈을 잘라 내고 ② 번호 2(줄 끝 하이픈 표시)를
 * 세 글자 "-\r\n" 으로 늘린다. 고르기는 자르기 전 번호를 쓴다. 그래서 쪽 전체 글의 i 번째 글자를 "번호 i" 로 고르면
 * 그 뒤 글자들의 네모가 밀렸다 — 대응표 없는 글꼴에서는 번호 2 가 흔한 글자라 좋은생각 24쪽 · 씨네21 13쪽이 그랬고,
 * 밀린 네모로 줄 · 순서를 가르면 글자가 뒤섞여 읽혔다. 글과 네모를 같은 물음에서 받으면 어긋날 수 없다.
 *
 * @param bound 번호를 어디까지 물을지(쪽 전체 글의 길이 + 여유). 넘어가면 엔진은 빈 글을 준다.
 * @param select 번호 [from]..[to](끝 제외)를 고른 글과 첫 네모(쪽 폭 · 높이에 대한 0..1, 네모가 없으면 null).
 *   쪽 밖(잘린 앞뒤 · 끝 너머)의 번호는 빈 글이다.
 */
internal fun assembleLayer(bound: Int, select: (from: Int, to: Int) -> Pair<String, FloatArray?>?): PageText {
    val text = StringBuilder()
    val boxes = ArrayList<Float>()
    for (p in 0 until bound) {
        val (s, box) = select(p, p + 1) ?: continue
        if (s.isEmpty()) continue
        // 번호 2 는 엔진이 "-\r\n" 으로 늘려 준다. 한 글자로 되돌린다 — 대응표 없는 글꼴에서는 느낌표 같은 보통 글자이고
        // ([BrokenHangul] 이 푼다), 멀쩡한 글꼴에서는 줄 끝 하이픈이다(줄은 네모로 가르므로 "\r\n" 이 없어도 된다).
        val chars = if (s == BROKEN_WORD) BROKEN_WORD_MARK.toString() else s
        for (c in chars) {
            text.append(c)
            if (box == null || c == '\r' || c == '\n') repeat(4) { boxes += Float.NaN } else box.forEach { boxes += it }
        }
    }
    return if (text.isEmpty()) PageText.EMPTY else PageText(text.toString(), boxes.toFloatArray())
}

/** 엔진이 번호 2 대신 주는 글. */
internal const val BROKEN_WORD = "-\r\n"

/** 번호 2 를 되돌린 글자. [BrokenHangul] 이 망가진 낱말 밖에서는 하이픈으로 바꾼다. */
internal const val BROKEN_WORD_MARK = '\u0002'
