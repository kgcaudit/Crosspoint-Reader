import OloCore
import SwiftUI
import UniformTypeIdentifiers

/// 앱 정보(구상안 31A, 안드로이드 `About` · `Records`): 앱 이름 · 판, 안내문, 읽기 기록 백업 · 가져오기.
/// 형식 · 합치기는 Kotlin `RecordsBackup` 이 정한다 — 안드로이드와 같은 파일이라 두 앱이 서로 가져온다.
struct AboutScreen: View {
    @ObservedObject var library: Library
    let back: () -> Void

    @State private var summary: BackupSummary?
    @State private var exporting: BackupDocument?
    @State private var importing = false
    @State private var popup: RecordsPopup?
    @State private var busy: String?
    @State private var toast: String?
    @State private var lastBackup = UserDefaults.standard.object(forKey: "records.lastBackupAt") as? Date

    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 0) {
                CpHeader(title: "앱 정보", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 14) {
                            RoundedRectangle(cornerRadius: 12).fill(CpColor.accent).frame(width: 48, height: 48)
                                .overlay(CpIconView(icon: .book, size: 26, color: .white))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("OLO eBook").font(CpFont.title).foregroundStyle(CpColor.text)
                                Text("iOS \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") · 공통 코어 안드로이드 0.54.9")
                                    .font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                            }
                        }
                        .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 12)
                        Text("인터넷을 쓰지 않습니다. 읽은 자리 · 책갈피 · 형광펜은 이 휴대폰에 남습니다. 휴대폰을 바꿀 때는 백업 파일로 옮기세요.")
                            .font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, CpMetrics.gutter).padding(.bottom, 8)
                        CpSectionLabel(text: "읽기 기록").padding(.horizontal, CpMetrics.gutter).padding(.top, 14).padding(.bottom, 4)
                        row("백업 파일 만들기", summary.map(RecordsText.summaryLine) ?? " ", dim: !canBackUp) { makeBackup() }
                        row("백업 파일에서 가져오기",
                            lastBackup.map { "마지막 백업 \(RecordsText.date($0))" } ?? "다른 휴대폰에서 만든 백업 파일을 고릅니다") { importing = true }
                    }
                    .frame(maxWidth: 600, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .background(CpColor.background)
            if let busy {
                CpPopup(onDismiss: {}) {
                    Text(busy).font(CpFont.title).foregroundStyle(CpColor.text)
                    Text("잠시만 기다려 주세요. 책이 많으면 몇 초 걸립니다.").font(CpFont.body).foregroundStyle(CpColor.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let popup { popupView(popup) }
            if let toast {
                CpToast(message: toast)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .allowsHitTesting(false)
                    .task(id: toast) {
                        try? await Task.sleep(for: .milliseconds(2500))
                        if !Task.isCancelled { self.toast = nil }
                    }
            }
        }
        // 서재를 다 훑으면(앱을 켜자마자 열었을 때) 다시 센다 — 한 번만 세면 "아직 기록이 없습니다" 로 남는다.
        .task(id: library.bookCount) { summary = library.backupSummary() }
        .task {
            #if DEBUG
            for _ in 0..<50 where library.scanning || library.isEmpty { try? await Task.sleep(for: .milliseconds(100)) }
            // 화면 확인용(고르기 · 저장 창은 명령으로 누를 수 없다): -olo-import <경로> 는 그 파일을 고른 것처럼, -olo-export-to <경로> 는
            // 백업 글을 그 경로에 쓴다. 출시 빌드에는 없다.
            let args = CommandLine.arguments
            if let i = args.firstIndex(of: "-olo-export-to"), i + 1 < args.count {
                try? library.exportRecords().write(toFile: args[i + 1], atomically: true, encoding: .utf8)
            }
            if let i = args.firstIndex(of: "-olo-import"), i + 1 < args.count { await read(URL(fileURLWithPath: args[i + 1])) }
            #endif
        }
        .fileExporter(isPresented: Binding(get: { exporting != nil }, set: { if !$0 { exporting = nil } }),
                      document: exporting, contentType: .json, defaultFilename: RecordsText.fileName()) { result in
            switch result {
            case .success:
                let now = Date()
                UserDefaults.standard.set(now, forKey: "records.lastBackupAt")
                lastBackup = now
                toast = "백업 파일을 만들었습니다 · \(RecordsText.volumes(Int(summary?.books ?? 0), Int(summary?.comics ?? 0)))"
            case .failure(let error):
                // 저장 창에서 취소한 것은 실패가 아니다.
                if (error as NSError).code != NSUserCancelledError { popup = .failed("백업 파일을 만들지 못했습니다") }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
            guard case let .success(url) = result else { return }
            Task { await read(url) }
        }
    }

    private var canBackUp: Bool { (summary?.books ?? 0) + (summary?.comics ?? 0) + (summary?.works ?? 0) > 0 }

    private func row(_ title: String, _ sub: String, dim: Bool = false, action: @escaping () -> Void) -> some View {
        // 흐려도 눌린다 — 누르면 왜 안 되는지 알린다(안드로이드 0.49.1: 흐리기만 하고 빈 백업 파일을 만들었다).
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(CpFont.body).foregroundStyle(CpColor.text)
                Text(sub).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
            }
            .opacity(dim ? 0.45 : 1)
            .padding(.horizontal, CpMetrics.gutter)
            .frame(maxWidth: .infinity, minHeight: CpMetrics.rowHeight, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func makeBackup() {
        guard canBackUp else { toast = "아직 담을 읽기 기록이 없습니다"; return }
        busy = "백업 파일을 만드는 중…"
        let text = library.exportRecords()
        busy = nil
        exporting = BackupDocument(text: text)
    }

    private func read(_ url: URL) async {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        busy = "백업 파일을 읽는 중…"
        defer { busy = nil }
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        // 큰 파일은 읽지 않는다 — 엉뚱한 동영상을 골라 앱이 멈추지 않게.
        guard Int64(size) <= RecordsCodec.shared.MAX_BYTES, let data = try? Data(contentsOf: url) else {
            popup = size > 0 ? .notBackup(url.lastPathComponent) : .failed("기록을 가져오지 못했습니다")
            return
        }
        guard let text = String(data: data, encoding: .utf8), let preview = library.previewRecords(text) else {
            popup = .notBackup(url.lastPathComponent)
            return
        }
        popup = preview.isEmpty ? .empty : .preview(preview, text)
    }

    private func apply(_ text: String) {
        popup = nil
        busy = "기록을 가져오는 중…"
        let result = library.importRecords(text)
        busy = nil
        summary = library.backupSummary()
        popup = result.map { .done($0) } ?? .failed("기록을 가져오지 못했습니다")
    }

    @ViewBuilder
    private func popupView(_ p: RecordsPopup) -> some View {
        CpPopup(onDismiss: { popup = nil }) {
            switch p {
            case .notBackup(let name):
                title("백업 파일이 아닙니다")
                body("고른 파일(\(name))은 OLO eBook 백업 파일이 아니어서 아무것도 바꾸지 않았습니다. 이름이 ‘OLO eBook 읽기 기록’으로 시작하는 파일을 골라 주세요.")
                ok()
            case .failed(let what):
                title(what)
                body("파일을 읽거나 쓰지 못했습니다. 다른 곳을 골라 다시 해 주세요.")
                ok()
            case .empty:
                title("백업 파일에 기록이 없습니다")
                body("가져올 읽기 기록이 없어 아무것도 바꾸지 않았습니다.")
                ok()
            case .preview(let v, let text):
                title("백업 파일에서 가져오기")
                body(RecordsText.previewBody(v))
                let books = Int(v.books), comics = Int(v.comics), foundComics = Int(v.comicsFound), foundBooks = Int(v.found) - foundComics
                if books + comics > 0 {
                    Text("이 휴대폰에서 찾은 \(RecordsText.volumes(foundBooks, foundComics))").font(CpFont.subtitle).foregroundStyle(CpColor.text)
                }
                if Int(v.missing) > 0 {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("아직 못 찾은 \(RecordsText.volumes(books - foundBooks, comics - foundComics))").font(CpFont.subtitle).foregroundStyle(CpColor.text)
                        Text("책 폴더를 추가하면 그때 이어집니다").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    }
                }
                HStack {
                    Spacer()
                    CpButton(title: "취소", kind: .secondary) { popup = nil }
                    CpButton(title: "가져오기") { apply(text) }
                }
            case .done(let r):
                title(RecordsText.doneTitle(r))
                let b = RecordsText.doneBody(r)
                if !b.isEmpty { body(b) }
                if !r.missingNames.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("못 찾은 책").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                        ForEach(Array(r.missingNames.prefix(5).enumerated()), id: \.offset) { _, n in
                            Text(n).font(CpFont.subtitle).foregroundStyle(CpColor.text).lineLimit(1)
                        }
                        if r.missingNames.count > 5 {
                            Text("그 밖에 \(r.missingNames.count - 5)권").font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                        }
                    }
                }
                ok()
            }
        }
    }

    private func title(_ t: String) -> some View { Text(t).font(CpFont.title).foregroundStyle(CpColor.text) }

    private func body(_ t: String) -> some View {
        Text(t).font(CpFont.body).foregroundStyle(CpColor.text).fixedSize(horizontal: false, vertical: true)
    }

    private func ok() -> some View { HStack { Spacer(); CpButton(title: "확인") { popup = nil } } }
}

private enum RecordsPopup {
    case notBackup(String)
    case failed(String)
    case empty
    case preview(ImportPreview, String)
    case done(ImportResult)
}

/// 백업 판들의 글(안드로이드 `Records.kt` 의 문구 그대로).
enum RecordsText {
    /// "책 3권" · "만화 2권" · "책 3권 · 만화 2권". 만화가 없으면 책만.
    static func volumes(_ books: Int, _ comics: Int) -> String {
        if comics == 0 { return "책 \(books)권" }
        if books == 0 { return "만화 \(comics)권" }
        return "책 \(books)권 · 만화 \(comics)권"
    }

    static func summaryLine(_ s: BackupSummary) -> String {
        let books = Int(s.books), comics = Int(s.comics)
        if books + comics > 0 { return "\(volumes(books, comics)) · 책갈피 \(s.bookmarks)개 · 형광펜 · 메모 \(s.notes)개" }
        if s.works > 0 { return "작품 설정 \(s.works)개" }
        return "아직 기록이 없습니다"
    }

    /// 올해면 "9월 30일", 아니면 "2025년 9월 30일".
    static func date(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = Calendar.current.isDate(d, equalTo: Date(), toGranularity: .year) ? "M월 d일" : "yyyy년 M월 d일"
        return f.string(from: d)
    }

    /// "OLO eBook 읽기 기록 2026-10-21"(저장 창이 .json 을 붙인다).
    static func fileName() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return "OLO eBook 읽기 기록 \(f.string(from: Date()))"
    }

    static func previewBody(_ v: ImportPreview) -> String {
        let made = v.createdAt > 0 ? "\(date(Date(timeIntervalSince1970: Double(v.createdAt) / 1000)))에 만든 백업 · " : ""
        var parts: [String] = []
        if v.books + v.comics > 0 { parts.append(volumes(Int(v.books), Int(v.comics))) }
        if v.works > 0 { parts.append("작품 설정 \(v.works)개") }
        return "\(made)\(parts.joined(separator: " · "))의 기록입니다. 이 휴대폰의 기록과 합칩니다. 읽은 자리는 더 많이 읽은 쪽을 따르고, 책갈피 · 형광펜 · 메모는 양쪽 것을 모두 남깁니다."
    }

    static func doneTitle(_ r: ImportResult) -> String {
        if r.found > 0 { return "\(volumes(Int(r.found - r.comics), Int(r.comics)))의 기록을 가져왔습니다" }
        // 찾은 권이 없어도 작품 설정은 붙었다 — "찾은 책이 없습니다" 라고 하면 실패한 것처럼 읽힌다(안드로이드 `doneTitle`).
        if r.works > 0 { return "작품 설정 \(r.works)개를 가져왔습니다" }
        return "이 휴대폰에서 찾은 책이 없습니다"
    }

    static func doneBody(_ r: ImportResult) -> String {
        let waiting = r.missingNames.isEmpty ? "" : "못 찾은 \(r.missingNames.count)권의 기록은 기억해 두었다가 책 폴더를 추가하면 이어 붙입니다."
        if r.found > 0 {
            let finished = r.finished > 0 ? " · 읽은 책 \(r.finished)권" : ""
            return "책갈피 \(r.bookmarks)개 · 형광펜 · 메모 \(r.notes)개\(finished). \(waiting)".trimmingCharacters(in: .whitespaces)
        }
        if r.works > 0 { return "고쳐 둔 작품 이름 · 넘기는 방향 · 보는 방식이 그 작품에 붙었습니다. \(waiting)".trimmingCharacters(in: .whitespaces) }
        return waiting
    }
}

/// 저장 창에 넘길 백업 글.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? ""
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}
