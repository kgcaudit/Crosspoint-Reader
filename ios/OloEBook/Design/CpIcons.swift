import SwiftUI

/// 선 아이콘(안드로이드 `CpIcons.kt` · 계열 메뉴 그림 `ic_menu_*.xml` 에서 옮김). 24 격자, 선 굵기 1.8, 둥근 끝 · 둥근 모서리.
///
/// 경로 문자열은 원본을 **그대로** 붙여 넣는다 — 손으로 다시 그리면 OLO 앱끼리 모양이 조금씩 달라져 한 식구로 읽히지
/// 않는다(디자인 인계 §5). 원본이 바뀌면 문자열만 바꿔 넣는다. SF Symbols 를 쓰지 않는 것도 같은 까닭이다.
struct CpIcon: Equatable {
    struct Layer: Equatable {
        let d: String
        let fill: Bool
        let stroke: CGFloat?
        /// 짝홀 채움(안드로이드 `fillType="evenOdd"`). 계열 메뉴 그림의 고리는 안팎 두 경로로 그려져, 빠뜨리면 고리가 덩어리로 칠해진다.
        var evenOdd = false
    }

    let layers: [Layer]

    static func line(_ d: String, width: CGFloat = 1.8) -> CpIcon { CpIcon(layers: [Layer(d: d, fill: false, stroke: width)]) }
    static func fill(_ d: String, stroke: CGFloat? = 1.8) -> CpIcon { CpIcon(layers: [Layer(d: d, fill: true, stroke: stroke)]) }

    static let back = line("M15 5 L8 12 L15 19")
    static let forward = line("M9 5 L16 12 L9 19")
    static let plus = line("M12 5 V19 M5 12 H19")
    static let minus = line("M5 12 H19")
    static let folder = line("M3.5 7.5 V18 A1.5 1.5 0 0 0 5 19.5 H19 A1.5 1.5 0 0 0 20.5 18 V9.5 A1.5 1.5 0 0 0 19 8 H11.5 L9.5 5.5 H5 A1.5 1.5 0 0 0 3.5 7 Z")
    static let book = line("M4 5.5 C6.5 4.5 9.5 4.5 12 6 C14.5 4.5 17.5 4.5 20 5.5 V18.5 C17.5 17.5 14.5 17.5 12 19 C9.5 17.5 6.5 17.5 4 18.5 Z M12 6 V19")
    static let text = line("M6 3.5 H14.5 L18.5 7.5 V20.5 H6 Z M14.5 3.5 V7.5 H18.5 M9 11 H15.5 M9 14 H15.5 M9 17 H13")
    static let bookmark = line("M7 4 H17 V20 L12 16 L7 20 Z")
    static let bookmarkFilled = fill("M7 4 H17 V20 L12 16 L7 20 Z")
    static let toc = line("M8.5 7 H19 M8.5 12 H19 M8.5 17 H19 M5 7 H5.1 M5 12 H5.1 M5 17 H5.1")
    static let close = line("M6 6 L18 18 M18 6 L6 18")
    static let note = line("M5 4 H19 V15 L14 20 H5 Z M14 20 V15 H19 M8.5 8.5 H15.5 M8.5 11.5 H13")
    static let headphones = line("M4.5 15 V12 A7.5 7.5 0 0 1 19.5 12 V15 M4.5 14 H7.5 V20 H5.5 A1 1 0 0 1 4.5 19 Z M19.5 14 H16.5 V20 H18.5 A1 1 0 0 0 19.5 19 Z")
    static let info = line("M12 3.5 A8.5 8.5 0 1 0 12 20.5 A8.5 8.5 0 1 0 12 3.5 Z M12 11 V16.5 M12 7.9 V8")
    /// 보기 설정: 조절 막대 셋(0.29.0). 구상안의 "가가" 자리 — 안드로이드는 이 그림으로 바꿨다.
    static let view = line("M4 7 H20 M4 12 H20 M4 17 H20 M9 5 V9 M15 10 V14 M7.5 15 V19")
    static let more = fill("M10.4 5.5 A1.6 1.6 0 1 0 13.6 5.5 A1.6 1.6 0 1 0 10.4 5.5 Z M10.4 12 A1.6 1.6 0 1 0 13.6 12 A1.6 1.6 0 1 0 10.4 12 Z M10.4 18.5 A1.6 1.6 0 1 0 13.6 18.5 A1.6 1.6 0 1 0 10.4 18.5 Z")
    static let chevronDown = line("M7 10 L12 15 L17 10")

    // 계열 메뉴 그림(OLO-Design 생성물 그대로).
    static let refresh = CpIcon(layers: [
        Layer(d: "M15 9L21 3V9H15Z", fill: true, stroke: nil, evenOdd: true),
        Layer(d: "M12.0086 4.5C11.0235 4.49888 10.048 4.69207 9.13767 5.0685C8.22739 5.44494 7.4003 5.99724 6.70377 6.69377C6.00724 7.39031 5.45494 8.21739 5.0785 9.12766C4.70206 10.0379 4.50889 11.0135 4.51001 11.9986L4.51002 12C4.51002 12.9843 4.70404 13.959 5.08099 14.8683C5.45794 15.7776 6.0104 16.6037 6.70681 17.2993C7.40322 17.9949 8.22994 18.5464 9.13966 18.9223C10.0494 19.2982 11.0243 19.4911 12.0086 19.49H12.01C13.9965 19.49 15.9016 18.7009 17.3062 17.2962C18.7109 15.8916 19.5 13.9865 19.5 12H22C22 14.6495 20.9475 17.1905 19.074 19.064C17.2009 20.9371 14.6604 21.9896 12.0114 21.99M12.0102 2.00001C10.6968 1.99867 9.396 2.25633 8.18229 2.75826C6.96818 3.26034 5.86503 3.99699 4.93601 4.92601C4.00699 5.85502 3.27034 6.95816 2.76825 8.17228C2.26625 9.38617 2.00861 10.6871 2.01002 12.0007C2.01002 12.0005 2.01002 12.0002 2.01002 12L3.26002 12L2.01002 12.0014C2.01002 12.0012 2.01002 12.001 2.01002 12.0007C2.01011 13.3134 2.2689 14.6131 2.77156 15.8257C3.27432 17.0384 4.01118 18.1402 4.94005 19.068C5.86893 19.9959 6.97158 20.7315 8.18494 21.2328C9.39786 21.734 10.6976 21.9913 12.01 21.99M12.0102 2.00001C13.3222 1.99897 14.6215 2.25689 15.8336 2.75901C17.0462 3.26129 18.1476 3.99804 19.0747 4.92698L17.3053 6.69303C16.6106 5.99702 15.7854 5.44502 14.8769 5.06869C13.9684 4.69235 12.9945 4.4991 12.0112 4.50001L12.0086 4.5", fill: true, stroke: nil, evenOdd: true),
    ])
    static let search = CpIcon(layers: [
        Layer(d: "M10.5 4.5C12.0913 4.5 13.6174 5.13214 14.7426 6.25735C15.8678 7.38257 16.5 8.9087 16.5 10.5C16.5 12.0913 15.8678 13.6174 14.7426 14.7426C13.6174 15.8679 12.0913 16.5 10.5 16.5C8.9087 16.5 7.38257 15.8679 6.25735 14.7426C5.13214 13.6174 4.5 12.0913 4.5 10.5C4.5 8.9087 5.13214 7.38257 6.25735 6.25735C7.38257 5.13214 8.9087 4.5 10.5 4.5ZM10.5 2C8.24647 2.00265 6.08599 2.89904 4.49251 4.49252C2.89902 6.08601 2.00265 8.24647 2 10.5C2 11.6162 2.21985 12.7215 2.64702 13.7528C3.07418 14.7841 3.7003 15.7211 4.48959 16.5104C5.27889 17.2997 6.21592 17.9258 7.24719 18.353C8.27846 18.7801 9.38376 19 10.5 19C11.6166 19.0013 12.7225 18.7824 13.7544 18.3557C14.7862 17.929 15.7238 17.3029 16.5133 16.5133C17.3029 15.7238 17.929 14.7862 18.3557 13.7544C18.7824 12.7225 19.0013 11.6166 19 10.5C19 8.24566 18.1045 6.08365 16.5104 4.48959C14.9163 2.89553 12.7543 2 10.5 2Z", fill: true, stroke: nil, evenOdd: true),
        Layer(d: "M16.4852 14.7068L21.8852 20.1168L20.1158 21.8829L14.7158 16.4729L16.4852 14.7068Z", fill: true, stroke: nil, evenOdd: true),
    ])
    static let grid = CpIcon(layers: [
        "M5.6,4.2 L9.6,4.2 Q11,4.2 11,5.6 L11,9.6 Q11,11 9.6,11 L5.6,11 Q4.2,11 4.2,9.6 L4.2,5.6 Q4.2,4.2 5.6,4.2 Z",
        "M14.4,4.2 L18.4,4.2 Q19.8,4.2 19.8,5.6 L19.8,9.6 Q19.8,11 18.4,11 L14.4,11 Q13,11 13,9.6 L13,5.6 Q13,4.2 14.4,4.2 Z",
        "M5.6,13 L9.6,13 Q11,13 11,14.4 L11,18.4 Q11,19.8 9.6,19.8 L5.6,19.8 Q4.2,19.8 4.2,18.4 L4.2,14.4 Q4.2,13 5.6,13 Z",
        "M14.4,13 L18.4,13 Q19.8,13 19.8,14.4 L19.8,18.4 Q19.8,19.8 18.4,19.8 L14.4,19.8 Q13,19.8 13,18.4 L13,14.4 Q13,13 14.4,13 Z",
    ].map { Layer(d: $0, fill: false, stroke: 2.0) })
    static let rows = CpIcon(layers: [
        Layer(d: "M4,5.5 h2.8 v2.8 h-2.8 Z", fill: true, stroke: nil),
        Layer(d: "M4,10.6 h2.8 v2.8 h-2.8 Z", fill: true, stroke: nil),
        Layer(d: "M4,15.7 h2.8 v2.8 h-2.8 Z", fill: true, stroke: nil),
        Layer(d: "M9,6.9 L20,6.9", fill: false, stroke: 2.4),
        Layer(d: "M9,12 L20,12", fill: false, stroke: 2.4),
        Layer(d: "M9,17.1 L20,17.1", fill: false, stroke: 2.4),
    ])
}

/// 아이콘 한 개를 그린다. 기본 24pt, 색은 줄 글자색.
struct CpIconView: View {
    let icon: CpIcon
    var size: CGFloat = 24
    var color: Color = CpColor.text

    var body: some View {
        Canvas { context, canvas in
            let scale = canvas.width / 24
            let transform = CGAffineTransform(scaleX: scale, y: scale)
            for layer in icon.layers {
                let path = SvgPath.parse(layer.d).applying(transform)
                if layer.fill { context.fill(path, with: .color(color), style: FillStyle(eoFill: layer.evenOdd)) }
                if let width = layer.stroke {
                    context.stroke(path, with: .color(color),
                                   style: StrokeStyle(lineWidth: width * scale, lineCap: .round, lineJoin: .round))
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// SVG/안드로이드 벡터의 경로 글을 `Path` 로(M L H V C Q A Z 와 그 소문자). 원(rx = ry)이 아닌 호도 받는다.
enum SvgPath {
    static func parse(_ d: String) -> Path {
        var path = Path()
        var tokens = Tokens(d)
        var current = CGPoint.zero
        var start = CGPoint.zero
        var command: Character = "M"
        while let next = tokens.peekCommand() ?? (tokens.hasNumber ? command : nil) {
            if tokens.peekCommand() != nil { tokens.skipCommand() }
            command = next
            let relative = command.isLowercase
            func point() -> CGPoint {
                let x = tokens.number(), y = tokens.number()
                return relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
            }
            switch command.uppercased().first! {
            case "M":
                current = point(); start = current
                path.move(to: current)
                command = relative ? "l" : "L" // 이어지는 좌표는 선이다.
            case "L":
                current = point(); path.addLine(to: current)
            case "H":
                let x = tokens.number()
                current.x = relative ? current.x + x : x
                path.addLine(to: current)
            case "V":
                let y = tokens.number()
                current.y = relative ? current.y + y : y
                path.addLine(to: current)
            case "C":
                let c1 = point(), c2 = point(), end = point()
                path.addCurve(to: end, control1: c1, control2: c2)
                current = end
            case "Q":
                let c = point(), end = point()
                path.addQuadCurve(to: end, control: c)
                current = end
            case "A":
                let rx = tokens.number(), ry = tokens.number(), rotation = tokens.number()
                let large = tokens.number() != 0, sweep = tokens.number() != 0
                let end = point()
                addArc(&path, from: current, to: end, rx: rx, ry: ry, rotation: rotation, large: large, sweep: sweep)
                current = end
            case "Z":
                path.closeSubpath()
                current = start
            default:
                return path
            }
        }
        return path
    }

    /// 끝점 표기의 호를 3차 곡선으로(SVG 1.1 부록 F.6.5). `Path.addArc` 의 시계 방향 뜻은 뒤집힌 좌표에서 헷갈려 쓰지 않는다.
    private static func addArc(_ path: inout Path, from p0: CGPoint, to p1: CGPoint, rx: CGFloat, ry: CGFloat,
                               rotation: CGFloat, large: Bool, sweep: Bool) {
        if p0 == p1 { return }
        var rx = abs(rx), ry = abs(ry)
        if rx == 0 || ry == 0 { path.addLine(to: p1); return }
        let phi = rotation * .pi / 180
        let cosPhi = cos(phi), sinPhi = sin(phi)
        let dx = (p0.x - p1.x) / 2, dy = (p0.y - p1.y) / 2
        let x1 = cosPhi * dx + sinPhi * dy, y1 = -sinPhi * dx + cosPhi * dy
        let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if lambda > 1 { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        let num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        let den = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var coef = sqrt(max(0, num / den))
        if large == sweep { coef = -coef }
        let cx1 = coef * rx * y1 / ry, cy1 = -coef * ry * x1 / rx
        let cx = cosPhi * cx1 - sinPhi * cy1 + (p0.x + p1.x) / 2
        let cy = sinPhi * cx1 + cosPhi * cy1 + (p0.y + p1.y) / 2
        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        let theta1 = angle(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry)
        var delta = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
        if !sweep && delta > 0 { delta -= 2 * .pi }
        if sweep && delta < 0 { delta += 2 * .pi }
        let segments = max(1, Int(ceil(abs(delta) / (.pi / 2))))
        let step = delta / CGFloat(segments)
        let k = 4.0 / 3.0 * tan(step / 4)
        func onEllipse(_ t: CGFloat) -> (CGPoint, CGPoint) {
            let x = rx * cos(t), y = ry * sin(t)
            let tx = -rx * sin(t), ty = ry * cos(t)
            let p = CGPoint(x: cx + cosPhi * x - sinPhi * y, y: cy + sinPhi * x + cosPhi * y)
            let d = CGPoint(x: cosPhi * tx - sinPhi * ty, y: sinPhi * tx + cosPhi * ty)
            return (p, d)
        }
        var t = theta1
        for _ in 0..<segments {
            let (a, da) = onEllipse(t)
            let (b, db) = onEllipse(t + step)
            path.addCurve(to: b, control1: CGPoint(x: a.x + k * da.x, y: a.y + k * da.y),
                          control2: CGPoint(x: b.x - k * db.x, y: b.y - k * db.y))
            t += step
        }
    }

    private struct Tokens {
        let chars: [Character]
        var i = 0
        init(_ s: String) { chars = Array(s) }

        mutating func skipSeparators() {
            while i < chars.count, chars[i] == " " || chars[i] == "," || chars[i] == "\n" || chars[i] == "\t" { i += 1 }
        }

        mutating func peekCommand() -> Character? {
            skipSeparators()
            guard i < chars.count, chars[i].isLetter, chars[i] != "e", chars[i] != "E" else { return nil }
            return chars[i]
        }

        mutating func skipCommand() { i += 1 }

        var hasNumber: Bool {
            mutating get {
                skipSeparators()
                guard i < chars.count else { return false }
                let c = chars[i]
                return c.isNumber || c == "-" || c == "+" || c == "."
            }
        }

        mutating func number() -> CGFloat {
            skipSeparators()
            var s = ""
            var seenDot = false, seenExp = false
            while i < chars.count {
                let c = chars[i]
                if c.isNumber {
                    s.append(c)
                } else if (c == "-" || c == "+") && (s.isEmpty || s.last == "e" || s.last == "E") {
                    s.append(c)
                } else if c == "." && !seenDot && !seenExp {
                    // "4.5.5" 처럼 붙여 쓴 수: 두 번째 점은 다음 수의 시작이다.
                    seenDot = true; s.append(c)
                } else if (c == "e" || c == "E") && !seenExp && !s.isEmpty {
                    seenExp = true; s.append(c)
                } else {
                    break
                }
                i += 1
            }
            return CGFloat(Double(s) ?? 0)
        }
    }
}
