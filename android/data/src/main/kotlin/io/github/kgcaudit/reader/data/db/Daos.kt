package io.github.kgcaudit.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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

    @Upsert
    suspend fun upsert(recent: RecentEntity)

    /** 숨겨진(스캔에서 사라진) 책은 뺀다. 눌러도 열리지 않는 항목을 보여 주면 안 된다. */
    @Query(
        "SELECT books.* FROM recent JOIN books ON books.id = recent.bookId " +
            "WHERE books.missing = 0 ORDER BY recent.openedAtEpochMs DESC LIMIT :limit",
    )
    fun observe(limit: Int): Flow<List<BookEntity>>
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
