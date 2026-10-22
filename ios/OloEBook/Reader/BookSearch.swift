import SwiftUI

/// 찾은 곳 한 줄(Kotlin `SearchRow`).
struct SearchItem: Identifiable, Hashable {
    let id: Int
    let spine: Int
    let start: Int
    let end: Int
    let context: String
    let matchStart: Int
    let percent: Double
    let section: String

    /// 앞뒤 문맥 중 찾은 말만 강조색 25% · 굵게(안드로이드 `HitRow`).
    var marked: AttributedString {
        let chars = Array(context)
        let from = min(max(matchStart, 0), chars.count)
        let to = min(from + (end - start), chars.count)
        var match = AttributedString(String(chars[from..<to]))
        match.backgroundColor = CpColor.accent.opacity(0.25)
        match.font = .system(size: 16, weight: .bold)
        return AttributedString(String(chars[..<from])) + match + AttributedString(String(chars[to...]))
    }
}

/// 한 권에서의 찾기 상태(안드로이드 `ReaderSearch`). 장을 차례로 찾아 찾는 대로 [results] 에 쌓는다. 찾기 화면 · 결과 막대가
/// 닫혀도 남는다 — "목록" 을 누르면 같은 결과로 돌아온다.
@MainActor
final class BookSearch: ObservableObject {
    @Published var query = ""
    @Published private(set) var results: [SearchItem] = []
    @Published private(set) var searched = 0
    @Published private(set) var units = 0
    @Published private(set) var running = false
    /// 찾는 단위: 책은 장, PDF 는 쪽(구상안 34A).
    @Published private(set) var unit = "장"
    /// 지금 보고 있는 결과. -1 이면 결과 막대를 띄우지 않는다.
    @Published var current = -1

    private var task: Task<Void, Never>?

    /// 장마다 찾아 나오는 대로 더한다. 새로 찾으면 앞의 찾기는 멈춘다.
    func start(_ engine: any BookEngine) {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        task?.cancel()
        results = []
        current = -1
        searched = 0
        unit = engine.isPdf ? "쪽" : "장"
        if q.isEmpty { running = false; return }
        running = true
        task = Task { [weak self] in
            let count = (try? await engine.chapterCount()) ?? 0
            guard let self, !Task.isCancelled else { return }
            self.units = count
            for i in 0..<count {
                let found = (try? await engine.searchChapter(i, q, firstId: self.results.count)) ?? []
                // 새 찾기가 시작됐으면 옛 결과를 더하지 않는다 — 더하면 새 말의 목록에 옛 말의 결과가 섞인다.
                if Task.isCancelled { return }
                if !found.isEmpty { self.results += found }
                self.searched = i + 1
            }
            self.running = false
        }
    }

    /// 찾기를 지운다(칸의 ×). 결과 · 요약 · 결과 막대를 모두 비운다 — 멈추기만 하면 칸은 비었는데 옛 결과가 남아, 지운 말의
    /// 결과처럼 보인다(안드로이드에서 실제로 났던 일).
    func stop() {
        task?.cancel()
        running = false
        results = []
        searched = 0
        units = 0
        current = -1
    }

    /// 찾기 화면 위의 한 줄.
    var summary: String {
        if running { return "찾는 중… \(searched) / \(units)\(unit) · 지금까지 \(results.count)곳" }
        if searched > 0 && results.isEmpty { return "찾지 못했습니다" }
        if searched > 0 { return "\(results.count)곳 · \(Set(results.map(\.spine)).count)\(unit)에서" }
        return ""
    }

    /// 찾는 동안의 진행(0~1). 찾지 않을 때는 nil — 막대를 숨긴다.
    var progress: Double? { running && units > 0 ? Double(searched) / Double(units) : nil }
}

/// 찾기 입력칸(안드로이드 `CpSearchField`): 뒤로 · 강조색 2 테두리 칸(돋보기 · 입력 · 지우기), 모서리 14. 서재 책 찾기도 이 칸을 쓴다.
struct CpSearchField: View {
    @Binding var query: String
    var placeholder = "책에서 찾기"
    let onBack: () -> Void
    let onClear: () -> Void
    let onSearch: () -> Void
    @FocusState private var focused: Bool
    var focusFirst = true

    var body: some View {
        HStack(spacing: 0) {
            CpIconButton(icon: .back, label: "뒤로", action: onBack)
            HStack(spacing: 10) {
                CpIconView(icon: .search, size: 20, color: CpColor.textMuted)
                TextField(placeholder, text: $query)
                    .font(CpFont.body).foregroundStyle(CpColor.text)
                    .submitLabel(.search)
                    .focused($focused)
                    .onSubmit(onSearch)
                    .accessibilityLabel("찾을 말")
                if !query.isEmpty {
                    CpIconButton(icon: .close, label: "지우기", color: CpColor.textMuted, action: onClear)
                }
            }
            .padding(.leading, 14)
            .frame(minHeight: 48)
            .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(CpColor.accent, lineWidth: 2))
            Spacer().frame(width: 12)
        }
        .padding(.horizontal, 4).padding(.vertical, 8)
        // 결과가 없을 때만 자판을 바로 올린다 — "목록" 으로 돌아왔을 때 자판이 결과를 가리면 안 된다.
        .onAppear { if focusFirst { focused = true } }
    }
}

/// 찾기 화면(구상안 16A, 안드로이드 `CpSearchScreen`): 입력칸 · 요약 · 진행 · 장 갈래로 묶은 결과.
struct SearchScreen: View {
    @ObservedObject var search: BookSearch
    let onSearch: () -> Void
    let onOpen: (Int) -> Void
    let onBack: () -> Void
    let onCleared: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpSearchField(query: $search.query, onBack: onBack,
                          onClear: { search.query = ""; search.stop(); onCleared() }, onSearch: onSearch,
                          focusFirst: search.results.isEmpty)
            if !search.summary.isEmpty {
                Text(search.summary).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 6)
            }
            if let progress = search.progress {
                CpProgressBar(fraction: progress, thickness: 4).padding(.horizontal, CpMetrics.gutter)
            }
            CpDivider().padding(.top, 8)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(search.results) { item in
                        if item.id == 0 || search.results[item.id - 1].section != item.section {
                            CpSectionLabel(text: item.section)
                                .padding(.horizontal, CpMetrics.gutter).padding(.top, 18).padding(.bottom, 6)
                        }
                        Button { onOpen(item.id) } label: {
                            // 위계 규칙: 장 이름(글자만) 아래 한 단(16) 안쪽.
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.marked).font(CpFont.body).foregroundStyle(CpColor.text).lineSpacing(5).lineLimit(2)
                                    .multilineTextAlignment(.leading)
                                // PDF 는 결과 줄 끝에 쪽(구상안 34A) — 쪽을 펴 볼 사람에게 %는 뜻이 없다.
                                Text(search.unit == "쪽" ? "\(item.spine + 1)쪽" : "\(Int(item.percent.rounded()))%")
                                    .font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                            }
                            .padding(.leading, CpMetrics.gutter + CpMetrics.levelIndent).padding(.trailing, CpMetrics.gutter)
                            .padding(.vertical, 10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.bottom, 24)
            }
        }
    }
}

/// 결과를 보고 있는 동안 아래에 뜨는 막대(구상안 17, 안드로이드 `CpSearchResultBar`): 앞 · n / 전체 · 다음 | 목록 · 끝내기.
struct SearchResultBar: View {
    let index: Int
    let total: Int
    let onPrevious: () -> Void
    let onNext: () -> Void
    let onList: () -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            CpIconButton(icon: .back, label: "앞 결과", color: index > 0 ? CpColor.text : CpColor.outline, action: onPrevious)
            Text("\(index + 1) / \(total)").font(CpFont.label).foregroundStyle(CpColor.text).padding(.horizontal, 8)
            CpIconButton(icon: .forward, label: "다음 결과", color: index < total - 1 ? CpColor.text : CpColor.outline, action: onNext)
            Rectangle().fill(CpColor.divider).frame(width: 1, height: 24)
            Button(action: onList) {
                Text("목록").font(CpFont.label).foregroundStyle(CpColor.accentText)
                    .padding(.horizontal, 14).frame(minHeight: CpMetrics.touchTarget).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            CpIconButton(icon: .close, label: "찾기 끝내기", action: onClose)
        }
        .padding(.horizontal, 6)
        .background(CpColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium))
        .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(CpColor.divider, lineWidth: 1))
        .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
    }
}
