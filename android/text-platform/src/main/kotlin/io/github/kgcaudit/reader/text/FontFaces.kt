package io.github.kgcaudit.reader.text

import java.security.MessageDigest
import kotlin.math.abs

/**
 * 한 가족의 서체들에서 보통과 굵게를 고른다. 사용자 글꼴과 출판사 글꼴이 같은 규칙을 쓴다 — 둘이 다르면
 * 같은 파일 한 쌍이 넣은 방식에 따라 다른 굵기로 그려진다.
 *
 * - 기울임만 있는 가족이 아니면 바로 선 서체에서 고른다(본문을 기울임으로 조판하면 안 된다).
 * - 보통 = 400 에 가장 가까운 것(같으면 가는 쪽). 굵게 = 보통보다 굵고 600 이상인 것 중 700 에 가장 가까운 것.
 */
internal fun <T> chooseRegularAndBold(faces: List<T>, weight: (T) -> Int, italic: (T) -> Boolean): Pair<T, T?> {
    val upright = faces.filter { !italic(it) }.ifEmpty { faces }
    val regular = upright.minWith(compareBy<T> { abs(weight(it) - 400) }.thenBy { weight(it) })
    val bold = upright.filter { it !== regular && weight(it) >= 600 && weight(it) > weight(regular) }
        .minByOrNull { abs(weight(it) - 700) }
    return regular to bold
}

/** 16진수 해시 앞 [length] 자. 파일 이름에 쓴다(경로에 한글·공백·`../` 가 섞여도 안전하다). */
internal fun hexDigest(digest: ByteArray, length: Int): String = digest.joinToString("") { "%02x".format(it) }.take(length)

internal fun sha1Hex(text: String, length: Int): String =
    hexDigest(MessageDigest.getInstance("SHA-1").digest(text.toByteArray()), length)
