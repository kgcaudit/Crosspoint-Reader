package io.github.kgcaudit.reader.data.sync

import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.ServerSocket

/**
 * 시험용 FTP 서버(아파치 FtpServer, 이 프로세스 안). 가짜가 아니라 진짜 FTP 를 말한다 — 명령 순서 · 수동 모드 · 이름 바꾸기가
 * 서버에서 실제로 되는지 본다. 사용자 둘: 쓸 수 있는 `reader`, 읽기만 되는 `guest`.
 */
class TestFtpServer(val root: File, private val tls: FtpSecurity = FtpSecurity.None) {
    val port: Int = ServerSocket(0).use { it.localPort }
    private var server: FtpServer? = null

    fun start(): TestFtpServer {
        root.mkdirs()
        val factory = FtpServerFactory()
        factory.addListener("default", ListenerFactory().apply {
            port = this@TestFtpServer.port
            if (tls != FtpSecurity.None) {
                val ssl = SslConfigurationFactory().apply { keystoreFile = keystore; keystorePassword = STORE_PASSWORD }.createSslConfiguration()
                sslConfiguration = ssl
                isImplicitSsl = tls == FtpSecurity.Implicit
                dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                    sslConfiguration = ssl
                    isImplicitSsl = tls == FtpSecurity.Implicit
                }.createDataConnectionConfiguration()
            }
        }.createListener())
        // 사용자 파일은 미리 있어야 한다(없으면 서버가 설정 오류로 멈춘다).
        val userFile = File(root.parentFile, "ftp-users-$port.properties").apply { parentFile.mkdirs(); if (!exists()) createNewFile() }
        val users = PropertiesUserManagerFactory().apply { file = userFile }.createUserManager()
        users.save(BaseUser().apply { name = USER; password = PASSWORD; homeDirectory = root.absolutePath; authorities = listOf(WritePermission()) })
        users.save(BaseUser().apply { name = "guest"; password = "guest"; homeDirectory = root.absolutePath; authorities = emptyList() })
        factory.userManager = users
        server = factory.createServer().also { it.start() }
        return this
    }

    fun stop() {
        server?.stop()
        server = null
    }

    fun config(path: String = "/OLO", user: String = USER, password: String = PASSWORD) =
        FtpConfig(host = "127.0.0.1", port = port, security = tls, user = user, password = password, path = path)

    /**
     * 서버가 스스로 만든 인증서(NAS 와 같은 처지 — 공인 기관 서명이 없다). JDK 의 keytool 로 시험마다 새로 만든다 — 저장소에 비밀 키
     * 파일을 두지 않는다.
     */
    private val keystore: File by lazy {
        File(root.parentFile, "ftps-$port.p12").also { file ->
            file.delete()
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "olo", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=nas.local", "-ext", "SAN=ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", file.path, "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "keytool failed: $out" }
        }
    }

    /** 서버 인증서의 지문 — 앱이 사람에게 보일 것과 같아야 한다. */
    val certificateFingerprint: String
        get() {
            val ks = java.security.KeyStore.getInstance("PKCS12")
            keystore.inputStream().use { ks.load(it, STORE_PASSWORD.toCharArray()) }
            return fingerprintOf(ks.getCertificate("olo").encoded)
        }

    companion object {
        const val USER = "reader"
        const val PASSWORD = "책갈피-1234"
        private const val STORE_PASSWORD = "olo-test"
    }
}

/**
 * 잠든 NAS 흉내: 잇기는 바로 받지만, [wakeAfterMs] 가 지나기 전에는 아무 말도 하지 않는다(디스크를 돌리는 중). 깬 뒤에는 [targetPort]
 * 로 그대로 잇는다. 기다리다 지쳐 끊은 연결은 버린다.
 */
class SleepyProxy(private val targetPort: Int, wakeAfterMs: Long) {
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    private val wakeAt = System.currentTimeMillis() + wakeAfterMs

    init {
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        Thread.sleep((wakeAt - System.currentTimeMillis()).coerceAtLeast(0))
                        val upstream = java.net.Socket("127.0.0.1", targetPort)
                        val a = Thread { runCatching { client.getInputStream().copyTo(upstream.getOutputStream()) }; runCatching { upstream.shutdownOutput() } }
                        a.start()
                        runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
                        a.join(1_000)
                        upstream.close()
                    }
                    runCatching { client.close() }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun close() = server.close()
}

/**
 * 시험용 SFTP 서버(아파치 MINA SSHD, 이 프로세스 안). 서버 키는 시작할 때 만든다 — 앱은 처음 잇는 순간 그 지문을 물어야 한다.
 */
class TestSftpServer(val root: File) {
    val port: Int = ServerSocket(0).use { it.localPort }
    private var server: org.apache.sshd.server.SshServer? = null

    fun start(): TestSftpServer {
        root.mkdirs()
        server = org.apache.sshd.server.SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = this@TestSftpServer.port
            keyPairProvider = org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(File(root.parentFile, "sftp-key-$port").toPath())
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { u, p, _ -> u == TestFtpServer.USER && p == TestFtpServer.PASSWORD }
            subsystemFactories = listOf(org.apache.sshd.sftp.server.SftpSubsystemFactory())
            fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(root.toPath())
            // OpenSSH 의 "덮어쓰는 이름 바꾸기"(posix-rename) 를 알리지 않는다 — 그것이 없는 서버(옛 NAS · 일부 SFTP 서버)처럼,
            // 있는 파일 위로 이름을 바꾸지 못해야 앱의 "지우고 다시" 길이 시험된다.
            org.apache.sshd.sftp.SftpModuleProperties.OPENSSH_EXTENSIONS.set(this, "fsync@openssh.com=1")
            start()
        }
        return this
    }

    fun stop() {
        server?.stop(true)
        server = null
    }

    fun config(path: String = "/OLO", password: String = TestFtpServer.PASSWORD, trusted: String? = null) = FtpConfig(
        host = "127.0.0.1", port = port, protocol = FtpProtocol.Sftp, user = TestFtpServer.USER, password = password, path = path, trusted = trusted,
    )
}

/** 저장소를 몇 번 불렀는지 센다 — 꺼져 있을 때 서버에 아무것도 묻지 않는지 본다. */
class CountingStore(private val inner: SyncStore) : SyncStore {
    /** 부른 수 — 실패한 것도 센다(꺼진 서버에 몇 번 물었는지 봐야 한다). */
    var calls = 0
    override suspend fun list(): List<SyncStore.Entry> { calls++; return inner.list() }
    override suspend fun read(entry: SyncStore.Entry, maxBytes: Long): String? { calls++; return inner.read(entry, maxBytes) }
    override suspend fun write(name: String, existing: SyncStore.Entry?, text: String): SyncStore.Entry { calls++; return inner.write(name, existing, text) }
    override suspend fun delete(entry: SyncStore.Entry) { calls++; inner.delete(entry) }
    /** 연결을 연 수(맞추기 한 번에 하나). 꺼진 서버면 일은 세지 않고 이것만 는다. */
    var sessions = 0
    override suspend fun <T> session(block: suspend () -> T): T { sessions++; return inner.session(block) }
    override suspend fun check() { calls++; inner.check() }
}
