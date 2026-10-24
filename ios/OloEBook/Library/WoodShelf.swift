import SwiftUI
import UIKit

/*
 * 책장 보기(안드로이드 `WoodShelf.kt` 0.39 · 0.44 의 짝, 구상안 10 확정). 뒷벽은 원목 결, 양옆에 기둥, 칸마다 위에 칸막이(판)를
 * 두고 그 앞면에 **그 칸의 이름**을 새긴다 — 이름이 책 아래에 있으면 어느 칸 것인지 헷갈린다(안드로이드 사용자 정정).
 * 이름 · 첫 표지는 격자 · 목록 보기와 같은 시작선(gutter) — 보기를 바꿔도 글자가 옆으로 움직이지 않는다.
 * 표지만 판 위에 세운다. 제목 · 진도를 얹으면 판 사이가 벌어져 책장으로 읽히지 않는다.
 */

/// 나무 한 벌. 밝은 테마는 원목, 어두운 테마는 같은 결을 낮춘 색 — 밤에 밝은 원목은 눈부시다. 값은 안드로이드 그대로.
struct Wood: Sendable {
    let base, light, grain, edge, plankTop, plankFront, plankLow, post, label: UInt32
    let dark: Bool

    static let maple = Wood(base: 0xFFC27733, light: 0xFFDE9A50, grain: 0xFF8A4A1A, edge: 0xCC4A2208, plankTop: 0xFFE6A865,
                            plankFront: 0xFFB8702F, plankLow: 0xFF7E4618, post: 0xFF6E3A12, label: 0xFFFBE8D0, dark: false)
    static let night = Wood(base: 0xFF5A3518, light: 0xFF744620, grain: 0xFF2E1808, edge: 0xDD120802, plankTop: 0xFF8A5A30,
                            plankFront: 0xFF5E3818, plankLow: 0xFF38200C, post: 0xFF2A1708, label: 0xFFE3CFB8, dark: true)

    static func of(_ scheme: ColorScheme) -> Wood { scheme == .dark ? .night : .maple }

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

    /// 결 그리기(안드로이드 `woodTexture` 와 같은 셈): 널빤지마다 밝기가 천천히 오르내리고, 가는 결 줄이 길이 · 굵기 · 짙기를
    /// 달리해 흔들리며, 군데군데 불꽃 무늬. 가장자리는 어둡게. 씨앗을 고정해 열 때마다 같은 결이다(난수만 안드로이드와 다르다).
    nonisolated static func draw(_ t: Wood, w: CGFloat = 600, h: CGFloat = 1400) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { ctx in
            let c = ctx.cgContext
            var rnd = Seeded(7)
            Wood.ui(t.base).setFill()
            c.fill(CGRect(x: 0, y: 0, width: w, height: h))
            let ph = (0..<3).map { _ in rnd.next() * 6.28 }
            c.setLineWidth(1.2)
            for x in 0..<Int(w) {
                let xd = Double(x)
                let v = sin(xd * 0.009 + ph[0]) * 0.45 + sin(xd * 0.037 + ph[1]) * 0.35 + sin(xd * 0.13 + ph[2]) * 0.2
                Wood.ui(v > 0 ? t.light : t.grain).withAlphaComponent(min(abs(v) * 90 / 255, 1)).setStroke()
                c.move(to: CGPoint(x: xd, y: 0)); c.addLine(to: CGPoint(x: xd, y: h)); c.strokePath()
            }
            for _ in 0..<Int(w) {
                let x0 = rnd.next() * w, y0 = rnd.next() * h * 0.6 - h * 0.2, len = h * (0.3 + rnd.next() * 0.9)
                let p = rnd.next() * 6.28, amp = 1.5 + rnd.next() * 3
                c.move(to: CGPoint(x: x0, y: y0))
                var y = y0
                while y < y0 + len { y += 20; c.addLine(to: CGPoint(x: x0 + sin(y * 0.005 + p) * amp, y: y)) }
                let darkLine = rnd.next() < 0.75
                let alpha = darkLine ? 25 + rnd.next() * 60 : 30 + rnd.next() * 50
                Wood.ui(darkLine ? t.grain : t.light).withAlphaComponent(alpha / 255).setStroke()
                c.setLineWidth(0.5 + rnd.next() * 1.6); c.strokePath()
            }
            for _ in 0..<2 {
                let cx = w * (0.25 + rnd.next() * 0.5), top = h * (0.1 + rnd.next() * 0.5)
                for j in 0..<7 {
                    let half = 10 + Double(j) * 9, apex = top + Double(j) * 26, bottom = apex + 420 + Double(j) * 30
                    c.move(to: CGPoint(x: cx - half * 2.2, y: bottom))
                    c.addCurve(to: CGPoint(x: cx, y: apex), control1: CGPoint(x: cx - half * 1.6, y: apex + 160),
                               control2: CGPoint(x: cx - half * 0.5, y: apex + 10))
                    c.addCurve(to: CGPoint(x: cx + half * 2.2, y: bottom), control1: CGPoint(x: cx + half * 0.5, y: apex + 10),
                               control2: CGPoint(x: cx + half * 1.6, y: apex + 160))
                    Wood.ui(t.grain).withAlphaComponent((30 + rnd.next() * 30) / 255).setStroke()
                    c.setLineWidth(1.2 + rnd.next()); c.strokePath()
                }
            }
            let colors = [UIColor.clear.cgColor, UIColor.clear.cgColor, Wood.ui(t.edge).cgColor] as CFArray
            if let g = CGGradient(colorsSpace: nil, colors: colors, locations: [0, 0.45, 1]) {
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
        mutating func next() -> Double {
            s = s &* 6364136223846793005 &+ 1442695040888963407
            return Double(s >> 11) / Double(UInt64(1) << 53)
        }
    }
}

/// 책장 보기면 [content](목록) 뒤에 원목 뒷벽, 위에 양옆 기둥. 뒷벽은 목록과 함께 굴러가지 않는다 — 벽은 서 있고 판과 책이 지나간다.
struct ShelfWall<Content: View>: View {
    @ViewBuilder var content: Content
    @Environment(\.colorScheme) private var scheme
    @State private var texture: UIImage?

    var body: some View {
        let wood = Wood.of(scheme)
        ZStack {
            Wood.color(wood.base)
            if let texture { Image(uiImage: texture).resizable() }
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
