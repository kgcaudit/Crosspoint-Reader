import OloCore
import SwiftUI
import UniformTypeIdentifiers

/// 독서노트 내보내기(구상안 22, 안드로이드 `CpNotesExport`): 담을 것 · 모양을 고르고 미리 본 뒤 파일로 저장하거나 공유한다.
/// 글 모양은 Kotlin `NotesExport` 가 만든다 — 두 앱이 같은 파일을 내야 한다(시험이 지킨다).
struct NotesExportScreen: View {
    @ObservedObject var model: ReaderModel
    /// 독서노트에서 보던 칩(0 전체 · 1 책갈피 · 2 형광펜 · 3 메모). 메모만 보고 있었으면 메모만 내보내려는 것이다.
    let initialFilter: Int
    let back: () -> Void

    @State private var items: [ExportItem] = []
    @State private var scope = 0
    /// 마크다운이 처음 값(안드로이드와 같다).
    @State private var markdown = true
    @State private var saving = false
    @State private var sharing: SharedText?

    private static let scopes = ["전체", "형광펜 · 메모", "메모만"]

    private var picked: [ExportItem] { items.filter { NotesExport.shared.takes(scope: Int32(scope), item: $0) } }

    private var text: String {
        if markdown {
            let f = DateFormatter()
            f.locale = Locale(identifier: "ko_KR")
            f.dateFormat = "yyyy년 M월 d일"
            return NotesExport.shared.markdown(title: model.title, author: model.author, items: picked, date: f.string(from: Date()))
        }
        return NotesExport.shared.text(title: model.title, author: model.author, items: picked)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "독서노트 내보내기", subtitle: model.title,
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    label("담을 것")
                    // 칠이 하나도 없는 갈래는 고를 수 없게 아예 보이지 않는다 — 고르면 빈 파일이 된다.
                    ForEach(availableScopes, id: \.self) { s in
                        CpRadioRow(title: Self.scopes[s], selected: s == scope, subtitle: detail(s)) { scope = s }
                    }
                    CpDivider()
                    label("모양")
                    CpRadioRow(title: "글", selected: !markdown, subtitle: "메모장 · 메신저 · 메일에 그대로") { markdown = false }
                    CpRadioRow(title: "마크다운", selected: markdown, subtitle: "옵시디언 · 노션에서 제목 · 인용이 살아 있음") { markdown = true }
                    CpDivider()
                    label("미리 보기")
                    Text(text).font(.system(size: 12)).lineSpacing(5).foregroundStyle(CpColor.text).lineLimit(12)
                        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                        .background(CpColor.surface)
                        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall))
                        .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall).stroke(CpColor.outline, lineWidth: 1))
                        .padding(.horizontal, CpMetrics.gutter)
                        .padding(.bottom, 8)
                }
            }
            HStack {
                Spacer()
                CpButton(title: "파일로 저장", kind: .secondary) { saving = true }
                CpButton(title: "공유") { sharing = SharedText(text: text) }
            }
            .padding(CpMetrics.gutter)
        }
        .task { await load() }
        .fileExporter(isPresented: $saving, document: PlainDocument(text: text),
                      contentType: markdown ? (UTType(filenameExtension: "md") ?? .plainText) : .plainText,
                      defaultFilename: NotesExport.shared.fileName(title: model.title, markdown: markdown)) { result in
            if case .success = result { model.say("파일로 저장했습니다") } else { model.say("저장하지 못했습니다. 다른 곳을 골라 보세요.") }
        }
        .sheet(item: $sharing) { ShareView(text: $0.text).presentationDetents([.medium, .large]) }
    }

    private var availableScopes: [Int] { [0, 1, 2].filter { s in s == 0 || items.contains { NotesExport.shared.takes(scope: Int32(s), item: $0) } } }

    /// "4개 · 책갈피 1 · 형광펜 2 · 메모 1" — 칩과 같은 갈래로 센다.
    private func detail(_ s: Int) -> String {
        let n = items.filter { NotesExport.shared.takes(scope: Int32(s), item: $0) }.count
        guard s == 0 else { return "\(n)개" }
        let marks = items.filter { $0.isBookmark }.count
        let memos = items.filter { $0.memo != nil }.count
        let parts = [marks > 0 ? "책갈피 \(marks)" : nil, items.count - marks - memos > 0 ? "형광펜 \(items.count - marks - memos)" : nil,
                     memos > 0 ? "메모 \(memos)" : nil].compactMap { $0 }
        return ([("\(n)개")] + parts).joined(separator: " · ")
    }

    private func label(_ t: String) -> some View {
        CpSectionLabel(text: t).padding(.horizontal, CpMetrics.gutter).padding(.top, 12).padding(.bottom, 2)
    }

    private func load() async {
        guard let engine = model.engine else { return }
        let marks = (try? await engine.bookmarks()) ?? []
        let notes = (try? await engine.noteRows()) ?? []
        let entries = (marks.map(NoteEntry.bookmark) + notes.map(NoteEntry.highlight)).sorted { $0.order < $1.order }
        items = entries.map { e in
            switch e {
            case .bookmark(let b): ExportItem(section: b.section, text: b.snippet, pen: nil, place: b.whereLabel, memo: nil)
            case .highlight(let h): ExportItem(section: h.section, text: h.snippet, pen: Pen.names[h.pen],
                                               place: noteWhere(h.percent, h.created, span: h.pageSpan), memo: h.note)
            }
        }
        // 보던 칩이 처음 값. 책갈피 칩에는 맞는 것이 없어 전체로.
        let start = [0, 0, 1, 2][min(max(initialFilter, 0), 3)]
        scope = availableScopes.contains(start) ? start : 0
    }
}

/// 내보낼 글 한 벌(파일 저장 창에 넘긴다).
struct PlainDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    let text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? ""
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}
