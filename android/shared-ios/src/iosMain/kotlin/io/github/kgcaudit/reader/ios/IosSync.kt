@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.Locator
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.convert
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/*
 * 기기 간 이어 읽기(구상안 50 · 1A 2A 3A, 안드로이드 0.58 · 0.59 · 0.59.1). 사람이 정한 FTP · FTPS · SFTP 서버의 한 폴더에 기기마다
 * `<기기 id>.json` 하나를 쓴다 — 안드로이드와 **같은 이름 · 같은 JSON** 이라 같은 서버면 두 앱이 서로 본다.
 *
 * 여기에는 서버 없이 시험할 수 있는 것을 둔다: 파일 모양([SyncCodec]), 묻기 · 합치기 규칙([SyncRules]), 주소 한 칸 풀이([FtpAddress]),
 * FTP 답 풀이([FtpReplies]), 이 기기의 상태와 맞추기 순서([IosReadingSync]). 서버와 주고받는 일은 Swift(`SyncClient`)가 한다 —
 * 명시적 FTPS(연결 도중에 TLS 로 바꾸기)와 SSH 는 iOS 의 통신 기능에 묶여 있다.
 */

/** 잇는 방식. SFTP 는 SSH 위의 파일 전송이라 늘 암호화되고 포트 하나(22)만 열면 된다. */
enum class FtpProtocol { Ftp, Sftp }

/**
 * FTP 의 암호화. [Auto] 는 설정 화면에서만 쓴다 — 명시적 FTPS 로 먼저 해 보고, 서버가 받지 않으면 [None] 으로 다시 한 뒤 **된 쪽을
 * 저장한다**. 자동을 남기면 누가 가운데서 "암호화 안 받음" 이라고 속였을 때 조용히 일반 FTP 로 내려간다.
 */
enum class FtpSecurity { Auto, Explicit, Implicit, None }

/** 파일 이름 글자 체계. 한글 폴더 이름이 깨지는 옛 한국 NAS 만 EUC-KR. */
enum class FtpEncoding { Auto, EucKr }

/**
 * 서버 한 곳. [path] 폴더에 기기별 파일을 둔다(없으면 만든다). 비밀번호는 여기 두지 않는다 — 키체인에서 꺼내 Swift 가 들고 잇는다.
 *
 * @param passive 수동 모드. 휴대폰은 늘 공유기 · 통신사 NAT 뒤라 서버가 거꾸로 잇는 능동 모드는 거의 열리지 않는다.
 * @param trusted 사람이 믿기로 한 인증서 · 서버 키의 SHA-256 지문("AB:CD:…").
 */
data class FtpConfig(
    val host: String,
    val port: Int,
    val protocol: FtpProtocol,
    val security: FtpSecurity,
    val user: String,
    val path: String,
    val passive: Boolean = true,
    val encoding: FtpEncoding = FtpEncoding.Auto,
    val trusted: String? = null,
) {
    /** 비밀번호 · 기록이 오가는 길이 암호화되는가. 아니면 서버 줄에 빨갛게 알린다. */
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

    /** 화면 한 줄("nas.local · /OLO Ecosystem"). */
    val label: String get() = address + " · " + path.ifBlank { "/" }

    fun withSecurity(security: FtpSecurity) = copy(security = security)
    fun withTrusted(trusted: String?) = copy(trusted = trusted)

    companion object {
        const val DEFAULT_PORT = 21

        /** 저장 폴더의 처음 값(안드로이드와 같다). OLO 앱들이 함께 쓸 제 폴더 — 서버 맨 위에 여러 앱의 파일이 섞이지 않게. */
        const val DEFAULT_PATH = "/OLO Ecosystem"

        fun defaultPort(protocol: FtpProtocol, security: FtpSecurity): Int = when {
            protocol == FtpProtocol.Sftp -> 22
            security == FtpSecurity.Implicit -> 990
            else -> DEFAULT_PORT
        }
    }
}

/**
 * 주소 한 칸 풀이(안드로이드 `FtpAddress` 와 같은 규칙 — 시험 표도 같다). 포트 칸 · 방식 칸을 따로 두지 않으려는 것.
 * `sftp://` SFTP · `ftps://` 암묵적 FTPS · `ftpes://` 명시적 FTPS · `ftp://` 정하지 않음. 앞머리 없이 22 면 SFTP, 990 이면 암묵적 FTPS.
 * "사용자@" 와 뒤 경로는 버린다.
 */
object FtpAddress {
    data class Parsed(val host: String, val port: Int?, val protocol: FtpProtocol?, val security: FtpSecurity?)

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

    /**
     * 설정 칸들로 확인할 설정. 주소의 앞머리가 고급 설정보다 앞선다(사람이 칸에 직접 쓴 것). 같은 서버(주소 · 포트)면 믿은 지문을 이어
     * 쓴다 — 폴더만 바꿨는데 인증서를 다시 묻지 않게. 칸이 모자라면 null.
     */
    fun configOf(
        address: String, user: String, path: String, security: FtpSecurity, passive: Boolean, encoding: FtpEncoding, previous: FtpConfig?,
    ): FtpConfig? {
        val parsed = parse(address) ?: return null
        if (user.isBlank()) return null
        val protocol = parsed.protocol ?: FtpProtocol.Ftp
        val chosen = if (protocol == FtpProtocol.Sftp) FtpSecurity.None else parsed.security ?: security
        val port = parsed.port ?: FtpConfig.defaultPort(protocol, chosen)
        val trusted = previous?.trusted?.takeIf { previous.host == parsed.host && previous.port == port }
        return FtpConfig(parsed.host, port, protocol, chosen, user.trim(), path.trim().ifEmpty { "/" }, passive, encoding, trusted)
    }

    /** 칸이 모자라거나 읽을 수 없으면 사람에게 보일 말, 다 됐으면 null(안드로이드 `FtpForm.missing`). */
    fun missing(address: String, user: String): String? = when {
        address.isBlank() -> "서버 주소를 넣어 주세요."
        parse(address) == null -> "서버 주소를 읽지 못했습니다. nas.local 또는 nas.local:2121 처럼 넣어 주세요."
        user.isBlank() -> "사용자 이름을 넣어 주세요."
        else -> null
    }
}

/** 맞추지 못한 까닭 — 화면이 사람에게 할 일을 알려 줄 수 있는 갈래만(안드로이드 `SyncProblem`). */
enum class SyncProblem {
    Offline, Unreachable, Asleep, Auth, NoAccess, Full, Tls, NoTls, Untrusted, Server;

    /** 사람이 고칠 것이 없고 시간이 지나면 풀리는 까닭 — 나중에 저절로 다시 해 볼 만한 것. */
    val retryable: Boolean get() = this in setOf(Offline, Unreachable, Asleep, Server, Full)

    /** 서버에 닿지 못한 까닭. 이 뒤 1분은 책을 열어도 다시 묻지 않는다. */
    val unreached: Boolean get() = this in setOf(Offline, Unreachable, Asleep)
}

/** 화면 문구(안드로이드 `SyncScreen.kt` 와 같은 낱말). */
object SyncText {
    fun problem(problem: SyncProblem): String = when (problem) {
        SyncProblem.Offline -> "인터넷에 연결되지 않아 맞추지 못했습니다. 연결되면 다시 맞춥니다."
        SyncProblem.Unreachable -> "서버를 찾지 못했습니다. 주소 · 포트가 맞는지, 서버가 켜져 있는지 확인하세요."
        SyncProblem.Asleep -> "서버가 답하지 않습니다(잠들어 있거나 꺼져 있음). 깨어나면 다시 맞춥니다."
        SyncProblem.Auth -> "사용자 이름이나 비밀번호가 맞지 않습니다."
        SyncProblem.NoAccess -> "저장 폴더에 쓸 수 없습니다. 폴더 이름과 그 사용자의 쓰기 권한을 확인하세요."
        SyncProblem.Full -> "서버 저장 공간이 가득 찼습니다. 기록은 몇 KB 라 공간을 비우면 바로 맞춥니다."
        SyncProblem.Tls -> "보안 연결(FTPS)을 맺지 못했습니다. 고급 설정의 보안 연결을 바꿔 보세요."
        SyncProblem.NoTls -> "이 서버는 암호화(FTPS)를 받지 않습니다. 고급 설정의 보안 연결을 ‘자동’ 으로 두세요."
        SyncProblem.Untrusted -> "서버 인증서(키)가 믿은 것과 다릅니다. 누가 가운데서 가로챌 수 있어 맞추지 않았습니다. ‘바꾸기’ 에서 다시 연결 확인을 하세요."
        SyncProblem.Server -> "서버가 잠시 답하지 않습니다. 조금 뒤 다시 맞춥니다."
    }

    fun security(security: FtpSecurity): String = when (security) {
        FtpSecurity.Auto -> "자동"
        FtpSecurity.Explicit -> "FTPS 명시적"
        FtpSecurity.Implicit -> "FTPS 암묵적"
        FtpSecurity.None -> "암호화 없음"
    }

    fun mode(passive: Boolean) = if (passive) "수동(패시브)" else "능동(액티브)"
    fun encoding(encoding: FtpEncoding) = if (encoding == FtpEncoding.EucKr) "EUC-KR" else "자동(UTF-8)"

    /** 띠 제목(안드로이드 `syncPromptTitle`). */
    fun promptTitle(deviceName: String) = "$deviceName 에서 더 읽었습니다"
}

/** 서버 폴더의 파일 하나. [modified] 는 밀리초(모르면 null). */
data class SyncEntry(val name: String, val modified: Long?, val size: Long?)

/** 한 기기의 읽기 기록(안드로이드 `SyncFile`). 책 · 만화는 백업과 같은 모양 — 형광펜 · 읽은 시간은 담지 않는다(결정 8-3). */
class SyncFile(val deviceId: String, val deviceName: String, val updatedAt: Long, val books: List<BackupBook>, val comics: List<BackupComic>)

/** [SyncFile] 의 글 모양: 겉은 기기 정보, 안(`records`)은 백업 파일과 같은 JSON(안드로이드 `SyncCodec` 과 같은 칸). */
object SyncCodec {
    const val FORMAT = "olo-ebook-sync"
    const val VERSION = 1L
    const val UNKNOWN_DEVICE = "다른 기기"

    /** 이보다 큰 파일은 이 앱의 것이 아니다(책 수천 권이라도 몇 MB). */
    const val MAX_BYTES = 8L * 1024 * 1024

    /**
     * 기기 id 는 UUID 다. 사람 · 다른 앱이 만든 사본("…(1).json", "….sync-conflict-….json")은 이 모양에서 벗어나 읽지 않는다 — 읽으면
     * 같은 기기가 둘로 보이고, 옛 사본의 자리로 "더 읽었습니다" 가 뜬다.
     */
    private val NAME = Regex("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.json$")

    fun fileName(deviceId: String) = "$deviceId.json"

    fun deviceIdOf(fileName: String): String? = NAME.matchEntire(fileName)?.groupValues?.get(1)?.lowercase()

    fun encode(file: SyncFile): String {
        val w = JsonWriter()
        val records = BackupFile(file.updatedAt, file.books.map(::bare), file.comics.map(::bare), emptyList())
        w.obj {
            field("format") { string(FORMAT) }
            field("version") { number(VERSION) }
            field("device") { string(file.deviceId) }
            field("deviceName") { string(file.deviceName) }
            field("updatedAt") { number(file.updatedAt) }
            field("records") { with(RecordsCodec) { records(records) } }
        }
        return w.toString()
    }

    /**
     * 이 앱의 기기 파일이 아니면 null. [expectedDeviceId] 를 주면(파일 이름의 id) 안에 적힌 id 가 같아야 한다 — 다른 기기의 파일을 손으로
     * 복사해 이름만 바꾼 것을 두 기기로 세지 않는다. 깨진 기록 칸은 빈 것으로(규칙 6).
     */
    fun decode(text: String, expectedDeviceId: String? = null): SyncFile? {
        if (text.length > MAX_BYTES) return null
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
        val root = runCatching { NSJSONSerialization.JSONObjectWithData(data, 0u, null) }.getOrNull() as? Map<*, *> ?: return null
        if (root["format"] != FORMAT) return null
        val id = (root["device"] as? String)?.lowercase()?.takeIf { deviceIdOf("$it.json") != null } ?: return null
        if (expectedDeviceId != null && id != expectedDeviceId.lowercase()) return null
        val records = (root["records"] as? Map<*, *>)?.let { RecordsCodec.decodeRoot(it) }
        val name = (root["deviceName"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_DEVICE
        val updated = (root["updatedAt"] as? NSNumber)?.takeIf { root["updatedAt"] !is String }?.longLongValue ?: 0L
        return SyncFile(id, name, updated, records?.books.orEmpty().map(::bare), records?.comics.orEmpty().map(::bare))
    }

    /** 형광펜 · 읽은 시간을 뺀 책. 다른 판이 담아 보냈어도 버린다 — 들이면 이쪽 형광펜과 합치는 규칙이 필요하다. */
    private fun bare(b: BackupBook) = BackupBook(b.name, b.size, b.title, b.author, b.progress, b.openedAt, b.finishedAt, b.bookmarks, emptyList(), emptyList())

    private fun bare(c: BackupComic) = BackupComic(c.name, c.size, c.page, c.pageCount, c.offset, c.half, c.updatedAt, c.finishedAt, c.bookmarks, emptyList())
}

/**
 * 묻기 · 합치기 규칙(안드로이드 `SyncRules`). 읽은 자리는 **저절로 옮기지 않는다**(결정 8-2) — 더 읽었으면 띠로 묻는다. 다시 처음부터
 * 읽는 사람의 자리를 다른 기기의 옛 자리가 덮으면 안 된다.
 */
object SyncRules {
    /**
     * 다른 기기의 자리가 지금 보이는 쪽보다 **한 쪽 이상** 뒤인가(EPUB · TXT). 보이는 쪽은 장 [shownSpine] 의 글자 [shownEndExclusive]
     * 앞에서 끝난다. 글자 수로 한 쪽을 어림하지 않는다 — 기기마다 한 쪽의 글자 수가 달라, 같은 쪽 안의 자리로 띠를 띄우면 눌러도
     * 같은 쪽에 머문다.
     */
    fun reflowBeyond(remoteSpine: Int, remoteOffset: Int, shownSpine: Int, shownEndExclusive: Int): Boolean =
        remoteSpine > shownSpine || (remoteSpine == shownSpine && remoteOffset >= shownEndExclusive)

    /** PDF: 다른 기기의 쪽이 지금 보이는 마지막 쪽(두쪽이면 오른쪽)보다 뒤인가. */
    fun pageBeyond(remotePage: Int, shownLast: Int): Boolean = remotePage > shownLast

    /** 만화: 쪽이 뒤이거나, 같은 그림(웹툰) 안에서 반 장 이상 아래인가. 그보다 가까우면 지금 화면에 거의 보이는 자리다. */
    fun comicBeyond(remotePage: Int, remoteOffset: Double?, localPage: Int, localOffset: Double?): Boolean =
        remotePage > localPage || (remotePage == localPage && (remoteOffset ?: 0.0) - (localOffset ?: 0.0) >= 0.5)

    /**
     * 다른 기기의 책갈피 가운데 이 기기에 넣을 것: 이 기기에 없고, 전에 받은 적도 없는 것. 받은 적이 있는데 지금 없으면 이 기기에서
     * 사람이 뺀 것이다 — 다시 넣으면 뺀 책갈피가 다른 기기를 다녀올 때마다 되살아난다.
     */
    fun bookmarksToImport(item: String, local: Set<String>, remote: List<String>, seen: Set<String>): List<String> =
        remote.distinct().filter { it !in local && bookmarkKey(item, it) !in seen }

    fun bookmarkKey(item: String, locator: String) = "b\u0000$item\u0000$locator"

    /**
     * 다 읽은 때를 바꿀 값: **가장 이른 때**가 남는다. 이 기기에 없으면 들이되, 전에 들였던 같은 때([seen])라면 사람이 이 기기에서
     * "다 읽음" 을 거둔 것이니 다시 넣지 않는다. 바꿀 것이 없으면 null.
     */
    fun finishedToApply(local: Long?, remote: Long?, seen: Boolean): Long? = when {
        remote == null -> null
        local == null -> remote.takeIf { !seen }
        remote < local -> remote
        else -> null
    }
}

/** 다른 기기가 더 읽은 책의 자리. [spine] · [offset] 은 EPUB · TXT, [page] 는 PDF(0부터). */
class SyncBookOffer(val deviceName: String, val updatedAt: Long, val locator: String, val percent: Float, val spine: Int?, val offset: Int?, val page: Int?)

/** 다른 기기가 더 읽은 만화 권의 자리. [half] 는 그 기기가 보던 펼침면의 반("LEFT" · "RIGHT"). */
class SyncComicOffer(val deviceName: String, val updatedAt: Long, val page: Int, val offset: Double?, val pageCount: Int?, val half: String?)

/** 함께 읽는 기기 하나. */
class SyncDevice(val id: String, val name: String, val updatedAt: Long, val titles: Int)

/**
 * FTP 서버의 답 풀이(아파치 commons-net 이 하던 것). 소켓은 Swift 가 들고, 글 풀이는 여기서 시험한다.
 */
object FtpReplies {
    /** 답 첫 줄의 세 자리 번호. 읽을 수 없으면 0. */
    fun code(line: String): Int = line.take(3).toIntOrNull()?.takeIf { line.length == 3 || line[3] == ' ' || line[3] == '-' } ?: 0

    /** 여러 줄 답("230-…" … "230 끝")의 마지막 줄인가. */
    fun isLast(line: String, code: Int): Boolean = line.length >= 4 && line.startsWith(code.toString().padStart(3, '0')) && line[3] == ' '

    /** 227 답의 (h1,h2,h3,h4,p1,p2) → 포트. 주소는 버린다 — NAT 뒤 서버가 안쪽 주소를 알리는 일이 흔해, 잇던 주소로 다시 잇는다. */
    fun pasvPort(reply: String): Int? {
        val nums = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(reply)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
        if (nums.any { it !in 0..255 }) return null
        return (nums[4] * 256 + nums[5]).takeIf { it in 1..65535 }
    }

    /** 229 답의 (|||포트|) → 포트(IPv6 서버). */
    fun epsvPort(reply: String): Int? =
        Regex("\\((.)\\1\\1(\\d+)\\1\\)").find(reply)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** FEAT 답에 MLSD 가 있는가. 있으면 목록을 기계가 읽는 모양으로 받는다(때 · 크기가 정확하다). */
    fun hasMlsd(feat: String): Boolean = feat.lineSequence().any { it.trim().uppercase().let { l -> l == "MLSD" || l.startsWith("MLSD ") || l.startsWith("MLST") } }

    /** MLSD 한 줄: "type=file;size=123;modify=20261023120000; 이름". 파일이 아니면 null. */
    fun mlsd(line: String): SyncEntry? {
        val space = line.indexOf(' ')
        if (space <= 0) return null
        val facts = line.substring(0, space).split(';').filter { '=' in it }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val name = line.substring(space + 1).trimEnd('\r', '\n')
        if (name.isEmpty() || facts["type"]?.lowercase() != "file") return null
        return SyncEntry(name, facts["modify"]?.let(::mdtm), facts["size"]?.toLongOrNull())
    }

    /**
     * LIST 한 줄(MLSD 가 없는 서버). 유닉스 모양("-rw-r--r-- 1 u g 1234 Oct 23 12:00 이름")과 윈도 IIS 모양
     * ("10-23-26  12:00PM  1234 이름")을 읽는다. 폴더 · 읽을 수 없는 줄은 null. 때는 분 단위(유닉스의 옛 파일은 날짜만)라 크기와 함께 본다.
     */
    fun list(line: String, nowYear: Int): SyncEntry? {
        val t = line.trimEnd('\r', '\n')
        val parts = t.split(Regex("\\s+"))
        if (t.startsWith("-") && parts.size >= 9) {
            val size = parts[4].toLongOrNull() ?: return null
            val month = MONTHS.indexOf(parts[5].lowercase().take(3)).takeIf { it >= 0 } ?: return null
            val day = parts[6].toIntOrNull() ?: return null
            var year = nowYear
            var hour = 0
            var minute = 0
            if (':' in parts[7]) {
                hour = parts[7].substringBefore(':').toIntOrNull() ?: return null
                minute = parts[7].substringAfter(':').toIntOrNull() ?: return null
            } else {
                year = parts[7].toIntOrNull() ?: return null
            }
            val name = nthField(t, 8) ?: return null
            return SyncEntry(name, epochMs(year, month + 1, day, hour, minute, 0), size)
        }
        val iis = Regex("^(\\d{2})-(\\d{2})-(\\d{2,4})\\s+(\\d{1,2}):(\\d{2})(AM|PM)\\s+(\\d+)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t) ?: return null
        val g = iis.groupValues
        val y = g[3].toInt().let { if (it < 100) 2000 + it else it }
        val h = g[4].toInt() % 12 + if (g[6].uppercase() == "PM") 12 else 0
        return SyncEntry(g[8], epochMs(y, g[1].toInt(), g[2].toInt(), h, g[5].toInt(), 0), g[7].toLong())
    }

    /** "20261023120000(.123)" → 밀리초(UTC). 깨지면 null. */
    fun mdtm(text: String): Long? {
        val d = text.trim()
        if (d.length < 14 || !d.take(14).all { it.isDigit() }) return null
        fun n(from: Int, len: Int) = d.substring(from, from + len).toInt()
        val y = n(0, 4)
        val mo = n(4, 2)
        val day = n(6, 2)
        val h = n(8, 2)
        val mi = n(10, 2)
        val s = n(12, 2)
        if (mo !in 1..12 || day !in 1..31 || h > 23 || mi > 59 || s > 60) return null
        return epochMs(y, mo, day, h, mi, s)
    }

    /** 거절 답 번호를 사람에게 보일 갈래로(안드로이드 `failed`). */
    fun problemOf(code: Int, fallback: SyncProblem = SyncProblem.Server): SyncProblem = when (code) {
        452, 552 -> SyncProblem.Full
        530 -> SyncProblem.Auth
        550, 553 -> SyncProblem.NoAccess
        else -> fallback
    }

    /** 저장 폴더를 한 칸씩(빈 칸 · "." 은 건너뜀). 앞이 "/" 면 맨 위에서 시작한다. */
    fun folderParts(path: String): List<String> = path.trim().split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** 빈칸으로 나뉜 [n] 번째 칸부터 줄 끝까지(이름에 빈칸이 있어도 그대로). */
    private fun nthField(line: String, n: Int): String? {
        var i = 0
        var field = 0
        while (i < line.length && field < n) {
            while (i < line.length && !line[i].isWhitespace()) i++
            while (i < line.length && line[i].isWhitespace()) i++
            field++
        }
        return line.substring(i).takeIf { it.isNotEmpty() }
    }

    /** 그레고리력 날짜 · 시각(UTC) → 밀리초. */
    private fun epochMs(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int): Long {
        val yy = if (m <= 2) y - 1L else y.toLong()
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097 + doe - 719468
        return ((days * 24 + h) * 60 + mi) * 60_000L + s * 1000L
    }
}

/** 지문 · 해시. */
object SyncHash {
    /**
     * SHA-256 지문을 "AB:CD:…" 대문자 콜론으로(안드로이드 `fingerprintOf`). NAS 설정 화면이 보이는 모양과 같아 사람이 눈으로 맞춰 본다.
     * 인증서는 DER 바이트, SSH 서버 키는 키 덩어리(공개 키 줄의 base64 를 푼 것).
     */
    fun fingerprint(data: NSData): String = hex(sha256(data), ":")

    fun sha1Hex(text: String): String {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
        val bytes = memScoped {
            val out = allocArray<UByteVar>(CC_SHA1_DIGEST_LENGTH)
            CC_SHA1(data.bytes, data.length.convert(), out)
            (0 until CC_SHA1_DIGEST_LENGTH).map { out[it].toInt() }
        }
        return bytes.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    private fun sha256(data: NSData): List<Int> = memScoped {
        val out = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
        CC_SHA256(data.bytes, data.length.convert(), out)
        (0 until CC_SHA256_DIGEST_LENGTH).map { out[it].toInt() }
    }

    private fun hex(bytes: List<Int>, sep: String) = bytes.joinToString(sep) { it.toString(16).padStart(2, '0').uppercase() }
}

/**
 * 이 기기의 이어 읽기 상태와 맞추기 순서(안드로이드 `ReadingSync` 의 서버 없는 부분). Swift 가 서버와 주고받으며 순서대로 부른다:
 * 목록 → [listed] → 바뀐 파일만 읽어 [received] → [mergeFresh] → [upload] → 쓰고 [uploaded] → [markSynced].
 *
 * 상태(기기 id · 켬 · 받은 것)는 [stateDir] 에 둔다. Swift 가 그 폴더를 **백업에서 뺀다** — 기기 id 가 새 휴대폰으로 따라가면 두 기기가
 * 한 파일을 번갈아 덮어 서로의 읽은 자리를 지운다. 한 번에 한 곳(Swift actor)에서만 부른다.
 */
class IosReadingSync(private val store: ReadingStore, private val stateDir: String, private val newId: () -> String = { NSUUID().UUIDString.lowercase() }) {

    private class Cached(val entry: SyncEntry, val file: SyncFile?)

    private var others: Map<String, Cached> = emptyMap()
    private var fresh: List<SyncFile> = emptyList()

    /** 서버에 이 기기의 파일이 있는가. 모르면(아직 목록을 받지 않음) null. */
    private var ownOnServer: Boolean? = null

    /** 마지막으로 올린 내용(때를 뺀 것). 같으면 올리지 않는다 — 쪽을 넘길 때마다 서버에 쓰지 않게. */
    private var written: String? = null
    private var pending: String? = null

    private var listedAt = Long.MIN_VALUE / 2
    private var unreachableAt = Long.MIN_VALUE / 2

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(stateDir, true, null, null)
    }

    val deviceId: String by lazy {
        read(DEVICE_ID)?.trim()?.lowercase()?.takeIf { SyncCodec.deviceIdOf("$it.json") != null } ?: newId().also { write(DEVICE_ID, it) }
    }

    /** 켬. 다시 켜면 처음처럼 한 번 올린다 — 꺼 둔 사이 바뀐 기록이 "이미 올림" 으로 걸러지지 않게. 서버를 바꿔도 다시 켜는 것으로 새로 올린다. */
    var enabled: Boolean
        get() = read(ENABLED) != null
        set(value) {
            if (value) write(ENABLED, "1") else remove(ENABLED)
            written = null
            ownOnServer = null
        }

    val lastSyncedAt: Long? get() = read(LAST_SYNC)?.trim()?.toLongOrNull()

    /** 사람이 지은 이 기기 이름(3A). 없으면 null — Swift 가 모델 이름을 쓴다. */
    var customName: String?
        get() = read(DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            val v = value?.trim()?.takeIf { it.isNotEmpty() }
            if (v == null) remove(DEVICE_NAME) else write(DEVICE_NAME, v)
            written = null
        }

    /** 목록을 받았다. 읽어야 할 파일(새로 생겼거나 때 · 크기가 바뀐 다른 기기의 것)을 돌려준다. */
    fun listed(entries: List<SyncEntry>, now: Long): List<SyncEntry> {
        listedAt = now
        ownOnServer = entries.any { SyncCodec.deviceIdOf(it.name) == deviceId }
        val keep = HashMap<String, Cached>()
        val toRead = ArrayList<SyncEntry>()
        for (e in entries) {
            // 모르는 파일 · 올리는 중의 임시 파일(점으로 시작)은 deviceIdOf 가 거른다. 이 기기의 파일은 읽지 않는다.
            val id = SyncCodec.deviceIdOf(e.name) ?: continue
            if (id == deviceId || id in keep) continue
            val old = others[id]
            if (old != null && old.entry.name == e.name && e.modified != null && old.entry.modified == e.modified && old.entry.size == e.size) {
                keep[id] = old
            } else {
                toRead += e
            }
        }
        // 읽을 파일은 받기 전까지 옛것을 남긴다 — 읽다 끊기면 마지막으로 안 자리라도 띠로 묻는다.
        for (e in toRead) SyncCodec.deviceIdOf(e.name)?.let { id -> others[id]?.let { keep[id] = it } }
        others = keep
        fresh = emptyList()
        return toRead
    }

    /** 파일 하나를 받았다(못 읽었으면 null). 깨진 파일도 "읽었음" 으로 적는다 — 바뀌기 전에는 다시 읽지 않는다. */
    fun received(entry: SyncEntry, text: String?) {
        val id = SyncCodec.deviceIdOf(entry.name) ?: return
        val file = text?.let { SyncCodec.decode(it, id) }
        others = others + (id to Cached(entry, file))
        if (file != null) fresh = fresh + file
    }

    /**
     * 새로 받은 파일의 책갈피 · 다 읽은 때를 이 기기의 같은 책 · 만화에 합친다(읽은 자리는 아니다 — 띠가 묻는다). 바꾼 것의 수.
     * 책은 이름 + 크기로 맞춘다 — 기기마다 책 폴더 · 주소가 다르다.
     */
    fun mergeFresh(shelf: List<ShelfBook>, comics: List<ShelfComic>): Int {
        val files = fresh
        fresh = emptyList()
        val seen = seen()
        val added = LinkedHashSet<String>()
        var changed = 0
        for (file in files) {
            for (r in file.books) {
                for (book in shelf.filter { matches(it.name, it.size, r.name, r.size) }) {
                    val item = itemKey(r.name, r.size)
                    val local = store.bookmarksOf(book.id).mapTo(HashSet()) { Locator.encode(it.locator) }
                    val remote = r.bookmarks.mapNotNull { m -> Locator.decodeOrNull(m.locator)?.let { Locator.encode(it) to m } }
                    // 이미 있는 것도 "본 것" 으로 적는다 — 그래야 나중에 이 기기에서 빼면 다시 들어오지 않는다.
                    remote.filter { it.first in local }.forEach { added += SyncHash.sha1Hex(SyncRules.bookmarkKey(item, it.first)) }
                    val seenHere = remote.map { it.first }.filterTo(HashSet()) { SyncHash.sha1Hex(SyncRules.bookmarkKey(item, it)) in seen }
                        .mapTo(HashSet()) { SyncRules.bookmarkKey(item, it) }
                    val take = SyncRules.bookmarksToImport(item, local, remote.map { it.first }, seenHere)
                    val marks = take.mapNotNull { l -> remote.firstOrNull { it.first == l }?.second?.let { BackupBookmark(l, it.snippet, it.createdAt) } }
                    store.syncBookmarks(book.id, marks)
                    marks.forEach { added += SyncHash.sha1Hex(SyncRules.bookmarkKey(item, it.locator)) }
                    changed += marks.size
                    val remoteFinished = r.finishedAt ?: continue
                    val key = SyncHash.sha1Hex("f\u0000$item\u0000$remoteFinished")
                    SyncRules.finishedToApply(store.finishedOf(book.id), remoteFinished, key in seen)?.let { at ->
                        store.syncFinished(book.id, at, r.openedAt)
                        changed++
                    }
                    added += key
                }
            }
            for (r in file.comics) {
                for (unit in comics.filter { matches(it.name, it.size, r.name, r.size) }) {
                    val item = itemKey(r.name, r.size)
                    val local = store.comicBookmarks(unit.id).mapTo(HashSet()) { it.toString() }
                    val remote = r.bookmarks.map { it.page.toString() }
                    remote.filter { it in local }.forEach { added += SyncHash.sha1Hex(SyncRules.bookmarkKey(item, it)) }
                    val seenHere = remote.filter { SyncHash.sha1Hex(SyncRules.bookmarkKey(item, it)) in seen }.mapTo(HashSet()) { SyncRules.bookmarkKey(item, it) }
                    val take = SyncRules.bookmarksToImport(item, local, remote, seenHere)
                    val marks = take.mapNotNull { p -> r.bookmarks.firstOrNull { it.page.toString() == p } }
                    store.syncComicBookmarks(unit.id, marks)
                    marks.forEach { added += SyncHash.sha1Hex(SyncRules.bookmarkKey(item, it.page.toString())) }
                    changed += marks.size
                    val remoteFinished = r.finishedAt ?: continue
                    val key = SyncHash.sha1Hex("f\u0000$item\u0000$remoteFinished")
                    SyncRules.finishedToApply(store.comicProgress()[unit.id]?.finishedAtEpochMs, remoteFinished, key in seen)?.let { at ->
                        store.syncComicFinished(unit.id, at, r.pageCount)
                        changed++
                    }
                    added += key
                }
            }
        }
        remember(added - seen)
        return changed
    }

    /** 올리기 전에 서버 목록을 받아야 하는가 — 이 기기 파일이 서버에 있는지 모르면 받는다(빈 파일을 만들지 않으려고). */
    val needsListing: Boolean get() = ownOnServer == null

    /**
     * 올릴 글. 바뀐 것이 없거나, 적을 것이 없고 서버에도 없으면 null — 책을 하나도 안 읽은 기기가 빈 파일로 "함께 읽는 기기" 에 끼지
     * 않게. 같은 기록이면 같은 글이 되게 이름순. 같은 파일이 두 폴더에 있으면 한 번만(다른 기기에서는 구별되지 않는다).
     */
    fun upload(shelf: List<ShelfBook>, comics: List<ShelfComic>, deviceName: String, now: Long): String? {
        val books = shelf.mapNotNull { store.recordOf(it.id, it.name, it.size, it.title, it.author) }
            .filter { it.progress != null || it.finishedAt != null || it.bookmarks.isNotEmpty() }
            .map { BackupBook(it.name, it.size, it.title, it.author, it.progress, it.openedAt, it.finishedAt, it.bookmarks.sortedBy { m -> m.locator }, emptyList(), emptyList()) }
            .distinctBy { nfc(it.name) to it.size }
            .sortedWith(compareBy({ it.name }, { it.size ?: -1 }))
        val units = comics.mapNotNull { store.recordOfComic(it.id, it.name, it.size) }
            .filter { it.page != null || it.bookmarks.isNotEmpty() }
            .distinctBy { nfc(it.name) to it.size }
            .sortedWith(compareBy({ it.name }, { it.size ?: -1 }))
        val fingerprint = SyncCodec.encode(SyncFile(deviceId, deviceName, 0L, books, units))
        if (written == fingerprint) return null
        if (ownOnServer == false && books.isEmpty() && units.isEmpty()) {
            written = fingerprint
            return null
        }
        pending = fingerprint
        return SyncCodec.encode(SyncFile(deviceId, deviceName, now, books, units))
    }

    /** [upload] 의 글을 서버에 썼다. */
    fun uploaded() {
        written = pending
        pending = null
        ownOnServer = true
    }

    fun markSynced(now: Long) {
        unreachableAt = Long.MIN_VALUE / 2
        write(LAST_SYNC, now.toString())
    }

    /** 서버에 닿지 못했다. 1분은 책을 열어도 다시 묻지 않는다 — 꺼진 서버에 책마다 시간 제한만큼 기다리지 않게. */
    fun unreachable(now: Long) {
        unreachableAt = now
    }

    /** 책을 열 때 목록을 다시 받을지. 방금 받았으면(책을 연달아 열 때) 받아 둔 것을 쓴다 — 다른 기기에서 막 읽고 온 것은 놓치지 않게 20초. */
    fun shouldRefreshForOffer(now: Long): Boolean = now - listedAt >= OFFER_REFRESH_MS && now - unreachableAt >= UNREACHABLE_PAUSE_MS

    /** 이 책을 다른 기기가 더 읽었으면 그 자리, 그 가운데 가장 뒤. 꺼져 있거나 없으면 null. 깨진 자리는 고르지 않는다. */
    fun offerForBook(bookId: String, name: String, size: Long?): SyncBookOffer? {
        if (!enabled) return null
        val local = store.progressOf(bookId)?.let { BackupProgress(Locator.encode(it.locator), it.percent, it.updatedAtEpochMs) }
        var best: Pair<SyncFile, BackupProgress>? = null
        for (c in others.values) {
            val f = c.file ?: continue
            val p = f.books.firstOrNull { matches(name, size, it.name, it.size) }?.progress ?: continue
            if (Locator.decodeOrNull(p.locator) == null) continue
            if (local != null && !isFurther(p, local)) continue
            if (best == null || isFurther(p, best.second)) best = f to p
        }
        val (file, p) = best ?: return null
        val locator = Locator.decodeOrNull(p.locator) ?: return null
        return SyncBookOffer(
            file.deviceName, p.updatedAt, p.locator, p.percent,
            (locator as? Locator.Reflow)?.spine, (locator as? Locator.Reflow)?.charOffset, (locator as? Locator.FixedPage)?.page,
        )
    }

    /** 만화 권판 [offerForBook]: 쪽(같은 쪽이면 그림 안 비율)이 더 뒤인 기기. */
    fun offerForComic(unitId: String, name: String, size: Long?): SyncComicOffer? {
        if (!enabled) return null
        val local = store.comicProgress()[unitId]?.let {
            BackupComic(name, size, it.page, it.pageCount, it.offset?.toDouble(), null, it.updatedAtEpochMs, it.finishedAtEpochMs, emptyList(), emptyList())
        }
        var best: Pair<SyncFile, BackupComic>? = null
        for (c in others.values) {
            val f = c.file ?: continue
            val r = f.comics.firstOrNull { matches(name, size, it.name, it.size) }?.takeIf { it.page != null } ?: continue
            if (local != null && !furtherComic(r, local)) continue
            if (best == null || furtherComic(r, best.second)) best = f to r
        }
        val (file, r) = best ?: return null
        return SyncComicOffer(file.deviceName, r.updatedAt, r.page ?: return null, r.offset, r.pageCount, r.half)
    }

    /** 지금까지 본 다른 기기, 최근에 기록을 남긴 것부터. */
    fun devices(): List<SyncDevice> = others.values.mapNotNull { it.file }
        .map { SyncDevice(it.deviceId, it.deviceName, it.updatedAt, it.books.size + it.comics.size) }
        .sortedByDescending { it.updatedAt }

    /** 지울 기기의 파일. 모르면 null. */
    fun entryOf(deviceId: String): SyncEntry? = others[deviceId]?.entry

    fun forgot(deviceId: String) {
        others = others - deviceId
    }

    /** 서버 기록을 모두 지웠다 — 이 기기에서 끈다. 다른 기기는 켜져 있으면 다음에 제 파일을 다시 쓴다. */
    fun cleared() {
        others = emptyMap()
        enabled = false
    }

    /** 폴더의 기기 파일들(모두 지우기). 다른 파일 · 임시 파일은 건드리지 않는다. */
    fun deviceFiles(entries: List<SyncEntry>): List<SyncEntry> = entries.filter { SyncCodec.deviceIdOf(it.name) != null }

    private fun matches(name: String, size: Long?, otherName: String, otherSize: Long?) =
        nfc(name) == nfc(otherName) && (size == null || otherSize == null || size == otherSize)

    private fun itemKey(name: String, size: Long?) = "$name\u0000${size ?: ""}"

    private var seenCache: MutableSet<String>? = null

    private fun seen(): Set<String> = seenCache ?: read(SEEN).orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet().also { seenCache = it }

    private fun remember(keys: Set<String>) {
        if (keys.isEmpty()) return
        val all = (seenCache ?: seen().toMutableSet()).apply { addAll(keys) }
        seenCache = all
        write(SEEN, all.joinToString("\n", postfix = "\n"))
    }

    private fun path(name: String) = "$stateDir/$name"

    private fun read(name: String): String? = NSString.stringWithContentsOfFile(path(name), NSUTF8StringEncoding, null)

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun write(name: String, text: String) {
        (text as NSString).writeToFile(path(name), atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun remove(name: String) {
        NSFileManager.defaultManager.removeItemAtPath(path(name), null)
    }

    companion object {
        private const val DEVICE_ID = "device-id"
        private const val ENABLED = "ftp-enabled"
        private const val SEEN = "seen-v2.txt"
        private const val LAST_SYNC = "last-sync"
        private const val DEVICE_NAME = "device-name"
        const val OFFER_REFRESH_MS = 20_000L
        const val UNREACHABLE_PAUSE_MS = 60_000L

        /** 연결 확인에 잠깐 쓰는 이름. 점으로 시작해 기기 파일로 읽히지 않는다. */
        const val PROBE = ".olo-check"

        /** 올리는 중의 이름. 점으로 시작해 다른 기기 목록에서 기기 파일로 읽히지 않는다. */
        fun partName(name: String) = ".$name.part"
    }
}
