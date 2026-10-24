import CoreText
import OloCore
import SwiftUI
import UniformTypeIdentifiers

/// 넣은 글꼴 하나. [file] 은 글꼴 폴더 안의 앱이 지은 이름 — 조판 설정의 글꼴 ID 가 된다(`UserFonts.fontId`).
struct UserFontItem: Identifiable, Hashable {
    let file: String
    let label: String
    let hasHangul: Bool
    var id: String { file }
}

/// 사용자 글꼴 넣기 · 빼기 · 목록(안드로이드 `UserFonts` · `FontCatalog` 의 짝). 파일은 Application Support/fonts 에 둔다 —
/// 캐시 영역에 두면 공간이 모자랄 때 iOS 가 지워, 고른 글꼴이 말없이 휴대폰 글꼴로 바뀐다.
@MainActor
final class FontLibrary: ObservableObject {
    static let shared = FontLibrary()

    @Published private(set) var fonts: [UserFontItem] = []

    let directory: URL

    private init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        directory = support.appendingPathComponent("fonts", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // 측정기(Kotlin)가 글꼴 ID 에서 파일을 찾는 곳. 첫 조판 전에 정해야 한다 — 늦으면 첫 쪽만 휴대폰 글꼴로 짜인다.
        UserFonts.shared.directory = directory.path
        reload()
    }

    /// 폴더를 읽어 목록을 만든다. 읽지 못하는 파일(손상 · 다른 앱이 넣은 파일)은 목록에서 뺀다 — 골라도 휴대폰 글꼴로 보인다.
    func reload() {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        fonts = names.sorted().compactMap { name in
            // 넣다 끊긴 임시 파일.
            guard !name.hasPrefix(".") else { return nil }
            let check = UserFonts.shared.check(path: directory.appendingPathComponent(name).path)
            guard check.result == UserFonts.Result.ok else { return nil }
            return UserFontItem(file: name, label: check.label ?? name, hasHangul: check.hasHangul)
        }
        .sorted { $0.label.localizedStandardCompare($1.label) == .orderedAscending }
    }

    func item(_ file: String?) -> UserFontItem? { fonts.first { $0.file == file } }

    enum ImportResult {
        case added(UserFontItem)
        case rejected(UserFonts.Result)
    }

    /// 고른 파일을 글꼴 폴더로 들여온다. 먼저 폴더 안으로 복사하고 검사한다 — 다른 앱의 파일은 보안 범위가 끝나면 다시 읽을 수
    /// 없다. 같은 글꼴(이름 · 크기가 같은)을 다시 넣으면 새로 두지 않고 있던 것을 돌려준다.
    func importFont(from url: URL) async -> ImportResult {
        let ext = url.pathExtension.lowercased().isEmpty ? "font" : url.pathExtension.lowercased()
        let temp = directory.appendingPathComponent(".\(UUID().uuidString).\(ext)")
        // 복사 · 검사는 주 스레드 밖에서 — 64MB 까지 받는 글꼴을 주 스레드에서 복사하면 그동안 화면이 멈췄다.
        let check: UserFonts.Check? = await Task.detached(priority: .userInitiated) {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            guard (try? FileManager.default.copyItem(at: url, to: temp)) != nil else { return nil }
            return UserFonts.shared.check(path: temp.path)
        }.value
        guard let check else { return .rejected(.unreadable) }
        guard check.result == UserFonts.Result.ok else {
            try? FileManager.default.removeItem(at: temp)
            return .rejected(check.result)
        }
        let label = check.label ?? url.deletingPathExtension().lastPathComponent
        let size = fileSize(temp)
        if let same = fonts.first(where: { $0.label == label && fileSize(directory.appendingPathComponent($0.file)) == size }) {
            try? FileManager.default.removeItem(at: temp)
            return .added(same)
        }
        let name = "\(UUID().uuidString).\(ext)"
        do {
            try FileManager.default.moveItem(at: temp, to: directory.appendingPathComponent(name))
        } catch {
            try? FileManager.default.removeItem(at: temp)
            return .rejected(.unreadable)
        }
        reload()
        return .added(item(name) ?? UserFontItem(file: name, label: label, hasHangul: check.hasHangul))
    }

    func remove(_ font: UserFontItem) {
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(font.file))
        reload()
    }

    /// 목록에서 이름을 그 글꼴로 그릴 SwiftUI 글꼴. 읽지 못하면 본문 글꼴.
    func preview(_ font: UserFontItem, size: CGFloat = 16) -> Font {
        guard let first = Self.descriptor(directory.appendingPathComponent(font.file)) else { return CpFont.body }
        return Font(CTFontCreateWithFontDescriptor(first, size, nil))
    }

    private static var descriptors: [URL: CTFontDescriptor] = [:]

    /// 글꼴 파일의 첫 꼴(파일마다 한 번만 읽는다 — 판을 그릴 때마다 글꼴 파일을 다시 읽고 풀었다).
    static func descriptor(_ url: URL) -> CTFontDescriptor? {
        if let d = descriptors[url] { return d }
        guard let list = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor], let first = list.first else { return nil }
        descriptors[url] = first
        return first
    }

    private func fileSize(_ url: URL) -> Int64? {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
    }

    /// 거절 이유를 사람의 말로 — 무엇을 하면 되는지까지(안드로이드 `describe` 와 같은 문구).
    static func describe(_ reason: UserFonts.Result) -> String {
        switch reason {
        case .notafont: "글꼴 파일이 아닙니다. TTF · OTF · TTC 파일을 골라 주세요."
        case .webfont: "웹 글꼴(WOFF)은 넣을 수 없습니다. 같은 글꼴의 TTF나 OTF 파일을 골라 주세요."
        case .broken: "글꼴 파일이 손상됐습니다. 내려받기가 덜 끝났을 수 있으니 다시 받아 보세요."
        case .toolarge: "글꼴 파일이 너무 큽니다. 64MB 이하만 넣을 수 있습니다."
        default: "파일을 읽지 못했습니다. 파일이 기기에 내려받아져 있는지 확인해 주세요."
        }
    }
}
