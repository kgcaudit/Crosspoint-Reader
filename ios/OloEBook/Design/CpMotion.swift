import SwiftUI
import UIKit

/// 판이 열리고 닫히는 움직임(구상안 42, 안드로이드 0.55.0 `CpMotion` 과 같은 값). 모든 판이 이 값을 함께 쓴다 — 판마다 따로 적으면
/// 메뉴는 0.25초, 목차는 0.4초처럼 같은 앱 안에서 빠르기가 갈린다. 시스템이 띄우는 판(시트 · 팝오버)은 길이 · 곡선을 앱이 정하지
/// 못해 여기 값을 따르지 않는다.
///
/// 0.54 까지(안드로이드)와 iOS 첫 판은 판이 그 자리에 "툭" 나타났다 — 어디서 무엇이 열렸는지 눈이 따라가지 못한다(사용자 지적).
enum CpMotion {
    static let barIn = 250.0, barOut = 200.0
    static let sideIn = 350.0, screenIn = 300.0, sideOut = 200.0
    static let sheetIn = 300.0, sheetOut = 200.0
    static let popupIn = 200.0, popupOut = 150.0

    /// "동작 줄이기" 면 모두 바로 — 안드로이드의 "애니메이션 제거" 와 같다.
    static var reduced: Bool { UIAccessibility.isReduceMotionEnabled }

    /// 들어올 때: 빠르게 나와 천천히 멈춘다(M3 emphasized decelerate).
    static func enter(_ ms: Double) -> Animation {
        reduced ? .linear(duration: 0) : .timingCurve(0.05, 0.7, 0.1, 1, duration: ms / 1000)
    }

    /// 나갈 때: 천천히 떠나 빠르게 사라진다 — 나가는 쪽을 늘 더 짧게.
    static func exit(_ ms: Double) -> Animation {
        reduced ? .linear(duration: 0) : .timingCurve(0.3, 0, 0.8, 0.15, duration: ms / 1000)
    }

    /// 열릴 때 · 닫힐 때 다른 곡선과 길이를 쓰는 옮김.
    static func both(_ t: AnyTransition, in inMs: Double, out outMs: Double) -> AnyTransition {
        .asymmetric(insertion: t.animation(enter(inMs)), removal: t.animation(exit(outMs)))
    }
}

/// 판과 지면 사이의 경계(구상안 42 ①, 안드로이드 `cpEdge`): 판 가장자리의 1pt 선 + 지면 쪽 10pt 옅은 그림자(검정 15% → 0). 판 색이
/// 종이와 거의 같아(아이보리 · 밝은 테마) 메뉴가 어디서 끝나는지 흐렸다. 선은 판 안쪽에 그어 판 높이를 늘리지 않고, 그림자는 판 밖에.
private struct CpEdge: ViewModifier {
    let atBottom: Bool

    func body(content: Content) -> some View {
        content.overlay(alignment: atBottom ? .bottom : .top) {
            Rectangle().fill(CpColor.divider).frame(height: 1)
                .overlay(alignment: atBottom ? .bottom : .top) {
                    LinearGradient(colors: atBottom ? [.black.opacity(0.15), .clear] : [.clear, .black.opacity(0.15)],
                                   startPoint: .top, endPoint: .bottom)
                        .frame(height: 10)
                        .offset(y: atBottom ? 10 : -10)
                        .allowsHitTesting(false)
                }
                .allowsHitTesting(false)
        }
    }
}

/// 처음 나타날 때만 움직이는 판(앱이 `if` 로 띄우는 팝업 · 아래 판). 닫힐 때는 바로 사라진다 — 안드로이드 0.55.0 과 같다.
private struct CpAppear: ViewModifier {
    enum Kind { case rise, fade, pop(UnitPoint, CGFloat) }
    let kind: Kind
    let ms: Double
    @State private var shown = false

    func body(content: Content) -> some View {
        Group {
            switch kind {
            case .rise:
                // 판 높이만큼 아래에서(높이를 미리 모른다 — 그리는 순간 잰다).
                content.visualEffect { [shown] c, proxy in c.offset(y: shown ? 0 : proxy.size.height) }
            case .fade:
                content.opacity(shown ? 1 : 0)
            case .pop(let anchor, let from):
                content.scaleEffect(shown ? 1 : from, anchor: anchor).opacity(shown ? 1 : 0)
            }
        }
        .onAppear { withAnimation(CpMotion.enter(ms)) { shown = true } }
    }
}

extension View {
    func cpEdge(atBottom: Bool) -> some View { modifier(CpEdge(atBottom: atBottom)) }
    /// 아래 판(메모 · 각주 · 듣기): 아래에서 올라온다.
    func cpRiseIn(_ ms: Double = CpMotion.sheetIn) -> some View { modifier(CpAppear(kind: .rise, ms: ms)) }
    /// 판 뒤 막: 판과 같은 길이로 서서히 짙어진다 — 막이 먼저 "탁" 어두워지면 판보다 막이 먼저 눈에 띈다.
    func cpFadeIn(_ ms: Double) -> some View { modifier(CpAppear(kind: .fade, ms: ms)) }
    /// 팝업 · 카드: 연 자리에서 조금 작게(0.95) 시작해 커진다. 크게 줄이면(0.8) 튀어나오는 장난감처럼 보였다.
    func cpPopIn(_ ms: Double = CpMotion.popupIn, anchor: UnitPoint = .center, from: CGFloat = 0.95) -> some View {
        modifier(CpAppear(kind: .pop(anchor, from), ms: ms))
    }
}
