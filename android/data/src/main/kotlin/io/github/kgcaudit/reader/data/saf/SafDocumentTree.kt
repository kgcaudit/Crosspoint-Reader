package io.github.kgcaudit.reader.data.saf

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.kgcaudit.reader.data.library.DocumentTree
import io.github.kgcaudit.reader.data.library.TreeEntry
import java.io.IOException

/**
 * SAF 트리 URI 를 [DocumentTree] 로 본다.
 *
 * `DocumentFile.listFiles()` 를 쓰지 않는다. 그건 목록을 받은 뒤 파일마다 이름·크기·
 * 종류를 **따로 질의**해서, 책 500권 폴더에서 질의가 2000번 나간다. 여기서는 폴더당
 * 한 번에 필요한 열을 모두 받는다.
 */
class SafDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : DocumentTree {

    override val rootKey: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun children(key: String): List<TreeEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, key)
        // 제공자는 다른 앱이다. 그 쪽의 버그(IllegalStateException, NullPointerException …)가
        // 바인더를 건너 그대로 올라오므로, 여기서 "이 폴더를 못 읽음" 으로 바꾼다. 그러지
        // 않으면 클라우드 앱 하나의 오류로 라이브러리 스캔 전체가 죽는다.
        val cursor = try {
            // Bundle 판 query 를 부른다. DocumentsProvider 는 O 부터 이 판만 받고, 기기의
            // ContentResolver 도 결국 이 판으로 보낸다. 5인자 판을 거치는 경로(일부 래퍼,
            // Robolectric)에서는 제공자가 UnsupportedOperationException 을 던진다.
            resolver.query(childrenUri, PROJECTION, null as Bundle?, null)
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("provider failed to list $childrenUri", e)
        }
        // null 은 "제공자가 답하지 않았다" 다. 빈 목록으로 바꾸면 그 폴더의 책이 전부
        // 사라진 것으로 처리된다.
        cursor ?: throw IOException("provider returned no cursor for $childrenUri")
        return cursor.use { it.readEntries() }
    }

    private fun Cursor.readEntries(): List<TreeEntry> {
        val id = getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        val name = getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        val mime = getColumnIndex(Document.COLUMN_MIME_TYPE)
        val size = getColumnIndex(Document.COLUMN_SIZE)
        val modified = getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        if (id < 0 || name < 0) throw IOException("provider omitted document id or name")

        val entries = ArrayList<TreeEntry>(count.coerceAtLeast(0))
        while (moveToNext()) {
            val documentId = getString(id) ?: continue
            val displayName = getString(name) ?: continue
            entries += TreeEntry(
                key = documentId,
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
                name = displayName,
                isDirectory = mime >= 0 && getString(mime) == Document.MIME_TYPE_DIR,
                sizeBytes = longOrNull(size),
                lastModifiedEpochMs = longOrNull(modified),
            )
        }
        return entries
    }

    private fun Cursor.longOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
