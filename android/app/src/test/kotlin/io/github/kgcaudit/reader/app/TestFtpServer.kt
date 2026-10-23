package io.github.kgcaudit.reader.app

import io.github.kgcaudit.reader.data.sync.FtpConfig

import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.ServerSocket

/**
 * 시험용 FTP 서버(아파치 FtpServer, 이 프로세스 안). 가짜가 아니라 진짜 FTP 를 말한다 — 명령 순서 · 수동 모드 · 이름 바꾸기가
 * 서버에서 실제로 되는지 본다. 사용자 둘: 쓸 수 있는 `reader`, 읽기만 되는 `guest`.
 */
internal class TestFtpServer(val root: File) {
    val port: Int = ServerSocket(0).use { it.localPort }
    private var server: FtpServer? = null

    fun start(): TestFtpServer {
        root.mkdirs()
        val factory = FtpServerFactory()
        factory.addListener("default", ListenerFactory().apply { port = this@TestFtpServer.port }.createListener())
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
        FtpConfig(host = "127.0.0.1", port = port, user = user, password = password, path = path)

    companion object {
        const val USER = "reader"
        const val PASSWORD = "책갈피-1234"
    }
}

