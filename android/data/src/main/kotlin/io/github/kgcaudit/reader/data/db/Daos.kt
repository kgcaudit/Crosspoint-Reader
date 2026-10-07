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

    /** 백업에서 가져온 기록을 붙일 책. 같은 파일이 두 폴더에 있으면 둘 다다 — 어느 쪽을 열어도 이어지게. */
    @Query("SELECT * FROM books WHERE displayName = :name AND sizeBytes = :size AND missing = 0")
    suspend fun visibleByFile(name: String, size: Long): List<BookEntity>

    @Query("SELECT * FROM books WHERE displayName = :name AND missing = 0")
    suspend fun visibleByName(name: String): List<BookEntity>

    /** 크기를 알려 주지 않는 제공자(일부 클라우드)의 책. 이름으로만 맞춰 볼 수 있다. */
    @Query("SELECT * FROM books WHERE displayName = :name AND sizeBytes IS NULL AND missing = 0")
    suspend fun visibleByNameUnknownSize(name: String): List<BookEntity>

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE folderUri = :folderUri")
    suspend fun inFolder(folderUri: String): List<BookEntity>

    @Upsert
    suspend fun upsert(books: List<BookEntity>)

    @Query("UPDATE books SET missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<String>)

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    suspend fun updateMetadata(id: String, title: String?, author: String?)

    /** 폴더 등록을 풀었다. 행은 남기고 숨긴다 — 지우면 그 책들의 기록이 이름을 잃어 백업에 담기지 않는다. */
    @Query("UPDATE books SET missing = 1 WHERE folderUri = :folderUri")
    suspend fun hideFolder(folderUri: String)
}

@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ProgressEntity?

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Query("SELECT * FROM progress")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
interface BookmarkDao {

    /** 책마다 책갈피 수(목록 보기의 "책갈피 2"). */
    @Query("SELECT bookId, COUNT(*) AS count FROM bookmarks GROUP BY bookId")
    fun observeCounts(): Flow<List<BookCount>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY orderMajor, orderMinor, orderPatch, id")
    suspend fun forBook(bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks ORDER BY bookId, orderMajor, orderMinor, orderPatch, id")
    suspend fun all(): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    /** 옮긴 책(0.50.0)의 옛 자리 기록을 비운다 — 새 자리에 합친 뒤. */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
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

    /** 백업에서 가져온 연 시각이 더 나중일 때만 옮긴다 — 이 휴대폰에서 방금 연 책이 책장 뒤로 밀리지 않게. */
    @Query("UPDATE recent SET openedAtEpochMs = :at WHERE bookId = :bookId AND openedAtEpochMs < :at")
    suspend fun touchIfLater(bookId: String, at: Long)

    @Query("SELECT * FROM recent")
    suspend fun all(): List<RecentEntity>

    @Transaction
    suspend fun opened(bookId: String, at: Long) {
        insertIfAbsent(bookId, at)
        touch(bookId, at)
    }

    /** 책장에서 뺀다 — 읽을 책으로 되돌린다. 진도 · 책갈피는 남아 다시 열면 이어진다. */
    @Query("DELETE FROM recent WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

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

    /** 책마다 칠 수와 그중 메모가 달린 수(목록 보기의 "형광펜 5 · 메모 1"). */
    @Query("SELECT bookId, COUNT(*) AS count, SUM(CASE WHEN note IS NULL THEN 0 ELSE 1 END) AS memos FROM annotations GROUP BY bookId")
    fun observeCounts(): Flow<List<AnnotationCount>>

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY orderMajor, orderMinor, id")
    suspend fun forBook(bookId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations ORDER BY bookId, orderMajor, orderMinor, id")
    suspend fun all(): List<AnnotationEntity>

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Query("UPDATE annotations SET color = :color, note = :note WHERE id = :id")
    suspend fun update(id: Long, color: String, note: String?)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long)

    /** 옮긴 책(0.50.0)의 옛 자리 기록을 비운다 — 새 자리에 합친 뒤. */
    @Query("DELETE FROM annotations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}

/** 책 한 권의 수. Room 이 질의 결과 칸 이름(bookId · count)으로 채운다. */
data class BookCount(val bookId: String, val count: Int)

data class AnnotationCount(val bookId: String, val count: Int, val memos: Int)

@Dao
interface ComicDao {

    /** 서재에 보일 단위: 숨기지 않았고, 살펴서 만화가 아니라고 나온 것이 아니고, 그냥 압축(zip · rar · 7z)이면 살펴서 만화라고 나온 것.
     *  목록은 [io.github.kgcaudit.reader.document.comic.ArchiveExtensions.PLAIN] 과 같아야 한다 — 빠지면 살피기 전의
     *  사진 묶음이 잠깐 만화로 보인다(ComicLibraryTest 가 둘을 맞춰 본다). */
    @Query(
        "SELECT * FROM comic_units WHERE missing = 0 AND notComic = 0 " +
            "AND (extension NOT IN ('zip', 'rar', '7z') OR probed = 1)",
    )
    fun observeVisible(): Flow<List<ComicUnitEntity>>

    @Query("SELECT * FROM comic_units WHERE folderUri = :folderUri")
    suspend fun inFolder(folderUri: String): List<ComicUnitEntity>

    @Query("SELECT * FROM comic_units WHERE id = :id")
    suspend fun get(id: String): ComicUnitEntity?

    /**
     * 아직 살피지 않았거나, 살핀 뒤 파일이 바뀐 압축(zip · rar · 7z · cbz · cbt · cbr · cb7 — 0.37.0 부터 뒤의 셋도). 압축 속 권(`NESTED`,
     * 0.48.0)도 같다 — 크기는 안쪽 권의 크기, 수정 시각은 바깥 압축의 것이다. 목록은
     * [io.github.kgcaudit.reader.document.comic.ArchiveExtensions.ALL] 과 같아야 한다 — 빠진 확장자는 영영 살피지 않아 서재에 안 보인다.
     */
    @Query(
        "SELECT * FROM comic_units WHERE missing = 0 AND kind IN ('ARCHIVE', 'NESTED') AND extension IN ('zip', 'rar', '7z', 'cbz', 'cbt', 'cbr', 'cb7') " +
            "AND (probed = 0 OR probedSize IS NOT sizeBytes OR probedModified IS NOT lastModifiedEpochMs)",
    )
    suspend fun needingProbe(): List<ComicUnitEntity>

    @Upsert
    suspend fun upsert(units: List<ComicUnitEntity>)

    /**
     * 바깥 압축 [outerId] 안에서 꺼낸 권들(0.48.0). id 앞부분을 글자 그대로 비교한다 — LIKE 는 문서 URI 의 `%` · `_` 를 무늬로 읽어
     * 다른 압축의 권까지 걸렸다.
     */
    @Query("SELECT * FROM comic_units WHERE kind = 'NESTED' AND substr(id, 1, length(:prefix)) = :prefix")
    suspend fun nestedIn(prefix: String): List<ComicUnitEntity>

    @Query("UPDATE comic_units SET missing = 1 WHERE id IN (:ids)")
    suspend fun markMissing(ids: List<String>)

    /** "만화 아님" 으로 적힌 압축을 모두 살필 차례로 되돌린다(살피기 규칙이 바뀌었을 때). */
    @Query("UPDATE comic_units SET probed = 0 WHERE notComic = 1")
    suspend fun reprobeRejected(): Int

    @Query("UPDATE comic_units SET missing = 1 WHERE folderUri = :folderUri")
    suspend fun hideFolder(folderUri: String)

    @Query(
        "UPDATE comic_units SET probed = 1, probedSize = :size, probedModified = :modified, notComic = :notComic, pageCount = :pages, " +
            "coverEntry = :cover, sections = :sections, infoSeries = :series, infoNumber = :number, infoFormat = :format, " +
            "infoRightToLeft = :rightToLeft WHERE id = :id",
    )
    suspend fun saveProbe(
        id: String, size: Long?, modified: Long?, notComic: Boolean, pages: Int?, cover: String?, sections: String?,
        series: String?, number: Double?, format: String?, rightToLeft: Boolean?,
    )

    @Query("SELECT * FROM comic_overrides")
    fun observeOverrides(): Flow<List<ComicOverrideEntity>>

    // ── 읽기 기록 백업(0.49.0) ──

    @Query("SELECT * FROM comic_overrides")
    suspend fun allOverrides(): List<ComicOverrideEntity>

    @Query("SELECT * FROM comic_progress")
    suspend fun allProgress(): List<ComicProgressEntity>

    @Query("SELECT * FROM comic_bookmarks")
    suspend fun allBookmarks(): List<ComicBookmarkEntity>

    @Query("SELECT * FROM comic_bookmarks WHERE unitId = :unitId")
    suspend fun bookmarksOf(unitId: String): List<ComicBookmarkEntity>

    @Query("SELECT * FROM comic_units WHERE id IN (:ids)")
    suspend fun unitsOf(ids: List<String>): List<ComicUnitEntity>

    // ── 옮긴 파일(0.50.0): 옛 자리의 기록을 새 자리에 합친 뒤 비운다 ──

    @Query("DELETE FROM comic_progress WHERE unitId = :unitId")
    suspend fun deleteProgress(unitId: String)

    @Query("DELETE FROM comic_bookmarks WHERE unitId = :unitId")
    suspend fun deleteBookmarks(unitId: String)

    /** 백업의 만화를 이 휴대폰에서 찾는다: 보이는 단위 중 이름 · 크기가 같은 것. */
    @Query("SELECT * FROM comic_units WHERE missing = 0 AND notComic = 0 AND name = :name AND sizeBytes = :size")
    suspend fun visibleByFile(name: String, size: Long): List<ComicUnitEntity>

    @Query("SELECT * FROM comic_units WHERE missing = 0 AND notComic = 0 AND name = :name")
    suspend fun visibleByName(name: String): List<ComicUnitEntity>

    @Upsert
    suspend fun setOverride(override: ComicOverrideEntity)

    @Query("DELETE FROM comic_overrides WHERE kind = :kind AND subject = :subject")
    suspend fun clearOverride(kind: String, subject: String)

    @Query("SELECT * FROM comic_progress")
    fun observeProgress(): Flow<List<ComicProgressEntity>>

    @Query("SELECT * FROM comic_progress WHERE unitId = :unitId")
    suspend fun progress(unitId: String): ComicProgressEntity?

    @Upsert
    suspend fun saveProgress(progress: ComicProgressEntity)

    @Query("SELECT * FROM comic_bookmarks WHERE unitId = :unitId ORDER BY page")
    fun observeBookmarks(unitId: String): Flow<List<ComicBookmarkEntity>>

    @Upsert
    suspend fun addBookmark(bookmark: ComicBookmarkEntity)

    @Query("DELETE FROM comic_bookmarks WHERE unitId = :unitId AND page = :page")
    suspend fun removeBookmark(unitId: String, page: Int): Int
}
