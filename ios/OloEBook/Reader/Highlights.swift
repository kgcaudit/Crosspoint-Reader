import OloCore
import SwiftUI
import UIKit

// 형광펜 · 메모(구상안 19A · 20, 안드로이드 CpSelection · CpReadingNotes 의 짝). 무엇을 골랐나(글자 자리)는 엔진이 셈하고,
// 여기는 그리기와 판만 맡는다 — 쪽 그림 위에 겹쳐 그리므로 고르는 동안 쪽을 다시 그리지 않는다.

/// 펜 4색(안드로이드 `Pen`): 칠 바탕 · 밝은 지면의 메모 쪽지(진하게).
enum Pen {
    static let names = ["노랑", "초록", "파랑", "분홍"]
    static let base: [UInt32] = [0xF2C94C, 0x6FCF97, 0x56B4F2, 0xF28DB2]
    static func color(_ i: Int) -> Color { Color(CpColor.uiColor(base[min(max(i, 0), 3)])) }
    /// 칠하는 색. 어두운 지면에서는 더 옅게 — 밝은 글자가 칠에 묻히지 않게.
    static func fill(_ i: Int, dark: Bool) -> Color { color(i).opacity(dark ? 0.38 : 0.45) }
    /// 떠 있는 알약 바탕(안드로이드 CpPill.opaque).
    static let pill = Color(CpColor.uiColor(0x302A24))
}

struct TextSelection: Equatable {
    let start: Int
    let end: Int
    let boxes: [CGRect]
}

/// 형광펜 한 줄(Kotlin `NoteRow`).
struct HighlightItem: Identifiable, Hashable {
    let id: Int64
    let spine: Int
    let start: Int
    let end: Int
    let pen: Int
    let snippet: String
    let note: String?
    let percent: Double
    let section: String
    let created: Date

    init(_ r: NoteRow) {
        id = r.id
        spine = Int(r.spine)
        start = Int(r.start)
        end = Int(r.end)
        pen = Int(r.color)
        snippet = r.snippet
        note = r.note
        percent = Double(r.percent)
        section = r.section
        created = Date(timeIntervalSince1970: Double(r.createdAtEpochMs) / 1000)
    }
}

struct TappedHighlight: Equatable {
    let item: HighlightItem
    let boxes: [CGRect]
}

struct MemoDraft: Identifiable {
    let id = UUID()
    /// 이미 칠한 곳이면 그 번호, 새로 고른 말이면 nil(저장할 때 칠한다).
    let noteId: Int64?
    let start: Int
    let end: Int
    let quote: String
    let pen: Int
    let text: String
}

struct SharedText: Identifiable {
    let id = UUID()
    let text: String
}

/// 공유하는 글: 인용 + (메모) + 책 제목. 받는 쪽에서 어느 책의 글인지 알 수 있게(안드로이드 `shareText`).
func shareText(quote: String, memo: String?, title: String) -> String {
    var s = "“\(quote)”"
    if let memo, !memo.isEmpty { s += "\n\n\(memo)" }
    return s + "\n\n— \(title)"
}

/// 네 동그라미. [current] 에 고리. 동그라미는 26 이어도 누르는 자리는 48 — 손가락이 옆 색을 누르지 않게.
struct PenDots: View {
    var current: Int?
    var ring: Color = .white
    var dot: CGFloat = 26
    let pick: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<4, id: \.self) { i in
                let on = i == current
                Button { pick(i) } label: {
                    Circle().fill(Pen.color(i)).frame(width: dot, height: dot)
                        .padding(on ? 4 : 1)
                        .overlay { if on { Circle().stroke(ring, lineWidth: 2) } }
                        .frame(width: max(dot + 18, CpMetrics.touchTarget), height: max(dot + 18, CpMetrics.touchTarget))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(Pen.names[i]) 형광펜")
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
    }
}

/// 고른 말 · 칠한 곳 위에 뜨는 어두운 알약(안드로이드 `CpFloatingMenu`): 4색 / 줄 / 낱말. 구간 위에 자리가 있으면 위, 없으면
/// 아래(손잡이 밑) — 손가락과 손잡이를 가리지 않게.
struct FloatingMenu: View {
    let anchor: CGRect
    let current: Int?
    let words: [String]
    let onPen: (Int) -> Void
    let onWord: (String) -> Void
    @State private var size: CGSize = .zero

    var body: some View {
        GeometryReader { g in
            let margin: CGFloat = 8
            let above = anchor.minY - 12 - size.height
            let y = above >= margin + g.safeAreaInsets.top ? above : min(anchor.maxY + 30, g.size.height - size.height - margin)
            let x = min(max(anchor.midX - size.width / 2, margin), max(g.size.width - size.width - margin, margin))
            pill
                .background(GeometryReader { p in Color.clear.onAppear { size = p.size }.onChange(of: p.size) { _, s in size = s } })
                .offset(x: x, y: y)
                .opacity(size == .zero ? 0 : 1)
        }
    }

    private var pill: some View {
        VStack(spacing: 0) {
            PenDots(current: current, pick: onPen).padding(.top, 2)
            Rectangle().fill(Color.white.opacity(0.2)).frame(height: 1)
            HStack(spacing: 0) {
                ForEach(words, id: \.self) { word in
                    Button { onWord(word) } label: {
                        Text(word).font(CpFont.label)
                            .foregroundStyle(word == "지우기" ? Color(CpColor.uiColor(0xFFB0A0)) : .white)
                            .padding(.horizontal, 12).frame(minHeight: CpMetrics.touchTarget).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 6).padding(.vertical, 4)
        .background(Pen.pill)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .shadow(color: .black.opacity(0.3), radius: 8, y: 3)
        .fixedSize()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("고른 글 메뉴")
    }
}

/// 고른 구간: 칠(강조색 28%) · 손잡이 둘 · 메뉴. 손잡이 끌기는 이 층이 받는다 — 손잡이 자체에 제스처를 달면 끄는 동안 손잡이가
/// 새 자리로 옮겨 가며 손가락과의 거리가 매번 어긋난다(안드로이드와 같은 까닭). 손잡이 밖을 누르면 고르기를 푼다.
struct SelectionLayer: View {
    @ObservedObject var model: ReaderModel
    let title: String
    @State private var dragging: Bool?
    /// 끄는 동안 손가락이 손잡이를 가리지 않게, 잡은 자리와 손잡이 가운데의 차이를 두고 따라간다.
    @State private var grab: CGSize = .zero

    private let r: CGFloat = 9

    var body: some View {
        ZStack(alignment: .topLeading) {
            if let sel = model.selection {
                ForEach(Array(sel.boxes.enumerated()), id: \.offset) { _, b in
                    Rectangle().fill(CpColor.accent.opacity(0.28)).frame(width: b.width, height: b.height).offset(x: b.minX, y: b.minY)
                }
                .allowsHitTesting(false)
                handles(sel)
                Color.clear.contentShape(Rectangle()).gesture(drag(sel))
                FloatingMenu(anchor: bounds(sel.boxes), current: nil, words: ["메모", "복사", "공유", "사전"],
                             onPen: { p in Task { await model.paint(pen: p) } }, onWord: { w in Task { await word(w) } })
            } else if let t = model.tapped {
                Color.clear.contentShape(Rectangle()).onTapGesture { model.clearSelection() }
                FloatingMenu(anchor: bounds(t.boxes), current: t.item.pen, words: ["메모", "복사", "공유", "지우기"],
                             onPen: { p in Task { await model.recolor(t.item, pen: p) } }, onWord: { w in Task { await word(w) } })
            }
        }
    }

    private func centres(_ sel: TextSelection) -> (CGPoint, CGPoint) {
        let first = sel.boxes.first!, last = sel.boxes.last!
        return (CGPoint(x: first.minX - r * 0.7, y: first.maxY + r), CGPoint(x: last.maxX + r * 0.7, y: last.maxY + r))
    }

    @ViewBuilder
    private func handles(_ sel: TextSelection) -> some View {
        let (a, b) = centres(sel)
        let first = sel.boxes.first!, last = sel.boxes.last!
        Path { p in
            p.move(to: CGPoint(x: first.minX, y: first.minY)); p.addLine(to: CGPoint(x: first.minX, y: first.maxY + 2))
            p.move(to: CGPoint(x: last.maxX, y: last.minY)); p.addLine(to: CGPoint(x: last.maxX, y: last.maxY + 2))
        }
        .stroke(CpColor.accent, lineWidth: 2)
        .allowsHitTesting(false)
        Circle().fill(CpColor.accent).frame(width: r * 2, height: r * 2).offset(x: a.x - r, y: a.y - r)
            .allowsHitTesting(false).accessibilityLabel("고르기 시작 손잡이")
        Circle().fill(CpColor.accent).frame(width: r * 2, height: r * 2).offset(x: b.x - r, y: b.y - r)
            .allowsHitTesting(false).accessibilityLabel("고르기 끝 손잡이")
    }

    private func drag(_ sel: TextSelection) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { v in
                if dragging == nil {
                    let (a, b) = centres(sel)
                    let da = hypot(v.startLocation.x - a.x, v.startLocation.y - a.y)
                    let db = hypot(v.startLocation.x - b.x, v.startLocation.y - b.y)
                    // 손잡이에서 30 안쪽을 잡았을 때만 끈다. 아니면 누름(고르기 풀기)으로 본다.
                    guard min(da, db) < 30 else { return }
                    let isStart = da < db
                    dragging = isStart
                    let c = isStart ? a : b
                    // 손잡이는 줄 아래에 달려 있다 — 끄는 자리는 줄 가운데로 올려 잰다.
                    let line = isStart ? sel.boxes.first! : sel.boxes.last!
                    grab = CGSize(width: c.x - v.startLocation.x, height: (line.midY - c.y) + (c.y - v.startLocation.y))
                }
                guard let isStart = dragging else { return }
                let at = CGPoint(x: v.location.x + grab.width, y: v.location.y + grab.height)
                Task { await model.dragHandle(start: isStart, to: at) }
            }
            .onEnded { v in
                if dragging == nil, abs(v.translation.width) < 10, abs(v.translation.height) < 10 { model.clearSelection() }
                dragging = nil
            }
    }

    private func bounds(_ boxes: [CGRect]) -> CGRect { boxes.dropFirst().reduce(boxes.first ?? .zero) { $0.union($1) } }

    private func word(_ w: String) async {
        let quote = await model.quote()
        switch w {
        case "메모": await model.startMemo()
        case "복사":
            UIPasteboard.general.string = quote
            model.clearSelection()
            model.say("복사했습니다")
        case "공유":
            model.shareOut = SharedText(text: shareText(quote: quote, memo: model.tapped?.item.note, title: title))
            model.clearSelection()
        case "사전":
            model.lookUp = SharedText(text: quote)
            model.clearSelection()
        case "지우기":
            if let t = model.tapped { await model.removeHighlight(t.item) }
        default: break
        }
    }
}

/// 메모 판(구상안 20, 안드로이드 `CpMemoSheet`): 아래에서 올라온다. 칠한 글 인용(색 막대) · 입력 칸 · 색 · 취소/저장. 입력 칸은 남는
/// 높이만 쓴다(최대 120) — 높이를 못 박으면 자판이 올라올 때 저장 단추가 화면 밖으로 밀렸다(안드로이드에서 실제로 났던 일).
struct MemoSheet: View {
    let draft: MemoDraft
    let bottom: CGFloat
    let save: (String, Int) -> Void
    let cancel: () -> Void
    @State private var text = ""
    @State private var pen = 0
    @FocusState private var focused: Bool

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.32).ignoresSafeArea().onTapGesture(perform: cancel)
                .accessibilityLabel("닫기").accessibilityAddTraits(.isButton)
            VStack(alignment: .leading, spacing: 0) {
                Capsule().fill(CpColor.divider).frame(width: 36, height: 4).frame(maxWidth: .infinity)
                HStack {
                    Text("메모").font(CpFont.title).foregroundStyle(CpColor.text)
                    Spacer()
                    PenDots(current: pen, ring: CpColor.text, dot: 24) { pen = $0 }
                }
                .padding(.top, 8).padding(.bottom, 4)
                HStack(spacing: 12) {
                    Capsule().fill(Pen.color(pen)).frame(width: 4, height: 44)
                    Text(draft.quote).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).lineLimit(2)
                    Spacer(minLength: 0)
                }
                ZStack(alignment: .topLeading) {
                    if text.isEmpty {
                        Text("생각을 적어 두세요").font(CpFont.body).foregroundStyle(CpColor.textMuted).padding(.top, 8).padding(.leading, 5)
                    }
                    TextEditor(text: $text).font(CpFont.body).foregroundStyle(CpColor.text)
                        .scrollContentBackground(.hidden).focused($focused)
                        .accessibilityLabel("메모 입력")
                }
                .frame(minHeight: 60, maxHeight: 120)
                .padding(10)
                .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(CpColor.accent, lineWidth: 2))
                .padding(.top, 14)
                HStack {
                    Spacer()
                    CpButton(title: "취소", kind: .secondary, action: cancel)
                    CpButton(title: "저장") { save(text, pen) }
                }
                .padding(.top, 16)
            }
            .padding(.horizontal, 24).padding(.top, 10).padding(.bottom, 24 + bottom)
            .background(CpColor.surface)
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: CpMetrics.cornerSheet, topTrailingRadius: CpMetrics.cornerSheet))
        }
        .onAppear {
            text = draft.text
            pen = draft.pen
            focused = true
        }
    }
}

/// iOS 사전 창(안드로이드는 휴대폰의 사전 · 번역 앱에 넘긴다). 사전 데이터를 앱에 넣지 않는다 — 책보다 크다.
struct DictionaryView: UIViewControllerRepresentable {
    let term: String
    func makeUIViewController(context: Context) -> UIReferenceLibraryViewController { UIReferenceLibraryViewController(term: term) }
    func updateUIViewController(_ controller: UIReferenceLibraryViewController, context: Context) {}
}

/// iOS 공유 창.
struct ShareView: UIViewControllerRepresentable {
    let text: String
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
