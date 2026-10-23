import CoreImage
import ImageIO
import OloCore
import PDFKit
import SwiftUI
import UIKit

/// 만화의 쪽 밖 바탕(안드로이드 `COMIC_BACKDROP` 0x141311) — 쪽 그림 둘레가 이 색이고, 메뉴도 이 밝기를 따라 어둡다.
let comicPaper = Paper(paper: 0x141311, ink: 0xE8E3DA, inkMuted: 0xB9B2A8, dark: true)

/// 만화 뷰어가 쪽 그림을 받는 곳(구상안 46): 압축 · 그림 폴더 · 그림책은 그림 바이트를 솎아 풀고, PDF 는 쪽을 그린다. 뷰어의 판
/// 짜기 · 자르기 · 색은 어느 쪽이든 같다.
protocol ComicPageSource: AnyObject, Sendable {
    var pageCount: Int { get }
    /// 쪽마다 크기(Kotlin 목록 그대로 — 짝짓기에 다시 넘긴다). 모르는 쪽은 비어 있다.
    func sizes() -> [Any]
    /// [i] 쪽을 긴 변 [maxPixel] 안으로. 못 읽으면 nil — 그 쪽만 "그리지 못했습니다".
    func image(_ i: Int, maxPixel: CGFloat) -> UIImage?
    func close()
}

/// 압축 · 그림 폴더 · 그림책 EPUB 의 그림.
final class ArchivePages: ComicPageSource, @unchecked Sendable {
    private let book: IosComicBook
    init(_ book: IosComicBook) { self.book = book }
    var pageCount: Int { Int(book.pageCount) }
    func sizes() -> [Any] { ComicImages.sizes(book) }
    func image(_ i: Int, maxPixel: CGFloat) -> UIImage? {
        book.data(i: Int32(i)).flatMap { ComicImages.decode($0 as Data, maxPixel: maxPixel) }
    }
    func close() { book.close() }
}

/// PDF 의 쪽을 그림으로(구상안 46 ②, 안드로이드 `PdfBook.renderPage`): 흰 바탕에 쪽 전체를 긴 변 [maxPixel] 안으로. 지면색은 섞지
/// 않는다 — 만화 뷰어는 늘 어두운 바탕이고, 색 보정은 뷰어가 건다.
final class PdfComicPages: ComicPageSource, @unchecked Sendable {
    private let doc: PDFDocument
    init?(url: URL) {
        guard let doc = PDFDocument(url: url), !doc.isLocked, doc.pageCount > 0 else { return nil }
        self.doc = doc
    }
    var pageCount: Int { doc.pageCount }
    func sizes() -> [Any] {
        (0..<doc.pageCount).map { i -> Any in
            guard let page = doc.page(at: i) else { return NSNull() }
            let s = PdfPages.size(of: page)
            return ImageSize(width: Int32(s.width.rounded()), height: Int32(s.height.rounded()))
        }
    }
    func image(_ i: Int, maxPixel: CGFloat) -> UIImage? {
        guard let page = doc.page(at: i) else { return nil }
        let s = PdfPages.size(of: page)
        let k = maxPixel / max(s.width, s.height, 1)
        let target = CGSize(width: max((s.width * k).rounded(), 1), height: max((s.height * k).rounded(), 1))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { ctx in
            PdfPages.draw(page, in: CGRect(origin: .zero, size: target), cg: ctx.cgContext, paper: comicPaper, blend: false)
        }
    }
    func close() {}
}

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

    /// 쪽마다 그림 크기(공통 `ImageHeader` 가 머리로 읽은 것). 머리로 못 읽은 쪽은 ImageIO 에 픽셀 크기만 묻는다(안드로이드 0.55.2 의
    /// `ComicPages.size` 와 같은 대비) — 크기를 모르면 펼침면도 세로 쪽으로 쳐 나누지 않아, 두 쪽 중 한 쪽을 건너뛴 것처럼 보였다.
    static func sizes(_ book: IosComicBook) -> [Any] {
        var raw = book.sizes()
        for i in raw.indices where !(raw[i] is ImageSize) {
            guard let data = book.data(i: Int32(i)), let src = CGImageSourceCreateWithData(data as CFData, nil),
                  let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
                  let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int, w > 0, h > 0 else { continue }
            // 돌려 찍은 그림(방향 5~8)은 가로 · 세로가 바뀐다.
            let turned = ((props[kCGImagePropertyOrientation] as? Int) ?? 1) >= 5
            raw[i] = ImageSize(width: Int32(turned ? h : w), height: Int32(turned ? w : h))
        }
        return raw
    }

    /// 펼침면의 반([half])을 떼고 여백 상자([frame], 조각 크기에 대한 비율)대로 자른다. 자를 것이 없거나 실패하면 그대로.
    static func cut(_ img: UIImage, half: SpreadSplit.Half, frame: MarginTrim.Frame) -> UIImage {
        guard let cg = img.cgImage else { return img }
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        var part = CGRect(x: 0, y: 0, width: w, height: h)
        if half == .left { part.size.width = (w / 2).rounded(.down) }
        if half == .right { part = CGRect(x: (w / 2).rounded(.down), y: 0, width: w - (w / 2).rounded(.down), height: h) }
        let rect = CGRect(x: part.minX + part.width * CGFloat(frame.left), y: part.minY + part.height * CGFloat(frame.top),
                          width: part.width * CGFloat(frame.width), height: part.height * CGFloat(frame.height)).integral
        guard rect.width >= 1, rect.height >= 1, rect != CGRect(x: 0, y: 0, width: w, height: h),
              let out = cg.cropping(to: rect) else { return img }
        return UIImage(cgImage: out)
    }

    /// 여백 상자 찾기: 폭 360 으로 줄인 RGBA 사본을 공통 `MarginTrim` 에 넘긴다. 실패하면 nil(자르지 않는다).
    static func marginFrame(_ img: UIImage) -> MarginTrim.Frame? {
        guard let cg = img.cgImage, cg.width > 0, cg.height > 0 else { return nil }
        let scale = min(1, CGFloat(IosComicLayout.shared.TRIM_PROBE) / CGFloat(cg.width))
        let w = max(Int(CGFloat(cg.width) * scale), 1), h = max(Int(CGFloat(cg.height) * scale), 1)
        let row = w * 4
        var bytes = [UInt8](repeating: 0, count: row * h)
        let drawn = bytes.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: row,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        return IosComicLayout.shared.frameOfRgba(width: Int32(w), height: Int32(h), bytesPerRow: Int32(row), rgba: Data(bytes))
    }

    /// 셈은 sRGB(감마) 그대로 — CoreImage 의 기본(선형)에서 행렬을 걸면 안드로이드보다 중간 밝기가 훨씬 밝아진다.
    private static let context = CIContext(options: [.cacheIntermediates: false,
                                                     .workingColorSpace: CGColorSpace(name: CGColorSpace.sRGB) as Any])

    /// 색 보정(끔 · 선명하게 · 흑백 — 행렬은 Kotlin `IosComicLayout.colorMatrix`, 안드로이드와 같은 값). 끔이면 그대로.
    static func colored(_ img: UIImage, mode: Int) -> UIImage {
        guard let m = IosComicLayout.shared.colorMatrix(mode: Int32(mode))?.map({ CGFloat(truncating: $0) }), m.count == 20,
              let cg = img.cgImage else { return img }
        let filter = CIFilter(name: "CIColorMatrix")
        filter?.setValue(CIImage(cgImage: cg), forKey: kCIInputImageKey)
        filter?.setValue(CIVector(x: m[0], y: m[1], z: m[2], w: m[3]), forKey: "inputRVector")
        filter?.setValue(CIVector(x: m[5], y: m[6], z: m[7], w: m[8]), forKey: "inputGVector")
        filter?.setValue(CIVector(x: m[10], y: m[11], z: m[12], w: m[13]), forKey: "inputBVector")
        filter?.setValue(CIVector(x: m[15], y: m[16], z: m[17], w: m[18]), forKey: "inputAVector")
        filter?.setValue(CIVector(x: m[4], y: m[9], z: m[14], w: m[19]), forKey: "inputBiasVector")
        guard let output = filter?.outputImage?.cropped(to: CGRect(x: 0, y: 0, width: cg.width, height: cg.height)),
              let out = context.createCGImage(output, from: output.extent) else { return img }
        return UIImage(cgImage: out)
    }
}

/// 만화를 그릴 화면.
struct ComicViewSpec: Equatable {
    let size: CGSize
    let insets: EdgeInsets
    let twoPages: Bool
    let coverAlone: Bool
    let rightToLeft: Bool
    /// 쪽 다듬기(구상안 38): 여백 자르기 · 펼침면 나누기(작품 값이 먼저) · 색 보정(0 끔 · 1 선명하게 · 2 흑백).
    var trim = false
    var split = true
    var color = 0

    /// 쪽을 놓는 칸: 노치를 피하고, 아래 줄이 없으니 화면 끝까지.
    var area: CGRect {
        CGRect(x: insets.leading, y: insets.top, width: size.width - insets.leading - insets.trailing,
               height: size.height - insets.top - max(insets.bottom, 0))
    }
}

/// 쪽 목록에 놓을 것(구상안 38 · 2A): 펼침면은 한 줄을 다 쓰고, 나누는 동안은 두 반을 따로 고른다.
struct ComicGridInfo {
    let wide: [Bool]
    let aspects: [CGFloat]
    let split: Bool
    let current: Int
    let currentHalf: SpreadSplit.Half
}

/// 만화 한 권(구상안 36 · 38, 안드로이드 `ComicReader` 쪽 넘김). 판(화면에 한 번에 보이는 조각들) · 짝짓기 · 나누기 · 여백 상자는 공통 ·
/// Kotlin(`IosComicLayout`), 그림 풀기 · 자르기 · 색은 ImageIO · CoreGraphics · CoreImage.
/// 진도는 판의 **마지막 쪽**으로 적는다 — 두 쪽 판이면 오른쪽(오→왼이면 왼쪽) 쪽까지 읽은 것이다(안드로이드와 같다). 펼침면의 반을 보는
/// 중이면 그 반(실제 쪽 LEFT · RIGHT)도 적는다.
actor ComicEngine: BookEngine, ZoomSource {
    private let source: ComicPageSource
    private let store: ReadingStore
    /// 자리 · 책갈피를 적는 곳 — 만화 권은 권의 기록, 그림책은 책의 기록(구상안 46).
    private let place: IosComicPlace
    let unitId: String
    nonisolated let title: String
    nonisolated let author: String? = nil
    nonisolated let pageCount: Int
    nonisolated var isComic: Bool { true }

    /// 쪽마다 그림 크기(Kotlin 목록 그대로 — 짝짓기에 다시 넘긴다). 모르는 쪽은 비어 있다.
    private var rawSizes: [Any] = []
    private var sizes: [ImageSize?] = []
    private var units: [[SpreadSplit.Part]] = []
    /// 지금 판의 첫 쪽과 반(설정이 바뀌어 판이 달라져도 이 자리가 든 판으로).
    private var page = 0
    private var half: SpreadSplit.Half = .whole
    private var spec: ComicViewSpec?
    /// 화면 크기로 푼 쪽 그림(자르기 · 색 전).
    private var images: [Int: UIImage] = [:]
    /// 쪽마다 찾은 여백 상자(한 번 찾으면 둔다 — 그림 크기와 상관없는 비율이다).
    private var frames: [Int: MarginTrim.Frame] = [:]
    private var broken = Set<Int>()

    init(file: Library.ComicFile, unitId: String, title: String, store: ReadingStore) throws {
        let opened: IosComicBook? = if let names = file.images {
            IosComicBook.companion.openFolder(path: file.url.path, fileNames: names)
        } else {
            IosComicBook.companion.openArchive(path: file.url.path)
        }
        guard let opened else { throw ComicOpenError.broken }
        try self.init(source: ArchivePages(opened), place: UnitComicPlace(store: store, unitId: unitId), unitId: unitId, title: title, store: store)
    }

    /// 그림책 EPUB 의 그림들을 만화로(구상안 46). 자리는 책의 것.
    init(picturesOf path: String, pictures: PictureBook, bookId: String, title: String, store: ReadingStore) throws {
        guard let opened = IosComicBook.companion.openPictures(path: path, paths: pictures.pages.map(\.path)) else { throw ComicOpenError.broken }
        try self.init(source: ArchivePages(opened), place: BookComicPlace(store: store, bookId: bookId, pages: pictures.pages), unitId: bookId, title: title, store: store)
    }

    /// PDF 를 만화로(구상안 46 ②) — 쪽을 그려서. 자리는 PDF 의 쪽 자리. 암호 · 깨진 PDF 는 열지 않는다(부르는 쪽이 PDF 보기로 연다).
    init(pdf url: URL, bookId: String, title: String, store: ReadingStore) throws {
        guard let pages = PdfComicPages(url: url) else { throw ComicOpenError.broken }
        try self.init(source: pages, place: PdfComicPlace(store: store, bookId: bookId, pageCount: Int32(pages.pageCount)), unitId: bookId, title: title, store: store)
    }

    private init(source opened: ComicPageSource, place: IosComicPlace, unitId: String, title: String, store: ReadingStore) throws {
        source = opened
        self.store = store
        self.place = place
        self.unitId = unitId
        self.title = title
        pageCount = Int(opened.pageCount)
        if let start = place.start(pageCount: Int32(pageCount)) {
            page = min(max(Int(start.page), 0), pageCount - 1)
            half = start.half
        }
    }

    // MARK: 판

    private func arrange() {
        if rawSizes.isEmpty {
            rawSizes = source.sizes()
            sizes = rawSizes.map { $0 as? ImageSize }
        }
        guard let spec, pageCount > 0 else { units = (0..<max(pageCount, 1)).map { [SpreadSplit.Part(page: Int32($0), half: .whole)] }; return }
        units = IosComicLayout.shared.units(sizes: rawSizes, twoPages: spec.twoPages, coverAlone: spec.coverAlone,
                                            split: spec.split, rightToLeft: spec.rightToLeft)
    }

    private var index: Int { Int(IosComicLayout.shared.indexOf(units: units, page: Int32(page), half: half)) }

    func layOut(_ spec: ComicViewSpec) async -> PageFrame {
        let old = self.spec
        let regroup = old?.twoPages != spec.twoPages || old?.coverAlone != spec.coverAlone || old?.split != spec.split
            || old?.rightToLeft != spec.rightToLeft
        if regroup || old?.size != spec.size || old?.trim != spec.trim || old?.color != spec.color { images = [:] }
        self.spec = spec
        if regroup || units.isEmpty { arrange() }
        return show(index)
    }

    private func pages(_ unit: [SpreadSplit.Part]) -> [Int] {
        var seen: [Int] = []
        for p in unit.map({ Int($0.page) }) where !seen.contains(p) { seen.append(p) }
        return seen
    }

    private func show(_ i: Int) -> PageFrame {
        let i = min(max(i, 0), max(units.count - 1, 0))
        let unit = units.isEmpty ? [SpreadSplit.Part(page: Int32(page), half: .whole)] : units[i]
        let shown = pages(unit)
        page = shown.first ?? 0
        half = unit.count == 1 ? unit[0].half : .whole
        let last = shown.max() ?? page
        let fraction = place.save(first: Int32(page), last: Int32(last), pageCount: Int32(pageCount), atEnd: i >= units.count - 1, half: half)
        let marks = Set(place.marks().map { Int(truncating: $0) })
        var f = PageFrame(pdfImage: render(unit), page: page, pages: pageCount, percent: Double(fraction) * 100,
                          bookmarked: !marks.isDisjoint(with: shown), hasRight: shown.count > 1, section: "", pageLabel: nil, pagesLeft: 0)
        f.isPdf = false
        f.isComic = true
        f.brokenPages = shown.filter { broken.contains($0) }
        f.halfLabel = IosComicLayout.shared.halfLabel(half: half)
        return f
    }

    /// 쪽 그림(화면 높이에 맞춘 크기로 솎아 풀어 둔다 — 앞뒤 판까지만 남긴다).
    private func image(_ p: Int) -> UIImage? {
        if let img = images[p] { return img }
        guard let spec else { broken.insert(p); return nil }
        let px = max(spec.size.width, spec.size.height) * UIScreen.main.scale
        guard let img = source.image(p, maxPixel: px) else { broken.insert(p); return nil }
        broken.remove(p)
        if images.count > 8 {
            let keep = Set((units.indices.filter { abs($0 - index) <= 1 }).flatMap { pages(units[$0]) })
            images = images.filter { keep.contains($0.key) }
        }
        images[p] = img
        return img
    }

    /// 쪽의 여백 상자. 자르기가 꺼져 있으면 쪽 전체. 처음 보는 쪽은 줄인 사본에서 찾는다(안드로이드 `TRIM_PROBE`).
    private func frame(_ p: Int, from img: UIImage?) -> MarginTrim.Frame {
        guard spec?.trim == true else { return MarginTrim.Frame.companion.WHOLE }
        if let f = frames[p] { return f }
        guard let img, let f = ComicImages.marginFrame(img) else { return MarginTrim.Frame.companion.WHOLE }
        frames[p] = f
        return f
    }

    /// 판의 조각마다 다듬은 그림: 반이면 그 반, 여백 상자대로 자르고, 색 보정을 건다.
    private func pieces(_ unit: [SpreadSplit.Part], source: (Int) -> UIImage?) -> [UIImage?] {
        let raw = unit.map { source(Int($0.page)) }
        let found = zip(unit, raw).map { frame(Int($0.0.page), from: $0.1) }
        let boxes = IosComicLayout.shared.partFrames(parts: unit, frames: found)
        return zip(zip(unit, raw), boxes).map { pair, box in
            guard let img = pair.1 else { return nil }
            return ComicImages.colored(ComicImages.cut(img, half: pair.0.half, frame: box), mode: spec?.color ?? 0)
        }
    }

    /// 판 한 장: 어두운 바탕 위에 쪽. 휴대폰은 위에 붙이고(남는 높이는 아래로), 두 쪽이면 책등에 붙인 둘 — 오→왼이면 앞 쪽이
    /// 오른쪽. 혼자인 쪽은 공통 `SpreadSlot` 자리(표지는 뒤 쪽 자리, 혼자 남은 쪽은 앞 쪽 자리, 펼침면은 가운데).
    private func render(_ unit: [SpreadSplit.Part]) -> UIImage {
        guard let spec else { return UIImage() }
        let shown = unit.map { Int($0.page) }
        let imgs = pieces(unit) { image($0) }
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
                    let (l, r) = spec.rightToLeft ? (imgs[1], imgs[0]) : (imgs[0], imgs[1])
                    place(l, in: left, align: -1)
                    place(r, in: right, align: 1)
                } else if let only = shown.first {
                    let size = sizes.indices.contains(only) ? sizes[only] : nil
                    let wide = size.map { SpreadSplit.shared.isWide(size: $0) } ?? false
                    let slot = SpreadSlot.companion.of(pages: [KotlinInt(int: Int32(only))], first: only == 0, wide: wide, coverAlone: spec.coverAlone)
                    if slot == .center {
                        place(imgs.first ?? nil, in: area, align: 0)
                    } else if slot.onLeft(rightToLeft: spec.rightToLeft) {
                        place(imgs.first ?? nil, in: left, align: -1)
                    } else {
                        place(imgs.first ?? nil, in: right, align: 1)
                    }
                }
            } else {
                place(imgs.first ?? nil, in: area, align: 0)
            }
        }
    }

    /// 쪽마다 그림 크기(Kotlin 목록 그대로 — 보는 방식 판별 · 웹툰 배치에 넘긴다).
    func rawSizeList() -> [Any] {
        if rawSizes.isEmpty {
            rawSizes = source.sizes()
            sizes = rawSizes.map { $0 as? ImageSize }
        }
        return rawSizes
    }

    /// 다른 화면(웹툰)이 진도를 옮겼다 — 저장된 자리에서 다시.
    func resume() {
        guard let saved = store.comicProgress()[unitId] else { return }
        page = min(max(Int(saved.page), 0), max(pageCount - 1, 0))
        half = saved.half ?? .whole
    }

    // MARK: BookEngine

    func next() async throws -> PageFrame? {
        let i = index
        return i + 1 < units.count ? show(i + 1) : nil
    }

    func previous() async throws -> PageFrame? {
        let i = index
        return i > 0 ? show(i - 1) : nil
    }

    /// 마지막 판인가(더 넘기면 권 끝 판).
    func atEnd() -> Bool { index >= units.count - 1 }

    func seek(_ percent: Double) async throws -> PageFrame {
        page = Int((min(max(percent, 0), 100) / 100 * Double(pageCount - 1)).rounded())
        half = .whole
        return show(index)
    }

    /// [p] 쪽으로(쪽 목록 · 책갈피 · 권 이어 읽기). [half] 는 펼침면의 어느 반인가 — 없으면 그 쪽의 첫 판.
    func go(_ p: Int, half: SpreadSplit.Half = .whole) -> PageFrame {
        page = min(max(p, 0), pageCount - 1)
        self.half = half
        return show(index)
    }

    /// 만화 권은 쪽 · 펼침면의 반, 만화로 연 책(그림책 · PDF)은 책의 자리를 이 뷰어의 쪽으로 바꿔서.
    func goToPlace(_ place: SyncPlace) async throws -> PageFrame? {
        if let encoded = place.locator, let book = self.place as? BookPlace {
            guard let locator = LocatorCompanion.shared.decodeOrNull(encoded: encoded), let p = book.pageOf(locator: locator) else { return nil }
            return go(Int(truncating: p))
        }
        guard let p = place.page else { return nil }
        let half = SpreadSplit.Half.entries.first { $0.name == place.half && $0 != .whole } ?? .whole
        return go(p, half: half)
    }

    func peek(_ step: Int) async -> UIImage? {
        let i = index + step
        guard units.indices.contains(i) else { return nil }
        return render(units[i])
    }

    func toggleBookmark() async throws -> PageFrame {
        let shown = units.isEmpty ? [page] : pages(units[index])
        _ = place.toggle(pages: shown.map { KotlinInt(int: Int32($0)) })
        return show(index)
    }

    func removeBookmark(_ id: Int64) async throws -> PageFrame {
        _ = place.toggle(pages: [KotlinInt(int: Int32(id))])
        return show(index)
    }

    /// 책갈피는 쪽(발췌 글이 없다). id 는 그 쪽.
    func bookmarks() async throws -> [BookmarkItem] {
        place.marks().map { n in
            let p = Int(truncating: n)
            return BookmarkItem(id: Int64(p), snippet: "\(p + 1)쪽", percent: Double(p + 1) * 100 / Double(pageCount), section: "",
                                created: Date(), page: p)
        }
    }

    func goToBookmark(_ id: Int64) async throws -> PageFrame? { go(Int(id)) }
    func contents() async throws -> (rows: [ContentsItem], current: Int) { ([], -1) }
    func goToContents(_ index: Int) async throws -> PageFrame? { nil }

    /// 쪽 목록에 놓을 것: 쪽마다 펼침면인가 · 그림 비(크기를 모르면 흔한 비).
    func gridInfo() -> ComicGridInfo {
        if rawSizes.isEmpty { arrange() }
        let wide = (0..<pageCount).map { p in sizes.indices.contains(p) && SpreadSplit.shared.isWide(size: sizes[p]) }
        let aspects = (0..<pageCount).map { p -> CGFloat in
            guard sizes.indices.contains(p), let s = sizes[p], s.height > 0 else { return wide[p] ? 1.43 : 0.7 }
            return CGFloat(s.width) / CGFloat(s.height)
        }
        // 두 쪽 보기는 펼침면을 나누지 않는다 — 반 칸을 내면 고른 반으로 갈 수 없다.
        return ComicGridInfo(wide: wide, aspects: aspects, split: (spec?.split ?? true) && spec?.twoPages != true, current: page, currentHalf: half)
    }

    /// 쪽 목록의 작은 그림. [half] 면 그 반만.
    func thumbnail(_ p: Int, height: CGFloat, half: SpreadSplit.Half = .whole) -> UIImage? {
        guard let img = source.image(p, maxPixel: height * 2 * UIScreen.main.scale) else { return nil }
        return half == .whole ? img : ComicImages.cut(img, half: half, frame: MarginTrim.Frame.companion.WHOLE)
    }

    /// 확대: 지금 판(한 조각만)을 화면 해상도 3배까지 — 보이는 그대로(반 · 여백 · 색) 크게.
    func zoomed(_ p: Int, size: CGSize, zoom: CGFloat) -> UIImage? {
        guard units.indices.contains(index), let unit = Optional(units[index]), unit.count == 1 else { return nil }
        let px = max(size.width, size.height) * zoom * UIScreen.main.scale * (unit[0].half == .whole ? 1 : 2)
        return pieces(unit) { q in source.image(q, maxPixel: px) }.first ?? nil
    }

    /// 표지 고르기(구상안 44 ④): 지금 판의 앞 조각 — 쪽 · 보이는 반 · 통째로 보이는 넓은 쪽인가 · 화면 위 자리. 자리는 한 쪽 보기에
    /// 한 조각일 때만(두 쪽 보기는 쪽마다 자리가 달라 표시를 긋지 않는다).
    func shownPart() -> (page: Int, half: SpreadSplit.Half, wide: Bool, rect: CGRect?) {
        guard units.indices.contains(index), let first = units[index].first else { return (page, .whole, false, nil) }
        let p = Int(first.page)
        let wide = first.half == .whole && sizes.indices.contains(p) && sizes[p].map { SpreadSplit.shared.isWide(size: $0) } == true
        let single = units[index].count == 1 && spec?.twoPages != true
        return (p, first.half, wide, single ? pageRect() : nil)
    }

    /// 표지로 쓸 그림: 쪽 [p] 의 [half](여백 자르기 · 색 보정 없이 — 표지는 책의 그림 그대로). 넓은 쪽의 반도 높이 480 이 나오게
    /// 긴 변 1920 으로 푼다.
    func coverImage(_ p: Int, half: SpreadSplit.Half) -> UIImage? {
        guard let img = source.image(p, maxPixel: 1920) else { return nil }
        return half == .whole ? img : ComicImages.cut(img, half: half, frame: MarginTrim.Frame.companion.WHOLE)
    }

    /// 지금 쪽이 화면에 놓인 자리(확대가 거기서 시작한다).
    func pageRect() -> CGRect? {
        guard let spec, units.indices.contains(index), let img = pieces(units[index], source: { image($0) }).first ?? nil else { return nil }
        let r = PdfPages.fit(img.size, in: spec.area)
        let phone = UIDevice.current.userInterfaceIdiom == .phone
        return CGRect(x: r.minX, y: phone ? spec.area.minY : spec.area.maxY - r.height, width: r.width, height: r.height)
    }

    nonisolated func close() {
        source.close()
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
