package io.github.kgcaudit.reader.data

import io.github.kgcaudit.reader.data.db.AnnotationDao
import io.github.kgcaudit.reader.data.db.AnnotationEntity
import io.github.kgcaudit.reader.data.db.LocatorOrder
import io.github.kgcaudit.reader.document.Annotation
import io.github.kgcaudit.reader.document.AnnotationRepository
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.HighlightColor
import io.github.kgcaudit.reader.document.Locator

class RoomAnnotationRepository(private val dao: AnnotationDao) : AnnotationRepository {

    override suspend fun forBook(bookId: BookId): List<Annotation> =
        // 자리가 상한 행(리플로우 위치가 아니거나 뒤집힌 구간)은 그 한 줄만 버린다.
        dao.forBook(bookId.value).mapNotNull { row ->
            val start = Locator.decodeOrNull(row.start) as? Locator.Reflow ?: return@mapNotNull null
            val end = Locator.decodeOrNull(row.end) as? Locator.Reflow ?: return@mapNotNull null
            runCatching {
                Annotation(
                    id = row.id,
                    bookId = bookId,
                    start = start,
                    end = end,
                    color = colorOf(row.color),
                    note = row.note,
                    snippet = row.snippet,
                    createdAtEpochMs = row.createdAtEpochMs,
                ).withNote(row.note)
            }.getOrNull()
        }

    override suspend fun add(annotation: Annotation): Annotation {
        val order = LocatorOrder.of(annotation.start)
        // 들어온 id 는 무시한다(책갈피와 같은 까닭 — 다른 형광펜을 덮어쓰지 않게).
        val id = dao.insert(
            AnnotationEntity(
                id = 0,
                bookId = annotation.bookId.value,
                start = Locator.encode(annotation.start),
                end = Locator.encode(annotation.end),
                orderMajor = order.major,
                orderMinor = order.minor,
                color = annotation.color.name,
                note = annotation.note?.trim()?.takeIf { it.isNotEmpty() },
                snippet = annotation.snippet,
                createdAtEpochMs = annotation.createdAtEpochMs,
            ),
        )
        return annotation.withNote(annotation.note).copy(id = id)
    }

    override suspend fun update(annotation: Annotation) =
        dao.update(annotation.id, annotation.color.name, annotation.note?.trim()?.takeIf { it.isNotEmpty() })

    override suspend fun remove(id: Long) = dao.delete(id)

    private fun colorOf(name: String): HighlightColor =
        HighlightColor.entries.firstOrNull { it.name == name } ?: HighlightColor.Yellow
}
