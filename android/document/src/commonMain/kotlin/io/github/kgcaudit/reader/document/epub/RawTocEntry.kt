package io.github.kgcaudit.reader.document.epub

/**
 * 목차 파일에서 읽어낸 한 줄. 아직 [io.github.kgcaudit.reader.document.Locator] 가 아니다.
 *
 * href 를 spine 인덱스로 바꾸는 일은 manifest 와 spine 을 둘 다 아는 쪽(문서 구현)의
 * 몫이므로, 파서는 본 것만 그대로 전달한다.
 *
 * @param href zip 엔트리 경로로 풀린 값. [fragment] 는 떼어 따로 담는다.
 * @param fragment `#` 뒤의 앵커. 챕터 안 특정 지점을 가리키는 항목에만 있다.
 * @param depth 중첩 깊이(0 = 최상위).
 */
data class RawTocEntry(
    val label: String,
    val href: String,
    val fragment: String? = null,
    val depth: Int = 0,
)
