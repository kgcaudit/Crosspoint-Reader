package io.github.kgcaudit.reader.layout.cache

import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.layout.ChapterIndex
import io.github.kgcaudit.reader.layout.EncodedChapter
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.Page
import io.github.kgcaudit.reader.layout.PageCodec
import java.io.Closeable
import java.io.File

/**
 * 조판 결과를 디스크에 두는 곳.
 *
 * 디렉터리 구조는 `<root>/<책>/<조판설정>/<챕터>.{txt,idx,run,obj}` 다. 책을 위에 둔
 * 이유: 책을 지우면 그 책의 캐시가 설정별로 몇 벌 있든 한 번에 사라진다. 설정을 위에
 * 두면 책 하나를 지우려고 모든 설정 디렉터리를 뒤져야 한다.
 *
 * 설정 디렉터리 이름은 [LayoutSpec.cacheKey] 다. 글자 크기나 여백을 바꾸면 다른
 * 디렉터리가 되므로 옛 페이지가 화면에 섞이지 않는다 — 조판을 바꿨는데 옛 화면이
 * 보이는 버그를 **구조로** 막는다.
 *
 * 이름에 해시를 쓰는 이유: 책 식별자는 `content://` URI 나 파일 경로라서 `/` 와 `:` 가
 * 들어 있고, 그대로 디렉터리 이름으로 쓸 수 없다.
 */
class PageStore(private val root: File) {

    fun chapter(bookId: BookId, spec: LayoutSpec, spineIndex: Int): ChapterCache =
        ChapterCache(layoutDir(bookId, spec), spineIndex)

    /** 이 책의 캐시를 전부 지운다. */
    fun delete(bookId: BookId) {
        bookDir(bookId).deleteRecursively()
    }

    /**
     * 지금 설정의 캐시만 남기고 이 책의 다른 조판 캐시를 지운다.
     *
     * 사용자가 글자 크기를 몇 번 만지면 설정 디렉터리가 그만큼 쌓인다. 돌아올 수도
     * 있는 값이라 즉시 지우는 게 늘 옳지는 않지만, 남겨 두면 한 권이 캐시를 여러 벌
     * 차지한다 — 기본은 정리하는 쪽으로 둔다.
     */
    fun pruneOtherLayouts(bookId: BookId, keep: LayoutSpec) {
        val keepName = keep.cacheKey
        bookDir(bookId).listFiles()
            ?.filter { it.isDirectory && it.name != keepName }
            ?.forEach { it.deleteRecursively() }
    }

    /** 캐시가 차지한 바이트. 설정 화면의 "캐시 지우기" 에 쓴다. */
    fun sizeBytes(): Long =
        root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll() {
        root.deleteRecursively()
    }

    private fun bookDir(bookId: BookId) = File(root, hash(bookId.value))

    private fun layoutDir(bookId: BookId, spec: LayoutSpec) = File(bookDir(bookId), spec.cacheKey)

    private companion object {
        /** FNV-1a. 짧고 플랫폼·버전에 무관하게 같은 값이 나온다([LayoutSpec.cacheKey] 와 같은 이유). */
        fun hash(text: String): String {
            var value = -0x340d631b7bdddcdbL
            for (ch in text) {
                value = value xor ch.code.toLong()
                value *= 0x100000001b3L
            }
            return value.toULong().toString(16).padStart(16, '0')
        }
    }
}

/**
 * 챕터 하나의 캐시.
 *
 * 읽기는 **색인부터** 본다. 머리말이 알아볼 수 없거나 텍스트 길이가 어긋나면 캐시
 * 전체를 없는 것으로 취급한다 — 짝이 맞지 않는 캐시로 화면을 그리면 글자가 밀리는데,
 * 증상이 "가끔 이상하다" 로 나타나 원인을 찾기 어렵다. 다시 조판하는 편이 싸다.
 */
class ChapterCache internal constructor(
    private val dir: File,
    private val spineIndex: Int,
) {

    private val textFile = File(dir, "$spineIndex.txt")
    private val indexFile = File(dir, "$spineIndex.idx")
    private val runsFile = File(dir, "$spineIndex.run")
    private val objectsFile = File(dir, "$spineIndex.obj")

    val exists: Boolean get() = indexFile.isFile && textFile.isFile

    /** 머리말. 없거나 알아볼 수 없으면 null. */
    fun readIndex(): ChapterIndex? {
        val bytes = indexFile.readBytesOrNull() ?: return null
        val header = PageCodec.decodeIndex(bytes) ?: return null
        // 텍스트와 색인이 서로 다른 조판에서 왔을 수 있다. 길이로 걸러 낸다.
        return if (header.textLength == textLength()) header else null
    }

    /** 조판 텍스트. 좌표계의 원본이다. 없으면 null. */
    fun readText(): String? = textFile.readTextOrNull()

    /**
     * 페이지마다 시작 글자 오프셋. 색인 파일만 읽는다.
     *
     * 책을 열 때 가장 먼저 필요한 값이다. 저장된 책갈피가 몇 번째 페이지인지 알아야
     * 첫 화면을 그릴 수 있고, 그걸 위해 챕터를 되돌릴 이유는 없다.
     */
    fun readPageStarts(): IntArray? {
        if (readIndex() == null) return null
        return indexFile.readBytesOrNull()?.let(PageCodec::decodeStarts)
    }

    /**
     * [charOffset] 이 놓인 페이지 번호. 캐시가 없으면 null.
     *
     * 이분 탐색이다. 첫 페이지보다 앞이면 0, 마지막 페이지보다 뒤면 마지막 —
     * 조판이 바뀌어 글자 수가 줄어든 캐시에서도 화면이 비지 않게 한다.
     */
    fun pageOf(charOffset: Int): Int? {
        val starts = readPageStarts() ?: return null
        if (starts.isEmpty()) return null

        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= charOffset) low = mid else high = mid - 1
        }
        return low
    }

    fun readPage(pageIndex: Int): Page? {
        readIndex() ?: return null
        val encoded = EncodedChapter(
            index = indexFile.readBytesOrNull() ?: return null,
            runs = runsFile.readBytesOrNull() ?: ByteArray(0),
            objects = objectsFile.readBytesOrNull() ?: ByteArray(0),
        )
        return PageCodec.decodePage(encoded, pageIndex)
    }

    /** 챕터 하나를 한 번에 쓴다. 조판이 이미 끝난 경우에 쓴다. */
    fun write(text: String, pages: List<Page>) {
        writer(text).use { writer ->
            pages.forEach(writer::add)
            writer.finish()
        }
    }

    fun delete() {
        listOf(textFile, indexFile, runsFile, objectsFile).forEach { it.delete() }
    }

    /**
     * 조판하면서 이어 쓰는 쓰기 도구.
     *
     * 페이지가 나오는 대로 받아 두고 [ChapterWriter.flush] 때 디스크에 내린다. 중간에
     * 내려 두는 이유: 큰 챕터를 조판하다 앱이 내려가면 처음부터 다시 해야 하는데,
     * 부분 캐시가 있으면 앞쪽은 즉시 보여 주고 뒤쪽만 이어서 조판할 수 있다.
     */
    fun writer(text: String): ChapterWriter = ChapterWriter(this, text)

    internal fun writeFiles(text: String, pages: List<Page>, complete: Boolean) {
        dir.mkdirs()
        val encoded = PageCodec.encode(pages, text.length, complete)

        // 임시 파일에 쓴 뒤 바꿔 넣는다. 그렇지 않으면 쓰다 만 색인이 멀쩡한 캐시처럼
        // 읽힐 수 있다. 색인을 **마지막에** 바꿔 넣는 것이 중요하다 — 읽기가 색인부터
        // 보므로, 색인이 새것이면 나머지도 이미 새것이다.
        textFile.writeAtomically(text.toByteArray(Charsets.UTF_8))
        runsFile.writeAtomically(encoded.runs)
        objectsFile.writeAtomically(encoded.objects)
        indexFile.writeAtomically(encoded.index)
    }

    private fun textLength(): Int = textFile.readTextOrNull()?.length ?: -1

    private companion object {
        fun File.readBytesOrNull(): ByteArray? = runCatching { readBytes() }.getOrNull()

        fun File.readTextOrNull(): String? =
            runCatching { readText(Charsets.UTF_8) }.getOrNull()

        fun File.writeAtomically(bytes: ByteArray) {
            val temp = File(parentFile, "$name.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(this)) {
                // 같은 이름이 이미 있으면 rename 이 실패하는 파일 시스템이 있다.
                delete()
                if (!temp.renameTo(this)) {
                    temp.copyTo(this, overwrite = true)
                    temp.delete()
                }
            }
        }
    }
}

/** [ChapterCache.writer] 로 만든다. */
class ChapterWriter internal constructor(
    private val cache: ChapterCache,
    private val text: String,
) : Closeable {

    private val pages = ArrayList<Page>()
    private var flushedCount = -1
    private var finished = false

    val pageCount: Int get() = pages.size

    fun add(page: Page) {
        pages.add(page)
    }

    /** 지금까지 받은 페이지를 **부분 캐시**로 내린다. 받은 게 없으면 아무것도 하지 않는다. */
    fun flush() {
        if (pages.isEmpty() || pages.size == flushedCount) return
        cache.writeFiles(text, pages, complete = false)
        flushedCount = pages.size
    }

    /** 조판이 끝났다고 표시하고 내린다. 이 뒤로는 부분 캐시가 아니다. */
    fun finish() {
        cache.writeFiles(text, pages, complete = true)
        flushedCount = pages.size
        finished = true
    }

    /**
     * 끝내지 않고 닫으면 **부분 캐시로** 남긴다.
     *
     * 조판이 중간에 취소됐다는 뜻이므로 complete 로 표시해서는 안 된다. 그러면 뒤쪽이
     * 없는 챕터를 "다 됐다" 고 읽어 마지막 페이지에서 책이 끝난 것처럼 보인다.
     */
    override fun close() {
        if (!finished) flush()
    }
}
