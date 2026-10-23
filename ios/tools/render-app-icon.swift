import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// 안드로이드 아이콘 생성물(벡터 XML: M/L/Z 다각형 + fillColor/fillAlpha)을 iOS 아이콘 PNG 로.
// 쓰기: swiftc -O render-app-icon.swift -o /tmp/r && /tmp/r <foreground.xml> <out.png> <size> <crop> <light|dark|tinted>
// crop 은 512 격자에서 보이는 가운데 폭 — 안드로이드 런처는 108 격자 중 72 만 보이므로 341.33(구상안 49 · 1A, 홈 화면에서 같은 크기).
// 밝게 · 어둡게는 투명도 없이 뽑는다 — 앱 스토어가 투명도가 있는 아이콘을 받지 않는다. 물들임만 투명 바탕(시스템이 밝기로 칠한다).
let a = CommandLine.arguments
let xml = try! String(contentsOfFile: a[1], encoding: .utf8)
let out = a[2]; let size = Int(a[3])!; let crop = Double(a[4])!; let mode = a[5]

struct P { var points: [[CGPoint]]; var alpha: Double }
var paths: [P] = []
let re = try! NSRegularExpression(pattern: "fillAlpha=\"([0-9.]+)\"\\s+android:pathData=\"([^\"]+)\"")
for m in re.matches(in: xml, range: NSRange(xml.startIndex..., in: xml)) {
    let alpha = Double(xml[Range(m.range(at: 1), in: xml)!])!
    let d = String(xml[Range(m.range(at: 2), in: xml)!])
    var polys: [[CGPoint]] = []; var cur: [CGPoint] = []
    let tok = try! NSRegularExpression(pattern: "([MLZ])\\s*([-0-9.]+)?,?([-0-9.]+)?")
    for t in tok.matches(in: d, range: NSRange(d.startIndex..., in: d)) {
        let c = d[Range(t.range(at: 1), in: d)!]
        if c == "Z" { if !cur.isEmpty { polys.append(cur); cur = [] }; continue }
        if c == "M", !cur.isEmpty { polys.append(cur); cur = [] }
        let x = Double(d[Range(t.range(at: 2), in: d)!])!, y = Double(d[Range(t.range(at: 3), in: d)!])!
        cur.append(CGPoint(x: x, y: y))
    }
    if !cur.isEmpty { polys.append(cur) }
    paths.append(P(points: polys, alpha: alpha))
}

let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                    bitmapInfo: (mode == "tinted" ? CGImageAlphaInfo.premultipliedLast : CGImageAlphaInfo.noneSkipLast).rawValue)!
// 512 격자 → 그림. 위가 0 인 좌표로.
let s = Double(size) / crop
ctx.translateBy(x: 0, y: CGFloat(size)); ctx.scaleBy(x: s, y: -s)
ctx.translateBy(x: -(256 - crop / 2), y: -(256 - crop / 2))
func c(_ hex: UInt32, _ a: Double = 1) -> CGColor {
    CGColor(colorSpace: cs, components: [CGFloat((hex >> 16) & 255) / 255, CGFloat((hex >> 8) & 255) / 255, CGFloat(hex & 255) / 255, a])!
}
let full = CGRect(x: 0, y: 0, width: 512, height: 512)
// 바탕은 ic_launcher_background.xml 의 그라데이션 두 겹 그대로(그 파일이 바뀌면 여기 값도 바꾼다).
if mode == "light" {
    let g = CGGradient(colorsSpace: cs, colors: [c(0xF0A684), c(0xE8855F), c(0xC5613F), c(0xA64D30)] as CFArray, locations: [0, 0.35, 0.72, 1])!
    ctx.saveGState(); ctx.clip(to: full)
    ctx.drawLinearGradient(g, start: CGPoint(x: 41, y: 20), end: CGPoint(x: 481, y: 502), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    let r = CGGradient(colorsSpace: cs, colors: [c(0xFFFFFF, 0x26 / 255.0), c(0xFFFFFF, 0)] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(r, startCenter: CGPoint(x: 154, y: 123), startRadius: 0, endCenter: CGPoint(x: 154, y: 123), endRadius: 300, options: [])
    ctx.restoreGState()
} else if mode == "dark" {
    // iOS 18 어두운 아이콘: 바탕은 어두운 회색 그라데이션(애플 본보기와 같은 결) — 투명으로 두면 시스템이 검정을 깐다.
    let g = CGGradient(colorsSpace: cs, colors: [c(0x3A3330), c(0x1E1A18)] as CFArray, locations: [0, 1])!
    ctx.saveGState(); ctx.clip(to: full)
    ctx.drawLinearGradient(g, start: CGPoint(x: 256, y: 0), end: CGPoint(x: 256, y: 512), options: [])
    ctx.restoreGState()
}
// 마크: 밝음은 흰색, 어두움은 클레이색, 물들임(tinted)은 흰색을 투명 바탕에(시스템이 밝기로 물들인다).
let mark: UInt32 = mode == "dark" ? 0xE8855F : 0xFFFFFF
for p in paths {
    ctx.beginPath()
    for poly in p.points { ctx.addLines(between: poly); ctx.closePath() }
    ctx.setFillColor(c(mark, p.alpha)); ctx.fillPath()
}
let img = ctx.makeImage()!
let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: out) as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, img, nil); CGImageDestinationFinalize(dest)
