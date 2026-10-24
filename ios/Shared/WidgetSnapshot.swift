import CryptoKit
import Foundation

/// 이어 읽기 위젯(구상안 47)에 보일 것. 앱이 셈해서(Kotlin `IosWidget.model`) 함께 쓰는 칸에 적고, 위젯은 읽어 그리기만 한다 —
/// 위젯은 앱과 따로 도는 작은 프로그램이라 앱의 기록 파일 · 공통 코어를 읽지 않는다. 앱과 위젯이 이 파일 하나를 함께 쓴다.
struct WidgetSnapshot: Codable, Equatable {
    /// 최근 목록 거르기마다 하나(0 책 · 만화, 1 책만, 2 만화만 — 위젯 설정이 고른다). 맨 위 책은 셋 다 같다.
    var models: [Int: Model]

    struct Model: Codable, Equatable {
        var top: Item?
        var recent: [Item]
    }

    struct Item: Codable, Equatable, Identifiable {
        var comic: Bool
        var id: String
        var title: String
        var byline: String?
        var percentText: String
        /// 막대(0~1000).
        var progress: Int
        /// 함께 쓰는 칸의 작은 표지 파일 이름. 없으면 대신 표지(제목을 얹은 판).
        var cover: String?

        /// 누르면 이 책 · 권을 읽던 자리로(앱의 `onOpenURL`).
        var url: URL {
            var c = URLComponents()
            c.scheme = WidgetShared.scheme
            c.host = "continue"
            c.queryItems = [URLQueryItem(name: comic ? "comic" : "book", value: id)]
            return c.url ?? WidgetShared.openApp
        }
    }
}

/// 앱과 위젯이 함께 쓰는 칸(App Group).
enum WidgetShared {
    static let group = "group.io.github.kgcaudit.oloebook"
    static let kind = "OloContinue"
    static let scheme = "olo"
    /// 읽은 것이 없을 때 — 앱만 연다.
    static let openApp = URL(string: "olo://continue")!

    static var folder: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) }
    static var snapshotURL: URL? { folder?.appendingPathComponent("widget.json") }
    static var coverFolder: URL? { folder?.appendingPathComponent("widget-covers", isDirectory: true) }

    static func load() -> WidgetSnapshot? {
        guard let url = snapshotURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    /// 표지 파일 이름: id 의 SHA-1 앞 20자(id 는 경로라 그대로 파일 이름이 될 수 없다).
    static func coverName(_ id: String) -> String { shortHash(id) + ".png" }

    /// id 의 SHA-1 앞 20자 — 경로인 id 를 파일 이름으로(앱의 고른 표지 파일도 같은 셈을 쓴다).
    static func shortHash(_ id: String) -> String {
        String(Insecure.SHA1.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined().prefix(20))
    }

    /// 위젯이 보낸 주소면 (만화인가, id), 아니면 nil. 앱만 여는 주소는 ("", false).
    static func target(of url: URL) -> (comic: Bool, id: String)? {
        guard url.scheme == scheme, url.host == "continue" else { return nil }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let id = items.first(where: { $0.name == "book" })?.value, !id.isEmpty { return (false, id) }
        if let id = items.first(where: { $0.name == "comic" })?.value, !id.isEmpty { return (true, id) }
        return (false, "")
    }
}
