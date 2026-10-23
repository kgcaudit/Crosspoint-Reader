import OloCore
import SwiftUI

/// 작품 정리(구상안 40, 안드로이드 `WorkArrange`): 모은 곳(폴더마다 든 권 — 보는 줄)과 묶음 고치기 넷. 고친 것은 파일이 아니라 앱
/// 기록에 적는다 — 파일 이름 · 자리는 그대로이고, 다시 훑어도 남는다.
struct WorkArrangeScreen: View {
    @ObservedObject var library: Library
    let work: Work
    let back: () -> Void
    /// 합쳐서 이 작품이 다른 작품이 됐다 — 작품 화면이 그 작품으로 옮겨 간다.
    let moved: (String) -> Void

    @State private var picking: Pick?

    private enum Pick: Identifiable {
        case merge, split, rename, copies(WorkEntry)
        var id: String {
            switch self {
            case .merge: "merge"
            case .split: "split"
            case .rename: "rename"
            case .copies(let e): "copies:" + e.slot
            }
        }
    }

    private var word: String { IosComicArrange.shared.unitWord(work: work) }
    private var others: [Work] { IosComicArrange.shared.others(work: work, all: library.works) }

    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 0) {
                CpHeader(title: "작품 정리", subtitle: work.title, leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        section("모은 곳")
                        // 모은 곳은 고칠 것이 아니라 보는 것이라 누름 자리를 두지 않는다.
                        ForEach(work.places, id: \.self) { place in
                            HStack(spacing: 14) {
                                CpIconView(icon: .folder, size: 24, color: CpColor.textMuted)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(place.isEmpty ? "등록 폴더" : place).font(CpFont.body).foregroundStyle(CpColor.text).lineLimit(2)
                                    Text(IosComicArrange.shared.placeSummary(work: work, place: place)).font(CpFont.subtitle)
                                        .foregroundStyle(CpColor.textMuted).lineLimit(2)
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 8)
                            .frame(minHeight: CpMetrics.rowHeight)
                            .accessibilityElement(children: .combine)
                        }
                        section("묶음")
                        // 흐린 줄도 눌린다 — 합칠 작품이 없으면 빈 판을 띄우지 않을 뿐이다(안드로이드와 같다).
                        row(.plus, "다른 작품과 합치기", "이름이 달라 따로 보이는 같은 작품", enabled: !others.isEmpty) { picking = .merge }
                        // 한 줄뿐인 작품에서 빼면 빈 작품과 같은 작품이 하나 더 생길 뿐이다.
                        row(.minus, "이 작품에서 빼기", "잘못 묶인 \(word)\(objectParticle(word)) 따로", enabled: work.entries.count > 1) { picking = .split }
                        row(.note, "작품 이름 고치기", "파일 이름은 그대로", enabled: true) { picking = .rename }
                        ForEach(work.entries.filter { !$0.copies.isEmpty }, id: \.slot) { e in
                            let n = e.copies.count + 1
                            row(.bookmark, "같은 \(word) \(n)곳 — \(e.label)", "읽을 파일 고르기 · \(n == 2 ? "다른 하나는" : "나머지는") 숨김", enabled: true) {
                                picking = .copies(e)
                            }
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
            // 넓은 화면에서도 600 가운데(구상안 48) — 줄 이름과 설명이 화면 끝까지 늘지 않게.
            .frame(maxWidth: IosAdaptive.shared.CONTENT_MAX)
            .frame(maxWidth: .infinity)
            .background(CpColor.background)
            if let picking { popup(picking) }
        }
    }

    private func section(_ t: String) -> some View {
        CpSectionLabel(text: t).padding(.horizontal, CpMetrics.gutter).padding(.top, 18).padding(.bottom, 6)
    }

    private func row(_ icon: CpIcon, _ title: String, _ sub: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        CpListRow(icon: icon, tile: CpColor.tileOther, title: title, subtitle: sub, action: { if enabled { action() } })
            .opacity(enabled ? 1 : 0.45)
            .padding(.horizontal, CpMetrics.gutter)
    }

    @ViewBuilder
    private func popup(_ p: Pick) -> some View {
        switch p {
        case .merge:
            PickPopup(title: "‘\(work.title)’ — 어느 작품에 합칠까요?", message: "합친 뒤에는 고른 작품의 이름으로 보입니다.", close: { picking = nil }) {
                ForEach(others, id: \.key) { other in
                    pickRow(other.title, other.subtitle) {
                        picking = nil
                        library.mergeWork(work, into: other)
                        moved(other.key)
                    }
                }
            }
        case .split:
            PickPopup(title: "어느 \(word)\(objectParticle(word)) 뺄까요?", message: "뺀 \(word)\(work.webtoon ? "는" : "은") 제 이름으로 따로 보입니다.", close: { picking = nil }) {
                ForEach(work.entries, id: \.slot) { e in
                    pickRow(e.label, e.unit.name) {
                        picking = nil
                        library.splitEntry(e)
                    }
                }
            }
        case .rename:
            RenamePopup(current: work.title, close: { picking = nil }) { title in
                picking = nil
                library.renameWork(work, title: title)
            }
        case .copies(let e):
            let all = IosComicArrange.shared.copies(entry: e)
            PickPopup(title: "같은 \(word) \(all.count)곳 — \(e.label)", message: "고른 파일로 읽습니다. 나머지는 목록에서 숨깁니다(파일은 지우지 않습니다).",
                      close: { picking = nil }) {
                ForEach(all, id: \.id) { u in
                    CpRadioRow(title: u.name, selected: u.id == e.unit.id,
                               subtitle: [u.place.isEmpty ? nil : u.place, u.sizeBytes.map { ByteCountFormatter.string(fromByteCount: $0.int64Value, countStyle: .file) }]
                                   .compactMap { $0 }.joined(separator: " · "),
                               inset: 0) {
                        picking = nil
                        library.preferCopy(e, unitId: u.id)
                    }
                }
            }
        }
    }

    private func pickRow(_ title: String, _ sub: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(CpFont.body).foregroundStyle(CpColor.text)
                Text(sub).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 고를 것이 여럿인 판. 길면 판 안에서 넘긴다 — 작품이 백 개면 판이 화면을 넘어 닫기 단추가 사라진다(안드로이드와 같다).
private struct PickPopup<Content: View>: View {
    let title: String
    let message: String
    let close: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        CpPopup(onDismiss: close) {
            Text(title).font(CpFont.title).foregroundStyle(CpColor.text).fixedSize(horizontal: false, vertical: true)
            Text(message).font(CpFont.body).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
            ScrollView { VStack(spacing: 0) { content } }.frame(maxHeight: 360).fixedSize(horizontal: false, vertical: true)
            HStack { Spacer(); CpButton(title: "닫기", kind: .secondary, action: close) }
        }
    }
}

private struct RenamePopup: View {
    let current: String
    let close: () -> Void
    let save: (String) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        CpPopup(onDismiss: close) {
            Text("작품 이름 고치기").font(CpFont.title).foregroundStyle(CpColor.text)
            Text("비우고 저장하면 원래 이름으로 돌아갑니다.").font(CpFont.body).foregroundStyle(CpColor.textMuted)
            TextField("", text: $text)
                .font(CpFont.body).foregroundStyle(CpColor.text).tint(CpColor.accent)
                .focused($focused)
                .submitLabel(.done)
                .onSubmit { save(text) }
                .padding(14)
                .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(CpColor.accent, lineWidth: 2))
                .accessibilityLabel("작품 이름 입력")
            HStack {
                Spacer()
                CpButton(title: "취소", kind: .secondary, action: close)
                CpButton(title: "저장") { save(text) }
            }
        }
        .onAppear { text = current; focused = true }
    }
}

/// 만화 탭에서 작품을 길게 눌렀다(구상안 40 ③ · 44 ③, 안드로이드 `WorkMenu`): 작품 화면 · 갈래 옮기기 · 표지 고르기 셋.
/// '보던 장면에서' 는 쪽 넘김이면 쪽을, 웹툰이면 틀을 고른다(44 ④ · ⑤).
struct WorkMenu: View {
    @ObservedObject var library: Library
    let workKey: String
    let openWork: () -> Void
    let pick: (CoverSource) -> Void
    let scene: () -> Void
    let close: () -> Void
    let done: (String) -> Void
    @ObservedObject private var picked = CoverStore.shared

    var body: some View {
        if let work = library.works.first(where: { $0.key == workKey }) { menu(work) }
    }

    private func menu(_ work: Work) -> some View {
        let shelf = library.status(work).shelf
        return CpPopup(onDismiss: close) {
            Text(work.title).font(CpFont.title).foregroundStyle(CpColor.text)
            Text(work.subtitle).font(CpFont.body).foregroundStyle(CpColor.textMuted)
            VStack(spacing: 0) {
                CpListRow(icon: .book, tile: CpColor.tileOther, title: "작품 화면 열기", action: openWork)
                if shelf == .finished {
                    CpListRow(icon: .book, tile: CpColor.tileOther, title: "읽는 책으로 옮기기") {
                        move(work, ShelfMarkReading(atEpochMs: now), "읽는 책으로 옮겼습니다")
                    }
                } else {
                    CpListRow(icon: .bookmark, tile: CpColor.tileOther, title: "읽은 책으로 옮기기") {
                        move(work, ShelfMarkFinished(atEpochMs: now, volumes: Int32(work.volumeCount)), "읽은 책으로 옮겼습니다")
                    }
                }
                if shelf != .toRead {
                    CpListRow(icon: .back, tile: CpColor.tileOther, title: "읽을 책으로 옮기기") {
                        move(work, ShelfMarkToRead(atEpochMs: now), "읽을 책으로 옮겼습니다")
                    }
                }
                CpListRow(icon: .view, tile: CpColor.tileOther, title: IosCovers.shared.SCENE_ROW, action: scene)
                CoverPickRow(pick: pick)
                let id = IosCovers.shared.workId(workKey: work.key)
                CoverRevertRow(title: IosCovers.shared.WORK_REVERT_ROW, enabled: picked.has(id), revert: {
                    CoverStore.shared.clear(id)
                    done(IosCovers.shared.reverted(label: work.title))
                }, close: close)
            }
            HStack { Spacer(); CpButton(title: "닫기", kind: .secondary, action: close) }
        }
    }

    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// 옮긴 때를 함께 적는다 — 그 뒤에 다시 펼치거나 새 권이 들어오면 집계가 표시를 이긴다(공통 `WorkStatuses`).
    private func move(_ work: Work, _ mark: ShelfMark, _ message: String) {
        library.setComicSetting("SHELF", work: work, value: ShelfMarkCompanion.shared.format(mark: mark))
        done(message)
    }
}
