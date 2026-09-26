package io.github.kgcaudit.reader.data.library

import io.github.kgcaudit.reader.document.BookFormat
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
 * @param complete 모든 폴더를 끝까지 읽었는가. false 면 [books] 에 없는 책이 **없어진
 *   것인지 못 본 것인지 모른다** — 그러니 아무것도 숨기면 안 된다.
 */
data class ScanResult(val books: List<ScannedBook>, val complete: Boolean)

/**
 * 등록 폴더 아래를 재귀로 훑어 EPUB·TXT·PDF 를 찾는다.
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
        var complete = true
        val visited = HashSet<String>()

        // 재귀 대신 명시적 스택. 깊은 트리에서 스택이 넘치지 않고, 취소를 폴더마다 본다.
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(tree.rootKey to 0)
        visited += tree.rootKey

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (key, depth) = pending.removeLast()

            val entries = try {
                tree.children(key)
            } catch (e: IOException) {
                // 폴더 하나를 못 읽어도 나머지는 계속 찾는다. 대신 이번 스캔은 불완전하다.
                complete = false
                continue
            } catch (e: SecurityException) {
                complete = false
                continue
            }

            for (entry in entries) {
                // 숨김 항목(.thumbnails, .trash, .epub 같은 이름뿐인 파일)은 책이 아니다.
                if (entry.name.startsWith('.')) continue

                if (entry.isDirectory) {
                    if (depth + 1 > MAX_DEPTH) {
                        complete = false
                        continue
                    }
                    if (visited.add(entry.key)) pending.addLast(entry.key to depth + 1)
                    continue
                }

                val format = BookFormat.fromFileName(entry.name) ?: continue
                books += ScannedBook(entry.uri, entry.name, format, entry.sizeBytes, entry.lastModifiedEpochMs)
            }
        }

        // 같은 파일이 두 경로로 보이면(링크) 한 번만 올린다. 둘 다 올리면 목록에 같은
        // 책이 두 번 나오고, 한쪽의 책갈피가 다른 쪽에서 안 보인다.
        return ScanResult(books.distinctBy { it.uri }, complete)
    }
}
