import SwiftUI
import UIKit

/// 아래 줄 한 자리에 보일 것(구상안 28A, 안드로이드 `FooterItem`). 저장값은 안드로이드 이름 그대로 — 백업을 두 앱이 주고받는다.
enum FooterItem: String, CaseIterable, Identifiable {
    case none = "None", bookTitle = "BookTitle", chapterTitle = "ChapterTitle", clock = "Clock", battery = "Battery"
    case page = "Page", percent = "Percent", chapterLeft = "ChapterLeft", chapterTime = "ChapterTime", bookTime = "BookTime"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .none: "없음"
        case .bookTitle: "책 제목"
        case .chapterTitle: "장 제목"
        case .clock: "시계"
        case .battery: "배터리"
        case .page: "쪽"
        case .percent: "진행률(%)"
        case .chapterLeft: "이 장 남은 쪽"
        case .chapterTime: "이 장 남은 시간"
        case .bookTime: "책 남은 시간"
        }
    }

    /// 설정 요약("책 제목 · 쪽 · %")에 쓰는 짧은 이름.
    var short: String { self == .percent ? "%" : label }

    /// 고르는 화면의 회색 본보기(28A — 안드로이드에는 없다). 이름만으로는 "쪽" 과 "이 장 남은 쪽" 이 헷갈린다.
    var sample: String? {
        switch self {
        case .none: nil
        case .bookTitle: "등대지기의 저녁"
        case .chapterTitle: "1장"
        case .clock: FooterText.clock(Date())
        case .battery: "배터리 82%"
        case .page: "3 / 12"
        case .percent: "29%"
        case .chapterLeft: "이 장 9쪽 남음"
        case .chapterTime: "이 장 4분 남음"
        case .bookTime: "책 2시간 10분 남음"
        }
    }
}

/// 아래 줄의 글(안드로이드 `CpReading` 의 문구와 같다).
enum FooterText {
    /// "1분 미만" · "4분" · "2시간" · "2시간 10분".
    static func minutes(_ m: Int) -> String {
        if m <= 0 { return "1분 미만" }
        if m < 60 { return "\(m)분" }
        return m % 60 == 0 ? "\(m / 60)시간" : "\(m / 60)시간 \(m % 60)분"
    }

    /// 휴대폰의 24시간 설정을 따른다. 한국어로 고정(오전/오후) — 앱이 한국어 화면이다.
    static func clock(_ date: Date) -> String {
        // 꼴은 한 번 만들어 둔다(1분마다 다시 그릴 때마다 새로 만들었다). 24시간 설정은 그때마다 본다 — 읽는 중에 바꿀 수 있다.
        let h24 = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current)?.contains("a") == false
        let f = h24 ? clock24 : clock12
        return f.string(from: date)
    }

    private static let clock24 = clockFormatter("HH:mm")
    private static let clock12 = clockFormatter("a h:mm")

    private static func clockFormatter(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = format
        return f
    }

    static func chapterLeft(_ pages: Int) -> String { pages == 0 ? "이 장 마지막 쪽" : "이 장 \(pages)쪽 남음" }
}

/// 읽는 빠르기(안드로이드 `ReadingSpeed`): 분당 글자(책) · 분당 쪽(PDF). 사람이 바로 다음 쪽으로 넘긴 것만 표본으로(2초 ~ 10분
/// 머문 쪽), 지수 평균 0.2. 세 번 재기 전에는 모른다(nil — 빈칸). 갈래마다 모든 책에 하나다 — 단위가 달라 섞으면 둘 다 틀린다.
struct ReadingSpeed: Equatable {
    /// 저장 열쇠(안드로이드 `speed.chars` · `speed.pages` 와 같은 갈래).
    enum Kind: String { case chars = "speedChars", pages = "speedPages" }

    var unitsPerMinute: Double?
    var samples: Int
    var kind: Kind = .chars

    static let minSamples = 3
    static let alpha = 0.2

    mutating func record(units: Int, seconds: Double) {
        guard units > 0, seconds >= 2, seconds <= 600 else { return }
        let speed = Double(units) / (seconds / 60)
        unitsPerMinute = unitsPerMinute.map { $0 + Self.alpha * (speed - $0) } ?? speed
        samples += 1
    }

    func minutes(for units: Int) -> Int? {
        guard samples >= Self.minSamples, let s = unitsPerMinute, s > 0 else { return nil }
        return Int((Double(units) / s).rounded(.up))
    }

    /// UserDefaults 에서. 망가진 값(무한대 · 음수)은 모르는 것으로 — 그 값으로 셈하면 "0분" 이나 수천 시간이 뜬다.
    static func load(_ kind: Kind = .chars) -> ReadingSpeed {
        let d = UserDefaults.standard
        let key = "reader.\(kind.rawValue)"
        // double(forKey:) 로 읽는다 — 글자로 저장된 값(설정 가져오기 · 실행 인자)도 숫자로 읽힌다.
        let v = d.object(forKey: key) == nil ? nil : d.double(forKey: key)
        let ok = v.map { $0.isFinite && $0 > 0 } ?? false
        return ReadingSpeed(unitsPerMinute: ok ? v : nil, samples: ok ? max(d.integer(forKey: "\(key).samples"), 0) : 0, kind: kind)
    }

    func save() {
        UserDefaults.standard.set(unitsPerMinute, forKey: "reader.\(kind.rawValue)")
        UserDefaults.standard.set(samples, forKey: "reader.\(kind.rawValue).samples")
    }
}

/// 지면 아래 줄(안드로이드 `CpReadingFooter`): 얇은 진도 막대 · 왼쪽 · 가운데 · 오른쪽. 막대는 강조색, 빈 곳은 지면의 흐린 글자색
/// 28% — UI 막대색은 세피아 · 회색 지면에 묻혀 얼마 남았는지 보이지 않았다(안드로이드 0.29).
struct ReadingFooter: View {
    let title: String
    let frame: PageFrame
    let items: [FooterItem]
    let speed: ReadingSpeed
    let ink: Color

    var body: some View {
        // 1분마다 다시 그린다(시계 · 배터리 · 남은 시간). 줄 하나라 가볍다 — 시계가 없을 때 멈추게 하면 분이 바뀌는 때에 맞추지 못한다.
        TimelineView(.everyMinute) { context in
            VStack(spacing: 6) {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(ink.opacity(0.28))
                        Capsule().fill(CpColor.accent).frame(width: g.size.width * min(max(frame.percent / 100, 0), 1))
                    }
                }
                .frame(height: 2)
                HStack(spacing: 0) {
                    Text(text(items[0], context.date)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                    Text(text(items[1], context.date)).lineLimit(1).padding(.horizontal, 12)
                    Text(text(items[2], context.date)).lineLimit(1).frame(maxWidth: .infinity, alignment: .trailing)
                }
                .font(CpFont.caption)
                .foregroundStyle(ink)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func text(_ item: FooterItem, _ now: Date) -> String {
        switch item {
        case .none: return ""
        case .bookTitle: return title
        case .chapterTitle: return frame.section
        case .clock: return FooterText.clock(now)
        case .battery:
            UIDevice.current.isBatteryMonitoringEnabled = true
            let level = UIDevice.current.batteryLevel
            // 모르면(시뮬레이터 −1) 비운다 — "배터리 -100%" 보다 낫다.
            return level < 0 ? "" : "배터리 \(Int((level * 100).rounded()))%"
        case .page:
            let n = frame.hasRight ? "\(frame.page + 1)–\(frame.page + 2) / \(frame.pages)" : "\(frame.page + 1) / \(frame.pages)"
            // 인쇄된 쪽 번호가 파일 순서와 다르면 앞에 — "iv · 4 / 230"(안드로이드 PDF).
            return frame.pageLabel.map { "\($0) · \(n)" } ?? n
        case .percent: return "\(Int(frame.percent.rounded()))%"
        case .chapterLeft: return FooterText.chapterLeft(frame.pagesLeft ?? max(frame.pages - frame.page - 1 - (frame.hasRight ? 1 : 0), 0))
        // 남은 시간: 책은 글자, PDF 는 쪽으로 잰다(글자를 읽을 수 없다 — 안드로이드와 같다). 두 쪽이면 오른쪽 쪽 뒤부터 남은 것.
        case .chapterTime:
            let left = frame.isPdf ? frame.pagesLeft ?? -1 : frame.charsLeftChapter
            return left < 0 ? "" : speed.minutes(for: left).map { "이 장 \(FooterText.minutes($0)) 남음" } ?? ""
        case .bookTime:
            let left = frame.isPdf ? frame.pages - 1 - frame.page - (frame.hasRight ? 1 : 0) : frame.charsLeftBook
            return left < 0 ? "" : speed.minutes(for: left).map { "책 \(FooterText.minutes($0)) 남음" } ?? ""
        }
    }
}

/// 모든 보기 설정 › 하단 정보(구상안 28): 미리 보기 · 왼쪽 · 가운데 · 오른쪽.
struct FooterSettingsScreen: View {
    @ObservedObject var prefs: ReaderPrefs
    let pick: (Int) -> Void
    let back: () -> Void

    static let slots = ["왼쪽", "가운데", "오른쪽"]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "하단 정보", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            CpSectionLabel(text: "미리 보기").padding(.horizontal, CpMetrics.gutter).padding(.top, 14).padding(.bottom, 6)
            HStack(spacing: 0) {
                Text(preview(0)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                Text(preview(1)).lineLimit(1).padding(.horizontal, 12)
                Text(preview(2)).lineLimit(1).frame(maxWidth: .infinity, alignment: .trailing)
            }
            .font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            .padding(.horizontal, 16).padding(.vertical, 18)
            .overlay(alignment: .top) {
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(CpColor.textMuted.opacity(0.28))
                        Capsule().fill(CpColor.accent).frame(width: g.size.width * 0.29)
                    }
                }
                .frame(height: 2).padding(.horizontal, 16).padding(.top, 10)
            }
            .background(CpColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall))
            .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall).stroke(CpColor.outline, lineWidth: 1))
            .padding(.horizontal, CpMetrics.gutter).padding(.bottom, 10)
            ForEach(0..<3, id: \.self) { i in
                CpLinkRow(label: Self.slots[i], value: prefs.footer[i].label) { pick(i) }
            }
            Spacer()
        }
    }

    /// 안드로이드 미리 보기의 본보기 값(책 제목 · 1장 · 3 / 12 · 29% · 9쪽 남음). 시간은 본보기 글로.
    private func preview(_ i: Int) -> String {
        let item = prefs.footer[i]
        return item == .bookTitle ? "책 제목" : item.sample ?? ""
    }
}

/// "<자리>에 보일 것"(구상안 28A): 항목마다 회색 본보기 글.
struct FooterItemScreen: View {
    @ObservedObject var prefs: ReaderPrefs
    let slot: Int
    let back: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "\(FooterSettingsScreen.slots[slot])에 보일 것",
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(FooterItem.allCases) { item in
                        CpRadioRow(title: item.label, selected: prefs.footer[slot] == item, subtitle: item.sample) {
                            prefs.footer[slot] = item
                            back()
                        }
                    }
                }
                .padding(.bottom, 24)
            }
        }
    }
}
