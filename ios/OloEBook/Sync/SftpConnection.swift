import Citadel
import Foundation
import NIOCore
import NIOSSH
import OloCore

/// SFTP 연결(구상안 50 · 1A — 안드로이드는 JSch, iOS 는 Citadel). 서버 키는 사람이 믿기로 한 지문과 같을 때만 받는다 — SSH 에는 공인
/// 기관이 없어, 처음 잇는 서버는 늘 지문을 묻는다. 지문이 바뀌면 다시 묻는다(누가 가운데서 가로채면 키가 바뀐다).
///
/// SFTP 에는 "들어가 있는 폴더" 가 없어 저장 폴더의 실제 경로([folder])를 붙여 부른다.
/// 처음 보는 · 바뀐 서버 키(Citadel 의 `InvalidHostKey` 는 밖에서 만들 수 없다).
private struct UnknownHostKey: Error {}

final class SftpConnection: SyncConnection {
    private let client: SSHClient
    private let sftp: SFTPClient
    private let folder: String

    private init(client: SSHClient, sftp: SFTPClient, folder: String) {
        self.client = client
        self.sftp = sftp
        self.folder = folder
    }

    /// 서버 키 판단. 믿은 지문과 같으면 받고, 아니면 지문을 남겨 거절한다.
    private final class PinnedKey: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
        let pinned: String?
        private(set) var seen: String?

        init(pinned: String?) { self.pinned = pinned }

        func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
            let blob = Self.blob(hostKey)
            if let blob, SyncHash.shared.sshMatches(data: blob, pinned: pinned) {
                validationCompletePromise.succeed(())
                return
            }
            // ssh-keygen 모양("SHA256:…")으로 보인다 — NAS 의 SSH 화면과 맞춰 볼 수 있게(안드로이드 0.59.2).
            seen = blob.map { SyncHash.shared.sshFingerprint(data: $0) }
            validationCompletePromise.fail(UnknownHostKey())
        }

        /// 키 덩어리(공개 키 줄 "ssh-ed25519 AAAA…" 의 base64 를 푼 것) — 지문은 이것의 SHA-256, 안드로이드 JSch 가 넘기는 바이트와 같다.
        static func blob(_ key: NIOSSHPublicKey) -> Data? {
            let line = String(openSSHPublicKey: key)
            let parts = line.split(separator: " ")
            guard parts.count >= 2 else { return nil }
            return Data(base64Encoded: String(parts[1]))
        }
    }

    static func open(_ config: FtpConfig, password: String, timeout: TimeInterval) async throws -> SftpConnection {
        let keys = PinnedKey(pinned: config.trusted)
        let host = config.host.trimmingCharacters(in: .whitespaces)
        let client: SSHClient
        do {
            // TCP 잇기에만 시간 제한이 있고, 그 뒤 SSH 인사 · 로그인에는 없다 — 전체를 한 번 더 묶는다. 늦게 이어진 연결은 닫는다.
            client = try await withDeadline(timeout * 2, late: { (late: SSHClient) in Task { try? await late.close() } }) {
                try await SSHClient.connect(
                    host: host, port: Int(config.port),
                    authenticationMethod: .passwordBased(username: config.user, password: password),
                    hostKeyValidator: .custom(keys), reconnect: .never,
                    connectTimeout: .milliseconds(Int64(timeout * 1000))
                )
            }
        } catch {
            throw failure(error, seen: keys.seen)
        }
        do {
            let (sftp, folder) = try await withDeadline(timeout, onTimeout: { Task { try? await client.close() } }) {
                let sftp = try await client.openSFTP()
                return (sftp, try await enterFolder(sftp, config.path))
            }
            return SftpConnection(client: client, sftp: sftp, folder: folder)
        } catch {
            try? await client.close()
            throw failure(error, seen: nil)
        }
    }

    /// [path] 의 실제 경로. 없는 폴더는 한 칸씩 만든다. 앞에 "/" 가 없으면 로그인한 자리(집 폴더)에서부터.
    private static func enterFolder(_ sftp: SFTPClient, _ path: String) async throws -> String {
        var here = path.trimmingCharacters(in: .whitespaces).hasPrefix("/") ? "/" : try await sftp.getRealPath(atPath: ".")
        do {
            for part in FtpReplies.shared.folderParts(path: path) {
                let next = here.hasSuffix("/") ? here + part : here + "/" + part
                if (try? await sftp.getAttributes(at: next)) == nil { try await sftp.createDirectory(atPath: next) }
                here = next
            }
        } catch {
            throw SyncFailure(problem: .noaccess, detail: "folder \(path): \(error)")
        }
        return here
    }

    private func path(_ name: String) -> String { folder.hasSuffix("/") ? folder + name : folder + "/" + name }

    /// SSH 오류를 갈래로(안드로이드 `ssh`).
    private static func failure(_ error: Error, seen: String?) -> SyncFailure {
        if let f = error as? SyncFailure { return f }
        if error is UnknownHostKey || error is InvalidHostKey, let seen { return SyncFailure(problem: .untrusted, fingerprint: seen, detail: "unknown host key") }
        if error is AuthenticationFailed { return SyncFailure(problem: .auth, detail: "login refused") }
        if let c = error as? SSHClientError, case .allAuthenticationOptionsFailed = c { return SyncFailure(problem: .auth, detail: "login refused") }
        if let s = error as? SFTPMessage.Status { return status(s) }
        if let e = error as? SFTPError, case let .errorStatus(s) = e { return status(s) }
        if let c = error as? ChannelError {
            if case .connectTimeout = c { return SyncFailure(problem: .asleep, detail: "timed out") }
            return SyncFailure(problem: .offline, detail: "\(c)")
        }
        if let io = error as? IOError { return SyncFailure.of(NSError(domain: NSPOSIXErrorDomain, code: Int(io.errnoCode))) }
        // 서로 맞는 키 교환 · 암호 방식이 없다(옛 NAS 의 ssh-rsa 만 등). 다시 해도 같다(안드로이드 0.59.2).
        if let s = error as? NIOSSHError, s.type == .keyExchangeNegotiationFailure { return SyncFailure(problem: .incompatible, detail: "no common ssh algorithm") }
        // 모든 주소에 잇지 못함(NIOPosix 의 NIOConnectionError — 그 모듈을 따로 들이지 않으려 글로 본다): 이름을 찾지 못했으면 주소가
        // 틀렸고, 거절이면 서버가 꺼져 있다.
        let text = "\(error)"
        if text.contains("NIOConnectionError") {
            if text.contains("dnsAError: Optional") && text.contains("dnsAAAAError: Optional") { return SyncFailure(problem: .unreachable, detail: "unknown host") }
            if text.contains("connectTimeout") { return SyncFailure(problem: .asleep, detail: "timed out") }
            return SyncFailure(problem: .unreachable, detail: text)
        }
        // 모르는 SSH 오류는 "인터넷 없음" 이 아니다 — 그렇게 알리면 사람이 엉뚱한 것(와이파이)을 고친다.
        return SyncFailure(problem: .server, detail: "ssh failed: \(error)")
    }

    /// SFTP 오류의 상태 번호(없으면 nil).
    private static func statusCode(_ error: Error) -> UInt32? {
        if let s = error as? SFTPMessage.Status { return s.errorCode.rawValue }
        if let e = error as? SFTPError, case let .errorStatus(s) = e { return s.errorCode.rawValue }
        return nil
    }

    private static func status(_ s: SFTPMessage.Status) -> SyncFailure {
        switch s.errorCode {
        case .permissionDenied, .noSuchFile: return SyncFailure(problem: .noaccess, detail: s.message)
        default: return SyncFailure(problem: .server, detail: s.message)
        }
    }

    /// 파일 일 하나의 시간 제한. 읽는 도중 와이파이가 끊기면 SFTP 요청은 답을 영영 기다린다 — 그동안 맞추기 줄이 막혀 책을 열 때의
    /// 묻기까지 멈췄다. 시간이 다 되면 연결을 끊어 걸린 요청도 끝나게 한다.
    private static let opTimeout: TimeInterval = 30

    private func guarded<T>(_ work: @escaping () async throws -> T) async throws -> T {
        let client = self.client
        do {
            return try await withDeadline(Self.opTimeout, onTimeout: { Task { try? await client.close() } }, work)
        } catch {
            throw Self.failure(error, seen: nil)
        }
    }

    private static func isMissing(_ error: Error) -> Bool {
        if let s = error as? SFTPMessage.Status { return s.errorCode == .noSuchFile }
        if let e = error as? SFTPError, case let .errorStatus(s) = e { return s.errorCode == .noSuchFile }
        return false
    }

    // ── SyncConnection ────────────────────────────────────────

    func list() async throws -> [SyncEntry] {
        let sftp = self.sftp, folder = self.folder
        return try await guarded {
            try await sftp.listDirectory(atPath: folder).flatMap(\.components).compactMap { c in
                // 보통 파일만(폴더 · 링크는 아니다). 권한 칸이 없는 서버는 크기가 있는 것을 파일로 본다.
                let regular = c.attributes.permissions.map { $0 & 0o170000 == 0o100000 } ?? (c.attributes.size != nil)
                guard regular, c.filename != ".", c.filename != ".." else { return nil }
                let modified = c.attributes.accessModificationTime.map { KotlinLong(value: Int64($0.modificationTime.timeIntervalSince1970 * 1000)) }
                return SyncEntry(name: c.filename, modified: modified, size: c.attributes.size.map { KotlinLong(value: Int64($0)) })
            }
        }
    }

    func read(_ name: String, maxBytes: Int) async throws -> String? {
        let sftp = self.sftp, file = path(name)
        // 조각으로 읽다가 [maxBytes] 를 넘으면 그만 — 목록에 크기가 없는 서버에서 남의 큰 파일을 통째로 메모리에 올리지 않게. 없어진
        // 파일(목록과 읽기 사이에 다른 기기가 바꾸는 중)과 너무 큰 파일은 nil.
        let data: Data? = try await guarded {
            do {
                return try await sftp.withFile(filePath: file, flags: .read) { f in
                    var all = Data()
                    while all.count <= maxBytes {
                        let chunk = try await f.read(from: UInt64(all.count), length: 32 * 1024)
                        if chunk.readableBytes == 0 { break }
                        all.append(contentsOf: chunk.readableBytesView)
                    }
                    return all.count > maxBytes ? nil : all
                }
            } catch {
                if Self.isMissing(error) { return nil }
                throw error
            }
        }
        return data.map { String(decoding: $0, as: UTF8.self) }
    }

    func write(_ name: String, _ data: Data) async throws {
        let sftp = self.sftp, temp = path(IosReadingSync.companion.partName(name: name)), target = path(name)
        try await guarded {
            try await sftp.withFile(filePath: temp, flags: [.write, .create, .truncate]) { try await $0.write(ByteBuffer(bytes: data)) }
        }
        try await guarded {
            do {
                try await sftp.rename(at: temp, to: target)
            } catch {
                // SFTP 3판의 이름 바꾸기는 있는 파일을 덮지 않는다(대부분의 서버) — 3판 서버는 일반 실패(4), 그 뒤 판을 아는 서버는
                // "이미 있음"(11)으로 답한다. 그때만(있는 파일이 막은 것) 옛것을 지우고 다시. 권한 등 다른 까닭이면 지우지 않는다 —
                // 지우고 나서도 못 바꾸면 이 기기 파일이 서버에서 사라졌다(안드로이드 0.59.2).
                let code = Self.statusCode(error)
                let exists = (try? await sftp.getAttributes(at: target)) != nil
                guard code == 4 || code == 11, exists else { throw error }
                try await sftp.remove(at: target)
                // 다시도 안 되면 임시 파일은 남긴다(다른 기기는 점으로 시작하는 파일을 읽지 않고, 다음 쓰기가 덮는다).
                try await sftp.rename(at: temp, to: target)
            }
        }
    }

    func delete(_ name: String) async throws {
        let sftp = self.sftp, file = path(name)
        try await guarded {
            do {
                try await sftp.remove(at: file)
            } catch {
                if !Self.isMissing(error) { throw error }
            }
        }
    }

    func close() async {
        try? await sftp.close()
        try? await client.close()
    }
}
