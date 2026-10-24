package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.data.sync.FtpConfig
import io.github.kgcaudit.reader.data.sync.FtpSecurity

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
internal class TestFtpServer(val root: File, private val tls: FtpSecurity = FtpSecurity.None) {
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

    fun config(path: String = FtpSettings.DEFAULT_PATH, user: String = USER, password: String = PASSWORD) =
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
            return java.security.MessageDigest.getInstance("SHA-256").digest(ks.getCertificate("olo").encoded).joinToString(":") { "%02X".format(it) }
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
internal class SleepyProxy(private val targetPort: Int, wakeAfterMs: Long) {
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
