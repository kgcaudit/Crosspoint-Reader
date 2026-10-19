import OloCore
import SwiftUI

/// 번들의 시험용 TXT 를 공통 코어로 조판해 첫 쪽을 그린다(iOS 첫 일 4단계). 서재 · 리더 화면은 구상안을 확정한 뒤에 만든다.
///
/// 흐름은 안드로이드와 같다: TxtDocument(인코딩 감지) → TextChapter(줄 = 문단) → Paginator(CoreText 측정기) → PagePainter.
struct FirstPageView: View {
    @StateObject private var model = FirstPageModel()

    var body: some View {
        // 안쪽 GeometryReader 는 안전 영역을 존중해 그 크기와 여백을 알려 준다. 지면은 화면 끝까지 칠하고, 글자만 안전
        // 영역 안에 둔다 — 안드로이드가 디스플레이 잘림을 여백에 더하는 것과 같다.
        GeometryReader { geometry in
            let insets = geometry.safeAreaInsets
            let full = CGSize(
                width: geometry.size.width + insets.leading + insets.trailing,
                height: geometry.size.height + insets.top + insets.bottom
            )
            ZStack {
                Paper.background
                if let image = model.image {
                    Image(uiImage: image)
                        .resizable()
                        .frame(width: full.width, height: full.height)
                        .accessibilityLabel(model.title)
                } else {
                    Text(model.status)
                        .font(.body)
                        .foregroundStyle(Paper.ink)
                }
            }
            .frame(width: full.width, height: full.height)
            .offset(x: -insets.leading, y: -insets.top)
            .task(id: LayoutKey(size: full, insets: insets)) {
                await model.load(size: full, insets: insets)
            }
        }
    }
}

/// 지면 색. 디자인 인계 §2 의 "시스템" 테마(밝은 쪽): 바탕 `background` · 글자 `text`.
enum Paper {
    static let paperRGB: Int32 = 0xF7F4EF
    static let inkRGB: Int32 = 0x1D1A16
    static let background = Color(red: 0xF7 / 255, green: 0xF4 / 255, blue: 0xEF / 255)
    static let ink = Color(red: 0x1D / 255, green: 0x1A / 255, blue: 0x16 / 255)
}

/// 화면 크기나 안전 영역이 바뀔 때만 다시 조판한다(회전 · 분할 화면).
private struct LayoutKey: Equatable {
    let size: CGSize
    let insets: EdgeInsets
}

@MainActor
final class FirstPageModel: ObservableObject {
    @Published private(set) var image: UIImage?
    @Published private(set) var status = "여는 중…"
    @Published private(set) var title = ""

    func load(size: CGSize, insets: EdgeInsets) async {
        guard size.width > 0, size.height > 0 else { return }
        guard let url = Bundle.main.url(forResource: "sample", withExtension: "txt") else {
            status = "시험용 책이 앱에 들어 있지 않습니다"
            return
        }
        do {
            // 조판은 주 스레드 밖에서 한다. 주 스레드에서 하면 첫 화면("여는 중…")조차 그려지기 전에 앱이 멈춘 것처럼 보인다.
            let result = try await Task.detached(priority: .userInitiated) {
                try await FirstPageModel.layOut(url: url, size: size, insets: insets)
            }.value
            title = result.title
            if let image = result.image { self.image = image } else { status = "빈 책입니다" }
        } catch {
            status = "책을 열지 못했습니다: \(error.localizedDescription)"
        }
    }

    private struct Result: @unchecked Sendable {
        let title: String
        let image: UIImage?
    }

    private nonisolated static func layOut(url: URL, size: CGSize, insets: EdgeInsets) async throws -> Result {
        let started = Date()
        let spec = ReadingSpec.shared.of(
            widthPt: Float(size.width),
            heightPt: Float(size.height),
            safeLeft: Float(insets.leading),
            safeTop: Float(insets.top),
            safeRight: Float(insets.trailing),
            safeBottom: Float(insets.bottom),
            fontSize: ReadingSpec.shared.DEFAULT_SIZE
        )
        let document = TxtDocument.companion.open(
            id: "sample",
            displayName: url.lastPathComponent,
            source: FileBytes.shared.file(path: url.path)
        )
        let reader = try await document.openChapter(index: 0)
        let chapter = TextChapter.shared.parse(reader: reader, context: StyleContext.companion.of(spec: spec))
        // 조판용과 그리기용 측정기를 따로 둔다(안드로이드와 같다). 같은 설정이면 같은 값이 나온다.
        let pages = ReadingSpec.shared.paginator(spec: spec, measurer: CoreTextMeasurer.companion.forSpec(spec: spec))
            .paginate(text: chapter.text, blocks: chapter.blocks)
            .iterator()
        guard pages.hasNext(), let first = pages.next() as? Page else {
            return Result(title: document.meta.title, image: nil)
        }
        let laidOut = Date()
        let image = PagePainter.shared.render(
            page: first,
            text: chapter.text,
            measurer: CoreTextMeasurer.companion.forSpec(spec: spec),
            widthPt: Double(size.width),
            heightPt: Double(size.height),
            paper: Paper.paperRGB,
            ink: Paper.inkRGB
        )
        print(String(format: "[olo] first page: layout %.0f ms, draw %.0f ms, encoding %@",
                     laidOut.timeIntervalSince(started) * 1000, Date().timeIntervalSince(laidOut) * 1000,
                     document.encoding.encoding.name))
        return Result(title: document.meta.title, image: image)
    }
}

#Preview {
    FirstPageView()
}
