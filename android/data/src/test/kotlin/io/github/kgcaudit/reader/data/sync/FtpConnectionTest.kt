package io.github.kgcaudit.reader.data.sync

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 서버에 잇는 길(0.58.0 보강): 주소 한 칸 풀기, FTPS 의 스스로 만든 인증서 믿기, 암호화를 받지 않는 서버, 암묵적 FTPS, SFTP.
 * 모두 이 프로세스 안의 진짜 서버에 대고 한다.
 */
class FtpConnectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stops = ArrayList<() -> Unit>()

    @After
    fun tearDown() = stops.forEach { it() }

    private fun ftp(tls: FtpSecurity) = TestFtpServer(File(tmp.root, "ftp-$tls/root"), tls).start().also { s -> stops += { s.stop() } }
    private fun sftp() = TestSftpServer(File(tmp.root, "sftp/root")).start().also { s -> stops += { s.stop() } }

    /** 확인이 [problem] 으로 끝나는지. 지문이 있으면 돌려준다. */
    private fun checkFails(config: FtpConfig, problem: SyncProblem): String? = runBlocking {
        try {
            FtpSyncStore({ config }, timeoutMs = 5_000).check()
            fail("expected $problem")
        } catch (e: SyncStoreException) {
            assertEquals(problem, e.problem, e.message)
            e.fingerprint
        }
    }

    private fun checkOk(config: FtpConfig) = runBlocking { FtpSyncStore({ config }, timeoutMs = 5_000).check() }

    @Test
    fun `one address box understands scheme, port, user and path and refuses what it cannot read`() {
        assertEquals(FtpAddress.Parsed("nas.local", null, null, null), FtpAddress.parse(" nas.local "))
        assertEquals(FtpAddress.Parsed("nas.local", 2121, null, null), FtpAddress.parse("nas.local:2121"))
        // 주소창에서 복사한 모양: 사용자 · 뒤 경로는 버린다.
        assertEquals(FtpAddress.Parsed("nas.local", 21, FtpProtocol.Ftp, null), FtpAddress.parse("ftp://reader@nas.local:21/책/"))
        assertEquals(FtpAddress.Parsed("nas.local", null, FtpProtocol.Sftp, null), FtpAddress.parse("SFTP://nas.local"))
        assertEquals(FtpAddress.Parsed("h", null, FtpProtocol.Ftp, FtpSecurity.Implicit), FtpAddress.parse("ftps://h"))
        assertEquals(FtpAddress.Parsed("h", null, FtpProtocol.Ftp, FtpSecurity.Explicit), FtpAddress.parse("ftpes://h/"))
        // 앞머리 없이 포트만 바꿔 넣어도 알아본다.
        assertEquals(FtpProtocol.Sftp, FtpAddress.parse("nas.local:22")?.protocol)
        assertEquals(FtpSecurity.Implicit, FtpAddress.parse("nas.local:990")?.security)
        assertEquals(FtpAddress.Parsed("fe80::1", 2121, null, null), FtpAddress.parse("[fe80::1]:2121"))
        assertEquals(FtpAddress.Parsed("fe80::1", null, null, null), FtpAddress.parse("fe80::1"))
        // 깨진 입력: 비었음 · 포트가 숫자 아님 · 범위 밖 · 다른 앞머리 · 빈칸 든 이름.
        listOf("", "  ", "nas:abc", "nas:70000", "nas:0", "http://nas", "nas local", "ftp://", "[::1]:x").forEach {
            assertNull(FtpAddress.parse(it), "'$it' 를 받아들였다")
        }
        // 저장된 설정을 다시 칸에 보일 때 처음 값 포트는 붙이지 않는다.
        assertEquals("sftp://nas.local", FtpConfig("nas.local", 22, FtpProtocol.Sftp, user = "u", password = "p", path = "/").address)
        assertEquals("nas.local:2121", FtpConfig("nas.local", 2121, user = "u", password = "p", path = "/").address)
        assertEquals("ftps://[fe80::1]", FtpConfig("fe80::1", 990, security = FtpSecurity.Implicit, user = "u", password = "p", path = "/").address)
    }

    @Test
    fun `a self signed ftps server asks once for its fingerprint and then syncs only with that certificate`() {
        val server = ftp(FtpSecurity.Explicit)
        val asked = checkFails(server.config(), SyncProblem.Untrusted)
        // 사람에게 보이는 지문은 서버 인증서의 것 — NAS 설정 화면과 맞춰 볼 수 있다.
        assertEquals(server.certificateFingerprint, asked)
        val trusted = server.config().copy(trusted = asked)
        checkOk(trusted)
        // 믿은 뒤에는 암호화된 연결로 쓰고 읽는다(PROT P 데이터 연결까지).
        val a = FtpSyncStore({ trusted }, timeoutMs = 5_000)
        runBlocking {
            a.session { a.write("a.json", null, "{\"읽은\":1}") }
            val b = FtpSyncStore({ trusted }, timeoutMs = 5_000)
            val entry = b.list().single { it.name == "a.json" }
            assertEquals("{\"읽은\":1}", b.read(entry, 1_000))
        }
        assertFalse(File(server.root, "OLO/.a.json.part").exists())
        // 다른 지문을 믿었다면(가운데서 바꿔치기) 잇지 않고 다시 묻는다.
        assertEquals(asked, checkFails(server.config().copy(trusted = "00:11:22"), SyncProblem.Untrusted))
    }

    @Test
    fun `a server without encryption is told apart from a broken certificate`() {
        val server = ftp(FtpSecurity.None)
        // 명시적 FTPS 를 청했는데 AUTH TLS 를 거절 — "암호화 안 받음" 이어야 화면이 일반 FTP 로 다시 해 볼 수 있다.
        assertNull(checkFails(server.config().copy(security = FtpSecurity.Explicit), SyncProblem.NoTls))
        checkOk(server.config())
    }

    @Test
    fun `implicit ftps on its own port connects once its certificate is trusted`() {
        val server = ftp(FtpSecurity.Implicit)
        val asked = checkFails(server.config(), SyncProblem.Untrusted)
        assertEquals(server.certificateFingerprint, asked)
        checkOk(server.config().copy(trusted = asked))
        // 일반 FTP 로 990 에 붙으면 인사가 오지 않는다 — 실패하되 멈추지 않는다.
        runBlocking {
            try {
                FtpSyncStore({ server.config().copy(security = FtpSecurity.None) }, timeoutMs = 1_000, wakeTimeoutMs = 1_000).check()
                fail("plain ftp on an implicit port connected")
            } catch (e: SyncStoreException) {
                // 암묵적 FTPS 서버는 TLS 인사를 기다리며 말이 없다 — "답하지 않음".
                assertTrue(e.problem in setOf(SyncProblem.Asleep, SyncProblem.Offline, SyncProblem.Server), e.problem.toString())
            }
        }
    }

    @Test
    fun `sftp asks for the host key, then writes whole files into a korean folder another device reads`() {
        val server = sftp()
        val asked = checkFails(server.config(path = "책/이어 읽기"), SyncProblem.Untrusted)
        assertNotNull(asked)
        // 같은 서버는 같은 지문 — 다시 물어도 사람이 본 것과 같다.
        assertEquals(asked, checkFails(server.config(), SyncProblem.Untrusted))
        // 사람에게 보이는 지문은 ssh-keygen · NAS 의 SSH 화면과 같은 모양이어야 맞춰 볼 수 있다.
        assertEquals(server.hostKeyFingerprint, asked)
        val trusted = server.config(path = "책/이어 읽기", trusted = asked)
        checkOk(trusted)
        assertFalse(File(server.root, "책/이어 읽기/.olo-check").exists())
        runBlocking {
            val a = FtpSyncStore({ trusted }, timeoutMs = 5_000)
            a.session { a.write("a.json", null, "첫째") }
            // 있는 파일을 덮는다(SFTP 이름 바꾸기는 덮지 않는 서버가 많다).
            a.session { a.write("a.json", null, "둘째") }
            val b = FtpSyncStore({ trusted }, timeoutMs = 5_000)
            val entry = b.list().single()
            assertEquals("a.json", entry.name)
            assertEquals("둘째", b.read(entry, 1_000))
            b.delete(entry)
            b.delete(entry)
            assertTrue(b.list().isEmpty())
        }
        assertFalse(File(server.root, "책/이어 읽기/.a.json.part").exists())
        assertEquals(asked, checkFails(server.config(trusted = "00:11"), SyncProblem.Untrusted))
        checkFails(server.config(password = "틀림", trusted = asked), SyncProblem.Auth)
        server.stop()
        checkFails(trusted, SyncProblem.Unreachable)
    }

    @Test
    fun `a certificate only matches the address it was issued for`() {
        val server = ftp(FtpSecurity.Explicit)
        server.certificateFingerprint
        val ks = KeyStore.getInstance("PKCS12")
        File(tmp.root, "ftp-Explicit/ftps-${server.port}.p12").inputStream().use { ks.load(it, "olo-test".toCharArray()) }
        val cert = ks.getCertificate("olo") as X509Certificate
        assertTrue(matchesHost(cert, "127.0.0.1"))
        assertFalse(matchesHost(cert, "127.0.0.2"))
        // CN 만 nas.local 이다 — 다른 이름(SAN)이 아니면 받지 않는다(요즘 규칙).
        assertFalse(matchesHost(cert, "nas.local"))
    }

    @Test
    fun `a sleeping server is waited for once more and a silent one is told apart from no internet`() {
        val server = ftp(FtpSecurity.None)
        // 2.5초 뒤에 깨는 NAS: 첫 시도(1초)는 시간이 다 되고, 두 번째(6초)가 깨어난 서버를 만난다.
        val sleepy = SleepyProxy(server.port, wakeAfterMs = 2_500).also { p -> stops += { p.close() } }
        val config = server.config().copy(port = sleepy.port)
        runBlocking { FtpSyncStore({ config }, timeoutMs = 1_000, wakeTimeoutMs = 6_000).check() }
        // 깨지 않는 서버: 기다려도 답이 없으면 "인터넷 없음" 이 아니라 "서버가 답하지 않음".
        val silent = SleepyProxy(server.port, wakeAfterMs = 60_000).also { p -> stops += { p.close() } }
        val started = System.currentTimeMillis()
        runBlocking {
            try {
                FtpSyncStore({ config.copy(port = silent.port) }, timeoutMs = 500, wakeTimeoutMs = 1_000).check()
                fail("a silent server connected")
            } catch (e: SyncStoreException) {
                assertEquals(SyncProblem.Asleep, e.problem)
            }
        }
        // 한 번만 더 기다린다(0.5 + 1초) — 끝없이 다시 하지 않는다.
        assertTrue(System.currentTimeMillis() - started < 5_000)
        // SFTP 도 같은 갈래.
        val sshSilent = SleepyProxy(sftp().port, wakeAfterMs = 60_000).also { p -> stops += { p.close() } }
        checkFailsWith(TestSftpServer(File(tmp.root, "x")).config().copy(port = sshSilent.port), SyncProblem.Asleep)
    }

    @Test
    fun `a sync cancelled while connecting still closes its connection`() {
        val server = ftp(FtpSecurity.None)
        // 1초 뒤에 답하는 서버 — 잇는 중에 책을 닫아(띠 확인 취소) 맞추기가 취소된다.
        val slow = SleepyProxy(server.port, wakeAfterMs = 1_000).also { p -> stops += { p.close() } }
        val store = FtpSyncStore({ server.config().copy(port = slow.port) }, timeoutMs = 5_000)
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { store.session { store.list() } }
            kotlinx.coroutines.delay(300)
            job.cancel()
            job.join()
        }
        // 잇기가 끝나면 바로 닫는다 — 안 닫으면 서버에 연결이 남아, 연결 수를 묶는 NAS 가 다음 로그인을 거절했다.
        val until = System.currentTimeMillis() + 5_000
        while (server.connections > 0 && System.currentTimeMillis() < until) Thread.sleep(50)
        assertEquals(0, server.connections)
    }

    private fun checkFailsWith(config: FtpConfig, problem: SyncProblem) = runBlocking {
        try {
            FtpSyncStore({ config }, timeoutMs = 500, wakeTimeoutMs = 1_000).check()
            fail("expected $problem")
        } catch (e: SyncStoreException) {
            assertEquals(problem, e.problem, e.message)
        }
    }
}
