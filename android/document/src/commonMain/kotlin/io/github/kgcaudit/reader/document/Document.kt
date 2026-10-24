package io.github.kgcaudit.reader.document

import io.github.kgcaudit.reader.document.io.InputStream
import io.github.kgcaudit.reader.document.io.Reader

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

    /** 컨테이너 안의 리소스를 경로로 직접 연다. 없으면 null. TXT는 항상 null. */
    suspend fun openResource(href: String): InputStream?

    /**
     * 챕터 [index] 가 참조하는 상대 href 를 풀어 리소스를 연다. 없으면 null.
     *
     * 경로 해석을 호출부가 아니라 문서가 하는 이유: `../images/a.png` 가 어디를
     * 가리키는지는 컨테이너 구조를 아는 쪽만 안다. 조판·렌더 쪽이 이걸 흉내 내면
     * 책마다 조금씩 다른 경로 관례에 걸려 "이 책만 그림이 안 나온다" 가 된다.
     */
    suspend fun openChapterResource(index: Int, href: String): InputStream?

    /**
     * 책의 스타일시트 경로(컨테이너 기준), **정렬된 순서로**.
     *
     * 책 글꼴표를 만드는 데 쓴다. 챕터를 연 순서가 아니라 이 목록으로 만들어야 글꼴 번호가
     * 어느 챕터부터 읽든 같다 — 페이지 캐시에 번호가 들어가므로 달라지면 다른 글꼴로 그린다.
     */
    suspend fun stylesheets(): List<String> = emptyList()

    /** [fromPath] 파일 안의 상대 [href] 를 컨테이너 경로로 푼다. CSS 안의 `url(...)` 에 쓴다. */
    fun resolveHref(fromPath: String, href: String): String = href

    /**
     * 글꼴 파일을 연다. 없으면 null.
     *
     * [openResource] 와 따로 있는 이유: EPUB 은 글꼴을 **난독화**해 넣을 수 있다
     * (`META-INF/encryption.xml`). 그대로 읽으면 앞 1KB 가 뒤섞인 파일이라 안드로이드가 읽지 못한다.
     */
    suspend fun openFont(path: String): InputStream? = openResource(path)
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

/**
 * 한 장을 조판 단위(조각) 여럿으로 나눠 내는 문서 — 큰 TXT(0.60.0, iOS 요청).
 *
 * 한 장짜리 큰 TXT 는 첫 쪽을 보이기 전에 파일 전체를 쪽으로 나눠야 했다(672KB 에 iPhone 13 mini 27초). 조각으로 나누면
 * 보이려는 조각 하나만 짜면 된다. [ReflowDocument.spine] 의 항목은 조각이지만 **저장하는 자리**(책갈피 · 진도 · 형광펜 ·
 * 백업 · 동기화)는 나누기 전 한 장의 글자 자리(`r:0:N`)로 둔다 — 옛 기록 · 옛 판 앱 · 다른 기기와 그대로 맞는다.
 * 두 자리를 오가는 것은 조판 코어의 `LocatorSpace` 다.
 */
interface PiecedDocument {
    /** 조각마다 한 장 안에서 시작하는 글자 자리. 첫 값은 0, 오름차순. */
    suspend fun pieceStarts(): IntArray

    /** 한 장의 글자 수. */
    suspend fun textLength(): Int
}
