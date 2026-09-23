package io.github.kgcaudit.reader.data.library

import androidx.room.withTransaction
import io.github.kgcaudit.reader.data.db.BookEntity
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.db.RecentEntity
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 라이브러리 목록 한 줄. */
data class LibraryBook(
    val id: BookId,
    val format: BookFormat,
    val displayName: String,
    /** 책을 한 번 열어 메타데이터를 읽기 전에는 null. */
    val title: String?,
    val author: String?,
    val sizeBytes: Long?,
    val folderUri: String,
) {
    /** 목록에 보일 이름. 제목을 모르면 파일 이름이다. */
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: displayName
}

/**
 * 라이브러리(등록 폴더에서 찾은 책들)와 최근 목록.
 *
 * 스캔 결과를 반영할 때 **지우지 않는다.** 스캔이 책을 놓치는 일은 흔하다(SD 카드가
 * 빠짐, 클라우드 제공자가 잠시 응답 없음, 권한 일시 회수). 그때 행을 지우면 진도·책갈피를
 * 찾을 열쇠가 사라진다. 대신 숨겨 두었다가 다시 보이면 그대로 되살린다.
 */
class Library(private val db: ReaderDatabase) {

    private val books = db.books()
    private val recent = db.recent()

    fun books(): Flow<List<LibraryBook>> = books.observeVisible().map { rows -> rows.mapNotNull(::toBook) }

    fun recent(limit: Int = RECENT_LIMIT): Flow<List<LibraryBook>> =
        recent.observe(limit).map { rows -> rows.mapNotNull(::toBook) }

    suspend fun get(id: BookId): LibraryBook? = books.get(id.value)?.let(::toBook)

    /**
     * 다른 앱이 넘긴 파일과 같은 책이 라이브러리에 있는가. 이름과 크기가 모두 같아야 한다.
     *
     * 파일 관리자가 넘기는 URI 는 라이브러리의 SAF URI 와 모양이 달라 id 로는 못 찾는다. 같은 책을
     * 다른 id 로 열면 진도·책갈피가 두 벌로 갈라진다. 크기를 모르면 찾지 않는다 — 이름만 같은
     * 다른 판을 열 수 있다.
     */
    suspend fun findByFile(displayName: String, sizeBytes: Long?): LibraryBook? =
        sizeBytes?.let { books.byFile(displayName, it) }?.let(::toBook)

    /**
     * 책마다 읽은 정도(0~100). 목록 오른쪽에 보여 준다.
     *
     * 표시용이라 저장된 값을 범위 안으로만 자른다. 위치를 복원하는 데는 쓰지 않는다.
     */
    fun percents(): Flow<Map<BookId, Float>> = db.progress().observeAll().map { rows ->
        rows.associate { BookId(it.bookId) to it.percent.coerceIn(0f, 100f) }
    }

    /** 책을 열었다. 최근 목록의 맨 앞으로 온다. */
    suspend fun markOpened(id: BookId, nowEpochMs: Long) = recent.upsert(RecentEntity(id.value, nowEpochMs))

    /** 책을 처음 열어 읽은 제목·저자를 기록한다. 다음부터 목록이 파일 이름 대신 보여 준다. */
    suspend fun updateMetadata(id: BookId, title: String?, author: String?) =
        books.updateMetadata(id.value, title?.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() })

    /**
     * 한 폴더의 스캔 결과를 반영한다.
     *
     * - 찾은 책은 넣거나 갱신하고, 숨겨져 있었으면 되살린다.
     * - 파일이 바뀌었으면(크기·수정 시각) 읽어 둔 제목을 버린다. 같은 이름으로 다른 판을
     *   덮어쓴 경우 옛 제목이 계속 보이면 안 된다.
     * - [ScanResult.complete] 일 때만, 이번에 안 보인 책을 숨긴다.
     */
    suspend fun applyScan(folderUri: String, result: ScanResult, nowEpochMs: Long) = db.withTransaction {
        val existing = books.inFolder(folderUri).associateBy { it.id }

        books.upsert(
            result.books.map { found ->
                val old = existing[found.uri]
                val changed = old != null &&
                    (old.sizeBytes != found.sizeBytes || old.lastModifiedEpochMs != found.lastModifiedEpochMs)
                BookEntity(
                    id = found.uri,
                    folderUri = folderUri,
                    displayName = found.displayName,
                    format = found.format.name,
                    sizeBytes = found.sizeBytes,
                    lastModifiedEpochMs = found.lastModifiedEpochMs,
                    title = if (changed) null else old?.title,
                    author = if (changed) null else old?.author,
                    addedAtEpochMs = old?.addedAtEpochMs ?: nowEpochMs,
                    missing = false,
                )
            },
        )

        if (result.complete) {
            val seen = result.books.mapTo(HashSet()) { it.uri }
            val gone = existing.values.filter { !it.missing && it.id !in seen }.map { it.id }
            // SQLite 의 변수 개수 제한(999)에 걸리지 않게 나눠 보낸다.
            gone.chunked(500).forEach { books.markMissing(it) }
        }
    }

    /**
     * 등록을 푼 폴더의 책을 목록에서 뺀다.
     *
     * 진도·책갈피는 남긴다. 같은 폴더를 다시 등록하면 URI 가 같으므로 그대로 이어진다.
     */
    suspend fun forgetFolder(folderUri: String) = books.deleteFolder(folderUri)

    /** 모르는 포맷 이름(다음 버전이 쓴 값 등)이 든 행은 목록에서만 빠진다. */
    private fun toBook(row: BookEntity): LibraryBook? {
        val format = BookFormat.entries.firstOrNull { it.name == row.format } ?: return null
        return LibraryBook(
            id = BookId(row.id),
            format = format,
            displayName = row.displayName,
            title = row.title,
            author = row.author,
            sizeBytes = row.sizeBytes,
            folderUri = row.folderUri,
        )
    }

    companion object {
        const val RECENT_LIMIT: Int = 20
    }
}
