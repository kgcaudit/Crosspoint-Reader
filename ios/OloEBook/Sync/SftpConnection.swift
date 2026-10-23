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
            let fingerprint = Self.fingerprint(hostKey)
            if let pinned, let fingerprint, fingerprint.caseInsensitiveCompare(pinned) == .orderedSame {
                validationCompletePromise.succeed(())
                return
            }
            seen = fingerprint
            validationCompletePromise.fail(UnknownHostKey())
        }

        /// 지문은 키 덩어리(공개 키 줄 "ssh-ed25519 AAAA…" 의 base64 를 푼 것)의 SHA-256 — 안드로이드 JSch 가 넘기는 바이트와 같다.
        static func fingerprint(_ key: NIOSSHPublicKey) -> String? {
            let line = String(openSSHPublicKey: key)
            let parts = line.split(separator: " ")
            guard parts.count >= 2, let blob = Data(base64Encoded: String(parts[1])) else { return nil }
            return SyncHash.shared.fingerprint(data: blob)
        }
    }

    static func open(_ config: FtpConfig, password: String, timeout: TimeInterval) async throws -> SftpConnection {
        let keys = PinnedKey(pinned: config.trusted)
        let host = config.host.trimmingCharacters(in: .whitespaces)
        let client: SSHClient
        do {
            client = try await SSHClient.connect(
                host: host, port: Int(config.port),
                authenticationMethod: .passwordBased(username: config.user, password: password),
                hostKeyValidator: .custom(keys), reconnect: .never,
                connectTimeout: .milliseconds(Int64(timeout * 1000))
            )
        } catch {
            throw failure(error, seen: keys.seen)
        }
        do {
            let sftp = try await client.openSFTP()
            let folder = try await enterFolder(sftp, config.path)
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
        // 모든 주소에 잇지 못함(NIOPosix 의 NIOConnectionError — 그 모듈을 따로 들이지 않으려 글로 본다): 이름을 찾지 못했으면 주소가
        // 틀렸고, 거절이면 서버가 꺼져 있다.
        let text = "\(error)"
        if text.contains("NIOConnectionError") {
            if text.contains("dnsAError: Optional") && text.contains("dnsAAAAError: Optional") { return SyncFailure(problem: .unreachable, detail: "unknown host") }
            if text.contains("connectTimeout") { return SyncFailure(problem: .asleep, detail: "timed out") }
            return SyncFailure(problem: .unreachable, detail: text)
        }
        return SyncFailure(problem: .offline, detail: "ssh failed: \(error)")
    }

    private static func status(_ s: SFTPMessage.Status) -> SyncFailure {
        switch s.errorCode {
        case .permissionDenied, .noSuchFile: return SyncFailure(problem: .noaccess, detail: s.message)
        default: return SyncFailure(problem: .server, detail: s.message)
        }
    }

    private func guarded<T>(_ work: () async throws -> T) async throws -> T {
        do { return try await work() } catch { throw Self.failure(error, seen: nil) }
    }

    private static func isMissing(_ error: Error) -> Bool {
        if let s = error as? SFTPMessage.Status { return s.errorCode == .noSuchFile }
        if let e = error as? SFTPError, case let .errorStatus(s) = e { return s.errorCode == .noSuchFile }
        return false
    }

    // ── SyncConnection ────────────────────────────────────────

    func list() async throws -> [SyncEntry] {
        try await guarded {
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
        do {
            let buffer = try await sftp.withFile(filePath: path(name), flags: .read) { try await $0.readAll() }
            if buffer.readableBytes > maxBytes { return nil }
            return buffer.getString(at: buffer.readerIndex, length: buffer.readableBytes) ?? String(decoding: buffer.readableBytesView, as: UTF8.self)
        } catch {
            if Self.isMissing(error) { return nil }
            throw Self.failure(error, seen: nil)
        }
    }

    func write(_ name: String, _ data: Data) async throws {
        let temp = path(IosReadingSync.companion.partName(name: name))
        try await guarded {
            try await sftp.withFile(filePath: temp, flags: [.write, .create, .truncate]) { try await $0.write(ByteBuffer(bytes: data)) }
        }
        do {
            try await sftp.rename(at: temp, to: path(name))
        } catch {
            // SFTP 3판의 이름 바꾸기는 있는 파일을 덮지 않는다(대부분의 서버). 옛것을 지우고 다시.
            try? await sftp.remove(at: path(name))
            do {
                try await sftp.rename(at: temp, to: path(name))
            } catch {
                try? await sftp.remove(at: temp)
                throw Self.failure(error, seen: nil)
            }
        }
    }

    func delete(_ name: String) async throws {
        do {
            try await sftp.remove(at: path(name))
        } catch {
            if !Self.isMissing(error) { throw Self.failure(error, seen: nil) }
        }
    }

    func close() async {
        try? await sftp.close()
        try? await client.close()
    }
}
