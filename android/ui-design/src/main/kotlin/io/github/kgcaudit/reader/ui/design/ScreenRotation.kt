package io.github.kgcaudit.reader.ui.design

/**
 * 화면 방향 설정. 책 종류(EPUB·TXT·PDF)와 관계없이 하나다 — 리더마다 따로 두면 PDF 를 가로로 보다가
 * EPUB 을 열 때 방향이 제멋대로 바뀐 것처럼 느껴진다.
 *
 * [Auto] 는 휴대폰의 회전 잠금과 **상관없이** 기기 방향을 따른다(사용자 결정: 잠금이 켜져 있어도 앱은 돈다).
 * 누워서 읽을 때 돌아가는 게 싫으면 앱 안에서 [Portrait] 로 고정한다 — 리디·Play 북과 같은 구성.
 */
enum class ScreenRotation(val label: String) {
    Auto("자동"),
    Portrait("세로"),
    Landscape("가로"),
}
