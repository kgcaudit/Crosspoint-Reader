import OloCore
import SwiftUI
import UIKit

/// 읽기 설정(안드로이드 `ReaderPrefs` · `ScreenPrefs`). 모든 책에 하나다 — 책마다 따로 두면 새 책을 열 때마다 글자 크기를
/// 다시 맞춰야 한다. 밝기만은 예외로 그 책에서만 쓴다([ScreenBrightness]).
///
/// UserDefaults 에 둔다: 읽기가 바로 끝나서(파일을 기다리지 않아서) 첫 조판부터 맞는 설정으로 짠다.
@MainActor
final class ReaderPrefs: ObservableObject {
    enum KeepScreenOn: Int, CaseIterable { case system, tenMinutes, always }

    static let lineSpacings: [Float] = [1.4, 1.65, 1.9]
    static let margins: [Float] = [24, 36, 48]
    static let paragraphSpacings: [Float] = [0.25, 0.6, 1.0]
    static let fontRange = 12...36

    @Published var theme: PaperTheme { didSet { save(theme.rawValue, "theme") } }
    @Published var blendPictures: Bool { didSet { save(blendPictures, "blendPictures") } }
    @Published var fontSize: Int { didSet { save(fontSize, "fontSize") } }
    /// 0 좁게 · 1 보통 · 2 넓게.
    @Published var lineSpacing: Int { didSet { save(lineSpacing, "lineSpacing") } }
    @Published var margin: Int { didSet { save(margin, "margin") } }
    /// 0 원본 · 1 양쪽 · 2 왼쪽.
    @Published var align: Int { didSet { save(align, "align") } }
    @Published var indentOff: Bool { didSet { save(indentOff, "indentOff") } }
    @Published var paragraphSpacing: Int { didSet { save(paragraphSpacing, "paragraphSpacing") } }
    @Published var keepScreenOn: KeepScreenOn { didSet { save(keepScreenOn.rawValue, "keepScreenOn") } }
    /// 넘김 효과: 0 없음 · 1 서서히 · 2 밀기 · 3 덮기 · 4 말림(기본, 안드로이드 0.32).
    @Published var pageTurn: Int { didSet { save(pageTurn, "pageTurn") } }
    /// 넘김 소리: 0 끔(기본) · 1 사락 · 2 휙 · 3 톡.
    @Published var turnSound: Int { didSet { save(turnSound, "turnSound") } }
    /// 넘김 진동(기본 끔).
    @Published var turnHaptic: Bool { didSet { save(turnHaptic, "turnHaptic") } }
    /// 형광펜 · 메모 보임(모든 보기 설정). 숨기면 그리지도, 눌러 열지도 않는다 — 지우지는 않는다.
    @Published var showHighlights: Bool { didSet { defaults.set(showHighlights, forKey: "reader.showHighlights") } }
    /// 본문 글꼴: nil = 휴대폰 글꼴, 아니면 넣은 글꼴의 파일 이름(`FontLibrary`).
    @Published var font: String? { didSet { defaults.set(font, forKey: "reader.font") } }
    /// 출판사 글꼴(책에 든 글꼴). 안드로이드처럼 기본으로 켠다 — 출판사가 고른 모양이 그 책의 첫인상이다. 끄면 고른 글꼴로 돌아간다.
    @Published var publisherFonts: Bool { didSet { defaults.set(publisherFonts, forKey: "reader.publisherFonts") } }

    private let defaults = UserDefaults.standard

    init() {
        let d = UserDefaults.standard
        func int(_ key: String, _ fallback: Int, _ range: ClosedRange<Int>) -> Int {
            guard d.object(forKey: "reader.\(key)") != nil else { return fallback }
            // 망가진 값(손으로 고친 설정 · 옛 판)은 기본값으로 — 범위 밖 값으로 조판하면 쪽이 비거나 넘친다.
            let v = d.integer(forKey: "reader.\(key)")
            return range.contains(v) ? v : fallback
        }
        theme = PaperTheme(rawValue: d.string(forKey: "reader.theme") ?? "") ?? .system
        blendPictures = d.object(forKey: "reader.blendPictures") as? Bool ?? true
        fontSize = int("fontSize", Int(ReadingSpec.shared.DEFAULT_SIZE), ReaderPrefs.fontRange)
        lineSpacing = int("lineSpacing", 1, 0...2)
        margin = int("margin", 1, 0...2)
        align = int("align", 0, 0...2)
        indentOff = d.bool(forKey: "reader.indentOff")
        paragraphSpacing = int("paragraphSpacing", 0, 0...2)
        keepScreenOn = KeepScreenOn(rawValue: int("keepScreenOn", 0, 0...2)) ?? .system
        font = d.string(forKey: "reader.font")
        showHighlights = d.object(forKey: "reader.showHighlights") as? Bool ?? true
        pageTurn = int("pageTurn", 4, 0...4)
        turnSound = int("turnSound", 0, 0...3)
        turnHaptic = d.bool(forKey: "reader.turnHaptic")
        publisherFonts = d.object(forKey: "reader.publisherFonts") as? Bool ?? true
    }

    /// 실제로 쓰이는 글꼴 파일. 고른 글꼴이 목록에 없으면(뺀 글꼴 · 읽지 못하는 파일) 휴대폰 글꼴 — 글꼴 ID 를 남겨 두면 캐시 키는
    /// 사용자 글꼴인데 측정기는 휴대폰 글꼴로 재서, 나중에 같은 이름이 돌아오면 옛 쪽을 다른 폭으로 그린다.
    var effectiveFont: String? { FontLibrary.shared.item(font)?.file }

    /// 이 책을 출판사 글꼴로 짜는가. 한 곳에서만 판단한다 — 조판(캐시 키) · 보기 판의 이름 · 글꼴 판의 선택 표시가 따로
    /// 판단하면 "출판사 글꼴" 이 켜져 보이는데 휴대폰 글꼴로 짜이는 식으로 어긋난다(안드로이드 `usesBookFonts`).
    func usesBookFonts(_ bookHasFonts: Bool) -> Bool { publisherFonts && bookHasFonts }

    private func save(_ value: Any, _ key: String) { defaults.set(value, forKey: "reader.\(key)") }

    /// 화면 크기 · 안전 영역과 함께 조판 설정을 만든다. 조판을 바꾸는 설정은 모두 여기를 지나 LayoutSpec 에 들어간다 —
    /// 빠뜨리면 캐시 키가 같아서 설정을 바꿨는데 옛 쪽이 보인다(CLAUDE.md 규칙 4).
    /// [bookFonts] 는 이 책에 쓸 수 있는 글꼴이 있는지. 출판사 글꼴로 짜면 책이 정하지 않은 곳은 휴대폰 글꼴이다 — 고른
    /// 사용자 글꼴과 섞으면 어느 것이 책의 모양인지 알 수 없다(안드로이드 `bodyFont`). 고른 글꼴은 지우지 않는다.
    func spec(size: CGSize, insets: EdgeInsets, bookFonts: Bool) -> LayoutSpec {
        let book = usesBookFonts(bookFonts)
        return
        ReadingSpec.shared.of(
            widthPt: Float(size.width), heightPt: Float(size.height),
            safeLeft: Float(insets.leading), safeTop: Float(insets.top),
            // 아래 안전 영역은 더하지 않는다: 아래 줄이 화면 끝에서 14 에 서고 홈 표시줄은 읽는 동안 숨는다(구상안 13A). 더하면
            // 본문이 34 만큼 덜 들어가 같은 크기 화면의 안드로이드와 쪽 수가 갈라진다.
            safeRight: Float(insets.trailing), safeBottom: 0,
            fontSize: Float(fontSize),
            lineSpacing: ReaderPrefs.lineSpacings[lineSpacing],
            sideMargin: ReaderPrefs.margins[margin],
            paragraphSpacingEm: ReaderPrefs.paragraphSpacings[paragraphSpacing],
            alignOverride: align == 1 ? TextAlign.justify : align == 2 ? TextAlign.start : nil,
            indentOff: indentOff,
            fontId: UserFonts.shared.fontId(fileName: book ? nil : effectiveFont),
            useBookFonts: book
        )
    }
}

/// 밝기. iOS 에서 앱이 밝기를 바꾸면 **휴대폰 전체**가 바뀐다 — 그래서 바꾼 밝기는 그 책에서만 쓰고, 책을 닫거나 다른 앱으로
/// 나가면 원래 밝기로 되돌린다(2026-10-20 사용자 결정 4A, 안드로이드 0.54.6 과 같은 규칙). 돌아오면 그 책의 밝기를 다시 건다.
@MainActor
final class ScreenBrightness: ObservableObject {
    /// 이 책에서 고른 밝기(0~1). nil 이면 휴대폰 밝기를 따른다.
    @Published private(set) var value: Double?
    private var original: CGFloat?

    private var screen: UIScreen? {
        (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen
    }

    /// 휴대폰 밝기(막대를 처음 놓을 자리). 0.5 에 두면 밤에 막대를 살짝 대기만 해도 50% 로 튄다.
    var phone: Double { Double(original ?? screen?.brightness ?? 0.5) }

    func set(_ v: Double) {
        guard let screen else { return }
        if original == nil { original = screen.brightness }
        value = min(max(v, 0.02), 1) // 0 이면 화면이 꺼진 것처럼 보여 되돌릴 수 없다.
        screen.brightness = CGFloat(value!)
    }

    /// "휴대폰" 을 눌렀다 — 휴대폰 밝기로 돌아간다.
    func followPhone() {
        restore()
        value = nil
    }

    /// 앱을 나갈 때 · 책을 닫을 때. 고른 값은 기억한다(돌아오면 다시 건다).
    func restore() {
        if let original { screen?.brightness = original }
        original = nil
    }

    func reapply() {
        if let value { set(value) }
    }

    /// 책을 닫았다: 휴대폰 밝기로 돌리고 잊는다(다음 책은 휴대폰 밝기로 연다).
    func forget() {
        restore()
        value = nil
    }
}
