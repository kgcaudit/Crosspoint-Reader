import SwiftUI
import UIKit

/// OLO 디자인 시스템(안드로이드 `CpTheme.kt` 에서 옮김 — 값은 그쪽이 원본이다). 화면 코드는 **역할 이름만** 쓴다.
/// 다크 모드는 여기 한 곳에서 끝난다(디자인 인계 §6-4): 색마다 밝은 · 어두운 값을 같이 두고 시스템이 고른다.
enum CpColor {
    static let background = dynamic(0xF7F4EF, 0x181613)
    static let surface = dynamic(0xFCFAF6, 0x1C1A16)
    static let dialog = dynamic(0xEDE8DF, 0x2B2723)
    static let text = dynamic(0x1D1A16, 0xE8E3DA)
    static let textMuted = dynamic(0x574D45, 0xCFC5B9)
    static let divider = dynamic(0xD6CCC1, 0x49423A)
    static let outline = dynamic(0x8B7F74, 0x978C80)
    static let accent = dynamic(0xB95B3B, 0xE8A183)
    /// 강조색 **글자**. 채운 단추는 `accent`.
    static let accentText = dynamic(0x9A4A2C, 0xF6C4AE)
    static let onAccent = dynamic(0xFFFFFF, 0x4A1E0C)
    static let accentContainer = dynamic(0xF6E0D6, 0x8A3E22)
    static let onAccentContainer = dynamic(0x4A1E0C, 0xFBE0D4)
    static let error = dynamic(0xA50E2E, 0xFFB0BE)
    static let progressTrack = dynamic(0xDCD3C6, 0x39434D)

    /// 타일 — 뜻이 있는 색. 흰 그림을 얹으므로 다크에서 더 밝다.
    static let tileFolder = dynamic(0xB95B3B, 0xD1734F)
    static let tileDocument = dynamic(0x55606B, 0x6E7A86)
    static let tileOther = dynamic(0x7A7168, 0x938A80)

    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? uiColor(dark) : uiColor(light) })
    }

    static func uiColor(_ rgb: UInt32) -> UIColor {
        UIColor(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}

/// 리더 지면(안드로이드 `PaperTheme`). "휴대폰" 은 휴대폰의 다크 모드를 따른다. 메뉴도 지면의 밝기를 따른다 — 검은 지면
/// 위에 밝은 메뉴가 뜨면 눈이 부시다.
enum PaperTheme: String, CaseIterable, Identifiable {
    case system, white, ivory, sepia, gray, black
    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "휴대폰"
        case .white: "흰색"
        case .ivory: "아이보리"
        case .sepia: "세피아"
        case .gray: "회색"
        case .black: "검은색"
        }
    }

    func paper(_ scheme: ColorScheme) -> Paper {
        switch self {
        case .system: scheme == .dark ? .dark : .light
        case .white: Paper(paper: 0xFFFFFF, ink: 0x1D1A16, inkMuted: 0x6B625A, dark: false)
        case .ivory: Paper(paper: 0xF4ECD8, ink: 0x3A2E22, inkMuted: 0x6E5F4E, dark: false)
        case .sepia: Paper(paper: 0xE9DCC0, ink: 0x3B2A1A, inkMuted: 0x6C5842, dark: false)
        case .gray: Paper(paper: 0xDAD8D3, ink: 0x1F1D1A, inkMuted: 0x55504A, dark: false)
        case .black: Paper(paper: 0x121110, ink: 0xD9D3C9, inkMuted: 0x9C948A, dark: true)
        }
    }
}

/// 지면 색 한 벌.
struct Paper: Equatable {
    let paper: UInt32
    let ink: UInt32
    let inkMuted: UInt32
    var dark = false

    static let light = Paper(paper: 0xFBF9F5, ink: 0x1D1A16, inkMuted: 0x574D45, dark: false)
    static let dark = Paper(paper: 0x181613, ink: 0xE8E3DA, inkMuted: 0xCFC5B9, dark: true)

    var paperColor: Color { Color(CpColor.uiColor(paper)) }
    var inkMutedColor: Color { Color(CpColor.uiColor(inkMuted)) }
}

/// 글자 단계(디자인 인계 §3). 시스템 글꼴. 제목만 줄이고 본문은 보통 크기 — 제목이 크면 팝업이 주변보다 두 배 큰 글자로 뜬다.
enum CpFont {
    static let title = Font.system(size: 19, weight: .bold)
    static let body = Font.system(size: 16)
    static let label = Font.system(size: 14, weight: .bold)
    static let subtitle = Font.system(size: 14)
    static let caption = Font.system(size: 12)
}

/// 치수(디자인 인계 §4).
enum CpMetrics {
    static let gutter: CGFloat = 16
    static let headerHeight: CGFloat = 64
    static let rowHeight: CGFloat = 64
    static let touchTarget: CGFloat = 48
    static let cornerChip: CGFloat = 6
    static let cornerSmall: CGFloat = 10
    static let cornerMedium: CGFloat = 14
    static let cornerSheet: CGFloat = 18
    static let cornerDialog: CGFloat = 20
    static let tileSize: CGFloat = 40
    static let tileCorner: CGFloat = 12
    static let childIndent: CGFloat = 38
    static let levelIndent: CGFloat = 16
}

/// 화면 배율 · 휴대폰인가. 앱을 켤 때 주 스레드에서 한 번 읽어 둔다 — 엔진(actor)이 그림을 그리며 `UIScreen.main` ·
/// `UIDevice.current` 를 주 스레드 밖에서 읽었다(주 스레드 전용 · 앞으로 막힐 API).
enum ScreenInfo {
    nonisolated(unsafe) private(set) static var scale: CGFloat = 3
    nonisolated(unsafe) private(set) static var phone = true

    @MainActor
    static func capture() {
        scale = UIScreen.main.scale
        phone = UIDevice.current.userInterfaceIdiom == .phone
    }
}
