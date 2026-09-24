package io.github.kgcaudit.reader.pdf

import android.os.ParcelFileDescriptor
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.BookMeta
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.PagedDocument
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.document.pdf.PdfStructure
import io.github.kgcaudit.reader.document.pdf.PdfStructureReader
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 열린 PDF 한 권. 쪽은 [source](PdfRenderer)가 그리고, 목차·제목은 [open] 이 파일 구조에서 따로 읽는다 —
 * PdfRenderer 는 쪽을 그릴 줄만 안다.
 */
class PdfBook(
    override val meta: BookMeta,
    internal val source: PdfSource,
    private val contents: List<TocEntry> = emptyList(),
    /** 제목이 파일에 적혀 있었다(아니면 [meta] 의 제목은 파일 이름에서 만든 것). */
    val hasOwnTitle: Boolean = false,
) : PagedDocument, AutoCloseable {

    override val pageCount: Int get() = source.pageCount

    private val aspects = HashMap<Int, Float>()

    /** 목차. 엔진이 센 쪽 수를 넘는 항목(파일 구조와 엔진이 어긋남)은 누르면 빈 화면이라 뺀다. */
    override suspend fun outline(): List<TocEntry> =
        contents.filter { (it.locator as Locator.FixedPage).page < pageCount }

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

    companion object {
        /**
         * 파일의 목차·제목을 **먼저** 읽고 나서 엔진에 넘긴다. 엔진이 쓰기 시작한 디스크립터를 옆에서 같이
         * 읽으면 서로의 읽기 위치를 흔들 수 있다(PdfRenderer 는 위치를 옮겨 가며 읽는다).
         *
         * 목차를 못 읽어도 책은 연다(빈 목차). [engine] 이 실패하면 디스크립터는 엔진이 닫는다.
         */
        fun open(
            id: BookId,
            fileName: String,
            descriptor: ParcelFileDescriptor,
            engine: (ParcelFileDescriptor) -> PdfSource,
        ): PdfBook {
            val structure = runCatching { DescriptorSource(descriptor).use(PdfStructureReader::read) }
                .getOrDefault(PdfStructure.EMPTY)
            // 제목이 적혀 있지 않으면 파일 이름(확장자 뺀 것).
            val meta = BookMeta(
                id = id,
                format = BookFormat.PDF,
                title = structure.title ?: fileName.substringBeforeLast('.').ifBlank { fileName },
                author = structure.author,
            )
            val contents = structure.outline.map { TocEntry(it.label, Locator.FixedPage(it.pageIndex), it.depth) }
            return PdfBook(meta, engine(descriptor), contents, hasOwnTitle = structure.title != null)
        }
    }
}

/**
 * 엔진에 넘길 디스크립터를 **빌려** 읽는 원천. 위치를 지정해 읽으므로(pread) 디스크립터의 읽기 위치를
 * 옮기지 않고, 닫지도 않는다 — 닫는 것은 디스크립터의 주인(엔진)이다.
 *
 * 복제(dup)해서 읽고 복제본을 닫는 길은 쓰지 않는다. 환경에 따라 복제본이 원본과 같은 파일 객체를 가리켜,
 * 복제본을 닫으면 엔진이 받을 원본까지 닫혔다(시험에서 실제로 걸렸다 — 쪽이 하나도 그려지지 않는다).
 */
private class DescriptorSource(descriptor: ParcelFileDescriptor) : SeekableSource {
    // 파일 디스크립터로 만든 스트림은 그 디스크립터의 주인이 아니다(안드로이드). 닫지 않고 버린다.
    private val channel: FileChannel = FileInputStream(descriptor.fileDescriptor).channel
    override val size: Long = channel.size()

    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(dest, destOffset, length), offset)

    override fun close() = Unit
}
