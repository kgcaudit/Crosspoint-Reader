import OloCore
import SwiftUI

/// Kotlin 리더를 줄 세워 부른다. 주 스레드 밖에서 돌고(장 하나를 짜는 데 수백 ms 가 걸릴 수 있다), 한 번에 하나씩만 —
/// 넘김이 겹치면 두 번 눌러도 한 쪽만 넘어가거나 자리 저장 순서가 뒤바뀐다.
actor ReaderEngine {
    private let reader: IosBookReader

    init(reader: IosBookReader) { self.reader = reader }

    func layOut(_ spec: LayoutSpec, _ paper: Paper, blend: Bool) async throws -> PageFrame {
        PageFrame(try await reader.layOut(spec: spec, paper: Int32(paper.paper), ink: Int32(paper.ink), blendPictures: blend))
    }

    func next() async throws -> PageFrame? { try await reader.next().map(PageFrame.init) }
    func previous() async throws -> PageFrame? { try await reader.previous().map(PageFrame.init) }
    func seek(_ percent: Double) async throws -> PageFrame { PageFrame(try await reader.seek(percent: Float(percent))) }
    func toggleBookmark() async throws -> PageFrame { PageFrame(try await reader.toggleBookmark()) }

    func contents() async throws -> (rows: [ContentsItem], current: Int) {
        let rows = try await reader.contents().enumerated().map { ContentsItem(index: $0.offset, label: $0.element.label, depth: Int($0.element.depth)) }
        return (rows, Int(try await reader.currentContentsIndex()))
    }

    func goToContents(_ index: Int) async throws -> PageFrame? { try await reader.goToContents(index: Int32(index)).map(PageFrame.init) }

    func bookmarks() async throws -> [BookmarkItem] {
        try await reader.bookmarks().map { BookmarkItem(id: $0.id, snippet: $0.snippet, percent: Double($0.percent)) }
    }

    func goToBookmark(_ id: Int64) async throws -> PageFrame? { try await reader.goToBookmark(id: id).map(PageFrame.init) }
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
enum ReaderPanel { case none, bar, view, settings, fonts, contents }

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
            engine = ReaderEngine(reader: reader)
            title = reader.title
            author = reader.author
        } catch {
            // "이 책은 열리지 않습니다" 는 가장 나쁜 답이지만, 정말 열 수 없는 파일(zip 이 아닌 EPUB)에는 까닭을 알린다.
            failure = "이 책을 열지 못했습니다. 파일이 망가졌거나 EPUB · TXT 가 아닙니다."
        }
    }

    func layOut(_ spec: LayoutSpec, _ paper: Paper, blend: Bool) async {
        guard let engine else { return }
        do { frame = try await engine.layOut(spec, paper, blend: blend) } catch { failure = "쪽을 짜지 못했습니다." }
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

    func toggleBookmark() async {
        guard let engine, let f = try? await engine.toggleBookmark() else { return }
        frame = f
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
        case .view, .contents: panel = .bar
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
                    ReadingFooter(title: model.title, frame: frame, color: paper.inkMutedColor)
                        .padding(.horizontal, CGFloat(ReaderPrefs.margins[prefs.margin]) + max(insets.leading, insets.trailing))
                        .frame(height: CpMetrics.statusBarHeight)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, insets.bottom)
                } else if let failure = model.failure {
                    VStack(spacing: 16) {
                        Text(failure).font(CpFont.body).foregroundStyle(CpColor.text).multilineTextAlignment(.center)
                        CpButton(title: "서재로", kind: .secondary) { close() }
                    }
                    .padding(32)
                }
                TurnZones(turn: { step in Task { await model.turn(step) } },
                          toggleMenu: { withAnimation(.easeOut(duration: 0.18)) { model.panel = model.panel == .none ? .bar : .none } })
                ReaderPanels(model: model, prefs: prefs, brightness: brightness, insets: insets, wide: full.width >= 600,
                             close: close)
                    // 메뉴도 지면의 밝기를 따른다 — 검은 지면 위에 밝은 메뉴가 뜨면 눈이 부시다(안드로이드 CpReaderTheme).
                    .environment(\.colorScheme, paper.dark ? .dark : .light)
            }
            .clipped()
            .frame(width: full.width, height: full.height)
            .offset(x: -insets.leading, y: -insets.top)
            .task { await model.open(book, library: library) }
            .task(id: LayoutKey(spec: model.engine == nil ? nil : prefs.spec(size: full, insets: insets), paper: paper, blend: prefs.blendPictures)) {
                guard model.engine != nil else { return }
                await model.layOut(prefs.spec(size: full, insets: insets), paper, blend: prefs.blendPictures)
                #if DEBUG
                // 화면 확인용: -olo-turn N 쪽 넘기고 -olo-panel bar|view|settings|fonts|contents 로 판을 연다. 출시 빌드에는 없다.
                let args = CommandLine.arguments
                if !debugApplied {
                    debugApplied = true
                    if let i = args.firstIndex(of: "-olo-turn"), i + 1 < args.count, let n = Int(args[i + 1]) {
                        for _ in 0..<n { await model.turn(1) }
                    }
                    if let i = args.firstIndex(of: "-olo-theme"), i + 1 < args.count, let t = PaperTheme(rawValue: args[i + 1]) { prefs.theme = t }
                    if let i = args.firstIndex(of: "-olo-panel"), i + 1 < args.count {
                        model.panel = ["bar": .bar, "view": .view, "settings": .settings, "fonts": .fonts, "contents": .contents][args[i + 1]] ?? .none
                    }
                }
                #endif
            }
            .task(id: KeepAwakeKey(mode: prefs.keepScreenOn, activity: model.lastActivity)) { await keepAwake() }
        }
        .statusBarHidden(model.panel == .none)
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
    let turn: (Int) -> Void
    let toggleMenu: () -> Void

    var body: some View {
        GeometryReader { g in
            Color.clear.contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0).onEnded { v in
                        let dx = v.translation.width
                        if abs(dx) > 40 && abs(dx) > abs(v.translation.height) {
                            turn(dx < 0 ? 1 : -1) // 왼쪽으로 밀면 다음 쪽(종이를 넘기는 방향).
                        } else if abs(dx) < 10 && abs(v.translation.height) < 10 {
                            let x = v.location.x / g.size.width
                            if x < 0.3 { turn(-1) } else if x > 0.7 { turn(1) } else { toggleMenu() }
                        }
                    }
                )
        }
        .accessibilityElement()
        .accessibilityLabel("지면")
        .accessibilityHint("왼쪽은 앞 쪽, 오른쪽은 다음 쪽, 가운데는 메뉴")
        .accessibilityAction(named: "다음 쪽") { turn(1) }
        .accessibilityAction(named: "앞 쪽") { turn(-1) }
        .accessibilityAction(named: "메뉴") { toggleMenu() }
    }
}

/// 지면 아래 줄(안드로이드 `CpReadingFooter` 기본 칸): 얇은 진도 막대 · 왼쪽 제목 · 가운데 장 안의 쪽 · 오른쪽 책 진도.
private struct ReadingFooter: View {
    let title: String
    let frame: PageFrame
    let color: Color

    var body: some View {
        VStack(spacing: 4) {
            CpProgressBar(fraction: frame.percent / 100, thickness: 2, fill: color.opacity(0.5))
            HStack {
                Text(title).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                Text("\(frame.page + 1) / \(frame.pages)")
                Text("\(Int(frame.percent.rounded()))%").frame(maxWidth: .infinity, alignment: .trailing)
            }
            .font(CpFont.caption)
            .foregroundStyle(color)
        }
        .accessibilityElement(children: .combine)
    }
}
