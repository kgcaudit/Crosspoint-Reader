package io.github.kgcaudit.reader.data.sync

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * SFTP 연결(JSch). 서버 키는 사람이 믿기로 한 지문([FtpConfig.trusted])과 같을 때만 받는다 — SSH 에는 공인 기관이 없어, 처음 잇는
 * 서버는 늘 지문을 묻는다. 지문이 바뀌면 다시 묻는다(누가 가운데서 가로채면 키가 바뀐다).
 */
internal class SftpConnection private constructor(private val session: Session, private val sftp: ChannelSftp) : FtpSyncStore.Connection {

    override fun list(): List<SyncStore.Entry> = guard {
        sftp.ls(".").filter { it.attrs.isReg }.map { e ->
            SyncStore.Entry(id = e.filename, name = e.filename, modifiedEpochMs = e.attrs.mTime * 1000L, sizeBytes = e.attrs.size)
        }
    }

    override fun read(name: String, maxBytes: Long): String? = guard {
        val out = ByteArrayOutputStream()
        try {
            sftp.get(name, out)
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return@guard null
            throw e
        }
        if (out.size() > maxBytes) null else out.toString("UTF-8")
    }

    override fun write(name: String, bytes: ByteArray) = guard {
        val temp = FtpSyncStore.partName(name)
        sftp.put(ByteArrayInputStream(bytes), temp, ChannelSftp.OVERWRITE)
        try {
            sftp.rename(temp, name)
        } catch (e: SftpException) {
            // SFTP 3판의 이름 바꾸기는 있는 파일을 덮지 않는다(대부분의 서버). 옛것을 지우고 다시.
            runCatching { sftp.rm(name) }
            try {
                sftp.rename(temp, name)
            } catch (again: SftpException) {
                runCatching { sftp.rm(temp) }
                throw again
            }
        }
    }

    override fun delete(name: String) = guard {
        try {
            sftp.rm(name)
        } catch (e: SftpException) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e
        }
    }

    override fun close() {
        runCatching { sftp.disconnect() }
        runCatching { session.disconnect() }
    }

    /** 서버 키 판단. 믿은 지문과 같으면 받고, 아니면 지문을 [seen] 에 남겨 거절한다. */
    private class PinnedKeys(private val pinned: String?) : HostKeyRepository {
        @Volatile
        var seen: String? = null

        override fun check(host: String?, key: ByteArray): Int {
            val fingerprint = fingerprintOf(key)
            if (pinned != null && fingerprint.equals(pinned, ignoreCase = true)) return HostKeyRepository.OK
            seen = fingerprint
            return HostKeyRepository.NOT_INCLUDED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "olo"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** 비밀번호 · "키보드로 답하기" 로그인 둘 다 같은 비밀번호로 답한다(일부 NAS 는 뒤엣것만 연다). */
    private class Password(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) = Unit
        override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?) =
            Array(prompt?.size ?: 0) { password }
    }

    companion object {
        fun open(cfg: FtpConfig, timeoutMs: Int): SftpConnection {
            val keys = PinnedKeys(cfg.trusted)
            val jsch = JSch().apply { hostKeyRepository = keys }
            val session = jsch.getSession(cfg.user, cfg.host.trim(), cfg.port)
            session.setPassword(cfg.password)
            session.userInfo = Password(cfg.password)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            session.timeout = timeoutMs
            try {
                ssh(keys) {
                    session.connect(timeoutMs)
                    val sftp = session.openChannel("sftp") as ChannelSftp
                    sftp.connect(timeoutMs)
                    if (cfg.encoding == FtpEncoding.EucKr) sftp.setFilenameEncoding("EUC-KR")
                    enterFolder(sftp, cfg.path)
                    return SftpConnection(session, sftp)
                }
            } catch (e: Exception) {
                runCatching { session.disconnect() }
                throw e
            }
        }

        /** [path] 로 들어간다. 없는 폴더는 한 칸씩 만든다. */
        private fun enterFolder(sftp: ChannelSftp, path: String) {
            val trimmed = path.trim()
            try {
                if (trimmed.startsWith("/")) sftp.cd("/")
                for (part in trimmed.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }) {
                    try {
                        sftp.cd(part)
                    } catch (e: SftpException) {
                        sftp.mkdir(part)
                        sftp.cd(part)
                    }
                }
            } catch (e: SftpException) {
                throw SyncStoreException(SyncProblem.NoAccess, "folder $path: ${e.message}", e)
            }
        }

        private inline fun <T> ssh(keys: PinnedKeys?, work: () -> T): T = try {
            work()
        } catch (e: SyncStoreException) {
            throw e
        } catch (e: SftpException) {
            val problem = when (e.id) {
                ChannelSftp.SSH_FX_PERMISSION_DENIED, ChannelSftp.SSH_FX_NO_SUCH_FILE -> SyncProblem.NoAccess
                else -> SyncProblem.Server
            }
            throw SyncStoreException(problem, "sftp ${e.id}: ${e.message}", e)
        } catch (e: JSchException) {
            val seen = keys?.seen
            val causes = generateSequence<Throwable>(e) { it.cause }.toList()
            val message = e.message.orEmpty()
            throw when {
                seen != null && ("HostKey" in message || "host key" in message.lowercase()) ->
                    SyncStoreException(SyncProblem.Untrusted, "unknown host key", e, fingerprint = seen)
                message.startsWith("Auth fail") || message.startsWith("Auth cancel") || "USERAUTH fail" in message ->
                    SyncStoreException(SyncProblem.Auth, "login refused", e)
                causes.any { it is UnknownHostException || it is ConnectException } ->
                    SyncStoreException(SyncProblem.Unreachable, "unreachable", e)
                causes.any { it is SocketTimeoutException || it is NoRouteToHostException } || "timeout" in message.lowercase() ->
                    SyncStoreException(SyncProblem.Offline, "timed out", e)
                else -> SyncStoreException(SyncProblem.Offline, "ssh failed: $message", e)
            }
        } catch (e: java.io.IOException) {
            throw SyncStoreException(SyncProblem.Offline, "sftp i/o failed", e)
        }
    }

    private inline fun <T> guard(work: () -> T): T = ssh(null, work)
}
