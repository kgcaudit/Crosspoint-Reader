package io.github.kgcaudit.reader.data

import io.github.kgcaudit.reader.data.db.ProgressDao
import io.github.kgcaudit.reader.data.db.ProgressEntity
import io.github.kgcaudit.reader.data.db.RecentDao
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.ReadingProgress

class RoomProgressRepository(
    private val dao: ProgressDao,
    /** 끝까지 읽으면 다 읽음을 적을 곳. 없으면(옛 시험) 적지 않는다. */
    private val recent: RecentDao? = null,
) : ProgressRepository {

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

    override suspend fun save(progress: ReadingProgress) {
        dao.upsert(
            ProgressEntity(
                bookId = progress.bookId.value,
                locator = Locator.encode(progress.locator),
                percent = progress.percent,
                updatedAtEpochMs = progress.updatedAtEpochMs,
            ),
        )
        // 마지막 쪽이 보이면 리더가 100 으로 적는다(EPUB 은 쪽 첫 글자로 재 끝 쪽도 97% 쯤이라 리더가 따로 판단한다).
        if (progress.percent >= FINISHED_PERCENT) recent?.finishOnce(progress.bookId.value, progress.updatedAtEpochMs)
    }

    override suspend fun remove(bookId: BookId) = dao.delete(bookId.value)
}

/** 이만큼이면 끝까지 읽었다. 부동소수 계산으로 100 이 99.99… 가 되는 것을 받아 준다. */
private const val FINISHED_PERCENT = 99.95f
