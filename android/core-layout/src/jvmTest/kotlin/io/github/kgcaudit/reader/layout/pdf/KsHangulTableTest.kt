package io.github.kgcaudit.reader.layout.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 앱이 싣는 KS X 1001 한글 표가 JVM 의 EUC-KR 과 같다. 공통 시험은 플랫폼 문자 집합을 쓸 수 없어(iOS) 이 표로 "우리말 글자인가" 를
 * 가린다 — 표가 틀어지면 공통 시험이 틀린 기준으로 통과한다. JVM 에서만 대조할 수 있어 여기 둔다.
 */
class KsHangulTableTest {
    @Test
    fun `the bundled ks x 1001 table matches the platform euc-kr for every hangul syllable`() {
        val euc = charset("EUC-KR")
        val wrong = ('가'..'힣').filter { c ->
            val bytes = "$c".toByteArray(euc)
            val platform = bytes.size == 2 && String(bytes, euc) == "$c"
            platform != KsHangul.contains(c)
        }
        assertEquals(emptyList(), wrong.take(10), "표와 EUC-KR 이 다른 글자 ${wrong.size}개")
    }
}
