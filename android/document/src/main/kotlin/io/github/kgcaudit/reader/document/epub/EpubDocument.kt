package io.github.kgcaudit.reader.document.epub

import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.BookMeta
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.document.SeekableSource
import io.github.kgcaudit.reader.document.SpineItem
import io.github.kgcaudit.reader.document.TocEntry
import io.github.kgcaudit.reader.document.readUpTo
import io.github.kgcaudit.reader.document.text.EncodingDetector
import io.github.kgcaudit.reader.document.text.TextEncoding
import io.github.kgcaudit.reader.document.zip.ZipReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset

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

    override suspend fun openChapterResource(index: Int, href: String): InputStream? {
        val chapter = readingOrder.getOrNull(index) ?: return null
        return zip.openStream(Hrefs.resolve(Hrefs.dirOf(chapter.href), href))
    }

    override suspend fun stylesheets(): List<String> =
        opf.manifestById.values.filter { it.mediaType.equals("text/css", ignoreCase = true) }.map { it.href }.sorted()

    override fun resolveHref(fromPath: String, href: String): String = Hrefs.resolve(Hrefs.dirOf(fromPath), href)

    /** 난독화된 글꼴 목록(경로 → 방식). 책마다 한 번 읽는다. 없거나 깨졌으면 비어 있다. */
    private val obfuscated: Map<String, FontObfuscation.Method> by lazy {
        runCatching { zip.readBytes(FontObfuscation.ENCRYPTION_PATH)?.let(FontObfuscation::parse) }.getOrNull().orEmpty()
    }

    override suspend fun openFont(path: String): InputStream? {
        val stream = zip.openStream(path) ?: return null
        val method = obfuscated[path] ?: return stream
        val key = FontObfuscation.key(method, opf.uniqueIdentifier ?: opf.identifier)
        // 열쇠를 못 만들면(식별자가 없음) 뒤섞인 채로 돌려준다. 받는 쪽이 "읽을 수 없는 글꼴" 로
        // 알아서 기본 글꼴을 쓴다 — 책은 열린다.
        return if (key == null) stream else FontObfuscation.Deobfuscating(stream, key, method.headerBytes)
    }

    /** 표지 이미지. 없으면 null. */
    fun openCoverImage(): InputStream? = opf.coverImageItem?.href?.let(zip::openStream)

    /**
     * 그림만 든 책이면 그 쪽들(0.50.0, [PictureBooks]). 글 책이면 앞 몇 장에서 멈춘다 — 소설을 열 때마다 장을 모두 읽지 않게.
     * 읽을 수 없는 장(항목이 사라짐)은 빈 장으로 친다.
     */
    suspend fun pictureBook(): PictureBook? {
        val seen = ArrayList<PictureBooks.Chapter>()
        for ((i, item) in readingOrder.withIndex()) {
            val chapter = runCatching { openChapter(i).use { PictureBooks.scan(item.href, it) } }.getOrDefault(PictureBooks.Chapter(emptyList(), 0))
            seen += chapter
            if (PictureBooks.alreadyRejected(seen, readingOrder.size)) return null
        }
        return PictureBooks.decide(seen, opf.rightToLeft)?.let { book ->
            // zip 에 없는 그림(깨진 링크)은 쪽에서 뺀다 — 빈 쪽이 끼면 쪽 수 · 진도가 어긋난다.
            book.copy(pages = book.pages.filter { it.path in zip }).takeIf { it.pages.size >= 2 }
        }
    }

    /** 그림책 쪽의 그림 바이트를 연다. */
    fun openPicture(path: String): InputStream? = zip.openStream(path)

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
            // container.xml 이 깨져 파서가 던지면 관례 위치를 보지도 못하고 책 전체가 열리지 않았다 — 실패는 "못 찾음" 으로.
            runCatching { zip.readBytes(ContainerParser.PATH) }.getOrNull()
                ?.let { bytes -> runCatching { ContainerParser.parse(bytes.inputStream().asXhtmlReader()) }.getOrNull() }
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
 * 그래서 TXT 처럼 바이트로 인코딩을 추측하지 않는다 — BOM 이 있으면 그 인코딩, 없으면 XML 선언
 * (`<?xml encoding="euc-kr"?>`)이 적은 인코딩, 그것도 없으면 UTF-8. 여기서 CP949 추측까지 하면 짧은 XML
 * 조각이 오판될 여지만 생긴다.
 *
 * 선언을 읽는 까닭: 규격을 어기고 EUC-KR 로 저장한 옛 한국 EPUB 이 있다. 선언은 제대로 적혀 있는데 UTF-8 로만
 * 읽어 본문 · 목차 · 제목이 통째로 깨졌다.
 */
private fun InputStream.asXhtmlReader(): Reader {
    val buffered = this.buffered(DECLARATION_PEEK)
    buffered.mark(DECLARATION_PEEK)
    val head = ByteArray(DECLARATION_PEEK)
    val read = buffered.readUpTo(head)
    buffered.reset()

    val charset = when {
        read >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte() -> {
            buffered.skip(3); Charsets.UTF_8
        }
        read >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> {
            buffered.skip(2); Charsets.UTF_16LE
        }
        read >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> {
            buffered.skip(2); Charsets.UTF_16BE
        }
        else -> declaredCharset(head, read) ?: Charsets.UTF_8
    }
    return InputStreamReader(buffered, charset)
}

/** XML 선언을 찾으려고 앞에서 보는 바이트. 선언은 문서 맨 앞에 있어야 하고 한 줄이다. */
private const val DECLARATION_PEEK = 4096

/**
 * XML 선언이 적은 인코딩. UTF-8 · 모르는 이름 · 선언 없음이면 null(부르는 쪽이 UTF-8 로 읽는다).
 *
 * 선언을 그대로 믿지 않는 경우가 둘 있다:
 *  - UTF-16 · UTF-32: BOM 없이 선언 글자가 ASCII 로 읽혔다면 그 파일은 UTF-16 이 아니다. 믿으면 글 전체가 한자 더미가 된다.
 *  - 실제 바이트가 UTF-8 로 맞는데 선언만 EUC-KR 인 것: 변환 도구가 옛 선언을 남긴 채 UTF-8 로 다시 저장한 책이 있다.
 *    선언을 믿으면 지금까지 잘 읽히던 그 책들이 깨진다 — 바이트가 말하는 쪽을 믿는다.
 */
private fun declaredCharset(head: ByteArray, length: Int): Charset? {
    val text = String(head, 0, length, Charsets.ISO_8859_1).trimStart()
    if (!text.startsWith("<?xml")) return null
    val end = text.indexOf("?>")
    if (end < 0) return null
    val declaration = text.substring(0, end)
    val at = declaration.indexOf("encoding")
    if (at < 0) return null
    val afterEquals = declaration.substring(at + "encoding".length).trimStart()
    if (!afterEquals.startsWith("=")) return null
    val quoted = afterEquals.substring(1).trimStart()
    val quote = quoted.firstOrNull()?.takeIf { it == '"' || it == '\'' } ?: return null
    val close = quoted.indexOf(quote, startIndex = 1)
    if (close < 0) return null
    val name = quoted.substring(1, close).trim().lowercase()
    val charset = when (name) {
        // 한국 윈도우가 "euc-kr" 이라 적고 실제로는 CP949 확장 글자까지 쓴다 — TXT 와 같은 넓은 쪽으로 읽는다.
        "euc-kr", "euckr", "ks_c_5601-1987", "ksc5601", "cp949", "ms949", "windows-949", "x-windows-949" -> TextEncoding.EUC_KR.charset
        else -> runCatching { Charset.forName(name) }.getOrNull() ?: return null
    }
    if (charset == Charsets.UTF_8 || charset.name().uppercase().let { it.startsWith("UTF-16") || it.startsWith("UTF-32") }) return null
    val body = head.copyOf(length)
    val sample = body.copyOf(EncodingDetector.trimIncompleteTail(body))
    if (sample.any { it < 0 } && EncodingDetector.isValidUtf8(sample)) return null
    return charset
}
