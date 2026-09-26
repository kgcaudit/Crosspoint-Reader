package io.github.kgcaudit.reader.data

import io.github.kgcaudit.reader.data.db.BookmarkDao
import io.github.kgcaudit.reader.data.db.BookmarkEntity
import io.github.kgcaudit.reader.data.db.LocatorOrder
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.Locator

class RoomBookmarkRepository(private val dao: BookmarkDao) : BookmarkRepository {

    override suspend fun forBook(bookId: BookId): List<Bookmark> =
        // 위치가 상한 행은 그 한 줄만 버린다. 하나 때문에 목록 전체가 안 열리면 안 된다.
        dao.forBook(bookId.value).mapNotNull { row ->
            Locator.decodeOrNull(row.locator)?.let { locator ->
                Bookmark(row.id, bookId, locator, row.snippet, row.createdAtEpochMs)
            }
        }

    override suspend fun add(bookmark: Bookmark): Bookmark {
        val order = LocatorOrder.of(bookmark.locator)
        // 들어온 id 는 무시하고 항상 새로 받는다. 호출부가 실수로 id 를 채워 넣으면
        // 기존 책갈피를 덮어쓰거나(REPLACE) 충돌로 죽는데, 둘 다 "책갈피를 꽂았는데
        // 다른 게 사라졌다" 로 나타난다.
        val id = dao.insert(
            BookmarkEntity(
                id = 0,
                bookId = bookmark.bookId.value,
                locator = Locator.encode(bookmark.locator),
                orderMajor = order.major,
                orderMinor = order.minor,
                orderPatch = order.patch,
                snippet = bookmark.snippet,
                createdAtEpochMs = bookmark.createdAtEpochMs,
            ),
        )
        return bookmark.copy(id = id)
    }

    override suspend fun remove(id: Long) = dao.delete(id)
}
