import ImageIO
import OloCore
import SwiftUI
import UIKit

/// 만화의 쪽 밖 바탕(안드로이드 `COMIC_BACKDROP` 0x141311) — 쪽 그림 둘레가 이 색이고, 메뉴도 이 밝기를 따라 어둡다.
let comicPaper = Paper(paper: 0x141311, ink: 0xE8E3DA, inkMuted: 0xB9B2A8, dark: true)

/// 그림 바이트를 화면 크기에 맞춰 솎아 푼다(ImageIO). 원본 크기로 풀면 스캔본 한 쪽이 수십 MB 다.
enum ComicImages {
    static func decode(_ data: Data, maxPixel: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(Int(maxPixel), 64),
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// 만화를 그릴 화면.
struct ComicViewSpec: Equatable {
    let size: CGSize
    let insets: EdgeInsets
    let twoPages: Bool
    let coverAlone: Bool
    let rightToLeft: Bool

    /// 쪽을 놓는 칸: 노치를 피하고, 아래 줄이 없으니 화면 끝까지.
    var area: CGRect {
        CGRect(x: insets.leading, y: insets.top, width: size.width - insets.leading - insets.trailing,
               height: size.height - insets.top - max(insets.bottom, 0))
    }
}

/// 만화 한 권(구상안 36, 안드로이드 `ComicReader` 쪽 넘김). 쪽 순서 · 짝짓기는 공통 코드, 그림 풀기는 ImageIO.
/// 진도는 판의 **마지막 쪽**으로 적는다 — 두 쪽 판이면 오른쪽(오→왼이면 왼쪽) 쪽까지 읽은 것이다(안드로이드와 같다).
actor ComicEngine: BookEngine, ZoomSource {
    private let book: IosComicBook
    private let store: ReadingStore
    let unitId: String
    nonisolated let title: String
    nonisolated let author: String? = nil
    nonisolated let pageCount: Int
    nonisolated var isComic: Bool { true }

    /// 쪽마다 그림 크기(Kotlin 목록 그대로 — 짝짓기에 다시 넘긴다). 모르는 쪽은 비어 있다.
    private var rawSizes: [Any] = []
    private var sizes: [ImageSize?] = []
    private var spreads: [[Int]] = []
    /// 지금 판의 첫 쪽(설정이 바뀌어 짝이 달라져도 이 쪽이 든 판으로).
    private var page = 0
    private var spec: ComicViewSpec?
    private var images: [Int: UIImage] = [:]
    private var broken = Set<Int>()

    init(file: Library.ComicFile, unitId: String, title: String, store: ReadingStore) throws {
        let opened: IosComicBook? = if let names = file.images {
            IosComicBook.companion.openFolder(path: file.url.path, fileNames: names)
        } else {
            IosComicBook.companion.openArchive(path: file.url.path)
        }
        guard let opened else { throw ComicOpenError.broken }
        book = opened
        self.store = store
        self.unitId = unitId
        self.title = title
        pageCount = Int(opened.pageCount)
        let saved = store.comicProgress()[unitId]
        // 다 읽고 마지막 쪽에서 멈춘 권을 다시 열면 처음부터(안드로이드와 같다).
        if let saved, !(saved.finishedAtEpochMs != nil && Int(saved.page) >= pageCount - 1) {
            page = min(max(Int(saved.page), 0), pageCount - 1)
        }
    }

    // MARK: 판

    private func arrange() {
        if rawSizes.isEmpty {
            rawSizes = book.sizes()
            sizes = rawSizes.map { $0 as? ImageSize }
        }
        guard let spec, spec.twoPages, pageCount > 1 else {
            spreads = (0..<pageCount).map { [$0] }
            return
        }
        spreads = book.spreads(sizes: rawSizes, coverAlone: spec.coverAlone).map { $0.map { Int(truncating: $0) } }
    }

    private var index: Int { spreads.firstIndex { $0.contains(page) } ?? 0 }

    func layOut(_ spec: ComicViewSpec) async -> PageFrame {
        let regroup = self.spec?.twoPages != spec.twoPages || self.spec?.coverAlone != spec.coverAlone
        if regroup || self.spec?.size != spec.size { images = [:] }
        self.spec = spec
        if regroup || spreads.isEmpty { arrange() }
        return show(index)
    }

    private func show(_ i: Int) -> PageFrame {
        let shown = spreads.isEmpty ? [page] : spreads[min(max(i, 0), spreads.count - 1)]
        page = shown.first ?? 0
        let last = shown.max() ?? page
        let p = store.saveComic(unitId: unitId, page: Int32(last), pageCount: Int32(pageCount), atEnd: last >= pageCount - 1)
        let marks = Set(store.comicBookmarks(unitId: unitId).map { Int(truncating: $0) })
        var f = PageFrame(pdfImage: render(shown), page: page, pages: pageCount, percent: Double(p.fraction) * 100,
                          bookmarked: !marks.isDisjoint(with: shown), hasRight: shown.count > 1, section: "", pageLabel: nil, pagesLeft: 0)
        f.isPdf = false
        f.isComic = true
        f.brokenPages = shown.filter { broken.contains($0) }
        return f
    }

    /// 쪽 그림(화면 높이에 맞춘 크기로 솎아 풀어 둔다 — 앞뒤 판까지만 남긴다).
    private func image(_ p: Int) -> UIImage? {
        if let img = images[p] { return img }
        guard let spec, let data = book.data(i: Int32(p)) else { broken.insert(p); return nil }
        let px = max(spec.size.width, spec.size.height) * UIScreen.main.scale
        guard let img = ComicImages.decode(data as Data, maxPixel: px) else { broken.insert(p); return nil }
        broken.remove(p)
        if images.count > 8 {
            let keep = Set((spreads.indices.filter { abs($0 - index) <= 1 }).flatMap { spreads[$0] })
            images = images.filter { keep.contains($0.key) }
        }
        images[p] = img
        return img
    }

    /// 판 한 장: 어두운 바탕 위에 쪽. 휴대폰은 위에 붙이고(남는 높이는 아래로), 두 쪽이면 책등에 붙인 둘 — 오→왼이면 앞 쪽이
    /// 오른쪽. 혼자인 쪽은 공통 `SpreadSlot` 자리(표지는 뒤 쪽 자리, 혼자 남은 쪽은 앞 쪽 자리, 펼침면은 가운데).
    private func render(_ shown: [Int]) -> UIImage {
        guard let spec else { return UIImage() }
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = true
        let area = spec.area
        let phone = UIDevice.current.userInterfaceIdiom == .phone
        return UIGraphicsImageRenderer(size: spec.size, format: format).image { ctx in
            let cg = ctx.cgContext
            cg.setFillColor(UIColor(comicPaper.paperColor).cgColor)
            cg.fill(CGRect(origin: .zero, size: spec.size))
            func place(_ img: UIImage?, in box: CGRect, align: Int) {
                guard let img, img.size.width > 0 else { return }
                let r = PdfPages.fit(img.size, in: box, align: align)
                // 휴대폰은 그림이 맨 위(남는 높이는 아래), 태블릿은 아래에 붙인다(안드로이드 verticalBias).
                let y = phone ? box.minY : box.maxY - r.height
                img.draw(in: CGRect(x: r.minX, y: y, width: r.width, height: r.height))
            }
            if spec.twoPages {
                let left = CGRect(x: area.minX, y: area.minY, width: area.width / 2, height: area.height)
                let right = left.offsetBy(dx: area.width / 2, dy: 0)
                if shown.count == 2 {
                    let (l, r) = spec.rightToLeft ? (shown[1], shown[0]) : (shown[0], shown[1])
                    place(image(l), in: left, align: -1)
                    place(image(r), in: right, align: 1)
                } else if let only = shown.first {
                    let size = sizes.indices.contains(only) ? sizes[only] : nil
                    let wide = size.map { Float($0.width) > Float($0.height) * 1.15 } ?? false
                    let slot = SpreadSlot.companion.of(pages: [KotlinInt(int: Int32(only))], first: only == 0, wide: wide, coverAlone: spec.coverAlone)
                    if slot == .center {
                        place(image(only), in: area, align: 0)
                    } else if slot.onLeft(rightToLeft: spec.rightToLeft) {
                        place(image(only), in: left, align: -1)
                    } else {
                        place(image(only), in: right, align: 1)
                    }
                }
            } else if let only = shown.first {
                place(image(only), in: area, align: 0)
            }
        }
    }

    // MARK: BookEngine

    func next() async throws -> PageFrame? {
        let i = index
        return i + 1 < spreads.count ? show(i + 1) : nil
    }

    func previous() async throws -> PageFrame? {
        let i = index
        return i > 0 ? show(i - 1) : nil
    }

    /// 마지막 판인가(더 넘기면 권 끝 판).
    func atEnd() -> Bool { index >= spreads.count - 1 }

    func seek(_ percent: Double) async throws -> PageFrame {
        page = Int((min(max(percent, 0), 100) / 100 * Double(pageCount - 1)).rounded())
        return show(index)
    }

    func go(_ p: Int) -> PageFrame {
        page = min(max(p, 0), pageCount - 1)
        return show(index)
    }

    func peek(_ step: Int) async -> UIImage? {
        let i = index + step
        guard spreads.indices.contains(i) else { return nil }
        return render(spreads[i])
    }

    func toggleBookmark() async throws -> PageFrame {
        let shown = spreads.isEmpty ? [page] : spreads[index]
        _ = store.toggleComicBookmark(unitId: unitId, pages: shown.map { KotlinInt(int: Int32($0)) })
        return show(index)
    }

    func removeBookmark(_ id: Int64) async throws -> PageFrame {
        _ = store.toggleComicBookmark(unitId: unitId, pages: [KotlinInt(int: Int32(id))])
        return show(index)
    }

    /// 책갈피는 쪽(발췌 글이 없다). id 는 그 쪽.
    func bookmarks() async throws -> [BookmarkItem] {
        store.comicBookmarks(unitId: unitId).map { n in
            let p = Int(truncating: n)
            return BookmarkItem(id: Int64(p), snippet: "\(p + 1)쪽", percent: Double(p + 1) * 100 / Double(pageCount), section: "",
                                created: Date(), page: p)
        }
    }

    func goToBookmark(_ id: Int64) async throws -> PageFrame? { go(Int(id)) }
    func contents() async throws -> (rows: [ContentsItem], current: Int) { ([], -1) }
    func goToContents(_ index: Int) async throws -> PageFrame? { nil }

    /// 쪽 목록의 작은 그림.
    func thumbnail(_ p: Int, height: CGFloat) -> UIImage? {
        guard let data = book.data(i: Int32(p)) else { return nil }
        return ComicImages.decode(data as Data, maxPixel: height * UIScreen.main.scale)
    }

    /// 확대: 지금 쪽(한 쪽 판만)을 화면 해상도 3배까지.
    func zoomed(_ p: Int, size: CGSize, zoom: CGFloat) -> UIImage? {
        guard let data = book.data(i: Int32(p)) else { return nil }
        return ComicImages.decode(data as Data, maxPixel: max(size.width, size.height) * zoom * UIScreen.main.scale)
    }

    /// 지금 쪽이 화면에 놓인 자리(확대가 거기서 시작한다).
    func pageRect() -> CGRect? {
        guard let spec, let img = image(page) else { return nil }
        let r = PdfPages.fit(img.size, in: spec.area)
        let phone = UIDevice.current.userInterfaceIdiom == .phone
        return CGRect(x: r.minX, y: phone ? spec.area.minY : spec.area.maxY - r.height, width: r.width, height: r.height)
    }

    nonisolated func close() {
        book.close()
    }
}

enum ComicOpenError: Error {
    case broken, missing

    var message: String {
        switch self {
        case .broken: "만화 파일이 손상됐거나 그림이 없습니다. 다른 곳에서 다시 받아 보세요."
        case .missing: "파일을 찾을 수 없습니다. 옮겨졌거나 지워졌을 수 있습니다. 서재에서 새로고침해 주세요."
        }
    }
}
