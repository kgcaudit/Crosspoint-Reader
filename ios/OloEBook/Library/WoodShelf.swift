import SwiftUI
import UIKit

/*
 * 책장 보기(안드로이드 `WoodShelf.kt` 0.39 · 0.44 의 짝, 구상안 10 확정). 뒷벽은 원목 결, 양옆에 기둥, 칸마다 위에 칸막이(판)를
 * 두고 그 앞면에 **그 칸의 이름**을 새긴다 — 이름이 책 아래에 있으면 어느 칸 것인지 헷갈린다(안드로이드 사용자 정정).
 * 이름 · 첫 표지는 격자 · 목록 보기와 같은 시작선(gutter) — 보기를 바꿔도 글자가 옆으로 움직이지 않는다.
 * 표지만 판 위에 세운다. 제목 · 진도를 얹으면 판 사이가 벌어져 책장으로 읽히지 않는다.
 */

/// 나무 한 벌. 밝은 테마는 원목, 어두운 테마는 같은 결을 낮춘 색 — 밤에 밝은 원목은 눈부시다. 값은 안드로이드 그대로
/// (`docs/design-handoff/README.md` "책장 원목 — 꿀빛 애쉬", 0.62.0 사용자 결정). 0.61 까지의 주황 메이플(색상 28°)은 브랜드
/// 클레이(15°)와 가까워 강조색 · 표지가 나무에 묻혔다 — 금빛 꿀색(약 38°)으로.
struct Wood: Sendable {
    let base, light, grain, edge, plankTop, plankFront, plankLow, post, label: UInt32
    let dark: Bool

    static let honeyAsh = Wood(base: 0xFFB98F4E, light: 0xFFD4AE6E, grain: 0xFF6E4E1E, edge: 0xCC3E2A0C, plankTop: 0xFFD8B57A,
                               plankFront: 0xFFA67D3E, plankLow: 0xFF6E4E22, post: 0xFF5C3F18, label: 0xFFFFF3DC, dark: false)
    static let night = Wood(base: 0xFF4A361A, light: 0xFF634A26, grain: 0xFF22170A, edge: 0xDD0E0904, plankTop: 0xFF7A5C32,
                            plankFront: 0xFF4E3818, plankLow: 0xFF2E2010, post: 0xFF22180A, label: 0xFFEADBC2, dark: true)

    static func of(_ scheme: ColorScheme) -> Wood { scheme == .dark ? .night : .honeyAsh }

    /// 0xAARRGGBB(안드로이드 Color 와 같은 순서).
    static func ui(_ v: UInt32) -> UIColor {
        UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255,
                alpha: CGFloat((v >> 24) & 0xFF) / 255)
    }
    static func color(_ v: UInt32) -> Color { Color(ui(v)) }
}

/// 판 두께 · 칸 여백(안드로이드 값). 칸 이름 글자가 격자 보기의 갈래 이름(위 18)과 같은 높이에 서게 맨 위 테두리를 둔다:
/// 테두리 12 + 그늘선 1 + 윗면 4 + 빛 1 = 18. 테두리가 없으면 격자 ↔ 책장을 바꿀 때 글자가 튄다.
enum ShelfMetrics {
    static let crown: CGFloat = 12
    static let boardTop: CGFloat = 4
    /// 판 아래 첫 줄까지. 판 아랫선(1) + 14 = 15 — 격자 보기의 이름 아래 6 + 줄 위 9 와 같아, 보기를 바꿔도 표지가 같은 높이에 선다.
    static let rowTop: CGFloat = 14
    static let post: CGFloat = 12
    /// 빈 판 수를 셀 때의 판 높이(윗면 4 + 빛 1 + 이름 줄 20 + 아랫선 1).
    static let board: CGFloat = 26
}

/// 원목 결 그림. 한 장을 그려 늘려 쓴다 — 결이 세로라 늘려도 티가 나지 않는다. 테마마다 한 번만 그리고(앱 전체에서 둔다),
/// 그리는 동안은 바탕색만 칠한다. 화면 스레드에서 그리면 책장으로 바꿀 때 한 박자 멈춘다.
actor WoodTextures {
    static let shared = WoodTextures()
    private var done: [Bool: UIImage] = [:]

    func texture(_ wood: Wood) -> UIImage {
        if let image = done[wood.dark] { return image }
        let image = Self.draw(wood)
        done[wood.dark] = image
        return image
    }

    /// 결 그리기(안드로이드 0.62.0 `woodTexture` 와 같은 셈, 사용자 결정: 새 결 — 참고 원목 사진). 한 장짜리 결은 단조로웠다:
    /// - 널빤지 여러 장: 폭(17~29%)과 밝기가 판마다 조금씩 다르고, 판 사이에 이음매(그늘 1.5 + 빛 1).
    /// - 판마다 곧은결: 가는 줄이 길게 조금씩 흔들린다.
    /// - 불꽃결(널결): 넓은 띠(여름 나무)와 그 가장자리의 짙은 선이 겹친 아치 — 판마다 한두 개.
    /// 가장자리는 어둡게(그늘). 씨앗을 고정해 열 때마다 같은 결이다(난수만 안드로이드와 다르다 — 결의 모양 규칙은 같다).
    nonisolated static func draw(_ t: Wood, w: CGFloat = 600, h: CGFloat = 1400) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { ctx in
            let c = ctx.cgContext
            var rnd = Seeded(11)
            func ink(_ v: UInt32, _ alpha: Double) -> CGColor { Wood.ui(v).withAlphaComponent(alpha / 255).cgColor }
            c.setFillColor(Wood.ui(t.base).cgColor)
            c.fill(CGRect(x: 0, y: 0, width: w, height: h))
            c.setLineCap(.round)
            var x: CGFloat = 0
            var board = 0
            while x < w {
                let bw = min(w * (0.17 + rnd.next() * 0.12), w - x)
                c.setFillColor(ink(rnd.next() < 0.5 ? t.light : t.grain, 18 + floor(rnd.next() * 34)))
                c.fill(CGRect(x: x, y: 0, width: bw, height: h))
                c.saveGState()
                c.clip(to: CGRect(x: x, y: 0, width: bw, height: h))
                for _ in 0..<Int(bw / 3) {
                    let lx = x + rnd.next() * bw, ph = rnd.next() * 6.28, amp = 1.0 + rnd.next() * 2.5
                    c.move(to: CGPoint(x: lx, y: -10))
                    var y: CGFloat = -10
                    while y < h + 10 { y += 24; c.addLine(to: CGPoint(x: lx + sin(y * 0.004 + ph) * amp, y: y)) }
                    c.setStrokeColor(ink(t.grain, 25 + floor(rnd.next() * 55)))
                    c.setLineWidth(0.6 + rnd.next() * 1.6)
                    c.strokePath()
                }
                let flames = rnd.next() < 0.7 ? 2 : 1
                for k in 0..<flames {
                    let cx = x + bw * (0.35 + rnd.next() * 0.3)
                    let top = h * (CGFloat(k) * 0.5 + rnd.next() * 0.25)
                    let rings = 7 + Int(rnd.next() * 5)
                    var gap: CGFloat = 0
                    for _ in 0..<rings {
                        gap += 22 + rnd.next() * 26
                        let half = 8 + gap * 0.55, apex = top + gap * 0.9, bottom = apex + 700 + gap
                        let wob = (rnd.next() - 0.5) * 14
                        let arch = CGMutablePath()
                        arch.move(to: CGPoint(x: cx - half * 2.4 + wob, y: bottom))
                        arch.addCurve(to: CGPoint(x: cx, y: apex), control1: CGPoint(x: cx - half * 2.0, y: apex + 220),
                                      control2: CGPoint(x: cx - half * 0.7, y: apex + 6))
                        arch.addCurve(to: CGPoint(x: cx + half * 2.4 - wob, y: bottom), control1: CGPoint(x: cx + half * 0.7, y: apex + 6),
                                      control2: CGPoint(x: cx + half * 2.0, y: apex + 220))
                        // 넓은 여름 나무 띠 → 그 가장자리의 짙은 선 → 아래로 7 비낀 밝은 띠(띠 사이의 봄 나무).
                        c.addPath(arch); c.setStrokeColor(ink(t.grain, 34 + floor(rnd.next() * 26))); c.setLineWidth(9 + rnd.next() * 8); c.strokePath()
                        c.addPath(arch); c.setStrokeColor(ink(t.grain, 80 + floor(rnd.next() * 50))); c.setLineWidth(1.4 + rnd.next() * 1.2); c.strokePath()
                        c.saveGState(); c.translateBy(x: 0, y: 7)
                        c.addPath(arch); c.setStrokeColor(ink(t.light, 45)); c.setLineWidth(5); c.strokePath()
                        c.restoreGState()
                    }
                }
                c.restoreGState()
                if board > 0 {
                    c.setFillColor(UIColor(white: 0, alpha: 0x55 / 255.0).cgColor); c.fill(CGRect(x: x, y: 0, width: 1.5, height: h))
                    c.setFillColor(UIColor(white: 1, alpha: 0x22 / 255.0).cgColor); c.fill(CGRect(x: x + 1.5, y: 0, width: 1, height: h))
                }
                x += bw
                board += 1
            }
            let colors = [UIColor.clear.cgColor, UIColor.clear.cgColor, Wood.ui(t.edge).cgColor] as CFArray
            if let g = CGGradient(colorsSpace: nil, colors: colors, locations: [0, 0.5, 1]) {
                let center = CGPoint(x: w / 2, y: h * 0.45)
                c.drawRadialGradient(g, startCenter: center, startRadius: 0, endCenter: center, endRadius: max(w, h) * 0.75,
                                     options: .drawsAfterEndLocation)
            }
        }
    }

    /// 고정 씨앗 난수(선형 합동). 결이 열 때마다 같아야 한다 — 바꾸면 책장을 열 때마다 나무가 달라 보인다.
    struct Seeded {
        var s: UInt64
        init(_ seed: UInt64) { s = seed }
        /// 0 이상 1 미만. 좌표 셈에 바로 쓰도록 CGFloat.
        mutating func next() -> CGFloat {
            s = s &* 6364136223846793005 &+ 1442695040888963407
            return CGFloat(Double(s >> 11) / Double(UInt64(1) << 53))
        }
    }
}

/// 책장 보기면 [content](목록) 뒤에 원목 뒷벽, 위에 양옆 기둥. 뒷벽은 목록과 함께 굴러가지 않는다 — 벽은 서 있고 판과 책이 지나간다.
/// [shown] 이 아니면(격자 · 목록) 벽 · 기둥이 서서히 사라지고 [content] 만 남는다. 보기를 바꿀 때 목록 자체를 겹쳐 바꾸면 격자의 이름 ·
/// 진도와 목록 줄이 0.3초 동안 겹쳐 잔상으로 보였다(사용자 지적) — 목록은 바로 바꾸고 벽만 서서히. 표지는 두 보기에서 같은 자리라
/// 책장이 들어오는 느낌은 남는다.
struct ShelfWall<Content: View>: View {
    var shown = true
    @ViewBuilder var content: Content
    @Environment(\.colorScheme) private var scheme
    @State private var texture: UIImage?

    var body: some View {
        let wood = Wood.of(scheme)
        let fade: Animation? = UIAccessibility.isReduceMotionEnabled ? nil : .easeInOut(duration: 0.3)
        ZStack {
            ZStack {
                Wood.color(wood.base)
                if let texture { Image(uiImage: texture).resizable() }
            }
            // 뒷벽은 홈 표시줄 밑까지 — 안전 영역에서 끊으면 책장 아래에 바탕색 띠가 남는다.
            .ignoresSafeArea(edges: .bottom)
            .opacity(shown ? 1 : 0)
            .animation(fade, value: shown)
            content
            HStack(spacing: 0) {
                LinearGradient(colors: [Wood.color(wood.post), Wood.color(wood.plankLow), .black.opacity(0.4)],
                               startPoint: .leading, endPoint: .trailing).frame(width: ShelfMetrics.post)
                Spacer(minLength: 0)
                LinearGradient(colors: [.black.opacity(0.4), Wood.color(wood.plankLow), Wood.color(wood.post)],
                               startPoint: .leading, endPoint: .trailing).frame(width: ShelfMetrics.post)
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .ignoresSafeArea(edges: .bottom)
            .opacity(shown ? 1 : 0)
            .animation(fade, value: shown)
        }
        .clipped()
        .task(id: wood.dark) { texture = await WoodTextures.shared.texture(wood) }
    }
}

/// 책장 맨 위 테두리: 기둥과 같은 짙은 나무로 책장 틀을 닫는다. 목록 맨 앞에 한 번만.
struct ShelfCrown: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        let wood = Wood.of(scheme)
        VStack(spacing: 0) {
            LinearGradient(colors: [Wood.color(wood.post), Wood.color(wood.plankLow)], startPoint: .top, endPoint: .bottom)
                .frame(height: ShelfMetrics.crown)
            Color.black.opacity(0.33).frame(height: 1)
        }
        .accessibilityHidden(true)
    }
}

/// 칸막이(판) 하나. [label] 은 이 판 **아래** 칸의 이름 — 판 앞면에 새긴다(밝은 글자 + 아래 1pt 그늘).
/// 앞면 높이는 이름 한 줄이 정한다: 고정 높이에 가운데 맞추면 글자 크기에 따라 이름이 격자와 다른 높이에 선다.
/// 이름 없는 판도 보이지 않는 한 줄로 같은 두께를 지킨다(판마다 두께가 다르면 책장이 삐뚤어 보인다).
struct ShelfBoard: View {
    let label: String?
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let wood = Wood.of(scheme)
        VStack(spacing: 0) {
            // 윗면: 벽 쪽이 어둡고 앞이 밝다 — 위에서 비춘 빛.
            LinearGradient(colors: [Wood.color(wood.plankLow).opacity(0.9), Wood.color(wood.plankTop)], startPoint: .top, endPoint: .bottom)
                .frame(height: ShelfMetrics.boardTop)
            Wood.color(0x66FFF0D8).frame(height: 1)
            ZStack(alignment: .leading) {
                Text(label ?? " ").font(CpFont.label).foregroundStyle(Color.black.opacity(label == nil ? 0 : 0.53))
                    .offset(y: 1).accessibilityHidden(true) // 그림자 글자 — 화면 읽기가 칸 이름을 두 번 읽지 않게.
                if let label {
                    Text(label).font(CpFont.label).foregroundStyle(Wood.color(wood.label)).accessibilityAddTraits(.isHeader)
                }
            }
            .padding(.horizontal, CpMetrics.gutter)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [Wood.color(wood.plankFront), Wood.color(wood.plankLow)], startPoint: .top, endPoint: .bottom))
            Color.black.opacity(0.33).frame(height: 1)
        }
    }
}

/// 판 밑그늘: 아래 칸 뒷벽 위쪽. 칸의 높이를 차지하지 않고 겹친다 — 표지 윗부분에도 그늘이 진다.
struct UnderBoardShadow: View {
    var body: some View {
        LinearGradient(colors: [.black.opacity(0.6), .black.opacity(0.2), .clear], startPoint: .top, endPoint: .bottom)
            .frame(height: 30)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// 판 사이의 한 줄: 표지들이 밑면을 맞춰 선다. 칸 폭은 격자와 같은 셈 — 보기를 바꿔도 한 줄의 권 수가 같다.
struct WoodRow<Item: Identifiable, Cell: View>: View {
    let items: [Item]
    let width: CGFloat
    @ViewBuilder let cell: (Item) -> Cell

    var body: some View {
        ZStack(alignment: .top) {
            // 채우개(Spacer)를 두지 않는다 — 표지 사이 간격이 마지막 표지와 채우개 사이에도 들어가 줄이 14pt 넓어졌고, 가운데 맞춤으로
            // 양쪽에 7pt 씩 비어져 나가 표지가 격자 보기보다 7pt 왼쪽에 섰다(보기를 바꾸면 책이 움찔했다).
            HStack(alignment: .bottom, spacing: LibraryGrid.gap) {
                ForEach(items) { cell($0).frame(width: width) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, CpMetrics.gutter)
            .padding(.top, ShelfMetrics.rowTop)
            UnderBoardShadow()
        }
    }
}

/// 마지막 칸 아래: 판 하나와 빈 칸들을 [minHeight] 까지. 책이 적어도 책장 모양이 남는다.
struct EmptyShelves: View {
    let cellWidth: CGFloat
    let minHeight: CGFloat

    var body: some View {
        let row = cellWidth / LibraryGrid.coverAspect + ShelfMetrics.rowTop
        let count = max(Int(minHeight / (row + ShelfMetrics.board)), 1) + 1
        VStack(spacing: 0) {
            ForEach(0..<count, id: \.self) { _ in
                ShelfBoard(label: nil)
                UnderBoardShadow().frame(height: row, alignment: .top)
            }
        }
        .accessibilityHidden(true)
    }
}
