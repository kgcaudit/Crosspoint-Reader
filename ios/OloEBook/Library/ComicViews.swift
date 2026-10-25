import OloCore
import SwiftUI
import UIKit

// MARK: 말

/// "만화" · "웹툰", "권" · "화"(안드로이드 `kindLabel` · `unitWord`).
extension Work {
    var kindLabel: String { webtoon ? "웹툰" : "만화" }
    var unitWord: String { webtoon ? "화" : "권" }
    var rtl: Bool { rightToLeft?.boolValue == true }

    /// "만화 · 12권 · 2곳에서 모음 · 완결 · 오→왼". 모르는 것은 뺀다(안드로이드 `workSubtitle`).
    var subtitle: String {
        var parts = [kindLabel, "\(volumeCount)\(unitWord)"]
        if places.count > 1 { parts.append("\(places.count)곳에서 모음") }
        if complete { parts.append("완결") }
        if let r = rightToLeft { parts.append(r.boolValue ? "오→왼" : "왼→오") }
        return parts.joined(separator: " · ")
    }
}

/// "3권을" · "12권을" · "외전을" — 받침으로 고른다. 숫자로 끝나면 읽는 소리(0 · 1 · 3 · 6 · 7 · 8 은 받침이 있다).
func objectParticle(_ word: String) -> String {
    guard let last = word.trimmingCharacters(in: .whitespaces).unicodeScalars.last else { return "을" }
    let v = last.value
    if (0xAC00...0xD7A3).contains(v) { return (v - 0xAC00) % 28 == 0 ? "를" : "을" }
    if let d = Int(String(last)) { return [0, 1, 3, 6, 7, 8].contains(d) ? "을" : "를" }
    return "를"
}

// MARK: 표지

/// 만화 표지: 표지 그림(이름이 cover · folder 인 그림, 없으면 첫 쪽). 펼침면이면 앞표지 쪽 반만(왼→오는 오른쪽 · 오→왼은 왼쪽,
/// 공통 `CoverCrop.front`). 칸 크기는 책 표지와 같다(1 : 1.45).
struct ComicCover: View {
    /// nil 이면 권이 없는 작품 — 빈 표지를 그린다.
    let unit: ComicUnit?
    let library: Library
    var rtl = false
    var tag: String?
    var shadow = false
    /// 작품의 얼굴로 그릴 때 그 작품(구상안 44) — 고른 표지가 있으면 그것. 권 줄 · 다음 권 카드는 nil(제 표지).
    var workKey: String?
    /// 처음부터 꺼내 둔 표지로 그린다 — 비워 두면 칸이 생길 때마다 대체 칸이 한 번 비쳤다(사용자 지적: 잔상).
    @State private var image: UIImage?
    @ObservedObject private var picked = CoverStore.shared

    init(unit: ComicUnit?, library: Library, rtl: Bool = false, tag: String? = nil, shadow: Bool = false, workKey: String? = nil) {
        self.unit = unit
        self.library = library
        self.rtl = rtl
        self.tag = tag
        self.shadow = shadow
        self.workKey = workKey
        let chosen = workKey.flatMap { CoverStore.shared.custom(IosCovers.shared.workId(workKey: $0)) }
        _image = State(initialValue: chosen ?? unit.flatMap { ComicCovers.shared.ready($0, rtl: rtl) })
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 8)
        Color.clear
            .aspectRatio(LibraryGrid.coverAspect, contentMode: .fit)
            .overlay(alignment: .bottom) {
                Group {
                    if let image {
                        Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
                    } else {
                        CpColor.tileOther.aspectRatio(1 / LibraryGrid.coverAspect, contentMode: .fit)
                            .overlay(CpIconView(icon: .grid, size: 22, color: .white))
                    }
                }
                .clipShape(shape)
                .overlay(shape.stroke(CpColor.divider, lineWidth: 1))
                .overlay(alignment: .bottomTrailing) {
                    if let tag {
                        Text(tag).font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.black.opacity(0.8)).clipShape(RoundedRectangle(cornerRadius: 4)).padding(6)
                    }
                }
            }
            .task(id: "\(unit?.id ?? "")|\(workKey ?? "")|\(picked.version)") {
                if let key = workKey, let chosen = CoverStore.shared.custom(IosCovers.shared.workId(workKey: key)) {
                    image = chosen
                } else if let unit {
                    image = await ComicCovers.shared.load(unit, library: library, rtl: rtl)
                } else {
                    image = nil
                }
            }
            .accessibilityHidden(true)
    }
}

final class ComicCovers: @unchecked Sendable {
    static let shared = ComicCovers()
    private let cache = NSCache<NSString, UIImage>()

    /// 이미 꺼내 둔 표지(압축을 열지 않는다). 칸이 처음 그려질 때 쓴다 — 대체 칸이 한 번 비치지 않게.
    func ready(_ unit: ComicUnit, rtl: Bool) -> UIImage? { cache.object(forKey: "\(unit.id)|\(rtl)" as NSString) }

    func load(_ unit: ComicUnit, library: Library, rtl: Bool) async -> UIImage? {
        let key = "\(unit.id)|\(rtl)" as NSString
        if let img = cache.object(forKey: key) { return img }
        guard let file = await library.comicFile(unit.id) else { return nil }
        let end = await library.beginAccess(folderId: file.folderId)
        defer { end() }
        let image = await Task.detached(priority: .utility) { () -> UIImage? in
            let data: Data
            if let nestedId = file.nestedId,
               let saved = IosNestedVolumes.shared.cover(id: nestedId, stamp: file.stamp, cacheDir: Library.ComicFile.nestedCache) {
                // 압축 속 권은 살필 때 적어 둔 표지 — 권을 열면 50~90MB 를 꺼내고, 꺼낸 사본(최근 4권)도 밀려난다.
                data = saved as Data
            } else {
                guard let book = file.open(cover: true) else { return nil }
                defer { book.close() }
                let coverName = unit.contents?.cover
                let index = coverName.flatMap { name in book.pages.firstIndex(of: name) } ?? 0
                guard let page = book.data(i: Int32(index)) else { return nil }
                data = page as Data
            }
            guard let img = ComicImages.decode(data, maxPixel: 600) else { return nil }
            guard let cg = img.cgImage, img.size.width > img.size.height * 1.15 else { return img }
            let range = CoverCrop.shared.front(width: Int32(cg.width), height: Int32(cg.height), rightToLeft: rtl)
            let rect = CGRect(x: Int(range.first), y: 0, width: Int(range.last) - Int(range.first) + 1, height: cg.height)
            return cg.cropping(to: rect).map { UIImage(cgImage: $0) } ?? img
        }.value
        if let image { cache.setObject(image, forKey: key) }
        return image
    }
}

// MARK: 만화 탭

/// 만화 탭(구상안 35A, 안드로이드 `ComicShelf`): 작품 단위 세 갈래. 읽는 중 칸은 이어 볼 권을 바로 열고, 다른 칸은 작품 화면.
struct ComicShelves: View {
    @ObservedObject var library: Library
    let open: (LibraryBook) -> Void
    let openWork: (Work) -> Void
    /// 길게 누름(구상안 40): 작품 화면 · 갈래 옮기기 판.
    var menu: (Work) -> Void = { _ in }

    private struct Item: Identifiable {
        let work: Work
        let status: WorkStatus
        let resume: (entry: WorkEntry, progress: ComicProgress?)?
        var id: String { work.key + "|" + work.title }
    }

    private struct Section: Identifiable {
        let id: String
        let title: String
        let items: [Item]
        var divider = false
    }

    private var sections: [Section] {
        let items = library.works.map { w -> Item in
            let r = ComicReading.shared.resume(work: w, progress: library.comicProgress)
            return Item(work: w, status: library.status(w), resume: r.map { ($0.first!, $0.second) })
        }
        func byName(_ a: Item, _ b: Item) -> Bool {
            a.work.title.compare(b.work.title, options: [.numeric, .caseInsensitive], locale: Locale(identifier: "ko_KR")) == .orderedAscending
        }
        let reading = items.filter { $0.status.shelf == .reading }
            .sorted { ($0.status.lastReadAtEpochMs?.int64Value ?? 0) > ($1.status.lastReadAtEpochMs?.int64Value ?? 0) }
        let finished = items.filter { $0.status.shelf == .finished }
            .sorted { ($0.status.finishedAtEpochMs?.int64Value ?? 0) > ($1.status.finishedAtEpochMs?.int64Value ?? 0) }
        let toRead = items.filter { $0.status.shelf == .toRead }.sorted(by: byName)
        return [Section(id: "reading", title: "읽는 책 · \(reading.count)작품", items: reading),
                Section(id: "finished", title: "읽은 책 · \(finished.count)작품", items: finished),
                Section(id: "to-read", title: "읽을 책 · \(toRead.count)작품", items: toRead, divider: !(reading.isEmpty && finished.isEmpty))]
            .filter { !$0.items.isEmpty }
    }

    var body: some View {
        GeometryReader { g in
            let width = LibraryGrid.cellWidth(g.size.width)
            let columns = LibraryGrid.columns(g.size.width)
            // 원목 벽은 늘 두고 책장 보기에서만 보인다(ShelfWall) — 목록은 바로 바꾸고 벽만 서서히.
            ShelfWall(shown: library.layout == .shelf) {
                if library.layout == .shelf {
                    ScrollView {
                        VStack(spacing: 0) {
                            ShelfCrown()
                            ForEach(sections) { section in
                                let rows = section.items.chunked(columns)
                                ForEach(rows.indices, id: \.self) { i in
                                    ShelfBoard(label: i == 0 ? section.title : nil)
                                    WoodRow(items: rows[i], width: width) { item in
                                        ComicCover(unit: cover(item), library: library, rtl: item.work.rtl, tag: tag(item), shadow: true, workKey: item.work.key)
                                            .workButton(item.work.title, longPress: { menu(item.work) }) { tap(item) }
                                    }
                                }
                            }
                            EmptyShelves(cellWidth: width, minHeight: g.size.height)
                        }
                    }
                    .refreshable { await library.refresh() }
                    // 책장은 홈 표시줄 밑까지 굴린다 — 안전 영역에서 끊으면 책장 아래에 바탕색 띠가 남는다.
                    .ignoresSafeArea(edges: .bottom)
                } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(sections) { section in
                            if section.divider {
                                Spacer().frame(height: 14)
                                CpDivider()
                            }
                            CpSectionLabel(text: section.title)
                                .padding(.horizontal, CpMetrics.gutter).padding(.top, 18).padding(.bottom, 6)
                            if library.layout == .grid {
                                ForEach(section.items.chunked(columns), id: \.first!.id) { row in
                                    HStack(alignment: .top, spacing: LibraryGrid.gap) {
                                        ForEach(row) { item in cell(item).frame(width: width, alignment: .leading) }
                                    }
                                    .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 9)
                                }
                            } else {
                                LazyVStack(alignment: .leading, spacing: 0) {
                                    ForEach(section.items) { item in rowView(item) }
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

    /// 표지로 쓸 권. 권이 하나도 없는 작품(합치기 · 빼기 뒤에 빈 것)은 nil — 강제로 꺼내면 앱이 죽었다.
    private func cover(_ item: Item) -> ComicUnit? { item.resume?.entry.unit ?? item.work.entries.first?.unit }

    /// 읽는 중 칸의 표지 꼬리표: 권이 둘 이상일 때 "12권".
    private func tag(_ item: Item) -> String? {
        item.status.shelf == .reading && item.work.volumeCount > 1 ? "\(item.work.volumeCount)\(item.work.unitWord)" : nil
    }

    private func line(_ item: Item) -> some View {
        Group {
            if item.status.shelf == .reading, let r = item.resume {
                VStack(alignment: .leading, spacing: 4) {
                    CpProgressBar(fraction: Double(r.progress?.fraction ?? 0))
                    if item.status.newVolumes {
                        Text("새 \(item.work.unitWord) · \(r.entry.label)부터").foregroundStyle(CpColor.accentText)
                    } else {
                        Text("\(r.entry.label) · \(r.progress.map { "\(Int($0.page) + 1)쪽" } ?? "처음부터")").foregroundStyle(CpColor.textMuted)
                    }
                }
            } else if item.status.shelf == .finished, let at = item.status.finishedAtEpochMs {
                Text("다 읽음 · \(monthDay(Date(timeIntervalSince1970: at.doubleValue / 1000)))").foregroundStyle(CpColor.accentText)
            } else {
                Text("\(item.work.kindLabel) · \(item.work.volumeCount)\(item.work.unitWord)").foregroundStyle(CpColor.textMuted)
            }
        }
        .font(CpFont.caption)
    }

    private func cell(_ item: Item) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ComicCover(unit: cover(item), library: library, rtl: item.work.rtl, tag: tag(item), workKey: item.work.key)
            Text(item.work.title).font(CpFont.label).foregroundStyle(CpColor.text).lineLimit(1).padding(.top, 8)
            line(item).padding(.top, 4)
        }
        .workButton(item.status.shelf == .reading ? "\(item.work.title) \(item.resume?.entry.label ?? "") 이어 읽기" : "\(item.work.title) 작품",
                    longPress: { menu(item.work) }) { tap(item) }
    }

    private func rowView(_ item: Item) -> some View {
        HStack(alignment: .top, spacing: 14) {
            ComicCover(unit: cover(item), library: library, rtl: item.work.rtl, workKey: item.work.key).frame(width: 56)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.work.title).font(CpFont.body).foregroundStyle(CpColor.text).lineLimit(2)
                Text(item.work.subtitle).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                line(item)
            }
            Spacer(minLength: 0)
            // 책 목록과 같은 보이는 문(구상안 43 ④) — 길게 누름과 같은 작품 판.
            CpIconButton(icon: .more, label: "\(item.work.title) 더 보기", color: CpColor.textMuted) { menu(item.work) }
        }
        .padding(.leading, CpMetrics.gutter).padding(.trailing, 4).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .workButton("\(item.work.title) 작품", longPress: { menu(item.work) }) { tap(item) }
    }

    private func tap(_ item: Item) {
        if item.status.shelf == .reading, let r = item.resume, let book = library.comicBook(r.entry.unit) {
            open(book)
        } else {
            openWork(item.work)
        }
    }
}

extension View {
    func workButton(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { self.contentShape(Rectangle()) }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityAddTraits(.isButton)
    }

    /// 누르면 [action], 길게 누르면 [longPress](구상안 40 — 갈래 옮기기 판). 단추(Button)에 길게 누름을 겹치면 손을 뗄 때 누름도
    /// 함께 불려, 판이 뜨자마자 작품이 열렸다 — 두 몸짓을 나란히 받는다.
    func workButton(_ label: String, longPress: @escaping () -> Void, action: @escaping () -> Void) -> some View {
        self.contentShape(Rectangle())
            .onTapGesture(perform: action)
            .onLongPressGesture(minimumDuration: 0.45, perform: longPress)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(named: "갈래 옮기기", longPress)
    }
}

// MARK: 작품 화면

/// 작품 화면(구상안 35A, 안드로이드 `WorkScreen`): 머리 · 이어 읽기 · 권 줄들(합본 안의 권은 표지 뒤 글자 시작선에서).
struct WorkScreen: View {
    @ObservedObject var library: Library
    let workKey: String
    let open: (LibraryBook) -> Void
    let back: () -> Void
    /// 합쳐서 다른 작품이 됐다 — 그 작품 화면으로.
    var reopen: (String) -> Void = { _ in }
    @State private var arranging = false

    private var work: Work? { library.works.first { $0.key == workKey } }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let work {
                CpHeader(title: work.title, subtitle: work.subtitle,
                         leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {
                    CpIconButton(icon: .more, label: "작품 정리") { arranging = true }
                }
                // 배치는 창 폭 등급으로(구상안 48, RESPONSIVE_PLAN): 좁음 · 낮은 창(가로로 돌린 iPhone)은 줄 목록, 넓고 높으면 두 판
                // (왼쪽 작품 · 오른쪽 권 표지 격자), 중간은 위 작품 · 아래 격자. 줄 목록이 iPad 에 늘어나면 표지가 엄지손톱만 했다.
                GeometryReader { g in
                    let (w, h) = (Double(g.size.width), Double(g.size.height))
                    if IosAdaptive.shared.twoPane(width: w, height: h) {
                        twoPane(work)
                    } else if IosAdaptive.shared.roomy(width: w, height: h) {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                HStack(alignment: .bottom, spacing: 20) {
                                    ComicCover(unit: work.entries.first?.unit, library: library, rtl: work.rtl, workKey: work.key).frame(width: 110)
                                    info(work, fill: false)
                                }
                                .padding(.horizontal, 24).padding(.vertical, 8)
                                grid(work, width: g.size.width - 32).padding(.horizontal, 16)
                            }
                            .padding(.bottom, 24)
                        }
                    } else {
                        list(work)
                    }
                }
            } else {
                CpHeader(title: "만화", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
                Spacer()
            }
        }
        .background(CpColor.background)
        .overlay {
            if arranging, let work {
                WorkArrangeScreen(library: library, work: work, back: { arranging = false }, moved: { key in
                    arranging = false
                    reopen(key)
                })
                .transition(.move(edge: .trailing))
            }
        }
    }

    private var resume: (WorkEntry, ComicProgress?)? {
        guard let work, let r = ComicReading.shared.resume(work: work, progress: library.comicProgress), let e = r.first else { return nil }
        return (e, r.second)
    }

    /// 넓음: 왼쪽 320 작품 판(큰 표지 200 · 이름 · 진도 · 이어 읽기 — 넘치면 이 판만 굴린다), 가는 선, 오른쪽 권 격자.
    private func twoPane(_ work: Work) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ComicCover(unit: work.entries.first?.unit, library: library, rtl: work.rtl, workKey: work.key).frame(width: 200)
                    info(work, fill: true)
                }
                .padding(.leading, 24).padding(.trailing, 16).padding(.top, 8).padding(.bottom, 24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(width: IosAdaptive.shared.TWO_PANE_LEFT)
            Rectangle().fill(CpColor.outline).frame(width: 1)
            GeometryReader { g in
                ScrollView { grid(work, width: g.size.width - 32).padding(.horizontal, 16).padding(.bottom, 24) }
            }
        }
    }

    /// 이름 · "1권 다 읽음 · 2권 읽는 중" · 이어 읽기(안드로이드 `WorkWide.Info`).
    private func info(_ work: Work, fill: Bool) -> some View {
        let done = work.entries.filter { e in ([e.unit] + e.copies).contains { library.comicProgress[$0.id]?.finishedAtEpochMs != nil } }.count
        let status = [done > 0 ? "\(done)\(work.unitWord) 다 읽음" : nil, resume.map { "\($0.0.label) 읽는 중" }].compactMap { $0 }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 4) {
            Text(work.title).font(CpFont.title).foregroundStyle(CpColor.text).lineLimit(2)
            if !status.isEmpty { Text(status).font(CpFont.caption).foregroundStyle(CpColor.textMuted) }
            if let (e, p) = resume, let book = library.comicBook(e.unit) {
                CpButton(title: "\(e.label) 이어 읽기" + (p.map { " · \(Int($0.page) + 1)쪽" } ?? "")) { open(book) }
                    .frame(maxWidth: fill ? .infinity : nil).padding(.top, 12)
            }
        }
    }

    /// 권 표지 격자(칸 최소 120 · 사이 16). 합본 안의 권은 "4–6권 합본 안" 글로 속한 곳을 보인다 — 격자에서는 들여쓰기로 보일 수 없다.
    private func grid(_ work: Work, width: CGFloat) -> some View {
        let cols = Int(IosAdaptive.shared.columns(width: Double(width), gap: 16))
        return VStack(alignment: .leading, spacing: 12) {
            CpSectionLabel(text: "\(work.volumeCount)\(work.unitWord)").padding(.top, 8)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 16, alignment: .top), count: cols), alignment: .leading, spacing: 12) {
                ForEach(Array(work.entries.enumerated()), id: \.offset) { _, e in
                    cell(work, e, label: e.omnibus ? "\(e.label) 합본" : e.label, sub: sub(e), inside: nil, first: nil)
                    if let sections = e.unit.contents?.sections, sections.count > 1 {
                        ForEach(Array(sections.enumerated()), id: \.offset) { _, s in
                            let f = Int(s.firstPage), n = Int(s.pageCount)
                            cell(work, e, label: s.name.isEmpty ? "앞부분" : s.name, sub: n > 1 ? "\(f + 1)–\(f + n)쪽" : "\(f + 1)쪽",
                                 inside: "\(e.omnibus ? "\(e.label) 합본" : e.label) 안", first: f)
                        }
                    }
                }
            }
        }
    }

    private func cell(_ work: Work, _ e: WorkEntry, label: String, sub: String, inside: String?, first: Int?) -> some View {
        let p = first == nil ? library.comicProgress[e.unit.id] : nil
        return VStack(alignment: .leading, spacing: 3) {
            ComicCover(unit: e.unit, library: library, rtl: work.rtl)
            Text(label).font(CpFont.label).foregroundStyle(CpColor.text).lineLimit(1).padding(.top, 3)
            if let inside { Text(inside).font(CpFont.caption).foregroundStyle(CpColor.textMuted).lineLimit(1) }
            if let p, p.finishedAtEpochMs == nil { CpProgressBar(fraction: Double(p.fraction)) }
            if !sub.isEmpty {
                Text(sub).font(CpFont.caption).foregroundStyle(p?.finishedAtEpochMs != nil ? CpColor.accentText : CpColor.textMuted).lineLimit(1)
            }
            if first == nil && !e.copies.isEmpty {
                Text("같은 \(work.unitWord) \(e.copies.count + 1)곳").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            }
        }
        .workButton(label) { openEntry(e, first: first) }
    }

    /// 권을 연다. 합본 안의 권이면 그 권의 첫 쪽부터.
    private func openEntry(_ e: WorkEntry, first: Int?) {
        guard let b = library.comicBook(e.unit) else { return }
        if let first {
            library.store.saveComic(unitId: e.unit.id, page: Int32(first), pageCount: Int32(e.unit.pageCount?.intValue ?? first + 1), atEnd: false, half: nil, offset: nil)
        }
        open(b)
    }

    /// 좁음 · 낮은 창: 줄 목록(지금까지와 같다).
    private func list(_ work: Work) -> some View {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if let r = ComicReading.shared.resume(work: work, progress: library.comicProgress), let p = r.second,
                           let book = library.comicBook(r.first!.unit) {
                            CpButton(title: "\(r.first!.label) 이어 읽기 · \(Int(p.page) + 1)쪽") { open(book) }
                                .frame(maxWidth: .infinity)
                                .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 10)
                        }
                        CpSectionLabel(text: "\(work.volumeCount)\(work.unitWord)")
                            .padding(.horizontal, CpMetrics.gutter).padding(.top, 8).padding(.bottom, 4)
                        ForEach(Array(work.entries.enumerated()), id: \.offset) { _, e in
                            entryRow(work, e)
                            if let sections = e.unit.contents?.sections, sections.count > 1 {
                                ForEach(Array(sections.enumerated()), id: \.offset) { _, s in sectionRow(e, s) }
                            }
                        }
                        Spacer().frame(height: 24)
                    }
                }
    }

    /// "다 읽음 · 180쪽" · "45 / 182쪽" · "182쪽"(안드로이드 `entrySub`).
    private func sub(_ e: WorkEntry) -> String {
        let count = e.unit.pageCount?.intValue
        let p = ([e.unit] + e.copies).compactMap { library.comicProgress[$0.id] }.first
        if let p, p.finishedAtEpochMs != nil { return count.map { "다 읽음 · \($0)쪽" } ?? "다 읽음" }
        if let p, let count { return "\(Int(p.page) + 1) / \(count)쪽" }
        return count.map { "\($0)쪽" } ?? ""
    }

    private func entryRow(_ work: Work, _ e: WorkEntry) -> some View {
        let p = library.comicProgress[e.unit.id]
        return HStack(spacing: 14) {
            ComicCover(unit: e.unit, library: library, rtl: work.rtl).frame(width: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(e.omnibus ? "\(e.label) 합본" : e.label).font(CpFont.body).foregroundStyle(CpColor.text)
                Text(sub(e)).font(CpFont.caption).foregroundStyle(p?.finishedAtEpochMs != nil ? CpColor.accentText : CpColor.textMuted)
                if let p, p.finishedAtEpochMs == nil { CpProgressBar(fraction: Double(p.fraction)).frame(maxWidth: 200).padding(.top, 2) }
            }
            Spacer(minLength: 0)
            if !e.copies.isEmpty {
                Text("같은 \(work.unitWord) \(e.copies.count + 1)곳").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerChip).stroke(CpColor.outline, lineWidth: 1))
            }
        }
        .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 6)
        .workButton(e.label) { if let b = library.comicBook(e.unit) { open(b) } }
    }

    /// 합본 안의 권: 표지(40) 뒤 글자 시작선(14 + 40 = 54)에서 — 합본에 속한다(UI 규칙 1). 누르면 그 권의 첫 쪽부터.
    private func sectionRow(_ e: WorkEntry, _ s: ComicContents.Section) -> some View {
        let first = Int(s.firstPage), count = Int(s.pageCount)
        return HStack(spacing: 10) {
            Text(s.name.isEmpty ? "앞부분" : s.name).font(CpFont.body).foregroundStyle(CpColor.text)
            Text(count > 1 ? "\(first + 1)–\(first + count)쪽" : "\(first + 1)쪽").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            Spacer(minLength: 0)
        }
        .padding(.leading, CpMetrics.gutter + 54).padding(.trailing, CpMetrics.gutter)
        .frame(minHeight: 40)
        .workButton(s.name) { openEntry(e, first: first) }
    }
}

// MARK: 권 끝

/// 권 끝 판(구상안 36, 안드로이드 `EndCard`): "3권을 다 읽었습니다" · 다음 권 표지 · 이어서 읽기 · 서재로.
struct ComicEndCard: View {
    let label: String
    let workTitle: String
    let next: WorkEntry?
    let webtoon: Bool
    let library: Library
    let onNext: (WorkEntry) -> Void
    let toLibrary: () -> Void
    let dismiss: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.45).ignoresSafeArea().onTapGesture(perform: dismiss)
                .accessibilityLabel("닫기").accessibilityAddTraits(.isButton)
            VStack(spacing: 14) {
                Text("\(label)\(objectParticle(label)) 다 읽었습니다").font(CpFont.title).foregroundStyle(CpColor.text)
                    .multilineTextAlignment(.center)
                if let next {
                    ComicCover(unit: next.unit, library: library).frame(width: 150)
                    Text("다음: \(workTitle) \(next.label)").font(CpFont.body).foregroundStyle(CpColor.text).multilineTextAlignment(.center)
                    CpButton(title: "이어서 읽기") { onNext(next) }
                } else {
                    // 세는 말은 그 줄 이름의 끝 글자(화 · 권)가 먼저, 모르면 작품의 세는 말.
                    let unit = label.hasSuffix("화") ? "화" : label.hasSuffix("권") ? "권" : (webtoon ? "화" : "권")
                    Text("마지막 \(unit)입니다").font(CpFont.body).foregroundStyle(CpColor.textMuted)
                }
                Button(action: toLibrary) {
                    Text("서재로").font(CpFont.label).foregroundStyle(CpColor.accentText)
                        .padding(.horizontal, 16).frame(minHeight: CpMetrics.touchTarget)
                }
                .buttonStyle(.plain)
            }
            .padding(24)
            .background(CpColor.dialog).clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerDialog))
            .padding(.horizontal, 40)
        }
        .environment(\.colorScheme, .dark)
    }
}

// MARK: 쪽 목록 · 책갈피

/// 쪽 목록(안드로이드 `PageGrid`): 칸 최소 96, 쪽마다 작은 그림과 "n쪽". 누르면 그 쪽으로.
struct ComicPageGrid: View {
    let engine: ComicEngine
    let title: String
    let go: (Int, SpreadSplit.Half) -> Void
    let back: () -> Void
    @State private var info: ComicGridInfo?

    var body: some View {
        VStack(spacing: 0) {
            CpHeader(title: "쪽 목록", subtitle: "\(title) · \(engine.pageCount)쪽",
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            if let info {
                GeometryReader { g in
                    let columns = max(Int((g.size.width - CpMetrics.gutter * 2 + 10) / (96 + 10)), 1)
                    let cell = (g.size.width - CpMetrics.gutter * 2 - CGFloat(columns - 1) * 10) / CGFloat(columns)
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVStack(alignment: .leading, spacing: 14) {
                                ForEach(Array(rows(info, columns: columns).enumerated()), id: \.offset) { _, row in
                                    HStack(alignment: .top, spacing: 10) {
                                        ForEach(row, id: \.self) { p in
                                            if info.wide[p] {
                                                wideCell(p, info, width: g.size.width - CpMetrics.gutter * 2)
                                            } else {
                                                cellView(p, half: .whole, aspect: 0.7, label: "\(p + 1)쪽",
                                                         on: p == info.current, info: info).frame(width: cell)
                                            }
                                        }
                                    }
                                    .id(row.first ?? 0)
                                }
                            }
                            .padding(CpMetrics.gutter)
                        }
                        .onAppear {
                            let target = rows(info, columns: columns).first { $0.contains(info.current) }?.first ?? 0
                            proxy.scrollTo(target, anchor: .center)
                        }
                    }
                }
            } else {
                Spacer()
            }
        }
        .task { info = await engine.gridInfo() }
    }

    /// 줄 나누기: 펼침면은 한 줄을 혼자 쓴다(안드로이드 `maxLineSpan`).
    private func rows(_ info: ComicGridInfo, columns: Int) -> [[Int]] {
        var out: [[Int]] = []
        var row: [Int] = []
        for p in 0..<info.wide.count {
            if info.wide[p] {
                if !row.isEmpty { out.append(row); row = [] }
                out.append([p])
            } else {
                row.append(p)
                if row.count == columns { out.append(row); row = [] }
            }
        }
        if !row.isEmpty { out.append(row) }
        return out
    }

    /// 펼침면 칸: 나누는 동안은 두 반을 따로 — 화면에 놓인 차례(왼쪽 · 오른쪽)로, 오→왼 책이라도 그림의 왼쪽 반은 왼쪽에.
    @ViewBuilder
    private func wideCell(_ p: Int, _ info: ComicGridInfo, width: CGFloat) -> some View {
        if info.split {
            HStack(spacing: 4) {
                ForEach([SpreadSplit.Half.left, SpreadSplit.Half.right], id: \.self) { h in
                    let side = h == .left ? "왼쪽" : "오른쪽"
                    cellView(p, half: h, aspect: info.aspects[p] / 2, label: "\(p + 1)쪽 \(side)",
                             on: p == info.current && info.currentHalf == h, info: info)
                        .frame(width: (width - 4) / 2)
                }
            }
        } else {
            cellView(p, half: .whole, aspect: info.aspects[p], label: "\(p + 1)쪽", on: p == info.current, info: info).frame(width: width)
        }
    }

    private func cellView(_ p: Int, half: SpreadSplit.Half, aspect: CGFloat, label: String, on: Bool, info: ComicGridInfo) -> some View {
        Button { go(p, half) } label: {
            VStack(spacing: 4) {
                ComicThumb(engine: engine, page: p, half: half, aspect: aspect)
                    .overlay(RoundedRectangle(cornerRadius: 4).stroke(on ? CpColor.accent : CpColor.divider, lineWidth: on ? 3 : 1))
                Text(label).font(CpFont.caption).foregroundStyle(on ? CpColor.accentText : CpColor.textMuted)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label)으로")
    }
}

struct ComicThumb: View {
    let engine: ComicEngine
    let page: Int
    var half: SpreadSplit.Half = .whole
    var aspect: CGFloat = 0.7
    @State private var image: UIImage?

    var body: some View {
        Color.black.opacity(0.2)
            .aspectRatio(aspect, contentMode: .fit)
            .overlay { if let image { Image(uiImage: image).resizable().aspectRatio(contentMode: .fit) } }
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .task(id: page) { image = await engine.thumbnail(page, height: 160, half: half) }
    }
}

/// 만화 책갈피 목록(안드로이드 쪽 단위 — 발췌 글이 없다).
struct ComicBookmarks: View {
    let engine: ComicEngine
    let go: (Int) -> Void
    let back: () -> Void
    @State private var pages: [Int]?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "책갈피", subtitle: pages.map { "\($0.count)개" },
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            if let pages {
                if pages.isEmpty {
                    CpEmptyMessage(text: "책갈피가 없습니다. 쪽 오른쪽 위를 누르거나 가운데를 누르고 위쪽 책갈피 단추로 꽂을 수 있습니다.")
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(pages, id: \.self) { p in
                                Button { go(p) } label: {
                                    HStack(spacing: 14) {
                                        ComicThumb(engine: engine, page: p).frame(width: 40)
                                        Text("\(p + 1)쪽").font(CpFont.body).foregroundStyle(CpColor.text)
                                        Spacer()
                                    }
                                    .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 6).contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            } else {
                Spacer()
            }
        }
        .task { pages = ((try? await engine.bookmarks()) ?? []).compactMap(\.page) }
    }
}

/// 보기 판의 "넓은 그림 — 한 쪽 · 두 쪽"(구상안 41, 안드로이드 0.55.3 `CpWideRow`): 두 쪽이 담긴 넓은 그림을 한 쪽 보기에서 반씩 차례로
/// 볼지 · 함께 볼지. 단추는 보이는 모습의 이름이다 — 0.55.1 의 "두 쪽 · 한 장" 은 아래 그림 줄과 같은 말을 다른 뜻으로 써, "두 쪽" 을
/// 누르면 한 쪽씩 보였다. 고르지 않으면 자동(한 쪽)이 골라져 있고 그렇다고 말한다. 고르면 설명이 "자동으로 되돌리기" 가 된다.
/// 두 쪽 보기에서는 어느 쪽이든 두 쪽이 함께라 그림 줄은 단추가 실제로 바꾸는 한 쪽 보기 하나만.
struct WideRow: View {
    let chosen: Bool?
    let change: (Bool?) -> Void

    var body: some View {
        let L = IosComicLayout.shared
        let halves = L.wideInHalves(chosen: chosen.map { KotlinBoolean(bool: $0) })
        VStack(alignment: .leading, spacing: 0) {
            CpChoice(label: "넓은 그림", options: [L.WIDE_HALVES, L.WIDE_TOGETHER], selected: halves ? 0 : 1) { change($0 == 0) }
            if chosen == nil {
                CpCaption(text: L.WIDE_AUTO_NOTE)
            } else {
                Button { change(nil) } label: {
                    Text(L.WIDE_CHOSEN_NOTE).font(CpFont.caption).foregroundStyle(CpColor.accentText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, CpMetrics.gutter).padding(.bottom, 6)
                .accessibilityLabel("자동으로 되돌리기")
            }
            HStack(spacing: 0) {
                if halves {
                    HStack(spacing: 6) { sheet(30, false); Text("→").font(CpFont.caption).foregroundStyle(CpColor.textMuted); sheet(30, false) }
                } else {
                    sheet(60, true)
                }
                Text(halves ? L.WIDE_SKETCH_HALVES : L.WIDE_SKETCH_TOGETHER).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    .padding(.leading, 10).lineLimit(2)
            }
            .padding(.horizontal, CpMetrics.gutter).padding(.bottom, 8)
            .accessibilityElement(children: .combine)
        }
    }

    private func sheet(_ w: CGFloat, _ fold: Bool) -> some View {
        Rectangle().fill(CpColor.accentContainer).frame(width: w, height: 42)
            .overlay(Rectangle().stroke(CpColor.outline, lineWidth: 1))
            .overlay { if fold { Rectangle().fill(CpColor.text.opacity(0.5)).frame(width: 1) } }
    }
}
