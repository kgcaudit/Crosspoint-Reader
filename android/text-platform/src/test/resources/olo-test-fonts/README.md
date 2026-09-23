# 테스트 전용 폰트

Pretendard 1.3.9(SIL OFL 1.1)에서 테스트에 쓰는 글자만 남긴 **수정본**이다. OFL 은 수정본이
예약 글꼴 이름("Pretendard")을 쓰지 못하게 하므로 이름을 "Olo Test Sans"·"Olo Test Latin" 으로
바꿨다. 라이선스 원문은 `OFL-LICENSE.txt`.

- `olo-test-regular.ttf` / `olo-test-bold.ttf` — 한 가족의 보통·굵게(굵기 400·700)
- `olo-test-latin.ttf` — 한글이 없는 폰트(사용자가 영문 폰트를 추가하는 경우)

KoPub 은 쓰지 않는다. KOPUS 약관이 수정본(서브셋 포함) 배포를 막는다.
- `olo-test-collection.ttc` — 두 서체를 묶은 TTC. 0번은 `olo-test-regular` 에 **한국어 이름**
  ("올로 테스트 산스")을 더하고 cmap 을 **형식 12 만** 남긴 것, 1번은 `olo-test-latin`.
  한국어 이름 고르기 · 형식 12 판독 · "TTC 에서 한국어 가족만 올리기" 를 한 파일로 본다.
  (fontTools 로 만들었다.)
