package io.github.kgcaudit.reader.data

import androidx.room.InvalidationTracker
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.document.comic.ComicProgress

/** 읽은 적이 있는 책 한 권(홈 화면 위젯의 후보). null 은 "기록 없음" 이다 — 0 과 다르다. */
data class ReadBook(
    val id: String,
    val displayName: String,
    val title: String?,
    val author: String?,
    val percent: Float?,
    val openedAtEpochMs: Long?,
    val progressAtEpochMs: Long?,
    val finishedAtEpochMs: Long?,
)

/** 읽은 적이 있는 만화 한 권. */
data class ReadComic(val unitId: String, val name: String, val progress: ComicProgress)

/**
 * 무엇을 언제 읽었나 — 앱 밖(홈 화면 위젯)이 DB 를 모른 채 묻는 창구. 서재(Library · ComicLibrary)는 화면이 보는 흐름(Flow)이라,
 * 한 번 묻고 끝나는 위젯에는 이것이 맞다. 숨은(사라진) 책 · 만화는 주지 않는다 — 눌러도 열 파일이 없다.
 */
class ReadingActivity internal constructor(private val db: ReaderDatabase) {

    suspend fun books(): List<ReadBook> {
        val recent = db.recent().all().associateBy { it.bookId }
        val progress = db.progress().all().associateBy { it.bookId }
        return db.books().all().filter { !it.missing && (it.id in recent || it.id in progress) }.map { b ->
            ReadBook(
                id = b.id,
                displayName = b.displayName,
                title = b.title,
                author = b.author,
                percent = progress[b.id]?.percent,
                openedAtEpochMs = recent[b.id]?.openedAtEpochMs,
                progressAtEpochMs = progress[b.id]?.updatedAtEpochMs,
                finishedAtEpochMs = recent[b.id]?.finishedAtEpochMs,
            )
        }
    }

    suspend fun comics(): List<ReadComic> {
        val rows = db.comics().allProgress()
        if (rows.isEmpty()) return emptyList()
        val units = rows.map { it.unitId }.chunked(500).flatMap { db.comics().unitsOf(it) }
            .filter { !it.missing && !it.notComic }.associateBy { it.id }
        return rows.mapNotNull { r ->
            val unit = units[r.unitId] ?: return@mapNotNull null
            ReadComic(unit.id, unit.name, ComicProgress(r.page, r.pageCount, r.updatedAtEpochMs, r.finishedAtEpochMs, r.offset))
        }
    }

    /** 이 책이 아직 서재에 보이는가(파일을 옮기거나 지웠으면 다음 훑기에 숨는다). */
    suspend fun bookVisible(id: String): Boolean = db.books().get(id)?.missing == false

    suspend fun comicVisible(unitId: String): Boolean = db.comics().get(unitId)?.let { !it.missing && !it.notComic } == true

    /**
     * 읽기 기록(자리 · 책갈피 · 다 읽음 · 제목)이 바뀔 때마다 [onChange]. 리더마다 고리를 거는 대신 표가 바뀐 것을 본다 — 리더가
     * 늘어도(만화 · 웹툰 · 그림책) 빠뜨릴 자리가 없다. 어느 스레드에서든 불린다.
     */
    fun observe(onChange: () -> Unit) {
        db.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(arrayOf("progress", "bookmarks", "recent", "books", "comic_progress", "comic_bookmarks")) {
                override fun onInvalidated(tables: Set<String>) = onChange()
            },
        )
    }
}

/** 이 데이터의 [ReadingActivity]. */
fun ReaderData.readingActivity(): ReadingActivity = ReadingActivity(database)
