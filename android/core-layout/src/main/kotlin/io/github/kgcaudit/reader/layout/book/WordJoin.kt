package io.github.kgcaudit.reader.layout.book

/**
 * 어절 쉼 줄이기(듣기)의 세기. 음성 엔진은 띄어쓰기마다 조금씩 쉬는데, 엔진마다 그 쉼이 길어 "어절마다
 * 끊겨 읽힌다" 고 들린다. 엔진에는 쉼 길이를 정하는 설정이 없어, 쉬는 자리(띄어쓰기) 자체를 줄여 넘긴다.
 *
 * 이름(저장값)은 처음 것(Off · Light)을 그대로 둔다 — 바꾸면 저장해 둔 선택이 풀린다. 0.19.0 의 "강하게"(두 글자 이하
 * 어절까지 붙임)는 폰에서 들어 보고 뺐다(2026-09-26 사용자 결정). 저장값 "Strong" 은 읽을 때 기본값(일반)이 된다.
 */
enum class WordJoin(val label: String) {
    Off("일반"),

    /** 뜻이 확실히 이어지는 곳만 붙인다: 의존명사 · 관형사 · 보조용언. */
    Light("속독"),
}

/**
 * 엔진에 넘길 글에서 띄어쓰기 몇 곳을 지운다. **화면의 글은 바꾸지 않는다** — 이 글은 엔진에만 간다.
 *
 * 붙이지 않는 곳: 문장부호 뒤 · 여는 따옴표 · 괄호 앞(거기는 쉬어야 뜻이 산다), 한글이 아닌 어절(영어 낱말을 붙이면
 * 한 낱말로 읽는다). [text] 는 [speakable] 로 이미 공백을 한 칸씩으로 정리한 글이라고 본다.
 */
fun joinWords(text: String, level: WordJoin): String {
    if (level == WordJoin.Off || text.isBlank()) return text
    val words = text.split(' ').filter { it.isNotEmpty() }
    return joinLight(words).joinToString(" ")
}

/** 엔진이 쉬는 자리의 수(띄어쓰기). 비교 판에 "쉬는 자리 7" 로 보인다. */
fun pauseCount(text: String): Int = text.trim().count { it == ' ' }

private fun joinLight(words: List<String>): List<String> {
    val out = ArrayList<String>()
    var glueNext = false
    for (w in words) {
        val prev = out.lastOrNull()
        val join = prev != null && canJoin(prev, w) && (glueNext || isDependent(w) || isAuxiliary(prev, w))
        if (join) out[out.size - 1] = prev + w else out += w
        // 관형사 · 짧은 부사는 뒤 어절에 붙는다("그 사람" · "더 미친").
        glueNext = w in PRENOUNS
    }
    return out
}

/** 경계에서 붙여도 되는가: 앞 어절이 한글로 끝나고(문장부호 · 닫는 따옴표 없이) 뒤 어절이 한글로 시작한다. */
private fun canJoin(prev: String, next: String): Boolean = isHangul(prev.last()) && isHangul(next.first())

/** 의존명사로 시작하는 어절("수" · "것인지" · "때문에" · "듯"). 앞 어절에 붙는다("알 수" → "알수"). */
private fun isDependent(word: String): Boolean {
    val stem = word.trimEnd { !isHangul(it) }
    return DEPENDENTS.any { d -> stem == d || (stem.startsWith(d) && stem.substring(d.length) in AFTER_DEPENDENT) }
}

/** 보조용언: "-고 싶다" · "-지 않다" · "-어 주다" · "-고 있다". 앞 어절의 끝 글자와 짝이 맞을 때만. */
private fun isAuxiliary(prev: String, word: String): Boolean {
    val end = prev.last()
    return AUXILIARIES.any { (head, ends) -> word.startsWith(head) && end in ends }
}

private fun isHangul(c: Char): Boolean = c in '가'..'힣'

private val DEPENDENTS = listOf("때문", "만큼", "대로", "것", "수", "때", "데", "줄", "뿐", "듯", "채", "척", "적", "바", "터", "김")

/** 의존명사 뒤에 붙어 한 어절을 이루는 조사 · 서술격 조사. 이 밖이면 우연히 같은 글자로 시작하는 낱말("수학을")이다. */
private val AFTER_DEPENDENT = setOf(
    "", "이", "가", "을", "를", "은", "는", "에", "에서", "으로", "로", "도", "만", "의", "과", "와", "까지", "부터", "처럼",
    "이다", "이었다", "인지", "인데", "이고", "이며", "일", "임", "입니다", "이에요", "이야", "이지", "이라고", "이라는",
    "에는", "에도", "이나", "이란", "이라", "이면", "이니", "이어서", "이었고", "이었는지", "에게", "이었던", "인가",
    // "듯한" · "척했다" · "듯이" 처럼 의존명사에 붙는 말.
    "이", "한", "하다", "하게", "해서", "했다", "하며", "하고",
)

private val PRENOUNS = setOf(
    "그", "이", "저", "이런", "그런", "저런", "어느", "무슨", "몇", "모든", "온", "새", "헌", "첫", "한", "두", "세", "네",
    "다른", "각", "더", "안", "못", "잘", "좀", "또",
)

/** (보조용언의 첫 글자, 그 앞 어절이 끝날 수 있는 글자들). */
private val AUXILIARIES = listOf(
    "싶" to "고",
    "않" to "지",
    "못하" to "지",
    "있" to "고어아여워와해",
    "없" to "고",
    "계" to "고",
    "주" to "어아여워와해",
    "드리" to "어아여워와해",
    "보" to "어아여워와해",
    "버리" to "어아여워와해",
    "버렸" to "어아여워와해",
    "버린" to "어아여워와해",
    "놓" to "어아여워와해",
    "두" to "어아여워와해",
)
