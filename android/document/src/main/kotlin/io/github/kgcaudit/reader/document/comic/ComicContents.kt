package io.github.kgcaudit.reader.document.comic

/**
 * 만화 단위(압축 하나 · 그림 폴더 하나) 안에 무엇이 들었나 — 항목 이름만 보고 정한다. 압축을 풀지 않는다.
 *
 * @param pages 쪽 그림의 항목 이름, 자연 순서. 표지로 쓰는 그림도 여기 들어 있다.
 * @param sections 안의 하위 폴더(합본의 권 · 화). 폴더 둘 이상에 쪽이 나뉘어 있을 때만 채운다.
 * @param comicInfo `ComicInfo.xml` 항목 이름. 없으면 null.
 */
data class ComicContents(
    val pages: List<String>,
    val sections: List<Section>,
    val comicInfo: String?,
    val cover: String?,
) {
    data class Section(val name: String, val firstPage: Int, val pageCount: Int)

    companion object {
        private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "heif")

        /** 쪽은 아니지만 있어도 만화로 보는 것들. 이것 말고 다른 파일(글 · 실행 파일 …)이 섞이면 만화가 아니다. */
        private val HARMLESS = setOf("xml", "nfo", "sfv", "url", "db", "ini", "md5", "sha1", "json")

        fun isImageName(name: String): Boolean =
            name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in IMAGE

        internal fun isJunk(path: String): Boolean {
            val base = path.substringAfterLast('/')
            return path.startsWith("__MACOSX/") || path.contains("/__MACOSX/") || base.startsWith(".") ||
                base.equals("Thumbs.db", ignoreCase = true) || base.equals("desktop.ini", ignoreCase = true)
        }

        /**
         * 압축 항목 이름들로 만화인지 · 무엇이 들었는지 정한다.
         *
         * @param trustExtension cbz · cbr 처럼 이름이 이미 만화라고 말하는 파일. 그림이 하나라도 있으면 만화다.
         *   그냥 zip 이면 그림이 둘 이상이고 다른 것이 섞이지 않아야 만화다 — 사진 묶음 zip 과 소설 zip 을 가르려고.
         * @return 만화가 아니면 null.
         */
        fun ofArchive(entryNames: List<String>, trustExtension: Boolean): ComicContents? {
            val files = entryNames.filter { !it.endsWith("/") && !isJunk(it) }
            val pages = files.filter(::isImageName).sortedWith(NaturalOrder)
            if (pages.isEmpty()) return null
            // 안에 든 권 압축은 따로 펼친다(NestedArchives) — 여기서 "다른 파일" 로 치면 그림과 권이 함께 든 zip 이 통째로 빠진다.
            val others = files.filter { !isImageName(it) && !NestedArchives.isVolumeName(it) }
            if (!trustExtension) {
                if (pages.size < 2) return null
                // 확장자가 없는 파일은 문서가 아니다 — 리디에서 받은 권에는 끝에 0바이트 "zzzzzzzzzz" 표시 파일이 붙는데, 이것을
                // 정체 모를 파일로 쳐서 그 권들이 통째로 "만화 아님" 이 됐다(사용자 보고, 0.48.3). 글 · 실행 파일은 확장자로 걸린다.
                if (others.any { it.substringAfterLast('/').substringAfterLast('.', "").lowercase().let { ext -> ext.isNotEmpty() && ext !in HARMLESS } }) return null
            }
            val info = files.firstOrNull { it.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) }
            return of(pages, info)
        }

        /** 그림 폴더(폴더 바로 아래의 파일 이름들). 그림이 [MIN_FOLDER_PAGES] 장 미만이면 만화가 아니다. */
        fun ofFolder(fileNames: List<String>): ComicContents? {
            val pages = fileNames.filter { !isJunk(it) && isImageName(it) }.sortedWith(NaturalOrder)
            if (pages.size < MIN_FOLDER_PAGES) return null
            // 같은 폴더에 책(EPUB · PDF · TXT)이 있으면 그림은 그 책의 부속(표지 · 삽화)이지 만화가 아니다.
            if (fileNames.any { it.substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS }) return null
            val info = fileNames.firstOrNull { it.equals("ComicInfo.xml", ignoreCase = true) }
            return of(pages, info)
        }

        /**
         * 이 장 수부터 그림 폴더를 만화로 본다. 한두 장뿐인 폴더는 표지(`cover.jpg`) · 작가 사진 같은 부속이다.
         */
        const val MIN_FOLDER_PAGES: Int = 3

        private val BOOK_EXTENSIONS = setOf("epub", "pdf", "txt")

        private fun of(pages: List<String>, info: String?): ComicContents {
            // 모든 쪽을 감싼 폴더 하나("별을 줍는 아이 1권/001.jpg")는 걷어 내고 그 아래를 본다.
            val wrapper = commonDirectory(pages)
            val inner = pages.map { it.removePrefix(wrapper) }
            val sections = ArrayList<Section>()
            if (inner.any { '/' in it }) {
                var start = 0
                while (start < inner.size) {
                    val dir = inner[start].substringBefore('/', "")
                    var end = start
                    while (end < inner.size && inner[end].substringBefore('/', "") == dir) end++
                    sections += Section(dir.ifEmpty { "" }, start, end - start)
                    start = end
                }
            }
            // 폴더 둘 이상에 나뉘어야 합본의 안 목차다. 하나뿐이면 목차가 없는 것과 같다.
            val named = sections.filter { it.name.isNotEmpty() }
            val cover = pages.firstOrNull { it.substringAfterLast('/').substringBeforeLast('.').lowercase().let { b -> b == "cover" || b == "folder" || b.startsWith("cover") } }
                ?: pages.first()
            return ComicContents(pages, if (named.size >= 2) sections else emptyList(), info, cover)
        }

        private fun commonDirectory(paths: List<String>): String {
            val first = paths.first()
            var prefix = first.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            while (prefix.isNotEmpty() && paths.any { !it.startsWith(prefix) }) {
                prefix = prefix.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            }
            return prefix
        }
    }
}
