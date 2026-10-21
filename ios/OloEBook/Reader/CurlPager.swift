import SwiftUI
import UIKit

/// "말림" 넘김(구상안 23A): iOS 기본 말림(`UIPageViewController` · `.pageCurl`, Apple Books 와 같은 모양). 손가락을 따라 말리려면 넘어올
/// 쪽 그림이 끌기 **전에** 있어야 해서, 앞 · 뒤 쪽을 미리 그려 받는다([previous] · [next], 엔진의 `peek`). 없으면 그쪽으로는 말리지
/// 않는다(책의 처음 · 끝).
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
    /// 사람이 누름 · 밀기로 넘겨 쪽이 바뀌었으면 말며 보인다(+1 앞으로 · −1 뒤로). 0 이면 말지 않는다(목차 이동 등 순간 이동).
    let animate: Int
    let onFlip: (Int) -> Void
    let onTap: (CGPoint) -> Void
    let onLongPress: (CGPoint) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> UIPageViewController {
        let pager = UIPageViewController(transitionStyle: .pageCurl, navigationOrientation: .horizontal)
        pager.isDoubleSided = false
        pager.dataSource = context.coordinator
        pager.delegate = context.coordinator
        pager.view.backgroundColor = paper
        pager.setViewControllers([page(image)], direction: .forward, animated: false)
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
            // 같은 쪽을 다시 그렸다(칠 · 테마): 말지 않고 바꿔 끼운다.
            if let current = pager.viewControllers?.first as? PageImageController, current.image !== image {
                current.image = image
            }
            return
        }
        c.shownKey = key
        if c.userFlipped {
            // 손가락으로 이미 말았다 — 같은 쪽을 다시 말면 두 번 넘어가 보인다.
            c.userFlipped = false
            pager.setViewControllers([page(image)], direction: .forward, animated: false)
        } else if animate != 0 && !UIAccessibility.isReduceMotionEnabled {
            pager.setViewControllers([page(image)], direction: animate > 0 ? .forward : .reverse, animated: true)
        } else {
            pager.setViewControllers([page(image)], direction: .forward, animated: false)
        }
    }

    fileprivate func page(_ image: UIImage) -> PageImageController {
        let vc = PageImageController()
        vc.image = image
        vc.view.backgroundColor = paper
        return vc
    }

    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        var parent: CurlPager
        var shownKey = ""
        var userFlipped = false
        private var pendingStep = 0

        init(_ parent: CurlPager) { self.parent = parent }

        func pageViewController(_ pvc: UIPageViewController, viewControllerBefore vc: UIViewController) -> UIViewController? {
            parent.previous.map { img in let p = parent.page(img); p.step = -1; return p }
        }

        func pageViewController(_ pvc: UIPageViewController, viewControllerAfter vc: UIViewController) -> UIViewController? {
            parent.next.map { img in let p = parent.page(img); p.step = 1; return p }
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
