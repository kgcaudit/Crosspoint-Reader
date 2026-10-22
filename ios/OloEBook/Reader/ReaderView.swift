import Combine
import OloCore
import SwiftUI

/// Kotlin 리더를 줄 세워 부른다. 주 스레드 밖에서 돌고(장 하나를 짜는 데 수백 ms 가 걸릴 수 있다), 한 번에 하나씩만 —
/// 넘김이 겹치면 두 번 눌러도 한 쪽만 넘어가거나 자리 저장 순서가 뒤바뀐다.
actor ReaderEngine: BookEngine {
    private let reader: IosBookReader

    init(reader: IosBookReader) { self.reader = reader }

    /// 형광펜 보임(모든 보기 설정). 조판이 아니라 그리기만 바꾸므로 LayoutSpec 이 아니라 여기 둔다.
    var showHighlights = true
    func setShowHighlights(_ on: Bool) { showHighlights = on }

    func layOut(_ spec: LayoutSpec, _ paper: Paper, blend: Bool, spread: Spread?) async throws -> PageFrame {
        // 찾은 말 칠은 지면 밝기에 맞는 강조색(메뉴도 지면 밝기를 따른다).
        PageFrame(try await reader.layOut(spec: spec, paper: Int32(paper.paper), ink: Int32(paper.ink), blendPictures: blend,
                                          accent: paper.dark ? 0xE8A183 : 0xB95B3B, accentText: paper.dark ? 0xF6C4AE : 0x9A4A2C,
                                          showHighlights: showHighlights, twoPages: spread != nil,
                                          fullWidth: Float(spread?.width ?? 0), leftInset: Float(spread?.leftInset ?? 0)))
    }

    func chapterCount() async throws -> Int { Int(try await reader.chapterCount()) }

    func searchChapter(_ spine: Int, _ query: String, firstId: Int) async throws -> [SearchItem] {
        try await reader.searchChapter(spine: Int32(spine), query: query).enumerated().map { i, r in
            SearchItem(id: firstId + i, spine: Int(r.spine), start: Int(r.start), end: Int(r.endExclusive), context: r.context,
                       matchStart: Int(r.contextMatchStart), percent: Double(r.percent), section: r.section)
        }
    }

    func goToHit(_ item: SearchItem) async throws -> PageFrame {
        PageFrame(try await reader.goToHit(spine: Int32(item.spine), start: Int32(item.start), endExclusive: Int32(item.end)))
    }

    func clearMark() async throws -> PageFrame { PageFrame(try await reader.clearMark()) }

    func tapLink(_ x: Double, _ y: Double) async throws -> LinkAction? { try await reader.tapLink(x: Float(x), y: Float(y)) }
    func jumpTo(_ spine: Int, _ anchor: String?, markLength: Int) async throws -> PageFrame {
        PageFrame(try await reader.jumpTo(spine: Int32(spine), anchor: anchor, markLength: Int32(markLength)))
    }
    func returnBack() async throws -> PageFrame? { try await reader.returnBack().map(PageFrame.init) }
    func peek(_ step: Int) async -> UIImage? { try? await reader.peek(step: Int32(step)) }

    // 형광펜 · 메모
    func charAt(_ p: CGPoint, after: Bool) async throws -> Int { Int(try await reader.charAt(x: Float(p.x), y: Float(p.y), after: after)) }
    func wordAt(_ p: CGPoint) async throws -> (Int, Int)? {
        let w = try await reader.wordAt(x: Float(p.x), y: Float(p.y))
        return w.count == 2 ? (w[0].intValue, w[1].intValue) : nil
    }
    func boxes(_ start: Int, _ end: Int) async throws -> [CGRect] {
        try await reader.boxes(start: Int32(start), end: Int32(end)).map {
            CGRect(x: CGFloat($0.left), y: CGFloat($0.top), width: CGFloat($0.right - $0.left), height: CGFloat($0.bottom - $0.top))
        }
    }
    func selectionState(_ start: Int, _ end: Int) async throws -> (Bool, Int) {
        let st = try await reader.selectionState(start: Int32(start), end: Int32(end))
        return (st.canContinue, Int(st.charsBefore))
    }
    func continueSelection(_ start: Int, _ end: Int) async throws -> (PageFrame, Int, Int)? {
        guard let c = try await reader.continueSelection(start: Int32(start), end: Int32(end)) else { return nil }
        return (PageFrame(c.frame), Int(c.start), Int(c.end))
    }
    func textOf(_ start: Int, _ end: Int) async throws -> String { try await reader.textOf(start: Int32(start), end: Int32(end)) }
    func highlight(_ start: Int, _ end: Int, pen: Int, note: String?) async throws -> PageFrame {
        PageFrame(try await reader.highlight(start: Int32(start), end: Int32(end), color: Int32(pen), note: note))
    }
    func recolor(_ id: Int64, pen: Int) async throws -> PageFrame { PageFrame(try await reader.recolor(id: id, color: Int32(pen))) }
    func setNote(_ id: Int64, _ note: String?) async throws -> PageFrame { PageFrame(try await reader.setNote(id: id, note: note)) }
    func removeNote(_ id: Int64) async throws -> PageFrame { PageFrame(try await reader.removeNote(id: id)) }
    func noteRows() async throws -> [HighlightItem] { try await reader.noteRows().map(HighlightItem.init) }
    func goToNote(_ id: Int64) async throws -> PageFrame? { try await reader.goToNote(id: id).map(PageFrame.init) }

    func next() async throws -> PageFrame? { try await reader.next().map(PageFrame.init) }
    func previous() async throws -> PageFrame? { try await reader.previous().map(PageFrame.init) }
    func seek(_ percent: Double) async throws -> PageFrame { PageFrame(try await reader.seek(percent: Float(percent))) }
    func toggleBookmark() async throws -> PageFrame { PageFrame(try await reader.toggleBookmark()) }
    func removeBookmark(_ id: Int64) async throws -> PageFrame { PageFrame(try await reader.removeBookmark(id: id)) }

    func contents() async throws -> (rows: [ContentsItem], current: Int) {
        let rows = try await reader.contents().enumerated().map { ContentsItem(index: $0.offset, label: $0.element.label, depth: Int($0.element.depth)) }
        return (rows, Int(try await reader.currentContentsIndex()))
    }

    func goToContents(_ index: Int) async throws -> PageFrame? { try await reader.goToContents(index: Int32(index)).map(PageFrame.init) }

    func bookmarks() async throws -> [BookmarkItem] {
        try await reader.bookmarks().map {
            BookmarkItem(id: $0.id, snippet: $0.snippet, percent: Double($0.percent), section: $0.section,
                         created: Date(timeIntervalSince1970: Double($0.createdAtEpochMs) / 1000))
        }
    }

    func goToBookmark(_ id: Int64) async throws -> PageFrame? { try await reader.goToBookmark(id: id).map(PageFrame.init) }

    // 듣기
    func speechSentences(_ spine: Int) async -> [SpokenSentence] {
        ((try? await reader.speechSentences(spine: Int32(spine))) ?? []).map { SpokenSentence(start: Int($0.start), end: Int($0.end)) }
    }
    func spoken(_ spine: Int, _ s: SpokenSentence, join: Int) async -> String {
        (try? await reader.spoken(spine: Int32(spine), start: Int32(s.start), end: Int32(s.end), join: Int32(join))) ?? ""
    }
    func speak(_ spine: Int, _ s: SpokenSentence, follow: Bool) async throws -> PageFrame {
        PageFrame(try await reader.speak(spine: Int32(spine), start: Int32(s.start), end: Int32(s.end), follow: follow))
    }
    func stopSpeaking() async throws -> PageFrame { PageFrame(try await reader.stopSpeaking()) }
    /// 책에 쓸 수 있는 글꼴이 있는지(꺼내 본 뒤에는 실제로 읽힌 것 기준).
    func hasBookFonts() async -> Bool { (try? await reader.hasBookFonts())?.boolValue ?? false }
    func bookFontPreviewPath() -> String? { reader.bookFontPreviewPath }
    func close() { reader.close() }
    nonisolated var title: String { reader.title }
    nonisolated var author: String? { reader.author }
}

struct ContentsItem: Identifiable, Hashable {
    let index: Int
    let label: String
    let depth: Int
    /// 줄 끝의 쪽(PDF — 인쇄된 번호가 있으면 그것). 책은 nil.
    var trailing: String?
    var id: Int { index }
}

struct BookmarkItem: Identifiable, Hashable {
    let id: Int64
    let snippet: String
    let percent: Double
    /// 독서노트의 갈래 이름(이 자리를 담은 목차 항목).
    let section: String
    let created: Date
    /// PDF 책갈피의 쪽(0 부터). 책은 nil.
    var page: Int?

    /// "3% · 2026.10.21."(안드로이드 `noteWhere`). PDF 는 "4쪽 · 2026.10.21." — 쪽이 진도보다 바로 읽힌다.
    var whereLabel: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = "yyyy.MM.dd."
        return page != nil ? "\(snippet) · \(f.string(from: created))" : "\(Int(percent.rounded()))% · \(f.string(from: created))"
    }
}

/// 화면에 낼 한 쪽(Kotlin `ReaderFrame` 을 Swift 값으로).
struct PageFrame: @unchecked Sendable {
    let image: UIImage
    let spine: Int
    let chapters: Int
    let page: Int
    let pages: Int
    let percent: Double
    let bookmarked: Bool
    /// 링크로 건너온 쪽 — "읽던 곳으로" 칩.
    let canReturn: Bool
    /// PDF 쪽(구상안 32A): 위 막대 "4 / 6쪽", 아래 줄의 쪽은 책 전체 쪽.
    var isPdf = false
    /// 인쇄된 쪽 번호가 파일 순서와 다르면 그 이름("iv") — 아래 줄 "iv · 4 / 230".
    var pageLabel: String?
    /// PDF 의 이 장 남은 쪽(목차 항목까지). 책은 nil — 장 안의 쪽 수로 센다.
    var pagesLeft: Int?
    /// PDF 쪽 안 세로 자리(천분율) — 폭 맞춤이 그 자리에서 다시 연다.
    var pdfY = 0
    /// 만화 쪽(구상안 36): 위 막대 "45 / 180쪽 · 오→왼", 아래 줄 없음.
    var isComic = false
    /// 그리지 못한 쪽(그 자리에 "이 쪽을 그리지 못했습니다").
    var brokenPages: [Int] = []
    /// 만화 펼침면의 반을 보는 중이면 " · 왼쪽 반"(위 막대 부제, 구상안 38).
    var halfLabel = ""
    /// 이 쪽의 글자 구간(장 안). 듣기가 이 쪽에서 시작하는 첫 문장을 찾는다.
    let pageStart: Int
    let pageEnd: Int
    /// 두 쪽 보기에서 오른쪽 쪽이 있다("3–4 / 12").
    let hasRight: Bool
    /// 이 자리의 목차 항목(아래 줄 "장 제목").
    let section: String
    let charsLeftChapter: Int
    let charsLeftBook: Int
    var key: String { "\(spine):\(page)" }

    init(_ f: ReaderFrame) {
        image = f.image
        spine = Int(f.spineIndex)
        chapters = Int(f.chapterCount)
        page = Int(f.pageIndex)
        pages = Int(f.pageCount)
        percent = Double(f.percent)
        bookmarked = f.bookmarked
        canReturn = f.canReturn
        pageStart = Int(f.pageStart)
        pageEnd = Int(f.pageEnd)
        hasRight = f.hasRight
        section = f.section
        charsLeftChapter = Int(f.charsLeftChapter)
        charsLeftBook = Int(f.charsLeftBook)
    }

    /// PDF 쪽(펼침이면 왼쪽 쪽). 남은 시간은 아직 세지 않는다(글자가 아니라 쪽 단위 빠르기가 따로 있다 — 안드로이드 `speed.pages`).
    init(pdfImage: UIImage, page: Int, pages: Int, percent: Double, bookmarked: Bool, hasRight: Bool, section: String,
         pageLabel: String?, pagesLeft: Int) {
        image = pdfImage
        spine = page
        chapters = pages
        self.page = page
        self.pages = pages
        self.percent = percent
        self.bookmarked = bookmarked
        canReturn = false
        pageStart = 0
        pageEnd = 0
        self.hasRight = hasRight
        self.section = section
        charsLeftChapter = -1
        charsLeftBook = -1
        isPdf = true
        self.pageLabel = pageLabel
        self.pagesLeft = pagesLeft
    }
}

/// 두 쪽 보기의 펼침 자리: 화면 폭과 왼쪽 쪽이 시작하는 곳(왼쪽 안전 영역).
struct Spread: Equatable {
    let width: CGFloat
    let leftInset: CGFloat
}

/// 각주 판에 띄울 것.
struct FootnoteItem: Identifiable {
    let id = UUID()
    let title: String
    let text: String
    let spine: Int
    let anchor: String?
}

/// 리더 위에 열린 판(안드로이드 `Panel`). 뒤로 가기: 목차 · 보기 → 메뉴, 보기 설정 · 글꼴 → 보기, 메뉴 → 지면.
enum ReaderPanel { case none, bar, view, settings, fonts, contents, search, export, voices, footer, footerItem, touchZones, pageGrid, comicMarks }

/// 쪽이 바뀐 까닭(읽는 빠르기 표본). 사람이 바로 다음 쪽으로 넘긴 것만 잰다.
enum TurnCause { case jump, forward, backward, machine }

@MainActor
final class ReaderModel: ObservableObject {
    @Published private(set) var frame: PageFrame? {
        didSet {
            guard frame?.key != oldValue?.key else { return }
            let cause = turnCause
            turnCause = .jump
            sampleTurn(cause)
            // 읽은 시간: 사람 · 자동이 넘겼으면 앞 쪽 시간을 적고, 건너뛰었으면 버린다(구상안 30).
            if let key = frame?.key { clock?.pageChanged(to: key, counted: cause != .jump) }
            if isFixed { pdfPageChanged() }
        }
    }
    /// 읽은 시간(구상안 30). 책을 열 때 만든다.
    private(set) var clock: ReadingClock?
    private var bookId: String?
    private var store: ReadingStore?
    /// 듣기를 잰 시작(1분마다 끊어 적는다 — 화면을 끈 채 몇 시간 들어도 앱이 죽으면 그 시간을 다 잃지 않게).
    private var listenSince: Date?
    private var listenLaps: Task<Void, Never>?
    @Published private(set) var failure: String?
    @Published var panel = ReaderPanel.none
    /// 넘긴 방향(+1 다음, −1 앞). 쪽이 들어오는 쪽을 정한다.
    @Published private(set) var direction = 1
    // 화면이 지켜본다 — 책이 열리면 그때 조판을 시작해야 한다(안 지켜보면 지면만 나오고 글이 영영 안 나온다).
    @Published private(set) var engine: (any BookEngine)?
    /// 마지막으로 넘긴 때. "화면 켜짐 유지 10분" 이 여기서 센다.
    @Published private(set) var lastActivity = Date()
    /// 이 책에 쓸 수 있는 글꼴이 있다 — 글꼴 판에 "출판사 글꼴" 을 내놓고, 켜져 있으면 책 글꼴로 짠다.
    @Published private(set) var bookFonts = false
    /// "출판사 글꼴" 이름을 그릴 글꼴 파일(꺼낸 뒤에만).
    @Published private(set) var bookFontPreview: String?
    private(set) var title = ""
    private(set) var author: String?
    private var endAccess: (() -> Void)?
    private var listenWatch: AnyCancellable?

    init() {
        // 조종판 · 칠 · 결과 막대 자리가 듣기 상태를 따라 바뀐다 — 듣기의 바뀜을 리더의 바뀜으로 넘긴다.
        listenWatch = listening.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        playingWatch = listening.$playing.removeDuplicates().sink { [weak self] playing in self?.listenChanged(playing) }
    }

    private var playingWatch: AnyCancellable?

    /// 들은 시간(LISTEN): 소리가 나는 동안만. 멈추면 그때까지를 적는다.
    private func listenChanged(_ playing: Bool) {
        lapListen()
        listenLaps?.cancel()
        listenSince = playing ? Date() : nil
        guard playing else { return }
        listenLaps = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                if Task.isCancelled { return }
                self?.lapListen()
            }
        }
    }

    private func lapListen() {
        guard let since = listenSince, let store, let bookId else { return }
        let now = Date()
        listenSince = now
        let ms = Int64(now.timeIntervalSince(since) * 1000)
        if ms > 0 { store.addTime(bookId: bookId, day: epochDay(now), mode: ReadingMode.listen.rawValue, millis: ms) }
    }

    func open(_ book: LibraryBook, library: Library) async {
        guard engine == nil else { return }
        endAccess = library.beginAccess(book)
        let store = library.store, cache = library.pageCache
        self.store = store
        bookId = book.id
        openBookId = book.id
        clock = ReadingClock(store: store, bookId: book.id)
        if book.format == .comic {
            // 만화(구상안 36): 제목은 "작품 권", 방향 · 다음 권은 작품에서.
            self.library = library
            guard let file = library.comicFile(book.id) else { failure = ComicOpenError.missing.message; return }
            let work = library.work(of: book.id)
            comicWork = work
            let entry = work.flatMap { w in w.entries.first { $0.unit.id == book.id || $0.copies.contains { $0.id == book.id } } }
            let name = [work?.title, entry?.label].compactMap { $0 }.joined(separator: " ")
            let label = name.isEmpty ? book.displayName : name
            do {
                let comic = try await Task.detached(priority: .userInitiated) {
                    try ComicEngine(file: file, unitId: book.id, title: label, store: store)
                }.value
                title = label
                comicLabel = entry?.label ?? book.displayName
                comicFileRef = file
                comicEntry = entry
                // 보는 방식(구상안 39): 고른 값 → ComicInfo → 쪽 모양(세로가 가로의 2배 넘는 쪽이 절반 넘게) → 쪽 넘김.
                // 엔진을 붙이기 전에 정한다 — 붙이고 나서 정하면 그사이 쪽 넘김이 첫 쪽을 내며 진도를 적어, 웹툰의 그림 안 자리가 지워졌다.
                let sizes = await comic.rawSizeList()
                if Webtoon.shared.view(chosen: work?.view, info: entry?.unit.info, sizes: sizes) == .webtoon { await openWebtoon() }
                engine = comic
                isComic = true
            } catch {
                failure = ComicOpenError.broken.message
            }
            return
        }
        if book.format == .pdf {
            do {
                let pdf = try await PdfEngine(url: book.url, bookId: book.id, displayName: book.displayName, store: store)
                self.engine = pdf
                isPdf = true
                // PDF 는 쪽 단위 빠르기(안드로이드 `speed.pages`) — 글자 빠르기로 쪽 수를 셈하면 "1분 미만" 이 된다.
                speed = ReadingSpeed.load(.pages)
                title = pdf.title
                author = pdf.author
            } catch let error as PdfOpenError {
                failure = error.message
            } catch {
                failure = PdfOpenError.broken.message
            }
            return
        }
        do {
            let reader = try await Task.detached(priority: .userInitiated) {
                try IosBookReader.companion.open(path: book.url.path, bookId: book.id, displayName: book.displayName,
                                                 store: store, cacheDir: cache, edition: book.edition)
            }.value
            let engine = ReaderEngine(reader: reader)
            // 엔진을 내놓기 전에 안다 — 내놓은 뒤에 알면 첫 쪽을 휴대폰 글꼴로 짠 다음 책 글꼴로 한 번 더 짠다.
            bookFonts = await engine.hasBookFonts()
            self.engine = engine
            title = reader.title
            author = reader.author
        } catch {
            // "이 책은 열리지 않습니다" 는 가장 나쁜 답이지만, 정말 열 수 없는 파일(zip 이 아닌 EPUB)에는 까닭을 알린다.
            failure = "파일이 망가졌거나 EPUB · TXT 가 아닙니다."
        }
    }

    // ── PDF 폭 · 이어서 · 확대(구상안 33) ──────────────────────────────────────

    /// 화면이 정한다: 쪽 맞춤과 쪽을 놓는 칸.
    var pdfFit = PdfFit.page
    var pdfArea = CGRect.zero
    /// 폭 맞춤: 쪽 그림에서 화면 맨 위가 걸친 자리(점).
    @Published private(set) var pdfOffset: CGFloat = 0
    /// 알약을 다시 띄우는 표.
    @Published private(set) var pdfPill = 0
    /// 이어서: 목차 · 진도 · 책갈피로 옮겼다(화면이 그 쪽 머리로 굴린다). 사람이 굴려 바뀐 쪽은 올리지 않는다.
    @Published private(set) var continuousJump = 0
    /// 이어서: 화면 90% 굴리기(+1 · −1)와 그 표.
    @Published private(set) var continuousStep = (step: 0, token: 0)
    /// 이어서: 굴리는 중의 가운데 쪽(알약).
    @Published private(set) var continuousCenter = 0
    /// 확대 중이면 그 시작(누른 자리 · 배율).
    @Published var pdfZoom: (anchor: CGPoint, scale: CGFloat)?
    /// 확대한 쪽의 또렷한 그림(늦게 온다).
    @Published private(set) var pdfZoomImage: UIImage?
    private var enterBottom = false
    private var fromScroll = false
    private var keepTask: Task<Void, Never>?
    private var panStart: CGFloat?

    /// 폭 맞춤의 쪽 그림 높이(화면 위 점).
    var pdfStripHeight: CGFloat {
        guard let img = frame?.image, img.size.width > 0 else { return 0 }
        return pdfArea.width * img.size.height / img.size.width
    }
    private var pdfMaxOffset: CGFloat { max(pdfStripHeight - pdfArea.height, 0) }

    private func pdfPageChanged() {
        pdfZoom = nil
        pdfZoomImage = nil
        if let z = engine as? any ZoomSource { Task { pdfPageRect = await z.pageRect() } }
        if pdfFit == .width {
            // 앞 쪽으로 넘겼으면 그 쪽의 끝부터(이어 읽는 자리), 아니면 저장된 자리(처음 열 때) · 머리.
            pdfOffset = enterBottom ? pdfMaxOffset : min(CGFloat(frame?.pdfY ?? 0) / 1000 * pdfStripHeight, pdfMaxOffset)
            enterBottom = false
        }
        if pdfFit == .continuous && !fromScroll { continuousJump += 1 }
        fromScroll = false
    }

    /// 폭 맞춤: 쪽 안에서 한 화면(10% 겹침)씩. 쪽 끝이면 false — 넘긴다.
    private func scrollWithinPage(_ step: Int) -> Bool {
        let limit = pdfMaxOffset
        guard step > 0 ? pdfOffset < limit - 0.5 : pdfOffset > 0.5 else { return false }
        withAnimation(.easeOut(duration: 0.25)) {
            pdfOffset = min(max(pdfOffset + CGFloat(step) * pdfArea.height * 0.9, 0), limit)
        }
        pdfMoved()
        return true
    }

    private func pdfMoved() {
        pdfPill += 1
        keepTask?.cancel()
        // 손을 멈추고 0.15초 뒤에 자리를 저장한다(안드로이드 SETTLE_MS).
        keepTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(150))
            guard let self, !Task.isCancelled, let pdf = self.engine as? PdfEngine, let page = self.frame?.page else { return }
            let y = self.pdfStripHeight > 0 ? Int((self.pdfOffset / self.pdfStripHeight * 1000).rounded()) : 0
            if let f = try? await pdf.keep(page, y: y) { self.frame = f }
        }
    }

    /// 폭 맞춤: 위아래로 끌기(쪽 안 이동).
    func pan(_ dy: CGFloat, ended: Bool) {
        guard isPdf, pdfFit == .width else { return }
        if panStart == nil { panStart = pdfOffset }
        pdfOffset = min(max((panStart ?? 0) - dy, 0), pdfMaxOffset)
        pdfPill += 1
        if ended {
            panStart = nil
            pdfMoved()
        }
    }

    /// 옆으로 밀었다: 폭 맞춤은 쪽 안을 옮기지 않고 바로 넘긴다(앞 쪽은 끝부터). 다른 보기는 누름과 같다.
    func swipe(_ step: Int) async {
        if isPdf && pdfFit == .width {
            enterBottom = step < 0
            await turn(step, pagewise: true)
        } else if isPdf && pdfFit == .continuous {
            guard let pdf = engine as? PdfEngine else { return }
            let target = continuousCenter + step
            guard target >= 0, target < pdf.pageCount else { return }
            await jump { _ in try await pdf.keep(target, y: 0) }
        } else {
            await turn(step)
        }
    }

    /// 이어서: 손을 멈췄다 — 맨 위 쪽 · 그 쪽 안 자리를 저장하고, 가운데 쪽을 아래 줄 · 진도로.
    func continuousSettled(top: Int, y: Int, center: Int) async {
        guard let pdf = engine as? PdfEngine, let f = try? await pdf.keep(top, y: y, shown: center) else { return }
        if f.key != frame?.key {
            fromScroll = true
            turnCause = center > (frame?.page ?? 0) ? .forward : .backward
        }
        frame = f
    }

    func continuousScrolled(center: Int) {
        if center != continuousCenter { continuousCenter = center }
        pdfPill += 1
    }

    /// 확대를 시작한다(두 번 누르기 · 두 손가락). 두 쪽 · 이어서에서는 하지 않는다.
    func startZoom(at point: CGPoint, scale: CGFloat) {
        guard isFixed, !(isPdf && pdfFit == .continuous), frame?.hasRight != true else { return }
        pdfZoom = (point, scale)
        Task {
            guard let z = engine as? any ZoomSource, let page = frame?.page else { return }
            let size = pdfZoomBase?.size ?? .zero
            pdfZoomImage = await z.zoomed(page, size: size, zoom: 3)
        }
    }

    /// 확대 1 에서 쪽이 놓인 자리(화면 좌표).
    var pdfZoomBase: CGRect? {
        guard isFixed, let img = frame?.image, img.size.width > 0 else { return nil }
        if isPdf && pdfFit == .width {
            return CGRect(x: pdfArea.minX, y: pdfArea.minY - pdfOffset, width: pdfArea.width, height: pdfStripHeight)
        }
        return pdfPageRect
    }
    var pdfPageRect: CGRect?
    /// 이어서의 쪽마다 높이 ÷ 폭.
    @Published private(set) var pdfAspects: [CGFloat] = []

    // ── 만화(구상안 35A · 36) ──────────────────────────────────────────────

    @Published private(set) var isComic = false
    /// 이 권의 작품(방향 · 다음 권 · 표지 설정). 작품 묶기에 없으면 nil.
    @Published private(set) var comicWork: Work?
    /// 권 끝 판을 띄운다.
    @Published var comicEnd = false
    private(set) var comicLabel = ""
    private weak var library: Library?

    /// 작품 설정을 바꾼다(넘기는 방향 · 표지) — 서재가 다시 묶고, 이 권의 작품도 새 값으로.
    func setComicSetting(_ kind: String, value: String?) {
        guard let library, let work = comicWork else { return }
        library.setComicSetting(kind, work: work, value: value)
        comicWork = library.work(of: openBookId)
    }

    /// 웹툰으로 보는 중(구상안 39). nil 이면 쪽 넘김.
    @Published private(set) var webtoon: WebtoonModel?
    private var comicFileRef: Library.ComicFile?
    private var comicEntry: WorkEntry?
    private var lastComicSpec: ComicViewSpec?

    /// 이 권을 웹툰으로 연다(같은 파일을 한 번 더 연다 — 쪽 넘김 엔진과 그림 읽기가 섞이지 않게).
    private func openWebtoon() async {
        guard let file = comicFileRef, let library else { return }
        let id = openBookId, label = comicLabel, entry = comicEntry
        let episode: WebtoonEpisode? = await Task.detached(priority: .userInitiated) {
            let book: IosComicBook? = if let names = file.images {
                IosComicBook.companion.openFolder(path: file.url.path, fileNames: names)
            } else {
                IosComicBook.companion.openArchive(path: file.url.path)
            }
            return book.map { WebtoonEpisode(entry: entry, unitId: id, label: label, book: $0) }
        }.value
        guard let episode else { return }
        webtoon = WebtoonModel(first: episode, work: comicWork, store: library.store, library: library)
    }

    /// 보는 방식을 바꿨다(자동 · 쪽 넘김 · 웹툰 — nil 은 자동). 작품마다 기억하고 곧바로 그 방식으로 다시 연다.
    func setComicView(_ value: String?) async {
        setComicSetting("VIEW", value: value)
        guard let comic = engine as? ComicEngine else { return }
        let sizes = await comic.rawSizeList()
        let wantToon = Webtoon.shared.view(chosen: comicWork?.view, info: comicEntry?.unit.info, sizes: sizes) == .webtoon
        if wantToon, webtoon == nil {
            await openWebtoon()
        } else if !wantToon, let toon = webtoon {
            // 웹툰에서 읽던 그림에서 쪽 넘김을 잇는다.
            toon.close()
            webtoon = nil
            panel = .none
            await comic.resume()
            if let spec = lastComicSpec { await layOutComic(spec) }
        }
    }

    /// 표지를 작품마다 바꾼 수(모든 보기 설정의 풀이).
    var comicCoverCount: Int { library?.store.comicOverrides().coverAlone.count ?? 0 }

    /// PDF 에 읽을 글이 있나. 없으면(스캔본) 안내를 띄우고 false(구상안 34A). 책 · 만화는 늘 true.
    func pdfTextOrNotice() async -> Bool {
        guard let pdf = engine as? PdfEngine else { return true }
        if await pdf.hasText() { return true }
        say("이 PDF는 글자가 그림으로 되어 있어(스캔본) 찾기 · 듣기 · 형광펜을 쓸 수 없습니다")
        return false
    }

    /// 쪽이 고정된 책(PDF · 만화) — 확대 · 쪽 칸 · 넘김 자리가 같은 길이다.
    var isFixed: Bool { isPdf || isComic }

    /// 다음 권(권 끝 판의 "이어서 읽기").
    var nextEntry: WorkEntry? {
        guard let work = comicWork else { return nil }
        return ComicReading.shared.nextAfter(work: work, unitId: openBookId)
    }

    func layOutComic(_ spec: ComicViewSpec) async {
        lastComicSpec = spec
        // 웹툰으로 보는 동안은 쪽을 내지 않는다 — 쪽을 내면 그 쪽이 진도로 적혀 웹툰의 자리(그림 안 비율)를 지운다.
        guard webtoon == nil, let comic = engine as? ComicEngine else { return }
        darkPaper = true
        pdfArea = spec.area
        pdfSpread = spec.twoPages
        frame = await comic.layOut(spec)
        pdfPageRect = await comic.pageRect()
    }

    /// PDF 인가(구상안 32A). 보기 판 · 위 막대 · 설정 줄이 갈린다.
    @Published private(set) var isPdf = false
    /// PDF 를 두 쪽으로 보고 있다 — 보기 판에 "표지" 줄이 나온다.
    @Published private(set) var pdfSpread = false
    private(set) var openBookId = ""

    /// PDF 쪽을 (다시) 낸다 — 화면 크기 · 지면 · 쪽 맞춤 · 두 쪽이 바뀔 때.
    func layOutPdf(_ spec: PdfViewSpec) async {
        guard let pdf = engine as? PdfEngine else { return }
        darkPaper = spec.paper.dark
        pdfSpread = spec.twoPages
        let fitChanged = pdfFit != spec.fit
        pdfFit = spec.fit
        pdfArea = spec.pageArea
        // 쪽 맞춤을 바꾸면 그 쪽의 머리에서 — 폭 맞춤의 쪽 안 자리를 쪽 전체로 가져가면 뜻이 없다.
        if fitChanged { fromScroll = false }
        do {
            let f = try await pdf.layOut(spec)
            frame = f
            pdfPageRect = await pdf.pageRect()
            if spec.fit == .continuous && pdfAspects.isEmpty { pdfAspects = await pdf.aspects() }
            // 같은 쪽을 다시 냈다(화면 크기 · 쪽 맞춤) — 자리를 다시 맞춘다.
            if spec.fit == .width { pdfOffset = min(CGFloat(f.pdfY) / 1000 * pdfStripHeight, pdfMaxOffset) }
            if spec.fit == .continuous { continuousJump += 1 }
        } catch { failure = PdfOpenError.broken.message }
    }

    func layOut(_ spec: LayoutSpec, _ paper: Paper, blend: Bool, highlights: Bool = true, spread: Spread? = nil) async {
        guard let engine else { return }
        showHighlights = highlights
        darkPaper = paper.dark
        await engine.setShowHighlights(highlights)
        guard let engine = engine as? ReaderEngine else { return }
        do { frame = try await engine.layOut(spec, paper, blend: blend, spread: spread) } catch { failure = "쪽을 짜지 못했습니다." }
        // 처음 책 글꼴로 짤 때 꺼내 본다. 하나도 읽히지 않으면(WOFF 뿐 · 깨짐) 목록에서 "출판사 글꼴" 을 거둔다.
        bookFonts = await engine.hasBookFonts()
        bookFontPreview = await engine.bookFontPreviewPath()
    }

    /// [quiet] 면 소리 · 진동 없이(자동 넘김 — 30초마다 종이 소리가 나면 거슬린다). 책의 처음 · 끝이라 넘기지 못했으면 false.
    @discardableResult
    func turn(_ step: Int, quiet: Bool = false, pagewise: Bool = false) async -> Bool {
        guard let engine else { return false }
        if panel != .none { panel = .none; return false }
        lastActivity = Date()
        // PDF 이어서: 누름 · 자동 넘김은 화면 90% 굴리기. 폭 맞춤: 쪽 안에서 한 화면씩 내리다 쪽 끝에서 넘긴다(앞 쪽은 끝부터).
        if isPdf && pdfFit == .continuous {
            continuousStep = (step, continuousStep.token + 1)
            return true
        }
        if isPdf && pdfFit == .width && !pagewise {
            if scrollWithinPage(step) { return true }
            enterBottom = step < 0
        }
        let next = try? await (step > 0 ? engine.next() : engine.previous())
        guard let next else {
            // 만화의 마지막 판에서 더 넘기면 권 끝 판(구상안 36). 책의 처음 · 끝은 아무 일도 없다.
            if isComic && step > 0 && !quiet { comicEnd = true }
            return false
        }
        // 쪽이 바뀌면 고르기를 푼다(이어 고르기는 "이어서 ›" 로만 — 그냥 넘기면 고르던 것을 들고 가지 않는다).
        selection = nil
        tapped = nil
        direction = step
        turnCount += 1
        turnCause = quiet ? .machine : step > 0 ? .forward : .backward
        // 사람이 넘긴 쪽만 효과 · 소리(안드로이드 PageTurnState.request) — 글자 크기 · 회전 · 목차 이동은 순간 이동이다(바뀐 조판이
        // 말리면 "넘긴 것" 으로 읽힌다).
        flipFor = "\(next.spine):\(next.page)"
        if turnEffect == 0 || turnEffect == 4 || UIAccessibility.isReduceMotionEnabled {
            frame = next
        } else {
            withAnimation(.easeOut(duration: turnEffect == 1 ? 0.25 : 0.22)) { frame = next }
        }
        if !quiet { feedback(step) }
        return true
    }

    // ── 자동 넘김(구상안 26) ────────────────────────────────────────────────

    /// ✕ 로 껐다 — 이번에 연 동안만. 설정은 그대로라 다음에 책을 열면 다시 돈다.
    @Published var autoOff = false
    /// 알약의 멈춤(또는 책 끝).
    @Published var autoPaused = false
    /// 다음 쪽까지 남은 초(알약).
    @Published var autoLeft = 0

    // ── 넘김 효과 · 소리 · 진동(구상안 23A) ──────────────────────────────────

    /// 0 없음 · 1 서서히 · 2 밀기 · 3 덮기 · 4 말림.
    var turnEffect = 4
    var turnSound = 0
    var turnHaptic = false
    var screenWidth: CGFloat = 0
    var landscape = false
    /// 넘긴 차례(덮기에서 새 쪽 · 옛 쪽의 겹침 차례를 정한다).
    @Published private(set) var turnCount = 0
    /// 사람이 넘겨 보이게 된 쪽의 열쇠 — 말림은 이 쪽으로 바뀔 때만 만다.
    @Published private(set) var flipFor = ""
    /// 말림이 손가락을 따라가게 미리 그린 앞 · 뒤 쪽.
    @Published private(set) var prevImage: UIImage?
    @Published private(set) var nextImage: UIImage?

    /// 손가락으로 다 말았다: 자리를 옮긴다(그림은 이미 넘어가 있다).
    func commitFlip(_ step: Int) async {
        guard let engine else { return }
        let next = try? await (step > 0 ? engine.next() : engine.previous())
        guard let next else { return }
        selection = nil
        tapped = nil
        lastActivity = Date()
        direction = step
        turnCause = step > 0 ? .forward : .backward
        frame = next
        feedback(step)
    }

    // ── 남은 시간(구상안 28) ────────────────────────────────────────────────

    /// 읽는 빠르기(모든 책에 하나).
    @Published private(set) var speed = ReadingSpeed.load()
    /// 다음 쪽 변화의 까닭. 쪽이 바뀌면 읽고 지운다 — 까닭을 적지 않은 변화(목차 · 찾기 · 진도 막대 · 링크 · 다시 짜기)는 건너뛴 것.
    private var turnCause = TurnCause.jump
    /// 지금 쪽(펼침)을 보이기 시작한 때와 그 글자 수. 사람이 바로 다음 쪽으로 넘기면 그 사이가 표본 하나다.
    private var sampleStart: (at: Date, chars: Int)?

    /// 쪽이 바뀌었다(안드로이드 `TurnSampler`). 자동 넘김 · 듣기가 넘긴 쪽, 건너뛴 쪽은 세지 않는다 — 기계의 빠르기나 목차를 고르던
    /// 시간이 섞이면 남은 시간이 엉뚱해진다. 기계가 넘기다 사람이 이어받으면 그때부터 다시 잰다.
    private func sampleTurn(_ cause: TurnCause) {
        if cause == .forward, let start = sampleStart {
            speed.record(units: start.chars, seconds: Date().timeIntervalSince(start.at))
            speed.save()
        }
        guard let frame, cause == .forward || cause == .backward else { sampleStart = nil; return }
        // 분량: 책은 그 쪽의 글자, PDF 는 보인 쪽 수(두 쪽이면 2 — 안드로이드 `state.shown.size`).
        sampleStart = (Date(), frame.isPdf ? (frame.hasRight ? 2 : 1) : frame.pageEnd - frame.pageStart)
    }

    func prefetch() async {
        guard let engine, turnEffect == 4 else { prevImage = nil; nextImage = nil; return }
        nextImage = await engine.peek(1)
        prevImage = await engine.peek(-1)
    }

    private func feedback(_ step: Int) {
        TurnFeedback.shared.turned(sound: turnSound, haptic: turnHaptic, forward: step > 0, widthPt: screenWidth, landscape: landscape)
    }

    func seek(_ percent: Double) async {
        guard let engine, let f = try? await engine.seek(percent) else { return }
        lastActivity = Date()
        direction = f.percent >= (frame?.percent ?? 0) ? 1 : -1
        frame = f
    }

    /// 내보내기를 열 때 독서노트에서 보던 칩.
    var exportFilter = 0

    /// 각주 판 · 바깥 주소 판(구상안 18).
    @Published var footnote: FootnoteItem?
    @Published var external: String?

    /// 쪽을 눌렀다. 링크가 먼저다 — 그 자리가 "다음 쪽" 자리여도 넘기지 않는다. 링크가 아니면 [otherwise](넘김 · 메뉴).
    func tap(at point: CGPoint, otherwise: @escaping () -> Void) async {
        // 고르는 중 · 칠한 곳 메뉴가 떠 있으면 그것만 닫는다 — 쪽이 넘어가면 고른 것을 잃는다.
        if selection != nil || tapped != nil { clearSelection(); return }
        guard let engine, let action = try? await engine.tapLink(point.x, point.y) else { otherwise(); return }
        switch action.kind {
        case "footnote":
            footnote = FootnoteItem(title: action.title, text: action.text, spine: Int(action.spine), anchor: action.anchor)
        case "jump":
            if let f = action.frame { frame = PageFrame(f); lastActivity = Date() }
        case "external":
            external = action.url
        case "note":
            if let n = action.note { await openTapped(HighlightItem(n)) }
        default:
            say(action.text)
        }
    }

    // ── 형광펜 · 메모(구상안 19A · 20) ────────────────────────────────────────

    /// 고른 구간(장 안 글자 자리)과 그 네모들. nil 이면 고르지 않았다.
    @Published var selection: TextSelection?
    /// 누른 칠(메뉴를 띄운다).
    @Published var tapped: TappedHighlight?
    /// 쓰는 중인 메모.
    @Published var memo: MemoDraft?
    /// 사전 · 공유 판에 넘길 글.
    @Published var lookUp: SharedText?
    @Published var shareOut: SharedText?
    /// 독서노트를 다시 읽을 때(칠 · 메모 · 지우기마다 하나씩 오른다).
    @Published private(set) var notesVersion = 0
    /// 지면이 어두운가 — 독서노트의 칠 바탕을 옅게.
    var darkPaper = false
    /// 마지막으로 고른 펜 — 메모 판이 그 색으로 연다.
    var lastPen = 0
    /// 형광펜 숨김이면 칠할 때 알린다(아무 일도 없어 보이면 저장이 안 된 줄 안다).
    var showHighlights = true

    /// 길게 누른 자리의 낱말을 고른다. 판 · 메뉴가 열려 있으면 하지 않는다.
    func select(at point: CGPoint) async {
        guard panel == .none, let engine, let (s, e) = try? await engine.wordAt(point) else { return }
        tapped = nil
        await setSelection(s, e)
    }

    private func setSelection(_ start: Int, _ end: Int) async {
        guard let engine, end > start else { return }
        let boxes = (try? await engine.boxes(start, end)) ?? []
        let (can, before) = (try? await engine.selectionState(start, end)) ?? (false, 0)
        selection = boxes.isEmpty ? nil : TextSelection(start: start, end: end, boxes: boxes, canContinue: can, charsBefore: before)
    }

    /// "이어서 ›": 쪽을 넘기고 고른 것을 그대로 들고 간다(끝은 새 쪽 첫 문장 끝까지).
    func continueSelection() async {
        guard let engine, let sel = selection, let (f, s, e) = try? await engine.continueSelection(sel.start, sel.end) else { return }
        direction = 1
        frame = f
        await setSelection(s, e)
    }

    /// 손잡이를 끈 자리 → 새 구간. 시작 손잡이는 끝을 넘지 못하고 끝 손잡이는 시작을 넘지 못한다(최소 한 글자) — 넘기면 구간이
    /// 뒤집혀 메뉴가 엉뚱한 곳에 뜬다(안드로이드 `dragHandle`). 고를 글자가 없는 자리면 그대로 둔다.
    func dragHandle(start isStart: Bool, to point: CGPoint) async {
        guard let engine, let sel = selection,
              let at = try? await engine.charAt(point, after: !isStart), at >= 0 else { return }
        if isStart { await setSelection(min(at, sel.end - 1), sel.end) } else { await setSelection(sel.start, max(at, sel.start + 1)) }
    }

    func clearSelection() {
        selection = nil
        tapped = nil
    }

    /// 고른 구간을 칠한다(알약의 색 동그라미).
    func paint(pen: Int) async {
        guard let engine, let sel = selection, let f = try? await engine.highlight(sel.start, sel.end, pen: pen, note: nil) else { return }
        lastPen = pen
        frame = f
        selection = nil
        notesVersion += 1
        if !showHighlights { say("형광펜을 숨겨 둔 상태라 보이지 않습니다. 보기 설정에서 켤 수 있습니다.") }
    }

    func openTapped(_ item: HighlightItem) async {
        guard let engine else { return }
        let boxes = (try? await engine.boxes(item.start, item.end)) ?? []
        selection = nil
        tapped = boxes.isEmpty ? nil : TappedHighlight(item: item, boxes: boxes)
    }

    func recolor(_ item: HighlightItem, pen: Int) async {
        guard let engine, let f = try? await engine.recolor(item.id, pen: pen) else { return }
        lastPen = pen
        frame = f
        tapped = nil
        notesVersion += 1
    }

    func removeHighlight(_ item: HighlightItem) async {
        guard let engine, let f = try? await engine.removeNote(item.id) else { return }
        frame = f
        tapped = nil
        notesVersion += 1
        say("형광펜을 지웠습니다")
    }

    /// 고른 말 · 칠의 글(복사 · 공유 · 사전 · 메모 인용).
    func quote() async -> String {
        if let t = tapped { return t.item.snippet }
        guard let engine, let sel = selection else { return "" }
        return (try? await engine.textOf(sel.start, sel.end)) ?? ""
    }

    /// 메모 판을 연다. 새로 고른 말이면 "저장" 할 때 비로소 칠한다 — 취소하면 아무것도 남지 않는다.
    func startMemo() async {
        let text = await quote()
        if let t = tapped {
            memo = MemoDraft(noteId: t.item.id, start: t.item.start, end: t.item.end, quote: text, pen: t.item.pen, text: t.item.note ?? "")
        } else if let sel = selection {
            memo = MemoDraft(noteId: nil, start: sel.start, end: sel.end, quote: text, pen: lastPen, text: "")
        }
        selection = nil
        tapped = nil
    }

    func saveMemo(_ draft: MemoDraft, text: String, pen: Int) async {
        memo = nil
        guard let engine else { return }
        lastPen = pen
        let f: PageFrame?
        if let id = draft.noteId {
            _ = try? await engine.recolor(id, pen: pen)
            f = try? await engine.setNote(id, text)
        } else {
            f = try? await engine.highlight(draft.start, draft.end, pen: pen, note: text)
        }
        if let f { frame = f }
        notesVersion += 1
        if !showHighlights { say("형광펜을 숨겨 둔 상태라 보이지 않습니다. 보기 설정에서 켤 수 있습니다.") }
    }

    /// 독서노트에서 칠한 곳으로.
    func goToHighlight(_ id: Int64) async {
        await jump { try await $0.goToNote(id) }
    }

    /// 각주 판의 "각주 자리로 가기": 그 자리를 칠해 보인다(주석 장에는 비슷한 문단이 줄지어 있다).
    func goToFootnote(_ note: FootnoteItem) async {
        footnote = nil
        guard let engine, let f = try? await engine.jumpTo(note.spine, note.anchor, markLength: note.text.count) else { return }
        frame = f
    }

    func returnBack() async {
        guard let engine, let f = try? await engine.returnBack() else { return }
        frame = f
    }

    /// 찾기(구상안 16A · 17). 판이 닫혀도 결과는 남는다.
    let search = BookSearch()

    func startSearch() {
        guard let engine else { return }
        search.start(engine)
    }

    /// 결과를 연다: 그 쪽으로 가서 찾은 말을 칠하고 판을 닫는다(결과 막대가 뜬다).
    func openHit(_ index: Int) async {
        guard let engine, index >= 0, index < search.results.count,
              let f = try? await engine.goToHit(search.results[index]) else { return }
        search.current = index
        lastActivity = Date()
        frame = f
        panel = .none
    }

    /// 찾기를 끝냈다(결과 막대의 × · 칸 비우기): 칠을 지운다.
    func endSearch() async {
        search.current = -1
        guard let engine, let f = try? await engine.clearMark() else { return }
        frame = f
    }

    /// 짧은 알림(책갈피를 꽂았습니다 등). 화면이 1.5초 뒤 지운다.
    @Published var toast: String?
    @Published private(set) var toastCount = 0

    func say(_ message: String) {
        toast = message
        toastCount += 1
    }

    func toggleBookmark() async {
        guard let engine, let f = try? await engine.toggleBookmark() else { return }
        frame = f
        // 책 · PDF · 만화가 같은 말을 쓴다(안드로이드 bookmarkMessage).
        say(f.bookmarked ? "책갈피를 꽂았습니다" : "책갈피를 뺐습니다")
    }

    /// 독서노트에서 지운다. 보던 쪽 것이면 리본도 바로 빠진다(엔진이 쪽을 다시 낸다).
    func removeBookmark(_ id: Int64) async {
        guard let engine, let f = try? await engine.removeBookmark(id) else { return }
        frame = f
        say("책갈피를 지웠습니다")
    }

    /// 목차 · 책갈피에서 골랐다: 그 쪽으로 가고 판을 닫는다(메뉴로 돌아가지 않고 바로 지면으로 — 안드로이드와 같다).
    func jump(_ go: (any BookEngine) async throws -> PageFrame?) async {
        guard let engine, let f = try? await go(engine) else { return }
        lastActivity = Date()
        frame = f
        panel = .none
    }

    func back() {
        switch panel {
        case .settings, .fonts: panel = .view
        case .footer, .touchZones: panel = .settings
        case .footerItem: panel = .footer
        case .export: panel = .contents
        // 듣는 중이면 듣기 판으로 돌아온다 — 판에서 "목소리" 를 눌러 왔다.
        case .voices: panel = .none; listenSheet = listening.active
        case .view, .contents, .search, .pageGrid, .comicMarks: panel = .bar
        case .bar, .none: panel = .none
        }
    }

    // ── 듣기(구상안 24 · 25A) ───────────────────────────────────────────────

    let listening = Listening()
    /// 하단 정보에서 고르는 자리(0 왼쪽 · 1 가운데 · 2 오른쪽).
    var footerSlot = 0
    /// 듣기 판을 열어 두었다. 듣기가 꺼지면 보이지 않는다.
    @Published var listenSheet = false

    /// 위 막대의 헤드폰: 듣던 것이 있으면 이어 듣고, 아니면 보이는 쪽에서 시작하는 첫 문장부터.
    func listen(rate: Double, voice: String?, join: Int) async {
        panel = .none
        if listening.active { listening.play(); return }
        guard let frame else { return }
        listening.source = self
        await listening.start(spine: frame.spine, offset: frame.pageStart, pageEnd: frame.pageEnd, rate: rate, voice: voice, join: join)
    }

    /// 듣기를 껐다(조종판 ✕ · 책 닫기): 문장 칠을 지운다.
    func stopListening() async {
        listenSheet = false
        if listening.active { listening.close() }
        if let engine, let f = try? await engine.stopSpeaking() { frame = f }
    }

    func close() async {
        webtoon?.close()
        clock?.pause()
        if listening.active { listening.close() }
        lapListen()
        listenLaps?.cancel()
        await engine?.close()
        endAccess?()
        endAccess = nil
    }
}

extension ReaderModel: ListenSource {
    func speech(_ spine: Int) async -> [SpokenSentence] { await engine?.speechSentences(spine) ?? [] }
    func spoken(_ spine: Int, _ sentence: SpokenSentence, join: Int) async -> String { await engine?.spoken(spine, sentence, join: join) ?? "" }
    func unitCount() async -> Int { (try? await engine?.chapterCount()) ?? 0 }

    /// 듣는 문장을 칠하고 쪽을 따라간다. 듣기가 넘긴 쪽은 효과 · 소리 없이 바뀐다 — 화면을 끈 채 듣는 동안에도 불린다.
    func follow(_ spine: Int, _ sentence: SpokenSentence, follow: Bool) async {
        guard let engine, listening.active, let f = try? await engine.speak(spine, sentence, follow: follow) else { return }
        if f.key != frame?.key { selection = nil; tapped = nil; direction = 1; turnCause = .machine }
        frame = f
    }
}

/// 리더(구상안 2A · 4A · 6A 확정). 지면은 화면 전체 — 양쪽 30% 는 넘김(어느 쪽이 다음인지는 터치 영역, 구상안 37), 가운데 40% 는
/// 메뉴. 옆으로 밀어도 넘어간다.
struct ReaderView: View {
    let book: LibraryBook
    let library: Library
    /// 보던 장면에서 표지를 고르는 중인 작품(구상안 44 ④). 읽기는 그대로 되고(넘기면 자리도 적힌다) 위 · 아래에 고르기 막대.
    var pickCover: String?
    let onClose: () -> Void
    /// 다른 책(만화 다음 권)을 연다.
    var openBook: ((LibraryBook) -> Void)?

    @StateObject private var model = ReaderModel()
    @StateObject private var prefs = ReaderPrefs()
    @StateObject private var brightness = ScreenBrightness()
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 앱이 뒤로 갈 때 보던 쪽(듣는 동안 쪽이 넘어갔는지 견준다).
    @State private var leftAt: String?
    #if DEBUG
    @State private var debugApplied = false
    #endif

    var body: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            let full = CGSize(width: geometry.size.width + insets.leading + insets.trailing,
                              height: geometry.size.height + insets.top + insets.bottom)
            // 만화는 늘 어두운 바탕(구상안 36) — 메뉴도 이 밝기를 따라 어둡다.
            let paper = model.isComic ? comicPaper : prefs.theme.paper(scheme)
            ZStack {
                paper.paperColor
                if let frame = model.frame {
                    let key = "\(frame.spine):\(frame.page)"
                    if model.isPdf && pdfFit == .continuous, let pdf = model.engine as? PdfEngine {
                        PdfContinuousView(aspects: model.pdfAspects, area: model.pdfArea, gap: UIColor(paper.gapColor),
                                          paper: UIColor(paper.paperColor), target: (frame.page, frame.pdfY), jumpToken: model.continuousJump,
                                          step: model.continuousStep.step, stepToken: model.continuousStep.token,
                                          image: { i, w in await pdf.continuousStrip(i, width: w) },
                                          onTap: { p in pageTap(p, size: full, insets: insets) },
                                          onSwipe: { s in Task { await model.swipe(s) } },
                                          onSettle: { t, y, c in Task { await model.continuousSettled(top: t, y: y, center: c) } },
                                          onScroll: { c in model.continuousScrolled(center: c) })
                            .ignoresSafeArea()
                            // 쪽을 놓는 칸 밖(노치 · 아래 줄)에는 보이지 않게 — 아래 줄 글자가 쪽 글자와 겹쳤다.
                            .mask(Rectangle().frame(height: model.pdfArea.height).position(x: full.width / 2, y: model.pdfArea.midY).ignoresSafeArea())
                            .accessibilityLabel("\(model.title), \(frame.page + 1)쪽")
                        PdfPositionPill(text: "\(model.continuousCenter + 1)쪽 · \(frame.pages)쪽 중", area: model.pdfArea, token: model.pdfPill)
                    } else if model.isPdf && pdfFit == .width {
                        PdfWidthPage(image: frame.image, area: model.pdfArea, offset: model.pdfOffset)
                            .id(key)
                            .transition(pageTransition)
                            .accessibilityLabel("\(model.title), \(frame.page + 1)쪽")
                        if let label = PdfPositionPill.screenLabel(page: frame.page, offset: model.pdfOffset,
                                                                   height: model.pdfStripHeight, viewport: model.pdfArea.height) {
                            PdfPositionPill(text: label, bar: (model.pdfOffset / model.pdfStripHeight, model.pdfArea.height / model.pdfStripHeight),
                                            area: model.pdfArea, token: model.pdfPill)
                        }
                    } else if curl {
                        // 오→왼(만화): 왼쪽 가장자리를 말아 다음 쪽 — 앞 · 뒤 쪽 그림과 방향을 맞바꾼다.
                        CurlPager(image: frame.image, key: key, previous: rtl ? model.nextImage : model.prevImage,
                                  next: rtl ? model.prevImage : model.nextImage,
                                  paper: UIColor(paper.paperColor), spread: twoPages(full),
                                  animate: model.flipFor == key ? (rtl ? -model.direction : model.direction) : 0,
                                  onFlip: { step in Task { await model.commitFlip(rtl ? -step : step) } },
                                  onTap: { p in pageTap(p, size: full, insets: insets) },
                                  onLongPress: { p in Task { await model.select(at: p) } },
                                  zoom: pdfZoomable ? { p, s in model.startZoom(at: p, scale: s) } : nil)
                            .accessibilityLabel("\(model.title), \(frame.page + 1)쪽")
                            // 한 쪽 ↔ 두 쪽은 책등 자리가 달라 말림을 새로 만든다(UIPageViewController 는 책등을 만들 때 정한다).
                            .id(twoPages(full))
                    } else {
                        Image(uiImage: frame.image).resizable()
                            .id(key)
                            .transition(pageTransition)
                            .zIndex(Double(model.turnCount) * (model.direction > 0 ? 1 : -1))
                            // 넘길 때마다 zIndex 가 커져 첫 넘김 뒤 그림이 넘김 자리(TurnZones) 위로 올라온다 — 그림이 누름을 받으면
                            // "말림" 밖의 효과(없음 · 서서히 · 밀기 · 덮기)에서 두 번째 누름부터 아무 일도 없었다.
                            .allowsHitTesting(false)
                            .accessibilityLabel("\(model.title), \(frame.page + 1)쪽")
                    }
                    // 윗여백 안에서 끝나 글자를 가리지 않는다. 노치 · 다이내믹 아일랜드가 있으면 그 아래로.
                    if frame.bookmarked {
                        CpRibbon().padding(.top, insets.top).padding(.trailing, 20 + insets.trailing)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                            .allowsHitTesting(false)
                    }
                    // 아래 줄은 화면 끝에서 14(구상안 13A, 안드로이드와 같다) — 읽는 동안 홈 표시줄을 숨긴다. 좌우는 본문 여백 선:
                    // 안드로이드의 16 은 iPhone 의 둥근 모서리에 글자가 잘린다.
                    // 만화에는 아래 줄이 없다(안드로이드와 같다) — 쪽 그림이 화면 끝까지 쓴다.
                    if !model.isComic {
                        ReadingFooter(title: model.title, frame: frame, items: prefs.footer, speed: model.speed, ink: paper.inkMutedColor)
                            .padding(.horizontal, CGFloat(ReaderPrefs.margins[prefs.margin]) + max(insets.leading, insets.trailing))
                            .frame(maxHeight: .infinity, alignment: .bottom)
                            .padding(.bottom, 14)
                            .allowsHitTesting(false)
                    }
                    if !frame.brokenPages.isEmpty {
                        Text("이 쪽을 그리지 못했습니다").font(CpFont.subtitle).foregroundStyle(paper.inkMutedColor).allowsHitTesting(false)
                    }
                }
                // 오→왼(만화): 왼쪽을 누르거나 오른쪽으로 밀면 다음 쪽 — 넘김 자리만 뒤집고 그림은 그대로.
                TurnZones(cornerTop: insets.top, cornerRight: insets.trailing, zones: zones, rightToLeft: rtl,
                          turn: { step in Task { await model.turn(step) } },
                          tap: { point, otherwise in Task { await model.tap(at: point, otherwise: otherwise) } },
                          longPress: { point in Task { await model.select(at: point) } },
                          bookmark: { Task { await model.toggleBookmark() } },
                          toggleMenu: {
                              // 표지를 고르는 동안은 메뉴를 띄우지 않는다 — 고르기 막대와 겹친다.
                              guard picking == nil else { return }
                              withAnimation(.easeOut(duration: 0.18)) { model.panel = model.panel == .none ? .bar : .none }
                          },
                          swipe: { step in Task { await model.swipe(rtl ? -step : step) } },
                          pan: model.isPdf && pdfFit == .width ? { dy, ended in model.pan(dy, ended: ended) } : nil,
                          zoom: pdfZoomable ? { p, s in model.startZoom(at: p, scale: s) } : nil)
                    // 말림이면 끌기는 말림이 받는다(위에 제스처 층이 있으면 그 층이 끌기를 가로챈다). 누름은 말림이 pageTap 으로 넘긴다.
                    // PDF 이어서는 굴리는 판이 누름 · 밀기를 받는다.
                    .allowsHitTesting(!curl && !(model.isPdf && pdfFit == .continuous))
                // PDF 확대(구상안 33): 넘김 자리 위에 — 확대한 동안의 끌기 · 두 손가락은 이 판이 받는다.
                if let z = model.pdfZoom, let base = model.pdfZoomBase, let frame = model.frame {
                    PdfZoomView(base: base, area: model.pdfArea,
                                image: model.pdfZoomImage ?? (model.isPdf && pdfFit == .width ? frame.image : frame.image.cropped(to: base)),
                                anchor: z.anchor, scale: z.scale, paper: UIColor(paper.paperColor),
                                onTap: { p in pageTap(p, size: full, insets: insets) }, onDone: { model.pdfZoom = nil })
                        .ignoresSafeArea()
                }
                if model.selection != nil || model.tapped != nil {
                    SelectionLayer(model: model, title: model.title, topInset: insets.top)
                }
                ReaderPanels(model: model, prefs: prefs, brightness: brightness, insets: insets, wide: full.width >= 600,
                             close: close)
                    // 메뉴도 지면의 밝기를 따른다 — 검은 지면 위에 밝은 메뉴가 뜨면 눈이 부시다(안드로이드 CpReaderTheme).
                    .environment(\.colorScheme, paper.dark ? .dark : .light)
                // 듣기 조종판 · 자동 넘김 알약은 아래 가운데 60(구상안 24 · 26). 메뉴 · 판이 열리면 숨는다.
                if model.panel == .none, model.listening.active {
                    ListenPlayer(listening: model.listening, openSheet: { model.listenSheet = true },
                                 close: { Task { await model.stopListening() } })
                        .padding(.bottom, 60)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                } else if autoPillShown {
                    AutoTurnPill(secondsLeft: model.autoLeft, paused: model.autoPaused,
                                 toggle: { model.autoPaused.toggle() }, close: { model.autoOff = true })
                        .padding(.bottom, 60)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                SearchBarLayer(model: model, search: model.search, bottom: chipBottom(insets))
                // 링크로 건너온 쪽: "읽던 곳으로". 결과 막대가 떠 있으면 그것이 먼저다(같은 자리).
                if model.panel == .none, model.search.current < 0, model.frame?.canReturn == true {
                    ReturnChip { Task { await model.returnBack() } }
                        .padding(.bottom, chipBottom(insets))
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                if model.listenSheet, model.listening.active {
                    ListenSheet(listening: model.listening, prefs: prefs, bottom: insets.bottom,
                                openVoices: { model.listenSheet = false; model.panel = .voices },
                                close: { model.listenSheet = false })
                        .environment(\.colorScheme, paper.dark ? .dark : .light)
                }
                if let draft = model.memo {
                    MemoSheet(draft: draft, bottom: insets.bottom,
                              save: { text, pen in Task { await model.saveMemo(draft, text: text, pen: pen) } },
                              cancel: { model.memo = nil })
                        .id(draft.id)
                }
                if let note = model.footnote {
                    NoteSheet(note: note, bottom: insets.bottom, goTo: { Task { await model.goToFootnote(note) } },
                              close: { model.footnote = nil })
                }
                if let url = model.external {
                    CpPopup(onDismiss: { model.external = nil }) {
                        Text("브라우저로 열까요?").font(CpFont.title).foregroundStyle(CpColor.text)
                        Text(url).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).lineLimit(3)
                        HStack {
                            Spacer()
                            CpButton(title: "취소", kind: .secondary) { model.external = nil }
                            CpButton(title: "열기") {
                                model.external = nil
                                // 앱은 인터넷을 쓰지 않는다 — 여는 것은 휴대폰의 브라우저 · 메일 앱이다.
                                if let link = URL(string: url) { UIApplication.shared.open(link) }
                            }
                        }
                    }
                }
                if let toast = model.toast {
                    CpToast(message: toast)
                        .padding(.bottom, insets.bottom)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .allowsHitTesting(false)
                        .task(id: model.toastCount) {
                            try? await Task.sleep(for: .milliseconds(1500))
                            if !Task.isCancelled { model.toast = nil }
                        }
                }
                if let key = picking, let comic = model.engine as? ComicEngine, let frame = model.frame {
                    CoverPickLayer(engine: comic, frameKey: "\(frame.page)|\(frame.halfLabel)", rightToLeft: rtl, insets: insets,
                                   back: close) { saveCover($0, key) }
                }
                // 만화 권 끝(구상안 36): 다음 권 · 서재로.
                if model.comicEnd && picking == nil {
                    ComicEndCard(label: model.comicLabel, workTitle: model.comicWork?.title ?? "", next: model.nextEntry,
                                 webtoon: model.comicWork?.webtoon == true, library: library,
                                 onNext: { entry in
                                     guard let next = library.comicBook(entry.unit) else { return }
                                     Task { await model.close(); openBook?(next) }
                                 },
                                 toLibrary: close, dismiss: { model.comicEnd = false })
                }
                // 웹툰(구상안 39): 쪽 넘김 화면 위에 따로 선다 — 메뉴 · 판도 웹툰의 것.
                if let toon = model.webtoon {
                    WebtoonScreen(model: toon, prefs: prefs, brightness: brightness, library: library, full: full, insets: insets,
                                  onView: { v in Task { await model.setComicView(v) } },
                                  openBook: { entry in
                                      guard let next = library.comicBook(entry.unit) else { return }
                                      Task { await model.close(); openBook?(next) }
                                  },
                                  pickCover: pickCover.map { key in { saveCover($0, key) } },
                                  close: close)
                } else if model.frame == nil {
                    if model.failure != nil {
                        CpPopup(onDismiss: close) {
                            Text(book.format == .comic ? "이 만화를 열지 못했습니다" : "이 책을 열지 못했습니다").font(CpFont.title).foregroundStyle(CpColor.text)
                            Text(model.failure ?? "").font(CpFont.body).foregroundStyle(CpColor.textMuted)
                                .fixedSize(horizontal: false, vertical: true)
                            HStack { Spacer(); CpButton(title: "서재로") { close() } }
                        }
                    } else {
                        // 큰 책은 첫 장을 짜는 데 시간이 걸린다. 빈 지면만 보이면 눌러도 안 되는 줄 안다.
                        CpPopup(onDismiss: {}) {
                            Text("책을 여는 중…").font(CpFont.title).foregroundStyle(CpColor.text)
                        }
                    }
                }
            }
            .clipped()
            .frame(width: full.width, height: full.height)
            .offset(x: -insets.leading, y: -insets.top)
            .task { await model.open(book, library: library) }
            // 만화(구상안 36): 화면 · 두 쪽 · 표지 · 방향이 바뀌면 다시 낸다.
            .task(id: comicSpec(full, insets)) {
                guard let spec = comicSpec(full, insets) else { return }
                await model.layOutComic(spec)
            }
            // PDF(구상안 32A · 33): 화면 · 지면 · 쪽 맞춤 · 두 쪽 · 표지가 바뀌면 다시 낸다.
            .task(id: pdfSpec(full, insets, paper)) {
                guard let spec = pdfSpec(full, insets, paper) else { return }
                await model.layOutPdf(spec)
            }
            .task(id: LayoutKey(spec: model.engine == nil ? nil : prefs.spec(size: full, insets: insets, bookFonts: model.bookFonts,
                                                                               twoPages: twoPages(full)),
                                paper: paper, blend: prefs.blendPictures, highlights: prefs.showHighlights,
                                spread: twoPages(full) ? Spread(width: full.width, leftInset: insets.leading) : nil)) {
                guard model.engine != nil else { return }
                let two = twoPages(full)
                await model.layOut(prefs.spec(size: full, insets: insets, bookFonts: model.bookFonts, twoPages: two), paper,
                                   blend: prefs.blendPictures, highlights: prefs.showHighlights,
                                   spread: two ? Spread(width: full.width, leftInset: insets.leading) : nil)
                #if DEBUG
                // 화면 확인용: -olo-turn N 쪽 넘기고 -olo-panel bar|view|settings|fonts|contents|voices|footer|footerItem 로 판을 연다, -olo-search <말> 은 찾기 판에서 찾는다. 출시 빌드에는 없다.
                let args = CommandLine.arguments
                if !debugApplied {
                    debugApplied = true
                    if let i = args.firstIndex(of: "-olo-turn"), i + 1 < args.count, let n = Int(args[i + 1]) {
                        for _ in 0..<n { await model.turn(1) }
                    }
                    if let i = args.firstIndex(of: "-olo-theme"), i + 1 < args.count, let t = PaperTheme(rawValue: args[i + 1]) { prefs.theme = t }
                    if let i = args.firstIndex(of: "-olo-search"), i + 1 < args.count {
                        model.search.query = args[i + 1]
                        model.startSearch()
                        model.panel = .search
                    }
                    // -olo-listen 은 듣기를 켜고, -olo-listen-sheet 는 듣기 판까지 연다.
                    if args.contains("-olo-listen") || args.contains("-olo-listen-sheet") {
                        await model.listen(rate: prefs.listenRate, voice: prefs.listenVoice, join: prefs.listenJoin)
                        if args.contains("-olo-listen-sheet") { model.listenSheet = true }
                    }
                    if let i = args.firstIndex(of: "-olo-panel"), i + 1 < args.count {
                        model.panel = ["bar": .bar, "view": .view, "settings": .settings, "fonts": .fonts, "contents": .contents,
                                       "voices": .voices, "footer": .footer, "footerItem": .footerItem,
                                       "touchZones": .touchZones, "pageGrid": .pageGrid][args[i + 1]] ?? .none
                    }
                }
                #endif
            }
            .task(id: KeepAwakeKey(mode: prefs.keepScreenOn, activity: model.lastActivity, auto: autoRunning)) { await keepAwake() }
            // 자동 넘김: 쪽이 바뀌면 처음부터 센다(열쇠에 쪽이 들어 있다). 메뉴 · 듣기 · 고르기 · 메모 · 앱이 뒤로 가면 쉰다.
            .task(id: AutoKey(seconds: ReaderPrefs.autoTurnSeconds[prefs.autoTurn], running: autoRunning, page: model.frame?.key ?? "")) {
                let seconds = ReaderPrefs.autoTurnSeconds[prefs.autoTurn]
                guard autoRunning, seconds > 0 else { return }
                for left in stride(from: seconds, to: 0, by: -1) {
                    model.autoLeft = left
                    try? await Task.sleep(for: .seconds(1))
                    if Task.isCancelled { return }
                }
                // 책 끝이면 멈춘다 — 알약이 "멈춰 있음" 으로 남아 왜 서 있는지 보인다.
                if !(await model.turn(1, quiet: true)) { model.autoPaused = true }
            }
            // 읽은 시간의 주체: 듣는 중이면 듣기(따로 센다), 자동 넘김이 돌면 자동, 아니면 사람.
            .onChange(of: clockMode, initial: true) { _, mode in model.clock?.setMode(mode) }
            // 사람이 쪽을 옮겼다 — 듣던 문장이 그 쪽에 없으면 그 쪽의 첫 문장으로(듣기가 넘긴 쪽이면 문장이 거기 있어 그대로다).
            .onChange(of: model.frame?.key) { _, _ in
                if let f = model.frame { model.listening.onPageShown(spine: f.spine, start: f.pageStart, end: f.pageEnd) }
            }
            .onChange(of: model.listening.message) { _, message in
                if let message { model.say(message); model.listening.message = nil }
            }
            .task(id: TurnKey(effect: prefs.pageTurn, sound: prefs.turnSound, haptic: prefs.turnHaptic, width: full.width, height: full.height)) {
                model.turnEffect = prefs.pageTurn
                model.turnSound = prefs.turnSound
                model.turnHaptic = prefs.turnHaptic
                model.screenWidth = full.width
                model.landscape = full.width > full.height
            }
            // 말림이면 쪽이 바뀔 때마다 앞 · 뒤 쪽을 미리 그린다.
            .task(id: "\(model.frame?.spine ?? -1):\(model.frame?.page ?? -1):\(curl):\(model.frame?.image.hash ?? 0)") {
                await model.prefetch()
            }
        }
        .sheet(item: $model.lookUp) { DictionaryView(term: $0.text).ignoresSafeArea() }
        .sheet(item: $model.shareOut) { ShareView(text: $0.text).presentationDetents([.medium, .large]) }
        .animation(.easeOut(duration: 0.2), value: model.listenSheet)
        .statusBarHidden(model.panel == .none)
        // 읽는 동안 홈 표시줄을 숨긴다(구상안 13A) — 아래 줄이 그 자리에 있다. 손대면 다시 보인다(Apple Books 와 같다).
        .persistentSystemOverlays(model.panel == .none ? .hidden : .automatic)
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: scenePhase) { _, phase in
            // 다른 앱으로 나가면 휴대폰 밝기로 되돌리고, 돌아오면 이 책의 밝기를 다시 건다.
            if phase == .active { brightness.reapply() } else { brightness.restore() }
            // 읽은 시간: 앱이 뒤로 간 동안은 세지 않는다.
            if phase == .background { model.clock?.pause() }
            if phase == .active { model.clock?.resume() }
            // 화면을 끈 채 듣는 동안 쪽이 넘어갔다 — 돌아와서 보던 쪽이 바뀐 까닭을 모르면 놀란다.
            if phase == .background { leftAt = model.frame?.key }
            if phase == .active, let left = leftAt {
                leftAt = nil
                if model.listening.active, left != model.frame?.key { model.say("듣던 곳으로 쪽을 옮겼습니다") }
            }
        }
    }

    /// 화면 켜짐 유지: 휴대폰 = 휴대폰의 꺼짐 시간, 10분 = 마지막으로 넘긴 뒤 10분(읽다 잠들어도 밤새 켜져 있지 않게), 항상.
    private func keepAwake() async {
        // 자동 넘김이 도는 동안은 화면을 켜 둔다 — 꺼지면 넘어가는 쪽을 볼 수 없다.
        if autoRunning { UIApplication.shared.isIdleTimerDisabled = true; return }
        switch prefs.keepScreenOn {
        case .system: UIApplication.shared.isIdleTimerDisabled = false
        case .always: UIApplication.shared.isIdleTimerDisabled = true
        case .tenMinutes:
            UIApplication.shared.isIdleTimerDisabled = true
            let left = 600 - Date().timeIntervalSince(model.lastActivity)
            try? await Task.sleep(for: .seconds(max(left, 0)))
            if !Task.isCancelled { UIApplication.shared.isIdleTimerDisabled = false }
        }
    }

    private func close() {
        Task {
            brightness.forget()
            if model.listening.active { await model.stopListening() }
            await model.close()
            onClose()
        }
    }

    private struct LayoutKey: Equatable {
        let spec: LayoutSpec?
        let paper: Paper
        let blend: Bool
        let highlights: Bool
        let spread: Spread?
    }

    private var clockMode: ReadingMode {
        model.listening.playing ? .listen : autoRunning ? .auto : .read
    }

    /// 이 화면 크기에서 두 쪽으로 보이는가(구상안 27). PDF 는 쪽 전체에서만(폭 · 이어서는 한 쪽씩, 구상안 33).
    private func twoPages(_ full: CGSize) -> Bool {
        prefs.twoPages(full) && (!model.isPdf || prefs.pdfFit == PdfFit.page.rawValue)
    }

    /// 오→왼으로 읽는 만화인가(작품마다: 사람이 고른 값 → ComicInfo → 왼→오).
    private var rtl: Bool { model.isComic && model.comicWork?.rightToLeft?.boolValue == true }

    /// 쪽 넘김의 표지 고르기. 웹툰은 `WebtoonScreen` 이 틀로 고른다(구상안 44 ⑤).
    private var picking: String? { model.isComic && model.webtoon == nil ? pickCover : nil }

    /// 고른 표지를 작품에 쓰고 서재로 돌아가 알린다. 못 쓰면 false — 고르기 화면이 그대로 남아 "쓸 수 없습니다" 를 띄운다.
    private func saveCover(_ image: UIImage, _ key: String) -> Bool {
        guard CoverStore.shared.set(IosCovers.shared.workId(workKey: key), image: image) else { return false }
        library.notice = IosCovers.shared.changed(label: library.workTitle(key))
        close()
        return true
    }

    private func comicSpec(_ full: CGSize, _ insets: EdgeInsets) -> ComicViewSpec? {
        guard model.isComic else { return nil }
        let cover = model.comicWork?.coverAlone?.boolValue ?? prefs.pdfCoverAlone
        // 넓은 그림(구상안 41): 이 작품에 고른 값, 고르지 않았으면 자동(가로로 긴 그림은 두 쪽 — 한 쪽 보기에서 반씩).
        let split = IosComicLayout.shared.wideInHalves(chosen: model.comicWork?.splitSpreads)
        return ComicViewSpec(size: full, insets: insets, twoPages: prefs.twoPages(full), coverAlone: cover, rightToLeft: rtl,
                             trim: prefs.comicTrim, split: split, color: prefs.comicColor)
    }

    private func pdfSpec(_ full: CGSize, _ insets: EdgeInsets, _ paper: Paper) -> PdfViewSpec? {
        guard model.isPdf else { return nil }
        return PdfViewSpec(size: full, insets: insets, paper: paper, blend: prefs.blendPictures,
                           fit: PdfFit(rawValue: prefs.pdfFit) ?? .page, twoPages: twoPages(full), coverAlone: prefs.coverAlone(book.id))
    }

    private struct TurnKey: Equatable {
        let effect: Int
        let sound: Int
        let haptic: Bool
        let width: CGFloat
        let height: CGFloat
    }

    /// 말림을 쓰는가. 휴대폰 "동작 줄이기" 면 효과 없이 넘긴다. PDF 폭 · 이어서는 화면 한 장이 쪽 전체가 아니라 말지 않는다.
    private var curl: Bool { prefs.pageTurn == 4 && !reduceMotion && !(model.isPdf && pdfFit != .page) }

    private var pdfFit: PdfFit { model.isPdf ? PdfFit(rawValue: prefs.pdfFit) ?? .page : .page }

    /// PDF 확대를 받는가: 쪽 전체 · 폭에서, 두 쪽이 아닐 때(안드로이드와 같다).
    /// 표지를 고르는 동안은 확대하지 않는다 — 확대한 그림 위에서는 고를 곳 표시가 쪽과 어긋나고, 저장하는 것은 어차피 쪽(반)이다.
    private var pdfZoomable: Bool { model.isFixed && pdfFit != .continuous && model.frame?.hasRight != true && picking == nil }

    /// 넘김 효과별 전환(사람이 넘겼을 때만 움직인다 — 다른 쪽 바꿈은 애니메이션 없이 바뀐다).
    private var pageTransition: AnyTransition {
        let forward = model.direction > 0
        switch reduceMotion ? 0 : prefs.pageTurn {
        case 1: return .opacity
        case 2: return .asymmetric(insertion: .move(edge: forward ? .trailing : .leading), removal: .move(edge: forward ? .leading : .trailing))
        // 덮기: 다음 쪽이 오른쪽에서 들어와 지금 쪽을 덮는다. 앞 쪽으로 갈 때는 지금 쪽이 오른쪽으로 걷혀 앞 쪽이 드러난다.
        case 3: return forward ? .asymmetric(insertion: .move(edge: .trailing), removal: .identity)
                               : .asymmetric(insertion: .identity, removal: .move(edge: .trailing))
        default: return .identity
        }
    }

    /// 지면 누름(말림에서도 같은 길): 오른쪽 위 모서리는 책갈피, 그다음 링크 · 칠, 아니면 왼쪽 30% 앞 쪽 · 오른쪽 30% 다음 쪽 · 가운데 메뉴.
    private func pageTap(_ p: CGPoint, size: CGSize, insets: EdgeInsets) {
        if p.x > size.width - insets.trailing - 56 && p.y < insets.top + 56 {
            Task { await model.toggleBookmark() }
            return
        }
        let step = Int(zones.step(x: Double(p.x / size.width), rightToLeft: rtl))
        Task {
            await model.tap(at: p) {
                if step != 0 { Task { await model.turn(step) } } else {
                    withAnimation(.easeOut(duration: 0.18)) { model.panel = model.panel == .none ? .bar : .none }
                }
            }
        }
    }

    private var zones: TouchZones { TouchZones.companion.of(index: Int32(prefs.touchZones)) }

    private struct KeepAwakeKey: Equatable {
        let mode: ReaderPrefs.KeepScreenOn
        let activity: Date
        let auto: Bool
    }

    private struct AutoKey: Equatable {
        let seconds: Int
        let running: Bool
        let page: String
    }

    /// 알약을 보이는가: 켜 두었고, 이번에 끄지 않았고, 지면만 보이고 있다. 듣기가 켜져 있으면 듣기가 넘김을 맡는다.
    private var autoPillShown: Bool {
        prefs.autoTurn > 0 && !model.autoOff && model.panel == .none && !model.listening.active
            && model.selection == nil && model.tapped == nil && model.memo == nil && model.footnote == nil && model.frame != nil
    }

    /// 세고 있는가: 알약이 보이고, 멈추지 않았고, 앱이 앞에 있다.
    private var autoRunning: Bool { autoPillShown && !model.autoPaused && scenePhase == .active && model.external == nil }

    /// 결과 막대 · "읽던 곳으로" 의 자리. 듣는 동안은 조종판(60 + 52) 위로 올린다(안드로이드 124) — 겹치면 누를 곳이 둘이 된다.
    private func chipBottom(_ insets: EdgeInsets) -> CGFloat {
        model.panel == .none && (model.listening.active || autoPillShown) ? 124 : 64 + insets.bottom
    }
}

/// 넘김 자리. 누름과 밀기를 함께 받는다. PDF(구상안 33)는 위아래 끌기(폭 맞춤의 쪽 안 이동) · 두 번 누르기 · 두 손가락(확대)을
/// 더 받는다 — 두 번 누르기를 기다리느라 한 번 누름이 0.3초 늦다(안드로이드와 같다).
private struct TurnZones: View {
    /// 오른쪽 위 모서리(책갈피 자리)의 위 · 오른쪽 기준. 리본이 안전 영역 아래에 서므로 누르는 자리도 같이 내린다.
    let cornerTop: CGFloat
    let cornerRight: CGFloat
    /// 어느 쪽이 다음인가(구상안 37). 오→왼 만화면 자리를 뒤집는다 — 한 손만은 늘 다음(공통 규칙 `TouchZones.step`).
    let zones: TouchZones
    let rightToLeft: Bool
    /// 읽는 순서로 몇 쪽(1 다음 · -1 앞). 누름 · 손쉬운 사용 동작이 부른다.
    let turn: (Int) -> Void
    /// 누름: 먼저 그 자리의 링크를 묻고, 링크가 아니면 두 번째 함수(넘김 · 메뉴)를 부른다.
    let tap: (CGPoint, @escaping () -> Void) -> Void
    /// 길게 누름(0.45초, 움직이지 않음) — 그 자리의 낱말을 고른다.
    let longPress: (CGPoint) -> Void
    let bookmark: () -> Void
    let toggleMenu: () -> Void
    /// 옆으로 밀기. nil 이면 [turn].
    var swipe: ((Int) -> Void)?
    /// 위아래 끌기(끈 거리, 손을 뗐는가). nil 이면 받지 않는다.
    var pan: ((CGFloat, Bool) -> Void)?
    /// 두 번 누르기 · 두 손가락 벌리기(확대). nil 이면 받지 않는다.
    var zoom: ((CGPoint, CGFloat) -> Void)?

    /// 안드로이드 `BOOKMARK_CORNER`(56). 리본(22×30)을 넉넉히 덮는다.
    private let corner: CGFloat = 56
    /// 누르기 시작한 때 · 자리 · 길게 누름이 이미 불렸는지. 밀기와 한 제스처라 시간으로 가른다.
    @State private var pressTask: Task<Void, Never>?
    @State private var longFired = false
    /// 끌기의 방향은 처음 움직인 쪽으로 정하고 손을 뗄 때까지 바꾸지 않는다(안드로이드와 같다).
    @State private var vertical: Bool?
    @State private var pendingTap: (at: CGPoint, task: Task<Void, Never>)?

    var body: some View {
        GeometryReader { g in
            Color.clear.contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        if pressTask == nil && !longFired {
                            let at = v.startLocation
                            pressTask = Task { @MainActor in
                                try? await Task.sleep(for: .milliseconds(450))
                                guard !Task.isCancelled else { return }
                                longFired = true
                                longPress(at)
                            }
                        }
                        // 움직이면 밀기다 — 길게 누름이 아니다.
                        if abs(v.translation.width) > 10 || abs(v.translation.height) > 10 {
                            pressTask?.cancel()
                            if vertical == nil { vertical = abs(v.translation.height) > abs(v.translation.width) }
                        }
                        if vertical == true, let pan { pan(v.translation.height, false) }
                    }
                    .onEnded { v in
                        pressTask?.cancel()
                        pressTask = nil
                        defer { vertical = nil }
                        if longFired { longFired = false; return }
                        if vertical == true, let pan {
                            pan(v.translation.height, true)
                            return
                        }
                        let dx = v.translation.width
                        if abs(dx) > 40 && abs(dx) > abs(v.translation.height) {
                            // 왼쪽으로 밀면 다음 쪽(종이를 넘기는 방향). 밀기는 화면 방향이라 오→왼이면 뒤집는다.
                            (swipe ?? { turn(rightToLeft ? -$0 : $0) })(dx < 0 ? 1 : -1)
                        } else if abs(dx) < 10 && abs(v.translation.height) < 10 {
                            // 오른쪽 위 모서리는 책갈피(안드로이드와 같다) — 리본이 있는 곳을 누르면 빠진다.
                            if v.location.x > g.size.width - cornerRight - corner && v.location.y < cornerTop + corner {
                                bookmark()
                                return
                            }
                            single(v.location, width: g.size.width)
                        }
                    }
                )
                .simultaneousGesture(
                    MagnifyGesture()
                        .onEnded { v in if let zoom, v.magnification > 1.05 { zoom(v.startLocation, min(v.magnification, 5)) } },
                    including: zoom == nil ? .none : .all
                )
        }
        .accessibilityElement()
        .accessibilityLabel("지면")
        .accessibilityHint("\(zones.description(rightToLeft: rightToLeft)), 가운데는 메뉴, 오른쪽 위 모서리는 책갈피")
        .accessibilityAction(named: "다음 쪽") { turn(1) }
        .accessibilityAction(named: "앞 쪽") { turn(-1) }
        .accessibilityAction(named: "메뉴") { toggleMenu() }
        .accessibilityAction(named: "책갈피") { bookmark() }
    }

    /// 한 번 누름. 확대를 받으면 두 번째 누름을 0.3초 기다린다 — 두 번이면 그 자리를 2.5배로.
    private func single(_ at: CGPoint, width: CGFloat) {
        let run = {
            let step = Int(zones.step(x: Double(at.x / width), rightToLeft: rightToLeft))
            tap(at) {
                if step != 0 { turn(step) } else { toggleMenu() }
            }
        }
        guard let zoom else { run(); return }
        if let pending = pendingTap, hypot(pending.at.x - at.x, pending.at.y - at.y) < 40 {
            pending.task.cancel()
            pendingTap = nil
            zoom(at, 2.5)
            return
        }
        pendingTap?.task.cancel()
        pendingTap = (at, Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            pendingTap = nil
            run()
        })
    }
}

/// 결과를 보고 있는 동안 떠 있는 결과 막대. 판이 열리면 숨는다(판 위에 겹치면 누를 곳이 둘이 된다).
private struct SearchBarLayer: View {
    @ObservedObject var model: ReaderModel
    @ObservedObject var search: BookSearch
    let bottom: CGFloat

    var body: some View {
        if model.panel == .none, search.current >= 0, !search.results.isEmpty {
            SearchResultBar(
                index: search.current, total: search.results.count,
                onPrevious: { if search.current > 0 { Task { await model.openHit(search.current - 1) } } },
                onNext: { if search.current < search.results.count - 1 { Task { await model.openHit(search.current + 1) } } },
                onList: { model.panel = .search },
                onClose: { Task { await model.endSearch() } }
            )
            .padding(.bottom, bottom)
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
    }
}

/// 링크로 건너간 뒤 떠 있는 "읽던 곳으로"(안드로이드 `ReturnChip`): 48 · 모서리 14 — 결과 막대와 같은 모양.
private struct ReturnChip: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 0) {
                CpIconView(icon: .back, size: 20, color: CpColor.accent).padding(8)
                Text("읽던 곳으로").font(CpFont.label).foregroundStyle(CpColor.accentText)
            }
            .padding(.leading, 8).padding(.trailing, 18).padding(.vertical, 4)
            .frame(minHeight: CpMetrics.touchTarget)
            .background(CpColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium))
            .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(CpColor.divider, lineWidth: 1))
            .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
        }
        .buttonStyle(.plain)
    }
}

/// 각주 판(구상안 18, 안드로이드 `NoteSheet` · `CpBottomSheet`): 아래에서 올라온다. 짧으면 내용만큼, 길면 화면의 60% 까지
/// 올라오고 판 안에서 스크롤한다. 읽던 쪽은 뒤에 흐리게 — 바깥을 누르거나 "닫기" 면 그대로 이어 읽는다.
private struct NoteSheet: View {
    let note: FootnoteItem
    let bottom: CGFloat
    let goTo: () -> Void
    let close: () -> Void

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .bottom) {
                Color.black.opacity(0.32).contentShape(Rectangle()).onTapGesture(perform: close)
                    .accessibilityLabel("닫기").accessibilityAddTraits(.isButton)
                    .cpFadeIn(CpMotion.sheetIn)
                VStack(alignment: .leading, spacing: 0) {
                    Capsule().fill(CpColor.divider).frame(width: 36, height: 4).frame(maxWidth: .infinity)
                    Text(note.title).font(CpFont.label).foregroundStyle(CpColor.accentText).padding(.top, 14).padding(.bottom, 8)
                    ScrollView {
                        Text(note.text).font(CpFont.body).foregroundStyle(CpColor.text).lineSpacing(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: max(g.size.height * 0.6 - 120, 80))
                    .fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Spacer()
                        CpButton(title: "각주 자리로 가기", kind: .secondary, action: goTo)
                        CpButton(title: "닫기", action: close)
                    }
                    .padding(.top, 20)
                }
                .padding(.horizontal, 24).padding(.top, 10).padding(.bottom, 24 + bottom)
                .background(CpColor.surface)
                .clipShape(UnevenRoundedRectangle(topLeadingRadius: CpMetrics.cornerSheet, topTrailingRadius: CpMetrics.cornerSheet))
                .cpRiseIn() // 아래에서 올라온다(구상안 42)
            }
        }
    }
}
