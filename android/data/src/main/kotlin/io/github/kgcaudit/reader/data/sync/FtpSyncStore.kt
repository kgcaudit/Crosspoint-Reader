package io.github.kgcaudit.reader.data.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import javax.net.ssl.SSLException

/**
 * FTP 서버 한 곳(0.58.0, 사용자 결정). [path] 폴더에 기기별 파일을 둔다 — 없으면 만든다.
 *
 * @param secure 명시적 FTPS(AUTH TLS). 일반 FTP 는 비밀번호 · 내용이 암호화되지 않고 오간다 — 집 안 공유기 너머의 NAS 라면 괜찮지만
 *   인터넷 너머의 서버라면 FTPS 를 권한다. 인증서는 검증한다(자체 서명 인증서는 [SyncProblem.Tls]).
 * @param path 서버 안 폴더. "/OLO" · "책/이어 읽기" 처럼. 앞 "/" 가 없으면 로그인한 자리에서부터.
 */
data class FtpConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val secure: Boolean = false,
    val user: String,
    val password: String,
    val path: String,
) {
    /** 화면에 보일 한 줄("nas.local · /OLO"). 비밀번호는 넣지 않는다. */
    val label: String get() = (if (port == DEFAULT_PORT) host else "$host:$port") + " · " + path.ifBlank { "/" }

    override fun toString(): String = "FtpConfig($label, secure=$secure, user=$user)"

    companion object {
        const val DEFAULT_PORT: Int = 21
    }
}

/**
 * [FtpConfig] 의 폴더에 이어 읽기 파일을 둔다. 아파치 commons-net 으로 FTP · FTPS 를 쓴다.
 *
 * - **수동 모드(PASV)** 만 쓴다 — 휴대폰은 늘 공유기 · 통신사 NAT 뒤라 서버가 휴대폰으로 거꾸로 잇는 능동 모드는 열리지 않는다.
 * - **통째로 바꾼다**: 임시 이름(`.이름.part`)으로 올린 뒤 이름을 바꾼다. 바로 덮어쓰면 다른 기기가 반쯤 올라간 글을 읽어 깨진
 *   파일로 버렸다가, 다음 목록에서야 다시 읽는다.
 * - 이름은 UTF-8 로 보낸다(OPTS UTF8) — 경로에 한글을 쓰는 사람이 많다. 받지 않는 서버도 ASCII 경로는 그대로 된다.
 * - 한 번 맞추는 동안([session]) 연결 하나를 쓴다. 맞추기 밖에서 부르면 일마다 잇고 끊는다.
 *
 * @param config 지금 설정. 사람이 바꾸면 다음 연결부터 따른다. null 이면 맞추지 않는다.
 */
class FtpSyncStore(
    private val config: () -> FtpConfig?,
    private val timeoutMs: Int = 15_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SyncStore {

    /** [session] 안에서 쓰는 연결. 세션 밖이면 null. */
    @Volatile
    private var open: FTPClient? = null

    override suspend fun <T> session(block: suspend () -> T): T {
        if (open != null) return block()
        val client = withContext(io) { connect() }
        open = client
        try {
            return block()
        } finally {
            open = null
            withContext(io) { close(client) }
        }
    }

    /** [session] 안이면 그 연결로, 밖이면 새로 이어서 한 번. */
    private suspend fun <T> withClient(work: (FTPClient) -> T): T {
        open?.let { c -> return withContext(io) { guard { work(c) } } }
        return session { withContext(io) { guard { work(open!!) } } }
    }

    override suspend fun list(): List<SyncStore.Entry> = withClient { c ->
        val files: Array<FTPFile> = if (c.hasFeature("MLSD")) c.mlistDir() else c.listFiles()
        files.filter { it.isFile }.map { f ->
            SyncStore.Entry(id = f.name, name = f.name, modifiedEpochMs = f.timestamp?.timeInMillis, sizeBytes = f.size.takeIf { it >= 0 })
        }
    }

    override suspend fun read(entry: SyncStore.Entry, maxBytes: Long): String? = withClient { c ->
        if (entry.sizeBytes != null && entry.sizeBytes > maxBytes) return@withClient null
        val out = ByteArrayOutputStream()
        if (!c.retrieveFile(entry.id, out)) {
            // 목록과 읽기 사이에 다른 기기가 바꾸는 중이었다(이름 바꾸기 직전) — 이번엔 건너뛰고 다음에 읽는다.
            if (c.replyCode == FTPReply.FILE_UNAVAILABLE) return@withClient null
            failed(c, "read ${entry.id}")
        }
        if (out.size() > maxBytes) null else out.toString("UTF-8")
    }

    override suspend fun write(name: String, existing: SyncStore.Entry?, text: String): SyncStore.Entry = withClient { c ->
        val bytes = text.toByteArray(Charsets.UTF_8)
        val temp = ".$name.part"
        if (!c.storeFile(temp, ByteArrayInputStream(bytes))) failed(c, "store $temp")
        if (!c.rename(temp, name)) {
            // 이름 바꾸기가 있는 파일을 덮지 않는 서버(일부 윈도 IIS · 오래된 NAS)가 있다. 옛것을 지우고 다시 — 그 사이 잠깐 파일이
            // 없어도 다른 기기는 "이번엔 없음" 으로 넘기고 다음에 읽는다.
            c.deleteFile(name)
            if (!c.rename(temp, name)) {
                c.deleteFile(temp)
                failed(c, "rename $temp")
            }
        }
        SyncStore.Entry(id = name, name = name, modifiedEpochMs = null, sizeBytes = bytes.size.toLong())
    }

    override suspend fun delete(entry: SyncStore.Entry) {
        withClient { c ->
            // 이미 없으면(550) 바라던 대로다.
            if (!c.deleteFile(entry.id) && c.replyCode != FTPReply.FILE_UNAVAILABLE) failed(c, "delete ${entry.id}")
        }
    }

    /** 연결 확인: 잇고 · 로그인 · 폴더 만들기 · 시험 파일 쓰고 지우기. 하나라도 안 되면 그 까닭으로 던진다. */
    override suspend fun check() {
        withClient { c ->
            if (!c.storeFile(PROBE, ByteArrayInputStream(PROBE.toByteArray()))) failed(c, "store probe")
            c.deleteFile(PROBE)
        }
    }

    // ── 잇기 ────────────────────────────────────────────────

    private fun connect(): FTPClient {
        val cfg = config() ?: throw SyncStoreException(SyncProblem.Unreachable, "no ftp server set")
        val client = if (cfg.secure) FTPSClient(false) else FTPClient()
        client.controlEncoding = "UTF-8"
        client.connectTimeout = timeoutMs
        client.defaultTimeout = timeoutMs
        client.setDataTimeout(Duration.ofMillis(timeoutMs.toLong()))
        guard {
            try {
                client.connect(cfg.host.trim(), cfg.port)
                if (!FTPReply.isPositiveCompletion(client.replyCode)) failed(client, "connect")
                client.soTimeout = timeoutMs
                if (!client.login(cfg.user, cfg.password)) throw SyncStoreException(SyncProblem.Auth, "login refused: ${client.replyString?.trim()}")
                if (client is FTPSClient) {
                    // 데이터 연결도 암호화한다(PROT P). 안 하면 이름 · 비밀번호만 감추고 읽은 자리는 그대로 보낸다.
                    client.execPBSZ(0)
                    client.execPROT("P")
                }
                runCatching { client.sendCommand("OPTS UTF8 ON") }
                client.setFileType(FTP.BINARY_FILE_TYPE)
                client.enterLocalPassiveMode()
                enterFolder(client, cfg.path)
            } catch (e: Exception) {
                close(client)
                throw e
            }
        }
        return client
    }

    /** [path] 로 들어간다. 없는 폴더는 한 칸씩 만든다. */
    private fun enterFolder(c: FTPClient, path: String) {
        val trimmed = path.trim()
        if (trimmed.startsWith("/") && !c.changeWorkingDirectory("/")) failed(c, "cwd /", SyncProblem.NoAccess)
        for (part in trimmed.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }) {
            if (c.changeWorkingDirectory(part)) continue
            if (!c.makeDirectory(part) || !c.changeWorkingDirectory(part)) failed(c, "mkdir $part", SyncProblem.NoAccess)
        }
    }

    private fun close(client: FTPClient) {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { client.disconnect() }
    }

    /** 서버의 거절 답을 사람에게 보일 갈래로. */
    private fun failed(c: FTPClient, what: String, fallback: SyncProblem = SyncProblem.Server): Nothing {
        val problem = when (c.replyCode) {
            // 452 · 552: 저장 공간 부족.
            452, 552 -> SyncProblem.Full
            530 -> SyncProblem.Auth
            // 550 · 553: 권한 없음 · 이름 허용 안 됨.
            550, 553 -> SyncProblem.NoAccess
            else -> fallback
        }
        throw SyncStoreException(problem, "$what failed: ${c.replyString?.trim()}")
    }

    /** 소켓 오류를 갈래로 바꾼다. */
    private inline fun <T> guard(work: () -> T): T = try {
        work()
    } catch (e: SyncStoreException) {
        throw e
    } catch (e: SSLException) {
        throw SyncStoreException(SyncProblem.Tls, "tls failed", e)
    } catch (e: UnknownHostException) {
        throw SyncStoreException(SyncProblem.Unreachable, "unknown host", e)
    } catch (e: ConnectException) {
        throw SyncStoreException(SyncProblem.Unreachable, "connection refused", e)
    } catch (e: NoRouteToHostException) {
        throw SyncStoreException(SyncProblem.Offline, "no route", e)
    } catch (e: SocketTimeoutException) {
        throw SyncStoreException(SyncProblem.Offline, "timed out", e)
    } catch (e: FTPConnectionClosedException) {
        throw SyncStoreException(SyncProblem.Offline, "server closed the connection", e)
    } catch (e: IOException) {
        throw SyncStoreException(SyncProblem.Offline, "ftp i/o failed", e)
    }

    private companion object {
        /** 연결 확인에 잠깐 쓰는 이름. 점으로 시작해 기기 파일로 읽히지 않는다. */
        const val PROBE = ".olo-check"
    }
}
