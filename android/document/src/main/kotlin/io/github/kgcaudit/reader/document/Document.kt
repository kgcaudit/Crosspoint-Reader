package io.github.kgcaudit.reader.document

import java.io.InputStream
import java.io.Reader

/**
 * 열린 책 한 권.
 *
 * 구현은 두 갈래뿐이다 — 조판해서 보여주는 [ReflowDocument](EPUB·TXT)와 이미 페이지가
 * 정해진 [PagedDocument](PDF). 라이브러리·책갈피·진도·리더 크롬은 이 상위 타입만
 * 알면 되므로 포맷과 무관하게 한 벌로 동작한다.
 */
sealed interface Document {
    val meta: BookMeta

    /** 목차. 없으면 빈 목록(예: 목차 없는 TXT, outline 없는 PDF). */
    suspend fun outline(): List<TocEntry>
}

/** 조판 대상 문서. 내용은 텍스트로 읽어 [io.github.kgcaudit.reader] 조판 코어가 페이지로 나눈다. */
interface ReflowDocument : Document {
    suspend fun spine(): List<SpineItem>

    /** 챕터 본문을 연다. 호출부가 닫는다. EPUB은 XHTML, TXT는 평문. */
    suspend fun openChapter(index: Int): Reader

    /** 챕터가 참조하는 리소스(이미지·CSS)를 연다. 없으면 null. TXT는 항상 null. */
    suspend fun openResource(href: String): InputStream?
}

/**
 * 페이지가 고정된 문서.
 *
 * 렌더링 계약이 여기 없는 것은 의도적이다. 페이지를 비트맵으로 만드는 일은 플랫폼
 * 타입(`android.graphics.Bitmap`)을 요구하므로, 그 인터페이스는 Android 모듈
 * (`:reader-pdf`의 `PageRasterizer`)에 둔다. 이 모듈이 순수하게 남아야 메타데이터와
 * 위치 모델을 기기 없이 테스트할 수 있다.
 */
interface PagedDocument : Document {
    val pageCount: Int

    /** 페이지의 가로/세로 비율. 렌더 전에 지면을 잡는 데 쓴다. */
    suspend fun pageAspectRatio(index: Int): Float
}
