import OloCore
import SwiftUI

/// Kotlin 리더를 줄 세워 부른다. 주 스레드 밖에서 돌고(장 하나를 짜는 데 수백 ms 가 걸릴 수 있다), 한 번에 하나씩만 —
/// 넘김이 겹치면 두 번 눌러도 한 쪽만 넘어가거나 자리 저장 순서가 뒤바뀐다.
actor ReaderEngine {
    private let reader: IosBookReader

    init(reader: IosBookReader) { self.reader = reader }

    func layOut(_ spec: LayoutSpec, _ paper: Paper, blend: Bool) async throws -> PageFrame {
        // 찾은 말 칠은 지면 밝기에 맞는 강조색(메뉴도 지면 밝기를 따른다).
        PageFrame(try await reader.layOut(spec: spec, paper: Int32(paper.paper), ink: Int32(paper.ink), blendPictures: blend,
                                          accent: paper.dark ? 0xE8A183 : 0xB95B3B))
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
    var id: Int { index }
}

struct BookmarkItem: Identifiable, Hashable {
    let id: Int64
    let snippet: String
    let percent: Double
    /// 독서노트의 갈래 이름(이 자리를 담은 목차 항목).
    let section: String
    let created: Date

    /// "3% · 2026.10.21."(안드로이드 `noteWhere`).
    var whereLabel: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = "yyyy.MM.dd."
        return "\(Int(percent.rounded()))% · \(f.string(from: created))"
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

    init(_ f: ReaderFrame) {
        image = f.image
        spine = Int(f.spineIndex)
        chapters = Int(f.chapterCount)
        page = Int(f.pageIndex)
        pages = Int(f.pageCount)
        percent = Double(f.percent)
        bookmarked = f.bookmarked
    }
}

/// 리더 위에 열린 판(안드로이드 `Panel`). 뒤로 가기: 목차 · 보기 → 메뉴, 보기 설정 · 글꼴 → 보기, 메뉴 → 지면.
enum ReaderPanel { case none, bar, view, settings, fonts, contents, search }

@MainActor
final class ReaderModel: ObservableObject {
    @Published private(set) var frame: PageFrame?
    @Published private(set) var failure: String?
    @Published var panel = ReaderPanel.none
    /// 넘긴 방향(+1 다음, −1 앞). 쪽이 들어오는 쪽을 정한다.
    @Published private(set) var direction = 1
    // 화면이 지켜본다 — 책이 열리면 그때 조판을 시작해야 한다(안 지켜보면 지면만 나오고 글이 영영 안 나온다).
    @Published private(set) var engine: ReaderEngine?
    /// 마지막으로 넘긴 때. "화면 켜짐 유지 10분" 이 여기서 센다.
    @Published private(set) var lastActivity = Date()
    /// 이 책에 쓸 수 있는 글꼴이 있다 — 글꼴 판에 "출판사 글꼴" 을 내놓고, 켜져 있으면 책 글꼴로 짠다.
    @Published private(set) var bookFonts = false
    /// "출판사 글꼴" 이름을 그릴 글꼴 파일(꺼낸 뒤에만).
    @Published private(set) var bookFontPreview: String?
    private(set) var title = ""
    private(set) var author: String?
    private var endAccess: (() -> Void)?

    func open(_ book: LibraryBook, library: Library) async {
        guard engine == nil else { return }
        endAccess = library.beginAccess(book)
        let store = library.store, cache = library.pageCache
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
            failure = "이 책을 열지 못했습니다"
        }
    }

    func layOut(_ spec: LayoutSpec, _ paper: Paper, blend: Bool) async {
        guard let engine else { return }
        do { frame = try await engine.layOut(spec, paper, blend: blend) } catch { failure = "쪽을 짜지 못했습니다." }
        // 처음 책 글꼴로 짤 때 꺼내 본다. 하나도 읽히지 않으면(WOFF 뿐 · 깨짐) 목록에서 "출판사 글꼴" 을 거둔다.
        bookFonts = await engine.hasBookFonts()
        bookFontPreview = await engine.bookFontPreviewPath()
    }

    func turn(_ step: Int) async {
        guard let engine else { return }
        if panel != .none { panel = .none; return }
        lastActivity = Date()
        let next = try? await (step > 0 ? engine.next() : engine.previous())
        guard let next else { return } // 책의 처음 · 끝: 아무 일도 없다.
        direction = step
        withAnimation(.easeOut(duration: 0.22)) { frame = next }
    }

    func seek(_ percent: Double) async {
        guard let engine, let f = try? await engine.seek(percent) else { return }
        lastActivity = Date()
        direction = f.percent >= (frame?.percent ?? 0) ? 1 : -1
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
    func jump(_ go: (ReaderEngine) async throws -> PageFrame?) async {
        guard let engine, let f = try? await go(engine) else { return }
        lastActivity = Date()
        frame = f
        panel = .none
    }

    func back() {
        switch panel {
        case .settings, .fonts: panel = .view
        case .view, .contents, .search: panel = .bar
        case .bar, .none: panel = .none
        }
    }

    func close() async {
        await engine?.close()
        endAccess?()
        endAccess = nil
    }
}

/// 리더(구상안 2A · 4A · 6A 확정). 지면은 화면 전체 — 왼쪽 30% 를 누르면 앞 쪽, 오른쪽 30% 는 다음 쪽, 가운데 40% 는 메뉴
/// (안드로이드 터치 영역 "기본"). 옆으로 밀어도 넘어간다.
struct ReaderView: View {
    let book: LibraryBook
    let library: Library
    let onClose: () -> Void

    @StateObject private var model = ReaderModel()
    @StateObject private var prefs = ReaderPrefs()
    @StateObject private var brightness = ScreenBrightness()
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase
    #if DEBUG
    @State private var debugApplied = false
    #endif

    var body: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            let full = CGSize(width: geometry.size.width + insets.leading + insets.trailing,
                              height: geometry.size.height + insets.top + insets.bottom)
            let paper = prefs.theme.paper(scheme)
            ZStack {
                paper.paperColor
                if let frame = model.frame {
                    Image(uiImage: frame.image).resizable()
                        .id("\(frame.spine):\(frame.page)")
                        .transition(.asymmetric(insertion: .move(edge: model.direction > 0 ? .trailing : .leading),
                                                removal: .move(edge: model.direction > 0 ? .leading : .trailing)))
                        .accessibilityLabel("\(model.title), \(frame.page + 1)쪽")
                    // 윗여백 안에서 끝나 글자를 가리지 않는다. 노치 · 다이내믹 아일랜드가 있으면 그 아래로.
                    if frame.bookmarked {
                        CpRibbon().padding(.top, insets.top).padding(.trailing, 20 + insets.trailing)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    }
                    // 아래 줄은 화면 끝에서 14(구상안 13A, 안드로이드와 같다) — 읽는 동안 홈 표시줄을 숨긴다. 좌우는 본문 여백 선:
                    // 안드로이드의 16 은 iPhone 의 둥근 모서리에 글자가 잘린다.
                    ReadingFooter(title: model.title, frame: frame, ink: paper.inkMutedColor)
                        .padding(.horizontal, CGFloat(ReaderPrefs.margins[prefs.margin]) + max(insets.leading, insets.trailing))
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, 14)
                }
                TurnZones(cornerTop: insets.top, cornerRight: insets.trailing,
                          turn: { step in Task { await model.turn(step) } },
                          bookmark: { Task { await model.toggleBookmark() } },
                          toggleMenu: { withAnimation(.easeOut(duration: 0.18)) { model.panel = model.panel == .none ? .bar : .none } })
                ReaderPanels(model: model, prefs: prefs, brightness: brightness, insets: insets, wide: full.width >= 600,
                             close: close)
                    // 메뉴도 지면의 밝기를 따른다 — 검은 지면 위에 밝은 메뉴가 뜨면 눈이 부시다(안드로이드 CpReaderTheme).
                    .environment(\.colorScheme, paper.dark ? .dark : .light)
                SearchBarLayer(model: model, search: model.search, bottom: insets.bottom)
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
                if model.frame == nil {
                    if model.failure != nil {
                        CpPopup(onDismiss: close) {
                            Text("이 책을 열지 못했습니다").font(CpFont.title).foregroundStyle(CpColor.text)
                            Text("파일이 망가졌거나 EPUB · TXT 가 아닙니다.").font(CpFont.body).foregroundStyle(CpColor.textMuted)
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
            .task(id: LayoutKey(spec: model.engine == nil ? nil : prefs.spec(size: full, insets: insets, bookFonts: model.bookFonts), paper: paper, blend: prefs.blendPictures)) {
                guard model.engine != nil else { return }
                await model.layOut(prefs.spec(size: full, insets: insets, bookFonts: model.bookFonts), paper, blend: prefs.blendPictures)
                #if DEBUG
                // 화면 확인용: -olo-turn N 쪽 넘기고 -olo-panel bar|view|settings|fonts|contents 로 판을 연다, -olo-search <말> 은 찾기 판에서 찾는다. 출시 빌드에는 없다.
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
                    if let i = args.firstIndex(of: "-olo-panel"), i + 1 < args.count {
                        model.panel = ["bar": .bar, "view": .view, "settings": .settings, "fonts": .fonts, "contents": .contents][args[i + 1]] ?? .none
                    }
                }
                #endif
            }
            .task(id: KeepAwakeKey(mode: prefs.keepScreenOn, activity: model.lastActivity)) { await keepAwake() }
        }
        .statusBarHidden(model.panel == .none)
        // 읽는 동안 홈 표시줄을 숨긴다(구상안 13A) — 아래 줄이 그 자리에 있다. 손대면 다시 보인다(Apple Books 와 같다).
        .persistentSystemOverlays(model.panel == .none ? .hidden : .automatic)
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: scenePhase) { _, phase in
            // 다른 앱으로 나가면 휴대폰 밝기로 되돌리고, 돌아오면 이 책의 밝기를 다시 건다.
            if phase == .active { brightness.reapply() } else { brightness.restore() }
        }
    }

    /// 화면 켜짐 유지: 휴대폰 = 휴대폰의 꺼짐 시간, 10분 = 마지막으로 넘긴 뒤 10분(읽다 잠들어도 밤새 켜져 있지 않게), 항상.
    private func keepAwake() async {
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
            await model.close()
            onClose()
        }
    }

    private struct LayoutKey: Equatable {
        let spec: LayoutSpec?
        let paper: Paper
        let blend: Bool
    }

    private struct KeepAwakeKey: Equatable {
        let mode: ReaderPrefs.KeepScreenOn
        let activity: Date
    }
}

/// 넘김 자리. 누름과 밀기를 함께 받는다.
private struct TurnZones: View {
    /// 오른쪽 위 모서리(책갈피 자리)의 위 · 오른쪽 기준. 리본이 안전 영역 아래에 서므로 누르는 자리도 같이 내린다.
    let cornerTop: CGFloat
    let cornerRight: CGFloat
    let turn: (Int) -> Void
    let bookmark: () -> Void
    let toggleMenu: () -> Void

    /// 안드로이드 `BOOKMARK_CORNER`(56). 리본(22×30)을 넉넉히 덮는다.
    private let corner: CGFloat = 56

    var body: some View {
        GeometryReader { g in
            Color.clear.contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0).onEnded { v in
                        let dx = v.translation.width
                        if abs(dx) > 40 && abs(dx) > abs(v.translation.height) {
                            turn(dx < 0 ? 1 : -1) // 왼쪽으로 밀면 다음 쪽(종이를 넘기는 방향).
                        } else if abs(dx) < 10 && abs(v.translation.height) < 10 {
                            // 오른쪽 위 모서리는 책갈피(안드로이드와 같다) — 리본이 있는 곳을 누르면 빠진다.
                            if v.location.x > g.size.width - cornerRight - corner && v.location.y < cornerTop + corner {
                                bookmark()
                                return
                            }
                            let x = v.location.x / g.size.width
                            if x < 0.3 { turn(-1) } else if x > 0.7 { turn(1) } else { toggleMenu() }
                        }
                    }
                )
        }
        .accessibilityElement()
        .accessibilityLabel("지면")
        .accessibilityHint("왼쪽은 앞 쪽, 오른쪽은 다음 쪽, 가운데는 메뉴, 오른쪽 위 모서리는 책갈피")
        .accessibilityAction(named: "다음 쪽") { turn(1) }
        .accessibilityAction(named: "앞 쪽") { turn(-1) }
        .accessibilityAction(named: "메뉴") { toggleMenu() }
        .accessibilityAction(named: "책갈피") { bookmark() }
    }
}

/// 지면 아래 줄(안드로이드 `CpReadingFooter` 기본 칸): 얇은 진도 막대 · 왼쪽 제목 · 가운데 장 안의 쪽 · 오른쪽 책 진도.
/// 막대는 강조색, 빈 곳은 지면의 흐린 글자색 28% — UI 막대색(progressTrack)은 세피아 · 회색 지면에 묻혀 얼마 남았는지 보이지
/// 않았다(안드로이드 0.29).
private struct ReadingFooter: View {
    let title: String
    let frame: PageFrame
    let ink: Color

    var body: some View {
        VStack(spacing: 6) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(ink.opacity(0.28))
                    Capsule().fill(CpColor.accent).frame(width: g.size.width * min(max(frame.percent / 100, 0), 1))
                }
            }
            .frame(height: 2)
            HStack(spacing: 0) {
                Text(title).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                Text("\(frame.page + 1) / \(frame.pages)").padding(.horizontal, 12)
                Text("\(Int(frame.percent.rounded()))%").frame(maxWidth: .infinity, alignment: .trailing)
            }
            .font(CpFont.caption)
            .foregroundStyle(ink)
        }
        .accessibilityElement(children: .combine)
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
            .padding(.bottom, 64 + bottom)
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
    }
}
