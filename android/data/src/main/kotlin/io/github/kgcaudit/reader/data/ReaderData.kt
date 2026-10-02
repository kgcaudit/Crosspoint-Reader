package io.github.kgcaudit.reader.data

import android.content.Context
import android.net.Uri
import io.github.kgcaudit.reader.data.backup.RecordsBackup
import io.github.kgcaudit.reader.data.db.ReaderDatabase
import io.github.kgcaudit.reader.data.library.ComicLibrary
import io.github.kgcaudit.reader.data.library.Library
import io.github.kgcaudit.reader.data.library.LibraryScanner
import io.github.kgcaudit.reader.data.library.ScanResult
import io.github.kgcaudit.reader.data.saf.LibraryFolders
import io.github.kgcaudit.reader.data.saf.SafDocumentTree
import io.github.kgcaudit.reader.data.saf.UriSources
import io.github.kgcaudit.reader.document.AnnotationRepository
import io.github.kgcaudit.reader.document.BookmarkRepository
import io.github.kgcaudit.reader.document.ProgressRepository
import io.github.kgcaudit.reader.document.comic.ComicContents
import io.github.kgcaudit.reader.document.comic.ComicInfo
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.zip.ZipReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val progress: ProgressRepository = RoomProgressRepository(database.progress(), database.recent())
    val annotations: AnnotationRepository = RoomAnnotationRepository(database.annotations())
    val library: Library = Library(database)
    val comics: ComicLibrary = ComicLibrary(database)
    val folders: LibraryFolders = LibraryFolders(resolver)
    val sources: UriSources = UriSources(resolver, File(context.cacheDir, "spool"))

    /** 읽기 기록 백업. 못 찾은 책의 기록은 filesDir 에 둔다 — 캐시에 두면 휴대폰이 공간을 비울 때 지워진다. */
    val records: RecordsBackup = RecordsBackup(database, File(context.filesDir, "pending-records.json"), clock)

    /**
     * 등록 폴더 하나를 다시 훑어 라이브러리에 반영한다.
     *
     * 폴더를 아예 못 읽으면(권한이 사라짐, 제공자 무응답) 불완전한 스캔이 되어 아무것도
     * 숨기지 않는다. 결과를 돌려주므로 화면이 "일부 폴더를 읽지 못했습니다" 를 띄울 수 있다.
     */
    suspend fun rescan(folderUri: Uri): ScanResult = withContext(io) {
        val key = folderUri.toString()
        // 훑기를 시작할 때 받아 둔 폴더 목록에 방금 뺀 폴더가 있을 수 있다 — 훑지 않고 넘긴다(권한이 없어 "일부 폴더를
        // 읽지 못했습니다" 가 잘못 뜬다). 같은 폴더를 다시 등록했으면 훑는다.
        if (removals.containsKey(key) && folderUri !in folders.folders()) return@withContext ScanResult(emptyList(), complete = true)
        val removalsAtStart = removals[key] ?: 0
        val result = try {
            LibraryScanner.scan(SafDocumentTree(resolver, folderUri))
        } catch (e: IllegalArgumentException) {
            // 트리 URI 가 아닌 값(옛 버전이 저장한 파일 URI 등)은 읽을 수 없는 폴더로 본다.
            ScanResult(emptyList(), complete = false)
        }
        // 훑는 사이 그 폴더를 뺐으면 결과를 넣지 않는다. 넣으면 뺀 폴더의 책이 목록에 다시 살아나고, 폴더 목록에는 없어
        // 다시 뺄 수도 없는 책으로 영영 남는다. 확인과 넣기를 빼기와 같은 자물쇠 안에서 해 사이에 끼어들 틈을 없앤다.
        val applied = folderLock.withLock {
            if ((removals[key] ?: 0) != removalsAtStart) return@withLock false
            library.applyScan(key, result, clock())
            comics.applyScan(key, result, clock())
            true
        }
        if (!applied) return@withContext ScanResult(emptyList(), complete = true)
        // 백업에서 가져왔지만 책을 못 찾아 기다리던 기록 — 방금 훑은 폴더에 그 책이 있으면 이제 붙는다.
        records.resumePending()
        result
    }

    /** 등록된 폴더 전부를 훑는다. 한 폴더의 실패가 다른 폴더를 막지 않는다. */
    suspend fun rescanAll(): Map<Uri, ScanResult> = folders.folders().associateWith { rescan(it) }

    /** 폴더 등록을 풀고 그 책들을 목록에서 뺀다. 진도·책갈피는 남는다. */
    suspend fun removeFolder(folderUri: Uri) = withContext(io) {
        folderLock.withLock {
            folders.unregister(folderUri)
            library.forgetFolder(folderUri.toString())
            comics.forgetFolder(folderUri.toString())
            removals[folderUri.toString()] = (removals[folderUri.toString()] ?: 0) + 1
        }
    }

    /**
     * 살필 차례인 만화 압축(zip · cbz)을 연다: 압축 끝의 목록만 읽고(통째로 복사하지 않는다 — 1GB 압축에서 느리고 공간을
     * 먹는다), 그림만 들었는지 · 쪽 수 · 표지 · 합본 목차 · ComicInfo 를 적는다. 하나가 깨져도 나머지는 계속한다.
     *
     * @return 살핀 수.
     */
    suspend fun probeComics(): Int = withContext(io) {
        val todo = comics.needingProbe()
        for (unit in todo) {
            currentCoroutineContext().ensureActive()
            var contents: ComicContents? = null
            var info: ComicInfo? = null
            try {
                ZipReader.open(sources.seekableSource(Uri.parse(unit.id))).use { zip ->
                    contents = ComicContents.ofArchive(zip.entries.keys.toList(), trustExtension = unit.extension != "zip")
                    contents?.comicInfo?.let { name ->
                        info = runCatching { zip.openStream(name)?.reader(Charsets.UTF_8)?.use { ComicInfo.parse(it) } }.getOrNull()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 열 수 없는 압축(깨짐 · 암호 · 제공자 오류). cbz 는 그대로 보이고 zip 은 만화가 아닌 것으로 둔다.
                contents = null
            }
            comics.saveProbe(unit, contents, info)
        }
        todo.size
    }

    /**
     * 만화 단위의 표지 그림 바이트. 표지 항목을 아직 모르면(살피기 전 zip) null, 파일을 못 읽으면 예외 — 부르는 쪽(표지
     * 보관소)이 "없음" 과 "못 읽음" 을 갈라, 저장소가 빠진 동안 "표지 없음" 으로 굳히지 않게 한다.
     *
     * 압축은 목록(중앙 디렉터리)과 표지 항목 하나만 읽는다. 그림 폴더는 그 안에서 이름이 같은 파일을 찾는다.
     */
    fun comicCover(unit: ComicUnit): ByteArray? {
        val entry = unit.contents?.cover ?: return null
        val uri = Uri.parse(unit.id)
        return when (unit.kind) {
            ComicUnitKind.ARCHIVE -> ZipReader.open(sources.seekableSource(uri)).use { zip -> zip.openStream(entry)?.use { it.readBytes() } }
            ComicUnitKind.IMAGE_FOLDER -> {
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri, android.provider.DocumentsContract.getDocumentId(uri))
                val projection = arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                // Bundle 판 query — 5인자 판은 일부 경로(래퍼 · Robolectric)에서 제공자가 받지 않는다(SafDocumentTree 와 같은 까닭).
                val id = resolver.query(children, projection, null as android.os.Bundle?, null)?.use { c ->
                    var found: String? = null
                    while (found == null && c.moveToNext()) if (c.getString(1) == entry) found = c.getString(0)
                    found
                } ?: return null
                resolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, id))?.use { it.readBytes() }
            }
        }
    }

    /** 폴더 빼기와 훑은 결과 넣기를 차례로 세운다. */
    private val folderLock = Mutex()

    /** 폴더마다 뺀 횟수. 훑는 사이 이 값이 바뀌었으면 그 훑기의 결과는 버린다. */
    private val removals = java.util.concurrent.ConcurrentHashMap<String, Int>()
}
