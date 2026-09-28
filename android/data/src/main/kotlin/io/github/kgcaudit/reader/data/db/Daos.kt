package io.github.kgcaudit.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books WHERE missing = 0 ORDER BY displayName COLLATE NOCASE")
    fun observeVisible(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE displayName = :name AND sizeBytes = :size AND missing = 0 ORDER BY id LIMIT 1")
    suspend fun byFile(name: String, size: Long): BookEntity?

    @Query("SELECT * FROM books WHERE folderUri = :folderUri")
    suspend fun inFolder(folderUri: String): List<BookEntity>

    @Upsert
    suspend fun upsert(books: List<BookEntity>)

    @Query("UPDATE books SET missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<String>)

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String?, author: String?)

    @Query("DELETE FROM books WHERE folderUri = :folderUri")
    suspend fun deleteFolder(folderUri: String)
}

@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ProgressEntity?

    @Query("SELECT * FROM progress")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY orderMajor, orderMinor, orderPatch, id")
    suspend fun forBook(bookId: String): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RecentDao {

    /**
     * 열었다: 없던 책이면 새 행, 있던 책이면 연 시각만. 행을 통째로 덮어쓰면 다 읽은 때가 지워져, 다 읽은 책을
     * 다시 펼칠 때마다 "읽는 중" 으로 돌아갔다(그래서 upsert 를 두지 않는다).
     */
    @Query("INSERT OR IGNORE INTO recent(bookId, openedAtEpochMs, finishedAtEpochMs) VALUES(:bookId, :at, NULL)")
    suspend fun insertIfAbsent(bookId: String, at: Long)

    @Query("UPDATE recent SET openedAtEpochMs = :at WHERE bookId = :bookId")
    suspend fun touch(bookId: String, at: Long)

    @Transaction
    suspend fun opened(bookId: String, at: Long) {
        insertIfAbsent(bookId, at)
        touch(bookId, at)
    }

    /** 다 읽음 표시 · 풀기(null). */
    @Query("UPDATE recent SET finishedAtEpochMs = :at WHERE bookId = :bookId")
    suspend fun setFinished(bookId: String, at: Long?)

    /** 끝까지 읽었다 — 처음 한 번만 적는다(끝 쪽을 다시 넘겨도 끝낸 날이 바뀌지 않게). */
    @Query("UPDATE recent SET finishedAtEpochMs = :at WHERE bookId = :bookId AND finishedAtEpochMs IS NULL")
    suspend fun finishOnce(bookId: String, at: Long)

    /** 책장. 숨겨진(스캔에서 사라진) 책은 뺀다 — 눌러도 열리지 않는 항목을 보여 주면 안 된다. */
    @Query(
        "SELECT books.*, recent.finishedAtEpochMs FROM recent JOIN books ON books.id = recent.bookId " +
            "WHERE books.missing = 0 ORDER BY recent.openedAtEpochMs DESC",
    )
    fun observeShelf(): Flow<List<ShelfRow>>
}

@Dao
interface AnnotationDao {

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY orderMajor, orderMinor, id")
    suspend fun forBook(bookId: String): List<AnnotationEntity>

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Query("UPDATE annotations SET color = :color, note = :note WHERE id = :id")
    suspend fun update(id: Long, color: String, note: String?)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long)
}
