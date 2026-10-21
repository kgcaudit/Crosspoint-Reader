import OloCore
import PDFKit
import SwiftUI
import UIKit

/// PDF 쪽 맞춤(구상안 32A · 33, 안드로이드 `PdfFit`): 쪽 전체(기본) · 폭 · 이어서. 앱 전체에 하나다(안드로이드와 같다).
enum PdfFit: Int { case page = 0, width = 1, continuous = 2 }

/// PDF 를 그릴 화면: 크기 · 안전 영역 · 지면 · 쪽 맞춤 · 두 쪽.
struct PdfViewSpec: Equatable {
    let size: CGSize
    let insets: EdgeInsets
    let paper: Paper
    /// 그림 흰 바탕을 지면색으로(쪽 전체를 지면색에 녹인다).
    let blend: Bool
    let fit: PdfFit
    /// 두 쪽 보기(쪽 전체에서만 — 화면이 정한다).
    let twoPages: Bool
    /// 표지를 혼자 두는가(책마다 고른 값 → 기본값).
    let coverAlone: Bool

    /// 쪽을 놓을 곳. 노치 · 다이내믹 아일랜드를 피하고, 아래는 아래 줄(화면 끝 14 + 두 줄) 자리를 비운다.
    var pageArea: CGRect {
        let top = max(insets.top, 8)
        let bottom: CGFloat = 44
        return CGRect(x: insets.leading, y: top, width: size.width - insets.leading - insets.trailing,
                      height: max(size.height - top - bottom, 1))
    }
}

/// PDFKit 으로 쪽을 그림으로. 쪽은 언제나 흰 바탕에 그리고(투명 바탕 PDF 가 지면색과 섞이지 않게), 지면에 맞춰 칠한다.
enum PdfPages {
    /// 쪽의 크기(점). 회전을 따른다. 재지 못하면 A판형(1 : 1.414).
    static func size(of page: PDFPage) -> CGSize {
        let box = page.bounds(for: .mediaBox)
        guard box.width > 0, box.height > 0 else { return CGSize(width: 1, height: 1.4142) }
        return page.rotation % 180 == 0 ? box.size : CGSize(width: box.height, height: box.width)
    }

    /// 쪽 하나를 [rect] 에 그린다(흰 바탕 → 쪽 → 지면색). [cg] 는 UIKit 좌표(왼쪽 위가 0).
    static func draw(_ page: PDFPage, in rect: CGRect, cg: CGContext, paper: Paper, blend: Bool) {
        cg.saveGState()
        cg.setFillColor(UIColor.white.cgColor)
        cg.fill(rect)
        // PDF 좌표(왼쪽 아래가 0)로 뒤집어 그 칸에 맞춘다.
        cg.translateBy(x: rect.minX, y: rect.maxY)
        cg.scaleBy(x: 1, y: -1)
        let s = size(of: page)
        cg.scaleBy(x: rect.width / s.width, y: rect.height / s.height)
        page.draw(with: .mediaBox, to: cg)
        cg.restoreGState()
        guard blend else { return }
        if paper.dark {
            // 검은 지면은 뒤집지 않고 어둡게만(안드로이드 DARK_DIM 0.78) — 뒤집으면 사진 · 그림이 음화가 된다.
            cg.setFillColor(UIColor.black.withAlphaComponent(0.22).cgColor)
            cg.fill(rect)
        } else if paper.paper != 0xFFFFFF {
            // 밝은 지면: 지면색을 곱한다 — 흰 종이가 지면색이 되고 글자는 그대로 검다.
            cg.saveGState()
            cg.setBlendMode(.multiply)
            cg.setFillColor(UIColor(paper.paperColor).cgColor)
            cg.fill(rect)
            cg.restoreGState()
        }
    }

    /// [size] 를 [area] 안에 비율을 지켜 가장 크게. [align] −1 왼쪽 붙임(오른쪽 끝을 책등에) · 0 가운데 · 1 오른쪽 붙임.
    static func fit(_ size: CGSize, in area: CGRect, align: Int = 0) -> CGRect {
        let k = min(area.width / size.width, area.height / size.height)
        let w = size.width * k, h = size.height * k
        let x: CGFloat = align < 0 ? area.maxX - w : align > 0 ? area.minX : area.midX - w / 2
        return CGRect(x: x, y: area.midY - h / 2, width: w, height: h)
    }

    /// 서재 표지: 첫 쪽을 흰 바탕으로(안드로이드 `coverImage`). 읽을 수 없으면 nil — 이름 표지가 대신 선다.
    static func cover(url: URL, height: CGFloat = 480) -> UIImage? {
        guard let doc = PDFDocument(url: url), let page = doc.page(at: 0) else { return nil }
        if doc.isLocked && !doc.unlock(withPassword: "") { return nil }
        let s = size(of: page)
        let target = CGSize(width: height * s.width / s.height, height: height)
        let format = UIGraphicsImageRendererFormat()
        format.opaque = true
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { ctx in
            draw(page, in: CGRect(origin: .zero, size: target), cg: ctx.cgContext, paper: .light, blend: false)
        }
    }
}

/// PDF 를 열지 못한 까닭(안드로이드 `describeOpenFailure`).
enum PdfOpenError: Error {
    case locked, broken, empty

    var message: String {
        switch self {
        case .locked: "암호가 걸린 PDF입니다. 암호를 푼 파일로 다시 열어 주세요."
        case .broken: "PDF 파일이 손상됐거나 PDF 형식이 아닙니다. 다른 곳에서 다시 받아 보세요."
        case .empty: "쪽이 하나도 없는 파일입니다."
        }
    }
}

/// PDF 한 권(구상안 32A · 33). 쪽은 PDFKit 으로 그리고, 기록 · 목차 · 쪽 이름표는 Kotlin `IosPdfBook`(안드로이드와 같은 형식).
/// 쪽 전체는 EPUB 과 같은 길(한 장 그림 · 넘김 효과 · 말림)로 낸다.
actor PdfEngine: BookEngine {
    private let doc: PDFDocument
    let book: IosPdfBook
    nonisolated let title: String
    nonisolated let author: String?
    nonisolated let pageCount: Int
    nonisolated var isPdf: Bool { true }

    /// 지금 쪽(두 쪽이면 펼침의 왼쪽)과 그 쪽 안 세로 자리(폭 맞춤 · 이어서).
    private(set) var page = 0
    private(set) var yPermille = 0
    private var spec: PdfViewSpec?

    init(url: URL, bookId: String, displayName: String, store: ReadingStore) async throws {
        guard let doc = PDFDocument(url: url) else { throw PdfOpenError.broken }
        // 소유자 암호만 걸린 파일은 빈 암호로 열린다(안드로이드와 같다). 사용자 암호면 묻지 않고 알린다(구상안 32A).
        if doc.isLocked && !doc.unlock(withPassword: "") { throw PdfOpenError.locked }
        guard doc.pageCount > 0 else { throw PdfOpenError.empty }
        self.doc = doc
        pageCount = doc.pageCount
        book = IosPdfBook.companion.open(path: url.path, bookId: bookId, displayName: displayName, pageCount: Int32(doc.pageCount), store: store)
        title = book.title
        author = book.author
        let place = try await book.restore()
        page = Int(place.page)
        yPermille = Int(place.yPermille)
    }

    // MARK: 펼침

    /// 이 쪽이 든 펼침의 쪽들(안드로이드 `spreadPages`). 표지 따로면 0 혼자, 1–2, 3–4 … · 함께면 0–1, 2–3 ….
    private func spread(_ p: Int) -> [Int] {
        guard let spec, spec.twoPages else { return [p] }
        let start: Int
        if spec.coverAlone { start = p == 0 ? 0 : p - (p - 1) % 2 } else { start = p - p % 2 }
        if spec.coverAlone && start == 0 { return [0] }
        return start + 1 < pageCount ? [start, start + 1] : [start]
    }

    private func nextStart(_ p: Int, _ step: Int) -> Int? {
        let shown = spread(p)
        if step > 0 {
            let n = (shown.last ?? p) + 1
            return n < pageCount ? n : nil
        }
        let n = (shown.first ?? p) - 1
        return n >= 0 ? spread(n).first : nil
    }

    // MARK: 쪽 내기

    func layOut(_ spec: PdfViewSpec) async throws -> PageFrame {
        self.spec = spec
        return try await show(page, y: yPermille)
    }

    private func show(_ p: Int, y: Int = 0) async throws -> PageFrame {
        shownCenter = nil
        let shown = spread(min(max(p, 0), pageCount - 1))
        page = shown.first ?? 0
        yPermille = y
        let last = shown.last ?? page
        let percent = try await book.save(page: Int32(page), yPermille: Int32(y), lastShown: Int32(last))
        let marked = try await book.isBookmarked(first: Int32(page), last: Int32(last)).boolValue
        let image = render(shown)
        lastImage = image
        var f = frame(shown, percent: Double(percent.floatValue), bookmarked: marked, image: image)
        f.pdfY = y
        return f
    }

    private func frame(_ shown: [Int], percent: Double, bookmarked: Bool, image: UIImage) -> PageFrame {
        let first = shown.first ?? 0, last = shown.last ?? first
        let label = book.label(page: Int32(first))
        return PageFrame(pdfImage: image, page: first, pages: pageCount, percent: percent, bookmarked: bookmarked,
                         hasRight: shown.count > 1, section: book.section(page: Int32(first)), pageLabel: label,
                         pagesLeft: Int(book.pagesLeftInSection(lastShown: Int32(last))))
    }

    /// 화면 한 장: 지면 위에 쪽(두 쪽이면 책등에 붙인 둘, 혼자인 쪽은 `SpreadSlot` 자리). 폭 맞춤이면 쪽 폭을 화면 폭에 맞춘
    /// 긴 그림 한 장(화면이 위아래로 옮겨 보인다).
    private func render(_ shown: [Int]) -> UIImage {
        guard let spec else { return UIImage() }
        if spec.fit == .width, let p = shown.first { return strip(p, width: spec.pageArea.width) ?? UIImage() }
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = true
        let area = spec.pageArea
        return UIGraphicsImageRenderer(size: spec.size, format: format).image { ctx in
            let cg = ctx.cgContext
            cg.setFillColor(UIColor(spec.paper.paperColor).cgColor)
            cg.fill(CGRect(origin: .zero, size: spec.size))
            let pages = shown.compactMap { doc.page(at: $0) }
            if spec.twoPages {
                let left = CGRect(x: area.minX, y: area.minY, width: area.width / 2, height: area.height)
                let right = left.offsetBy(dx: area.width / 2, dy: 0)
                if pages.count == 2 {
                    PdfPages.draw(pages[0], in: PdfPages.fit(PdfPages.size(of: pages[0]), in: left, align: -1), cg: cg, paper: spec.paper, blend: spec.blend)
                    PdfPages.draw(pages[1], in: PdfPages.fit(PdfPages.size(of: pages[1]), in: right, align: 1), cg: cg, paper: spec.paper, blend: spec.blend)
                } else if let only = pages.first {
                    // 혼자인 쪽: 표지는 오른쪽 반, 혼자 남은 마지막 쪽은 왼쪽 반, 가로로 긴 쪽은 가운데(공통 SpreadSlot).
                    let s = PdfPages.size(of: only)
                    let slot = SpreadSlot.companion.of(pages: shown.map { KotlinInt(int: Int32($0)) }, first: shown.first == 0,
                                                       wide: s.width > s.height, coverAlone: pageCount > 1)
                    let rect: CGRect = switch slot {
                    case .later: PdfPages.fit(s, in: right, align: 1)
                    case .earlier: PdfPages.fit(s, in: left, align: -1)
                    default: PdfPages.fit(s, in: area)
                    }
                    PdfPages.draw(only, in: rect, cg: cg, paper: spec.paper, blend: spec.blend)
                }
            } else if let only = pages.first {
                PdfPages.draw(only, in: PdfPages.fit(PdfPages.size(of: only), in: area), cg: cg, paper: spec.paper, blend: spec.blend)
            }
        }
    }

    // MARK: 폭 · 이어서 · 확대(구상안 33)

    /// 쪽 폭을 [width] 에 맞춘 그림. 아주 긴 쪽(웹툰형)은 1600만 화소에서 비율을 지켜 줄인다(안드로이드 `baseSize`) — 그대로 그리면
    /// 한 장이 수백 MB 다.
    func strip(_ p: Int, width: CGFloat) -> UIImage? {
        guard let spec, let page = doc.page(at: p) else { return nil }
        let s = PdfPages.size(of: page)
        var size = CGSize(width: width, height: width * s.height / s.width)
        let scale = UIScreen.main.scale
        let pixels = size.width * size.height * scale * scale
        if pixels > 16_000_000 {
            let k = (16_000_000 / pixels).squareRoot()
            size = CGSize(width: size.width * k, height: size.height * k)
        }
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            PdfPages.draw(page, in: CGRect(origin: .zero, size: size), cg: ctx.cgContext, paper: spec.paper, blend: spec.blend)
        }
    }

    /// 쪽마다 높이 ÷ 폭(이어서의 자리 잡기). 재지 못하면 A판형.
    func aspects() -> [CGFloat] {
        (0..<pageCount).map { i in
            guard let page = doc.page(at: i) else { return 1.4142 }
            let s = PdfPages.size(of: page)
            return s.height / s.width
        }
    }

    /// 확대한 쪽: 화면에 놓인 크기 [size](점)의 [zoom] 배를 화면 해상도로 — 1600만 화소에서 자른다(5배를 그대로 그리면 한 장이
    /// 수백 MB). 움직이는 동안은 이 그림을 늘려 보인다.
    func zoomed(_ p: Int, size: CGSize, zoom: CGFloat) -> UIImage? {
        guard let spec, let page = doc.page(at: p) else { return nil }
        let scale = UIScreen.main.scale
        var k = zoom
        let pixels = size.width * size.height * k * k * scale * scale
        if pixels > 16_000_000 { k *= (16_000_000 / pixels).squareRoot() }
        let target = CGSize(width: size.width * k, height: size.height * k)
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { ctx in
            PdfPages.draw(page, in: CGRect(origin: .zero, size: target), cg: ctx.cgContext, paper: spec.paper, blend: spec.blend)
        }
    }

    /// 지금 쪽이 화면에 놓인 자리(확대가 거기서 시작한다).
    func pageRect() -> CGRect? {
        guard let spec, let page = doc.page(at: page) else { return nil }
        return PdfPages.fit(PdfPages.size(of: page), in: spec.pageArea)
    }

    /// 폭 맞춤 · 이어서: 손을 멈춘 자리를 저장한다(쪽은 그대로, 쪽 안 세로 자리만). 이어서면 [shown](화면 가운데 쪽)을 아래 줄 ·
    /// 진도로 낸다 — 저장하는 자리는 화면 맨 위의 쪽([p])이다(안드로이드와 같다).
    func keep(_ p: Int, y: Int, shown: Int? = nil) async throws -> PageFrame {
        page = min(max(p, 0), pageCount - 1)
        yPermille = min(max(y, 0), 1000)
        let center = shown ?? page
        shownCenter = center
        let percent = try await book.save(page: Int32(page), yPermille: Int32(yPermille), lastShown: Int32(max(center, page)))
        let marked = try await book.isBookmarked(first: Int32(center), last: Int32(center)).boolValue
        var f = frame([center], percent: Double(percent.floatValue), bookmarked: marked, image: lastImage ?? UIImage())
        f.pdfY = yPermille
        return f
    }

    private var lastImage: UIImage?
    /// 이어서에서 화면 가운데 쪽(책갈피는 여기에 — 사람이 보고 있는 쪽이다).
    private var shownCenter: Int?

    // MARK: BookEngine

    func next() async throws -> PageFrame? {
        guard let n = nextStart(page, 1) else { return nil }
        return try await show(n)
    }

    func previous() async throws -> PageFrame? {
        guard let n = nextStart(page, -1) else { return nil }
        return try await show(n)
    }

    func seek(_ percent: Double) async throws -> PageFrame {
        // 진도 막대: 0 % 는 첫 쪽, 100 % 는 마지막 쪽(안드로이드 pageAt).
        try await show(Int((min(max(percent, 0), 100) / 100 * Double(pageCount - 1)).rounded()))
    }

    func peek(_ step: Int) async -> UIImage? {
        guard let n = nextStart(page, step) else { return nil }
        return render(spread(n))
    }

    func toggleBookmark() async throws -> PageFrame {
        if spec?.fit != .page {
            // 폭 · 이어서는 화면에 보이는 쪽에(이어서의 지금 쪽은 화면 가운데 — 저장 자리와 다를 수 있다).
            let here = shownCenter ?? page
            _ = try await book.toggleBookmark(first: Int32(here), last: Int32(here))
            return try await keep(page, y: yPermille, shown: here)
        }
        let shown = spread(page)
        _ = try await book.toggleBookmark(first: Int32(shown.first ?? page), last: Int32(shown.last ?? page))
        return try await show(page, y: yPermille)
    }

    func removeBookmark(_ id: Int64) async throws -> PageFrame {
        try await book.removeBookmark(id: id)
        return try await show(page, y: yPermille)
    }

    func bookmarks() async throws -> [BookmarkItem] {
        try await book.bookmarks().map { b in
            BookmarkItem(id: b.id, snippet: b.snippet, percent: Double(b.page + 1) * 100 / Double(pageCount),
                         section: book.section(page: b.page).isEmpty ? "앞부분" : book.section(page: b.page),
                         created: Date(timeIntervalSince1970: Double(b.createdAtEpochMs) / 1000), page: Int(b.page))
        }
    }

    func goToBookmark(_ id: Int64) async throws -> PageFrame? {
        guard let b = try await book.bookmarks().first(where: { $0.id == id }) else { return nil }
        return try await show(Int(b.page))
    }

    func contents() async throws -> (rows: [ContentsItem], current: Int) {
        let rows = book.contents.enumerated().map { i, r in
            ContentsItem(index: i, label: r.label, depth: Int(r.depth), trailing: r.pageName)
        }
        return (rows, Int(book.currentContentsIndex(page: Int32(page))))
    }

    func goToContents(_ index: Int) async throws -> PageFrame? {
        guard index >= 0, index < book.contents.count else { return nil }
        return try await show(Int(book.contents[index].page))
    }

    nonisolated func close() {}
}
