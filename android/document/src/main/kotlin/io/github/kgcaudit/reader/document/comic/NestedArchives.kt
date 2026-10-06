package io.github.kgcaudit.reader.document.comic

import io.github.kgcaudit.reader.document.extensionOf

/**
 * 압축 속 압축(0.48.0, 사용자 결정: 권으로 펼침 · 한 겹만). 웹툰 · 완결 만화는 흔히 권별 zip 을 다시 zip 하나로 묶어 둔다
 * ("[작가] 작품 (1-3, 완결).zip" → 폴더 → "작품 1권.zip" …). 바깥 zip 에는 그림이 바로 없어서 "만화 아님" 으로 적혀 서재에서
 * 통째로 빠졌다. 안의 권 압축을 하나씩 만화 단위로 꺼내 보인다.
 *
 * 이름만 보고 정한다 — 압축을 여는 일은 데이터 층이 한다.
 */
object NestedArchives {

    /**
     * 단위 id 에서 바깥 압축 주소와 안쪽 경로를 가르는 글. 문서 URI 는 `#` 을 `%23` 으로 적으므로 날것의 `#` 이 나오지 않는다 —
     * 이 글이 바깥 주소 안에 들어 있을 수 없다.
     */
    const val SEPARATOR: String = "#!/"

    /**
     * 권으로 펼칠 안쪽 압축인가: 서재 훑기가 만화로 보는 압축([ArchiveExtensions.ALL] — rar · 7z 포함)과 같다. 점이 없는 "zip"
     * 은 확장자가 아니다([extensionOf]) · 맥이 남긴 `._1권.zip` 은 [ComicContents.isJunk] 가 거른다.
     */
    fun isVolumeName(path: String): Boolean {
        if (path.endsWith("/") || ComicContents.isJunk(path)) return false
        return extensionOf(path) in ArchiveExtensions.ALL
    }

    /** 바깥 압축 항목들 중 권으로 펼칠 것, 자연 순서. */
    fun volumes(entryNames: List<String>): List<String> = entryNames.filter(::isVolumeName).sortedWith(NaturalOrder)

    fun id(outerId: String, inner: String): String = outerId + SEPARATOR + inner

    /** (바깥 id, 안쪽 경로). 압축 속 권이 아니면 null. */
    fun split(id: String): Pair<String, String>? {
        val at = id.indexOf(SEPARATOR)
        if (at <= 0) return null
        val inner = id.substring(at + SEPARATOR.length)
        return if (inner.isEmpty()) null else id.substring(0, at) to inner
    }

    /**
     * 안쪽 권이 든 폴더 이름들: 바깥 압축이 든 폴더들 + 바깥 압축 이름 + 안의 폴더들. 바깥 압축 이름과 같은 맨 위 폴더는
     * 걷어 낸다 — 압축 프로그램이 폴더째 묶으면 늘 그 폴더가 생겨, 두면 "모은 곳" 에 같은 이름이 두 번 나온다.
     */
    fun folders(outerFolders: List<String>, outerName: String, inner: String): List<String> {
        val dirs = inner.split('/').dropLast(1).filter { it.isNotEmpty() }
        val stem = outerName.substringBeforeLast('.', outerName)
        val trimmed = if (dirs.firstOrNull()?.trim() == stem.trim()) dirs.drop(1) else dirs
        return outerFolders + outerName + trimmed
    }
}
