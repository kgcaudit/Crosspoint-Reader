package io.github.kgcaudit.reader.data.sync

/** 잇는 방식. SFTP 는 SSH 위의 파일 전송이라 늘 암호화되고, 포트 하나(22)만 열면 된다. */
enum class FtpProtocol { Ftp, Sftp }

/**
 * FTP 의 암호화. [Auto] 는 설정 화면에서만 쓴다 — 연결 확인이 명시적 FTPS 로 먼저 해 보고, 서버가 받지 않으면 [None] 으로 다시 한 뒤
 * **된 쪽을 저장한다**. 저장된 설정에 자동을 남기면, 나중에 누가 가운데서 "암호화 안 받음" 이라고 속였을 때 조용히 일반 FTP 로 내려간다.
 */
enum class FtpSecurity {
    Auto,

    /** AUTH TLS — 포트 21 에서 잇고 암호화로 바꾼다. 대부분의 NAS. */
    Explicit,

    /** 처음부터 TLS — 포트 990. 오래된 서버. */
    Implicit,

    None,
}

/** 파일 이름 글자 체계. 한글 폴더 이름이 깨지는 옛 한국 NAS 만 EUC-KR. */
enum class FtpEncoding { Auto, EucKr }

/**
 * 서버 한 곳(0.58.0, 사용자 결정). [path] 폴더에 기기별 파일을 둔다 — 없으면 만든다.
 *
 * @param path 서버 안 폴더. "/OLO Ecosystem" · "책/이어 읽기" 처럼. 앞 "/" 가 없으면 로그인한 자리에서부터.
 * @param passive 수동 모드. 휴대폰은 늘 공유기 · 통신사 NAT 뒤라 서버가 휴대폰으로 거꾸로 잇는 능동 모드는 거의 열리지 않는다.
 * @param trusted 사람이 믿기로 한 인증서 · 서버 키의 SHA-256 지문. 공인 인증서가 아닌 NAS 는 이것으로만 잇는다.
 */
data class FtpConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val protocol: FtpProtocol = FtpProtocol.Ftp,
    val security: FtpSecurity = FtpSecurity.None,
    val user: String,
    val password: String,
    val path: String,
    val passive: Boolean = true,
    val encoding: FtpEncoding = FtpEncoding.Auto,
    val trusted: String? = null,
) {
    /** 비밀번호 · 사용자가 오가는 길이 암호화되는가. */
    val encrypted: Boolean get() = protocol == FtpProtocol.Sftp || security != FtpSecurity.None

    /** 설정 칸에 다시 보일 주소("sftp://nas.local", "nas.local:2121"). 처음 값 포트면 붙이지 않는다. */
    val address: String
        get() {
            val scheme = when {
                protocol == FtpProtocol.Sftp -> "sftp://"
                security == FtpSecurity.Implicit -> "ftps://"
                else -> ""
            }
            val shownHost = if (':' in host) "[$host]" else host
            return scheme + shownHost + if (port == defaultPort(protocol, security)) "" else ":$port"
        }

    /** 화면에 보일 한 줄("nas.local · /OLO Ecosystem"). 비밀번호는 넣지 않는다. */
    val label: String get() = address + " · " + path.ifBlank { "/" }

    override fun toString(): String = "FtpConfig($label, $protocol, $security, user=$user)"

    companion object {
        const val DEFAULT_PORT: Int = 21

        fun defaultPort(protocol: FtpProtocol, security: FtpSecurity): Int = when {
            protocol == FtpProtocol.Sftp -> 22
            security == FtpSecurity.Implicit -> 990
            else -> DEFAULT_PORT
        }
    }
}

/**
 * 사람이 넣은 서버 주소 한 칸을 푼다. 포트 칸 · 프로토콜 칸을 따로 두지 않으려는 것 — "nas.local", "nas.local:2121", "sftp://nas.local",
 * "ftps://ftp.example.com", 주소창에서 복사한 "ftp://reader@nas.local:21/책/" 까지 받는다.
 *
 * - 앞머리: `sftp://` SFTP · `ftps://` 암묵적 FTPS · `ftpes://` 명시적 FTPS · `ftp://` 아무것도 정하지 않음(고급 설정을 따른다).
 * - 앞머리가 없고 포트가 22 면 SFTP, 990 이면 암묵적 FTPS — 포트만 바꿔 넣는 사람이 많다.
 * - "사용자@" 와 뒤의 경로는 버린다(경로는 저장 폴더 칸에 따로 있다).
 */
object FtpAddress {
    data class Parsed(val host: String, val port: Int?, val protocol: FtpProtocol?, val security: FtpSecurity?)

    /** 풀 수 없으면(비었거나 포트가 숫자가 아님) null. */
    fun parse(text: String): Parsed? {
        var rest = text.trim()
        var protocol: FtpProtocol? = null
        var security: FtpSecurity? = null
        val scheme = rest.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty()) {
            rest = rest.substringAfter("://")
            when (scheme) {
                "sftp" -> protocol = FtpProtocol.Sftp
                "ftps" -> { protocol = FtpProtocol.Ftp; security = FtpSecurity.Implicit }
                "ftpes" -> { protocol = FtpProtocol.Ftp; security = FtpSecurity.Explicit }
                "ftp" -> protocol = FtpProtocol.Ftp
                else -> return null
            }
        }
        rest = rest.substringBefore('/').substringAfterLast('@').trim()
        val host: String
        var port: Int? = null
        if (rest.startsWith("[")) {
            // IPv6 "[fe80::1]:21" — 대괄호 밖의 ":" 만 포트다.
            host = rest.substringAfter('[').substringBefore(']')
            val after = rest.substringAfter(']', "")
            if (after.startsWith(":")) port = after.drop(1).toIntOrNull() ?: return null
        } else if (rest.count { it == ':' } == 1) {
            host = rest.substringBefore(':')
            port = rest.substringAfter(':').toIntOrNull() ?: return null
        } else {
            host = rest
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        if (port != null && port !in 1..65535) return null
        if (protocol == null) {
            when (port) {
                22 -> protocol = FtpProtocol.Sftp
                990 -> { protocol = FtpProtocol.Ftp; security = FtpSecurity.Implicit }
            }
        }
        return Parsed(host, port, protocol, security)
    }
}
