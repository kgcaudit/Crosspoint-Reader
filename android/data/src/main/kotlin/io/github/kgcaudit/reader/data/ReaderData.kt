package io.github.kgcaudit.reader.data

import android.content.Context
import android.net.Uri
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.library.Library
import io.github.kgcaudit.reader.data.library.LibraryScanner
import io.github.kgcaudit.reader.data.library.ScanResult
import io.github.kgcaudit.reader.data.saf.LibraryFolders
import io.github.kgcaudit.reader.data.saf.SafDocumentTree
import io.github.kgcaudit.reader.data.saf.UriSources
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.ProgressRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * :data 가 앱에 내주는 것 전부. `AppContainer` 가 하나 만들어 들고 있는다.
 *
 * DB 는 열 때 비용이 크고 두 개를 열면 서로의 쓰기를 못 본다. 그래서 이 객체도 앱에
 * 하나여야 한다.
 */
class ReaderData(
    context: Context,
    val database: ReaderDatabase = ReaderDatabase.open(context),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolver = context.applicationContext.contentResolver

    val bookmarks: BookmarkRepository = RoomBookmarkRepository(database.bookmarks())
    val progress: ProgressRepository = RoomProgressRepository(database.progress())
    val library: Library = Library(database)
    val folders: LibraryFolders = LibraryFolders(resolver)
    val sources: UriSources = UriSources(resolver, File(context.cacheDir, "spool"))

    /**
     * 등록 폴더 하나를 다시 훑어 라이브러리에 반영한다.
     *
     * 폴더를 아예 못 읽으면(권한이 사라짐, 제공자 무응답) 불완전한 스캔이 되어 아무것도
     * 숨기지 않는다. 결과를 돌려주므로 화면이 "일부 폴더를 읽지 못했습니다" 를 띄울 수 있다.
     */
    suspend fun rescan(folderUri: Uri): ScanResult = withContext(io) {
        val result = try {
            LibraryScanner.scan(SafDocumentTree(resolver, folderUri))
        } catch (e: IllegalArgumentException) {
            // 트리 URI 가 아닌 값(옛 버전이 저장한 파일 URI 등)은 읽을 수 없는 폴더로 본다.
            ScanResult(emptyList(), complete = false)
        }
        library.applyScan(folderUri.toString(), result, clock())
        result
    }

    /** 등록된 폴더 전부를 훑는다. 한 폴더의 실패가 다른 폴더를 막지 않는다. */
    suspend fun rescanAll(): Map<Uri, ScanResult> = folders.folders().associateWith { rescan(it) }

    /** 폴더 등록을 풀고 그 책들을 목록에서 뺀다. 진도·책갈피는 남는다. */
    suspend fun removeFolder(folderUri: Uri) {
        folders.unregister(folderUri)
        withContext(io) { library.forgetFolder(folderUri.toString()) }
    }
}
