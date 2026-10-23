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
 * 서버 한 곳의 폴더에 이어 읽기 파일을 둔다. FTP · FTPS 는 아파치 commons-net, SFTP 는 JSch([SftpConnection]).
 *
 * - **통째로 바꾼다**: 임시 이름(`.이름.part`)으로 올린 뒤 이름을 바꾼다. 바로 덮어쓰면 다른 기기가 반쯤 올라간 글을 읽어 깨진
 *   파일로 버렸다가, 다음 목록에서야 다시 읽는다.
 * - 한 번 맞추는 동안([session]) 연결 하나를 쓴다. 맞추기 밖에서 부르면 일마다 잇고 끊는다.
 * - [FtpSecurity.Auto] 는 명시적 FTPS 로 다룬다 — 일반 FTP 로 내려가는 것은 설정 화면이 사람에게 보이며 정한다.
 *
 * @param config 지금 설정. 사람이 바꾸면 다음 연결부터 따른다. null 이면 맞추지 않는다.
 */
class FtpSyncStore(
    private val config: () -> FtpConfig?,
    private val timeoutMs: Int = 15_000,
    /** 잠든 서버를 한 번 더 기다리는 시간. 디스크를 멈춘 NAS 는 깨어나 첫 답을 하기까지 20 ~ 40초 걸린다. */
    private val wakeTimeoutMs: Int = 45_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SyncStore {

    /** 서버와의 연결 하나. 오류는 [SyncStoreException] 으로 바꿔 던진다. */
    internal interface Connection {
        fun list(): List<SyncStore.Entry>

        /** 없으면(목록과 읽기 사이에 다른 기기가 바꾸는 중) null. */
        fun read(name: String, maxBytes: Long): String?

        fun write(name: String, bytes: ByteArray)

        /** 이미 없으면 조용히 끝난다. */
        fun delete(name: String)

        fun close()
    }

    /** [session] 안에서 쓰는 연결. 세션 밖이면 null. */
    @Volatile
    private var open: Connection? = null

    override suspend fun <T> session(block: suspend () -> T): T {
        if (open != null) return block()
        val connection = withContext(io) { connect() }
        open = connection
        try {
            return block()
        } finally {
            open = null
            withContext(io) { connection.close() }
        }
    }

    /** [session] 안이면 그 연결로, 밖이면 새로 이어서 한 번. */
    private suspend fun <T> withConnection(work: (Connection) -> T): T {
        open?.let { c -> return withContext(io) { work(c) } }
        return session { withContext(io) { work(open!!) } }
    }

    override suspend fun list(): List<SyncStore.Entry> = withConnection { it.list() }

    override suspend fun read(entry: SyncStore.Entry, maxBytes: Long): String? = withConnection { c ->
        if (entry.sizeBytes != null && entry.sizeBytes > maxBytes) null else c.read(entry.id, maxBytes)
    }

    override suspend fun write(name: String, existing: SyncStore.Entry?, text: String): SyncStore.Entry = withConnection { c ->
        val bytes = text.toByteArray(Charsets.UTF_8)
        c.write(name, bytes)
        SyncStore.Entry(id = name, name = name, modifiedEpochMs = null, sizeBytes = bytes.size.toLong())
    }

    override suspend fun delete(entry: SyncStore.Entry) {
        withConnection { it.delete(entry.id) }
    }

    /**
     * 연결 확인: 잇고 · 로그인 · 폴더 만들기 · 시험 파일 쓰고 지우기. 하나라도 안 되면 그 까닭으로 던진다. 쓰던 연결이 있어도 새로 잇는다 —
     * 확인은 늘 지금 설정으로 해야 한다.
     */
    override suspend fun check() {
        withContext(io) {
            val c = connect()
            try {
                c.write(PROBE, PROBE.toByteArray())
                c.delete(PROBE)
            } finally {
                c.close()
            }
        }
    }

    /**
     * 잇는다. 답이 없으면([SyncProblem.Asleep]) 한 번만 더 넉넉히 기다린다 — 첫 시도가 잠든 NAS 를 깨우고, 깨어나는 동안 두 번째가
     * 기다린다. 처음부터 길게 기다리지 않는 까닭: 꺼진 서버라면 책을 열 때마다 그만큼 띠가 늦는다.
     */
    private fun connect(): Connection {
        val cfg = config() ?: throw SyncStoreException(SyncProblem.Unreachable, "no server set")
        return try {
            open(cfg, timeoutMs)
        } catch (e: SyncStoreException) {
            if (e.problem != SyncProblem.Asleep || wakeTimeoutMs <= timeoutMs) throw e
            open(cfg, wakeTimeoutMs)
        }
    }

    private fun open(cfg: FtpConfig, timeout: Int): Connection = when (cfg.protocol) {
        FtpProtocol.Sftp -> SftpConnection.open(cfg, timeout)
        FtpProtocol.Ftp -> FtpConnection.open(cfg, timeout)
    }

    internal companion object {
        /** 연결 확인에 잠깐 쓰는 이름. 점으로 시작해 기기 파일로 읽히지 않는다. */
        const val PROBE = ".olo-check"

        /** 올리는 중의 이름. 점으로 시작해 다른 기기의 목록에서 기기 파일로 읽히지 않는다. */
        fun partName(name: String) = ".$name.part"
    }
}

/** FTP · FTPS 연결. */
internal class FtpConnection private constructor(private val c: FTPClient) : FtpSyncStore.Connection {

    override fun list(): List<SyncStore.Entry> = guard {
        val files: Array<FTPFile> = if (c.hasFeature("MLSD")) c.mlistDir() else c.listFiles()
        files.filter { it.isFile }.map { f ->
            SyncStore.Entry(id = f.name, name = f.name, modifiedEpochMs = f.timestamp?.timeInMillis, sizeBytes = f.size.takeIf { it >= 0 })
        }
    }

    override fun read(name: String, maxBytes: Long): String? = guard {
        val out = ByteArrayOutputStream()
        if (!c.retrieveFile(name, out)) {
            if (c.replyCode == FTPReply.FILE_UNAVAILABLE) return@guard null
            failed(c, "read $name")
        }
        if (out.size() > maxBytes) null else out.toString("UTF-8")
    }

    override fun write(name: String, bytes: ByteArray) = guard {
        val temp = FtpSyncStore.partName(name)
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
    }

    override fun delete(name: String) = guard {
        // 이미 없으면(550) 바라던 대로다.
        if (!c.deleteFile(name) && c.replyCode != FTPReply.FILE_UNAVAILABLE) failed(c, "delete $name")
    }

    override fun close() = closeQuietly(c)

    companion object {
        fun open(cfg: FtpConfig, timeoutMs: Int): FtpConnection {
            val tls = cfg.security != FtpSecurity.None
            val trust = PinningTrustManager(cfg.host.trim(), cfg.trusted)
            val client = if (tls) FTPSClient(cfg.security == FtpSecurity.Implicit).apply { trustManager = trust } else FTPClient()
            client.controlEncoding = if (cfg.encoding == FtpEncoding.EucKr) "EUC-KR" else "UTF-8"
            client.connectTimeout = timeoutMs
            client.defaultTimeout = timeoutMs
            client.setDataTimeout(Duration.ofMillis(timeoutMs.toLong()))
            try {
                guard {
                    try {
                        client.connect(cfg.host.trim(), cfg.port)
                    } catch (e: SSLException) {
                        // 명시적 FTPS: AUTH TLS 를 거절(4xx · 5xx — 431 "서비스 없음" 을 쓰는 서버도 있다)했다면 서버가 암호화를 받지 않는
                        // 것 — 인증서 문제와 다르게 알린다(자동이면 화면이 일반 FTP 로 다시 해 본다).
                        trust.seen?.let { throw SyncStoreException(SyncProblem.Untrusted, "untrusted certificate", e, fingerprint = it) }
                        if (cfg.security != FtpSecurity.Implicit && client.replyCode >= 400) throw SyncStoreException(SyncProblem.NoTls, "AUTH TLS refused: ${client.replyString?.trim()}", e)
                        throw e
                    }
                    if (!FTPReply.isPositiveCompletion(client.replyCode)) failed(client, "connect")
                    client.soTimeout = timeoutMs
                    if (!client.login(cfg.user, cfg.password)) throw SyncStoreException(SyncProblem.Auth, "login refused: ${client.replyString?.trim()}")
                    if (client is FTPSClient) {
                        // 데이터 연결도 암호화한다(PROT P). 안 하면 이름 · 비밀번호만 감추고 읽은 자리는 그대로 보낸다.
                        client.execPBSZ(0)
                        client.execPROT("P")
                    }
                    if (cfg.encoding == FtpEncoding.Auto) runCatching { client.sendCommand("OPTS UTF8 ON") }
                    client.setFileType(FTP.BINARY_FILE_TYPE)
                    if (cfg.passive) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
                    enterFolder(client, cfg.path)
                }
            } catch (e: Exception) {
                closeQuietly(client)
                // 데이터 연결의 인증서도 같은 판단을 거친다 — 거기서 거절돼도 지문을 묻는다.
                if (e !is SyncStoreException || e.problem != SyncProblem.Untrusted) trust.seen?.let { throw SyncStoreException(SyncProblem.Untrusted, "untrusted certificate", e, fingerprint = it) }
                throw e
            }
            return FtpConnection(client)
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

        private fun closeQuietly(client: FTPClient) {
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
            throw SyncStoreException(SyncProblem.Asleep, "timed out", e)
        } catch (e: FTPConnectionClosedException) {
            throw SyncStoreException(SyncProblem.Offline, "server closed the connection", e)
        } catch (e: IOException) {
            // commons-net 은 인사를 기다리다 시간이 다 된 것을 IOException("Timed out waiting for initial connect reply") 으로 싼다
            // — 속의 시간 초과를 봐야 잠든 서버를 "인터넷 없음" 으로 잘못 알리지 않고 한 번 더 기다린다.
            if (generateSequence<Throwable>(e) { it.cause }.any { it is SocketTimeoutException }) throw SyncStoreException(SyncProblem.Asleep, "timed out", e)
            throw SyncStoreException(SyncProblem.Offline, "ftp i/o failed", e)
        }
    }
}
