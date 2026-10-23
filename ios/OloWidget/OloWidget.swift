import AppIntents
import SwiftUI
import WidgetKit

// 이어 읽기 위젯(구상안 47, 안드로이드 0.56.0 `ContinueWidget` 과 같은 배치 · 문구). 무엇을 보일지는 앱이 셈해 둔 것(`WidgetSnapshot`)을
// 읽기만 한다 — 고르는 규칙은 공통 Kotlin `IosWidget` 에 있다.

@main
struct OloWidgetBundle: WidgetBundle {
    var body: some Widget { ContinueWidget() }
}

// MARK: - 설정(위젯마다 — 길게 눌러 '위젯 편집')

enum OpacityOption: String, AppEnum {
    case p0 = "0", p25 = "25", p50 = "50", p75 = "75", p100 = "100"
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "불투명도"
    static var caseDisplayRepresentations: [OpacityOption: DisplayRepresentation] =
        [.p0: "0%", .p25: "25%", .p50: "50%", .p75: "75%", .p100: "100%"]
    var value: Double { (Double(rawValue) ?? 100) / 100 }
}

enum ColorOption: String, AppEnum {
    case system, light, dark
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "색"
    static var caseDisplayRepresentations: [ColorOption: DisplayRepresentation] = [.system: "휴대폰", .light: "밝게", .dark: "어둡게"]
}

enum RecentOption: String, AppEnum {
    case all, books, comics
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "최근 목록"
    static var caseDisplayRepresentations: [RecentOption: DisplayRepresentation] = [.all: "책 · 만화", .books: "책만", .comics: "만화만"]
    /// `WidgetSnapshot.models` 의 열쇠(Kotlin `IosWidget.ALL` · `BOOKS` · `COMICS`).
    var key: Int { switch self { case .all: 0; case .books: 1; case .comics: 2 } }
}

struct ContinueIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "이어 읽기"
    static var description = IntentDescription("읽던 책과 최근에 읽은 책을 보여 줍니다.")

    @Parameter(title: "배경", default: true) var background: Bool
    @Parameter(title: "불투명도", default: .p100) var opacity: OpacityOption
    @Parameter(title: "색", default: .system) var color: ColorOption
    @Parameter(title: "최근 목록", default: .all) var recent: RecentOption
}

// MARK: - 시간표

struct ContinueEntry: TimelineEntry {
    let date: Date
    let model: WidgetSnapshot.Model
    let intent: ContinueIntent
}

struct ContinueProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ContinueEntry { ContinueEntry(date: .now, model: Self.sample, intent: ContinueIntent()) }

    func snapshot(for configuration: ContinueIntent, in context: Context) async -> ContinueEntry {
        // 고르기 화면의 미리보기는 본보기 글(안드로이드 고르기 화면과 같은 책들) — 읽은 것이 없는 사람에게도 모양이 보이게.
        ContinueEntry(date: .now, model: context.isPreview ? Self.sample : Self.current(configuration), intent: configuration)
    }

    /// 정해 둔 때마다 깨어나지 않는다 — 앱이 자리를 적을 때 다시 그리게 한다(배터리를 쓰지 않게).
    func timeline(for configuration: ContinueIntent, in context: Context) async -> Timeline<ContinueEntry> {
        Timeline(entries: [ContinueEntry(date: .now, model: Self.current(configuration), intent: configuration)], policy: .never)
    }

    static func current(_ c: ContinueIntent) -> WidgetSnapshot.Model {
        WidgetShared.load()?.models[c.recent.key] ?? WidgetSnapshot.Model(top: nil, recent: [])
    }

    static let sample = WidgetSnapshot.Model(
        top: .init(comic: false, id: "", title: "어린 왕자", byline: "생텍쥐페리", percentText: "64%", progress: 640, cover: nil),
        recent: [
            .init(comic: false, id: "", title: "데미안", byline: "헤르만 헤세", percentText: "87%", progress: 870, cover: nil),
            .init(comic: true, id: "", title: "별을 줍는 아이 5권", byline: "만화", percentText: "22%", progress: 220, cover: nil),
            .init(comic: false, id: "", title: "노인과 바다", byline: "헤밍웨이", percentText: "41%", progress: 410, cover: nil),
        ])
}

// MARK: - 위젯

struct ContinueWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: WidgetShared.kind, intent: ContinueIntent.self, provider: ContinueProvider()) { entry in
            ContinueView(entry: entry)
        }
        .configurationDisplayName("이어 읽기")
        .description("읽던 책과 최근에 읽은 책을 보여 줍니다.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        // 여백은 판이 정한다(안드로이드 14dp) — 시스템 여백(16)을 더하면 표지가 작아진다.
        .contentMarginsDisabled()
    }
}

/// 위젯의 색(안드로이드 `widget_*` 밝은 · 어두운 값 그대로).
struct WidgetPalette {
    let surface: Color, text: Color, muted: Color, accent: Color, accentText: Color, track: Color, blank: Color

    static let light = WidgetPalette(surface: .hex(0xFCFAF6), text: .hex(0x1D1A16), muted: .hex(0x574D45), accent: .hex(0xB95B3B),
                                     accentText: .hex(0x9A4A2C), track: .hex(0xDCD3C6), blank: .hex(0x8A6A3B))
    static let dark = WidgetPalette(surface: .hex(0x1C1A16), text: .hex(0xE8E3DA), muted: .hex(0xCFC5B9), accent: .hex(0xE8A183),
                                    accentText: .hex(0xF6C4AE), track: .hex(0x39434D), blank: .hex(0xB08A54))
}

extension Color {
    static func hex(_ v: Int) -> Color { Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255) }
}

struct ContinueView: View {
    let entry: ContinueEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme

    /// "휴대폰" 은 휴대폰의 밝기를 따른다 — 고정해 두면 휴대폰이 어두워져도 그린 때의 색이 남는다(안드로이드와 같은 까닭).
    private var p: WidgetPalette {
        switch entry.intent.color {
        case .light: .light
        case .dark: .dark
        case .system: scheme == .dark ? .dark : .light
        }
    }

    var body: some View {
        Group {
            if let top = entry.model.top {
                switch family {
                case .systemSmall: small(top)
                case .systemLarge: large(top)
                default: medium(top)
                }
            } else {
                empty
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(entry.model.top?.url ?? WidgetShared.openApp)
        .containerBackground(for: .widget) {
            entry.intent.background ? p.surface.opacity(entry.intent.opacity.value) : Color.clear
        }
    }

    // MARK: 판

    private func small(_ top: WidgetSnapshot.Item) -> some View {
        VStack(spacing: 6) {
            CoverView(item: top, p: p).frame(maxHeight: .infinity)
            Text(top.title).font(.system(size: 13, weight: .bold)).foregroundStyle(p.text).lineLimit(1)
            HStack(spacing: 6) {
                Bar(progress: top.progress, p: p, height: 4)
                Text(top.percentText).font(.system(size: 11, weight: .semibold)).foregroundStyle(p.text)
            }
        }
    }

    private func medium(_ top: WidgetSnapshot.Item) -> some View {
        HStack(alignment: .top, spacing: 12) {
            CoverView(item: top, p: p)
            main(top, titleSize: 17)
            if !entry.model.recent.isEmpty {
                Rectangle().fill(p.track).frame(width: 1)
                VStack(alignment: .leading, spacing: 7) {
                    Text("최근에 읽은").font(.system(size: 12)).foregroundStyle(p.muted)
                    ForEach(entry.model.recent.prefix(3)) { item in
                        Link(destination: item.url) {
                            VStack(spacing: 3) {
                                HStack(spacing: 4) {
                                    Text(item.title).font(.system(size: 13)).foregroundStyle(p.text).lineLimit(1)
                                    Spacer(minLength: 0)
                                    Text(item.percentText).font(.system(size: 11)).foregroundStyle(p.muted)
                                }
                                Bar(progress: item.progress, p: p, height: 3)
                            }
                        }
                    }
                }
                .frame(width: 128)
            }
        }
    }

    private func large(_ top: WidgetSnapshot.Item) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                CoverView(item: top, p: p)
                main(top, titleSize: 19)
            }
            .frame(height: entry.model.recent.isEmpty ? nil : 168)
            if !entry.model.recent.isEmpty {
                Rectangle().fill(p.track).frame(height: 1).padding(.top, 10).padding(.bottom, 8)
                Text("최근에 읽은").font(.system(size: 12)).foregroundStyle(p.muted)
                ForEach(entry.model.recent.prefix(3)) { item in
                    Link(destination: item.url) {
                        HStack(spacing: 10) {
                            CoverView(item: item, p: p, small: true).frame(height: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(item.title).font(.system(size: 13)).foregroundStyle(p.text).lineLimit(1)
                                    Spacer(minLength: 0)
                                    Text(item.percentText).font(.system(size: 11)).foregroundStyle(p.muted)
                                }
                                if let by = item.byline { Text(by).font(.system(size: 11)).foregroundStyle(p.muted).lineLimit(1) }
                                Bar(progress: item.progress, p: p, height: 3)
                            }
                        }
                        .frame(height: 46)
                    }
                    .padding(.top, 2)
                }
            }
        }
    }

    /// 지금 책: "이어 읽기" · 제목(2줄) · 저자 · % · 막대.
    private func main(_ top: WidgetSnapshot.Item, titleSize: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("이어 읽기").font(.system(size: 12)).foregroundStyle(p.accentText)
            Text(top.title).font(.system(size: titleSize, weight: .bold)).foregroundStyle(p.text).lineLimit(2)
            if let by = top.byline { Text(by).font(.system(size: 13)).foregroundStyle(p.muted).lineLimit(1) }
            Spacer(minLength: 0)
            Text(top.percentText).font(.system(size: 13, weight: .bold)).foregroundStyle(p.text)
            Bar(progress: top.progress, p: p, height: 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 6) {
            Spacer(minLength: 0)
            Text("OLO eBook 열기").font(.system(size: 17, weight: .bold)).foregroundStyle(p.text)
            Text("책을 읽기 시작하면 여기에 보입니다").font(.system(size: 13)).foregroundStyle(p.muted)
            Spacer(minLength: 0)
        }
    }
}

/// 표지: 원래 비율로 칸 높이에 맞춘다 — 자르지 않는다(안드로이드 0.56.0, 꽉 채우던 것이 위아래 · 옆을 잘랐다). 표지가 없으면 2:3 판에 제목.
private struct CoverView: View {
    let item: WidgetSnapshot.Item
    let p: WidgetPalette
    var small = false

    var body: some View {
        if let name = item.cover, let url = WidgetShared.coverFolder?.appendingPathComponent(name),
           let img = UIImage(contentsOfFile: url.path) {
            Image(uiImage: img).resizable().aspectRatio(contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: small ? 4 : 8))
        } else {
            RoundedRectangle(cornerRadius: small ? 4 : 8).fill(p.blank).aspectRatio(2 / 3, contentMode: .fit)
                .overlay(alignment: .topLeading) {
                    if !small {
                        Text(item.title).font(.system(size: 13, weight: .bold)).foregroundStyle(.white).lineLimit(3).padding(8)
                    }
                }
        }
    }
}

private struct Bar: View {
    let progress: Int
    let p: WidgetPalette
    let height: CGFloat

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(p.track)
                Capsule().fill(p.accent).frame(width: g.size.width * CGFloat(min(max(progress, 0), 1000)) / 1000)
            }
        }
        .frame(height: height)
    }
}
