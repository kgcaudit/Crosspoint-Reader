# 앱 아이콘 원본

`gen.py` 가 108 격자(적응형 아이콘 규격)의 좌표를 만들고 `icon.svg`(미리보기)와
`icon.json` 을 쓴다. `res/drawable/ic_launcher_foreground.xml` · `ic_launcher_monochrome.xml`
은 그 수치를 VectorDrawable 로 옮긴 것이다(SVG 의 `translate → rotate(피벗)` 은
`<group>` 의 변환 순서와 같아서 값이 그대로 옮겨진다). 런처는 가운데 72 만 보여 주므로
전경 전체를 `scale 0.78` 그룹으로 감쌌다.

컨셉(2026-09-23 사용자 지정): OLO Explorer 와 **바탕색 통일**(주황), **도형은 밝은
그레이**, **찢는 느낌** — 펼친 책의 오른쪽 페이지 바깥쪽이 사선으로 찢겨 위로 들린다.

고칠 때: `gen.py` 의 `cuts`(찢는 위치) · `lift`(들림·회전) · `g`(틈) 를 바꾸고 실행한 뒤,
`icon.json` 값으로 XML 두 파일을 다시 만든다. 결과는
`./gradlew :app:testDebugUnitTest` 가 `build/screenshots/00-launcher-icon.png` 로 그려 준다.
