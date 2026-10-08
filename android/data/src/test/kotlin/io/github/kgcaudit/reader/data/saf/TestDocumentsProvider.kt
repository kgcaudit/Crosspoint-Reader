package io.github.kgcaudit.reader.data.saf

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * 임시 폴더를 SAF 로 내보이는 진짜 `DocumentsProvider`.
 *
 * 가짜 커서가 아니라 플랫폼의 `DocumentsProvider` 를 상속한다. 트리 URI 검사
 * (`isChildDocument`), 하위 목록 URI 규칙, `openFile` 로 이어지는 경로가 기기와 같아서,
 * URI 를 잘못 조립하면 여기서 걸린다.
 *
 * 문서 id 는 [base] 기준 상대 경로다(`root/소설/책.epub`).
 */
class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, ROOT_ID).add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_PROJECTION).apply { addRow(this, documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (parentDocumentId in failing) throw IllegalStateException("provider bug in $parentDocumentId")
        if (parentDocumentId in unanswered) return null
        onList?.invoke()
        val dir = fileOf(parentDocumentId)
        return MatrixCursor(projection ?: DEFAULT_PROJECTION).apply {
            dir.listFiles().orEmpty().sortedBy { it.name }.forEach { addRow(this, "$parentDocumentId/${it.name}") }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        opens++
        onOpen?.invoke()
        val file = fileOf(documentId)
        // 쓰기(기기 간 이어 읽기의 기록 파일). "wt" 를 받지 않는 제공자도 흉내 낸다 — 그런 제공자에서도 깨진 JSON 이 남지 않는지.
        if ('w' in mode) {
            if ('t' in mode && !truncates) throw FileNotFoundException("mode $mode not supported")
            if (!file.isFile) throw FileNotFoundException(documentId)
            // 안드로이드 10 부터 많은 제공자가 "w" 를 비우지 않고 덮어쓴다("wt" 가 따로 있는 까닭). 그 모양을 흉내 낸다.
            val flags = if ('t' in mode) ParcelFileDescriptor.parseMode(mode) else ParcelFileDescriptor.MODE_WRITE_ONLY
            return ParcelFileDescriptor.open(file, flags)
        }
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val id = "$parentDocumentId/$displayName"
        val file = fileOf(id)
        if (mimeType == Document.MIME_TYPE_DIR) file.mkdirs() else file.createNewFile()
        return id
    }

    override fun deleteDocument(documentId: String) {
        fileOf(documentId).deleteRecursively()
    }

    private fun addRow(cursor: MatrixCursor, documentId: String) {
        val file = fileOf(documentId)
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, 0)
    }

    companion object {
        const val AUTHORITY = "io.github.kgcaudit.reader.test.documents"
        const val ROOT_ID = "root"

        lateinit var base: File
        var failing: Set<String> = emptySet()

        /** 커서 대신 null 로 답하는 폴더(응답 없는 클라우드 제공자). */
        var unanswered: Set<String> = emptySet()

        /** 폴더 목록을 줄 때마다 부른다. 훑기 한가운데서 무엇을 끼워 넣는 시험용. */
        var onList: (() -> Unit)? = null

        /** 파일을 열 때마다 부른다. 살피기 한가운데서 무엇을 끼워 넣는 시험용. */
        var onOpen: (() -> Unit)? = null

        /** 파일을 연 횟수 — 클라우드 제공자라면 받은 횟수다. */
        var opens: Int = 0

        /** "wt"(비우고 쓰기)를 받는가. 받지 않는 제공자를 흉내 낼 때 끈다. */
        var truncates: Boolean = true

        private val DEFAULT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)

        fun fileOf(documentId: String) = File(base, documentId)

        /** [base] 아래 `root/` 를 만들고 제공자를 등록한다. */
        fun install(base: File): File {
            this.base = base
            failing = emptySet()
            unanswered = emptySet()
            onOpen = null
            opens = 0
            truncates = true
            val root = File(base, ROOT_ID).apply { mkdirs() }
            val info = ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info)
            return root
        }
    }
}
