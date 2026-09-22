package io.github.kgcaudit.reader.document

/**
 * 책 안의 한 지점.
 *
 * 이 타입이 EPUB·TXT·PDF를 하나로 묶는 지점이다. 책갈피·이어읽기·목차 이동이
 * 전부 [Locator]만 다루므로, 포맷이 늘어도 그 화면들은 손대지 않는다.
 *
 * 리플로우 문서가 페이지 번호가 아니라 **글자 오프셋**을 쓰는 이유: 글꼴·크기·여백·
 * 화면 크기가 바뀌면 페이지 경계가 통째로 달라진다. 화면 회전이 일상인 안드로이드에서
 * 페이지 번호로 진도를 저장하면 회전 한 번에 엉뚱한 곳으로 간다. 글자 오프셋은
 * 재조판과 무관하게 같은 글자를 가리킨다.
 */
sealed interface Locator {

    /**
     * 리플로우 문서(EPUB·TXT)의 위치.
     *
     * @param spine 챕터 인덱스(TXT는 항상 0)
     * @param charOffset 그 챕터의 정규화 텍스트에서 0부터 센 글자(코드포인트가 아닌
     *   UTF-16 코드 유닛) 오프셋. 페이지 캐시의 `.txt`와 같은 좌표계를 쓴다.
     */
    data class Reflow(val spine: Int, val charOffset: Int) : Locator {
        init {
            require(spine >= 0) { "spine must be >= 0, was $spine" }
            require(charOffset >= 0) { "charOffset must be >= 0, was $charOffset" }
        }
    }

    /**
     * 고정 페이지 문서(PDF)의 위치.
     *
     * 페이지 안의 위치는 천분율 정수로 둔다. 줌·회전 후 보던 자리를 되찾는 데
     * 천분율이면 충분하고, 부동소수와 달리 직렬화 왕복이 정확하며 로케일에 휘둘리지
     * 않는다.
     *
     * @param page 0부터 센 페이지 번호
     * @param xPermille 페이지 폭 기준 가로 위치 0..1000
     * @param yPermille 페이지 높이 기준 세로 위치 0..1000
     */
    data class FixedPage(
        val page: Int,
        val xPermille: Int = 0,
        val yPermille: Int = 0,
    ) : Locator {
        init {
            require(page >= 0) { "page must be >= 0, was $page" }
            require(xPermille in 0..1000) { "xPermille must be in 0..1000, was $xPermille" }
            require(yPermille in 0..1000) { "yPermille must be in 0..1000, was $yPermille" }
        }
    }

    companion object {
        /**
         * 저장용 문자열로 인코딩한다.
         *
         * 형식은 `r:<spine>:<charOffset>` 과 `p:<page>:<x>:<y>` 로, 전부 정수라
         * 왕복이 정확하고 DB를 직접 들여다봐도 읽힌다. Room의 TypeConverter가
         * 이 함수를 쓴다.
         */
        fun encode(locator: Locator): String = when (locator) {
            is Reflow -> "r:${locator.spine}:${locator.charOffset}"
            is FixedPage -> "p:${locator.page}:${locator.xPermille}:${locator.yPermille}"
        }

        /**
         * [encode]의 역. 형식이 깨졌거나 값이 범위를 벗어나면 null을 돌려준다.
         *
         * 예외가 아니라 null인 이유: 저장된 값이 상하거나 옛 형식이 남아 있을 때
         * 책 한 권의 책갈피 때문에 라이브러리 전체가 못 열리면 안 된다. 호출부는
         * null을 "위치 없음"(책 처음)으로 처리한다.
         */
        fun decodeOrNull(encoded: String): Locator? {
            val parts = encoded.split(':')
            return runCatching {
                when {
                    parts.size == 3 && parts[0] == "r" ->
                        Reflow(parts[1].toInt(), parts[2].toInt())

                    parts.size == 4 && parts[0] == "p" ->
                        FixedPage(parts[1].toInt(), parts[2].toInt(), parts[3].toInt())

                    else -> null
                }
            }.getOrNull()
        }

        /** 해당 포맷에서 "책의 처음"을 가리키는 위치. */
        fun start(format: BookFormat): Locator =
            if (format.isReflowable) Reflow(spine = 0, charOffset = 0) else FixedPage(page = 0)
    }
}
