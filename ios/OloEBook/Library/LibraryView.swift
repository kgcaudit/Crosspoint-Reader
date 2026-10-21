import SwiftUI
import UniformTypeIdentifiers

/// 서재(구상안 9 · 10 · 11 확정, 2026-10-21 — 안드로이드 0.54.8 에 맞춤). 머리 줄 없이 맨 위 한 줄에 순서 · 찾기 · 보기 · 더 보기
/// (안드로이드 0.44: 앱 이름은 아이콘이 이미 말하고, 권 수는 갈래 이름에 있다). 갈래는 읽는 책 · 읽은 책 · 읽을 책(0.43 용어),
/// 셋 다 같은 보기를 따르고 옆으로 넘기는 줄이 없다(0.38 — 만화 탭이 생기면 좌우 밀기가 탭 넘기기다).
/// 창 폭이 넓어지면 칸 수만 늘어난다(RESPONSIVE_PLAN 의 서재 줄).
struct LibraryView: View {
    @ObservedObject var library: Library
    let open: (LibraryBook) -> Void

    @State private var showFolders = false
    @State private var showInfo = false
    @State private var showMore = false
    @State private var showSort = false
    @State private var pickingFolder = false
    /// 책 찾기 · 독서 기록(구상안 29A · 30). 찾던 말은 닫아도 남는다 — 다시 열면 그대로(안드로이드와 같다).
    @State private var searching = false
    @State private var query = ""
    @State private var showStats = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            CpColor.background.ignoresSafeArea()
            VStack(spacing: 0) {
                if library.isEmpty && !library.scanning {
                    // 처음 켠 사람: 앱 이름과 할 일. 앱 정보는 책이 없어도 닿아야 한다(안드로이드와 같다).
                    CpHeader(title: "OLO eBook", subtitle: "책을 들여 주세요") {
                        CpIconButton(icon: .refresh, label: "새로고침") { Task { await library.refresh() } }
                        CpIconButton(icon: .info, label: "앱 정보") { showInfo = true }
                    }
                    ImportWays(library: library, linkFolder: { pickingFolder = true })
                } else {
                    LibraryTools(library: library, onSort: { showSort = true }, onSearch: { searching = true }, onMore: { showMore = true })
                    if library.scanning { CpProgressBar(fraction: 0.35).padding(.horizontal, CpMetrics.gutter) }
                    LibraryShelves(library: library, open: open)
                }
            }
            if showMore {
                // 판 밖을 누르면 닫힌다. 막을 어둡게 하지 않는다 — 서재를 가리지 않는 작은 판이다.
                Color.clear.contentShape(Rectangle()).ignoresSafeArea().onTapGesture { showMore = false }
                    .accessibilityLabel("닫기").accessibilityAddTraits(.isButton)
                MoreMenu(items: [
                    (.refresh, "새로고침", { Task { await library.refresh() } }),
                    (.folder, "책 폴더", { showFolders = true }),
                    (.timer, "독서 기록", { showStats = true }),
                    (.info, "앱 정보", { showInfo = true }),
                ], dismiss: { showMore = false })
                .padding(.top, 4 + CpMetrics.touchTarget).padding(.trailing, 8)
            }
            if showSort {
                CpPopup(onDismiss: { showSort = false }) {
                    Text("순서").font(CpFont.title).foregroundStyle(CpColor.text)
                    Text("읽는 책 · 읽은 책 · 읽을 책이 모두 이 차례를 따릅니다.").font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                    VStack(spacing: 0) {
                        ForEach(LibrarySort.allCases) { sort in
                            CpRadioRow(title: sort.rawValue, selected: sort == library.sort, inset: 0) {
                                library.sort = sort
                                showSort = false
                            }
                        }
                    }
                }
            }
            if searching {
                LibrarySearchScreen(library: library, query: $query, open: open, back: { searching = false })
                    .transition(.move(edge: .trailing))
            }
            if showStats {
                ReadingStatsScreen(library: library, open: { showStats = false; open($0) }, back: { showStats = false })
                    .transition(.move(edge: .trailing))
            }
            if showFolders {
                FoldersPopup(library: library, addFolder: { showFolders = false; pickingFolder = true },
                             dismiss: { showFolders = false })
            }
            if showInfo {
                AboutScreen(library: library, back: { showInfo = false })
                    .transition(.move(edge: .trailing))
            }
        }
        #if DEBUG
        // 화면 확인용: -olo-book-search <말> 은 책 찾기, -olo-stats 는 독서 기록, -olo-about 은 앱 정보를 연다. 출시 빌드에는 없다.
        .onAppear {
            let args = CommandLine.arguments
            if let i = args.firstIndex(of: "-olo-book-search"), i + 1 < args.count { query = args[i + 1]; searching = true }
            if args.contains("-olo-stats") { showStats = true }
            if args.contains("-olo-about") || args.contains("-olo-import") || args.contains("-olo-export-to") { showInfo = true }
        }
        #endif
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case let .success(url) = result { Task { await library.link(folder: url) } }
        }
    }
}

/// 맨 위 도구 한 줄(안드로이드 `LibraryTools`): 순서 ▾ · · · 찾기 · 격자 | 목록 | 책장 · 더 보기.
/// 순서 글자가 갈래 이름과 같은 시작선에 선다 — 누르는 곳의 안쪽 여백(10)만큼 덜 들인다.
private struct LibraryTools: View {
    @ObservedObject var library: Library
    let onSort: () -> Void
    let onSearch: () -> Void
    let onMore: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onSort) {
                HStack(spacing: 2) {
                    Text(library.sort.rawValue).font(CpFont.caption).foregroundStyle(CpColor.text)
                    CpIconView(icon: .chevronDown, size: 18, color: CpColor.textMuted)
                }
                .padding(.leading, 10).padding(.trailing, 4)
                .frame(minHeight: CpMetrics.touchTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("순서: \(library.sort.rawValue)")
            Spacer(minLength: 0)
            CpIconButton(icon: .search, label: "책 찾기", action: onSearch)
            CpIconToggle(options: [(LibraryLayout.grid, CpIcon.grid, "격자로 보기"), (.list, CpIcon.rows, "목록으로 보기"),
                                   (.shelf, CpIcon.shelf, "책장으로 보기")],
                         selection: $library.layout)
            CpIconButton(icon: .more, label: "더 보기", action: onMore)
        }
        .padding(.leading, CpMetrics.gutter - 10)
        .padding(.top, 4)
    }
}

/// 더 보기 판: 오른쪽 위에 떠서 서재를 밀어내지 않는다.
private struct MoreMenu: View {
    let items: [(CpIcon, String, () -> Void)]
    let dismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(items.indices, id: \.self) { i in
                let (icon, label, action) = items[i]
                Button { dismiss(); action() } label: {
                    HStack(spacing: 14) {
                        CpIconView(icon: icon, size: 22)
                        Text(label).font(CpFont.body).foregroundStyle(CpColor.text)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)
                    .frame(minHeight: CpMetrics.touchTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(label)
            }
        }
        .padding(.vertical, 6)
        .frame(width: 200)
        .background(CpColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall + 4))
        .shadow(color: .black.opacity(0.25), radius: 8, y: 3)
    }
}

/// 칸 셈(안드로이드 `shelfColumns`): 칸 폭 102(넓은 화면 130) 이상 · 사이 14 · 적어도 세 칸. 넓은 화면에서 102 로 채우면 한 줄에
/// 예닐곱 권이 작게 늘어서 제목이 두세 자에서 잘렸다(안드로이드 0.30).
enum LibraryGrid {
    static let gap: CGFloat = 14
    static let coverAspect: CGFloat = 1 / 1.45

    static func columns(_ width: CGFloat) -> Int {
        let item: CGFloat = width >= 600 ? 130 : 102
        return max(Int((width - CpMetrics.gutter * 2 + gap) / (item + gap)), 3)
    }

    static func cellWidth(_ width: CGFloat) -> CGFloat {
        let n = CGFloat(columns(width))
        return floor((width - CpMetrics.gutter * 2 - gap * (n - 1)) / n)
    }
}

/// 세 갈래. 빈 갈래는 이름째 숨긴다. 읽을 책 앞에만 선을 긋는다(앞의 두 갈래가 있을 때) — 책장은 판이 갈래를 가른다.
private struct LibraryShelves: View {
    @ObservedObject var library: Library
    let open: (LibraryBook) -> Void

    private struct Section: Identifiable {
        let id: String
        let title: String
        let items: [ShelfItem]
        /// 읽을 책 앞의 선. 앞의 두 갈래가 비면 긋지 않는다 — 맨 위에 선만 떠 있게 된다.
        var divider = false
    }

    private var sections: [Section] {
        [Section(id: "reading", title: "읽는 책 · \(library.reading.count)권", items: library.reading),
         Section(id: "finished", title: "읽은 책 · \(library.finished.count)권", items: library.finished),
         Section(id: "to-read", title: "읽을 책 · \(library.toRead.count)권", items: library.toRead,
                 divider: !(library.reading.isEmpty && library.finished.isEmpty))]
            .filter { !$0.items.isEmpty }
    }

    var body: some View {
        GeometryReader { g in
            let width = LibraryGrid.cellWidth(g.size.width)
            let columns = LibraryGrid.columns(g.size.width)
            if library.layout == .shelf {
                ShelfWall {
                    ScrollView {
                        VStack(spacing: 0) {
                            ShelfCrown()
                            ForEach(sections) { section in
                                let rows = section.items.chunked(columns)
                                ForEach(rows.indices, id: \.self) { i in
                                    ShelfBoard(label: i == 0 ? section.title : nil)
                                    WoodRow(items: rows[i], width: width) { item in
                                        BookCover(book: item.book, library: library, shadow: true)
                                            .bookButton(item) { open(item.book) }
                                    }
                                }
                            }
                            EmptyShelves(cellWidth: width, minHeight: g.size.height)
                        }
                    }
                    .refreshable { await library.refresh() }
                }
                // 뒷벽은 홈 표시줄 밑까지 — 안전 영역에서 끊으면 책장 아래에 바탕색 띠가 남는다.
                .ignoresSafeArea(edges: .bottom)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(sections) { section in
                            let items = section.items
                            if section.divider {
                                Spacer().frame(height: 14)
                                CpDivider()
                            }
                            CpSectionLabel(text: section.title)
                                .padding(.horizontal, CpMetrics.gutter).padding(.top, 18).padding(.bottom, 6)
                            if library.layout == .grid {
                                ForEach(items.chunked(columns), id: \.first!.id) { row in
                                    HStack(alignment: .top, spacing: LibraryGrid.gap) {
                                        ForEach(row) { BookCell(item: $0, library: library, width: width) { open($0) } }
                                    }
                                    .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 9)
                                }
                            } else {
                                LazyVStack(alignment: .leading, spacing: 0) {
                                    ForEach(items) { item in BookRow(item: item, library: library) { open(item.book) } }
                                }
                            }
                        }
                        Spacer().frame(height: 24)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .refreshable { await library.refresh() }
            }
        }
    }
}

extension Array {
    /// 한 줄 칸 수씩 자른다.
    func chunked(_ size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: Swift.max(size, 1)).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}

extension View {
    /// 책 한 권을 누르는 곳으로. 화면 읽기는 이름 · 진도 · 다 읽음을 한 번에 읽는다.
    func bookButton(_ item: ShelfItem, action: @escaping () -> Void) -> some View {
        Button(action: action) { self.contentShape(Rectangle()) }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(item.spokenLabel)
            .accessibilityAddTraits(.isButton)
    }
}

extension ShelfItem {
    var isReading: Bool { openedAt != nil && finishedAt == nil }

    var spokenLabel: String {
        var s = book.label
        if isReading, let p = percent { s += ", \(Int(p.rounded()))% 읽음" }
        if finishedAt != nil { s += ", 다 읽음" }
        return s
    }
}

/// "10월 18일". 해가 바뀌어도 서재에서는 날짜만으로 충분하다 — 해까지 적으면 칸 폭을 넘는다(안드로이드 `monthDay`).
func monthDay(_ date: Date) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "ko_KR")
    f.dateFormat = "M월 d일"
    return f.string(from: date)
}

/// 격자 칸: 표지 · 이름 · 갈래에 따른 줄(읽는 책: 진도, 읽은 책: 다 읽은 날, 읽을 책: 형식). 칸 폭은 화면에서 나오고 표지는 칸 밑면에 선다.
struct BookCell: View {
    let item: ShelfItem
    let library: Library
    let width: CGFloat
    let action: (LibraryBook) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            BookCover(book: item.book, library: library)
            Text(item.book.label).font(CpFont.label).foregroundStyle(CpColor.text).lineLimit(1).padding(.top, 8)
            BookStatus(item: item, grid: true)
        }
        .frame(width: width, alignment: .leading)
        .bookButton(item) { action(item.book) }
    }
}

/// 갈래에 따른 줄. 다 읽은 책은 막대 대신 "다 읽음 · 끝낸 날"(강조색). 읽을 책은 격자에서만 형식(목록은 아래 꼬리표에 있다).
private struct BookStatus: View {
    let item: ShelfItem
    let grid: Bool

    var body: some View {
        if let finished = item.finishedAt {
            Text("다 읽음 · \(monthDay(finished))").font(CpFont.caption).foregroundStyle(CpColor.accentText).padding(.top, 4)
        } else if item.isReading {
            HStack(spacing: 6) {
                CpProgressBar(fraction: (item.percent ?? 0) / 100)
                Text("\(Int((item.percent ?? 0).rounded()))%").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            }
            .padding(.top, 6)
            .padding(.trailing, grid ? 0 : 12)
        } else if grid {
            // 저자를 알면 저자(구상안 29A 로 책 정보를 읽는다), 모르면 형식.
            Text(item.book.author ?? item.book.format.rawValue).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                .lineLimit(1).padding(.top, 4)
        }
    }
}

/// 목록 행(안드로이드 `DetailRow`): 작은 표지 · 이름 · 갈래 줄 · 형식 꼬리표 · 크기 · 든 폴더 · 추가한 날 · 책갈피 수.
struct BookRow: View {
    let item: ShelfItem
    let library: Library
    let action: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            BookCover(book: item.book, library: library, small: true).frame(width: 56)
            VStack(alignment: .leading, spacing: 0) {
                Text(item.book.label).font(CpFont.body).foregroundStyle(CpColor.text).lineLimit(2)
                BookStatus(item: item, grid: false)
                HStack(spacing: 0) {
                    Text(item.book.format.rawValue).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .overlay(RoundedRectangle(cornerRadius: 4).stroke(CpColor.outline, lineWidth: 1))
                    Text("  \(ByteCountFormatter.string(fromByteCount: item.book.sizeBytes, countStyle: .file)) · \(item.book.folderLabel)")
                        .font(CpFont.caption).foregroundStyle(CpColor.textMuted).lineLimit(1)
                }
                .padding(.top, 4)
                Text("\(monthDay(item.book.added)) 추가").font(CpFont.caption).foregroundStyle(CpColor.textMuted).padding(.top, 6)
                if item.bookmarkCount > 0 {
                    Text("책갈피 \(item.bookmarkCount)").font(CpFont.caption).foregroundStyle(CpColor.textMuted).padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, CpMetrics.gutter).padding(.trailing, 4).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .bookButton(item, action: action)
    }
}

/// 책 표지(안드로이드 `CpCover`). 칸의 크기는 늘 같고(1 : 1.45), 그림은 원래 비율 그대로 칸 안에 가장 크게 — 큰 표지는 칸 밑면,
/// 작은 표지(목록)는 가운데. 테두리 · 둥근 모서리는 그림에만. 그림이 없으면 대신 표지: 문서 회청 위에 이름 · 형식을 어두운 띠에
/// 얹는다 — 띠 없이 흰 글자를 얹으면 어두운 테마에서 대비가 3 밖에 안 됐다(안드로이드 0.29).
/// [shadow] 는 책장에서만: 뒷벽에 비스듬히 지는 그림자를 **그림 크기**로(칸 크기로 깔면 낮은 표지 위로 빈 그림자가 뜬다, 0.48.6).
struct BookCover: View {
    let book: LibraryBook
    let library: Library
    var small = false
    var shadow = false
    @State private var image: UIImage?

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: small ? 4 : 8)
        Color.clear
            .aspectRatio(LibraryGrid.coverAspect, contentMode: .fit)
            .overlay(alignment: small ? .center : .bottom) {
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
                        .clipShape(shape)
                        .overlay(shape.stroke(CpColor.divider, lineWidth: 1))
                        .background { if shadow { CoverShadow() } }
                } else {
                    fallback
                        .clipShape(shape)
                        .overlay(shape.stroke(CpColor.divider, lineWidth: 1))
                        .background { if shadow { CoverShadow() } }
                }
            }
            .task(id: book.id + book.edition) { image = await Covers.shared.load(book, library: library) }
            .accessibilityHidden(true)
    }

    private var fallback: some View {
        CpColor.tileDocument.overlay {
            if small {
                CpIconView(icon: book.format == .epub ? .book : book.format == .pdf ? .pdf : .text, size: 18, color: .white)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    Text(book.label).font(CpFont.body).foregroundStyle(.white).lineLimit(3)
                        .padding(10).frame(maxWidth: .infinity, alignment: .leading).background(Color.black.opacity(0.6))
                    Spacer(minLength: 0)
                    Text(book.format.rawValue).font(CpFont.caption).foregroundStyle(.white)
                        .padding(.horizontal, 10).padding(.vertical, 8)
                        .frame(maxWidth: .infinity, alignment: .leading).background(Color.black.opacity(0.6))
                }
            }
        }
    }
}

/// 뒷벽에 오른쪽 아래로 지는 그림자. 위는 6 내려 시작하고 오른쪽으로 6 삐져나온다.
private struct CoverShadow: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 6).fill(Color.black.opacity(0.4))
            .padding(.leading, 6).padding(.top, 6).offset(x: 6)
    }
}

/// 빈 서재: 책을 들이는 세 길(구상안 3A 확정).
struct ImportWays: View {
    @ObservedObject var library: Library
    let linkFolder: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Spacer().frame(height: 96)
            CpSectionLabel(text: "책을 들이는 길")
            CpListRow(icon: .folder, tile: CpColor.tileFolder, title: "이 iPhone 의 OLO eBook 폴더",
                      subtitle: "파일 앱 › 나의 iPhone › OLO eBook 에 넣으면 저절로 보입니다") { openAppFolderInFiles() }
            // "폴더 추가"(0.43 용어집, 안드로이드와 같은 말). 더하는 줄은 강조색 타일 — 폴더 행(클레이)과 갈린다.
            CpListRow(icon: .plus, tile: CpColor.accent, title: "폴더 추가",
                      subtitle: "iCloud Drive · 다운로드 등 이미 책이 있는 폴더", action: linkFolder)
            CpListRow(icon: .book, tile: CpColor.tileDocument, title: "다른 앱에서 열기",
                      subtitle: "메일 · 메신저의 파일에서 공유 › OLO eBook")
            Spacer()
        }
        .padding(.horizontal, CpMetrics.gutter)
    }
}

/// 파일 앱에서 앱 폴더를 연다. 열 수 없으면(시뮬레이터 등) 아무 일도 하지 않는다.
@MainActor
func openAppFolderInFiles() {
    var components = URLComponents(url: Library.appFolder, resolvingAgainstBaseURL: false)
    components?.scheme = "shareddocuments"
    if let url = components?.url { UIApplication.shared.open(url) }
}

/// 책 폴더 판(구상안 15A, 안드로이드 "책 폴더" 팝업과 같은 모양 · 말): 폴더 추가(맨 위) · 앱 폴더 · 추가한 폴더(✕ 빼기).
/// 추가한 폴더는 앱 폴더에 **속하지 않으므로** 들이지 않는다(UI 규칙 1). 앱 폴더는 뺄 수 없다 — 누르면 파일 앱에서 연다.
struct FoldersPopup: View {
    @ObservedObject var library: Library
    let addFolder: () -> Void
    let dismiss: () -> Void

    var body: some View {
        CpPopup(onDismiss: dismiss) {
            Text("책 폴더").font(CpFont.title).foregroundStyle(CpColor.text)
            Text("폴더를 빼도 그 책들의 읽은 자리와 책갈피는 남습니다. 다시 추가하면 이어집니다.")
                .font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
            ScrollView {
                VStack(spacing: 0) {
                    row(icon: .plus, tile: CpColor.accent, title: "폴더 추가", action: addFolder)
                    row(icon: .folder, tile: CpColor.tileFolder, title: "OLO eBook (이 iPhone)") { openAppFolderInFiles() }
                    ForEach(library.folders) { folder in
                        HStack(spacing: 0) {
                            row(icon: .folder, tile: CpColor.tileFolder, title: folder.name, action: nil)
                            CpIconButton(icon: .close, label: "\(folder.name) 빼기", color: CpColor.textMuted) {
                                Task { await library.unlink(folder) }
                            }
                        }
                    }
                }
            }
            .frame(maxHeight: 320)
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// 48 줄(안드로이드 compact 행): 타일 · 이름.
    private func row(icon: CpIcon, tile: Color, title: String, action: (() -> Void)?) -> some View {
        let content = HStack(spacing: 14) {
            CpTile(icon: icon, color: tile)
            Text(title).font(CpFont.body).foregroundStyle(CpColor.text).lineLimit(1)
            Spacer(minLength: 0)
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
        return Group {
            if let action { Button(action: action) { content }.buttonStyle(.plain) } else { content.accessibilityElement(children: .combine) }
        }
    }
}
