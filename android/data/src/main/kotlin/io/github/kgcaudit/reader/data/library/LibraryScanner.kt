package io.github.kgcaudit.reader.data.library

import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.comic.ArchiveExtensions
import io.github.kgcaudit.reader.document.comic.ComicContents
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.extensionOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** 폴더 트리의 한 항목. SAF 에서는 `DocumentsContract` 의 한 행이다. */
data class TreeEntry(
    /** 트리 안에서 이 항목을 다시 찾는 열쇠(SAF 의 document id). */
    val key: String,
    /** 이 항목을 여는 URI. 책이면 이 값이 `BookId` 가 된다. */
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    /** null 은 "모름". 0 바이트와 다르다. */
    val sizeBytes: Long? = null,
    val lastModifiedEpochMs: Long? = null,
)

/**
 * 폴더 트리. 스캔 규칙을 SAF 없이 시험하려고 둔 경계다.
 *
 * 하위 목록을 못 읽으면 [IOException] 을 던진다. 빈 목록과 구별해야 한다 — 빈 목록이면
 * "책이 사라졌다" 로 처리되지만, 못 읽은 것은 "모른다" 다.
 */
interface DocumentTree {
    val rootKey: String

    /** 등록 폴더 자체의 이름("Comics"). 만화 작품 이름의 마지막 후보 · 모은 곳 표시에 쓴다. 모르면 빈 글. */
    val rootName: String get() = ""

    /** 등록 폴더 자체의 문서 URI. 등록 폴더가 곧 그림 폴더(웹툰 한 화)일 때 그 단위의 열쇠다. 모르면 null. */
    val rootUri: String? get() = null

    @Throws(IOException::class)
    fun children(key: String): List<TreeEntry>
}

/** 스캔에서 찾은 책 한 권. */
data class ScannedBook(
    val uri: String,
    val displayName: String,
    val format: BookFormat,
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long?,
)

/**
 * 훑기에서 찾은 만화 단위 하나(0.33.0): 만화 압축(cbz · cbr · cb7 · cbt), 만화일 수 있는 zip, 그림 폴더.
 *
 * @param folders 등록 폴더 이름부터 이 단위가 든 폴더까지. 작품 이름이 파일 이름에 없을 때 쓴다.
 * @param extension 소문자 확장자. 그림 폴더는 빈 글. "zip" · "rar" · "7z" 는 살펴서 그림만 들어 있어야 만화다.
 * @param folderContents 그림 폴더의 쪽 · 표지(훑으며 이미 목록을 읽었으므로 따로 살피지 않는다).
 */
data class ScannedComic(
    val uri: String,
    val name: String,
    val folders: List<String>,
    val kind: ComicUnitKind,
    val extension: String,
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long?,
    val folderContents: ComicContents? = null,
)

/**
 * @param complete 모든 폴더를 끝까지 읽었는가. false 면 [books] 에 없는 책이 **없어진
 *   것인지 못 본 것인지 모른다** — 그러니 아무것도 숨기면 안 된다.
 */
data class ScanResult(
    val books: List<ScannedBook>,
    val complete: Boolean,
    val comics: List<ScannedComic> = emptyList(),
)

/**
 * 등록 폴더 아래를 재귀로 훑어 EPUB·TXT·PDF 와 만화 단위를 찾는다.
 */
object LibraryScanner {

    /**
     * 이보다 깊이는 내려가지 않는다.
     *
     * 제공자가 심볼릭 링크를 따라가면 트리가 끝나지 않을 수 있다. 방문한 key 로 막지만,
     * key 가 매번 새로 만들어지는 제공자도 있어서 깊이로 한 번 더 막는다. 책을 스무 단계
     * 아래 두는 사람은 없다.
     */
    const val MAX_DEPTH: Int = 20

    suspend fun scan(tree: DocumentTree): ScanResult {
        val books = ArrayList<ScannedBook>()
        val comics = ArrayList<ScannedComic>()
        var complete = true
        val visited = HashSet<String>()

        // 재귀 대신 명시적 스택. 깊은 트리에서 스택이 넘치지 않고, 취소를 폴더마다 본다. 폴더마다 등록 폴더에서 거기까지의
        // 이름들과 그 폴더 자신의 URI 를 함께 든다 — 만화 작품 이름 · 그림 폴더 단위의 열쇠로 쓴다.
        class Pending(val key: String, val depth: Int, val names: List<String>, val uri: String?, val size: Long?, val modified: Long?)
        val pending = ArrayDeque<Pending>()
        pending.addLast(Pending(tree.rootKey, 0, listOf(tree.rootName), tree.rootUri, null, null))
        visited += tree.rootKey

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = pending.removeLast()

            val entries = try {
                tree.children(dir.key)
            } catch (e: IOException) {
                // 폴더 하나를 못 읽어도 나머지는 계속 찾는다. 대신 이번 스캔은 불완전하다.
                complete = false
                continue
            } catch (e: SecurityException) {
                complete = false
                continue
            }

            val visible = entries.filter { !it.name.startsWith('.') }
            // 그림 폴더: 그림이 세 장 이상이고 책이 없는 폴더는 그 자체로 만화 한 권(웹툰이면 한 화)이다. 하위 폴더는 그대로
            // 더 훑는다 — 표지 몇 장과 화 폴더가 함께 있는 작품 폴더도 있다.
            val files = visible.filter { !it.isDirectory }
            val uri = dir.uri
            if (uri != null) {
                ComicContents.ofFolder(files.map { it.name })?.let { contents ->
                    val images = files.filter { ComicContents.isImageName(it.name) }
                    comics += ScannedComic(
                        uri = uri,
                        name = dir.names.last(),
                        folders = dir.names.dropLast(1),
                        kind = ComicUnitKind.IMAGE_FOLDER,
                        extension = "",
                        sizeBytes = images.mapNotNull { it.sizeBytes }.takeIf { it.size == images.size }?.sum(),
                        lastModifiedEpochMs = images.mapNotNull { it.lastModifiedEpochMs }.maxOrNull() ?: dir.modified,
                        folderContents = contents,
                    )
                }
            }

            for (entry in visible) {
                if (entry.isDirectory) {
                    if (dir.depth + 1 > MAX_DEPTH) {
                        complete = false
                        continue
                    }
                    if (visited.add(entry.key)) {
                        pending.addLast(Pending(entry.key, dir.depth + 1, dir.names + entry.name, entry.uri, entry.sizeBytes, entry.lastModifiedEpochMs))
                    }
                    continue
                }

                // 만화로 볼 압축 목록은 압축 속 권 펼치기와 하나다(ArchiveExtensions).
                val extension = extensionOf(entry.name)
                if (extension != null && extension in ArchiveExtensions.ALL) {
                    comics += ScannedComic(entry.uri, entry.name, dir.names, ComicUnitKind.ARCHIVE, extension, entry.sizeBytes, entry.lastModifiedEpochMs)
                    continue
                }
                val format = BookFormat.fromFileName(entry.name) ?: continue
                books += ScannedBook(entry.uri, entry.name, format, entry.sizeBytes, entry.lastModifiedEpochMs)
            }
        }

        // 같은 파일이 두 경로로 보이면(링크) 한 번만 올린다. 둘 다 올리면 목록에 같은
        // 책이 두 번 나오고, 한쪽의 책갈피가 다른 쪽에서 안 보인다.
        return ScanResult(books.distinctBy { it.uri }, complete, comics.distinctBy { it.uri })
    }
}
