package io.github.kgcaudit.reader.pdf

import io.github.kgcaudit.reader.document.BookMeta
import io.github.kgcaudit.reader.document.PagedDocument
import io.github.kgcaudit.reader.document.TocEntry

/**
 * 열린 PDF 한 권. 목차는 없다 — PdfRenderer 는 PDF 의 목차(outline)를 읽지 못한다(결정 P1 ①의 한계).
 */
class PdfBook(override val meta: BookMeta, internal val source: PdfSource) : PagedDocument, AutoCloseable {

    override val pageCount: Int get() = source.pageCount

    private val aspects = HashMap<Int, Float>()

    override suspend fun outline(): List<TocEntry> = emptyList()

    /** 페이지 가로/세로 비. 깨진 페이지는 A 판형으로 본다 — 한 쪽 때문에 책이 멈추지 않게. */
    override suspend fun pageAspectRatio(index: Int): Float = synchronized(aspects) {
        aspects.getOrPut(index) {
            runCatching { source.pageSize(index) }.getOrNull()
                ?.takeIf { (w, h) -> w > 0 && h > 0 }
                ?.let { (w, h) -> w.toFloat() / h }
                ?: PageViewport.DEFAULT_ASPECT
        }
    }

    /** 이미 잰 쪽이면 기다리지 않고 준다. 아직이면 null. */
    fun knownAspectRatio(index: Int): Float? = synchronized(aspects) { aspects[index] }

    override fun close() = source.close()
}
