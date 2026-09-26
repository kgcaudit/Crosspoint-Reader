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

/**
 * 형광펜 · 메모 보관소. 책갈피와 같은 까닭으로 인터페이스만 여기 둔다.
 */
interface AnnotationRepository {

    /** 이 책의 형광펜. 읽는 순서(시작 위치 오름차순)로 준다 — 독서노트가 그 순서로 보인다. */
    suspend fun forBook(bookId: BookId): List<Annotation>

    /** 넣고 부여된 id 를 돌려준다. 들어온 id 는 무시한다. */
    suspend fun add(annotation: Annotation): Annotation

    /** 색 · 메모를 바꾼다. 자리 · 책은 바꾸지 않는다(칠한 글과 자리가 어긋나면 안 된다). */
    suspend fun update(annotation: Annotation)

    suspend fun remove(id: Long)
}

/** 보관소를 주지 않았을 때(시험 · 미리보기). 앱은 Room 보관소를 준다. */
class InMemoryAnnotations : AnnotationRepository {
    private val rows = ArrayList<Annotation>()
    private var next = 1L

    override suspend fun forBook(bookId: BookId): List<Annotation> =
        rows.filter { it.bookId == bookId }.sortedWith(compareBy({ it.start.spine }, { it.start.charOffset }, { it.id }))

    override suspend fun add(annotation: Annotation): Annotation = annotation.copy(id = next++).also { rows.add(it) }

    override suspend fun update(annotation: Annotation) {
        val i = rows.indexOfFirst { it.id == annotation.id }
        if (i >= 0) rows[i] = rows[i].copy(color = annotation.color, note = annotation.note)
    }

    override suspend fun remove(id: Long) {
        rows.removeAll { it.id == id }
    }
}
