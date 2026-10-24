package io.github.kgcaudit.reader.data.sync

import java.io.IOException

/**
 * 기기별 이어 읽기 파일을 두는 곳(0.58.0). 지금은 사람이 정한 FTP · FTPS · SFTP 서버의 한 폴더([FtpSyncStore]) 하나다. 시험은
 * 진짜 서버(내장)를 꽂는다.
 *
 * 0.56 까지는 책 폴더 안 `.olo/sync/` 에 쓰고, 사람이 깐 동기화 앱이 그 폴더를 맞춰 주기를 바랐다. 구글 드라이브 · 원드라이브의
 * 안드로이드 앱은 폴더를 양방향으로 맞춰 주지 않아, 따로 앱(Syncthing · FolderSync)을 깔지 않으면 아무 일도 일어나지 않았다. 0.57 의
 * 구글 드라이브 API 는 구글 클라우드에 앱을 등록해야 해 걷어냈다(사용자 결정 2026-10-23: FTP 서버로).
 */
interface SyncStore {
    /** 저장소의 파일 하나. [id] 는 저장소가 가리키는 이름표(FTP · SFTP 는 파일 이름 그대로). */
    data class Entry(val id: String, val name: String, val modifiedEpochMs: Long?, val sizeBytes: Long?)

    suspend fun list(): List<Entry>

    /** 파일 글. [maxBytes] 보다 크면 null(이 앱의 파일이 아니다). */
    suspend fun read(entry: Entry, maxBytes: Long): String?

    /** [name] 으로 쓴다(있으면 덮는다). [existing] 은 저장소가 이미 아는 그 파일(쓰지 않아도 된다). 다른 기기가 반쯤 쓴 글을 읽지 않게 통째로 바꾼다. */
    suspend fun write(name: String, existing: Entry?, text: String): Entry

    /** 지운다. 이미 없으면 조용히 끝난다. */
    suspend fun delete(entry: Entry)

    /**
     * [block] 안의 일을 한 연결로 한다. 맞추기 한 번에 목록 · 읽기 · 쓰기를 여러 번 하는데, FTP 는 일마다 다시 잇고 로그인하면 몇 초씩
     * 걸린다. 연결이 없는 저장소는 그냥 부른다.
     */
    suspend fun <T> session(block: suspend () -> T): T = block()

    /** 쓸 수 있는지 확인한다(연결 확인 단추). 안 되면 [SyncStoreException]. */
    suspend fun check() {
        list()
    }
}

/** 맞추지 못한 까닭 — 화면이 사람에게 할 일을 알려 줄 수 있는 갈래만. */
enum class SyncProblem {
    /** 인터넷이 없다. 연결되면 다음 기회에 다시 맞춘다. */
    Offline,

    /** 서버를 찾지 못했다(주소 · 포트가 틀렸거나 서버가 꺼져 있다). */
    Unreachable,

    /**
     * 서버가 답하지 않는다 — 잇기는 했는데 인사가 오지 않거나, 잇는 데서 시간이 다 됐다. 디스크를 멈춘 NAS 가 깨어나는 중이거나
     * 꺼져 가는 중이 대부분이다. "인터넷 없음" 과 나눈다 — 휴대폰의 인터넷은 멀쩡한데 그렇게 알리면 사람이 엉뚱한 것을 고친다.
     */
    Asleep,

    /** 사용자 · 비밀번호가 맞지 않아 로그인이 거절되었다. */
    Auth,

    /** 로그인은 되었지만 정한 경로에 폴더를 만들거나 쓸 수 없다. */
    NoAccess,

    /** 서버 저장 공간이 가득 찼다. */
    Full,

    /** 보안 연결(FTPS)을 맺지 못했다 — 연결 중에 끊기거나 서로 맞는 암호 방식이 없다. */
    Tls,

    /** 서버가 암호화(FTPS 의 AUTH TLS)를 받지 않는다. 보안 연결 "자동" 이면 화면이 일반 FTP 로 다시 해 본다. */
    NoTls,

    /**
     * 믿을 수 없는 인증서 · 처음 보는 서버 키. NAS 가 스스로 만든 인증서가 대부분이다. [SyncStoreException.fingerprint] 를 사람에게
     * 보이고, 믿으면 설정에 적어 다음부터 그 지문만 받는다. 믿은 뒤 지문이 바뀌면 다시 이것 — 누가 가운데서 가로챌 수 있다.
     */
    Untrusted,

    /**
     * 서버와 맞는 방식이 없다 — SSH 의 키 교환 · 암호 방식이 겹치지 않거나, FTPS 데이터 연결이 TLS 세션 재사용을 요구한다(522).
     * 다시 해도 같아 다시 하지 않는다. 화면이 다른 방식(SFTP · 다른 보안 연결)을 권한다.
     */
    Incompatible,

    /** 서버 쪽 오류. 다음 기회에 다시. */
    Server,
}

class SyncStoreException(
    val problem: SyncProblem,
    message: String,
    cause: Throwable? = null,
    /** [SyncProblem.Untrusted] 일 때 서버가 보인 인증서 · 키의 SHA-256 지문("AB:CD:…"). */
    val fingerprint: String? = null,
) : IOException(message, cause)

/** 연결 확인의 결과. 되면 [problem] 이 null. */
data class SyncCheck(val problem: SyncProblem?, val fingerprint: String? = null)

/** 이어 읽기의 지금 모습. 화면의 "마지막으로 맞춘 때" 줄이 본다. */
data class SyncStatus(val lastSyncedAtEpochMs: Long? = null, val problem: SyncProblem? = null, val busy: Boolean = false)
