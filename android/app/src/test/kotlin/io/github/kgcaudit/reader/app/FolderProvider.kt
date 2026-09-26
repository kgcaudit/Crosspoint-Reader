package io.github.kgcaudit.reader.app

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
 * 임시 폴더를 SAF 로 내보이는 `DocumentsProvider`. 사용자가 폴더 선택기에서 고른
 * 폴더 자리를 대신한다. (`:data` 테스트의 것과 같은 방식, 실패 흉내는 뺐다.)
 */
class FolderProvider : DocumentsProvider() {
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply { row(this, documentId) }

    override fun queryChildDocuments(parent: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply {
            File(base, parent).listFiles().orEmpty().sortedBy { it.name }.forEach { row(this, "$parent/${it.name}") }
        }

    override fun isChildDocument(parent: String, documentId: String) = documentId.startsWith("$parent/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = File(base, documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun row(cursor: MatrixCursor, id: String) {
        val file = File(base, id)
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    companion object {
        const val AUTHORITY = "io.github.kgcaudit.oloebook.test.folders"
        const val ROOT = "Books"
        lateinit var base: File
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)

        fun install(base: File): File {
            this.base = base
            Robolectric.buildContentProvider(FolderProvider::class.java).create(
                ProviderInfo().apply {
                    authority = AUTHORITY
                    exported = true
                    grantUriPermissions = true
                    readPermission = Manifest.permission.MANAGE_DOCUMENTS
                    writePermission = Manifest.permission.MANAGE_DOCUMENTS
                },
            )
            return File(base, ROOT).apply { mkdirs() }
        }
    }
}
