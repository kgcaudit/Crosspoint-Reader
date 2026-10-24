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
/// 확대를 그릴 수 있는 엔진(PDF · 만화).
protocol ZoomSource: Actor {
    func zoomed(_ p: Int, size: CGSize, zoom: CGFloat) -> UIImage?
    func pageRect() -> CGRect?
}

actor PdfEngine: BookEngine, ZoomSource {
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
        await loadTints(shown)
        let image = render(shown, current: true)
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
    /// [current] 면 지금 화면의 쪽이다 — 넘김 효과가 앞뒤 쪽을 미리 그릴 때 쪽 자리 기록을 덮어쓰면, 누른 자리를 이웃 쪽의 글자로 읽었다.
    private func render(_ shown: [Int], current: Bool = false) -> UIImage {
        guard let spec else { return UIImage() }
        if spec.fit == .width, let p = shown.first { return strip(p, width: spec.pageArea.width) ?? UIImage() }
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = true
        let area = spec.pageArea
        var placed: [(Int, CGRect)] = []
        let image = UIGraphicsImageRenderer(size: spec.size, format: format).image { ctx in
            let cg = ctx.cgContext
            cg.setFillColor(UIColor(spec.paper.paperColor).cgColor)
            cg.fill(CGRect(origin: .zero, size: spec.size))
            let pages = shown.compactMap { doc.page(at: $0) }
            /// 쪽을 그리고 그 위에 칠 · 찾은 말 · 듣는 문장(구상안 34A).
            func put(_ i: Int, _ rect: CGRect) {
                guard pages.indices.contains(i) else { return }
                PdfPages.draw(pages[i], in: rect, cg: cg, paper: spec.paper, blend: spec.blend)
                paintMarks(shown[i], in: rect, cg: cg)
                placed.append((shown[i], rect))
            }
            if spec.twoPages {
                let left = CGRect(x: area.minX, y: area.minY, width: area.width / 2, height: area.height)
                let right = left.offsetBy(dx: area.width / 2, dy: 0)
                if pages.count == 2 {
                    put(0, PdfPages.fit(PdfPages.size(of: pages[0]), in: left, align: -1))
                    put(1, PdfPages.fit(PdfPages.size(of: pages[1]), in: right, align: 1))
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
                    put(0, rect)
                }
            } else if let only = pages.first {
                put(0, PdfPages.fit(PdfPages.size(of: only), in: area))
            }
        }
        if current { placedPages = placed }
        return image
    }

    /// 마지막으로 낸 화면 한 장에서 쪽마다 놓인 자리(쪽 전체 보기 — 누른 자리를 쪽 안 글자로 바꾼다).
    private var placedPages: [(Int, CGRect)] = []

    // MARK: 폭 · 이어서 · 확대(구상안 33)

    /// 쪽 폭을 [width] 에 맞춘 그림. 아주 긴 쪽(웹툰형)은 1600만 화소에서 비율을 지켜 줄인다(안드로이드 `baseSize`) — 그대로 그리면
    /// 한 장이 수백 MB 다.
    func strip(_ p: Int, width: CGFloat) -> UIImage? {
        guard let spec, let page = doc.page(at: p) else { return nil }
        let s = PdfPages.size(of: page)
        var size = CGSize(width: width, height: width * s.height / s.width)
        let scale = ScreenInfo.scale
        let pixels = size.width * size.height * scale * scale
        if pixels > 16_000_000 {
            let k = (16_000_000 / pixels).squareRoot()
            size = CGSize(width: size.width * k, height: size.height * k)
        }
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            PdfPages.draw(page, in: CGRect(origin: .zero, size: size), cg: ctx.cgContext, paper: spec.paper, blend: spec.blend)
            paintMarks(p, in: CGRect(origin: .zero, size: size), cg: ctx.cgContext)
        }
    }

    /// 이어서 보기의 쪽 띠: 칠을 먼저 읽어 두고 그린다.
    func continuousStrip(_ p: Int, width: CGFloat) async -> UIImage? {
        // 이 쪽이 화면에서 이미 빠졌다(그리기를 거뒀다) — 엔진 차례를 기다리는 동안 취소됐으면 그리지 않는다.
        if Task.isCancelled { return nil }
        await loadTints([p])
        return strip(p, width: width)
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
        let scale = ScreenInfo.scale
        var k = zoom
        let pixels = size.width * size.height * k * k * scale * scale
        if pixels > 16_000_000 { k *= (16_000_000 / pixels).squareRoot() }
        let target = CGSize(width: size.width * k, height: size.height * k)
        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { ctx in
            PdfPages.draw(page, in: CGRect(origin: .zero, size: target), cg: ctx.cgContext, paper: spec.paper, blend: spec.blend)
            paintMarks(p, in: CGRect(origin: .zero, size: target), cg: ctx.cgContext)
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

    /// 같은 자리를 다시 낸다(칠 · 책갈피 · 찾은 말 · 듣는 문장이 바뀌었다). 이어서 보기는 화면 가운데 쪽을 지키는 [keep] — [show] 는
    /// 쪽 번호를 화면 맨 위 쪽으로 바꿔, 칠하기 · 지우기 뒤에 화면이 그 쪽 머리로 뛰었다.
    private func stay() async throws -> PageFrame {
        if spec?.fit == .continuous { return try await keep(page, y: yPermille, shown: shownCenter) }
        return try await show(page, y: yPermille)
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

    func goToPlace(_ place: SyncPlace) async throws -> PageFrame? {
        guard let p = place.page else { return nil }
        return try await show(min(max(p, 0), pageCount - 1))
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
        return try await stay()
    }

    func removeBookmark(_ id: Int64) async throws -> PageFrame {
        try await book.removeBookmark(id: id)
        return try await stay()
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

    // MARK: 글자 층(구상안 34A)

    private var layers: [Int: PageText] = [:]
    private var layerOrder: [Int] = []
    private var tints: [Int: [PdfTint]] = [:]
    private var showTints = true
    /// 찾은 말(쪽, 층 구간)과 듣는 문장.
    private var mark: (page: Int, start: Int, end: Int)?
    private var speakingRange: (page: Int, start: Int, end: Int)?
    private var readableCache: Bool?

    /// 쪽의 글자 층: PDFKit 의 글과 글자 네모(쪽 비율, 왼쪽 위가 0)를 공통 `PageText` 로. 회전한 쪽은 네모를 보이는 방향으로 돌린다
    /// (Kotlin `IosPdfText.rotated`) — 쪽 그림이 돌아 그려지기 때문이다.
    /// 몇 쪽만 쥐고 있는다 — 글자마다 네모를 재는 일이 쪽마다 수천 번이다.
    func layer(_ p: Int) -> PageText {
        if let l = layers[p] { return l }
        guard let page = doc.page(at: p) else { return PageText.companion.EMPTY }
        let text = page.string ?? ""
        let n = (text as NSString).length
        let box = page.bounds(for: .mediaBox)
        var boxes = KotlinFloatArray(size: 0)
        let turn = Int32(page.rotation)
        if box.width > 0, box.height > 0, n > 0 {
            boxes = KotlinFloatArray(size: Int32(n * 4))
            for i in 0..<n {
                // 글자 네모는 그 글자를 고른 네모로 잰다. `characterBounds(at:)` 는 번호가 `page.string` 과 어긋나는 PDF 가 있고
                // (줄바꿈을 다르게 센다 — 찾은 말의 칠이 몇 글자 뒤에 앉았다), 높이를 0 으로 주기도 한다.
                // 고르기 네모는 줄 간격보다 조금 높아 이웃 줄과 겹친다 — 그대로 두면 여러 줄이 한 줄로 묶여 칠이 세 줄 높이가 됐다.
                // 가운데 2/3 만 쓴다(글자 몸의 높이쯤).
                let sel = page.selection(for: NSRange(location: i, length: 1))?.bounds(for: page) ?? .null
                let r = sel.isNull ? sel : sel.insetBy(dx: 0, dy: sel.height / 6)
                let k = Int32(i * 4)
                if r.isNull || r.isEmpty || !r.minX.isFinite {
                    for j in 0..<4 { boxes.set(index: k + Int32(j), value: .nan) }
                } else {
                    let q = IosPdfText.shared.rotated(l: Float((r.minX - box.minX) / box.width), t: Float((box.maxY - r.maxY) / box.height),
                                                      r: Float((r.maxX - box.minX) / box.width), b: Float((box.maxY - r.minY) / box.height),
                                                      rotation: turn)
                    for j in 0..<4 { boxes.set(index: k + Int32(j), value: q.get(index: Int32(j))) }
                }
            }
        }
        let l = IosPdfText.shared.layer(text: text, boxes: boxes)
        layers[p] = l
        layerOrder.append(p)
        if layerOrder.count > 8 { layers[layerOrder.removeFirst()] = nil }
        return l
    }

    private func loadTints(_ pages: [Int]) async {
        for p in pages where tints[p] == nil {
            tints[p] = (try? await book.tintsOn(page: Int32(p))) ?? []
        }
    }

    private func refreshTints() async {
        tints = [:]
    }

    /// 쪽 위의 칠 · 찾은 말 · 듣는 문장 · 메모 쪽지(쪽이 놓인 [rect] 에 맞춰).
    private func paintMarks(_ p: Int, in rect: CGRect, cg: CGContext) {
        let t = showTints ? (tints[p] ?? []) : []
        let m = mark.flatMap { $0.page == p ? ints($0.start, $0.end) : nil }
        let s = speakingRange.flatMap { $0.page == p ? ints($0.start, $0.end) : nil }
        if t.isEmpty && m == nil && s == nil { return }
        let boxes = IosPdfText.shared.paint(layer: layer(p), tints: t, mark: m, speaking: s, dark: spec?.paper.dark == true, accent: 0xB95B3B)
        for b in boxes {
            let color = UIColor(red: CGFloat((b.rgb >> 16) & 0xFF) / 255, green: CGFloat((b.rgb >> 8) & 0xFF) / 255,
                                blue: CGFloat(b.rgb & 0xFF) / 255, alpha: CGFloat(b.alpha))
            let x0 = rect.minX + CGFloat(b.left) * rect.width, y0 = rect.minY + CGFloat(b.top) * rect.height
            let h = CGFloat(b.bottom - b.top) * rect.height
            cg.setFillColor(color.cgColor)
            if b.memo {
                // 메모 쪽지: 끝 글자 오른쪽 위의 작은 네모(책과 같은 자리).
                let size = max(h * 0.55, 5)
                cg.fill(CGRect(x: x0 + size * 0.1, y: y0 - size * 0.75, width: size, height: size))
            } else {
                cg.setBlendMode(.multiply)
                cg.fill(CGRect(x: x0, y: y0, width: CGFloat(b.right - b.left) * rect.width, height: h))
                cg.setBlendMode(.normal)
            }
        }
    }

    private func ints(_ a: Int, _ b: Int) -> KotlinIntArray {
        let k = KotlinIntArray(size: 2)
        k.set(index: 0, value: Int32(a))
        k.set(index: 1, value: Int32(b))
        return k
    }

    /// 사람이 읽을 글이 있는 PDF 인가(스캔본 안내 — 안드로이드 `hasText`): 지금 쪽 + 앞 12쪽 중 하나라도.
    func hasText() -> Bool {
        if let r = readableCache { return r }
        let pages = Array(Set([page] + Array(0..<min(pageCount, 12))))
        let r = pages.contains { IosPdfText.shared.readable(text: doc.page(at: $0)?.string ?? "") }
        readableCache = r
        return r
    }

    /// 화면 점 → (쪽, 쪽 비율 x, y). 쪽 전체 보기에서만 — 폭 · 이어서는 화면이 쪽을 옮겨 보여 자리를 셀 수 없다(고르기 · 칠은 쪽 전체에서).
    private func pageAt(_ point: CGPoint) -> (Int, Float, Float)? {
        for (p, r) in placedPages where r.insetBy(dx: -12, dy: -12).contains(point) {
            return (p, Float((point.x - r.minX) / r.width), Float((point.y - r.minY) / r.height))
        }
        return nil
    }

    private func rectOf(_ p: Int) -> CGRect? { placedPages.first { $0.0 == p }?.1 }

    func setShowHighlights(_ on: Bool) { showTints = on }

    func chapterCount() async throws -> Int { pageCount }

    func searchChapter(_ spine: Int, _ query: String, firstId: Int) async throws -> [SearchItem] {
        let text = doc.page(at: spine)?.string ?? ""
        let section = book.section(page: Int32(spine))
        return IosPdfText.shared.search(text: text, page: Int32(spine), query: query).enumerated().map { i, r in
            SearchItem(id: firstId + i, spine: spine, start: Int(r.start), end: Int(r.endExclusive), context: r.context,
                       matchStart: Int(r.contextMatchStart), percent: Double(spine + 1) * 100 / Double(pageCount),
                       section: section.isEmpty ? "\(spine + 1)쪽" : section)
        }
    }

    /// 찾은 곳으로: 그 쪽에서 몇 번째인지로 층의 구간을 칠한다(쪽 글과 층은 번호가 다를 수 있다 — 안드로이드와 같다). 폭 맞춤이면
    /// 찾은 말이 화면 위 30% 쯤에 오게 쪽 안을 옮긴다.
    func goToHit(_ item: SearchItem) async throws -> PageFrame {
        let text = doc.page(at: item.spine)?.string ?? ""
        let query = (IosPdfText.shared.searchText(text: text) as NSString).substring(with: NSRange(location: item.start, length: max(item.end - item.start, 0)))
        let ordinal = IosPdfText.shared.search(text: text, page: Int32(item.spine), query: query).filter { Int($0.start) < item.start }.count
        let l = layer(item.spine)
        if let r = IosPdfText.shared.hit(layer: l, query: query, ordinal: Int32(ordinal)) {
            mark = (item.spine, Int(r.get(index: 0)), Int(r.get(index: 1)))
        } else {
            mark = nil
        }
        var y = 0
        if spec?.fit == .width, let m = mark, let top = IosPdfText.shared.rects(layer: l, start: Int32(m.start), end: Int32(m.end)).first?.get(index: 1),
           let spec, let pg = doc.page(at: item.spine) {
            let s = PdfPages.size(of: pg)
            let stripH = spec.pageArea.width * s.height / s.width
            let view = spec.pageArea.height
            let topPt = CGFloat(top) * stripH - view * 0.3
            y = Int((max(min(topPt, stripH - view), 0) / max(stripH, 1) * 1000).rounded())
        }
        return try await show(item.spine, y: y)
    }

    func clearMark() async throws -> PageFrame {
        mark = nil
        return try await stay()
    }

    // 고르기 · 칠(한 쪽 안에서만 — 결정 2). 번호는 쪽과 묶은 수(`IosPdfText.encode`).

    /// 폭 맞춤 · 이어서: 쪽이 지금 화면에 놓인 자리(모델 · 굴리는 뷰가 알린다). 쪽 전체 보기는 그리는 자리가 곧 놓인 자리라 쓰지 않는다.
    /// 이것이 없으면 누른 자리를 쪽 글자로 바꿀 수 없어, 폭 맞춤 · 이어서에서는 고르기 · 칠 누르기가 되지 않았다.
    func place(_ list: [(Int, CGRect)]) {
        guard spec?.fit != .page else { return }
        placedPages = list
    }

    func charAt(_ p: CGPoint, after: Bool) async throws -> Int {
        guard let (pg, x, y) = pageAt(p) else { return -1 }
        let i = Int(IosPdfText.shared.charAt(layer: layer(pg), x: x, y: y, after: after))
        return i < 0 ? -1 : Int(IosPdfText.shared.encode(page: Int32(pg), char: Int32(i)))
    }

    func wordAt(_ p: CGPoint) async throws -> (Int, Int)? {
        guard let (pg, x, y) = pageAt(p) else { return nil }
        let l = layer(pg)
        let i = Int(IosPdfText.shared.charAt(layer: l, x: x, y: y, after: false))
        guard i >= 0 else { return nil }
        let w = IosPdfText.shared.wordAt(layer: l, i: Int32(i))
        return (Int(IosPdfText.shared.encode(page: Int32(pg), char: w.get(index: 0))), Int(IosPdfText.shared.encode(page: Int32(pg), char: w.get(index: 1))))
    }

    func boxes(_ start: Int, _ end: Int) async throws -> [CGRect] {
        let pg = Int(IosPdfText.shared.pageOf(index: Int32(start)))
        guard pg == Int(IosPdfText.shared.pageOf(index: Int32(max(end - 1, start)))), let r = rectOf(pg) else { return [] }
        let s = IosPdfText.shared.charOf(index: Int32(start)), e = s + Int32(max(end - start, 0))
        return IosPdfText.shared.rects(layer: layer(pg), start: s, end: e).map { b in
            CGRect(x: r.minX + CGFloat(b.get(index: 0)) * r.width, y: r.minY + CGFloat(b.get(index: 1)) * r.height,
                   width: CGFloat(b.get(index: 2) - b.get(index: 0)) * r.width, height: CGFloat(b.get(index: 3) - b.get(index: 1)) * r.height)
        }
    }

    func textOf(_ start: Int, _ end: Int) async throws -> String {
        let pg = Int(IosPdfText.shared.pageOf(index: Int32(start)))
        let l = layer(pg)
        let s = Int(IosPdfText.shared.charOf(index: Int32(start))), e = min(s + max(end - start, 0), Int(l.length))
        return l.quote(start: Int32(s), endExclusive: Int32(e))
    }

    func highlight(_ start: Int, _ end: Int, pen: Int, note: String?) async throws -> PageFrame {
        let pg = Int(IosPdfText.shared.pageOf(index: Int32(start)))
        let s = Int(IosPdfText.shared.charOf(index: Int32(start)))
        let e = s + max(end - start, 0)
        let snippet = layer(pg).quote(start: Int32(s), endExclusive: Int32(min(e, Int(layer(pg).length))))
        try await book.highlight(page: Int32(pg), start: Int32(s), end: Int32(e), color: Int32(pen), note: note, snippet: snippet)
        tints[pg] = nil
        return try await stay()
    }

    func recolor(_ id: Int64, pen: Int) async throws -> PageFrame {
        try await book.recolor(id: id, color: Int32(pen))
        tints = [:]
        return try await stay()
    }

    func setNote(_ id: Int64, _ note: String?) async throws -> PageFrame {
        try await book.setNote(id: id, note: note)
        tints = [:]
        return try await stay()
    }

    func removeNote(_ id: Int64) async throws -> PageFrame {
        try await book.removeNote(id: id)
        tints = [:]
        return try await stay()
    }

    func noteRows() async throws -> [HighlightItem] { try await book.noteRows().map(HighlightItem.init) }

    func goToNote(_ id: Int64) async throws -> PageFrame? {
        guard let p = try await book.notePage(id: id) else { return nil }
        return try await show(Int(truncating: p))
    }

    /// 누름: 칠을 누르면 그 칠의 메뉴(책과 같은 "note" 동작). 쪽 전체 보기에서만.
    func tapLink(_ x: Double, _ y: Double) async throws -> LinkAction? {
        guard showTints, let (pg, fx, fy) = pageAt(CGPoint(x: x, y: y)) else { return nil }
        await loadTints([pg])
        let t = tints[pg] ?? []
        let k = Int(IosPdfText.shared.tintAt(layer: layer(pg), tints: t, x: fx, y: fy))
        guard k >= 0 else { return nil }
        let rows = try await book.notesOn(page: Int32(pg))
        let hit = t[k]
        guard let row = rows.first(where: { IosPdfText.shared.charOf(index: $0.start) == hit.start && IosPdfText.shared.charOf(index: $0.end) == hit.end }) else { return nil }
        return LinkAction(kind: "note", title: "", text: "", url: "", spine: Int32(pg), anchor: nil, frame: nil, note: row)
    }

    // 듣기(구상안 34A): 쪽마다, 머리말 · 쪽 번호 줄은 빼고(공통 `PageText.speech`).

    func speechSentences(_ spine: Int) async -> [SpokenSentence] {
        let s = IosPdfText.shared.sentences(layer: layer(spine))
        return stride(from: 0, to: Int(s.size) - 1, by: 2).map { SpokenSentence(start: Int(s.get(index: Int32($0))), end: Int(s.get(index: Int32($0 + 1)))) }
    }

    func spoken(_ spine: Int, _ s: SpokenSentence, join: Int) async -> String {
        IosPdfText.shared.spoken(layer: layer(spine), start: Int32(s.start), end: Int32(s.end))
    }

    func speak(_ spine: Int, _ s: SpokenSentence, follow: Bool) async throws -> PageFrame {
        speakingRange = (spine, s.start, s.end)
        let here = spread(page)
        if follow && !here.contains(spine) { return try await show(spine) }
        return try await stay()
    }

    /// 듣는 문장이 그 쪽에서 차지하는 높이(쪽 비율). 폭 맞춤 · 이어서가 화면 밖으로 나간 문장을 따라간다. 글자 네모가 없으면 nil.
    func speakingSpan() -> (page: Int, top: CGFloat, bottom: CGFloat)? {
        guard let r = speakingRange, let s = IosPdfText.shared.span(layer: layer(r.page), start: Int32(r.start), end: Int32(r.end)) else { return nil }
        return (r.page, CGFloat(s.get(index: 0)), CGFloat(s.get(index: 1)))
    }

    func stopSpeaking() async throws -> PageFrame {
        speakingRange = nil
        return try await stay()
    }
}
