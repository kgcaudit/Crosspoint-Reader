package io.github.kgcaudit.reader.data

import android.content.Context
import android.net.Uri
import io.github.kgcaudit.reader.data.backup.MovedRecords
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
import io.github.kgcaudit.reader.document.comic.ArchiveExtensions
import io.github.kgcaudit.reader.document.comic.ComicContents
import io.github.kgcaudit.reader.document.comic.ComicInfo
import io.github.kgcaudit.reader.document.comic.ComicUnit
import io.github.kgcaudit.reader.document.comic.ComicUnitKind
import io.github.kgcaudit.reader.document.comic.NestedArchives
import io.github.kgcaudit.reader.document.extensionOf
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
    private val context: Context,
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
        adoptMoved()
        // 백업에서 가져왔지만 책을 못 찾아 기다리던 기록 — 방금 훑은 폴더에 그 책이 있으면 이제 붙는다. 저장 공간이 모자라
        // 기다림 파일을 못 쓰더라도 훑기는 이미 끝났다 — 그 실패로 훑기 결과까지 버리지 않는다(다음 훑기에 다시 붙인다).
        try {
            records.resumePending()
        } catch (e: java.io.IOException) {
            android.util.Log.w("OloData", "pending records not resumed", e)
        }
        result
    }

    /**
     * 옮긴 파일의 기록을 새 자리에 잇고(0.50.0, [RecordsBackup.adoptMoved]) 옮긴 것을 [onMoved] 로 알린다. 폴더 빼기와 같은
     * 자물쇠 안에서 한다 — 빼는 중인 폴더의 책을 "사라진 책" 으로 보고 다른 폴더의 같은 파일에 기록을 넘기는 일이 사이에
     * 끼지 않게.
     */
    private suspend fun adoptMoved() {
        val moved = folderLock.withLock { records.adoptMoved() }
        if (!moved.isEmpty) onMoved?.invoke(moved)
    }

    /**
     * 옮긴 파일의 기록을 이었을 때 부른다. 앱이 사람이 고른 표지처럼 DB 밖에 둔, 파일 주소로 찾는 것을 함께 옮긴다.
     * 입출력 스레드에서 불린다.
     */
    @Volatile var onMoved: ((MovedRecords) -> Unit)? = null

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
     * 살필 차례인 만화 압축(zip · rar · 7z · cbz · cbt · cbr · cb7)을 연다: 압축의 목록만 읽고(통째로 복사하지 않는다 — 1GB 압축에서 느리고 공간을
     * 먹는다), 그림만 들었는지 · 쪽 수 · 표지 · 합본 목차 · ComicInfo 를 적는다. 하나가 깨져도 나머지는 계속한다.
     *
     * @return 살펴 적은 수. 이번에 읽지 못해 다음으로 미룬 압축은 세지 않는다.
     */
    suspend fun probeComics(): Int = withContext(io) {
        // 살피기 규칙이 바뀌면(0.48.0: 압축 속 권) 옛 규칙이 "만화 아님" 이라 적은 압축을 한 번 다시 본다. 파일이 그대로면
        // 다시 살피지 않아서, 0.48.0 으로 올린 휴대폰에서 "[작가] 작품 (1-3, 완결).zip" 이 계속 빠져 있었다(사용자 보고 —
        // 새로 깐 시험 환경에서는 처음부터 살피니 드러나지 않았다). 표시는 백업에서 빠지는 곳에 둔다: 기록을 새 휴대폰으로
        // 옮기면 그쪽에서도 한 번 다시 본다.
        val mark = File(context.noBackupFilesDir, PROBE_MARK)
        val seen = runCatching { mark.readText().trim().toInt() }.getOrDefault(0)
        if (seen < PROBE_GENERATION) {
            comics.reprobeRejected()
            runCatching { mark.writeText(PROBE_GENERATION.toString()) }
        }
        // 살핀 뒤에 기다리는 만화 기록을 다시 맞춰 본다(0.49.0) — 그냥 zip · 압축 속 권은 살펴야 서재에 보여, 훑기 뒤에 맞추면
        // 아직 없는 권이라 새 휴대폰에서 가져온 만화 기록이 다음 훑기까지 붙지 않았다.
        try {
            return@withContext probeRounds()
        } finally {
            // 압축 속 권 · 그냥 zip 은 살펴야 보이므로, 옮긴 권의 기록도 살핀 뒤에 다시 잇는다(0.50.0).
            runCatching { adoptMoved() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            runCatching { records.resumePending() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    private suspend fun probeRounds(): Int {
        var probed = 0
        // 이번에 읽지 못해 미룬 단위. 다음 바퀴에 또 고르지 않는다 — 고르면 권한이 풀린 클라우드 압축 하나를 한 부름에서 세 번
        // 열려다 실패하고, 살핀 수에도 세 번 들어갔다.
        val deferred = HashSet<String>()
        // 압축 속 권(0.48.0)은 바깥 압축을 살피면서 생긴다 — 같은 부름에서 그 권들까지 살펴야 서재에 바로 보인다. 한 겹만
        // 펼치므로 두 바퀴면 끝나지만, 바퀴 수를 묶어 두어 무엇이 꼬여도 끝없이 돌지 않게 한다.
        repeat(3) {
            // 폴더마다 뺀 횟수를 차례 목록을 받기 **전에** 적는다 — 목록을 받은 뒤에 적으면 그 사이에 뺀 폴더의 압축을 알아채지 못한다.
            val removalsAtStart = HashMap(removals)
            val todo = comics.needingProbe().filter { it.id !in deferred }
            if (todo.isEmpty()) return probed
            probed += probeOnce(todo, removalsAtStart, deferred)
        }
        return probed
    }

    /** @return 적은 수. 적지 못한 단위는 [deferred] 에 더한다. */
    private suspend fun probeOnce(
        todo: List<io.github.kgcaudit.reader.data.db.ComicUnitEntity>,
        removalsAtStart: Map<String, Int>,
        deferred: MutableSet<String>,
    ): Int {
        var saved = 0
        for (unit in todo) {
            currentCoroutineContext().ensureActive()
            var contents: ComicContents? = null
            var info: ComicInfo? = null
            var volumes: List<Pair<String, Long?>>? = null
            try {
                openArchive(unit.id, keep = null).use { archive ->
                    // 압축 속 권은 만화 묶음에서 꺼낸 것이라 cbz 처럼 믿는다 — 그림이 하나라도 있으면 만화다. 안내 글 하나 섞였다고
                    // 묶음의 권 하나가 사라지면 그 권만 빠진 채 이어 보기가 끊긴다.
                    contents = ComicContents.ofArchive(archive.names, trustExtension = unit.extension !in ArchiveExtensions.PLAIN || unit.kind == ComicUnitKind.NESTED.name)
                    contents?.comicInfo?.let { name ->
                        info = runCatching { archive.entry(name)?.reader(Charsets.UTF_8)?.use { ComicInfo.parse(it) } }.getOrNull()
                    }
                    // 한 겹만 펼친다(사용자 결정 ②) — 안에서 꺼낸 권 속의 압축은 보지 않는다.
                    if (unit.kind == ComicUnitKind.ARCHIVE.name) {
                        volumes = NestedArchives.volumes(archive.names).map { it to archive.sizeOf(it) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // 읽기 허락이 잠시 풀렸다 · 제공자(클라우드 · SD 카드)가 빠졌다: 압축이 깨진 것이 아니다. 적지 않고 다음 훑기에
                // 다시 살핀다 — "만화 아님" 으로 적으면 파일이 바뀌기 전까지 그 만화가 서재에서 사라진다.
                deferred += unit.id
                continue
            } catch (e: java.io.FileNotFoundException) {
                deferred += unit.id
                continue
            } catch (e: Exception) {
                // 열 수 없는 압축(깨짐 · 암호). cbz 는 그대로 보이고 zip 은 만화가 아닌 것으로 둔다.
                contents = null
            }
            // 살피는 사이 그 폴더를 뺐으면 적지 않는다 — 권을 적으면(missing = false) 뺀 폴더의 압축 속 권이 서재에 되살아나고,
            // 폴더 목록에는 없어 다시 뺄 수도 없다. 훑기([rescan])와 같은 자물쇠 · 같은 확인이다.
            val written = folderLock.withLock {
                if ((removals[unit.folderUri] ?: 0) != (removalsAtStart[unit.folderUri] ?: 0)) return@withLock false
                // 바깥을 열지 못했으면(volumes == null) 전에 꺼낸 권들은 그대로 둔다 — 잠깐 못 읽은 것으로 권이 사라지지 않게.
                volumes?.let { comics.saveVolumes(unit, it, clock()) }
                comics.saveProbe(unit, contents, info, holdsVolumes = !volumes.isNullOrEmpty())
                true
            }
            if (written) saved++ else deferred += unit.id
        }
        return saved
    }

    /**
     * 단위의 압축을 연다. 압축 속 권(0.48.0)이면 바깥 압축에서 꺼낸다: 압축 없이 담긴 zip 이면 바깥 파일의 그 구간을 그대로
     * 읽고(복사 없음), 아니면 [keep] 폴더에 한 번 꺼내 둔 사본에서 연다. [keep] 이 null 이면(살피기 · 표지) 임시 사본을 쓰고
     * 닫을 때 지운다 — 살피기가 풀어 둔 권 폴더를 늘리면 읽던 권의 폴더가 밀려 지워진다.
     */
    private fun openArchive(id: String, keep: File?): ComicArchive {
        val (outerId, inner) = NestedArchives.split(id) ?: return ComicArchive.open(sources, Uri.parse(id), scratch)
        val outer = ComicArchive.open(sources, Uri.parse(outerId), scratch)
        try {
            if (inner !in outer.names) throw java.io.FileNotFoundException("$inner is no longer in $outerId")
            (outer as? ComicArchive.Zip)?.openStored(inner)?.let { return it }
            val ext = extensionOf(inner) ?: ""
            if (keep != null) {
                val file = File(keep, VOLUME_FILE + ext)
                val done = File(keep, VOLUME_DONE)
                if (!done.isFile || !file.isFile) {
                    keep.mkdirs()
                    done.delete()
                    // 다 꺼냈을 때만 표시를 남긴다 — 반쯤 꺼낸 사본을 다음에 그대로 열면 뒤쪽 쪽들이 사라진다.
                    val tmp = File(keep, "$VOLUME_FILE$ext.part")
                    if (!outer.copyEntry(inner, tmp) || !tmp.renameTo(file)) throw java.io.IOException("cannot copy out $inner")
                    done.createNewFile()
                }
                outer.close()
                return ComicArchive.openFile(file, scratch)
            }
            scratch.mkdirs()
            val tmp = File.createTempFile("volume", ".$ext", scratch)
            if (!outer.copyEntry(inner, tmp)) {
                tmp.delete()
                throw java.io.IOException("cannot copy out $inner")
            }
            outer.close()
            return ComicArchive.openFile(tmp, scratch) { tmp.delete() }
        } catch (e: Throwable) {
            outer.close()
            throw e
        }
    }

    /**
     * 만화 단위의 표지 그림 바이트. 표지 항목을 아직 모르면(살피기 전 zip) null, 파일을 못 읽으면 예외 — 부르는 쪽(표지
     * 보관소)이 "없음" 과 "못 읽음" 을 갈라, 저장소가 빠진 동안 "표지 없음" 으로 굳히지 않게 한다.
     *
     * 압축은 목록(중앙 디렉터리)과 표지 항목 하나만 읽는다. 그림 폴더는 그 안에서 이름이 같은 파일을 찾는다.
     */
    fun comicCover(unit: ComicUnit): ByteArray? {
        val entry = unit.contents?.cover ?: return null
        return when (unit.kind) {
            ComicUnitKind.ARCHIVE, ComicUnitKind.NESTED -> openArchive(unit.id, keep = null).use { it.entry(entry)?.use { s -> s.readBytes() } }
            ComicUnitKind.IMAGE_FOLDER -> folderFiles(Uri.parse(unit.id))[entry]?.let { doc -> resolver.openInputStream(doc)?.use { it.readBytes() } }
        }
    }

    /**
     * 만화 한 권을 연다(0.34.0). 쪽 이름은 이때 다시 읽는다 — 서재는 쪽 수만 들고 있다. 압축은 목록만 읽고 쪽은 볼 때
     * 하나씩 꺼낸다(1GB 압축을 통째로 복사하면 느리고 공간을 먹는다). 그림이 하나도 없으면 [java.io.IOException].
     */
    fun openComic(unit: ComicUnit): ComicPages {
        return when (unit.kind) {
            ComicUnitKind.ARCHIVE, ComicUnitKind.NESTED -> {
                // 압축 속 권은 꺼낸 사본과 풀린 쪽을 같은 폴더에 둔다 — 최근 권만 남기는 규칙([KEEP_UNPACKED])을 함께 따른다.
                val keep = if (unit.kind == ComicUnitKind.NESTED) unpackedDir(unit) else null
                val archive = openArchive(unit.id, keep)
                try {
                    // 이름이 만화라고 말하지 않아도(zip) 여기까지 왔으면 서재가 만화로 본 것이다 — 그림 하나라도 있으면 연다.
                    val contents = ComicContents.ofArchive(archive.names, trustExtension = true)
                        ?: throw java.io.IOException("no pictures in ${unit.name}")
                    if (archive is ComicArchive.Native) {
                        // RAR · 7z: 한 번에 다 풀어 두고 쪽은 풀린 파일에서 읽는다(쪽마다 풀면 통짜 RAR 은 n²). 압축 속 권은
                        // 꺼낸 사본 곁의 하위 폴더에 — 같은 폴더를 비우고 풀면 지금 읽고 있는 사본까지 지운다.
                        val dir = keep?.let { File(it, "pages") } ?: unpackedDir(unit)
                        if (!File(dir, DONE).isFile) {
                            dir.deleteRecursively()
                            // 다 풀었을 때만 표시를 남긴다 — 저장 공간이 모자라 반쯤 풀렸는데 표시가 남으면 다시 풀지 않아 그 쪽들이
                            // 영영 빈 쪽이 된다. 표시가 없으면 다음에 열 때 처음부터 다시 푼다.
                            if (archive.unpackAll(dir)) File(dir, DONE).createNewFile()
                        }
                        archive.close()
                        ComicPages(contents.pages, { name -> File(dir, name).takeIf { it.isFile }?.inputStream() }, null)
                    } else {
                        ComicPages(contents.pages, { name -> archive.entry(name) }, archive)
                    }
                } catch (e: Throwable) {
                    archive.close()
                    throw e
                }
            }
            ComicUnitKind.IMAGE_FOLDER -> {
                val files = folderFiles(Uri.parse(unit.id))
                // 훑기와 같은 쪽 규칙 — 그림 확장자만 보던 때는 맥이 남긴 `._001.jpg` · 숨김 그림이 쪽으로 끼어, 서재의 쪽 수와
                // 어긋나고 그 자리에 깨진 쪽이 보였다.
                val pages = ComicContents.folderPages(files.keys.toList())
                if (pages.isEmpty()) throw java.io.IOException("no pictures in ${unit.name}")
                ComicPages(pages, { name -> files[name]?.let { doc -> resolver.openInputStream(doc) } }, null)
            }
        }
    }

    /** RAR · 7z 를 풀어 둘 곳과 살피기 · 표지용 임시 자리. 캐시 영역 — 시스템이 공간이 모자라면 지워도 다시 풀면 된다. */
    private val scratch = File(context.cacheDir, "comic-scratch").apply { deleteRecursively() }
    private val unpacked = File(context.cacheDir, "comic-unpacked")

    /**
     * 이 권을 풀어 둘 폴더. 같은 권(파일 · 크기 · 수정 시각)이면 다시 열 때 다시 풀지 않는다. 최근 [KEEP_UNPACKED] 권만 남긴다 —
     * 다 두면 만화 몇십 권에 캐시가 기가바이트로 쌓인다.
     *
     * 수정 시각은 지금 파일의 것을 제공자에게 묻는다: 파일 · 크기만 보던 때는 같은 이름 · 같은 크기로 바꿔 넣은 권(다시 받은
     * 스캔본)이 옛 쪽을 보였다. 서재의 값은 마지막 훑기 때의 것이라 훑기 전에 열면 놓친다.
     */
    private fun unpackedDir(unit: ComicUnit): File {
        val key = java.security.MessageDigest.getInstance("SHA-1").digest("${unit.id}|${unit.sizeBytes}|${modifiedOf(unit)}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        val dir = File(unpacked, key)
        // 파일 시각이라 파일 시각끼리 견준다 — [clock] 을 쓰면 시험의 멈춘 시계에서 모든 폴더가 같은 시각이 되어 지울 차례가 엉킨다.
        dir.setLastModified(System.currentTimeMillis())
        unpacked.listFiles()?.filter { it != dir }?.sortedByDescending { it.lastModified() }?.drop(KEEP_UNPACKED - 1)?.forEach { it.deleteRecursively() }
        return dir
    }

    /**
     * 그림 폴더 바로 안의 파일(하위 폴더는 빼고): 이름 → 문서 URI. 훑기와 같은 목록 읽기([SafDocumentTree])를 쓴다 — 제공자의
     * 오류는 [java.io.IOException] 으로 바뀌어 "못 읽음" 으로 올라간다.
     */
    private fun folderFiles(folder: Uri): Map<String, Uri> =
        SafDocumentTree(resolver, folder).children(android.provider.DocumentsContract.getDocumentId(folder))
            .filter { !it.isDirectory }
            .associate { it.name to Uri.parse(it.uri) }

    /** 만화 파일의 지금 수정 시각. 압축 속 권은 바깥 압축의 것. 제공자가 모르거나 못 물으면 null. */
    private fun modifiedOf(unit: ComicUnit): Long? = runCatching {
        val uri = Uri.parse(NestedArchives.split(unit.id)?.first ?: unit.id)
        // Bundle 판 query — 5인자 판은 일부 경로(래퍼 · Robolectric)에서 제공자가 받지 않는다(SafDocumentTree 와 같은 까닭).
        resolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null as android.os.Bundle?, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    /** 폴더 빼기와 훑은 결과 넣기를 차례로 세운다. */
    private val folderLock = Mutex()

    /** 폴더마다 뺀 횟수. 훑는 사이 이 값이 바뀌었으면 그 훑기의 결과는 버린다. */
    private val removals = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private companion object {
        /**
         * 풀어 둔 RAR · 7z 권을 몇 권까지 남길지. 웹툰 이어 보기(0.47.0)는 앞 화 · 지금 화 · 미리 연 다음 화를 함께 쥔다 —
         * 2 였을 때는 다음 화를 미리 열면서 아직 화면에 있는 앞 화의 풀린 파일을 지워, 위로 올리면 그 화가 빈 쪽이 됐다.
         * 하나 더 두어 막 넘어온 화까지.
         */
        const val KEEP_UNPACKED = 4
        /**
         * 살피기 규칙의 세대. 규칙이 바뀌어 옛 "만화 아님" 판정이 틀릴 수 있으면 올린다. 1 = 0.33.0 ~ 0.47.1, 2 = 0.48.0 압축 속 권,
         * 3 = 0.48.3 확장자 없는 표시 파일("zzzzzzzzzz")을 문서로 치지 않음 · 압축 속 권은 믿음, 4 = 압축 속 .rar · .7z 도 권으로
         * 펼침(그 전에는 "1권.rar · 2권.7z" 만 든 zip 이 "만화 아님" 으로 적혔다).
         */
        const val PROBE_GENERATION = 4
        const val PROBE_MARK = "comic-probe-generation"
        /**
         * 다 풀었다는 표시 파일(빈 파일 — 있는지만 본다. 어느 권인지는 폴더 열쇠가 말한다). 풀다가 앱이 닫히거나 공간이 모자라
         * 멈췄으면 없으니, 다음에 다시 푼다.
         */
        const val DONE = ".olo-unpacked"
        /** 압축 속 권(0.48.0)을 꺼내 둔 사본의 이름 앞부분(뒤에 확장자)과, 다 꺼냈다는 표시. */
        const val VOLUME_FILE = ".olo-volume."
        const val VOLUME_DONE = ".olo-volume-done"
    }
}
