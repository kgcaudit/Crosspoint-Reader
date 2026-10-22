import ImageIO
import OloCore
import SwiftUI
import UIKit

/// 틀 아래 그림 조각들(화 하나 안) — 그리는 일은 화면 줄 밖에서 한다.
struct WebtoonCoverJob: @unchecked Sendable {
    let episode: WebtoonEpisode
    let pieces: [WebtoonPiece]
    /// 틀이 기둥 폭의 어디부터 어디까지인가(0 = 기둥 왼쪽, 1 = 오른쪽 — 밖이면 0 미만 · 1 초과).
    let columnFrom: Double
    let columnTo: Double
    let frameHeight: Double

    /// 표지 한 장(331 × 480): 바탕색 위에 조각마다 원본의 그 부분. 틀이 그림 두 장에 걸치면 두 장을 이어 붙인다 — 위 그림 한 장만
    /// 자르면 틀 위가 앞 그림 끝에 몇 픽셀 걸칠 때 그 얇은 띠가 표지가 됐다(안드로이드 0.48.4). 한 장이라도 못 풀면 쓰지 않는다 —
    /// 반쯤 빈 표지가 저장되면 고장 난 줄 안다.
    func render() -> UIImage? {
        let L = IosCovers.shared
        let out = CGSize(width: Int(L.TOON_OUT_WIDTH), height: Int(L.HEIGHT))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        var ok = true
        let image = UIGraphicsImageRenderer(size: out, format: format).image { ctx in
            ctx.cgContext.setFillColor(UIColor(comicPaper.paperColor).cgColor)
            ctx.cgContext.fill(CGRect(origin: .zero, size: out))
            ctx.cgContext.interpolationQuality = .high
            for piece in pieces {
                guard let data = episode.book.data(i: piece.index), let src = CGImageSourceCreateWithData(data as CFData, nil),
                      let full = CGImageSourceCreateImageAtIndex(src, 0, [kCGImageSourceShouldCache: false] as CFDictionary) else {
                    ok = false
                    continue
                }
                guard let d = L.pieceDraw(piece: piece, columnFrom: columnFrom, columnTo: columnTo, frameHeight: frameHeight,
                                          imageWidth: Int32(full.width), imageHeight: Int32(full.height),
                                          outWidth: Int32(out.width), outHeight: Int32(out.height)) else { continue }
                guard let cut = full.cropping(to: CGRect(x: Int(d.srcX), y: Int(d.srcY), width: Int(d.srcWidth), height: Int(d.srcHeight))) else {
                    ok = false
                    continue
                }
                UIImage(cgImage: cut).draw(in: CGRect(x: d.dstX, y: d.dstY, width: d.dstWidth, height: d.dstHeight))
            }
        }
        return ok ? image : nil
    }
}

/// 보던 장면에서 표지 고르기 — 웹툰(구상안 44 ⑤, 안드로이드 `ComicWebtoon` 의 틀). 표지 모양 틀을 끌어 옮기고(틀 밖을 끌면 그림이
/// 넘어간다), 두 손가락으로 크기를 바꾼다(그림 뷰가 알려 준다). 틀 밖은 어둡게, 틀은 흰 선.
struct WebtoonCoverFrame: View {
    @ObservedObject var model: WebtoonModel
    @Binding var box: IosCovers.FrameBox
    let full: CGSize
    let insets: EdgeInsets
    let back: () -> Void
    let save: (UIImage) -> Bool

    @State private var dragBase: IosCovers.FrameBox?
    @State private var magnifyBase: IosCovers.FrameBox?
    @State private var saving = false
    @State private var failed = false

    private var L: IosCovers { IosCovers.shared }

    private var rect: CGRect { CGRect(x: box.x, y: box.y, width: box.width, height: box.height) }

    var body: some View {
        ZStack(alignment: .topLeading) {
            Path { p in
                p.addRect(CGRect(origin: .zero, size: full))
                p.addRect(rect)
            }
            .fill(Color.black.opacity(0.6), style: FillStyle(eoFill: true))
            .allowsHitTesting(false)
            // 틀 안만 끌기를 받는다 — 틀 밖의 끌기는 그 아래 그림 뷰가 받아 그림을 넘긴다.
            Rectangle().fill(Color.white.opacity(0.001))
                .overlay(Rectangle().stroke(Color.white, lineWidth: 2))
                .frame(width: rect.width, height: rect.height)
                .offset(x: rect.minX, y: rect.minY)
                .gesture(DragGesture(minimumDistance: 1).onChanged { g in
                    let base = dragBase ?? box
                    dragBase = base
                    box = L.clampFrame(f: IosCovers.FrameBox(x: base.x + g.translation.width, y: base.y + g.translation.height, width: base.width),
                                       viewWidth: Double(full.width), viewHeight: Double(full.height), columnWidth: Double(model.columnWidth))
                }.onEnded { _ in dragBase = nil })
                // 틀 위에 놓인 두 손가락은 이 틀이 받는다(틀 밖의 두 손가락은 그림 뷰가 받아 알려 준다) — 틀이 손을 가로채
                // 크기가 바뀌지 않았다.
                .simultaneousGesture(MagnifyGesture().onChanged { g in
                    let base = magnifyBase ?? box
                    magnifyBase = base
                    box = L.resizeFrame(base: base, scale: Double(g.magnification), viewWidth: Double(full.width),
                                        viewHeight: Double(full.height), columnWidth: Double(model.columnWidth))
                }.onEnded { _ in magnifyBase = nil })
                .accessibilityLabel("표지 틀")
            VStack(spacing: 0) {
                CpHeader(title: L.TOON_TITLE, subtitle: failed ? L.TOON_FAILED : L.TOON_HINT,
                         leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
                    .padding(.top, insets.top).padding(.leading, insets.leading).padding(.trailing, insets.trailing)
                    .background(CpColor.surface)
                Spacer(minLength: 0).allowsHitTesting(false)
                CpButton(title: saving ? L.SAVING : L.TOON_BUTTON) { pick() }
                    .frame(maxWidth: .infinity).padding(.horizontal, CpMetrics.gutter).padding(.vertical, 10)
                    .padding(.bottom, insets.bottom).padding(.leading, insets.leading).padding(.trailing, insets.trailing)
                    .background(CpColor.surface)
            }
            .frame(width: full.width, height: full.height)
        }
        .frame(width: full.width, height: full.height, alignment: .topLeading)
    }

    private func pick() {
        guard !saving else { return }
        guard let job = model.coverJob(frame: rect) else { failed = true; return }
        saving = true
        Task {
            let image = await Task.detached(priority: .userInitiated) { job.render() }.value
            if !(image.map(save) ?? false) {
                failed = true
                saving = false
            }
        }
    }
}
