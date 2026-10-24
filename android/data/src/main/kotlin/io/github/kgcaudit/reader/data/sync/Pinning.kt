package io.github.kgcaudit.reader.data.sync

import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 인증서 SHA-256 지문을 "AB:CD:…" 로. NAS 의 인증서 화면 · 브라우저가 보이는 모양과 같아 사람이 눈으로 맞춰 볼 수 있다. */
internal fun fingerprintOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

/**
 * SSH 서버 키 지문을 "SHA256:…"(base64, 끝 = 없음)로 — `ssh-keygen -lf` · OpenSSH · NAS 의 SSH 화면이 보이는 모양. 0.59.0 은 인증서와
 * 같은 콜론 16진으로 보여, 사람이 NAS 화면과 맞춰 볼 수 없어 보지 않고 "믿기" 를 누르게 됐다.
 */
internal fun sshFingerprintOf(key: ByteArray): String =
    "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

/**
 * FTPS 인증서 판단. 공인 기관이 서명했고 이름이 주소와 맞으면 받는다. 아니면 사람이 믿기로 한 지문([pinned])과 같을 때만 받는다.
 * 둘 다 아니면 지문을 [seen] 에 남기고 거절한다 — 연결 확인이 그 지문을 사람에게 묻는다.
 *
 * 이름 확인을 여기서 직접 하는 까닭: commons-net 의 "끝점 확인" 을 켜면 데이터 연결(PASV 로 받은 IP)에도 이름을 맞춰, 이름으로 발급된
 * 공인 인증서도 데이터 연결에서 거절된다.
 */
internal class PinningTrustManager(private val host: String, private val pinned: String?) : X509TrustManager {
    @Volatile
    var seen: String? = null
        private set

    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
        val fingerprint = fingerprintOf(leaf.encoded)
        if (pinned != null && fingerprint.equals(pinned, ignoreCase = true)) return
        val trusted = runCatching { system.checkServerTrusted(chain, authType) }.isSuccess && matchesHost(leaf, host)
        if (trusted) return
        seen = fingerprint
        throw CertificateException("untrusted certificate $fingerprint")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit = throw CertificateException("client")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/** 인증서의 다른 이름(SAN)이 [host] 와 맞는가. "*.example.com" 은 한 단만. IP 주소는 IP 칸과. */
internal fun matchesHost(cert: X509Certificate, host: String): Boolean {
    val names = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
    val wanted = host.trim().trimEnd('.').lowercase()
    val isIp = wanted.isNotEmpty() && (wanted.all { it.isDigit() || it == '.' } || ':' in wanted)
    for (entry in names) {
        val type = entry.getOrNull(0) as? Int ?: continue
        val value = (entry.getOrNull(1) as? String)?.lowercase() ?: continue
        if (isIp && type == 7) {
            if (runCatching { InetAddress.getByName(value) == InetAddress.getByName(wanted) }.getOrDefault(false)) return true
        } else if (!isIp && type == 2) {
            if (value == wanted) return true
            if (value.startsWith("*.") && wanted.substringAfter('.', "") == value.drop(2) && wanted.substringBefore('.').isNotEmpty()) return true
        }
    }
    return false
}
