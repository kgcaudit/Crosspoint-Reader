import Darwin
import Foundation
import OloCore

/// CFStream 속성 이름을 스트림 속성 이름으로(SDK 마다 CFString · CFStreamPropertyKey 로 갈린다).
private func streamKey(_ key: CFStreamPropertyKey) -> Stream.PropertyKey { Stream.PropertyKey(key.rawValue as String) }
private func streamKey(_ key: CFString) -> Stream.PropertyKey { Stream.PropertyKey(key as String) }

/// 소켓 하나(제어 · 데이터). Foundation 스트림을 쓰는 까닭: 명시적 FTPS 는 평문으로 이은 뒤 **같은 연결**을 TLS 로 바꿔야 하는데,
/// Network.framework 는 열린 연결에 TLS 를 끼울 수 없다. 스트림은 열린 뒤에도 TLS 설정을 넣으면 그 자리에서 바꾼다.
///
/// 기다리며 읽고 쓴다(`offThread` 에서만 부른다). 시간 제한은 직접 잰다 — 스트림에는 시간 제한이 없어, 잠든 서버 앞에서 끝없이 멈춘다.
private final class Socket {
    let input: Foundation.InputStream
    let output: Foundation.OutputStream
    private let timeout: TimeInterval
    private var buffer = Data()

    init(host: String, port: Int, timeout: TimeInterval) {
        var i: Foundation.InputStream?
        var o: Foundation.OutputStream?
        Stream.getStreamsToHost(withName: host, port: port, inputStream: &i, outputStream: &o)
        input = i!
        output = o!
        self.timeout = timeout
    }

    /// 이미 이은 소켓(능동 모드에서 서버가 이어 온 것).
    init(native fd: Int32, timeout: TimeInterval) {
        var r: Unmanaged<CFReadStream>?
        var w: Unmanaged<CFWriteStream>?
        CFStreamCreatePairWithSocket(nil, fd, &r, &w)
        input = r!.takeRetainedValue() as Foundation.InputStream
        output = w!.takeRetainedValue() as Foundation.OutputStream
        input.setProperty(kCFBooleanTrue, forKey: streamKey(kCFStreamPropertyShouldCloseNativeSocket))
        self.timeout = timeout
    }

    /// TLS 로 바꾼다(열기 전이면 처음부터 TLS — 암묵적 FTPS · 데이터 연결). 인증서는 스스로 판단하므로 체인 확인을 끈다 — 끄지 않으면
    /// NAS 의 스스로 만든 인증서에서 지문을 보이기도 전에 끊긴다. 대신 [verify] 를 첫 주고받기 뒤에 꼭 부른다.
    func startTLS(host: String) {
        let settings: [String: Any] = [
            kCFStreamSSLValidatesCertificateChain as String: false,
            kCFStreamSSLPeerName as String: host,
        ]
        for s in [input as Stream, output as Stream] {
            s.setProperty(StreamSocketSecurityLevel.negotiatedSSL, forKey: .socketSecurityLevelKey)
            s.setProperty(settings, forKey: streamKey(kCFStreamPropertySSLSettings))
        }
    }

    func verify(host: String, pinned: String?) throws {
        guard let trust = input.property(forKey: streamKey(kCFStreamPropertySSLPeerTrust)) else {
            throw SyncFailure(problem: .tls, detail: "no peer trust")
        }
        try TlsTrust.verify(trust as! SecTrust, host: host, pinned: pinned)
    }

    func open() throws {
        input.open()
        output.open()
        let deadline = Date().addingTimeInterval(timeout)
        while input.streamStatus == .opening || output.streamStatus == .opening {
            if Date() > deadline { close(); throw SyncFailure(problem: .asleep, detail: "connect timed out") }
            usleep(5_000)
        }
        try check()
    }

    private func check() throws {
        if let e = input.streamError ?? output.streamError { throw SyncFailure.of(e) }
        if input.streamStatus == .error || output.streamStatus == .error { throw SyncFailure(problem: .offline, detail: "stream error") }
    }

    func write(_ data: Data) throws {
        var sent = 0
        let deadline = Date().addingTimeInterval(timeout)
        while sent < data.count {
            try check()
            if output.hasSpaceAvailable {
                let n = data.withUnsafeBytes { p in output.write(p.bindMemory(to: UInt8.self).baseAddress! + sent, maxLength: data.count - sent) }
                if n < 0 { try check(); throw SyncFailure(problem: .offline, detail: "write failed") }
                sent += n
                continue
            }
            if Date() > deadline { throw SyncFailure(problem: .asleep, detail: "write timed out") }
            usleep(2_000)
        }
    }

    /// 들어온 만큼 읽는다. 끝(상대가 닫음)이면 빈 Data.
    private func readSome() throws -> Data {
        let deadline = Date().addingTimeInterval(timeout)
        var chunk = [UInt8](repeating: 0, count: 16 * 1024)
        while true {
            try check()
            if input.streamStatus == .atEnd || input.streamStatus == .closed { return Data() }
            if input.hasBytesAvailable {
                let n = input.read(&chunk, maxLength: chunk.count)
                if n < 0 { try check(); throw SyncFailure(problem: .offline, detail: "read failed") }
                if n == 0 { return Data() }
                return Data(chunk[0..<n])
            }
            if Date() > deadline { throw SyncFailure(problem: .asleep, detail: "read timed out") }
            usleep(2_000)
        }
    }

    /// 한 줄(CRLF 앞까지). 끝이면 nil.
    func readLine(encoding: String.Encoding) throws -> String? {
        while true {
            if let r = buffer.firstRange(of: Data([0x0D, 0x0A])) ?? buffer.firstRange(of: Data([0x0A])) {
                let line = buffer[buffer.startIndex..<r.lowerBound]
                buffer.removeSubrange(buffer.startIndex..<r.upperBound)
                return String(data: line, encoding: encoding) ?? String(decoding: line, as: UTF8.self)
            }
            let more = try readSome()
            if more.isEmpty { return buffer.isEmpty ? nil : String(decoding: buffer, as: UTF8.self) }
            buffer.append(more)
        }
    }

    /// 끝까지 읽는다(데이터 연결). [max] 를 넘으면 그만 읽는다.
    func readAll(max: Int) throws -> Data {
        var all = buffer
        buffer = Data()
        while all.count <= max {
            let more = try readSome()
            if more.isEmpty { break }
            all.append(more)
        }
        return all
    }

    func close() {
        input.close()
        output.close()
    }
}

/// FTP · FTPS 연결(안드로이드 `FtpConnection` — commons-net 이 하던 일). 제어 연결 하나로 명령을 주고받고, 목록 · 읽기 · 쓰기마다
/// 데이터 연결을 하나씩 연다.
final class FtpConnection: SyncConnection {
    private let config: FtpConfig
    private let control: Socket
    private let timeout: TimeInterval
    private let encoding: String.Encoding
    private var mlsd = false
    /// 데이터 연결도 암호화하는가(PROT P). 안 하면 이름 · 비밀번호만 감추고 읽은 자리는 그대로 보낸다.
    private var protectData = false

    private init(_ config: FtpConfig, control: Socket, timeout: TimeInterval) {
        self.config = config
        self.control = control
        self.timeout = timeout
        encoding = config.encoding == .euckr
            ? String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.EUC_KR.rawValue)))
            : .utf8
    }

    private var host: String { config.host.trimmingCharacters(in: .whitespaces) }

    static func open(_ config: FtpConfig, password: String, timeout: TimeInterval) async throws -> FtpConnection {
        try await offThread { try openBlocking(config, password: password, timeout: timeout) }
    }

    private static func openBlocking(_ config: FtpConfig, password: String, timeout: TimeInterval) throws -> FtpConnection {
        let host = config.host.trimmingCharacters(in: .whitespaces)
        let socket = Socket(host: host, port: Int(config.port), timeout: timeout)
        let c = FtpConnection(config, control: socket, timeout: timeout)
        do {
            if config.security == .implicit { socket.startTLS(host: host) }
            try socket.open()
            try c.expect(c.reply(), 2, what: "connect")
            if config.security == .implicit {
                try socket.verify(host: host, pinned: config.trusted)
            } else if config.security != FtpSecurity.none {
                // 명시적 FTPS: AUTH TLS 를 거절(4xx · 5xx — 431 "서비스 없음" 을 쓰는 서버도 있다)하면 서버가 암호화를 받지 않는 것 —
                // 인증서 문제와 다르게 알린다(자동이면 화면이 일반 FTP 로 다시 해 본다).
                let auth = try c.command("AUTH TLS")
                if auth.code != 234 { throw SyncFailure(problem: .notls, detail: "AUTH TLS refused: \(auth.text)") }
                socket.startTLS(host: host)
            }
            // FEAT 는 로그인 전에도 받는다. 명시적 FTPS 에서는 이 주고받기가 TLS 를 맺게 하고, 그 뒤 인증서를 본 다음에야
            // 비밀번호를 보낸다 — 믿지 않는 서버에 비밀번호가 가지 않게.
            let feat = try c.command("FEAT")
            if config.security == .explicit_ || config.security == .auto_ { try socket.verify(host: host, pinned: config.trusted) }
            c.mlsd = feat.code == 211 && FtpReplies.shared.hasMlsd(feat: feat.text)
            var login = try c.command("USER \(config.user)")
            if login.code == 331 { login = try c.command("PASS \(password)") }
            if login.code != 230 && login.code != 202 {
                throw SyncFailure(problem: login.code == 530 || login.code / 100 == 5 ? .auth : FtpReplies.shared.problemOf(code: Int32(login.code), fallback: .server),
                                  detail: "login refused: \(login.text)")
            }
            if config.security != FtpSecurity.none {
                _ = try c.command("PBSZ 0")
                try c.expect(c.command("PROT P"), 2, what: "PROT P")
                c.protectData = true
            }
            if config.encoding == .auto_ { _ = try? c.command("OPTS UTF8 ON") }
            try c.expect(c.command("TYPE I"), 2, what: "TYPE I")
            try c.enterFolder(config.path)
            return c
        } catch {
            socket.close()
            throw SyncFailure.of(error)
        }
    }

    // ── 제어 연결 ─────────────────────────────────────────────

    private struct Reply {
        let code: Int
        let text: String
    }

    /// 답 하나(여러 줄 답 "230-…" … "230 끝" 은 하나로).
    private func reply() throws -> Reply {
        guard let first = try control.readLine(encoding: encoding) else {
            throw SyncFailure(problem: .offline, detail: "server closed the connection")
        }
        let code = Int(FtpReplies.shared.code(line: first))
        guard code > 0 else { throw SyncFailure(problem: .server, detail: "bad reply: \(first)") }
        var lines = [first]
        if first.count > 3, first[first.index(first.startIndex, offsetBy: 3)] == "-" {
            while let line = try control.readLine(encoding: encoding) {
                lines.append(line)
                if FtpReplies.shared.isLast(line: line, code: Int32(code)) { break }
            }
        }
        return Reply(code: code, text: lines.joined(separator: "\n"))
    }

    @discardableResult
    private func command(_ line: String) throws -> Reply {
        guard let data = (line + "\r\n").data(using: encoding) else { throw SyncFailure(problem: .server, detail: "cannot encode \(line)") }
        try control.write(data)
        return try reply()
    }

    /// 답이 [klass] 갈래(2xx · 3xx)가 아니면 그 번호에 맞는 까닭으로 던진다.
    @discardableResult
    private func expect(_ r: Reply, _ klass: Int, what: String, fallback: SyncProblem = .server) throws -> Reply {
        if r.code / 100 == klass { return r }
        throw SyncFailure(problem: FtpReplies.shared.problemOf(code: Int32(r.code), fallback: fallback), detail: "\(what) failed: \(r.text)")
    }

    /// [path] 로 들어간다. 없는 폴더는 한 칸씩 만든다.
    private func enterFolder(_ path: String) throws {
        if path.trimmingCharacters(in: .whitespaces).hasPrefix("/") {
            try expect(command("CWD /"), 2, what: "cwd /", fallback: .noaccess)
        }
        for part in FtpReplies.shared.folderParts(path: path) {
            if try command("CWD \(part)").code / 100 == 2 { continue }
            try expect(command("MKD \(part)"), 2, what: "mkdir \(part)", fallback: .noaccess)
            try expect(command("CWD \(part)"), 2, what: "cwd \(part)", fallback: .noaccess)
        }
    }

    // ── 데이터 연결 ───────────────────────────────────────────

    /// 데이터 연결을 열고 [line] 명령을 보낸 뒤 [work] 를 한다. 끝나면 서버의 끝 답(226)을 기다린다.
    private func transfer<T>(_ line: String, _ work: (Socket) throws -> T) throws -> T {
        if !config.passive { return try activeTransfer(line, work) }
        // 주소는 버리고 포트만 쓴다 — NAT 뒤 서버는 안쪽 주소를 알리는 일이 흔하다. IPv6 서버는 EPSV 만 안다.
        var port: Int?
        if host.contains(":") {
            let r = try command("EPSV")
            port = r.code == 229 ? FtpReplies.shared.epsvPort(reply: r.text).map { Int(truncating: $0) } : nil
        } else {
            let r = try command("PASV")
            port = r.code == 227 ? FtpReplies.shared.pasvPort(reply: r.text).map { Int(truncating: $0) } : nil
            if port == nil {
                let e = try command("EPSV")
                port = e.code == 229 ? FtpReplies.shared.epsvPort(reply: e.text).map { Int(truncating: $0) } : nil
            }
        }
        guard let port else { throw SyncFailure(problem: .server, detail: "passive mode refused") }
        let data = Socket(host: host, port: port, timeout: timeout)
        if protectData { data.startTLS(host: host) }
        try data.open()
        defer { data.close() }
        let start = try command(line)
        if start.code / 100 != 1 {
            throw SyncFailure(problem: FtpReplies.shared.problemOf(code: Int32(start.code), fallback: .server), detail: "\(line): \(start.text)")
        }
        let result = try work(data)
        data.close()
        try expect(reply(), 2, what: line)
        return result
    }

    /// 능동 모드: 이 기기가 듣고 서버가 이어 온다(PORT). 휴대폰은 늘 NAT 뒤라 거의 열리지 않지만, 집 안 공유기에서 수동 모드를 막은 서버가 있다.
    private func activeTransfer<T>(_ line: String, _ work: (Socket) throws -> T) throws -> T {
        let listener = socket(AF_INET, SOCK_STREAM, 0)
        guard listener >= 0 else { throw SyncFailure(problem: .offline, detail: "socket") }
        defer { Darwin.close(listener) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = INADDR_ANY
        addr.sin_port = 0
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(listener, $0, len) } }
        guard bound == 0, listen(listener, 1) == 0 else { throw SyncFailure(problem: .offline, detail: "listen") }
        _ = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(listener, $0, &len) } }
        let port = Int(UInt16(bigEndian: addr.sin_port))
        guard let local = localIPv4() else { throw SyncFailure(problem: .offline, detail: "no local address") }
        let parts = local.split(separator: ".").joined(separator: ",")
        try expect(command("PORT \(parts),\(port / 256),\(port % 256)"), 2, what: "PORT")
        let start = try command(line)
        if start.code / 100 != 1 {
            throw SyncFailure(problem: FtpReplies.shared.problemOf(code: Int32(start.code), fallback: .server), detail: "\(line): \(start.text)")
        }
        var fds = pollfd(fd: listener, events: Int16(POLLIN), revents: 0)
        guard poll(&fds, 1, Int32(timeout * 1000)) > 0 else { throw SyncFailure(problem: .asleep, detail: "server did not connect back") }
        let fd = accept(listener, nil, nil)
        guard fd >= 0 else { throw SyncFailure(problem: .offline, detail: "accept") }
        let data = Socket(native: fd, timeout: timeout)
        if protectData { data.startTLS(host: host) }
        try data.open()
        defer { data.close() }
        let result = try work(data)
        data.close()
        try expect(reply(), 2, what: line)
        return result
    }

    /// 제어 연결의 이 기기 쪽 주소(능동 모드에서 서버에 알릴 것).
    private func localIPv4() -> String? {
        guard let handle = control.input.property(forKey: streamKey(CFStreamPropertyKey.socketNativeHandle)) as? Data,
              handle.count >= MemoryLayout<Int32>.size else { return nil }
        let fd = handle.withUnsafeBytes { $0.load(as: Int32.self) }
        var addr = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let ok = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) } }
        guard ok == 0, addr.sin_family == sa_family_t(AF_INET) else { return nil }
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        inet_ntop(AF_INET, &addr.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN))
        return String(cString: buf)
    }

    // ── SyncConnection ────────────────────────────────────────

    func list() async throws -> [SyncEntry] {
        try await offThread { [self] in
            let year = Calendar(identifier: .gregorian).component(.year, from: Date())
            let raw = try transfer(mlsd ? "MLSD" : "LIST") { try $0.readAll(max: 4 * 1024 * 1024) }
            let text = String(data: raw, encoding: encoding) ?? String(decoding: raw, as: UTF8.self)
            return text.split(whereSeparator: \.isNewline).compactMap { line in
                mlsd ? FtpReplies.shared.mlsd(line: String(line)) : FtpReplies.shared.list(line: String(line), nowYear: Int32(year))
            }
        }
    }

    func read(_ name: String, maxBytes: Int) async throws -> String? {
        try await offThread { [self] () throws -> String? in
            do {
                let data = try transfer("RETR \(name)") { try $0.readAll(max: maxBytes) }
                return data.count > maxBytes ? nil : String(decoding: data, as: UTF8.self)
            } catch let f as SyncFailure where f.problem == .noaccess {
                // 550: 목록과 읽기 사이에 없어졌다(다른 기기가 바꾸는 중). 이번엔 건너뛴다.
                return nil
            }
        }
    }

    func write(_ name: String, _ data: Data) async throws {
        try await offThread { [self] in
            let temp = IosReadingSync.companion.partName(name: name)
            try transfer("STOR \(temp)") { try $0.write(data) }
            if try rename(temp, name) { return }
            // 이름 바꾸기가 있는 파일을 덮지 않는 서버(일부 윈도 IIS · 오래된 NAS)가 있다. 옛것을 지우고 다시 — 그 사이 잠깐 파일이
            // 없어도 다른 기기는 "이번엔 없음" 으로 넘기고 다음에 읽는다.
            _ = try command("DELE \(name)")
            if try rename(temp, name) { return }
            _ = try command("DELE \(temp)")
            throw SyncFailure(problem: .noaccess, detail: "rename \(temp) failed")
        }
    }

    private func rename(_ from: String, _ to: String) throws -> Bool {
        guard try command("RNFR \(from)").code == 350 else { return false }
        return try command("RNTO \(to)").code / 100 == 2
    }

    func delete(_ name: String) async throws {
        try await offThread { [self] in
            let r = try command("DELE \(name)")
            // 이미 없으면(550) 바라던 대로다.
            if r.code / 100 != 2 && r.code != 550 { try expect(r, 2, what: "delete \(name)") }
        }
    }

    func close() async {
        _ = try? await offThread { [self] in
            _ = try? command("QUIT")
            control.close()
        }
    }
}
