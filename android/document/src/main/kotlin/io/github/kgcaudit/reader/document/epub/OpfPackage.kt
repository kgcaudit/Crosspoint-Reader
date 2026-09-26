package io.github.kgcaudit.reader.document.epub

/** manifest 의 한 항목. [href] 는 zip 엔트리 경로로 이미 풀려 있다. */
data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String?,
    val properties: Set<String> = emptySet(),
)

/** `content.opf` 에서 읽어낸 것. 경로는 모두 zip 엔트리 기준으로 정규화돼 있다. */
data class OpfPackage(
    val title: String?,
    val creator: String?,
    val language: String?,
    val identifier: String?,
    /** `unique-identifier` 가 가리키는 식별자. 없으면 null — 그때는 [identifier] 로 대신한다. */
    val uniqueIdentifier: String? = null,
    /** `content.opf` 가 있는 디렉터리. 챕터 안의 상대 경로를 풀 때 기준이 된다. */
    val baseDir: String,
    val manifestById: Map<String, ManifestItem>,
    val spineIdRefs: List<String>,
    /** `<spine toc="...">` 가 가리키는 manifest id(EPUB 2 목차). */
    val ncxId: String?,
    /** EPUB 2 `<meta name="cover" content="...">` 가 가리키는 manifest id. */
    val coverMetaId: String?,
    val version: String?,
) {
    /**
     * 읽기 순서대로 풀린 spine.
     *
     * manifest 에 없는 idref 는 버린다. 그런 EPUB 이 실제로 있고, 여기서 예외를 던지면
     * 나머지가 멀쩡한 책을 못 열게 된다.
     */
    val spineItems: List<ManifestItem> get() = spineIdRefs.mapNotNull(manifestById::get)

    /** EPUB 3 목차 문서(`properties="nav"`). */
    val navItem: ManifestItem?
        get() = manifestById.values.firstOrNull { "nav" in it.properties }

    /**
     * EPUB 2 목차 문서.
     *
     * `<spine toc>` 를 먼저 보고, 없거나 가리키는 항목이 없으면 미디어 타입으로 찾는다 —
     * `toc` 속성을 빼먹은 책이 흔하다.
     */
    val ncxItem: ManifestItem?
        get() = ncxId?.let(manifestById::get)
            ?: manifestById.values.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }

    /**
     * 표지 이미지.
     *
     * EPUB 3 는 `properties="cover-image"`, EPUB 2 는 `<meta name="cover">` 로 가리킨다.
     * 둘 다 없으면 관례적인 id 를 찾아본다. 표지는 없어도 되는 값이라 못 찾으면 null.
     */
    val coverImageItem: ManifestItem?
        get() = manifestById.values.firstOrNull { "cover-image" in it.properties }
            ?: coverMetaId?.let(manifestById::get)
            ?: manifestById.values.firstOrNull { item ->
                item.id.equals("cover", true) || item.id.equals("cover-image", true)
            }?.takeIf { it.mediaType?.startsWith("image/") == true }

    companion object {
        const val NCX_MEDIA_TYPE: String = "application/x-dtbncx+xml"
    }
}
