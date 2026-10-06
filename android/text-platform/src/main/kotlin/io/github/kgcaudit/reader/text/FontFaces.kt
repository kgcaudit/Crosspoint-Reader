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

/**
 * 짝짓기(보통 · 굵게 고르기)와 합성 굵게 판단에 쓰는 굵기. 가변 폰트는 보통(400)을 낼 수 있으면 400 이고, 낼 수
 * 없으면(굵기 축이 500~900 처럼 한쪽뿐) 파일이 적은 기본 굵기(OS/2)다.
 *
 * 사용자 글꼴과 출판사 글꼴이 이 하나를 쓴다. 출판사 글꼴만 `400.coerceIn(축)`(축 끝 값)을 쓰던 때는 같은 가변 파일이
 * 넣은 방식에 따라 다른 굵기로 읽혀 한쪽에서만 합성 굵게가 붙었다(축 500~900, 파일 굵기 900: 출판사 쪽은 500 → 합성). 기준을 사용자 글꼴 쪽으로 둔 이유: 넣어 둔
 * 사용자 글꼴의 가족 짝(어느 파일이 보통인가 — 목록 이름 · 설정 키가 거기서 나온다)이 이미 이 규칙으로 정해져 있어,
 * 바꾸면 사용자가 고른 글꼴이 다른 파일로 바뀔 수 있다. 축 끝 값은 우리가 고른 값이지 글꼴이 밝힌 굵기가 아니기도 하다.
 */
internal fun nominalWeight(osWeight: Int, variableWeights: IntRange?): Int =
    if (variableWeights != null && 400 in variableWeights) 400 else osWeight

/** 16진수 해시 앞 [length] 자. 파일 이름에 쓴다(경로에 한글·공백·`../` 가 섞여도 안전하다). */
internal fun hexDigest(digest: ByteArray, length: Int): String = digest.joinToString("") { "%02x".format(it) }.take(length)

internal fun sha1Hex(text: String, length: Int): String =
    hexDigest(MessageDigest.getInstance("SHA-1").digest(text.toByteArray()), length)
