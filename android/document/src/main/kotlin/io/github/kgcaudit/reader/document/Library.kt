package io.github.kgcaudit.reader.document

/**
 * 책갈피 보관소.
 *
 * 인터페이스를 여기(순수 Kotlin) 두고 구현을 밖에 두는 이유: 책갈피를 꽂고 지우고
 * 되찾는 규칙은 기기와 무관한데, 저장 수단(SQLite·파일)은 플랫폼에 묶인다. 규칙을
 * 여기서 검증해 두면 나중에 저장 수단을 바꿔도 다시 검증할 것이 없다.
 */
interface BookmarkRepository {

    /** 이 책의 책갈피. 읽는 순서(위치 오름차순)로 준다 — 목록을 그 순서로 보여 준다. */
    suspend fun forBook(bookId: BookId): List<Bookmark>

    /** 넣고 부여된 id 를 돌려준다. [Bookmark.id] 가 [Bookmark.NO_ID] 인 채로 들어온다. */
    suspend fun add(bookmark: Bookmark): Bookmark

    suspend fun remove(id: Long)
}

/**
 * 이어읽기 위치 보관소.
 *
 * 책마다 하나뿐이다. 새로 저장하면 앞의 것을 덮어쓴다 — 이력을 남기면 "어디까지
 * 읽었나" 에 답이 여러 개가 된다.
 */
interface ProgressRepository {

    suspend fun get(bookId: BookId): ReadingProgress?

    suspend fun save(progress: ReadingProgress)

    suspend fun remove(bookId: BookId)
}
