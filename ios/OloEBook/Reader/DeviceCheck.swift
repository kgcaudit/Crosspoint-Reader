#if DEBUG
import Foundation
import UIKit

/// 기기 점검(개발용, 실행 인자 -olo-device-check): 서재의 모든 책 · 만화를 차례로 열어 넘겨 보고 결과를 Caches/device-check.txt 에
/// 적는다. 맥에서 실기기를 누를 수 없어, 사람이 손으로 하던 "열고 · 넘기고 · 되돌아오기" 를 기기에서 그대로 돌린다. 출시 빌드에는 없다.
@MainActor
final class DeviceCheck: ObservableObject {
    static let shared = DeviceCheck()

    /// 지금 열 책. 앱이 이것을 읽는 책으로 삼는다.
    @Published private(set) var current: LibraryBook?
    private(set) var active = false
    private var queue: [LibraryBook] = []
    private var peak: Double = 0
    private var problems = 0
    private let file = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("device-check.txt")

    func start(_ books: [LibraryBook]) {
        active = true
        queue = books
        try? FileManager.default.removeItem(at: file)
        let d = UIDevice.current
        log("START \(d.model) iOS \(d.systemVersion) books=\(books.count) mem=\(Self.mb())MB")
        advance()
    }

    private func advance() {
        current = nil
        Task {
            // 앞 책이 닫히고 자원이 풀린 뒤 연다.
            try? await Task.sleep(for: .seconds(1.5))
            guard !queue.isEmpty else {
                log("DONE problems=\(problems) peakMem=\(Int(peak))MB")
                active = false
                return
            }
            current = queue.removeFirst()
        }
    }

    func log(_ line: String) {
        let text = line + "\n"
        if let h = try? FileHandle(forWritingTo: file) {
            h.seekToEndOfFile()
            h.write(Data(text.utf8))
            try? h.close()
        } else {
            try? Data(text.utf8).write(to: file)
        }
    }

    private func problem(_ s: String) {
        problems += 1
        log("  PROBLEM " + s)
    }

    /// 앱이 쓰는 메모리(물리 사용량, MB).
    static func mb() -> Int {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let r = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
        }
        return r == KERN_SUCCESS ? Int(info.phys_footprint / 1_048_576) : -1
    }

    private func sample() {
        peak = max(peak, Double(Self.mb()))
    }

    /// 리더가 연 책 하나를 점검한다. 이 책이 지금 점검 차례가 아니면 아무것도 하지 않는다.
    func run(model: ReaderModel, book: LibraryBook) async {
        guard active, current?.id == book.id else { return }
        let started = Date()
        log("[\(book.format.rawValue)] \(book.displayName)")
        // 열기: 첫 쪽 · 웹툰 · 실패 중 하나가 될 때까지(최대 40초).
        while model.frame == nil && model.webtoon == nil && model.failure == nil && Date().timeIntervalSince(started) < 40 {
            try? await Task.sleep(for: .milliseconds(100))
        }
        let openTime = Date().timeIntervalSince(started)
        sample()
        if let f = model.failure {
            problem("open failed: \(f)")
            return advance()
        }
        if model.frame == nil && model.webtoon == nil {
            problem("open timed out after 40s")
            return advance()
        }
        log(String(format: "  open %.2fs mem=%dMB", openTime, Self.mb()))
        if openTime > 5 { problem(String(format: "slow open %.1fs", openTime)) }

        if let toon = model.webtoon {
            await checkWebtoon(toon)
        } else {
            await checkPages(model)
        }
        sample()
        log("  mem after=\(Self.mb())MB")
        advance()
    }

    /// 쪽 넘김: 앞으로 10 · 뒤로 10 이 같은 쪽들을 거꾸로 지나 처음 쪽으로 돌아오는가, 넘김 하나에 걸리는 시간, 진도 막대로 가운데 · 처음.
    private func checkPages(_ model: ReaderModel) async {
        guard let first = model.frame else { return }
        log("  start \(first.key) page \(first.page + 1)/\(first.pages) pct \(String(format: "%.1f", first.percent))")
        var forward: [String] = [first.key]
        var times: [Double] = []
        for _ in 0..<10 {
            let t = Date()
            let moved = await model.turn(1, quiet: true)
            times.append(Date().timeIntervalSince(t))
            await settle()
            guard moved, let f = model.frame else { break }
            if f.key == forward.last { problem("turn forward did not change page at \(f.key)") }
            forward.append(f.key)
            if f.image.size.width < 2 { problem("empty page image at \(f.key)") }
        }
        sample()
        var back: [String] = [model.frame?.key ?? "?"]
        for _ in 0..<(forward.count - 1) {
            let t = Date()
            _ = await model.turn(-1, quiet: true)
            times.append(Date().timeIntervalSince(t))
            await settle()
            back.append(model.frame?.key ?? "?")
        }
        if Array(back.reversed()) != forward {
            problem("back path differs\n    fwd \(forward.joined(separator: " "))\n    back \(back.reversed().joined(separator: " "))")
        }
        if model.frame?.key != first.key { problem("did not return to start: \(model.frame?.key ?? "nil") vs \(first.key)") }
        let avg = times.isEmpty ? 0 : times.reduce(0, +) / Double(times.count)
        let worst = times.max() ?? 0
        log(String(format: "  turns %d avg %.0fms worst %.0fms", times.count, avg * 1000, worst * 1000))
        if worst > 1.5 { problem(String(format: "slow turn %.1fs", worst)) }
        // 진도 막대: 가운데로 갔다가 처음 자리로.
        let t = Date()
        await model.seek(50)
        await settle()
        let mid = model.frame
        log(String(format: "  seek 50%% -> %@ pct %.1f in %.2fs", mid?.key ?? "nil", mid?.percent ?? -1, Date().timeIntervalSince(t)))
        if let mid, abs(mid.percent - 50) > 15, (mid.isPdf || mid.isComic ? mid.pages : mid.pages * mid.chapters) > 20 { problem(String(format: "seek 50%% landed at %.1f%%", mid.percent)) }
        sample()
        await model.seek(max(first.percent, 0))
        await settle()
        if model.frame?.key != first.key { log("  note: seek back landed at \(model.frame?.key ?? "nil") (start \(first.key))") }
    }

    /// 웹툰: 아래로 10 화면 · 위로 10 화면, 자리가 움직이고 돌아오는가.
    private func checkWebtoon(_ toon: WebtoonModel) async {
        let top0 = toon.top
        for _ in 0..<10 { toon.step(1); try? await Task.sleep(for: .milliseconds(400)) }
        let down = toon.top
        sample()
        for _ in 0..<10 { toon.step(-1); try? await Task.sleep(for: .milliseconds(400)) }
        log(String(format: "  webtoon top %.0f -> %.0f -> %.0f", top0, down, toon.top))
        if down <= top0 { problem("webtoon did not scroll down") }
        if abs(toon.top - top0) > 2 { problem(String(format: "webtoon did not return (%.0f vs %.0f)", toon.top, top0)) }
    }

    /// 넘김 그림 · 앞뒤 그리기가 자리를 잡게 잠깐.
    private func settle() async { try? await Task.sleep(for: .milliseconds(350)) }
}
#endif
