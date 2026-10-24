import OloCore
import SwiftUI

/// 읽은 시간의 방식(안드로이드 `ReadingMode`). 저장값은 안드로이드와 같다 — 백업을 두 앱이 주고받는다.
enum ReadingMode: String { case read = "READ", listen = "LISTEN", auto = "AUTO" }

/// 그 기기의 날짜를 1970-01-01 부터 센 날(안드로이드 `LocalDate.toEpochDay`). 기록하는 순간의 벽시계 · 시간대로 정한다.
func epochDay(_ date: Date, calendar: Calendar = .current) -> Int64 {
    let c = calendar.dateComponents([.year, .month, .day], from: date)
    var utc = Calendar(identifier: .gregorian)
    utc.timeZone = TimeZone(identifier: "UTC")!
    let day = utc.date(from: c) ?? date
    return Int64((day.timeIntervalSince1970 / 86_400).rounded(.down))
}

/// 쪽이 보이는 동안의 시간(안드로이드 `PageClock`). 한 쪽(두 쪽이면 펼침)은 5분까지만 센다 — 책을 펴 둔 채 자리를 비운 시간이
/// 몇 시간씩 쌓이면 기록을 믿을 수 없다(버리지는 않는다, 자른다). 넘김 · 책 닫기 · 앱이 뒤로 감은 세고, 목차 · 찾기 · 진도 막대 ·
/// 링크로 건너뛰기 전 쪽은 세지 않는다(쪽을 고르던 시간이다). 듣는 동안은 여기서 세지 않는다(듣기가 따로 센다).
@MainActor
final class ReadingClock {
    static let cap: TimeInterval = 5 * 60

    private let store: ReadingStore
    private let bookId: String
    private var page: String?
    private var since: Date?
    /// 이 쪽에서 이미 센 시간(5분 자르기는 쪽마다).
    private var spent: TimeInterval = 0
    private var mode = ReadingMode.read
    private var running = true

    init(store: ReadingStore, bookId: String) {
        self.store = store
        self.bookId = bookId
    }

    /// 쪽이 바뀌었다. [counted] 면(사람 · 자동이 넘겼다) 앞 쪽의 시간을 적고, 아니면(건너뛰기 · 다시 짜기) 버린다.
    func pageChanged(to key: String, counted: Bool) {
        if counted { flush() }
        page = key
        spent = 0
        since = running ? Date() : nil
    }

    /// 넘기는 주체가 바뀌었다(사람 ↔ 자동 넘김 ↔ 듣기). 쪽은 그대로 — 그때까지를 앞 주체의 시간으로 끊는다.
    func setMode(_ m: ReadingMode) {
        guard m != mode else { return }
        flush()
        mode = m
    }

    /// 앱이 뒤로 감 · 책을 닫음: 지금까지를 적고 멈춘다. 멈춘 동안은 세지 않는다.
    func pause() {
        flush()
        running = false
        since = nil
    }

    func resume() {
        running = true
        if page != nil { since = Date() }
    }

    private func flush() {
        guard let start = since, page != nil else { return }
        let now = Date()
        since = running ? now : nil
        let elapsed = now.timeIntervalSince(start)
        // 시계가 뒤로 갔으면 버린다.
        guard elapsed > 0, mode != .listen else { return }
        let take = min(elapsed, max(Self.cap - spent, 0))
        spent += take
        if take > 0 { store.addTime(bookId: bookId, day: epochDay(now), mode: mode.rawValue, millis: Int64(take * 1000)) }
    }
}

/// 독서 기록의 셈(안드로이드 `ReadingStats.of`). 화면과 떨어져 있어 시험하기 쉽다.
struct ReadingStats {
    struct Book: Identifiable {
        let id: String
        let title: String
        let author: String?
        let item: ShelfItem?
        let finished: Date?
        let millis: Int64
    }

    /// 날마다 읽은 시간(READ 만).
    let days: [Int64: Int64]
    let today: Int64
    let week: Int64
    let year: Int64
    let listenYear: Int64
    let autoYear: Int64
    /// 올해 다 읽은 책(최근 것부터).
    let finished: [Book]
    /// 올해 가장 오래 읽은 책 다섯.
    let longest: [Book]
    /// 기록이 있는 첫 날(달력을 거기까지 넘길 수 있다).
    let firstDay: Int64?

    var isEmpty: Bool { days.isEmpty && listenYear == 0 && autoYear == 0 && finished.isEmpty }

    static func of(times: [TimeRecord], items: [ShelfItem], now: Date = Date(), calendar: Calendar = .current) -> ReadingStats {
        var cal = calendar
        cal.firstWeekday = 1 // 주는 일요일부터(안드로이드와 같다).
        let todayDay = epochDay(now, calendar: cal)
        let weekStart = epochDay(cal.dateInterval(of: .weekOfYear, for: now)?.start ?? now, calendar: cal)
        let yearStart = epochDay(cal.dateInterval(of: .year, for: now)?.start ?? now, calendar: cal)
        var days: [Int64: Int64] = [:]
        var perBookYear: [String: Int64] = [:]
        var perBookAll: [String: Int64] = [:]
        var listen: Int64 = 0, auto: Int64 = 0
        for t in times {
            let ms = t.millis
            // 모르는 방식(새 판이 더한 것)은 셈에서만 뺀다.
            switch ReadingMode(rawValue: t.mode) {
            case .read:
                days[t.day, default: 0] += ms
                perBookAll[t.bookId, default: 0] += ms
                if t.day >= yearStart { perBookYear[t.bookId, default: 0] += ms }
            case .listen: if t.day >= yearStart { listen += ms }
            case .auto: if t.day >= yearStart { auto += ms }
            case nil: break
            }
        }
        let byId = Dictionary(items.map { ($0.book.id, $0) }, uniquingKeysWith: { a, _ in a })
        func book(_ id: String, _ ms: Int64) -> Book {
            let item = byId[id]
            // 서재에서 사라진 책(지운 파일)은 이름만 — 누를 수 없다.
            return Book(id: id, title: item?.book.label ?? fileName(id),
                        author: item?.book.author, item: item, finished: item?.finishedAt, millis: ms)
        }
        let yearStartDate = cal.dateInterval(of: .year, for: now)?.start ?? now
        let finished = items.filter { ($0.finishedAt ?? .distantPast) >= yearStartDate }
            .sorted { ($0.finishedAt ?? .distantPast) > ($1.finishedAt ?? .distantPast) }
            .map { book($0.book.id, perBookAll[$0.book.id] ?? 0) }
        let longest = perBookYear.filter { $0.value > 0 }.sorted { $0.value > $1.value }.prefix(5).map { book($0.key, $0.value) }
        return ReadingStats(
            days: days,
            today: days[todayDay] ?? 0,
            week: days.filter { $0.key >= weekStart && $0.key <= todayDay }.values.reduce(0, +),
            year: days.filter { $0.key >= yearStart }.values.reduce(0, +),
            listenYear: listen, autoYear: auto, finished: finished, longest: Array(longest),
            firstDay: days.keys.min()
        )
    }

    /// 서재에서 사라진 책의 이름: 책 id("app:소설/옛 일기.txt", "link:<폴더>:옛 일기.txt")의 파일 이름.
    static func fileName(_ id: String) -> String {
        var rest = Substring(id)
        if rest.hasPrefix("app:") { rest = rest.dropFirst(4) } else if rest.hasPrefix("link:") {
            rest = rest.dropFirst(5).drop(while: { $0 != ":" }).dropFirst()
        }
        return String(rest.split(separator: "/").last ?? rest)
    }

    /// 타일의 값(안드로이드 `tileValue`): 60분 미만은 분, 넘으면 시간을 소수 한 자리로 내림, 100시간부터 정수.
    static func tileValue(_ ms: Int64) -> (String, String) {
        let minutes = ms / 60_000
        if minutes < 60 { return ("\(minutes)", "분") }
        let hours = Double(ms) / 3_600_000
        if hours >= 100 { return ("\(Int(hours))", "시간") }
        return (String(format: "%.1f", (hours * 10).rounded(.down) / 10), "시간")
    }

    /// "1분 미만" · "38분" · "2시간" · "6시간 40분"(안드로이드 `durationText`).
    static func duration(_ ms: Int64) -> String {
        let m = ms / 60_000
        if m < 1 { return "1분 미만" }
        if m < 60 { return "\(m)분" }
        return m % 60 == 0 ? "\(m / 60)시간" : "\(m / 60)시간 \(m % 60)분"
    }

    /// 달력 칸의 짙기 0–3: 안 읽음 · 20분 미만 · 40분 미만 · 그 이상.
    static func level(_ ms: Int64) -> Int {
        if ms <= 0 { return 0 }
        let m = ms / 60_000
        return m < 20 ? 1 : m < 40 ? 2 : 3
    }
}

/// 독서 기록(구상안 30, 안드로이드 `ReadingStatsScreen`): 오늘 · 이번 주 · 올해 타일, 달 달력, 올해 다 읽은 것, 가장 오래 읽은 책.
/// 넓은 화면(840 이상)은 두 판 — 왼쪽 320 에 타일 · 달력, 오른쪽에 목록(RESPONSIVE_PLAN). 중간 폭은 가운데 600.
struct ReadingStatsScreen: View {
    @ObservedObject var library: Library
    let open: (LibraryBook) -> Void
    let back: () -> Void

    /// 보고 있는 달(그 달 1일).
    @State private var month = Calendar.current.dateInterval(of: .month, for: Date())?.start ?? Date()
    @State private var showAll = false
    /// 센 결과. 달을 넘기거나 "모두 보기" 를 눌러 다시 그릴 때마다 기록 전체를 다시 읽고 세지 않게 둔다.
    @State private var counted: ReadingStats?

    private var statsKey: String { "\(library.reading.count)|\(library.finished.count)|\(library.toRead.count)|\(library.scanning)" }

    private func count() -> ReadingStats {
        ReadingStats.of(times: library.store.times(), items: library.reading + library.finished + library.toRead)
    }

    var body: some View {
        screen.task(id: statsKey) { counted = count() }
    }

    private var screen: some View {
        VStack(spacing: 0) {
            CpHeader(title: "독서 기록", subtitle: "이 휴대폰에서 읽은 시간",
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            // 서재가 바뀌면(앱을 켜자마자 열어 아직 훑는 중이었다) 다시 센다 — 한 번만 세면 다 읽은 책 · 책 이름이 비었다.
            let stats = counted ?? count()
            Group {
                if stats.isEmpty {
                    CpEmptyMessage(text: "아직 읽은 기록이 없습니다. 책장을 넘기며 읽으면 여기에 쌓입니다.")
                } else {
                    GeometryReader { g in
                        if g.size.width >= 840 {
                            HStack(alignment: .top, spacing: 0) {
                                ScrollView { overview(stats, narrow: true).padding(.vertical, 8) }.frame(width: 320)
                                Rectangle().fill(CpColor.divider).frame(width: 1)
                                ScrollView { lists(stats).padding(.vertical, 8) }
                            }
                        } else {
                            ScrollView {
                                VStack(alignment: .leading, spacing: 0) {
                                    overview(stats, narrow: false)
                                    lists(stats)
                                }
                                .frame(maxWidth: 600)
                                .frame(maxWidth: .infinity)
                                .padding(.bottom, 24)
                            }
                        }
                    }
                }
            }
        }
        .background(CpColor.background)
    }

    // MARK: 타일 · 달력

    private func overview(_ s: ReadingStats, narrow: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                tile("오늘", s.today, narrow)
                tile("이번 주", s.week, narrow)
                tile("올해", s.year, narrow)
            }
            .padding(.horizontal, CpMetrics.gutter).padding(.top, 6)
            calendar(s).padding(.horizontal, CpMetrics.gutter).padding(.top, 16)
        }
    }

    private func tile(_ label: String, _ ms: Int64, _ narrow: Bool) -> some View {
        let (value, unit) = ReadingStats.tileValue(ms)
        return VStack(alignment: .leading, spacing: 4) {
            Text(label).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value).font(.system(size: narrow ? 19 : 24, weight: .bold)).foregroundStyle(CpColor.text)
                Text(unit).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
            }
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(CpColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall))
        .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall).stroke(CpColor.divider, lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label) \(value) \(unit)")
    }

    private func calendar(_ s: ReadingStats) -> some View {
        let cal = Calendar.current
        let first = epochDay(month)
        let count = cal.range(of: .day, in: .month, for: month)?.count ?? 30
        let lead = (cal.component(.weekday, from: month) + 6) % 7 // 일요일 0
        let today = epochDay(Date())
        let monthDays = (0..<Int64(count)).map { first + $0 }
        let read = monthDays.compactMap { s.days[$0] }.filter { $0 > 0 }
        let thisMonth = cal.dateInterval(of: .month, for: Date())?.start ?? Date()
        let firstMonth = s.firstDay.map { Date(timeIntervalSince1970: Double($0) * 86_400) }
            .flatMap { cal.dateInterval(of: .month, for: $0)?.start } ?? thisMonth
        let canBack = month > firstMonth
        let canNext = month < thisMonth
        let ym = cal.dateComponents([.year, .month], from: month)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 0) {
                CpIconButton(icon: .back, label: "앞 달", color: canBack ? CpColor.text : CpColor.outline) {
                    if canBack { month = cal.date(byAdding: .month, value: -1, to: month) ?? month }
                }
                // 숫자를 글로 — Text 에 정수를 그대로 넣으면 지역 형식으로 "2,026년" 이 된다.
                Text(verbatim: "\(String(ym.year ?? 0))년 \(String(ym.month ?? 0))월").font(CpFont.label).foregroundStyle(CpColor.text)
                CpIconButton(icon: .forward, label: "다음 달", color: canNext ? CpColor.text : CpColor.outline) {
                    if canNext { month = cal.date(byAdding: .month, value: 1, to: month) ?? month }
                }
                Spacer(minLength: 4)
                Text(read.isEmpty ? "읽은 날 없음" : "\(read.count)일 읽음 · \(ReadingStats.duration(read.reduce(0, +)))")
                    .font(CpFont.caption).foregroundStyle(CpColor.textMuted).lineLimit(1)
            }
            HStack(spacing: 4) {
                ForEach(["일", "월", "화", "수", "목", "금", "토"], id: \.self) {
                    Text($0).font(CpFont.caption).foregroundStyle(CpColor.textMuted).frame(maxWidth: .infinity)
                }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 7), spacing: 4) {
                ForEach(0..<(lead + count), id: \.self) { i in
                    if i < lead {
                        Color.clear.aspectRatio(1.25, contentMode: .fit)
                    } else {
                        let d = first + Int64(i - lead)
                        let ms = s.days[d] ?? 0
                        let lv = ReadingStats.level(ms)
                        RoundedRectangle(cornerRadius: 6).fill(CpColor.accent.opacity([0, 0.25, 0.5, 0.85][lv]))
                            .aspectRatio(1.25, contentMode: .fit)
                            .overlay(Text("\(i - lead + 1)").font(CpFont.caption)
                                .foregroundStyle(d > today ? CpColor.textMuted.opacity(0.5) : lv == 3 ? CpColor.onAccent : CpColor.text))
                            .overlay { if d == today { RoundedRectangle(cornerRadius: 6).stroke(CpColor.text, lineWidth: 1.5) } }
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel("\(ym.month ?? 0)월 \(i - lead + 1)일 \(ms > 0 ? ReadingStats.duration(ms) : "안 읽음")")
                    }
                }
            }
        }
    }

    // MARK: 목록

    private func lists(_ s: ReadingStats) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            label(s.finished.isEmpty ? "올해 다 읽은 것" : "올해 다 읽은 것 · 책 \(s.finished.count)")
            if s.finished.isEmpty {
                Text("올해 다 읽은 책이 아직 없습니다").font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                    .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 8)
            }
            ForEach(showAll ? s.finished : Array(s.finished.prefix(3))) { b in
                finishedRow(b)
            }
            if s.finished.count > 3 {
                Button { showAll.toggle() } label: {
                    Text(showAll ? "접기" : "모두 보기").font(CpFont.label).foregroundStyle(CpColor.accentText)
                        .padding(.horizontal, CpMetrics.gutter).frame(minHeight: CpMetrics.touchTarget).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if !s.longest.isEmpty {
                label("가장 오래 읽은 책 · 올해").padding(.top, 8)
                let top = Double(max(s.longest.first?.millis ?? 1, 1))
                ForEach(s.longest) { b in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(b.title).font(CpFont.body).foregroundStyle(CpColor.text).lineLimit(1)
                            Spacer()
                            Text(ReadingStats.duration(b.millis)).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                        }
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Capsule().fill(CpColor.progressTrack)
                                Capsule().fill(CpColor.accent).frame(width: g.size.width * max(Double(b.millis) / top, 0.02))
                            }
                        }
                        .frame(height: 6)
                    }
                    .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 7)
                    .accessibilityElement(children: .combine)
                }
            }
            if s.listenYear > 0 || s.autoYear > 0 {
                // 없는 쪽은 뺀다 — "자동 넘김 1분 미만" 은 조금 썼다는 뜻으로 읽힌다.
                let parts = [s.listenYear > 0 ? "들은 시간 \(ReadingStats.duration(s.listenYear))" : nil,
                             s.autoYear > 0 ? "자동 넘김 \(ReadingStats.duration(s.autoYear))" : nil].compactMap { $0 }
                Text((["올해 따로 센 시간"] + parts).joined(separator: " · "))
                    .font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, CpMetrics.gutter).padding(.top, 14)
            }
            // 만화(웹툰 1분 규칙)는 iOS 에 만화가 생길 때 더한다.
            CpCaption(text: "책장을 넘기며 읽은 시간만 셉니다. 한 쪽에 5분 넘게 머물면 5분까지만 셉니다. 듣기 · 자동 넘김은 따로 셉니다.")
                .padding(.top, 14)
        }
    }

    private func label(_ t: String) -> some View {
        CpSectionLabel(text: t).padding(.horizontal, CpMetrics.gutter).padding(.top, 20).padding(.bottom, 4)
    }

    private func finishedRow(_ b: ReadingStats.Book) -> some View {
        let sub = [b.author, b.millis > 0 ? ReadingStats.duration(b.millis) : nil].compactMap { $0 }.joined(separator: " · ")
        return HStack {
            CpListRow(icon: .book, tile: CpColor.tileDocument, title: b.title, subtitle: sub.isEmpty ? "책" : sub,
                      action: b.item.map { item in { open(item.book) } })
            Text(b.finished.map(monthDay) ?? "").font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
        }
        .padding(.horizontal, CpMetrics.gutter)
    }
}
