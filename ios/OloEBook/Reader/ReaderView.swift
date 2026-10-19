import OloCore
import SwiftUI

/// Kotlin 리더를 줄 세워 부른다. 주 스레드 밖에서 돌고(장 하나를 짜는 데 수백 ms 가 걸릴 수 있다), 한 번에 하나씩만 —
/// 넘김이 겹치면 두 번 눌러도 한 쪽만 넘어가거나 자리 저장 순서가 뒤바뀐다.
actor ReaderEngine {
    private let reader: IosBookReader

    init(reader: IosBookReader) { self.reader = reader }

    func layOut(_ spec: LayoutSpec, _ paper: Paper) async throws -> PageFrame {
        PageFrame(try await reader.layOut(spec: spec, paper: Int32(paper.paper), ink: Int32(paper.ink)))
    }

    func next() async throws -> PageFrame? { try await reader.next().map(PageFrame.init) }
    func previous() async throws -> PageFrame? { try await reader.previous().map(PageFrame.init) }
    func seek(_ percent: Double) async throws -> PageFrame { PageFrame(try await reader.seek(percent: Float(percent))) }
    func toggleBookmark() async throws -> PageFrame { PageFrame(try await reader.toggleBookmark()) }
    func close() { reader.close() }
    nonisolated var title: String { reader.title }
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

@MainActor
final class ReaderModel: ObservableObject {
    @Published private(set) var frame: PageFrame?
    @Published private(set) var failure: String?
    @Published var menu = false
    /// 넘긴 방향(+1 다음, −1 앞). 쪽이 들어오는 쪽을 정한다.
    @Published private(set) var direction = 1
    // 화면이 지켜본다 — 책이 열리면 그때 조판을 시작해야 한다(안 지켜보면 지면만 나오고 글이 영영 안 나온다).
    @Published private(set) var engine: ReaderEngine?
    private(set) var title = ""
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
        } catch {
            // "이 책은 열리지 않습니다" 는 가장 나쁜 답이지만, 정말 열 수 없는 파일(zip 이 아닌 EPUB)에는 까닭을 알린다.
            failure = "이 책을 열지 못했습니다. 파일이 망가졌거나 EPUB · TXT 가 아닙니다."
        }
    }

    func layOut(_ spec: LayoutSpec, _ paper: Paper) async {
        guard let engine else { return }
        do { frame = try await engine.layOut(spec, paper) } catch { failure = "쪽을 짜지 못했습니다." }
    }

    func turn(_ step: Int) async {
        guard let engine else { return }
        if menu { menu = false; return }
        let next = try? await (step > 0 ? engine.next() : engine.previous())
        guard let next else { return } // 책의 처음 · 끝: 아무 일도 없다.
        direction = step
        withAnimation(.easeOut(duration: 0.22)) { frame = next }
    }

    func seek(_ percent: Double) async {
        guard let engine, let f = try? await engine.seek(percent) else { return }
        direction = (f.percent >= (frame?.percent ?? 0)) ? 1 : -1
        frame = f
    }

    func toggleBookmark() async {
        guard let engine, let f = try? await engine.toggleBookmark() else { return }
        frame = f
    }

    func close() async {
        await engine?.close()
        endAccess?()
        endAccess = nil
    }
}

/// 리더(구상안 2A 확정, 2026-10-20). 지면은 화면 전체 — 왼쪽 ⅓ 을 누르면 앞 쪽, 오른쪽 ⅓ 은 다음 쪽, 가운데는 메뉴.
/// 옆으로 밀어도 넘어간다.
struct ReaderView: View {
    let book: LibraryBook
    let library: Library
    let onClose: () -> Void

    @StateObject private var model = ReaderModel()
    @Environment(\.colorScheme) private var scheme
    #if DEBUG
    @State private var debugApplied = false
    #endif

    var body: some View {
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            let full = CGSize(width: geometry.size.width + insets.leading + insets.trailing,
                              height: geometry.size.height + insets.top + insets.bottom)
            let paper = Paper.system(scheme)
            ZStack {
                paper.paperColor
                if let frame = model.frame {
                    Image(uiImage: frame.image).resizable()
                        .id("\(frame.spine):\(frame.page)")
                        .transition(.asymmetric(insertion: .move(edge: model.direction > 0 ? .trailing : .leading),
                                                removal: .move(edge: model.direction > 0 ? .leading : .trailing)))
                        .accessibilityLabel("\(model.title), \(frame.page + 1)쪽")
                    ReadingFooter(title: model.title, frame: frame, color: paper.inkMutedColor)
                        .padding(.horizontal, CGFloat(ReadingSpec.shared.SIDE_MARGIN) + max(insets.leading, insets.trailing))
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
                          toggleMenu: { withAnimation(.easeOut(duration: 0.18)) { model.menu.toggle() } })
                if model.menu, let frame = model.frame {
                    ReaderMenu(title: model.title, frame: frame, insets: insets,
                               back: close,
                               bookmark: { Task { await model.toggleBookmark() } },
                               seek: { p in Task { await model.seek(p) } })
                        .transition(.opacity)
                }
            }
            .clipped()
            .frame(width: full.width, height: full.height)
            .offset(x: -insets.leading, y: -insets.top)
            .task { await model.open(book, library: library) }
            .task(id: Key(size: full, insets: insets, paper: paper, ready: model.engine != nil)) {
                guard model.engine != nil else { return }
                let spec = ReadingSpec.shared.of(
                    widthPt: Float(full.width), heightPt: Float(full.height),
                    safeLeft: Float(insets.leading), safeTop: Float(insets.top),
                    safeRight: Float(insets.trailing), safeBottom: Float(insets.bottom),
                    fontSize: ReadingSpec.shared.DEFAULT_SIZE)
                await model.layOut(spec, paper)
                #if DEBUG
                // 화면 확인용: -olo-turn N 쪽 넘기고 -olo-menu 로 메뉴를 편다. 출시 빌드에는 없다.
                let args = CommandLine.arguments
                if let i = args.firstIndex(of: "-olo-turn"), i + 1 < args.count, let n = Int(args[i + 1]), !debugApplied {
                    debugApplied = true
                    for _ in 0..<n { await model.turn(1) }
                }
                if args.contains("-olo-menu") { model.menu = true }
                #endif
            }
        }
        .statusBarHidden(!model.menu)
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true } // 화면 켜짐 유지(안드로이드와 같다).
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func close() {
        Task {
            await model.close()
            onClose()
        }
    }

    private struct Key: Equatable {
        let size: CGSize
        let insets: EdgeInsets
        let paper: Paper
        let ready: Bool
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
                            if x < 1.0 / 3 { turn(-1) } else if x > 2.0 / 3 { turn(1) } else { toggleMenu() }
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

/// 메뉴(구상안 2A): 위 — 뒤로 · 제목 · "n / m장" · 찾기 · 듣기 · 책갈피. 아래 — 진도 막대 · 목차 · 독서노트 · 보기.
/// 찾기 · 듣기 · 목차 · 독서노트 · 보기는 판 구상안을 정한 뒤에 붙인다(지금은 흐리게 — 누를 수 없다).
private struct ReaderMenu: View {
    let title: String
    let frame: PageFrame
    let insets: EdgeInsets
    let back: () -> Void
    let bookmark: () -> Void
    let seek: (Double) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                CpIconButton(icon: .back, label: "서재로", action: back)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).font(CpFont.title).foregroundStyle(CpColor.text).lineLimit(1)
                    Text("\(frame.spine + 1) / \(frame.chapters)장").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                }
                Spacer(minLength: 8)
                CpIconButton(icon: .search, label: "찾기", enabled: false) {}
                CpIconButton(icon: .headphones, label: "듣기", enabled: false) {}
                CpIconButton(icon: frame.bookmarked ? .bookmarkFilled : .bookmark,
                             label: frame.bookmarked ? "책갈피 빼기" : "책갈피 꽂기",
                             color: frame.bookmarked ? CpColor.accent : CpColor.text, action: bookmark)
            }
            .padding(.trailing, 4)
            .frame(height: CpMetrics.headerHeight)
            .padding(.top, insets.top)
            .padding(.horizontal, max(insets.leading, insets.trailing))
            .background(CpColor.surface)
            .contentShape(Rectangle())
            .onTapGesture {} // 막대를 눌러도 지면으로 새지 않게.

            Spacer()

            VStack(spacing: 4) {
                SeekBar(percent: frame.percent, seek: seek)
                    .padding(.horizontal, 24).padding(.top, 10)
                HStack {
                    item(.toc, "목차")
                    item(.note, "독서노트")
                    item(.view, "보기")
                }
                .padding(.bottom, 4)
            }
            .padding(.bottom, insets.bottom)
            .padding(.horizontal, max(insets.leading, insets.trailing))
            .background(CpColor.surface)
            .contentShape(Rectangle())
            .onTapGesture {}
        }
    }

    private func item(_ icon: CpIcon, _ label: String) -> some View {
        VStack(spacing: 4) {
            CpIconView(icon: icon)
            Text(label).font(CpFont.caption).foregroundStyle(CpColor.text)
        }
        .frame(maxWidth: .infinity, minHeight: 56)
        .opacity(0.38)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(label)
        .accessibilityHint("아직 준비 중입니다")
    }
}

/// 진도 막대. 끌면 손을 뗀 자리로 옮긴다(끄는 동안은 숫자만 바뀐다 — 끌 때마다 짜면 막대가 따라오지 못한다).
private struct SeekBar: View {
    let percent: Double
    let seek: (Double) -> Void
    @State private var dragging: Double?

    var body: some View {
        HStack(spacing: 12) {
            GeometryReader { g in
                let value = (dragging ?? percent) / 100
                let thumb: CGFloat = 22
                let x = (g.size.width - thumb) * value
                ZStack(alignment: .leading) {
                    Rectangle().fill(CpColor.progressTrack).frame(height: 4)
                    Rectangle().fill(CpColor.accent).frame(width: x + thumb / 2, height: 4)
                    Circle().fill(CpColor.accent).frame(width: thumb, height: thumb).offset(x: x)
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in dragging = min(max((v.location.x - thumb / 2) / (g.size.width - thumb), 0), 1) * 100 }
                    .onEnded { _ in
                        if let d = dragging { seek(d) }
                        dragging = nil
                    })
            }
            .frame(height: CpMetrics.touchTarget)
            Text("\(Int((dragging ?? percent).rounded()))%").font(CpFont.label).foregroundStyle(CpColor.text)
                .frame(minWidth: 44, alignment: .trailing)
        }
        .accessibilityElement()
        .accessibilityLabel("진도")
        .accessibilityValue("\(Int(percent.rounded()))%")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: seek(min(percent + 5, 100))
            case .decrement: seek(max(percent - 5, 0))
            @unknown default: break
            }
        }
    }
}
