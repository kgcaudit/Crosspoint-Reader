import OloCore
import SwiftUI

/// 보던 장면에서 표지 고르기 — 쪽 넘김(구상안 44 ④, 안드로이드 `PagePickBar`). 읽기는 그대로라 넘기며 쪽을 고른다. 위에 "표지로 쓸 쪽"
/// 머리, 아래에 "이 쪽을 표지로". 통째로 보이는 넓은 쪽이면 "왼쪽 · 오른쪽 · 통째로" 를 더하고, 고르지 않은 반을 어둡게 덮는다.
///
/// 표지는 여백 자르기 · 색 보정 전의 그림이다 — 보이는 그대로 자르면 흑백으로 보던 사람의 표지가 흑백으로 남는다.
struct CoverPickLayer: View {
    let engine: ComicEngine
    /// 판이 바뀔 때마다 다르다(쪽 · 반) — 이 값으로 지금 보이는 조각을 다시 묻는다.
    let frameKey: String
    let rightToLeft: Bool
    let insets: EdgeInsets
    let back: () -> Void
    /// 고른 그림을 저장한다. 못 쓰면 false — 머리 글이 "이 쪽을 표지로 쓸 수 없습니다" 가 된다.
    let save: (UIImage) -> Bool

    private struct Shown: Equatable {
        let page: Int
        let half: SpreadSplit.Half
        let wide: Bool
        let rect: CGRect?
    }

    @State private var shown: Shown?
    @State private var choice = 2
    @State private var saving = false
    @State private var failed = false

    private var L: IosCovers { IosCovers.shared }

    var body: some View {
        ZStack {
            if let s = shown, let r = s.rect {
                mark(s, r).allowsHitTesting(false)
            }
            VStack(spacing: 0) {
                CpHeader(title: L.PICK_TITLE, subtitle: subtitle, leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
                    .padding(.top, insets.top).padding(.leading, insets.leading).padding(.trailing, insets.trailing)
                    .background(CpColor.surface)
                Spacer(minLength: 0).allowsHitTesting(false)
                VStack(spacing: 0) {
                    if let s = shown, s.wide, s.half == .whole {
                        CpChoice(label: "표지로 쓸 곳", options: L.PICK_HALVES, selected: choice) { choice = $0 }
                    }
                    CpButton(title: saving ? L.SAVING : L.PICK_BUTTON) { pick() }
                        .frame(maxWidth: .infinity).padding(.horizontal, CpMetrics.gutter).padding(.vertical, 10)
                }
                .padding(.bottom, insets.bottom).padding(.leading, insets.leading).padding(.trailing, insets.trailing)
                .background(CpColor.surface)
            }
        }
        .environment(\.colorScheme, comicPaper.dark ? .dark : .light)
        .task(id: frameKey) {
            let p = await engine.shownPart()
            // 새 쪽이면 앞(표지) 쪽 반부터 — 앞 쪽에서 고른 "통째로" 가 남으면 펼침면마다 다시 골라야 한다.
            if p.page != shown?.page { choice = Int(L.indexOf(half: L.frontHalf(rightToLeft: rightToLeft))) }
            shown = Shown(page: p.page, half: p.half, wide: p.wide, rect: p.rect)
            failed = false
        }
    }

    private var subtitle: String {
        guard let s = shown else { return " " }
        return L.pickSubtitle(page: Int32(s.page), half: s.half, wide: s.wide, failed: failed)
    }

    /// 고를 곳 표시: 고르지 않은 반은 60% 어둡게, 고른 곳은 강조 테(안드로이드 `drawCoverMark`). 나눠 보는 반이나 세로 쪽은 쪽 전체에 테.
    private func mark(_ s: Shown, _ r: CGRect) -> some View {
        let half = L.savedHalf(shown: s.half, wide: s.wide, chosen: L.halfAt(index: Int32(choice)))
        let picked: CGRect = switch (s.half == .whole && s.wide, half) {
        case (true, .left): CGRect(x: r.minX, y: r.minY, width: r.width / 2, height: r.height)
        case (true, .right): CGRect(x: r.midX, y: r.minY, width: r.width / 2, height: r.height)
        default: r
        }
        return Canvas { ctx, _ in
            if picked != r {
                let other = picked.minX == r.minX ? CGRect(x: r.midX, y: r.minY, width: r.width / 2, height: r.height)
                                                  : CGRect(x: r.minX, y: r.minY, width: r.width / 2, height: r.height)
                ctx.fill(Path(other), with: .color(.black.opacity(L.DIM)))
            }
            ctx.stroke(Path(picked.insetBy(dx: 1.5, dy: 1.5)), with: .color(CpColor.accent), lineWidth: 3)
        }
    }

    private func pick() {
        guard let s = shown, !saving else { return }
        saving = true
        let half = L.savedHalf(shown: s.half, wide: s.wide, chosen: L.halfAt(index: Int32(choice)))
        Task {
            let image = await engine.coverImage(s.page, half: half)
            if !(image.map(save) ?? false) {
                failed = true
                saving = false
            }
        }
    }
}
