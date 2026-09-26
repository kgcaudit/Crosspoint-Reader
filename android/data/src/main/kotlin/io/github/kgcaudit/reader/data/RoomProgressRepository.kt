package io.github.kgcaudit.reader.data

import io.github.kgcaudit.reader.data.db.ProgressDao
import io.github.kgcaudit.reader.data.db.ProgressEntity
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress

class RoomProgressRepository(private val dao: ProgressDao) : ProgressRepository {

    override suspend fun get(bookId: BookId): ReadingProgress? {
        val row = dao.get(bookId.value) ?: return null
        // 위치가 상했으면 "진도 없음"(책 처음)으로 연다. 예외를 던지면 그 책이 영영
        // 열리지 않는다.
        val locator = Locator.decodeOrNull(row.locator) ?: return null
        return ReadingProgress(
            bookId = bookId,
            locator = locator,
            // 퍼센트는 표시용 파생값이다. 범위를 벗어난 값이 저장돼 있어도(옛 버전의
            // 계산 실수 등) ReadingProgress 의 검사에 걸려 책이 안 열리게 두지 않는다.
            percent = row.percent.coerceIn(0f, 100f),
            updatedAtEpochMs = row.updatedAtEpochMs,
        )
    }

    override suspend fun save(progress: ReadingProgress) = dao.upsert(
        ProgressEntity(
            bookId = progress.bookId.value,
            locator = Locator.encode(progress.locator),
            percent = progress.percent,
            updatedAtEpochMs = progress.updatedAtEpochMs,
        ),
    )

    override suspend fun remove(bookId: BookId) = dao.delete(bookId.value)
}
