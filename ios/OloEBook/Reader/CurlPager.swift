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
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        let long = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pressed(_:)))
        long.minimumPressDuration = 0.45
        pager.view.addGestureRecognizer(tap)
        pager.view.addGestureRecognizer(long)
        return pager
    }

    func updateUIViewController(_ pager: UIPageViewController, context: Context) {
        let c = context.coordinator
        c.parent = self
        pager.view.backgroundColor = paper
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
        if c.userFlipped {
            // 손가락으로 이미 말았다 — 같은 쪽을 다시 말면 두 번 넘어가 보인다.
            c.userFlipped = false
            pager.setViewControllers(pages(image, step: 0), direction: .forward, animated: false)
        } else if animate != 0 && !UIAccessibility.isReduceMotionEnabled {
            pager.setViewControllers(pages(image, step: 0), direction: animate > 0 ? .forward : .reverse, animated: true)
        } else {
            pager.setViewControllers(pages(image, step: 0), direction: .forward, animated: false)
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

    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        var parent: CurlPager
        var shownKey = ""
        weak var shownImage: UIImage?
        var userFlipped = false
        private var pendingStep = 0

        init(_ parent: CurlPager) { self.parent = parent }

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
        }

        func pageViewController(_ pvc: UIPageViewController, didFinishAnimating finished: Bool,
                                previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
            // 덜 끌고 떼면 되돌아간다 — 그때는 아무 일도 없다.
            guard completed, pendingStep != 0 else { return }
            userFlipped = true
            parent.onFlip(pendingStep)
            pendingStep = 0
        }

        @objc func tapped(_ g: UITapGestureRecognizer) { parent.onTap(g.location(in: g.view)) }

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
}
