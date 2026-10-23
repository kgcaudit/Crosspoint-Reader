import Foundation
import OloCore
import UIKit
import WidgetKit

/// 이어 읽기 위젯에 보일 것을 적는다(구상안 47). 앱이 기록을 다시 읽을 때(책을 닫음 · 서재 새로고침 · 앱이 뒤로 감)마다 후보를 모아
/// Kotlin `IosWidget.model` 로 셈하고, 함께 쓰는 칸에 위젯 파일 · 작은 표지를 적은 뒤 위젯을 다시 그리게 한다. 바뀐 것이 없으면 적지
/// 않는다 — 위젯을 다시 그리게 할 때마다 시스템이 정한 하루 횟수를 쓴다.
@MainActor
enum WidgetFeed {
    /// 위젯에 그리는 표지 높이(픽셀). 큰 판의 표지가 168pt × 3배쯤 — 위젯은 메모리 한도가 작아 큰 그림을 읽다가 그려지지 않는다.
    private static let coverHeight: CGFloat = 480

    private static var last: WidgetSnapshot?

    static func update(_ library: Library) {
        guard WidgetShared.folder != nil else { return }
        let candidates = self.candidates(library)
        var models: [Int: WidgetSnapshot.Model] = [:]
        for kind in [IosWidget.shared.ALL, IosWidget.shared.BOOKS, IosWidget.shared.COMICS] {
            let m = IosWidget.shared.model(candidates: candidates, kind: kind)
            models[Int(kind)] = WidgetSnapshot.Model(top: m.top.map(item), recent: m.recent.map(item))
        }
        let snapshot = WidgetSnapshot(models: models)
        guard snapshot != last else { return }
        last = snapshot
        Task { await write(snapshot, library: library) }
    }

    /// 읽은 책(열어 본 것)과 읽은 만화 권(쪽 기록이 있는 것). 만화는 "작품 권" 이름 — 권 이름("1권")만으로는 무슨 만화인지 모른다.
    private static func candidates(_ library: Library) -> [WidgetCandidate] {
        let store = library.store
        let books = (library.reading + library.finished + library.toRead).compactMap { item -> WidgetCandidate? in
            let progress = store.progressOf(bookId: item.book.id)
            let opened = item.openedAt.map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
            let readAt = max(opened, progress?.updatedAtEpochMs ?? 0)
            guard readAt > 0 else { return nil }
            return WidgetCandidate(comic: false, id: item.book.id, title: item.book.label, byline: item.book.author,
                                   percent: Float(item.percent ?? 0), readAt: readAt,
                                   finishedAt: item.finishedAt.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) })
        }
        let comics = library.comicProgress.compactMap { id, p -> WidgetCandidate? in
            guard let work = library.work(of: id) else { return nil }
            let label = work.entries.first { $0.unit.id == id || $0.copies.contains { $0.id == id } }?.label
            return WidgetCandidate(comic: true, id: id, title: [work.title, label].compactMap { $0 }.joined(separator: " "),
                                   byline: "만화", percent: p.fraction * 100, readAt: p.updatedAtEpochMs, finishedAt: p.finishedAtEpochMs)
        }
        return books + comics
    }

    private static func item(_ i: WidgetItem) -> WidgetSnapshot.Item {
        WidgetSnapshot.Item(comic: i.comic, id: i.id, title: i.title, byline: i.byline, percentText: i.percentText, progress: Int(i.progress),
                            cover: WidgetShared.coverName(i.id))
    }

    /// 표지를 먼저 적고 위젯 파일을 나중에 — 위젯이 그 사이에 깨어나면 없는 표지 대신 대신 표지를 그린다(빈칸이 아니다).
    private static func write(_ snapshot: WidgetSnapshot, library: Library) async {
        guard let covers = WidgetShared.coverFolder, let url = WidgetShared.snapshotURL else { return }
        try? FileManager.default.createDirectory(at: covers, withIntermediateDirectories: true)
        var keep = Set<String>()
        var shown = snapshot
        for (kind, model) in snapshot.models {
            let items = [model.top].compactMap { $0 } + model.recent
            for item in items {
                guard let name = item.cover else { continue }
                if !keep.contains(name), let png = await cover(item, library: library) {
                    try? png.write(to: covers.appendingPathComponent(name), options: .atomic)
                }
                if FileManager.default.fileExists(atPath: covers.appendingPathComponent(name).path) {
                    keep.insert(name)
                } else {
                    // 표지가 없는 책(TXT · 표지 없는 EPUB)은 대신 표지 — 위젯이 없는 파일을 찾지 않게 이름을 지운다.
                    if shown.models[kind]?.top?.id == item.id { shown.models[kind]?.top?.cover = nil }
                    if let i = shown.models[kind]?.recent.firstIndex(where: { $0.id == item.id }) { shown.models[kind]?.recent[i].cover = nil }
                }
            }
        }
        // 이제 보이지 않는 책의 표지는 지운다 — 쌓이면 함께 쓰는 칸이 커진다.
        for name in (try? FileManager.default.contentsOfDirectory(atPath: covers.path)) ?? [] where !keep.contains(name) {
            try? FileManager.default.removeItem(at: covers.appendingPathComponent(name))
        }
        guard let data = try? JSONEncoder().encode(shown) else { return }
        try? data.write(to: url, options: .atomic)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetShared.kind)
    }

    /// 서재에 보이는 표지 그대로(고른 표지 · 만화로 보는 책의 앞 반 포함)를 위젯 크기로. 없으면 nil.
    private static func cover(_ item: WidgetSnapshot.Item, library: Library) async -> Data? {
        let image: UIImage?
        if item.comic {
            guard let work = library.work(of: item.id),
                  let unit = work.entries.flatMap({ [$0.unit] + $0.copies }).first(where: { $0.id == item.id }) else { return nil }
            image = await ComicCovers.shared.load(unit, library: library, rtl: work.rtl)
        } else {
            guard let book = library.shelfItem(item.id)?.book else { return nil }
            image = await Covers.shared.load(book, library: library)
        }
        guard let image, image.size.height > 0 else { return nil }
        let scale = min(1, coverHeight / (image.size.height * image.scale))
        let size = CGSize(width: max((image.size.width * image.scale * scale).rounded(), 1), height: max((image.size.height * image.scale * scale).rounded(), 1))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }.pngData()
    }
}
