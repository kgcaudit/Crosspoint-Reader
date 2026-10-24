import Foundation
import OloCore

/// 맞추지 못한 까닭(공통 `SyncProblem`) — 화면이 사람에게 할 일을 알린다. [fingerprint] 는 믿을지 물을 인증서 · 서버 키의 지문.
struct SyncFailure: Error {
    let problem: SyncProblem
    var fingerprint: String?
    var detail = ""
}

/// 서버와의 연결 하나(안드로이드 `FtpSyncStore.Connection`). 저장 폴더 안에서 일한다.
protocol SyncConnection: AnyObject {
    func list() async throws -> [SyncEntry]
    /// 없으면(목록과 읽기 사이에 다른 기기가 바꾸는 중) nil. [maxBytes] 보다 크면 nil(이 앱의 파일이 아니다).
    func read(_ name: String, maxBytes: Int) async throws -> String?
    /// 임시 이름(`.이름.part`)으로 올린 뒤 이름을 바꾼다 — 다른 기기가 반쯤 올라간 글을 읽어 깨진 파일로 버리지 않게.
    func write(_ name: String, _ data: Data) async throws
    /// 이미 없으면 조용히 끝난다.
    func delete(_ name: String) async throws
    func close() async
}

/// 서버 한 곳에 잇기(안드로이드 `FtpSyncStore`). 설정과 비밀번호(키체인)로 FTP · FTPS 는 [FtpConnection], SFTP 는 [SftpConnection].
enum SyncServer {
    /// 잇기 시간 제한. 꺼진 서버라면 책을 열 때마다 이만큼 띠가 늦는다.
    static let timeout: TimeInterval = 15
    /// 잠든 서버를 한 번 더 기다리는 시간. 디스크를 멈춘 NAS 는 깨어나 첫 답을 하기까지 20 ~ 40초 걸린다(안드로이드 0.59.1).
    static let wakeTimeout: TimeInterval = 45

    /// 잇는다. 답이 없으면(잠듦) 한 번만 더 넉넉히 기다린다 — 첫 시도가 NAS 를 깨우고, 깨어나는 동안 두 번째가 기다린다.
    /// [wake] 가 false 면(앱이 뒤로 갔을 때 · 뒤 작업 — iOS 가 주는 시간이 30초쯤) 두 번째 기다림을 건너뛴다. 넘기면 iOS 가 앱을 끝낸다.
    static func open(_ config: FtpConfig, password: String, wake: Bool = true) async throws -> SyncConnection {
        do {
            return try await open(config, password: password, timeout: timeout)
        } catch let f as SyncFailure where f.problem == .asleep && wake {
            return try await open(config, password: password, timeout: wakeTimeout)
        }
    }

    private static func open(_ config: FtpConfig, password: String, timeout: TimeInterval) async throws -> SyncConnection {
        if config.protocol == .sftp { return try await SftpConnection.open(config, password: password, timeout: timeout) }
        return try await FtpConnection.open(config, password: password, timeout: timeout)
    }

    /// 연결 확인: 잇고 · 로그인 · 폴더 만들기 · 시험 파일 쓰고 지우기. 하나라도 안 되면 그 까닭으로 던진다.
    static func check(_ config: FtpConfig, password: String) async throws {
        let c = try await open(config, password: password)
        do {
            let probe = IosReadingSync.companion.PROBE
            try await c.write(probe, Data(probe.utf8))
            try await c.delete(probe)
            await c.close()
        } catch {
            await c.close()
            throw error
        }
    }
}

extension SyncFailure {
    /// 소켓 · TLS 오류를 갈래로(안드로이드 `guard`). 인터넷이 없는 것과 서버가 답하지 않는 것을 나눈다 — 휴대폰의 인터넷은 멀쩡한데
    /// "인터넷 없음" 이라고 하면 사람이 엉뚱한 것을 고친다.
    static func of(_ error: Error) -> SyncFailure {
        if let f = error as? SyncFailure { return f }
        let e = error as NSError
        if e.domain == NSPOSIXErrorDomain {
            switch Int32(e.code) {
            case ECONNREFUSED, EADDRNOTAVAIL: return SyncFailure(problem: .unreachable, detail: "\(e.code)")
            case ETIMEDOUT: return SyncFailure(problem: .asleep, detail: "timed out")
            case ENETUNREACH, EHOSTUNREACH, ENETDOWN, EHOSTDOWN: return SyncFailure(problem: .offline, detail: "\(e.code)")
            default: return SyncFailure(problem: .offline, detail: "posix \(e.code)")
            }
        }
        if e.domain == kCFErrorDomainCFNetwork as String {
            // 이름을 찾지 못함(DNS) — 주소가 틀렸거나 집 밖에서 nas.local 을 찾는다.
            if e.code == Int(CFNetworkErrors.cfHostErrorUnknown.rawValue) || e.code == Int(CFNetworkErrors.cfHostErrorHostNotFound.rawValue) {
                return SyncFailure(problem: .unreachable, detail: "unknown host")
            }
            return SyncFailure(problem: .offline, detail: "cfnetwork \(e.code)")
        }
        // errSSL… (-9800 ~ -9899): 보안 연결을 맺지 못했다.
        if e.domain == NSOSStatusErrorDomain, (-9899 ... -9800).contains(e.code) { return SyncFailure(problem: .tls, detail: "ssl \(e.code)") }
        return SyncFailure(problem: .offline, detail: "\(e.domain) \(e.code)")
    }
}

/// 인증서 판단(안드로이드 `PinningTrustManager`): 공인 기관이 서명했고 이름이 주소와 맞으면 받는다. 아니면 사람이 믿기로 한 지문과 같을
/// 때만. 둘 다 아니면 지문을 들고 거절한다 — 연결 확인이 그 지문을 사람에게 묻는다.
enum TlsTrust {
    static func verify(_ trust: SecTrust, host: String, pinned: String?) throws {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else {
            throw SyncFailure(problem: .tls, detail: "no certificate")
        }
        let fingerprint = SyncHash.shared.fingerprint(data: SecCertificateCopyData(leaf) as Data)
        if let pinned, fingerprint.caseInsensitiveCompare(pinned) == .orderedSame { return }
        // 이름 확인은 제어 연결의 주소로 한다 — 데이터 연결(PASV 로 받은 포트)도 같은 서버 이름으로 본다.
        SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, host as CFString))
        if SecTrustEvaluateWithError(trust, nil) { return }
        throw SyncFailure(problem: .untrusted, fingerprint: fingerprint, detail: "untrusted certificate")
    }
}

/// 한 번만 참이 되는 깃발(여러 스레드에서). 시간 제한과 일의 끝 가운데 먼저 온 쪽만 결과를 낸다.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func take() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/// [seconds] 안에 끝나지 않으면 잠듦(`asleep`)으로 던진다. 기다리던 일은 놓아두되 [onTimeout] 으로 연결을 끊어 스스로 끝나게 한다 —
/// 작업 묶음(TaskGroup)은 취소해도 안 끝나는 일을 끝까지 기다려, 와이파이가 끊긴 SFTP 앞에서 맞추기가 영영 멈췄다.
/// [late] 는 시간이 다 된 뒤에 늦게 성공한 결과(잇기라면 그 연결)를 치운다.
func withDeadline<T>(_ seconds: TimeInterval, onTimeout: @escaping () -> Void = {}, late: @escaping (T) -> Void = { _ in },
                     _ work: @escaping () async throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { (c: CheckedContinuation<T, Error>) in
        let once = Once()
        let job = Task {
            do {
                let value = try await work()
                if once.take() { c.resume(returning: value) } else { late(value) }
            } catch {
                if once.take() { c.resume(throwing: error) }
            }
        }
        Task {
            try? await Task.sleep(for: .seconds(seconds))
            if once.take() {
                job.cancel()
                onTimeout()
                c.resume(throwing: SyncFailure(problem: .asleep, detail: "timed out after \(Int(seconds))s"))
            }
        }
    }
}

/// 막는(blocking) 일을 협력 스레드 밖에서 — FTP 소켓은 기다리며 읽는다. 맞추기 동안 앱의 다른 비동기 일이 멈추지 않게.
func offThread<T>(_ work: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { c in
        DispatchQueue.global(qos: .utility).async {
            do { c.resume(returning: try work()) } catch { c.resume(throwing: error) }
        }
    }
}
