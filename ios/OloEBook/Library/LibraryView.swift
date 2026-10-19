import SwiftUI
import UniformTypeIdentifiers

/// 서재(구상안 1A 확정, 2026-10-20). 안드로이드 홈과 같은 OLO 머리 · 갈래 · 격자/목록.
/// 창 폭 등급이 커지면 격자 칸 수만 늘어난다(RESPONSIVE_PLAN 의 서재 줄).
struct LibraryView: View {
    @ObservedObject var library: Library
    let open: (LibraryBook) -> Void

    @State private var showFolders = false
    @State private var showInfo = false
    @State private var pickingFolder = false

    var body: some View {
        ZStack {
            CpColor.background.ignoresSafeArea()
            VStack(spacing: 0) {
                CpHeader(title: "OLO eBook", subtitle: subtitle) {
                    CpIconButton(icon: .refresh, label: "새로 훑기") { Task { await library.refresh() } }
                    CpIconButton(icon: .folder, label: "폴더") { showFolders = true }
                    CpIconButton(icon: .info, label: "앱 정보") { showInfo = true }
                }
                if library.isEmpty && !library.scanning {
                    ImportWays(library: library, linkFolder: { pickingFolder = true })
                } else {
                    shelves
                }
            }
            if showInfo {
                CpPopup(onDismiss: { showInfo = false }) {
                    Text("OLO eBook").font(CpFont.title).foregroundStyle(CpColor.text)
                    Text("iOS \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") · 공통 코어 안드로이드 0.54.7")
                        .font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                    HStack { Spacer(); CpButton(title: "닫기", kind: .secondary) { showInfo = false } }
                }
            }
        }
        .sheet(isPresented: $showFolders) {
            FoldersSheet(library: library, linkFolder: { showFolders = false; pickingFolder = true })
        }
        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            if case let .success(url) = result { Task { await library.link(folder: url) } }
        }
    }

    private var subtitle: String {
        let folderCount = library.folders.count + 1 // 앱 폴더도 하나다.
        return library.scanning && library.isEmpty ? "책을 찾는 중…" : "책 \(library.bookCount)권 · 폴더 \(folderCount)개"
    }

    private var shelves: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !library.reading.isEmpty {
                    CpSectionLabel(text: "읽는 중 · \(library.reading.count)권").padding(.top, 12).padding(.bottom, 10)
                    // 읽는 중은 한 줄로 옆으로 넘긴다 — 이어 읽을 책이 위에서 한눈에 보여야 한다.
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 16) {
                            ForEach(library.reading) { item in
                                BookCell(item: item, library: library) { open(item.book) }.frame(width: 106)
                            }
                        }
                        .padding(.horizontal, CpMetrics.gutter)
                    }
                    .padding(.horizontal, -CpMetrics.gutter)
                    CpDivider().padding(.vertical, 16)
                }
                if !library.toRead.isEmpty {
                    section("읽을 책 · \(library.toRead.count)권", items: library.toRead, controls: true)
                }
                if !library.finished.isEmpty {
                    if !library.toRead.isEmpty { CpDivider().padding(.vertical, 16) }
                    section("읽은 책 · \(library.finished.count)권", items: library.finished, controls: library.toRead.isEmpty)
                }
            }
            .padding(.horizontal, CpMetrics.gutter)
            .padding(.bottom, 32)
        }
        .refreshable { await library.refresh() }
    }

    @ViewBuilder
    private func section(_ title: String, items: [ShelfItem], controls: Bool) -> some View {
        HStack {
            CpSectionLabel(text: title)
            Spacer()
            if controls {
                Menu {
                    Picker("순서", selection: $library.sort) {
                        ForEach(LibrarySort.allCases) { Text($0.rawValue).tag($0) }
                    }
                } label: {
                    HStack(spacing: 2) {
                        Text(library.sort.rawValue).font(CpFont.caption).foregroundStyle(CpColor.text)
                        CpIconView(icon: .chevronDown, size: 14)
                    }
                    .frame(minHeight: CpMetrics.touchTarget)
                }
                .accessibilityLabel("순서: \(library.sort.rawValue)")
                CpIconToggle(options: [(LibraryLayout.grid, CpIcon.grid, "격자로 보기"), (.list, CpIcon.rows, "목록으로 보기")],
                             selection: $library.layout)
                    .padding(.leading, 8)
            }
        }
        .padding(.bottom, 8)
        if library.layout == .grid {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100, maximum: 150), spacing: 14, alignment: .bottom)],
                      alignment: .leading, spacing: 20) {
                ForEach(items) { item in BookCell(item: item, library: library) { open(item.book) } }
            }
        } else {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(items) { item in BookRow(item: item, library: library) { open(item.book) } }
            }
        }
    }
}

/// 표지 + 이름 + (읽는 중이면) 진도. 칸은 같은 크기, 그림은 원래 비율로 칸 안에 가장 크게, 밑면을 맞춘다(규칙 6-2).
struct BookCell: View {
    let item: ShelfItem
    let library: Library
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                BookCover(book: item.book, library: library).aspectRatio(0.7, contentMode: .fit)
                Text(item.book.displayName).font(.system(size: 15, weight: .bold)).foregroundStyle(CpColor.text).lineLimit(1)
                if let percent = item.percent, item.openedAt != nil, item.finishedAt == nil {
                    HStack(spacing: 8) {
                        CpProgressBar(fraction: percent / 100)
                        Text("\(Int(percent.rounded()))%").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    }
                } else {
                    Text(item.book.format.rawValue).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibility)
        .accessibilityAddTraits(.isButton)
    }

    private var accessibility: String {
        var s = item.book.displayName
        if let p = item.percent, item.finishedAt == nil, item.openedAt != nil { s += ", \(Int(p.rounded()))% 읽음" }
        if item.finishedAt != nil { s += ", 다 읽음" }
        return s
    }
}

/// 목록 행: 작은 표지 · 이름 · 꼬리표(형식) · 크기 · 추가한 날 · 책갈피 수.
struct BookRow: View {
    let item: ShelfItem
    let library: Library
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 20) {
                BookCover(book: item.book, library: library, compact: true).frame(width: 56, height: 80)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.book.displayName).font(.system(size: 17)).foregroundStyle(CpColor.text).lineLimit(2)
                    HStack(spacing: 6) {
                        Text(item.book.format.rawValue).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                            .padding(.horizontal, 6).padding(.vertical, 1)
                            .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerChip).stroke(CpColor.outline, lineWidth: 1))
                        Text(ByteCountFormatter.string(fromByteCount: item.book.sizeBytes, countStyle: .file))
                            .font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    }
                    Text(added).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    if item.bookmarkCount > 0 {
                        Text("책갈피 \(item.bookmarkCount)").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var added: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = "M월 d일 추가"
        return f.string(from: item.book.added)
    }
}

/// 표지. EPUB 은 책 속 표지 그림, 없으면(또는 TXT) 이름 표지 — 문서 회청 바탕 위 흰 이름 · 형식.
struct BookCover: View {
    let book: LibraryBook
    let library: Library
    var compact = false
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .bottom) {
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
                        .frame(width: g.size.width, height: g.size.height, alignment: .bottom)
                } else {
                    RoundedRectangle(cornerRadius: CpMetrics.cornerChip).fill(CpColor.tileDocument)
                        .overlay(alignment: .topLeading) {
                            if !compact {
                                Text(book.displayName).font(.system(size: 17)).foregroundStyle(.white).padding(12)
                            }
                        }
                        .overlay(alignment: compact ? .center : .bottomLeading) {
                            if compact {
                                CpIconView(icon: book.format == .epub ? .book : .text, size: 22, color: .white)
                            } else {
                                Text(book.format.rawValue).font(CpFont.caption).foregroundStyle(.white.opacity(0.85)).padding(12)
                            }
                        }
                }
            }
        }
        .task(id: book.id + book.edition) { image = await Covers.shared.load(book, library: library) }
        .accessibilityHidden(true)
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
            CpListRow(icon: .plus, tile: CpColor.tileFolder, title: "다른 폴더 연결",
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

/// 폴더 판: 앱 폴더 · 연결한 폴더 · 다른 폴더 연결. 연결한 폴더는 앱 폴더에 **속하지 않으므로** 들이지 않는다(규칙 1).
struct FoldersSheet: View {
    @ObservedObject var library: Library
    let linkFolder: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "폴더", subtitle: "책을 찾는 곳") {
                CpIconButton(icon: .close, label: "닫기") { dismiss() }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    CpListRow(icon: .folder, tile: CpColor.tileFolder, title: "이 iPhone 의 OLO eBook 폴더",
                              subtitle: "파일 앱에서 열기") { openAppFolderInFiles() }
                    ForEach(library.folders) { folder in
                        HStack(spacing: 0) {
                            CpListRow(icon: .folder, tile: CpColor.tileFolder, title: folder.name, subtitle: "연결한 폴더")
                            CpIconButton(icon: .close, label: "\(folder.name) 연결 끊기", color: CpColor.textMuted) {
                                Task { await library.unlink(folder) }
                            }
                        }
                    }
                    CpListRow(icon: .plus, tile: CpColor.tileFolder, title: "다른 폴더 연결",
                              subtitle: "iCloud Drive · 다운로드 등", action: linkFolder)
                }
                .padding(.horizontal, CpMetrics.gutter)
            }
        }
        .background(CpColor.background.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationCornerRadius(CpMetrics.cornerSheet)
    }
}
