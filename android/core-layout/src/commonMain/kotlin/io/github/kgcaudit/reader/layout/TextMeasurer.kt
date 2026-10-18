package io.github.kgcaudit.reader.layout

/**
 * 글자 폭과 줄 높이를 재는 유일한 통로.
 *
 * 이 인터페이스가 조판 코어의 **플랫폼 경계**다. 안드로이드에서는
 * `android.graphics.Paint` 가 구현해 커닝·리거처·한글 조합·이모지 셰이핑을 공짜로
 * 얻고, 테스트에서는 결정적인 가짜 구현이 들어와 페이지 분할을 골든으로 고정할 수
 * 있게 한다.
 *
 * 구현은 **순수 함수처럼** 동작해야 한다. 같은 입력에 항상 같은 값을 돌려주지 않으면
 * 캐시된 페이지와 화면이 어긋난다.
 */
interface TextMeasurer {

    /** 기준 글자 크기(px). [TextStyle.sizeScale] 이 이 값에 곱해진다. */
    val baseSizePx: Float

    /** `[start, endExclusive)` 구간의 진행 폭(px). */
    fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle): Float

    /** 이 서식으로 한 줄이 차지하는 높이(px). 줄 간격 배수는 조판기가 따로 곱한다. */
    fun lineHeight(style: TextStyle): Float

    /** 베이스라인까지의 높이(px). 한 줄 안에 크기가 섞일 때 정렬 기준이 된다. */
    fun ascent(style: TextStyle): Float

    /** 공백 한 칸의 폭(px). 양쪽정렬에서 간격의 기준이 된다. */
    fun spaceAdvance(style: TextStyle): Float
}
