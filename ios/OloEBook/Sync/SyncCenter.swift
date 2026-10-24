import BackgroundTasks
import Foundation
import OloCore
import Security
import UIKit

/// 기기 간 이어 읽기(구상안 50 · 1A 2A 3A, 안드로이드 `ReadingSync` + `SyncScreenUi` 의 흐름). 규칙과 이 기기 상태는 공통 Kotlin
/// `IosReadingSync`, 서버와 주고받기는 `SyncServer`. 여기서는 그 둘을 순서대로 잇고 화면 · 리더가 보는 값을 든다.
///
/// - 기본은 꺼짐. 꺼져 있으면 아무것도 쓰지도 읽지도 않는다(인터넷을 쓰지 않는다).
/// - 맞추지 못해도 던지지 않고 [problem] 에 까닭을 남긴다 — 이 기능 때문에 책이 안 열리면 안 된다.
/// - 맞추기는 한 번에 하나씩([serial]). 같은 파일을 두 맞추기가 번갈아 쓰면 임시 파일이 남는다.
@MainActor
final class SyncCenter: ObservableObject {
    static let shared = SyncCenter()

    @Published private(set) var enabled = false
    @Published private(set) var server: FtpConfig?
    @Published private(set) var lastSynced: Date?
    @Published private(set) var problem: SyncProblem?
    @Published private(set) var busy = false
    @Published private(set) var devices: [SyncDevice] = []
    @Published private(set) var deviceName = DeviceModel.name

    private weak var library: Library?
    private var engine: IosReadingSync?

    /// 상태 폴더. **백업에서 뺀다** — 기기 id 가 새 휴대폰으로 따라가면 두 기기가 한 파일을 번갈아 덮는다.
    private nonisolated static let stateDir: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("sync", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var url = dir
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return dir
    }()

    private var serverFile: URL { Self.stateDir.appendingPathComponent("server.json") }

    /// 못 올린 이 기기 파일(2A). 뒤 작업은 서재 없이 이것만 올린다 — 앱이 꺼진 채 깨어나면 서재를 훑을 수 없다.
    private nonisolated static var pendingFile: URL { stateDir.appendingPathComponent("pending-upload.json") }

    private init() {}

    /// 서재가 생기면 붙는다(앱 시작). 상태를 읽고, 켜져 있으면 다른 기기 것을 받는다.
    func attach(_ library: Library) {
        guard engine == nil else { return }
        self.library = library
        let engine = IosReadingSync(store: library.store, stateDir: Self.stateDir.path) { UUID().uuidString.lowercased() }
        self.engine = engine
        server = loadServer()
        enabled = engine.enabled && server != nil && Keychain.password != nil
        lastSynced = engine.lastSyncedAt.map { Date(timeIntervalSince1970: Double(truncating: $0) / 1000) }
        deviceName = engine.customName ?? DeviceModel.name
        #if DEBUG
        // 화면 확인용: -olo-sync-server <주소> <사용자> <비밀번호> 는 그 서버를 확인하고(처음 보는 인증서 · 키는 믿고) 켠다. 출시 빌드에는 없다.
        let args = CommandLine.arguments
        // -olo-sync-off 는 끈다(꺼진 화면 확인).
        if args.contains("-olo-sync-off") { turnOff() }
        if let i = args.firstIndex(of: "-olo-sync-server"), i + 3 < args.count,
           let config = FtpAddress.shared.configOf(address: args[i + 1], user: args[i + 2], path: FtpConfig.companion.DEFAULT_PATH, security: .auto_,
                                                   passive: true, encoding: .auto_, previous: nil) {
            let password = args[i + 3]
            Task {
                var r = await check(config, password: password)
                if r.problem == .untrusted, let fp = r.fingerprint { r = await check(r.tried.withTrusted(trusted: fp), password: password) }
                if r.problem == nil { connected(r.tried, password: password) }
            }
        }
        #endif
    }

    func setDeviceName(_ name: String) {
        engine?.customName = name
        deviceName = engine?.customName ?? DeviceModel.name
        // 이름이 바뀌었으니 다시 올린다(다른 기기의 "함께 읽는 기기" · 띠 이름).
        recordsChanged()
    }

    // ── 서버 설정 ─────────────────────────────────────────────

    private struct SavedServer: Codable {
        var host: String
        var port: Int
        var protocolName: String
        var security: String
        var user: String
        var path: String
        var passive: Bool
        var encoding: String
        var trusted: String?
    }

    private func loadServer() -> FtpConfig? {
        guard let data = try? Data(contentsOf: serverFile), let s = try? JSONDecoder().decode(SavedServer.self, from: data), !s.host.isEmpty else { return nil }
        // 모르는 이름(다음 판이 더한 값)이면 처음 값.
        return FtpConfig(
            host: s.host, port: Int32(s.port),
            protocol: FtpProtocol.entries.first { $0.name == s.protocolName } ?? .ftp,
            security: FtpSecurity.entries.first { $0.name == s.security } ?? FtpSecurity.none,
            user: s.user, path: s.path, passive: s.passive,
            encoding: FtpEncoding.entries.first { $0.name == s.encoding } ?? .auto_,
            trusted: s.trusted
        )
    }

    /// 저장한다. 비밀번호를 키체인에 두지 못하면 false — 켠 것처럼 보이는데 맞추기가 말없이 멈추지 않게, 부르는 쪽이 알린다.
    private func saveServer(_ c: FtpConfig, password: String) -> Bool {
        guard Keychain.set(password) else { return false }
        let s = SavedServer(host: c.host, port: Int(c.port), protocolName: c.protocol.name, security: c.security.name, user: c.user, path: c.path,
                            passive: c.passive, encoding: c.encoding.name, trusted: c.trusted)
        if let data = try? JSONEncoder().encode(s) { try? data.write(to: serverFile, options: .atomic) }
        server = c
        return true
    }

    /// 지금 설정의 비밀번호(설정 칸에 다시 채울 때).
    var savedPassword: String { Keychain.password ?? "" }

    /// 연결 확인의 결과: 되면 problem 이 nil 이고 [tried] 가 실제로 된 설정(자동이면 내려간 쪽).
    struct CheckResult {
        let problem: SyncProblem?
        let fingerprint: String?
        let tried: FtpConfig
    }

    /// "연결 확인"(안드로이드 `SyncScreenUi.check`). 보안 연결 "자동" 은 명시적 FTPS 로 먼저 해 보고, 서버가 암호화를 받지 않으면 일반
    /// FTP 로 다시 한다. 저장된 설정은 건드리지 않는다 — 확인 중인(틀렸을 수도 있는) 서버로 맞추기가 기록을 올리지 않게.
    func check(_ config: FtpConfig, password: String) async -> CheckResult {
        var tried = config.security == .auto_ ? config.withSecurity(security: .explicit_) : config
        var result = await probe(tried, password: password)
        if result?.problem == .notls && config.security == .auto_ {
            tried = tried.withSecurity(security: FtpSecurity.none)
            result = await probe(tried, password: password)
        }
        return CheckResult(problem: result?.problem, fingerprint: result?.fingerprint, tried: tried)
    }

    private func probe(_ config: FtpConfig, password: String) async -> SyncFailure? {
        do {
            try await SyncServer.check(config, password: password)
            return nil
        } catch {
            return SyncFailure.of(error)
        }
    }

    /// 확인된 서버를 저장하고 켠다. 다시 켜는 것으로 "이미 올린 내용" 기억이 지워져 새 서버에 이 기기 기록을 처음부터 올린다.
    /// 비밀번호를 저장하지 못했으면 false(켜지 않는다).
    @discardableResult
    func connected(_ config: FtpConfig, password: String) -> Bool {
        guard saveServer(config, password: password) else { return false }
        // 옛 서버로 못 올린 파일이 새 서버로 가지 않게.
        try? FileManager.default.removeItem(at: Self.pendingFile)
        turnOn()
        return true
    }

    /// 이미 확인한 서버가 있으면 묻지 않고 켠다(껐다 다시 켤 때).
    func turnOn() {
        guard server != nil, Keychain.password != nil else { return }
        engine?.enabled = true
        enabled = true
        problem = nil
        Task { await syncNow() }
    }

    func turnOff() {
        debounce?.cancel()
        engine?.enabled = false
        enabled = false
        problem = nil
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskId)
        try? FileManager.default.removeItem(at: Self.pendingFile)
    }

    // ── 맞추기 ───────────────────────────────────────────────

    private var chain: Task<Void, Never>?

    /// 한 번에 하나씩. 앞의 맞추기가 끝난 뒤에 돈다.
    private func serial<T>(_ work: @escaping @MainActor () async -> T) async -> T {
        let previous = chain
        let task = Task { @MainActor () -> T in
            await previous?.value
            return await work()
        }
        chain = Task { _ = await task.value }
        return await task.value
    }

    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// 서버 일 한 덩이: 잇고 · [work] · 끊는다. 맞추지 못한 까닭은 [problem] 에, 닿지 못했으면 1분은 책을 열어도 다시 묻지 않는다.
    /// [wake] 가 false 면 잠든 서버를 한 번 더 기다리지 않는다(뒤로 간 앱 — 시간이 30초쯤뿐이다).
    private func session(wake: Bool = true, _ work: (SyncConnection, IosReadingSync) async throws -> Void) async -> Bool {
        guard enabled, let engine, let server, let password = Keychain.password else { return false }
        busy = true
        defer { busy = false }
        do {
            let c = try await SyncServer.open(server, password: password, wake: wake)
            do {
                try await work(c, engine)
                await c.close()
            } catch {
                await c.close()
                throw error
            }
            engine.markSynced(now: now)
            lastSynced = Date()
            problem = nil
            return true
        } catch {
            let f = SyncFailure.of(error)
            problem = f.problem
            if f.problem.unreached { engine.unreachable(now: now) }
            return false
        }
    }

    /// 목록을 받아 바뀐 파일만 읽고, 책갈피 · 다 읽은 때를 합친다(읽은 자리는 아니다 — 띠가 묻는다).
    private func pull(_ c: SyncConnection, _ engine: IosReadingSync) async throws {
        let toRead = engine.listed(entries: try await c.list(), now: now)
        for e in toRead {
            // 너무 큰 파일은 이 앱의 것이 아니다 — 읽지 않고 "읽었음" 으로 적는다.
            let tooBig = (e.size?.int64Value ?? 0) > SyncCodec.shared.MAX_BYTES
            engine.received(entry: e, text: tooBig ? nil : try await c.read(e.name, maxBytes: Int(SyncCodec.shared.MAX_BYTES)))
        }
        if let library, engine.mergeFresh(shelf: library.shelfBooks, comics: library.shelfComics) > 0 { library.reloadRecords() }
        devices = engine.devices()
    }

    /// 이 기기 기록을 올린다(바뀐 것이 없으면 아무것도 보내지 않는다).
    private func push(_ c: SyncConnection, _ engine: IosReadingSync, text: String) async throws {
        try await c.write(SyncCodec.shared.fileName(deviceId: engine.deviceId), Data(text.utf8))
        engine.uploaded()
        try? FileManager.default.removeItem(at: Self.pendingFile)
    }

    /// 올릴 글. 서재를 처음 다 훑기 전에는 없다 — 그때의 빈 서재로 이 기기 기록을 덮으면 다른 기기에서 이 기기의 자리가 사라진다.
    private func ownText(_ library: Library, _ engine: IosReadingSync) -> String? {
        guard library.scannedOnce else { return nil }
        return engine.upload(shelf: library.shelfBooks, comics: library.shelfComics, deviceName: deviceName, now: now)
    }

    /// 못 올린 글을 남긴다(뒤 작업이 서재 없이 다시 올린다). 꺼진 뒤에는 남기지 않는다 — 나중에 다른 서버로 켜면 옛 글이 그리로 갔다.
    private func savePending(_ text: String) {
        guard enabled else { return }
        try? text.write(to: Self.pendingFile, atomically: true, encoding: .utf8)
    }

    /// 이 기기 기록을 올리고 다른 기기 것을 받는다 — 켤 때 · 화면을 열 때 · "지금 맞추기" · 앱을 열 때.
    func syncNow() async {
        await serial { [self] in
            guard let library, let engine else { return }
            let ok = await session { c, engine in
                try await pull(c, engine)
                if let text = ownText(library, engine) { try await push(c, engine, text: text) }
            }
            afterSync(ok, engine: engine)
        }
    }

    /// 책을 닫을 때 · 앱이 뒤로 갈 때 · 다른 기기 것을 합친 뒤: 이 기기 기록만 올린다. 바뀐 것이 없으면 서버에 잇지도 않는다.
    /// [background] 면 잠든 서버를 한 번 더 기다리지 않고, 잇기 **전에** 올릴 글을 남겨 둔다 — 그 사이 iOS 가 앱을 멈춰도 뒤 작업이 올린다.
    func writeOwn(background: Bool = false) async {
        await serial { [self] in
            guard enabled, let library, let engine else { return }
            // 서버에 이 기기 파일이 있는지 모르면 받기부터 — 빈 파일을 만들지 않으려고.
            if engine.needsListing {
                if background, let text = ownText(library, engine) { savePending(text) }
                let ok = await session(wake: !background) { c, engine in
                    try await pull(c, engine)
                    if let text = ownText(library, engine) { try await push(c, engine, text: text) }
                }
                afterSync(ok, engine: engine)
                return
            }
            guard let text = ownText(library, engine) else { return }
            savePending(text)
            let ok = await session(wake: !background) { c, engine in try await push(c, engine, text: text) }
            afterSync(ok, engine: engine)
        }
    }

    private var debounce: Task<Void, Never>?

    /// 기록이 바뀌었다(책을 닫음 · 갈래를 옮김 · 가져오기). 2초 기다렸다 한 번 올린다 — 잇달아 바뀌어도 서버에는 한 번만 잇는다.
    func recordsChanged() {
        guard enabled else { return }
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            if Task.isCancelled { return }
            await self?.writeOwn()
        }
    }

    /// 앱이 뒤로 갔다: 마지막 자리를 올린다. iOS 가 빌려주는 시간(30초쯤)이 끝나면 맞추기를 놓고 시간을 돌려준다 — 돌려주지 않으면 iOS 가
    /// 앱을 강제로 끝낸다. 못 올린 글은 이미 남겨 두었다(뒤 작업이 다시 올린다).
    func flushOnBackground() {
        guard enabled else { return }
        debounce?.cancel()
        let done = Once()
        let task = BackgroundTaskId()
        let work = Task { await writeOwn(background: true) }
        task.id = UIApplication.shared.beginBackgroundTask(withName: "olo-sync") {
            work.cancel()
            if done.take() { UIApplication.shared.endBackgroundTask(task.id) }
        }
        Task {
            await work.value
            if done.take() { UIApplication.shared.endBackgroundTask(task.id) }
        }
    }

    /// 맞추기 뒤: 시간이 지나면 풀릴 까닭이면 다시 하기를 건다(2A), 되면 거둔다.
    private func afterSync(_ ok: Bool, engine: IosReadingSync) {
        if ok {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskId)
        } else if let problem, problem.retryable, FileManager.default.fileExists(atPath: Self.pendingFile.path) {
            scheduleRetry()
        }
        // 사람이 고쳐야 하는 까닭(비밀번호 · 권한 · 인증서)이면 다시 하기는 걸지 않는다 — 화면이 까닭을 보인다.
    }

    // ── 묻기(결정 8-2) ────────────────────────────────────────

    /// 책을 열 때 목록을 다시 받을지 — 방금 받았거나(20초) 닿지 못한 지 1분이 안 됐으면 받아 둔 것을 쓴다.
    private func refreshForOffer() async {
        guard let engine, engine.shouldRefreshForOffer(now: now) else { return }
        await serial { [self] in _ = await session { c, engine in try await pull(c, engine) } }
    }

    /// 이 책을 다른 기기가 더 읽었으면 그 자리. 꺼져 있거나 없으면 nil. 서버에 닿지 못해도 받아 둔 것으로 본다.
    func offerForBook(id: String, name: String, size: Int64?) async -> SyncBookOffer? {
        guard enabled else { return nil }
        await refreshForOffer()
        return engine?.offerForBook(bookId: id, name: name, size: size.map { KotlinLong(value: $0) })
    }

    func offerForComic(id: String, name: String, size: Int64?) async -> SyncComicOffer? {
        guard enabled else { return nil }
        await refreshForOffer()
        return engine?.offerForComic(unitId: id, name: name, size: size.map { KotlinLong(value: $0) })
    }

    // ── 지우기 ───────────────────────────────────────────────

    /// "함께 읽는 기기" 에서 하나를 지운다(오래 쓰지 않는 기기). 그 기기가 다시 켜지면 제 파일을 새로 쓴다.
    func forget(_ device: SyncDevice) async -> Bool {
        await serial { [self] in
            guard let entry = engine?.entryOf(deviceId: device.id) else { return false }
            let ok = await session { c, engine in
                try await c.delete(entry.name)
                engine.forgot(deviceId: device.id)
            }
            devices = engine?.devices() ?? []
            return ok
        }
    }

    /// 서버에 저장한 기록을 모두 지우고 이 기기에서 끈다. 다른 기기는 켜져 있으면 다음에 제 파일을 다시 쓴다.
    func clearRemote() async -> Bool {
        await serial { [self] in
            let ok = await session { c, engine in
                for e in engine.deviceFiles(entries: try await c.list()) { try await c.delete(e.name) }
                engine.cleared()
            }
            if ok {
                devices = []
                turnOff()
            }
            return ok
        }
    }

    // ── 다시 하기(2A) ─────────────────────────────────────────

    nonisolated static let taskId = "io.github.kgcaudit.oloebook.sync"

    /// 앱 시작에 한 번(시스템 규칙: 앱이 다 뜨기 전에). 깨어나면 못 올린 파일만 올린다. 시간이 다 되면 그 자리에서 끝을 알리고 다음을
    /// 건다 — 늦게 알리면 iOS 가 앱을 끝내고, 다음부터 덜 깨워 준다. 끝은 한 번만 알린다.
    nonisolated static func registerBackground() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            let finished = Once()
            let work = Task { @MainActor in
                let done = await SyncCenter.shared.retryPending()
                guard finished.take() else { return }
                if !done { SyncCenter.shared.scheduleRetry() }
                task.setTaskCompleted(success: done)
            }
            task.expirationHandler = {
                work.cancel()
                guard finished.take() else { return }
                Task { @MainActor in SyncCenter.shared.scheduleRetry() }
                task.setTaskCompleted(success: false)
            }
        }
    }

    /// 15분 뒤 이후 iOS 가 정한 때(안드로이드의 15분 · 두 배씩은 iOS 가 허락하지 않는다 — 깨우는 때는 시스템이 정한다).
    func scheduleRetry() {
        let request = BGAppRefreshTaskRequest(identifier: Self.taskId)
        request.earliestBeginDate = Date().addingTimeInterval(15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// 못 올린 이 기기 파일을 올린다. 서재 없이 한다(앱이 꺼진 채 깨어났을 수 있다). 올렸거나 올릴 것이 없으면 true.
    /// 맞추기 줄 안에서 차례가 왔을 때 파일을 읽는다 — 그 사이 앱이 더 새 기록을 올리고 파일을 지웠으면 옛 글로 되돌리지 않게.
    func retryPending() async -> Bool {
        await serial { [self] in
            guard let text = try? String(contentsOf: Self.pendingFile, encoding: .utf8) else { return true }
            // 기기 id · 켬만 상태 폴더에서 읽는다. 기록 저장소는 쓰지 않으므로 빈 임시 저장소를 준다 — 서재가 쓰는 저장소를 둘로 열면
            // 한쪽이 다른 쪽의 기록을 덮는다. 다 쓰면 지운다.
            let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("sync-retry-\(UUID().uuidString).tsv")
            defer { try? FileManager.default.removeItem(at: scratch) }
            let state = IosReadingSync(store: ReadingStore(path: scratch.path), stateDir: Self.stateDir.path) { UUID().uuidString.lowercased() }
            guard state.enabled, let server = server ?? loadServer(), let password = Keychain.password else { return true }
            do {
                let c = try await SyncServer.open(server, password: password, wake: false)
                do {
                    try await c.write(SyncCodec.shared.fileName(deviceId: state.deviceId), Data(text.utf8))
                    await c.close()
                } catch {
                    await c.close()
                    throw error
                }
                try? FileManager.default.removeItem(at: Self.pendingFile)
                lastSynced = Date()
                problem = nil
                return true
            } catch {
                let f = SyncFailure.of(error)
                problem = f.problem
                // 사람이 고쳐야 하는 까닭은 다시 해도 같다 — 멈추고 화면이 까닭을 보인다.
                return !f.problem.retryable
            }
        }
    }
}

/// 빌린 뒤 작업 시간의 번호(끝 처리기가 받기 전에 정해진다 — 상자에 담아 넘긴다).
final class BackgroundTaskId: @unchecked Sendable {
    var id = UIBackgroundTaskIdentifier.invalid
}

/// 비밀번호 상자(안드로이드 `KeystoreBox` 의 짝). 이 기기에서만 열리고(ThisDeviceOnly — 새 휴대폰으로 백업 · 복원되지 않는다), 처음 잠금을
/// 푼 뒤에는 뒤 작업도 읽는다.
enum Keychain {
    private static let service = "io.github.kgcaudit.oloebook.sync"
    private static let account = "ftp-password"

    /// 저장한다. 실패하면 false(기기 잠금 상태 · 키체인 오류).
    static func set(_ password: String) -> Bool {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(password.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static var password: String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

/// 이 기기 이름의 처음 값(3A). iOS 16 부터 앱은 사람이 지은 기기 이름("민수의 iPad")을 읽을 수 없어(시스템 제약) 모델 이름을 쓴다 —
/// "iPhone" 만으로는 같은 종류 둘이 구별되지 않는다. 표에 없는 새 모델은 "iPhone" · "iPad". 사람이 "이 기기 이름" 에서 바꿀 수 있다.
enum DeviceModel {
    static var name: String {
        let id = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? machine
        return names[id] ?? (UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone")
    }

    private static var machine: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
    }

    private static let names: [String: String] = [
        "iPhone13,1": "iPhone 12 mini", "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro", "iPhone13,4": "iPhone 12 Pro Max",
        "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max",
        "iPhone14,6": "iPhone SE", "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus", "iPhone15,2": "iPhone 14 Pro",
        "iPhone15,3": "iPhone 14 Pro Max", "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus", "iPhone16,1": "iPhone 15 Pro",
        "iPhone16,2": "iPhone 15 Pro Max", "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus", "iPhone17,1": "iPhone 16 Pro",
        "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,5": "iPhone 16e",
        "iPad13,4": "iPad Pro 11형", "iPad13,5": "iPad Pro 11형", "iPad13,6": "iPad Pro 11형", "iPad13,7": "iPad Pro 11형",
        "iPad13,8": "iPad Pro 12.9형", "iPad13,9": "iPad Pro 12.9형", "iPad13,10": "iPad Pro 12.9형", "iPad13,11": "iPad Pro 12.9형",
        "iPad14,3": "iPad Pro 11형", "iPad14,4": "iPad Pro 11형", "iPad14,5": "iPad Pro 12.9형", "iPad14,6": "iPad Pro 12.9형",
        "iPad16,3": "iPad Pro 11형", "iPad16,4": "iPad Pro 11형", "iPad16,5": "iPad Pro 13형", "iPad16,6": "iPad Pro 13형",
        "iPad13,1": "iPad Air", "iPad13,2": "iPad Air", "iPad13,16": "iPad Air", "iPad13,17": "iPad Air",
        "iPad14,8": "iPad Air 11형", "iPad14,9": "iPad Air 11형", "iPad14,10": "iPad Air 13형", "iPad14,11": "iPad Air 13형",
        "iPad14,1": "iPad mini", "iPad14,2": "iPad mini", "iPad16,1": "iPad mini", "iPad16,2": "iPad mini",
        "iPad12,1": "iPad", "iPad12,2": "iPad", "iPad13,18": "iPad", "iPad13,19": "iPad",
    ]
}
