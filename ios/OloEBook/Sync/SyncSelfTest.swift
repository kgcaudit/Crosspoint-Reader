#if DEBUG
import Foundation
import OloCore

/// 연결 시험(안드로이드 `FtpConnectionTest` 의 짝). 이 맥에서 띄운 시험 서버(2121 FTP · 2122 명시적 FTPS · 2990 암묵적 FTPS ·
/// 2222 SFTP · 2124 말없는 소켓 · 2125 인사 도중 끊는 소켓, 사용자 reader / secret)에 실제로 잇고 쓰고 읽는다. 시뮬레이터는 맥의 127.0.0.1 에 그대로 닿는다.
/// `-olo-sync-selftest <인증서 지문> <SSH 키 지문>` 으로 돌리고, 결과는 Caches/sync-selftest.txt 에 줄마다 "PASS · FAIL 이름" 으로 적는다.
/// 출시 빌드에는 없다.
enum SyncSelfTest {
    static func runIfAsked() {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "-olo-sync-selftest"), i + 2 < args.count else { return }
        let certPin = args[i + 1]
        let sshPin = args[i + 2]
        Task.detached { await run(certPin: certPin, sshPin: sshPin) }
    }

    private static var lines: [String] = []

    private static func record(_ ok: Bool, _ name: String, _ detail: String = "") {
        lines.append("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : " — \(detail)")")
    }

    private static func config(_ port: Int32, _ protocol: FtpProtocol = .ftp, _ security: FtpSecurity = FtpSecurity.none,
                               path: String = "/OLO Ecosystem/이어 읽기", passive: Bool = true, trusted: String? = nil) -> FtpConfig {
        FtpConfig(host: "127.0.0.1", port: port, protocol: `protocol`, security: security, user: "reader", path: path, passive: passive,
                  encoding: .auto_, trusted: trusted)
    }

    /// 실패해야 하는 경우: 던진 까닭이 [want] 인가.
    private static func expectFailure(_ name: String, _ want: SyncProblem, _ c: FtpConfig, password: String = "secret") async -> SyncFailure? {
        do {
            try await SyncServer.check(c, password: password)
            record(false, name, "connected")
            return nil
        } catch let f as SyncFailure {
            record(f.problem == want, name, "\(f.problem.name) \(f.detail)")
            return f
        } catch {
            record(false, name, "\(error)")
            return nil
        }
    }

    /// 쓰기 · 덮어쓰기 · 목록 · 읽기 · 지우기 한 바퀴. 다른 기기가 읽는 모양 그대로(임시 이름 · 점 파일은 목록에 기기 파일로 남지 않음).
    private static func roundTrip(_ name: String, _ c: FtpConfig) async {
        do {
            try await SyncServer.check(c, password: "secret")
            let conn = try await SyncServer.open(c, password: "secret")
            let file = "aaaaaaaa-0000-4000-8000-000000000001.json"
            try await conn.write(file, Data("첫째".utf8))
            // 같은 이름으로 다시 — 이름 바꾸기가 있는 파일을 덮지 않는 서버에서도 된다.
            try await conn.write(file, Data("둘째 — 한글".utf8))
            let listed = try await conn.list()
            let names = listed.map(\.name)
            let entry = listed.first { $0.name == file }
            let back = try await conn.read(file, maxBytes: 1024)
            let leftovers = names.filter { $0.hasPrefix(".") }
            try await conn.delete(file)
            try await conn.delete(file) // 이미 없어도 조용히.
            let after = try await conn.list().map(\.name)
            let missing = try await conn.read(file, maxBytes: 1024)
            await conn.close()
            let ok = back == "둘째 — 한글" && entry?.size?.int64Value == Int64("둘째 — 한글".utf8.count) && entry?.modified != nil
                && leftovers.isEmpty && !after.contains(file) && missing == nil
            record(ok, name, "read=\(back ?? "nil") size=\(entry?.size?.stringValue ?? "-") left=\(leftovers) after=\(after)")
        } catch let f as SyncFailure {
            record(false, name, "\(f.problem.name) \(f.detail)")
        } catch {
            record(false, name, "\(error)")
        }
    }

    private static func run(certPin: String, sshPin: String) async {
        await roundTrip("plain ftp writes whole files into a korean folder", config(2121))
        await roundTrip("active mode", config(2121, path: "능동", passive: false))
        // 처음 보는 인증서: 지문을 들고 거절하고, 믿은 뒤에는 그 인증서로만.
        let first = await expectFailure("explicit ftps asks for the certificate first", .untrusted, config(2122, .ftp, .explicit_))
        record(first?.fingerprint?.replacingOccurrences(of: ":", with: "").lowercased() == certPin.lowercased(), "certificate fingerprint matches openssl",
               first?.fingerprint ?? "nil")
        await roundTrip("explicit ftps once trusted", config(2122, .ftp, .explicit_, trusted: first?.fingerprint))
        _ = await expectFailure("a different pinned certificate is refused", .untrusted, config(2122, .ftp, .explicit_, trusted: "AB:CD"))
        await roundTrip("implicit ftps once trusted", config(2990, .ftp, .implicit, trusted: first?.fingerprint))
        _ = await expectFailure("a server without tls says so", .notls, config(2121, .ftp, .explicit_))
        _ = await expectFailure("a wrong password is a refused login", .auth, config(2121), password: "wrong")
        _ = await expectFailure("a closed port is unreachable", .unreachable, config(2999))
        // 여러 줄 인사("220-…")를 보내다 끝줄 없이 끊는 서버(2125): 같은 줄을 끝없이 읽지 않고 곧 실패한다.
        let cut = Date()
        _ = await expectFailure("a server that hangs up mid-greeting fails at once", .offline, config(2125))
        record(Date().timeIntervalSince(cut) < 5, "it did not spin", "\(Int(Date().timeIntervalSince(cut)))s")
        // 잇기는 되는데 인사가 오지 않는 서버(디스크를 멈춘 NAS): "인터넷 없음" 이 아니라 잠듦으로, 한 번 더 넉넉히 기다린 뒤에(2124 는 말없는 소켓).
        let started = Date()
        _ = await expectFailure("a silent server is asleep and waited for once more", .asleep, config(2124))
        let waited = Date().timeIntervalSince(started)
        record(waited > SyncServer.timeout + SyncServer.wakeTimeout - 2 && waited < SyncServer.timeout + SyncServer.wakeTimeout + 10, "it waited 15 + 45 seconds", "\(Int(waited))s")
        let ssh = await expectFailure("sftp asks for the host key first", .untrusted, config(2222, .sftp))
        record(ssh?.fingerprint?.replacingOccurrences(of: ":", with: "").lowercased() == sshPin.lowercased(), "ssh key fingerprint matches ssh-keygen",
               ssh?.fingerprint ?? "nil")
        await roundTrip("sftp once trusted", config(2222, .sftp, trusted: ssh?.fingerprint))
        _ = await expectFailure("sftp wrong password", .auth, config(2222, .sftp, trusted: ssh?.fingerprint), password: "wrong")
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        try? (lines.joined(separator: "\n") + "\nDONE\n").write(to: dir.appendingPathComponent("sync-selftest.txt"), atomically: true, encoding: .utf8)
    }
}
#endif
