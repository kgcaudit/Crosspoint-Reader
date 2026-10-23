package io.github.kgcaudit.reader.data.sync

import java.io.IOException

/**
 * 기기별 이어 읽기 파일을 두는 곳(0.57.0). 지금은 구글 드라이브의 앱 전용 공간([DriveSyncStore]) 하나다. 시험은 가짜를 꽂는다 —
 * 진짜 드라이브 없이 두 기기가 맞춰지는지를 본다.
 *
 * 0.56 까지는 책 폴더 안 `.olo/sync/` 에 쓰고, 사람이 깐 동기화 앱이 그 폴더를 맞춰 주기를 바랐다. 구글 드라이브 · 원드라이브의
 * 안드로이드 앱은 폴더를 양방향으로 맞춰 주지 않아, 따로 앱(Syncthing · FolderSync)을 깔지 않으면 아무 일도 일어나지 않았다.
 */
interface SyncStore {
    /** 저장소의 파일 하나. [id] 는 저장소가 정한 이름표(드라이브 파일 id) — 이름이 같은 파일이 둘일 수 있어 이름으로 가리키지 않는다. */
    data class Entry(val id: String, val name: String, val modifiedEpochMs: Long?, val sizeBytes: Long?)

    suspend fun list(): List<Entry>

    /** 파일 글. [maxBytes] 보다 크면 null(이 앱의 파일이 아니다). */
    suspend fun read(entry: Entry, maxBytes: Long): String?

    /** [existing] 이 있으면 그 파일을 덮어쓰고, 없으면 [name] 으로 새로 만든다. 쓴 파일. */
    suspend fun write(name: String, existing: Entry?, text: String): Entry

    /** 지운다. 이미 없으면 조용히 끝난다. */
    suspend fun delete(entry: Entry)
}

/** 맞추지 못한 까닭 — 화면이 사람에게 할 일을 알려 줄 수 있는 갈래만. */
enum class SyncProblem {
    /** 인터넷이 없다. 연결되면 다음 기회에 다시 맞춘다. */
    Offline,

    /** 구글 계정 연결이 없거나 허락이 취소되었다. 사람이 다시 연결해야 한다. */
    SignedOut,

    /** 드라이브 저장 공간이 가득 찼다. */
    Full,

    /** 드라이브 쪽 오류(잠깐 몰림 · 서버 오류). 다음 기회에 다시. */
    Server,
}

class SyncStoreException(val problem: SyncProblem, message: String, cause: Throwable? = null) : IOException(message, cause)

/** 이어 읽기의 지금 모습. 화면의 "마지막으로 맞춘 때" 줄이 본다. */
data class SyncStatus(val lastSyncedAtEpochMs: Long? = null, val problem: SyncProblem? = null, val busy: Boolean = false)
