import SwiftUI
import UIKit
import simd

/*
 * 책장 보기(안드로이드 `WoodShelf.kt` 0.39 · 0.44 의 짝, 구상안 10 확정). 뒷벽은 원목 결, 양옆에 기둥, 칸마다 위에 칸막이(판)를
 * 두고 그 앞면에 **그 칸의 이름**을 새긴다 — 이름이 책 아래에 있으면 어느 칸 것인지 헷갈린다(안드로이드 사용자 정정).
 * 이름 · 첫 표지는 격자 · 목록 보기와 같은 시작선(gutter) — 보기를 바꿔도 글자가 옆으로 움직이지 않는다.
 * 표지만 판 위에 세운다. 제목 · 진도를 얹으면 판 사이가 벌어져 책장으로 읽히지 않는다.
 */

/// 나무 한 벌. 밝은 테마는 내추럴 라이트 오크, 어두운 테마는 같은 결을 따뜻한 짙은 갈색으로 — 밤에 밝은 원목은 눈부시다. 값은
/// 안드로이드 그대로(`docs/design-handoff/README.md` "책장 원목 — 내추럴 라이트 오크", 0.62.1 사용자 결정). 0.62.0 의 꿀빛
/// 애쉬는 짙은 결 · 거의 검은 그늘 · 짙은 기둥 때문에 회색 기운이 돌아 "깔끔하지 않다" 고 했다 — 모든 색이 같은 따뜻한 색상(약
/// 36°)에서 밝기만 다르고, 그늘도 검정이 아니라 호박색으로 옅게. 밝은 나무 위 칸 이름은 짙은 갈색(흰 글자는 읽히지 않는다).
struct Wood: Sendable {
    let base, light, grain, edge, plankTop, plankFront, plankLow, post, label: UInt32
    let dark: Bool

    static let naturalOak = Wood(base: 0xFFD7AE73, light: 0xFFE8C893, grain: 0xFFA97A3E, edge: 0x558A5A2A, plankTop: 0xFFEBD0A4,
                                 plankFront: 0xFFC99B60, plankLow: 0xFFA87A42, post: 0xFF9C6E3A, label: 0xFF3A250F, dark: false)
    static let night = Wood(base: 0xFF5A3F1E, light: 0xFF7A5630, grain: 0xFF3A2610, edge: 0xCC1A0F05, plankTop: 0xFF8A6A3E,
                            plankFront: 0xFF5E4220, plankLow: 0xFF3E2A14, post: 0xFF3A2612, label: 0xFFF2E3C8, dark: true)

    static func of(_ scheme: ColorScheme) -> Wood { scheme == .dark ? .night : .naturalOak }

    /// 그늘 색(기둥 안쪽 · 판 밑): 밤은 검정, 밝은 나무는 호박색으로 옅게 — 검은 그늘이 회색 기운을 만들었다(안드로이드 0.62.1).
    var shade: Color { dark ? .black : Color(Wood.ui(0xFF3A2410)) }

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

    /// 결 그림의 판. 식 · 색 · 씨앗을 바꾸면 올린다 — 저장해 둔 옛 결이 새 판에 보이지 않게.
    nonisolated static let edition = "0.62.1-oak-11"

    /// 결 그림. 메모리 → 저장해 둔 파일 → 새로 그림. 0.62.1 의 결은 점 84만 개를 하나씩 셈해, 개발용 빌드에서는 몇 초 걸려 첫 실행에
    /// 나무결이 뒤늦게 떴다(실기기, 사용자). 한 번 그린 것은 Caches 에 PNG 로 두어 다음 실행부터는 바로 읽는다.
    func texture(_ wood: Wood) -> UIImage {
        if let image = done[wood.dark] { return image }
        let file = Self.file(wood)
        let image = UIImage(contentsOfFile: file.path) ?? {
            let drawn = Self.draw(wood)
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? drawn.pngData()?.write(to: file, options: .atomic)
            return drawn
        }()
        done[wood.dark] = image
        return image
    }

    /// 앱이 켜질 때 미리 그린다 — 서재가 책장 보기로 열리면 화면이 뜰 때쯤 결이 있다. 다른 보기면 하지 않는다(쓰지 않을 셈).
    nonisolated static func prewarm() {
        guard UserDefaults.standard.string(forKey: "library.layout") == LibraryLayout.shelf.rawValue else { return }
        let wood = UIScreen.main.traitCollection.userInterfaceStyle == .dark ? Wood.night : Wood.naturalOak
        Task.detached(priority: .userInitiated) { _ = await WoodTextures.shared.texture(wood) }
    }

    private nonisolated static func file(_ wood: Wood) -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("wood", isDirectory: true)
            .appendingPathComponent("\(edition)-\(wood.dark ? "night" : "day").png")
    }

    /// 결 그리기(안드로이드 0.62.1 `woodTexture` 와 같은 식). 결을 선으로 긋지 않고 **나무를 잘라 본다** — 절차적 나무결의 표준
    /// 방식. 선으로 그은 아치는 "인위적" 이었다(사용자).
    /// - 나이테는 줄기 둘레의 동심 원통. 판 면이 줄기와 조금 비스듬히 지나가 원통이 잘린 자리가 위로 선 아치(널결), 양옆은 곧은결.
    ///   판이 심을 지나지 않게 깊이를 둔다 — 지나면 닫힌 타원(옹이처럼)이 생긴다.
    /// - **통판 한 장**(이음매 · 판마다 밝기 차이 없음). 줄기 가운데 흔들림 · 반지름 · 나이테 간격에 펄린 잡음.
    /// - 나이테 하나 = 밝은 봄 나무 + 고리 끝의 옅은 여름 나무 띠, 나이테마다 짙기가 다르다. 세로로 긴 섬유, 넓은 밝기 물결.
    /// 씨앗 11 로 열 때마다 같은 결(난수 생성기가 자바와 달라 결의 자리는 안드로이드와 똑같지 않다 — 식 · 모양은 같다).
    nonisolated static func draw(_ t: Wood, w: Int = 600, h: Int = 1400) -> UIImage {
        var rnd = Seeded(11)
        let noise = Perlin(&rnd)
        func rgb(_ v: UInt32) -> SIMD3<Float> {
            SIMD3(Float((v >> 16) & 0xFF), Float((v >> 8) & 0xFF), Float(v & 0xFF)) / 255
        }
        func mix(_ a: SIMD3<Float>, _ b: SIMD3<Float>, _ k: Float) -> SIMD3<Float> { a + (b - a) * k }
        let base = rgb(t.base)
        let early = mix(base, rgb(t.light), 0.45), late = mix(base, rgb(t.grain), 0.6)
        let pith = Float(w) * 0.44, depth: Float = 140, tilt: Float = 0.035, spacing: Float = 6
        let off = Float(rnd.next()) * 100
        let fh = Float(h) * stretch
        var pixels = [UInt8](repeating: 255, count: w * h * 4)
        for y in 0..<h {
            // 그림은 벽 높이로 세로 2배쯤 늘어난다 — 결을 그만큼 촘촘히 그려 늘어난 뒤 사진의 결 간격이 되게.
            let yy = Float(y) * stretch
            let sway = 40 * noise.fbm(off * 0.7 + yy * 0.00175, 2.2, 3)
            let d = depth + (fh - yy) * tilt + 30 * noise.fbm(off + yy * 0.0015, off * 0.3 + 0.5, 3)
            for x in 0..<w {
                let fx = Float(x)
                let dx = fx - pith - sway
                let r = (dx * dx + d * d).squareRoot() + 12 * noise.fbm(off + fx * 0.006, yy * 0.00125, 3)
                    + 1.6 * noise.fbm(off + fx * 0.025, yy * 0.005, 2)
                let ring = r / spacing + 3 * noise.fbm(off + r * 0.008, 7.3, 3)
                let f = ring - ring.rounded(.down)
                let latewood = min(max((f - 0.55) / 0.30, 0), 1) * min(max((1 - f) / 0.10, 0), 1)
                let band = 0.55 + 0.45 * noise.fbm(off + ring.rounded(.down) * 0.37, 3.1, 1)
                let fiber = noise.fbm(off + fx * 0.55, yy * 0.005, 2)
                let amount = min(max(powf(latewood, 0.8) * 0.5 * band + fiber * 0.18 + 0.10 + 0.08 * noise.fbm(off + fx * 0.005, yy * 0.001, 2), 0), 1)
                let light = 1 + 0.06 * noise.fbm(fx * 0.002 + off * 0.01, yy * 0.00075, 2)
                let c = simd_clamp(mix(early, late, amount) * light, SIMD3(repeating: 0), SIMD3(repeating: 1))
                let i = (y * w + x) * 4
                pixels[i] = UInt8(c.x * 255); pixels[i + 1] = UInt8(c.y * 255); pixels[i + 2] = UInt8(c.z * 255)
            }
        }
        let space = CGColorSpaceCreateDeviceRGB()
        guard let ctx = CGContext(data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return UIImage() }
        // 가장자리 그늘(호박색으로 옅게, 밤은 짙게). CGContext 는 아래가 0 — 가운데 높이를 위에서 45% 로 맞춘다.
        let colors = [UIColor.clear.cgColor, UIColor.clear.cgColor, Wood.ui(t.edge).cgColor] as CFArray
        if let g = CGGradient(colorsSpace: nil, colors: colors, locations: [0, 0.5, 1]) {
            let center = CGPoint(x: CGFloat(w) / 2, y: CGFloat(h) * 0.55)
            ctx.drawRadialGradient(g, startCenter: center, startRadius: 0, endCenter: center, endRadius: CGFloat(max(w, h)) * 0.75,
                                   options: .drawsAfterEndLocation)
        }
        guard let image = ctx.makeImage() else { return UIImage() }
        return UIImage(cgImage: image)
    }

    /// 결 그림이 벽에 늘어나는 세로 배율(어림, 안드로이드 `STRETCH`).
    nonisolated static let stretch: Float = 2

    /// 펄린 잡음(2차원, -1~1 언저리). 씨앗으로 섞은 순열 — 같은 씨앗이면 같은 결.
    struct Perlin: Sendable {
        private var perm = [Int](repeating: 0, count: 512)
        private var gx = [Float](repeating: 0, count: 256)
        private var gy = [Float](repeating: 0, count: 256)

        init(_ rnd: inout Seeded) {
            var p = Array(0..<256)
            for i in stride(from: 255, to: 0, by: -1) { p.swapAt(i, Int(rnd.next() * CGFloat(i + 1))) }
            for i in 0..<512 { perm[i] = p[i & 255] }
            for i in 0..<256 {
                let a = Float(rnd.next()) * 6.2831855
                gx[i] = cos(a); gy[i] = sin(a)
            }
        }

        private func fade(_ t: Float) -> Float { t * t * t * (t * (t * 6 - 15) + 10) }

        private func grad(_ ix: Int, _ iy: Int, _ dx: Float, _ dy: Float) -> Float {
            let g = perm[perm[ix] + iy]
            return gx[g] * dx + gy[g] * dy
        }

        func at(_ x: Float, _ y: Float) -> Float {
            let fx = x.rounded(.down), fy = y.rounded(.down)
            let xi = Int(fx) & 255, yi = Int(fy) & 255
            let xf = x - fx, yf = y - fy
            let u = fade(xf), v = fade(yf)
            let a = grad(xi, yi, xf, yf), b = grad(xi + 1, yi, xf - 1, yf)
            let c = grad(xi, yi + 1, xf, yf - 1), d = grad(xi + 1, yi + 1, xf - 1, yf - 1)
            let top = a + (b - a) * u
            return top + ((c + (d - c) * u) - top) * v
        }

        /// 겹친 잡음(옥타브 [octaves]). 큰 물결에 잔물결이 얹힌다.
        func fbm(_ x: Float, _ y: Float, _ octaves: Int) -> Float {
            var sum: Float = 0, amp: Float = 1, freq: Float = 1, norm: Float = 0
            for _ in 0..<octaves { sum += at(x * freq, y * freq) * amp; norm += amp; amp *= 0.5; freq *= 2 }
            return sum / norm
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
                // 안쪽 그늘: 밝은 나무에서는 옅게 — 짙은 그늘이 회색 기운을 만들었다(안드로이드 0.62.1).
                LinearGradient(colors: [Wood.color(wood.post), Wood.color(wood.plankLow), Color.black.opacity(wood.dark ? 0.4 : 0.13)],
                               startPoint: .leading, endPoint: .trailing).frame(width: ShelfMetrics.post)
                Spacer(minLength: 0)
                LinearGradient(colors: [Color.black.opacity(wood.dark ? 0.4 : 0.13), Wood.color(wood.plankLow), Wood.color(wood.post)],
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
/// 당기는 동안에는 테두리가 그만큼 두꺼워진다(ShelfPull.stretch, 최대 10pt) — 테두리와 첫 판 사이가 벌어지지 않고 틀이 닫힌 채
/// 책장 전체가 조금 내려앉는다.
struct ShelfCrown: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.shelfPull) private var pull
    var body: some View {
        let wood = Wood.of(scheme)
        VStack(spacing: 0) {
            LinearGradient(colors: [Wood.color(wood.post), Wood.color(wood.plankLow)], startPoint: .top, endPoint: .bottom)
                .frame(height: ShelfMetrics.crown + pull.stretch)
            Color.black.opacity(0.33).frame(height: 1)
        }
        // 스크롤 안쪽에 두어야 감싼 UIScrollView 를 찾는다.
        .background(ShelfPullProbe(link: pull))
        .accessibilityHidden(true)
    }
}

/// 책장 보기의 당겨서 새로고침. 시스템 것(.refreshable)은 목록 전체를 손가락만큼 끌어내려, 맨 위 테두리 위로 빈 나무 벽이
/// 드러나고 고정된 양옆 기둥과 틀이 어긋났다(실기기, 사용자: 심각). 그렇다고 아예 막으면 새로고침이 없다. 그래서:
/// - 목록은 튕기지 않는다. 당김은 손가락의 이동으로만 잰다(ShelfPullProbe).
/// - 책장은 손가락을 따라가지 않고 고무줄처럼 조금만 내려앉는다: f(x) = x·d·c / (d + c·x), c = 0.55(UIScrollView 의 고무줄
///   상수로 알려진 값), d = 10pt 가 끝값. 문턱(64pt)에서 약 8pt — 눈에 띄되 벽이 드러나지 않는 정도.
/// - 당긴 정도와 새로고침 중임은 위에서 내려오는 작은 원판이 보인다(안드로이드 SwipeRefreshLayout 처럼 내용 대신 표시가 움직인다).
///   문턱은 그 기본값 64dp 와 같은 64pt, 넘는 순간 가벼운 진동(시스템 새로고침과 같다).
/// - 놓으면 용수철로 돌아온다. 새로고침이 금방 끝나도 원판은 0.6초 돈다 — 그보다 짧으면 깜박임으로만 보여 됐는지 모른다.
struct ShelfPull {
    static let trigger: CGFloat = 64
    static let maxStretch: CGFloat = 10

    var stretch: CGFloat = 0
    var changed: (CGFloat) -> Void = { _ in }
    var released: (CGFloat) -> Void = { _ in }

    static func stretch(_ x: CGFloat) -> CGFloat {
        guard x > 0 else { return 0 }
        let c: CGFloat = 0.55, d = maxStretch
        return x * d * c / (d + c * x)
    }
}

private struct ShelfPullKey: EnvironmentKey { static let defaultValue = ShelfPull() }
extension EnvironmentValues {
    var shelfPull: ShelfPull {
        get { self[ShelfPullKey.self] }
        set { self[ShelfPullKey.self] = newValue }
    }
}

/// 책장 목록을 감싸 당겨서 새로고침을 단다. 안의 ShelfCrown 이 당김을 알려 온다.
struct ShelfRefresh<Content: View>: View {
    let action: () async -> Void
    @ViewBuilder let content: Content
    @State private var pull: CGFloat = 0
    @State private var refreshing = false
    private let haptic = UIImpactFeedbackGenerator(style: .light)

    var body: some View {
        content
            .environment(\.shelfPull, ShelfPull(stretch: ShelfPull.stretch(pull), changed: changed, released: released))
            .overlay(alignment: .top) {
                ShelfRefreshDisc(progress: pull / ShelfPull.trigger, spinning: refreshing)
                    .offset(y: ShelfMetrics.crown + discTravel - 44)
                    .opacity(refreshing ? 1 : min(1, pull / 24))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    // 원판은 책장 위(도구 줄)로 넘어가지 않는다.
                    .clipped()
                    .allowsHitTesting(false)
            }
    }

    /// 원판은 문턱까지 손가락과 같이, 넘으면 4분의 1만 — 문턱을 넘었음을 손이 느낀다.
    private var discTravel: CGFloat {
        if refreshing { return ShelfPull.trigger }
        return pull <= ShelfPull.trigger ? pull : min(ShelfPull.trigger + (pull - ShelfPull.trigger) * 0.25, ShelfPull.trigger + 16)
    }

    private func changed(_ p: CGFloat) {
        if !refreshing, pull < ShelfPull.trigger, p >= ShelfPull.trigger { haptic.impactOccurred() }
        pull = p
    }

    private func released(_ p: CGFloat) {
        if p >= ShelfPull.trigger, !refreshing {
            withAnimation(.easeOut(duration: 0.2)) { refreshing = true }
            Task {
                let start = Date()
                await action()
                let rest = 0.6 - Date().timeIntervalSince(start)
                if rest > 0 { try? await Task.sleep(for: .seconds(rest)) }
                withAnimation(.easeOut(duration: 0.25)) { refreshing = false }
            }
        }
        // 감쇠 0.6 — 한 번 살짝 출렁이고 선다(사용자: 인지할 수 있는 정도의 흔들림).
        withAnimation(.spring(response: 0.38, dampingFraction: 0.6)) { pull = 0 }
    }
}

/// 당김 표시 원판: 당긴 만큼 호가 차오르고, 새로고침 중에는 돈다.
private struct ShelfRefreshDisc: View {
    let progress: CGFloat
    let spinning: Bool

    var body: some View {
        TimelineView(.animation(paused: !spinning)) { t in
            let turn = spinning ? t.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1) * 360 : progress * 200
            ZStack {
                Circle().fill(CpColor.surface).shadow(color: .black.opacity(0.28), radius: 3, y: 1)
                Circle()
                    .trim(from: 0, to: spinning ? 0.72 : min(progress, 1) * 0.8)
                    .stroke(CpColor.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(turn - 90))
                    .padding(9)
            }
            .frame(width: 36, height: 36)
        }
        .accessibilityHidden(true)
    }
}

/// 책장 목록의 UIScrollView 를 찾아 튕김을 끄고, 맨 위에서 아래로 끄는 손가락의 이동을 당김으로 알린다.
/// SwiftUI 에는 튕김만 끄는 수단이 없다(scrollBounceBehavior 는 내용이 화면보다 길면 듣지 않는다 — 책장은 빈 칸으로 늘 길다).
struct ShelfPullProbe: UIViewRepresentable {
    let link: ShelfPull

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UIView {
        let probe = Probe()
        probe.coordinator = context.coordinator
        return probe
    }
    func updateUIView(_ uiView: UIView, context: Context) { context.coordinator.link = link }

    final class Coordinator: NSObject {
        var link = ShelfPull()
        weak var scroll: UIScrollView?
        /// 맨 위에 닿았을 때의 손가락 위치. 굴리다가 맨 위에 닿아도 그때부터 당김을 센다(시스템과 같다).
        private var base: CGFloat?
        private var last: CGFloat = 0

        func attach(_ s: UIScrollView) {
            guard scroll !== s else { return }
            scroll?.panGestureRecognizer.removeTarget(self, action: #selector(pan))
            scroll = s
            s.bounces = false
            s.alwaysBounceVertical = false
            s.panGestureRecognizer.addTarget(self, action: #selector(pan))
        }

        @objc private func pan(_ g: UIPanGestureRecognizer) {
            guard let s = scroll else { return }
            let atTop = s.contentOffset.y <= -s.adjustedContentInset.top + 0.5
            let y = g.translation(in: s).y
            switch g.state {
            case .began:
                base = nil
            case .changed:
                if !atTop { base = nil }
                else if base == nil { base = y }
                let p = base.map { max(0, y - $0) } ?? 0
                if p != last { last = p; link.changed(p) }
            default:
                if last > 0 || base != nil { link.released(last) }
                base = nil
                last = 0
            }
        }
    }

    private final class Probe: UIView {
        weak var coordinator: Coordinator?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            var view = superview
            while let v = view, !(v is UIScrollView) { view = v.superview }
            if let scroll = view as? UIScrollView { coordinator?.attach(scroll) }
        }
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
                // 그림자 글자: 짙은 글자(밝은 나무)는 빛 그림자, 밝은 글자(밤)는 짙은 그림자 — 새긴 글자처럼(안드로이드 0.62.1).
                // 화면 읽기가 칸 이름을 두 번 읽지 않게 숨긴다.
                Text(label ?? " ").font(CpFont.label)
                    .foregroundStyle((wood.dark ? Color.black.opacity(0.53) : Color.white.opacity(0.33)).opacity(label == nil ? 0 : 1))
                    .offset(y: 1).accessibilityHidden(true)
                if let label {
                    Text(label).font(CpFont.label).foregroundStyle(Wood.color(wood.label)).accessibilityAddTraits(.isHeader)
                }
            }
            .padding(.horizontal, CpMetrics.gutter)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(LinearGradient(colors: [Wood.color(wood.plankFront), Wood.color(wood.plankLow)], startPoint: .top, endPoint: .bottom))
            (wood.dark ? Color.black.opacity(0.33) : Wood.color(wood.plankLow).opacity(0.8)).frame(height: 1)
        }
    }
}

/// 판 밑그늘: 아래 칸 뒷벽 위쪽. 칸의 높이를 차지하지 않고 겹친다 — 표지 윗부분에도 그늘이 진다.
struct UnderBoardShadow: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        // 밝은 나무에서는 호박색으로 옅게 — 검은 그늘이 회색 기운을 만들었다(안드로이드 0.62.1).
        let wood = Wood.of(scheme)
        let colors: [Color] = wood.dark ? [.black.opacity(0.6), .black.opacity(0.2), .clear]
            : [wood.shade.opacity(0.33), wood.shade.opacity(0.1), wood.shade.opacity(0)]
        LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
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
