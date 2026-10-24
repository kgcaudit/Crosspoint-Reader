import SwiftUI
import UIKit

/// "말림" 넘김(구상안 23A): iOS 기본 말림(`UIPageViewController` · `.pageCurl`, Apple Books 와 같은 모양). 손가락을 따라 말리려면 넘어올
/// 쪽 그림이 끌기 **전에** 있어야 해서, 앞 · 뒤 쪽을 미리 그려 받는다([previous] · [next], 엔진의 `peek`). 없으면 그쪽으로는 말리지
/// 않는다(책의 처음 · 끝).
///
/// 두 쪽 보기면 책등을 가운데에 둔 펼침 말림(구상안 27): 오른쪽 쪽이 말려 넘어가고 그 뒷면이 다음 펼침의 왼쪽 쪽이 된다(Apple Books ·
/// 안드로이드와 같은 움직임). 엔진은 펼침을 한 장으로 그리므로 여기서 반으로 잘라 두 쪽으로 넘긴다.
///
/// 끌기는 이 부품이 받고, 누름 · 길게 누름은 [onTap] · [onLongPress] 로 넘긴다 — 지면 위에 SwiftUI 제스처 층을 얹으면 그 층이 끌기를
/// 먼저 가로채 말림이 손가락을 따라가지 않는다. 기본 말림의 "가장자리 누르면 넘김" 은 끈다(누름은 링크 · 칠 · 메뉴를 먼저 본다).
struct CurlPager: UIViewControllerRepresentable {
    let image: UIImage
    /// 지금 쪽의 열쇠(장:쪽). 바뀌면 새 쪽을 보인다.
    let key: String
    let previous: UIImage?
    let next: UIImage?
    let paper: UIColor
    /// 두 쪽 보기. 만들 때 정해진다(책등 자리는 바꿀 수 없다) — 바뀌면 화면이 새로 만든다.
    var spread = false
    /// 사람이 누름 · 밀기로 넘겨 쪽이 바뀌었으면 말며 보인다(+1 앞으로 · −1 뒤로). 0 이면 말지 않는다(목차 이동 등 순간 이동).
    let animate: Int
    let onFlip: (Int) -> Void
    let onTap: (CGPoint) -> Void
    let onLongPress: (CGPoint) -> Void
    /// PDF 확대(두 번 누르기 2.5배 · 두 손가락). nil 이면 받지 않는다 — 받으면 한 번 누름이 두 번째를 기다린다.
    var zoom: ((CGPoint, CGFloat) -> Void)?
    /// 늘면 지금 쪽 그림으로 되돌린다 — 손으로 말았는데 쪽이 옮겨지지 않았을 때(말린 그림이 남지 않게).
    var reset = 0

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> UIPageViewController {
        let options: [UIPageViewController.OptionsKey: Any]? =
            spread ? [.spineLocation: NSNumber(value: UIPageViewController.SpineLocation.mid.rawValue)] : nil
        let pager = UIPageViewController(transitionStyle: .pageCurl, navigationOrientation: .horizontal, options: options)
        pager.isDoubleSided = spread
        pager.dataSource = context.coordinator
        pager.delegate = context.coordinator
        pager.view.backgroundColor = paper
        pager.setViewControllers(pages(image, step: 0), direction: .forward, animated: false)
        context.coordinator.shownKey = key
        for g in pager.gestureRecognizers where g is UITapGestureRecognizer { g.isEnabled = false }
        let c = context.coordinator
        let tap = UITapGestureRecognizer(target: c, action: #selector(Coordinator.tapped(_:)))
        let long = UILongPressGestureRecognizer(target: c, action: #selector(Coordinator.pressed(_:)))
        long.minimumPressDuration = 0.45
        // 확대 제스처는 늘 붙이고, 확대를 받을 때만 듣게 한다(대리자). 만들 때만 붙이면 표지 고르기가 끝나거나 두 쪽에서 한 쪽으로 바뀌어
        // 확대가 생겨도 말림 화면에서는 두 번 누르기 · 두 손가락 확대가 듣지 않았다.
        let double = UITapGestureRecognizer(target: c, action: #selector(Coordinator.doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        let pinch = UIPinchGestureRecognizer(target: c, action: #selector(Coordinator.pinched(_:)))
        for g in [tap, double, pinch] as [UIGestureRecognizer] { g.delegate = c }
        c.double = double
        c.pinch = pinch
        pager.view.addGestureRecognizer(double)
        pager.view.addGestureRecognizer(pinch)
        pager.view.addGestureRecognizer(tap)
        pager.view.addGestureRecognizer(long)
        return pager
    }

    func updateUIViewController(_ pager: UIPageViewController, context: Context) {
        let c = context.coordinator
        c.parent = self
        pager.view.backgroundColor = paper
        if reset != c.shownReset {
            c.shownReset = reset
            c.userFlipped = false
            c.shownKey = key
            c.shownImage = image
            c.show(pager, pages(image, step: 0), direction: .forward, animated: false)
            return
        }
        guard key != c.shownKey else {
            // 같은 쪽을 다시 그렸다(칠 · 테마 · 듣는 문장): 말지 않고 바꿔 끼운다.
            if c.shownImage !== image {
                c.shownImage = image
                let fresh = halves(image)
                for (vc, img) in zip(pager.viewControllers?.compactMap { $0 as? PageImageController } ?? [], fresh) { vc.image = img }
            }
            return
        }
        c.shownKey = key
        c.shownImage = image
        let fresh = pages(image, step: 0)
        if c.userFlipped {
            // 손가락으로 이미 말았다 — 같은 쪽을 다시 말면 두 번 넘어가 보인다.
            c.userFlipped = false
            c.show(pager, fresh, direction: .forward, animated: false)
        } else if animate != 0 && !UIAccessibility.isReduceMotionEnabled {
            c.show(pager, fresh, direction: animate > 0 ? .forward : .reverse, animated: true)
        } else {
            c.show(pager, fresh, direction: .forward, animated: false)
        }
    }

    /// 쪽 하나, 두 쪽이면 펼침의 왼쪽 · 오른쪽.
    fileprivate func pages(_ image: UIImage, step: Int) -> [PageImageController] {
        halves(image).enumerated().map { i, img in
            let vc = PageImageController()
            vc.image = img
            vc.step = step
            vc.half = i
            vc.view.backgroundColor = paper
            return vc
        }
    }

    /// 펼침 그림을 화면 가운데에서 둘로(책등이 가운데다 — 양옆 안전 영역이 같다).
    private func halves(_ image: UIImage) -> [UIImage] {
        guard spread, let cg = image.cgImage else { return [image] }
        let w = cg.width / 2
        return [CGRect(x: 0, y: 0, width: w, height: cg.height), CGRect(x: w, y: 0, width: cg.width - w, height: cg.height)].map {
            cg.cropping(to: $0).map { UIImage(cgImage: $0, scale: image.scale, orientation: .up) } ?? image
        }
    }

    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate, UIGestureRecognizerDelegate {
        var parent: CurlPager
        var shownKey = ""
        weak var shownImage: UIImage?
        var userFlipped = false
        var shownReset = 0
        private var pendingStep = 0
        weak var double: UITapGestureRecognizer?
        weak var pinch: UIPinchGestureRecognizer?

        /// 말리는 중(넘김 움직임 · 손가락으로 끄는 중). 그사이 온 새 쪽은 끝난 뒤에 끼운다 — 말리는 도중 `setViewControllers` 를 겹쳐
        /// 부르면 `UIPageViewController` 가 NSInternalInconsistencyException 으로 앱을 끝낸다(빠르게 연달아 누를 때).
        private var moving = false
        private var queued: (() -> Void)?

        init(_ parent: CurlPager) { self.parent = parent }

        func show(_ pager: UIPageViewController, _ vcs: [UIViewController], direction: UIPageViewController.NavigationDirection, animated: Bool) {
            if moving {
                // 밀린 것은 마지막 하나만, 움직임 없이 — 몇 쪽이 쌓였어도 지금 쪽 하나만 보이면 된다.
                queued = { [weak self, weak pager] in
                    guard let self, let pager else { return }
                    pager.setViewControllers(vcs, direction: direction, animated: false)
                    self.drain()
                }
                return
            }
            // 곧바로 부른다 — 약한 참조로 잡은 닫힘에 담아 부르던 때는 책을 열자마자 넘길 때 그 참조를 읽다 앱이 죽었다(SIGSEGV,
            // objc_retain).
            moving = animated
            pager.setViewControllers(vcs, direction: direction, animated: animated) { [weak self] _ in
                guard let self, animated else { return }
                self.moving = false
                self.drain()
            }
        }

        private func drain() {
            guard !moving, let next = queued else { return }
            queued = nil
            next()
        }

        // 확대를 받을 때만 두 번 누르기 · 두 손가락을 듣고, 그때만 한 번 누름이 두 번째를 기다린다.
        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            if g === double || g === pinch { return parent.zoom != nil }
            return true
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRequireFailureOf other: UIGestureRecognizer) -> Bool {
            g is UITapGestureRecognizer && g !== double && other === double && parent.zoom != nil
        }

        // 두 쪽이면 지금 오른쪽 → 다음 펼침의 왼쪽(말린 쪽의 뒷면) → 그 오른쪽, 거꾸로는 지금 왼쪽 → 앞 펼침의 오른쪽 → 그 왼쪽.
        func pageViewController(_ pvc: UIPageViewController, viewControllerBefore vc: UIViewController) -> UIViewController? {
            guard let p = vc as? PageImageController, let img = parent.previous else { return nil }
            if !parent.spread { return p.step == 0 ? parent.pages(img, step: -1).first : nil }
            if p.step == 0 && p.half == 0 { return parent.pages(img, step: -1).last }
            if p.step == -1 && p.half == 1 { return parent.pages(img, step: -1).first }
            return nil
        }

        func pageViewController(_ pvc: UIPageViewController, viewControllerAfter vc: UIViewController) -> UIViewController? {
            guard let p = vc as? PageImageController, let img = parent.next else { return nil }
            if !parent.spread { return p.step == 0 ? parent.pages(img, step: 1).first : nil }
            if p.step == 0 && p.half == 1 { return parent.pages(img, step: 1).first }
            if p.step == 1 && p.half == 0 { return parent.pages(img, step: 1).last }
            return nil
        }

        func pageViewController(_ pvc: UIPageViewController, willTransitionTo pending: [UIViewController]) {
            pendingStep = (pending.first as? PageImageController)?.step ?? 0
            moving = true
        }

        func pageViewController(_ pvc: UIPageViewController, didFinishAnimating finished: Bool,
                                previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
            moving = false
            defer { drain() }
            // 덜 끌고 떼면 되돌아간다 — 그때는 아무 일도 없다.
            guard completed, pendingStep != 0 else { return }
            userFlipped = true
            parent.onFlip(pendingStep)
            pendingStep = 0
        }

        @objc func tapped(_ g: UITapGestureRecognizer) { parent.onTap(g.location(in: g.view)) }

        @objc func doubleTapped(_ g: UITapGestureRecognizer) { parent.zoom?(g.location(in: g.view), 2.5) }

        @objc func pinched(_ g: UIPinchGestureRecognizer) {
            if g.state == .ended, g.scale > 1.05 { parent.zoom?(g.location(in: g.view), min(g.scale, 5)) }
        }

        @objc func pressed(_ g: UILongPressGestureRecognizer) {
            if g.state == .began { parent.onLongPress(g.location(in: g.view)) }
        }
    }
}

/// 말림의 한 쪽: 쪽 그림을 화면 크기 그대로.
final class PageImageController: UIViewController {
    var step = 0
    /// 펼침의 왼쪽(0) · 오른쪽(1). 한 쪽이면 0.
    var half = 0
    private let imageView = UIImageView()
    var image: UIImage? {
        get { imageView.image }
        set { imageView.image = newValue }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        imageView.contentMode = .scaleToFill
        imageView.frame = view.bounds
        imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(imageView)
    }

    // 늘 제 크기를 따른다. 말림이 뒷면 쪽을 크기 0 으로 만든 뒤 늘리면, 늘이기(autoresizing)는 0 에서 커지지 않아 뒷면이 바탕색(만화는
    // 검정)으로만 보였다 — 두 쪽 보기에서 넘어가는 종이 뒷면이 까맣게 나왔다(실기기에서 찾음).
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        imageView.frame = view.bounds
    }
}
