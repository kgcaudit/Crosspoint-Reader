package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.BookMeta
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.SpineItem
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.document.zip.ZipReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * EPUB 2 / 3 문서.
 *
 * 컨테이너 → OPF → 목차를 엮어 [ReflowDocument] 로 보여 준다. 조판·리더·책갈피는
 * TXT 와 똑같은 경로를 타므로, 이 클래스가 포맷 차이를 흡수하는 마지막 지점이다.
 *
 * 열 때 zip 중앙 디렉터리와 OPF·목차만 읽는다. 챕터 본문은 실제로 읽을 때 꺼낸다 —
 * 책 열기 예산(캐시 적중 300ms)을 지키려면 여는 시점에 본문을 건드리면 안 된다.
 */
class EpubDocument private constructor(
    override val meta: BookMeta,
    private val zip: ZipReader,
    private val opf: OpfPackage,
) : ReflowDocument, Closeable {

    /** zip 에 실제로 존재하는 spine 항목만 추린 읽기 순서. */
    private val readingOrder: List<ManifestItem> =
        opf.spineItems.filter { it.href in zip }

    private val outline: List<TocEntry> by lazy { loadOutline() }

    override suspend fun outline(): List<TocEntry> = outline

    override suspend fun spine(): List<SpineItem> = readingOrder.mapIndexed { index, item ->
        SpineItem(index = index, href = item.href, sizeBytes = zip.entries[item.href]?.size ?: 0)
    }

    override suspend fun openChapter(index: Int): Reader {
        val item = readingOrder.getOrNull(index)
            ?: throw IOException("spine index $index out of range (size=${readingOrder.size})")
        val stream = zip.openStream(item.href)
            ?: throw IOException("chapter entry missing from archive: ${item.href}")
        return stream.asXhtmlReader()
    }

    override suspend fun openResource(href: String): InputStream? = zip.openStream(href)

    /** 표지 이미지. 없으면 null. */
    fun openCoverImage(): InputStream? = opf.coverImageItem?.href?.let(zip::openStream)

    override fun close() = zip.close()

    // ── 목차 ────────────────────────────────────────────────────────

    /**
     * 목차를 읽는다. EPUB 3 의 nav 를 먼저 보고, 없거나 비면 EPUB 2 의 NCX 로 내려간다.
     *
     * 둘 다 가진 책이 많고(하위 호환용), 그럴 때 nav 가 더 정확하다. 다만 nav 가 있는데
     * 내용이 비는 책이 있어서 비면 NCX 를 다시 본다 — 목차를 통째로 잃는 것보다 낫다.
     */
    private fun loadOutline(): List<TocEntry> {
        val fromNav = opf.navItem
            ?.let { item -> readRawToc(item.href) { reader, dir -> NavParser.parse(reader, dir) } }
            .orEmpty()
        val raw = fromNav.ifEmpty {
            opf.ncxItem
                ?.let { item -> readRawToc(item.href) { reader, dir -> NcxParser.parse(reader, dir) } }
                .orEmpty()
        }
        return raw.mapNotNull(::toTocEntry)
    }

    private fun readRawToc(
        href: String,
        parse: (Reader, String) -> List<RawTocEntry>,
    ): List<RawTocEntry> = runCatching {
        zip.openStream(href)?.use { parse(it.asXhtmlReader(), Hrefs.dirOf(href)) }
    }.getOrNull().orEmpty()

    /**
     * 목차 href 를 spine 위치로 바꾼다.
     *
     * 읽기 순서에 없는 파일을 가리키는 항목은 버린다(`linear="no"` 인 표지를 가리키는
     * 목차가 흔하다). 눌러도 아무 일이 없는 항목을 남기는 것보다 목록에서 빼는 게 낫다.
     */
    private fun toTocEntry(raw: RawTocEntry): TocEntry? {
        val spineIndex = spineIndexByHref[raw.href] ?: return null
        return TocEntry(
            label = raw.label,
            locator = Locator.Reflow(spine = spineIndex, charOffset = 0),
            depth = raw.depth,
            anchor = raw.fragment,
        )
    }

    private val spineIndexByHref: Map<String, Int> =
        readingOrder.withIndex().associate { (index, item) -> item.href to index }

    companion object {

        /**
         * EPUB 을 연다. 실패하면 [source] 를 닫고 [IOException] 을 던진다.
         *
         * @param displayName 파일명. OPF 에 제목이 없을 때 쓴다 — 제목이 빈 책이 실제로
         *   있고, 라이브러리 목록에 빈 줄이 생기면 고를 수가 없다.
         */
        @Throws(IOException::class)
        fun open(id: BookId, displayName: String, source: SeekableSource): EpubDocument {
            val zip = ZipReader.open(source)
            return runCatching {
                val opfPath = findOpfPath(zip) ?: throw IOException("no OPF package document found")
                val opfBytes = zip.readBytes(opfPath) ?: throw IOException("OPF entry unreadable: $opfPath")
                val opf = OpfParser.parse(opfBytes.inputStream().asXhtmlReader(), opfPath)

                val meta = BookMeta(
                    id = id,
                    format = BookFormat.EPUB,
                    title = opf.title?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.', displayName),
                    author = opf.creator?.takeIf { it.isNotBlank() },
                    language = opf.language?.takeIf { it.isNotBlank() },
                )
                EpubDocument(meta, zip, opf)
            }.getOrElse { error ->
                runCatching { zip.close() }
                throw if (error is IOException) error else IOException("not a readable EPUB", error)
            }
        }

        /**
         * `content.opf` 의 위치를 찾는다.
         *
         * 규격대로는 `META-INF/container.xml` 이 가리킨다. 그 파일이 없거나 깨진 책이
         * 있어서, 못 찾으면 관례적인 위치를 순서대로 열어 본다 — "이 책은 못 읽습니다"
         * 보다 낫다.
         */
        private fun findOpfPath(zip: ZipReader): String? {
            zip.readBytes(ContainerParser.PATH)
                ?.let { ContainerParser.parse(it.inputStream().asXhtmlReader()) }
                ?.takeIf { it in zip }
                ?.let { return it }

            ContainerParser.FALLBACK_OPF_PATHS.firstOrNull { it in zip }?.let { return it }

            // 마지막 수단: 아무 위치에 있는 .opf 를 찾는다.
            return zip.entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        }
    }
}

/**
 * EPUB 안의 XML/XHTML 을 읽을 [Reader] 를 만든다.
 *
 * EPUB 규격은 본문을 UTF-8 또는 UTF-16 으로 제한하고, 실제로는 거의 전부 UTF-8 이다.
 * 그래서 TXT 처럼 인코딩을 추측하지 않고 BOM 만 본다 — BOM 이 있으면 그 인코딩, 없으면
 * UTF-8. 여기서 CP949 추측까지 하면 짧은 XML 조각이 오판될 여지만 생긴다.
 */
private fun InputStream.asXhtmlReader(): Reader {
    val buffered = this.buffered()
    buffered.mark(3)
    val head = ByteArray(3)
    val read = buffered.read(head)

    val charset = when {
        read >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte() -> {
            Charsets.UTF_8 // BOM 3바이트는 이미 소비됐다
        }
        read >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> {
            buffered.reset(); buffered.skip(2); Charsets.UTF_16LE
        }
        read >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> {
            buffered.reset(); buffered.skip(2); Charsets.UTF_16BE
        }
        else -> {
            buffered.reset(); Charsets.UTF_8
        }
    }
    return InputStreamReader(buffered, charset)
}
