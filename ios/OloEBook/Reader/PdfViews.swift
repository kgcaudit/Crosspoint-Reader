import SwiftUI
import UIKit

/// 폭 맞춤의 쪽(구상안 33): 쪽 폭을 화면 폭에 맞춘 긴 그림을 [offset] 만큼 올려 보인다. 쪽 안 이동 · 넘김은 모델이 정한다.
struct PdfWidthPage: View {
    let image: UIImage
    let area: CGRect
    let offset: CGFloat

    var body: some View {
        Image(uiImage: image).resizable()
            .frame(width: area.width, height: area.width * image.size.height / max(image.size.width, 1))
            .offset(y: -offset)
            .frame(width: area.width, height: area.height, alignment: .top)
            .clipped()
            .position(x: area.midX, y: area.midY)
            .allowsHitTesting(false)
    }
}

/// 쪽 안 자리 알약 · 오른쪽 막대(안드로이드 `screenLabel` · `continuousLabel`). 바뀐 뒤 1.5초 보였다 사라진다.
struct PdfPositionPill: View {
    let text: String
    /// 막대: 보이는 부분의 시작 · 길이(0~1). nil 이면 막대 없이 알약만(이어서).
    var bar: (start: CGFloat, length: CGFloat)?
    let area: CGRect
    let token: Int
    @State private var visible = false

    var body: some View {
        ZStack {
            if visible {
                if let bar {
                    Capsule().fill(Color.secondary.opacity(0.5))
                        .frame(width: 3, height: max(area.height * bar.length, 24))
                        .position(x: area.maxX - 6, y: area.minY + area.height * bar.start + max(area.height * bar.length, 24) / 2)
                }
                Text(text).font(CpFont.label).foregroundStyle(CpColor.text)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(CpColor.surface).clipShape(Capsule())
                    .overlay(Capsule().stroke(CpColor.divider, lineWidth: 1))
                    .position(x: area.midX, y: area.maxY - 30)
                    .accessibilityLabel(bar == nil ? "쪽 위치 \(text)" : "쪽 안 위치 \(text)")
            }
        }
        .allowsHitTesting(false)
        .task(id: token) {
            guard token > 0 else { return }
            visible = true
            try? await Task.sleep(for: .milliseconds(1500))
            if !Task.isCancelled { withAnimation(.easeOut(duration: 0.2)) { visible = false } }
        }
    }

    /// "4쪽 · 첫 화면 (1/3)" · "4쪽 · 2번째 화면 (2/3)" · "4쪽 · 끝 화면 (3/3)". 한 화면에 다 들어가면 nil.
    static func screenLabel(page: Int, offset: CGFloat, height: CGFloat, viewport: CGFloat) -> String? {
        guard height > viewport + 1, viewport > 0 else { return nil }
        let step = viewport * 0.9
        let count = Int(((height - viewport) / step).rounded(.up)) + 1
        let at = offset >= height - viewport - 0.5 ? count : min(Int((offset / step).rounded(.down)) + 1, count)
        let name = at == 1 ? "첫 화면" : at == count ? "끝 화면" : "\(at)번째 화면"
        return "\(page + 1)쪽 · \(name) (\(at)/\(count))"
    }
}

/// 이어서(구상안 33, 안드로이드 `PdfContinuous`): 쪽을 폭에 맞춰 위아래로 잇는다(사이 14, 지면보다 짙은 틈). 누르면 화면 90% 씩,
/// 옆으로 밀면 한 쪽. 확대 · 넘김 효과는 없다 — 그래서 한 번 누름이 늦지 않다. 지금 쪽은 화면 **가운데**가 걸친 쪽, 저장하는
/// 자리는 화면 **맨 위**의 쪽과 그 쪽 안 천분율.
struct PdfContinuousView: UIViewRepresentable {
    let aspects: [CGFloat]
    let area: CGRect
    let gap: UIColor
    let paper: UIColor
    /// 굴려 갈 쪽(목차 · 진도 · 책갈피) — [jumpToken] 이 바뀔 때만 따른다(사람이 굴린 것을 되돌리지 않게).
    let target: (page: Int, y: Int)
    let jumpToken: Int
    /// 화면 90% 굴리기(+1 아래 · −1 위). [stepToken] 이 바뀔 때만.
    let step: Int
    let stepToken: Int
    let image: (Int, CGFloat) async -> UIImage?
    let onTap: (CGPoint) -> Void
    let onSwipe: (Int) -> Void
    /// 손을 멈췄다: (맨 위 쪽, 그 쪽 안 천분율, 가운데 쪽).
    let onSettle: (Int, Int, Int) -> Void
    /// 굴리는 중: 가운데 쪽(알약).
    let onScroll: (Int) -> Void
    /// 길게 눌렀다(화면 좌표) — 낱말 고르기.
    var onLongPress: (CGPoint) -> Void = { _ in }
    /// 손을 멈췄다: 화면 가까운 쪽마다 지금 놓인 자리(화면 좌표) — 고르기 · 칠 누르기가 쓴다.
    var onPlaced: ([(Int, CGRect)]) -> Void = { _ in }
    /// 듣는 문장(쪽 · 쪽 비율 위 · 아래) — [reveal] 의 번호가 바뀌면 화면 밖일 때만 보이게 옮긴다.
    var reveal: (page: Int, top: CGFloat, bottom: CGFloat, token: Int) = (0, 0, 0, 0)
    /// 옮길 자리 셈(위, 아래, 화면 위, 화면 높이, 끝) → 새 화면 위 또는 nil(Kotlin `IosPdfText.follow`).
    var follow: (CGFloat, CGFloat, CGFloat, CGFloat, CGFloat) -> CGFloat? = { _, _, _, _, _ in nil }

    static let spacing: CGFloat = 14

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UIScrollView {
        let sv = UIScrollView()
        sv.backgroundColor = gap
        sv.showsVerticalScrollIndicator = false
        sv.delegate = context.coordinator
        sv.contentInsetAdjustmentBehavior = .never
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        sv.addGestureRecognizer(tap)
        let hold = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.held(_:)))
        sv.addGestureRecognizer(hold)
        for dir in [UISwipeGestureRecognizer.Direction.left, .right] {
            let swipe = UISwipeGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.swiped(_:)))
            swipe.direction = dir
            swipe.delegate = context.coordinator
            sv.addGestureRecognizer(swipe)
        }
        context.coordinator.scroll = sv
        return sv
    }

    func updateUIView(_ sv: UIScrollView, context: Context) {
        let c = context.coordinator
        c.parent = self
        sv.backgroundColor = gap
        c.layout(sv)
        if c.jumpToken != jumpToken {
            c.jumpToken = jumpToken
            c.scrollTo(page: target.page, y: target.y, in: sv)
        }
        if c.stepToken != stepToken {
            c.stepToken = stepToken
            c.stepBy(step, in: sv)
        }
        if c.revealToken != reveal.token {
            c.revealToken = reveal.token
            c.reveal(page: reveal.page, top: reveal.top, bottom: reveal.bottom, in: sv)
        }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate, UIGestureRecognizerDelegate {
        var parent: PdfContinuousView
        weak var scroll: UIScrollView?
        var jumpToken = -1
        var stepToken = 0
        var revealToken = 0
        private var tops: [CGFloat] = []
        private var heights: [CGFloat] = []
        private var views: [Int: UIImageView] = [:]
        private var laidWidth: CGFloat = 0
        /// 잇달아 누르면 거리를 쌓는다(굴리는 중에 또 누르면 거기서부터 90% 더).
        private var goal: CGFloat?
        private var settle: DispatchWorkItem?

        init(_ parent: PdfContinuousView) { self.parent = parent }

        func layout(_ sv: UIScrollView) {
            let w = parent.area.width
            guard w > 0, w != laidWidth || tops.count != parent.aspects.count else { return }
            let keep = place(sv)
            laidWidth = w
            var y = parent.area.minY
            tops = []
            heights = []
            for a in parent.aspects {
                tops.append(y)
                heights.append(w * a)
                y += w * a + PdfContinuousView.spacing
            }
            sv.contentSize = CGSize(width: sv.bounds.width, height: y - PdfContinuousView.spacing + (sv.bounds.height - parent.area.maxY))
            for v in views.values { v.removeFromSuperview() }
            views = [:]
            if let keep { scrollTo(page: keep.0, y: keep.1, in: sv) }
            fill(sv)
        }

        /// 맨 위 쪽과 그 쪽 안 천분율.
        private func place(_ sv: UIScrollView) -> (Int, Int)? {
            guard !tops.isEmpty else { return nil }
            let y = sv.contentOffset.y + parent.area.minY
            let i = max((tops.lastIndex { $0 <= y + 0.5 }) ?? 0, 0)
            return (i, Int(((y - tops[i]) / max(heights[i], 1) * 1000).rounded()).clamped(0, 1000))
        }

        private func center(_ sv: UIScrollView) -> Int {
            let y = sv.contentOffset.y + parent.area.midY
            return max((tops.lastIndex { $0 <= y }) ?? 0, 0)
        }

        func scrollTo(page: Int, y: Int, in sv: UIScrollView) {
            guard page < tops.count else { return }
            let off = tops[page] + heights[page] * CGFloat(y) / 1000 - parent.area.minY
            sv.setContentOffset(CGPoint(x: 0, y: clampOffset(off, sv)), animated: false)
            fill(sv)
        }

        private var litPage: Int?

        /// 듣는 문장이 화면(쪽을 놓는 칸) 밖이면 칸 높이 30% 자리로 부드럽게. 칠도 새로 — 쪽 그림은 처음 한 번만 그려 두므로, 다시
        /// 그리지 않으면 문장이 바뀌어도 첫 문장의 칠이 남거나 칠이 아예 보이지 않았다.
        func reveal(page: Int, top: CGFloat, bottom: CGFloat, in sv: UIScrollView) {
            if page < 0 {
                for i in views.keys { redraw(i) }
                return
            }
            guard page < tops.count else { return }
            for p in Set([page, litPage].compactMap { $0 }) { redraw(p) }
            litPage = page
            guard top >= 0 else { return }
            let a = tops[page] + heights[page] * top - parent.area.minY
            let b = tops[page] + heights[page] * bottom - parent.area.minY
            guard let next = parent.follow(a, b, sv.contentOffset.y, parent.area.height, max(sv.contentSize.height - sv.bounds.height, 0)) else { return }
            sv.setContentOffset(CGPoint(x: 0, y: clampOffset(next, sv)), animated: true)
        }

        func stepBy(_ step: Int, in sv: UIScrollView) {
            let from = goal ?? sv.contentOffset.y
            let to = clampOffset(from + CGFloat(step) * parent.area.height * 0.9, sv)
            goal = to
            sv.setContentOffset(CGPoint(x: 0, y: to), animated: true)
        }

        private func clampOffset(_ y: CGFloat, _ sv: UIScrollView) -> CGFloat {
            min(max(y, 0), max(sv.contentSize.height - sv.bounds.height, 0))
        }

        /// 화면 가까운 쪽만 그린다 — 1000쪽 PDF 를 다 그리면 메모리가 바닥난다.
        private func fill(_ sv: UIScrollView) {
            guard !tops.isEmpty else { return }
            let top = sv.contentOffset.y - sv.bounds.height, bottom = sv.contentOffset.y + sv.bounds.height * 2
            let near = Set(tops.indices.filter { tops[$0] + heights[$0] > top && tops[$0] < bottom })
            for (i, v) in views where !near.contains(i) {
                v.removeFromSuperview()
                views[i] = nil
            }
            for i in near where views[i] == nil {
                let v = UIImageView(frame: CGRect(x: parent.area.minX, y: tops[i], width: laidWidth, height: heights[i]))
                v.backgroundColor = parent.paper
                sv.addSubview(v)
                views[i] = v
                let width = laidWidth
                Task { @MainActor [weak v] in
                    let img = await self.parent.image(i, width)
                    v?.image = img
                    if img == nil { v?.addSubview(Self.broken(v?.bounds ?? .zero)) }
                }
            }
        }

        /// 놓인 쪽 하나를 다시 그린다(칠이 바뀌었다). 화면 가까이 없으면 할 일이 없다 — 가까이 오면 새로 그린다.
        private func redraw(_ i: Int) {
            guard let v = views[i] else { return }
            let width = laidWidth
            Task { @MainActor [weak v] in
                if let img = await self.parent.image(i, width) { v?.image = img }
            }
        }

        /// 그리지 못한 쪽 — 그 자리에만 알리고 다른 쪽은 계속 굴린다.
        static func broken(_ rect: CGRect) -> UILabel {
            let l = UILabel(frame: rect)
            l.text = "이 쪽을 그리지 못했습니다"
            l.textAlignment = .center
            l.textColor = .secondaryLabel
            l.font = .systemFont(ofSize: 14)
            return l
        }

        func scrollViewDidScroll(_ sv: UIScrollView) {
            fill(sv)
            if sv.isDragging || sv.isDecelerating || goal != nil { parent.onScroll(center(sv)) }
            settle?.cancel()
            let work = DispatchWorkItem { [weak self, weak sv] in
                guard let self, let sv, let p = self.place(sv) else { return }
                self.parent.onSettle(p.0, p.1, self.center(sv))
                self.parent.onPlaced(self.placed(sv))
            }
            settle = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: work)
        }

        func scrollViewDidEndScrollingAnimation(_ sv: UIScrollView) { goal = nil }
        func scrollViewWillBeginDragging(_ sv: UIScrollView) { goal = nil }

        @objc func tapped(_ g: UITapGestureRecognizer) {
            guard let v = g.view else { return }
            let p = g.location(in: v)
            parent.onTap(CGPoint(x: p.x, y: p.y - (scroll?.contentOffset.y ?? 0)))
        }

        @objc func held(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let v = g.view else { return }
            // 놓인 자리가 아직 알려지지 않았을 수 있다(굴리자마자 길게 눌렀다) — 지금 자리를 먼저 알린다.
            if let sv = scroll { parent.onPlaced(placed(sv)) }
            let p = g.location(in: v)
            parent.onLongPress(CGPoint(x: p.x, y: p.y - (scroll?.contentOffset.y ?? 0)))
        }

        /// 화면 가까운 쪽들이 지금 화면에 놓인 자리(화면 좌표 — 굴리는 판이 화면 전체를 덮는다).
        func placed(_ sv: UIScrollView) -> [(Int, CGRect)] {
            views.keys.sorted().map { i in (i, CGRect(x: parent.area.minX, y: tops[i] - sv.contentOffset.y, width: laidWidth, height: heights[i])) }
        }

        @objc func swiped(_ g: UISwipeGestureRecognizer) { parent.onSwipe(g.direction == .left ? 1 : -1) }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}

/// 확대(구상안 33, 안드로이드 `PageViewport`): 두 손가락 · 두 번 누르기. 1(쉬는 크기) ~ 5배, 두 번 누르면 2.5배 ↔ 쉬는 크기. 확대 중
/// 끌기는 쪽 안 이동이고 넘기지 않는다. 쉬는 크기로 돌아오면 닫힌다. 한 번 누름은 넘김 · 메뉴(넘기면 확대가 풀린다).
struct PdfZoomView: UIViewRepresentable {
    /// 확대 1 에서 쪽이 놓인 자리(화면 좌표).
    let base: CGRect
    /// 쪽을 둘 칸(쪽 전체면 쪽 영역, 폭 맞춤이면 그 칸) — 작을 때 가운데에.
    let area: CGRect
    let image: UIImage
    /// 시작: 누른 자리와 배율.
    let anchor: CGPoint
    let scale: CGFloat
    let paper: UIColor
    let onTap: (CGPoint) -> Void
    let onDone: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UIScrollView {
        let sv = UIScrollView()
        sv.backgroundColor = paper
        sv.minimumZoomScale = 1
        sv.maximumZoomScale = 5
        sv.bouncesZoom = true
        sv.showsHorizontalScrollIndicator = false
        sv.showsVerticalScrollIndicator = false
        sv.contentInsetAdjustmentBehavior = .never
        sv.delegate = context.coordinator
        let iv = UIImageView(image: image)
        iv.frame = CGRect(origin: .zero, size: base.size)
        sv.addSubview(iv)
        sv.contentSize = base.size
        context.coordinator.imageView = iv
        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        single.require(toFail: double)
        sv.addGestureRecognizer(double)
        sv.addGestureRecognizer(single)
        DispatchQueue.main.async { context.coordinator.start(sv) }
        return sv
    }

    func updateUIView(_ sv: UIScrollView, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.imageView?.image !== image { context.coordinator.imageView?.image = image }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var parent: PdfZoomView
        weak var imageView: UIImageView?

        init(_ parent: PdfZoomView) { self.parent = parent }

        func viewForZooming(in sv: UIScrollView) -> UIView? { imageView }

        func start(_ sv: UIScrollView) {
            center(sv)
            sv.contentOffset = CGPoint(x: -sv.contentInset.left, y: -sv.contentInset.top)
            zoom(sv, to: parent.scale, at: parent.anchor, animated: true)
        }

        /// [point](화면) 이 제자리에 있게 [scale] 로.
        func zoom(_ sv: UIScrollView, to scale: CGFloat, at point: CGPoint, animated: Bool) {
            guard let iv = imageView else { return }
            // [point] 는 화면 좌표 — 스크롤 뷰 안 좌표(내용이 밀린 만큼 더한다)를 거쳐 그림 안 좌표로.
            let p = iv.convert(CGPoint(x: point.x + sv.contentOffset.x, y: point.y + sv.contentOffset.y), from: sv)
            let size = CGSize(width: sv.bounds.width / scale, height: sv.bounds.height / scale)
            sv.zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height), animated: animated)
        }

        /// 쪽이 칸보다 작으면 칸 가운데에, 크면 화면 끝까지 움직일 수 있게.
        func center(_ sv: UIScrollView) {
            let w = sv.contentSize.width, h = sv.contentSize.height
            let a = parent.area
            let top = h < a.height ? a.midY - h / 2 : min(a.minY, max(sv.bounds.height - h, 0))
            let left = w < a.width ? a.midX - w / 2 : 0
            sv.contentInset = UIEdgeInsets(top: max(top, 0), left: max(left, 0),
                                           bottom: max(sv.bounds.height - top - h, 0), right: max(sv.bounds.width - left - w, 0))
        }

        func scrollViewDidZoom(_ sv: UIScrollView) { center(sv) }

        func scrollViewDidEndZooming(_ sv: UIScrollView, with view: UIView?, atScale scale: CGFloat) {
            if scale <= 1.01 { parent.onDone() }
        }

        @objc func doubleTapped(_ g: UITapGestureRecognizer) {
            guard let sv = g.view as? UIScrollView else { return }
            if sv.zoomScale > 1.01 {
                sv.setZoomScale(1, animated: true)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.parent.onDone() }
            } else {
                zoom(sv, to: 2.5, at: g.location(in: sv.superview), animated: true)
            }
        }

        @objc func tapped(_ g: UITapGestureRecognizer) { parent.onTap(g.location(in: g.view?.superview)) }
    }
}

private extension Comparable {
    func clamped(_ lo: Self, _ hi: Self) -> Self { min(max(self, lo), hi) }
}

extension UIImage {
    /// 화면 좌표의 [rect] 부분(확대가 시작할 때 또렷한 그림이 오기 전의 자리 채움).
    func cropped(to rect: CGRect) -> UIImage {
        guard let cg = cgImage else { return self }
        let r = CGRect(x: rect.minX * scale, y: rect.minY * scale, width: rect.width * scale, height: rect.height * scale)
        return cg.cropping(to: r).map { UIImage(cgImage: $0, scale: scale, orientation: .up) } ?? self
    }
}

extension Paper {
    /// 이어서의 쪽 사이 틈: 지면과 글자색 사이 30%(안드로이드 `lerp(paper, ink, 0.3)`).
    var gapColor: Color {
        func c(_ v: UInt32, _ shift: UInt32) -> Double { Double((v >> shift) & 0xFF) / 255 }
        func mix(_ s: UInt32) -> Double { c(paper, s) + (c(ink, s) - c(paper, s)) * 0.3 }
        return Color(red: mix(16), green: mix(8), blue: mix(0))
    }
}
