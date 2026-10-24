import ImageIO
import OloCore
import SwiftUI
import UIKit

/// 웹툰으로 연 화 하나: 책 · 그림 크기. [entry] 는 작품을 모르면(낱개 파일) nil.
final class WebtoonEpisode: @unchecked Sendable {
    let entry: WorkEntry?
    let unitId: String
    let label: String
    let book: IosComicBook
    let sizes: [ImageSize?]
    /// Kotlin 목록 그대로(칸 배치에 다시 넘긴다).
    let rawSizes: [Any]

    init(entry: WorkEntry?, unitId: String, label: String, book: IosComicBook) {
        self.entry = entry
        self.unitId = unitId
        self.label = label
        self.book = book
        rawSizes = ComicImages.sizes(book)
        sizes = rawSizes.map { $0 as? ImageSize }
    }
}

/// 웹툰 보기(구상안 39 · 2A, 안드로이드 `ComicWebtoon`): 그림을 틈 없이 세로로 잇고 밀어 내린다. 다음 화는 경계 띠를 두고 이어 붙이고,
/// 화면 위에 걸린 화가 "지금 화" 다 — 위 막대 · 책갈피 · 진도가 그 화를 따른다. 칸 배치 · 띠 나누기 · 자리 셈은 Kotlin
/// (`IosWebtoon` · 공통 `WebtoonChain`), 여기는 상태와 그리기만.
@MainActor
final class WebtoonModel: ObservableObject {
    let store: ReadingStore
    weak var library: Library?
    let work: Work?
    @Published private(set) var episodes: [WebtoonEpisode] = []
    /// 기둥의 칸들과 그 배치(폭이 바뀌거나 화가 붙으면 다시 만든다).
    private(set) var cells: [WebtoonCell] = []
    private(set) var chain: WebtoonChain?
    private(set) var columnWidth: CGFloat = 0
    private(set) var viewSize: CGSize = .zero
    /// 칸 배치가 바뀐 횟수(화면이 칸 그림을 새로 놓는다).
    @Published private(set) var layoutToken = 0
    /// 화면 위(기둥 좌표, 확대 1 기준).
    @Published private(set) var top: CGFloat = 0
    /// 화면을 옮겨 달라는 요청(기둥 좌표 · 움직임 · 번호).
    @Published private(set) var scrollTo: (y: CGFloat, animated: Bool, token: Int) = (0, false, 0)
    @Published var panel: Panel = .none
    @Published var autoOn = false
    @Published var autoPaused = false
    @Published var ended = false
    @Published var toast: String?
    /// 처음 열 때 갈 자리(화 0 의 그림 · 비율).
    private var start: (index: Int, fraction: Float)
    private var placed = false
    private var loadingNext = false
    private var failedNext: String?
    /// 확대 배율(기둥 뷰가 알린다). 한 화면의 높이는 화면 높이 ÷ 배율 — 이것을 빼면 2배 확대에서 한 번 누를 때 두 화면 가까이 건너뛰어
    /// 내용을 놓쳤고, 아직 아래 절반이 남았는데 끝 판이 떴다.
    var zoom: CGFloat = 1
    /// 마지막 다시 짜기가 다음 화를 덧붙인 것인가(폭이 바뀐 것이 아니라). 덧붙이기면 확대 · 놓인 칸을 그대로 두고 길이만 늘린다.
    private(set) var appended = false

    /// 화면 하나의 높이(기둥 좌표).
    var screen: CGFloat { viewSize.height / max(zoom, 1) }

    /// 더 붙일 화가 없다(마지막 화 · 다음 화를 열지 못함). 자동 스크롤이 끝에서 멈춘다.
    var noMoreEpisodes: Bool { nextEntry.map { failedNext == $0.unit.id } ?? true }
    private var finishedSaved = Set<String>()
    private var saveTask: Task<Void, Never>?
    /// 읽은 시간(안드로이드 `ScrollClock`): 화면이 움직인 사이를 1분까지, 보던 화에. 규칙 · 시험은 Kotlin `WebtoonClock`.
    private let clock = WebtoonClock()

    enum Panel { case none, bar, view, settings, touchZones, episodes, bookmarks }

    init(first: WebtoonEpisode, work: Work?, store: ReadingStore, library: Library) {
        self.work = work
        self.store = store
        self.library = library
        let saved = store.comicProgress()[first.unitId]
        // 다 읽고 끝에서 멈춘 화는 처음부터(쪽 넘김과 같다).
        if let saved, !(saved.finished && Int(saved.page) >= first.sizes.count - 1) {
            start = (Int(saved.page), saved.offset?.floatValue ?? 0)
        } else {
            start = (0, 0)
        }
        episodes = [first]
    }

    // MARK: 배치

    /// 화면 크기 · 그림 폭이 정해졌다(또는 바뀌었다). 보던 칸(화 · 그림 · 비율)을 지킨다 — 폭이 바뀌면 높이가 모두 바뀐다.
    func layOut(view: CGSize, percent: Int) {
        let width = (view.width * CGFloat(percent) / 100).rounded()
        guard width > 0, view.height > 0 else { return }
        if width == columnWidth && view == viewSize && !cells.isEmpty { return }
        let anchor = placed ? here() : nil
        viewSize = view
        columnWidth = width
        rebuild(appended: false)
        if let anchor, let chain {
            request(CGFloat(chain.offsetOf(k: Int32(anchor.k), index: Int32(anchor.i), fraction: anchor.f)))
        } else if let chain {
            placed = true
            request(CGFloat(chain.offsetOf(k: 0, index: Int32(start.index), fraction: start.fraction)))
        }
    }

    private func rebuild(appended: Bool) {
        self.appended = appended
        let sizes = episodes.map(\.rawSizes)
        chain = IosWebtoon.shared.chain(episodes: sizes, width: Float(columnWidth))
        cells = IosWebtoon.shared.cells(episodes: sizes, width: Float(columnWidth), pixelScale: Float(ScreenInfo.scale))
        layoutToken += 1
    }

    var total: CGFloat { CGFloat(chain?.total ?? 0) }

    private func request(_ y: CGFloat, animated: Bool = false) {
        let y = min(max(y, 0), max(total - screen, 0))
        scrollTo = (y, animated, scrollTo.token + 1)
        top = y
        // 화면이 움직였다는 알림은 이 자리와 같아 걸러지므로, 다음 화 열기는 여기서도 본다 — 진도 막대로 화 끝에 가면 다음 화가
        // 붙지 않아 경계 띠 대신 끝 판이 떴다. 자리 적기도 같다(첫 자리는 적지 않는다 — 연 것만으로 진도가 바뀌면 안 된다).
        loadNextIfNear()
        if placedOnce { scheduleSave() }
        placedOnce = true
    }
    private var placedOnce = false

    /// 지금 화면 위의 (화, 그림, 비율).
    func here() -> (k: Int, i: Int, f: Float) {
        guard let chain else { return (0, 0, 0) }
        let t = chain.at(y: Float(top))
        return (Int(t.first?.intValue ?? 0), Int(t.second?.intValue ?? 0), t.third?.floatValue ?? 0)
    }

    var currentEpisode: WebtoonEpisode? { episodes.indices.contains(here().k) ? episodes[here().k] : episodes.first }

    /// 지금 화의 몇 %(0..1, 공통 `WebtoonChain.fraction`).
    var fraction: Double { Double(chain?.fraction(y: Float(top), viewHeight: Float(viewSize.height)) ?? 0) }

    var title: String {
        let label = currentEpisode?.label ?? ""
        return [work?.title, label].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
    }

    var subtitle: String { "\(currentEpisode?.label ?? "") · \(Int((fraction * 100).rounded()))%" }

    // MARK: 밀기

    /// 화면이 움직였다(손 · 자동 스크롤 · 요청).
    func scrolled(_ y: CGFloat) {
        guard abs(y - top) > 0.5 else { return }
        top = y
        loadNextIfNear()
        scheduleSave()
        if let e = currentEpisode { record(clock.moved(unitId: e.unitId, now: Self.nowMs())) }
    }

    // MARK: 읽은 시간

    /// 앱이 뒤로 감 · 쪽 넘김으로 바꿈 · 닫음: 마지막 움직임부터 지금까지(최대 1분)를 적고 멈춘다.
    func pauseClock() {
        record(clock.pause(now: Self.nowMs()))
        flushTime()
    }

    /// 다시 앞에 왔다 — 뒤에 있던 시간은 다음 움직임에 붙지 않는다.
    func resumeClock() { clock.resume(now: Self.nowMs()) }

    /// 아직 적지 않은 시간(화 → ms). 움직임은 화면 그림마다(자동 스크롤이면 1초에 60번) 오는데 한 번 적을 때마다 기록 파일 전체를
    /// 다시 쓴다 — 모아 두었다가 30초가 차거나 손을 멈춤 · 멈춤 · 닫을 때 적는다.
    private var pendingTime: [String: Int64] = [:]

    private func record(_ t: WebtoonTime?) {
        guard let t else { return }
        pendingTime[t.unitId, default: 0] += t.millis
        if pendingTime.values.reduce(0, +) >= 30_000 { flushTime() }
    }

    private func flushTime() {
        let day = epochDay(Date())
        // 쪽 넘김 만화와 같은 줄(권 id · 그날 · 읽기)에 더한다 — 독서 기록이 한 권의 시간으로 합친다(안드로이드 `TimeItem.COMIC`).
        for (unit, ms) in pendingTime { store.addTime(bookId: unit, day: day, mode: ReadingMode.read.rawValue, millis: ms) }
        pendingTime = [:]
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// 손을 멈춘 뒤에 적는다 — 끄는 동안 매번 쓰면 파일 쓰기가 쌓인다(안드로이드 SAVE_DELAY_MS).
    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            self?.savePlace()
        }
    }

    private func savePlace() {
        flushTime()
        guard let chain else { return }
        let (k, i, f) = here()
        guard episodes.indices.contains(k) else { return }
        let e = episodes[k]
        let end = chain.reachedEnd(k: Int32(k), y: Float(top), viewHeight: Float(screen))
        _ = store.saveComic(unitId: e.unitId, page: Int32(i), pageCount: Int32(e.sizes.count), atEnd: end, half: nil, offset: KotlinFloat(float: f))
        // 지나온 화는 다 읽은 것(안드로이드와 같다).
        for j in 0..<k where finishedSaved.insert(episodes[j].unitId).inserted {
            let b = episodes[j]
            _ = store.saveComic(unitId: b.unitId, page: Int32(max(b.sizes.count - 1, 0)), pageCount: Int32(b.sizes.count), atEnd: true, half: nil,
                                offset: KotlinFloat(float: 1))
        }
    }

    func saveNow() {
        saveTask?.cancel()
        savePlace()
    }

    /// 누름: 한 화면(85%)씩. 끝에서 더 내리면 끝 판.
    func step(_ direction: Int) {
        guard viewSize.height > 0 else { return }
        if direction > 0, atBottom {
            // 다음 화가 있으면 붙기를 기다린다. 없거나 열지 못했으면 끝 판(이어서 읽기 · 서재로).
            if let next = nextEntry, failedNext != next.unit.id { loadNextIfNear() } else { ended = true }
            return
        }
        request(top + CGFloat(direction) * screen * CGFloat(IosWebtoon.shared.SCREEN_STEP), animated: true)
    }

    var atBottom: Bool { top + screen >= total - 1 }

    /// 진도 막대: 지금 화 안의 자리.
    func seek(_ f: Double) {
        guard let chain else { return }
        let k = Int32(here().k)
        let y = CGFloat(truncating: chain.starts[Int(k)]) + CGFloat(f) * CGFloat(chain.readable(k: k, viewHeight: Float(viewSize.height)))
        request(y)
    }

    func go(episode k: Int, index: Int) {
        guard let chain, episodes.indices.contains(k) else { return }
        request(CGFloat(chain.offsetOf(k: Int32(k), index: Int32(index), fraction: 0)))
        panel = .none
    }

    // MARK: 다음 화

    /// 다음 화.
    var nextEntry: WorkEntry? {
        guard let work, let last = episodes.last else { return nil }
        return ComicReading.shared.nextAfter(work: work, unitId: last.unitId)
    }

    /// 기둥 끝이 두 화면 안으로 들어오면 다음 화를 연다(안드로이드 `PRELOAD_SCREENS`). 열지 못하면 끝 판에서 다시 시도한다.
    private func loadNextIfNear() {
        guard !loadingNext, top + viewSize.height * 3 >= total, let next = nextEntry, failedNext != next.unit.id,
              let library, let file = library.comicFile(next.unit.id) else { return }
        loadingNext = true
        let label = next.label
        Task { [weak self] in
            let opened: WebtoonEpisode? = await Task.detached(priority: .userInitiated) {
                let book = file.open()
                return book.map { WebtoonEpisode(entry: next, unitId: next.unit.id, label: label, book: $0) }
            }.value
            guard let self else { return }
            loadingNext = false
            if let opened { episodes.append(opened); rebuild(appended: true) } else { failedNext = next.unit.id }
        }
    }

    // MARK: 책갈피

    /// 지금 화면 위 그림에 책갈피가 있나.
    var bookmarked: Bool {
        guard let e = currentEpisode else { return false }
        return store.comicBookmarks(unitId: e.unitId).contains { Int(truncating: $0) == here().i }
    }

    func toggleBookmark() {
        guard let e = currentEpisode else { return }
        let on = store.toggleComicBookmark(unitId: e.unitId, pages: [KotlinInt(int: Int32(here().i))])
        toast = on ? "책갈피를 꽂았습니다" : "책갈피를 뺐습니다"
        objectWillChange.send()
    }

    func marks() -> [Int] {
        guard let e = currentEpisode else { return [] }
        return store.comicBookmarks(unitId: e.unitId).map { Int(truncating: $0) }
    }

    func close() {
        saveNow()
        pauseClock()
        for e in episodes { e.book.close() }
    }

    // MARK: 표지 고르기(구상안 44 ⑤)

    /// 화면 위 틀([frame], 화면 좌표) 아래의 그림 조각들. 틀 위가 걸친 화 안에서만 — 경계 띠 · 다음 화는 버린다(공통 `pieces`).
    func coverJob(frame: CGRect) -> WebtoonCoverJob? {
        guard let chain, columnWidth > 0 else { return nil }
        let top = Float(self.top + frame.minY), bottom = Float(self.top + frame.maxY)
        let k = Int(chain.episodeAt(y: top))
        guard episodes.indices.contains(k) else { return nil }
        let pieces = chain.pieces(k: Int32(k), top: top, bottom: bottom)
        guard !pieces.isEmpty else { return nil }
        let left = (viewSize.width - columnWidth) / 2
        return WebtoonCoverJob(episode: episodes[k], pieces: pieces, columnFrom: Double((frame.minX - left) / columnWidth),
                               columnTo: Double((frame.maxX - left) / columnWidth), frameHeight: Double(frame.height))
    }

    // MARK: 그림

    private var sources: [String: CGImageSource] = [:]
    private var sourceOrder: [String] = []

    /// 칸 하나의 그림: 원본의 그 행만 잘라 화면 폭으로 그린다. 그림은 칸마다 따로 풀어 화면 밖 칸은 내려놓는다(800×30000 을 통째로
    /// 풀면 메모리 한도에 걸린다). 실패하면 nil.
    nonisolated func strip(_ cell: WebtoonCell, episode: WebtoonEpisode, width: CGFloat, color: Int, source: CGImageSource?) -> UIImage? {
        guard cell.known, let source,
              let size = episode.sizes.indices.contains(Int(cell.index)) ? episode.sizes[Int(cell.index)] : nil,
              let full = Self.decode(source, columnPx: max(width * ScreenInfo.scale, 1)) else { return nil }
        // 머리의 크기와 실제 그림 크기가 다르면(깨진 머리) 비율로 맞춘다.
        let ratio = CGFloat(full.height) / CGFloat(max(size.height, 1))
        let rows = CGRect(x: 0, y: CGFloat(cell.rowFrom) * ratio, width: CGFloat(full.width), height: CGFloat(cell.rowTo - cell.rowFrom) * ratio).integral
        guard let piece = full.cropping(to: rows) else { return nil }
        let px = max(width * ScreenInfo.scale, 1)
        let h = max((px * CGFloat(piece.height) / CGFloat(max(piece.width, 1))).rounded(), 1)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let img = UIGraphicsImageRenderer(size: CGSize(width: px, height: h), format: format).image { ctx in
            ctx.cgContext.interpolationQuality = .high
            UIImage(cgImage: piece).draw(in: CGRect(x: 0, y: 0, width: px, height: h))
        }
        return ComicImages.colored(img, mode: color)
    }

    /// 그림을 기둥 폭에 맞춰 줄여 푼다(원본이 더 넓을 때만). 원본 그대로 풀면 800×30000 한 장이 96MB — 띠마다 따로 푸니 램이 적은
    /// iPhone 에서 한도를 넘을 수 있었다. 줄일 수 없으면(원본이 기둥보다 좁다) 그대로.
    nonisolated private static func decode(_ source: CGImageSource, columnPx: CGFloat) -> CGImage? {
        if let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
           let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue, let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue,
           w > Double(columnPx) * 1.05, w > 0 {
            let longest = max(w, h) * Double(columnPx) / w
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: longest.rounded(.up),
                kCGImageSourceCreateThumbnailWithTransform: false, kCGImageSourceShouldCacheImmediately: false,
            ]
            if let small = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) { return small }
        }
        return CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCache: false] as CFDictionary)
    }

    /// 그림 원본(압축 속 바이트)을 몇 장만 쥐고 있는다 — 한 그림의 띠 여러 개가 같은 원본을 쓴다.
    func source(_ episode: WebtoonEpisode, _ index: Int) -> CGImageSource? {
        let key = "\(episode.unitId)#\(index)"
        if let s = sources[key] { return s }
        guard let data = episode.book.data(i: Int32(index)), let s = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        sources[key] = s
        sourceOrder.append(key)
        if sourceOrder.count > 6 { sources[sourceOrder.removeFirst()] = nil }
        return s
    }
}

// MARK: - 화면

struct WebtoonScreen: View {
    @ObservedObject var model: WebtoonModel
    @ObservedObject var prefs: ReaderPrefs
    @ObservedObject var brightness: ScreenBrightness
    let library: Library
    /// 화면 전체 크기와 안전 영역(리더가 넘긴다 — 리더는 안전 영역을 무시하고 화면 전체에 그리므로 여기서 다시 잴 수 없다).
    let full: CGSize
    let insets: EdgeInsets
    /// 보는 방식을 바꿨다(자동 · 쪽 넘김 · 웹툰 — nil 은 자동).
    let onView: (String?) -> Void
    let openBook: (WorkEntry) -> Void
    /// 보던 장면에서 표지를 고르는 중이면 고른 그림을 저장하는 일(구상안 44 ⑤). 못 쓰면 false.
    var pickCover: ((UIImage) -> Bool)?
    let close: () -> Void
    /// 그림 폭을 끄는 동안(놓기 전) 미리 보이는 값.
    @State private var previewPercent: Int?
    /// 표지 틀(화면 좌표). 기둥 폭을 안 뒤에 놓는다.
    @State private var coverFrame: IosCovers.FrameBox?
    @State private var pinchBase: IosCovers.FrameBox?

    var body: some View {
        Group {
            let wide = full.width > full.height || min(UIScreen.main.bounds.width, UIScreen.main.bounds.height) >= ReaderPrefs.wideScreen
            let percent = previewPercent ?? Int(IosWebtoon.shared.percent(saved: Int32(wide ? prefs.webtoonWide : prefs.webtoonNarrow), wide: wide))
            ZStack {
                comicPaper.paperColor
                WebtoonCanvas(model: model, color: prefs.comicColor, zones: prefs.touchZones,
                              auto: model.autoOn && !model.autoPaused && model.panel == .none && pickCover == nil,
                              secondsPerScreen: CGFloat(truncating: IosWebtoon.shared.secondsPerScreen[Int(IosWebtoon.shared.speed(saved: Int32(prefs.webtoonSpeed)))]),
                              onMenu: {
                                  withAnimation(.easeOut(duration: 0.18)) {
                                      if model.autoOn && model.panel == .none { model.autoPaused.toggle() } else { model.panel = model.panel == .none ? .bar : .none }
                                  }
                              },
                              onAutoEnd: { model.autoOn = false },
                              onPinch: pickCover == nil ? nil : { scale, ended in
                                  // 두 손가락은 그림을 키우지 않고 틀을 키운다 — 고르는 동안 확대는 꺼 둔다.
                                  guard let base = pinchBase ?? coverFrame else { return }
                                  pinchBase = ended ? nil : base
                                  coverFrame = IosCovers.shared.resizeFrame(base: base, scale: Double(scale), viewWidth: Double(full.width),
                                                                            viewHeight: Double(full.height), columnWidth: Double(model.columnWidth))
                              })
                if let save = pickCover {
                    if let box = coverFrame {
                        WebtoonCoverFrame(model: model, box: Binding(get: { box }, set: { coverFrame = $0 }), full: full, insets: insets,
                                          back: close, save: save)
                            .environment(\.colorScheme, .dark)
                    }
                } else if model.bookmarked {
                    CpRibbon().padding(.top, insets.top).padding(.trailing, 20 + insets.trailing)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                        .allowsHitTesting(false)
                }
                if model.autoOn && model.panel == .none {
                    AutoScrollPill(speed: Int(IosWebtoon.shared.speed(saved: Int32(prefs.webtoonSpeed))), paused: model.autoPaused,
                                   onSpeed: { prefs.webtoonSpeed = $0 }, onPause: { model.autoPaused.toggle() }, onClose: { model.autoOn = false })
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, 44 + insets.bottom)
                }
                if pickCover == nil {
                    panels(insets: insets, wide: full.width >= 600, percent: percent, wideClass: wide)
                        .environment(\.colorScheme, .dark)
                }
                if let toast = model.toast {
                    CpToast(message: toast).frame(maxHeight: .infinity, alignment: .bottom).padding(.bottom, 60 + insets.bottom)
                        .allowsHitTesting(false)
                        .task(id: toast) {
                            try? await Task.sleep(for: .milliseconds(1500))
                            if !Task.isCancelled { model.toast = nil }
                        }
                }
                if model.ended && pickCover == nil {
                    ComicEndCard(label: model.currentEpisode?.label ?? "", workTitle: model.work?.title ?? "", next: model.nextEntry,
                                 webtoon: true, library: library, onNext: { openBook($0) }, toLibrary: close,
                                 dismiss: { model.ended = false })
                        .environment(\.colorScheme, .dark)
                }
            }
            .frame(width: full.width, height: full.height)
            .onAppear {
                model.layOut(view: full, percent: percent)
                if pickCover != nil, coverFrame == nil, model.columnWidth > 0 {
                    coverFrame = IosCovers.shared.startFrame(viewWidth: Double(full.width), viewHeight: Double(full.height),
                                                             columnWidth: Double(model.columnWidth))
                }
            }
            #if DEBUG
            // 화면 확인용: -olo-toon-seek <0..1> 은 지금 화의 그 자리로, -olo-toon-panel bar|view|episodes|bookmarks 는 판을,
            // -olo-toon-auto 는 자동 스크롤을 연다. 출시 빌드에는 없다.
            .task {
                try? await Task.sleep(for: .milliseconds(600))
                let args = CommandLine.arguments
                if let i = args.firstIndex(of: "-olo-toon-seek"), i + 1 < args.count, let f = Double(args[i + 1]) { model.seek(f) }
                if let i = args.firstIndex(of: "-olo-toon-panel"), i + 1 < args.count {
                    model.panel = ["bar": .bar, "view": .view, "episodes": .episodes, "bookmarks": .bookmarks][args[i + 1]] ?? .none
                }
                if args.contains("-olo-toon-auto") { model.autoOn = true }
            }
            #endif
            .onChange(of: full) { _, v in model.layOut(view: v, percent: percent) }
            // 기둥 폭을 알면 틀을 놓고, 화면이 돌거나 폭이 바뀌면 틀을 화면 안으로 되돌린다.
            .onChange(of: model.layoutToken) { _, _ in
                guard pickCover != nil, model.columnWidth > 0 else { return }
                let (w, h, c) = (Double(full.width), Double(full.height), Double(model.columnWidth))
                coverFrame = coverFrame.map { IosCovers.shared.clampFrame(f: $0, viewWidth: w, viewHeight: h, columnWidth: c) }
                    ?? IosCovers.shared.startFrame(viewWidth: w, viewHeight: h, columnWidth: c)
            }
            .onChange(of: percent) { _, p in model.layOut(view: full, percent: p) }
            // 자동 스크롤 중에는 화면을 켜 둔다 — 꺼지면 스크롤도 멈춘 채 다음 칸을 못 본다(안드로이드 `ReadingWindow`).
            // 끌 때 · 나갈 때는 "화면 켜짐 유지: 항상" 이면 켠 채로 둔다(그 설정을 무시하고 끄고 있었다).
            .onChange(of: model.autoOn) { _, on in UIApplication.shared.isIdleTimerDisabled = on || prefs.keepScreenOn == .always }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = prefs.keepScreenOn == .always }
        }
    }

    @ViewBuilder
    private func panels(insets: EdgeInsets, wide: Bool, percent: Int, wideClass: Bool) -> some View {
        switch model.panel {
        case .none:
            EmptyView()
        case .bar, .view:
            bar(insets: insets, wide: wide, percent: percent, wideClass: wideClass)
        case .settings:
            SidePanel(wide: wide, insets: insets, dismiss: { model.panel = .view }) {
                ViewSettingsScreen(prefs: prefs, isComic: true, isWebtoon: true, coverCount: 0, back: { model.panel = .view },
                                   openFooter: {}, openTouch: { model.panel = .touchZones })
            }
        case .touchZones:
            SidePanel(wide: wide, insets: insets, dismiss: { model.panel = .settings }) {
                TouchZonesScreen(prefs: prefs, rightToLeft: false, back: { model.panel = .settings })
            }
        case .episodes:
            if let work = model.work {
                SidePanel(wide: wide, insets: insets, dismiss: { model.panel = .bar }) {
                    WebtoonEpisodes(work: work, current: model.currentEpisode?.unitId ?? "", library: library,
                                    back: { model.panel = .bar }) { entry in
                        if let k = model.episodes.firstIndex(where: { $0.unitId == entry.unit.id }) {
                            model.go(episode: k, index: 0)
                        } else {
                            openBook(entry)
                        }
                    }
                }
            }
        case .bookmarks:
            SidePanel(wide: wide, insets: insets, dismiss: { model.panel = .bar }) {
                WebtoonBookmarks(marks: model.marks(), back: { model.panel = .bar }) { i in
                    if let e = model.currentEpisode, let k = model.episodes.firstIndex(where: { $0 === e }) { model.go(episode: k, index: i) }
                }
            }
        }
    }

    /// 메뉴(구상안 39 ②): 위 막대 · 아래 띠(보기를 열면 보기 줄들 · 진도 막대 · 회차 · 책갈피 · 자동 · 보기).
    private func bar(insets: EdgeInsets, wide: Bool, percent: Int, wideClass: Bool) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                CpIconButton(icon: .back, label: "뒤로", action: close)
                VStack(alignment: .leading, spacing: 0) {
                    Text(model.title).font(.system(size: 17, weight: .bold)).foregroundStyle(CpColor.text).lineLimit(1)
                    Text(model.subtitle).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                }
                Spacer(minLength: 8)
                CpIconButton(icon: model.bookmarked ? .bookmarkFilled : .bookmark, label: model.bookmarked ? "책갈피 빼기" : "책갈피 꽂기",
                             color: model.bookmarked ? CpColor.accent : CpColor.text) { model.toggleBookmark() }
            }
            .padding(.horizontal, 4).frame(height: 52)
            .padding(.top, insets.top).padding(.horizontal, max(insets.leading, insets.trailing))
            .background(CpColor.surface)
            .contentShape(Rectangle())
            .cpEdge(atBottom: true)
            Color.clear.contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeOut(duration: 0.18)) { model.panel = .none } }
                .accessibilityLabel("메뉴 닫기").accessibilityAddTraits(.isButton)
            VStack(spacing: 0) {
                if model.panel == .view {
                    viewRows(percent: percent, wideClass: wideClass)
                        .frame(maxWidth: wide ? 560 : .infinity)
                        .frame(maxWidth: .infinity)
                }
                SeekBar(percent: model.fraction * 100) { model.seek($0 / 100) }
                    .padding(.horizontal, CpMetrics.gutter).frame(height: 40)
                HStack(spacing: 0) {
                    Spacer(minLength: 0)
                    if model.work != nil {
                        CpToolButton(icon: .toc, label: "회차") { model.panel = .episodes }
                        Spacer(minLength: 0)
                    }
                    CpToolButton(icon: .bookmark, label: "책갈피") { model.panel = .bookmarks }
                    Spacer(minLength: 0)
                    CpToolButton(icon: .play, label: "자동") {
                        model.autoOn = true
                        model.autoPaused = false
                        model.panel = .none
                    }
                    Spacer(minLength: 0)
                    CpToolButton(icon: .view, label: "보기", selected: model.panel == .view) {
                        model.panel = model.panel == .view ? .bar : .view
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 8)
            }
            .padding(.bottom, insets.bottom)
            .padding(.horizontal, max(insets.leading, insets.trailing))
            .background(CpColor.surface)
            .contentShape(Rectangle())
            .cpEdge(atBottom: false)
        }
        .transition(.opacity)
    }

    /// 보기 판(구상안 39 ②): 보는 방식 → 그림 폭 → 색 보정 → 밝기 → 모든 보기 설정.
    @ViewBuilder
    private func viewRows(percent: Int, wideClass: Bool) -> some View {
        VStack(spacing: 0) {
            let options: [String?] = [nil, "PAGE", "WEBTOON"]
            let chosen = model.work?.view?.name
            CpChoice(label: "보는 방식", options: ["자동", "쪽 넘김", "웹툰"], selected: options.firstIndex { $0 == chosen } ?? 0) { onView(options[$0]) }
            // 그림 폭: 화면 등급(좁음 · 넓음)마다 따로 기억한다 — 휴대폰 세로는 꽉 채워, 가로 · iPad 는 70% 에서 시작.
            HStack(spacing: 0) {
                Text("그림 폭").font(CpFont.body).foregroundStyle(CpColor.text).frame(width: 72, alignment: .leading)
                CpSlider(value: Double(percent - 40) / 60, label: "그림 폭",
                         onChange: { previewPercent = Int(IosWebtoon.shared.percentAt(fraction: Float($0))) },
                         onCommit: { v in
                             let p = Int(IosWebtoon.shared.percentAt(fraction: Float(v)))
                             previewPercent = nil
                             if wideClass { prefs.webtoonWide = p } else { prefs.webtoonNarrow = p }
                         })
                Text("\(percent)%").font(CpFont.label).foregroundStyle(CpColor.text).monospacedDigit().frame(width: 52, alignment: .trailing)
            }
            .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 4)
            CpChoice(label: "색 보정", options: ["끔", "선명하게", "흑백"], selected: prefs.comicColor) { prefs.comicColor = $0 }
            HStack(spacing: 0) {
                Text("밝기").font(CpFont.body).foregroundStyle(CpColor.text).frame(width: 72, alignment: .leading)
                CpSlider(value: brightness.value ?? brightness.phone, label: "밝기",
                         onChange: { brightness.preview($0) }, onCommit: { brightness.set($0) })
                Button { brightness.followPhone() } label: {
                    Text("휴대폰").font(CpFont.label)
                        .foregroundStyle(brightness.value == nil ? CpColor.accentText : CpColor.textMuted)
                        .padding(.horizontal, 12).frame(minHeight: CpMetrics.touchTarget)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("휴대폰 설정 밝기")
            }
            .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 4)
            CpLinkRow(label: "모든 보기 설정") { model.panel = .settings }
        }
        .padding(.top, 8)
    }
}

/// 자동 스크롤 조절기(안드로이드 `CpAutoScrollPill`): "자동 스크롤" − 보통 + ⏸ ✕. 자동 넘김 알약과 같은 카드 모양.
private struct AutoScrollPill: View {
    let speed: Int
    let paused: Bool
    let onSpeed: (Int) -> Void
    let onPause: () -> Void
    let onClose: () -> Void

    var body: some View {
        let labels = IosWebtoon.shared.speedLabels
        HStack(spacing: 0) {
            Text(paused ? "자동 스크롤 · 멈춤" : "자동 스크롤").font(CpFont.label).foregroundStyle(CpColor.text).padding(.leading, 16)
            CpIconButton(icon: .minus, label: "더 느리게", color: speed == 0 ? CpColor.textMuted : CpColor.text) { onSpeed(max(speed - 1, 0)) }
            Text(labels[speed]).font(CpFont.label).foregroundStyle(CpColor.accentText)
            CpIconButton(icon: .plus, label: "더 빠르게", color: speed == labels.count - 1 ? CpColor.textMuted : CpColor.text) {
                onSpeed(min(speed + 1, labels.count - 1))
            }
            CpIconButton(icon: paused ? .play : .pause, label: paused ? "자동 스크롤 이어 하기" : "자동 스크롤 멈춤", action: onPause)
            CpIconButton(icon: .close, label: "자동 스크롤 끄기", action: onClose)
        }
        .padding(.trailing, 4)
        .background(CpColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium))
        .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(CpColor.divider, lineWidth: 1))
        .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
        .environment(\.colorScheme, .dark)
    }
}

/// 회차(구상안 39 ③): 화마다 작은 그림 · 읽은 정도, 지금 화를 칠한다. 화는 모두 같은 급이라 들이지 않는다(UI 규칙 1).
private struct WebtoonEpisodes: View {
    let work: Work
    let current: String
    let library: Library
    let back: () -> Void
    let pick: (WorkEntry) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "회차", subtitle: "\(work.title) · \(work.entries.count)화",
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(work.entries.enumerated()), id: \.offset) { i, e in
                            let here = e.unit.id == current || e.copies.contains { $0.id == current }
                            Button { pick(e) } label: {
                                HStack(spacing: 14) {
                                    EpisodeThumb(unit: e.unit, library: library, rtl: work.rightToLeft?.boolValue == true)
                                    Text(e.label).font(CpFont.body).foregroundStyle(here ? CpColor.accentText : CpColor.text)
                                    Spacer()
                                    if let v = value(e) {
                                        Text(v).font(CpFont.label).foregroundStyle(here ? CpColor.accentText : CpColor.textMuted)
                                    }
                                }
                                .padding(.horizontal, CpMetrics.gutter).frame(height: 52)
                                .background(here ? CpColor.accentContainer.opacity(0.5) : .clear)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .id(i)
                        }
                    }
                }
                .onAppear {
                    let i = work.entries.firstIndex { $0.unit.id == current || $0.copies.contains { $0.id == current } } ?? 0
                    proxy.scrollTo(max(i - 2, 0), anchor: .top)
                }
            }
        }
    }

    /// "다 읽음" · "35%" · 없음(아직 열지 않은 화).
    private func value(_ e: WorkEntry) -> String? {
        let all = library.comicProgress
        let p = ([e.unit] + e.copies).compactMap { all[$0.id] }.max { $0.updatedAtEpochMs < $1.updatedAtEpochMs }
        guard let p else { return nil }
        return p.finished ? "다 읽음" : "\(Int((Double(p.fraction) * 100).rounded()))%"
    }
}

/// 회차의 작은 그림: 표지(첫 칸)의 위쪽을 가로로 잘라 보인다(안드로이드 `EpisodeThumb` — 긴 그림을 통째로 줄이면 가는 줄이 된다).
private struct EpisodeThumb: View {
    let unit: ComicUnit
    let library: Library
    let rtl: Bool
    @State private var image: UIImage?

    var body: some View {
        CpColor.tileOther
            .frame(width: 40, height: 30)
            .overlay(alignment: .top) {
                if let image { Image(uiImage: image).resizable().aspectRatio(contentMode: .fill).frame(width: 40, height: 30, alignment: .top) }
            }
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .overlay(RoundedRectangle(cornerRadius: 4).stroke(CpColor.divider))
            .task(id: unit.id) { image = await ComicCovers.shared.load(unit, library: library, rtl: rtl) }
            .accessibilityHidden(true)
    }
}

/// 지금 화의 책갈피(그림 단위).
private struct WebtoonBookmarks: View {
    let marks: [Int]
    let back: () -> Void
    let go: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "책갈피", subtitle: "\(marks.count)개", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            if marks.isEmpty {
                CpEmptyMessage(text: "이 화에 책갈피가 없습니다. 가운데를 누르고 위쪽 책갈피 단추로 꽂을 수 있습니다.")
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(marks, id: \.self) { i in
                            Button { go(i) } label: {
                                HStack {
                                    Text("\(i + 1)번째 그림").font(CpFont.body).foregroundStyle(CpColor.text)
                                    Spacer()
                                }
                                .padding(.horizontal, CpMetrics.gutter).frame(height: 52).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 기둥(UIScrollView)

/// 웹툰 기둥: 보이는 칸만 그림을 풀어 놓고, 두 손가락으로 확대한다(2A — 손을 떼도 그 배율로 내려 읽는다). 누름은 터치 영역 규칙으로
/// 한 화면씩, 가운데는 메뉴.
private struct WebtoonCanvas: UIViewRepresentable {
    @ObservedObject var model: WebtoonModel
    let color: Int
    let zones: Int
    let auto: Bool
    let secondsPerScreen: CGFloat
    let onMenu: () -> Void
    let onAutoEnd: () -> Void
    /// 표지 틀을 고르는 중이면 두 손가락 배율(손을 떼면 ended). nil 이면 보통 읽기 — 두 손가락은 확대.
    var onPinch: ((CGFloat, Bool) -> Void)?

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    static func dismantleUIView(_ scroll: UIScrollView, coordinator: Coordinator) { coordinator.stop() }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.backgroundColor = UIColor(comicPaper.paperColor)
        scroll.delegate = context.coordinator
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.showsVerticalScrollIndicator = false
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.addSubview(context.coordinator.content)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        scroll.addGestureRecognizer(tap)
        let pinch = UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pinched(_:)))
        pinch.isEnabled = false
        scroll.addGestureRecognizer(pinch)
        context.coordinator.pinch = pinch
        context.coordinator.scroll = scroll
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        let c = context.coordinator
        c.model = model
        c.color = color
        c.zones = zones
        c.onMenu = onMenu
        c.onAutoEnd = onAutoEnd
        c.onPinch = onPinch
        c.pinch?.isEnabled = onPinch != nil
        scroll.maximumZoomScale = onPinch == nil ? 4 : 1
        if onPinch != nil && scroll.zoomScale != 1 { scroll.zoomScale = 1 }
        c.relayout(token: model.layoutToken)
        if c.appliedScroll != model.scrollTo.token {
            c.appliedScroll = model.scrollTo.token
            scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: model.scrollTo.y * scroll.zoomScale), animated: model.scrollTo.animated)
        }
        c.setAuto(auto, secondsPerScreen: secondsPerScreen)
        c.refresh()
    }

    @MainActor
    final class Coordinator: NSObject, UIScrollViewDelegate {
        var model: WebtoonModel
        let content = UIView()
        weak var scroll: UIScrollView?
        var color = 0 { didSet { if color != oldValue { clear() } } }
        var zones = 0
        var onMenu: () -> Void = {}
        var onAutoEnd: () -> Void = {}
        var onPinch: ((CGFloat, Bool) -> Void)?
        weak var pinch: UIPinchGestureRecognizer?
        var appliedScroll = -1
        private var appliedLayout = -1
        private var views: [Int: UIView] = [:]
        private var loading: [Int: Task<Void, Never>] = [:]
        private var link: CADisplayLink?
        private var speed: CGFloat = 0
        private var last: CFTimeInterval = 0

        init(model: WebtoonModel) { self.model = model }

        func relayout(token: Int) {
            guard token != appliedLayout, let scroll else { return }
            let first = appliedLayout < 0
            appliedLayout = token
            let size = CGSize(width: model.viewSize.width, height: max(model.total, model.viewSize.height))
            if model.appended && !first {
                // 다음 화가 붙었다: 앞 칸들의 자리는 그대로라 놓인 그림 · 확대를 두고 길이만 늘린다. 전처럼 다 지우고 확대를 풀면 확대해
                // 읽던 화면이 화 끝 근처에서 깜빡이며 1배로 돌아갔다(2A — 손을 떼도 그 배율로 내려 읽는다).
                let z = scroll.zoomScale
                content.bounds = CGRect(origin: .zero, size: size)
                content.center = CGPoint(x: size.width * z / 2, y: size.height * z / 2)
                scroll.contentSize = CGSize(width: size.width * z, height: size.height * z)
                return
            }
            clear()
            // 폭이 바뀌어 다시 짜면 확대를 푼다 — 확대한 채 틀을 바꾸면 UIScrollView 의 변환과 새 크기가 어긋난다.
            scroll.zoomScale = 1
            model.zoom = 1
            content.frame = CGRect(origin: .zero, size: size)
            scroll.contentSize = size
        }

        /// 화면이 사라진다: 자동 스크롤 시계와 그리던 일을 거둔다. CADisplayLink 는 대상을 꽉 붙들어, 자동 스크롤을 켠 채 나가면 시계가
        /// 영영 돌고 조정자 · 모델(그림 캐시)이 풀리지 않았다.
        func stop() {
            link?.invalidate()
            link = nil
            clear()
        }

        private func clear() {
            for (_, v) in views { v.removeFromSuperview() }
            views = [:]
            for (_, t) in loading { t.cancel() }
            loading = [:]
        }

        /// 화면 가까운 칸(위아래 한 화면 더)만 둔다.
        func refresh() {
            guard let scroll, model.viewSize.height > 0 else { return }
            let zoom = scroll.zoomScale
            let top = scroll.contentOffset.y / zoom
            // 처음 그릴 때는 스크롤 뷰가 아직 자리를 받지 않아 높이가 0 이다 — 그 높이로 고르면 보일 칸이 하나도 없고, 누르거나
            // 밀기 전까지 다시 고르지 않아 화면이 빈 채로 남았다. 그때는 화면 크기(모델이 아는 값)로 고른다.
            let h = (scroll.bounds.height > 0 ? scroll.bounds.height : model.viewSize.height) / zoom
            let want = Set(IosWebtoon.shared.visible(cells: model.cells, top: Float(top - h), bottom: Float(top + h * 2)).map { Int(truncating: $0) })
            for (i, v) in views where !want.contains(i) {
                v.removeFromSuperview()
                views[i] = nil
                loading[i]?.cancel()
                loading[i] = nil
            }
            let x = ((model.viewSize.width - model.columnWidth) / 2).rounded()
            for i in want where views[i] == nil && model.cells.indices.contains(i) {
                let cell = model.cells[i]
                let frame = CGRect(x: x, y: CGFloat(cell.top), width: model.columnWidth, height: CGFloat(cell.height))
                if cell.seam {
                    let v = seamView(cell, frame: CGRect(x: 0, y: CGFloat(cell.top), width: model.viewSize.width, height: CGFloat(cell.height)))
                    content.addSubview(v)
                    views[i] = v
                    continue
                }
                let image = UIImageView(frame: frame)
                image.backgroundColor = UIColor(white: 0.12, alpha: 1)
                image.contentMode = .scaleToFill
                content.addSubview(image)
                views[i] = image
                guard model.episodes.indices.contains(Int(cell.episode)) else { continue }
                let episode = model.episodes[Int(cell.episode)]
                if !cell.known { broken(image); continue }
                let source = model.source(episode, Int(cell.index))
                let width = model.columnWidth
                let color = color
                let m = model
                loading[i] = Task { [weak image] in
                    // 못 풀면 조금 뒤 세 번까지 다시(안드로이드 0.59.2) — 풀기는 메모리가 잠깐 모자랄 때도 실패하는데, 한 번 실패로
                    // "그리지 못했습니다" 를 붙이면 그 칸이 화면 밖으로 나갈 때까지 남았다. 정말 깨진 그림은 세 번 뒤에 알린다.
                    for attempt in 0..<3 {
                        if attempt > 0 { try? await Task.sleep(for: .milliseconds(400 * attempt)) }
                        guard !Task.isCancelled else { return }
                        let img = await WebtoonDecoder.shared.run { m.strip(cell, episode: episode, width: width, color: color, source: source) }
                        guard !Task.isCancelled, let image else { return }
                        if let img { image.image = img; image.backgroundColor = .clear; return }
                    }
                    if let image { self.broken(image) }
                }
            }
        }

        private func broken(_ view: UIImageView) {
            let label = UILabel(frame: view.bounds)
            label.text = "이 그림을 그리지 못했습니다"
            label.textAlignment = .center
            label.textColor = UIColor(comicPaper.inkMutedColor)
            label.font = .systemFont(ofSize: 14)
            label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(label)
        }

        /// 경계 띠(③ B): "3화 끝 ─ 4화 · 고양이 우체국 · 12화 가운데 4번째".
        private func seamView(_ cell: WebtoonCell, frame: CGRect) -> UIView {
            let v = UIView(frame: frame)
            v.backgroundColor = UIColor(comicPaper.paperColor)
            let k = Int(cell.episode)
            let before = model.episodes.indices.contains(k - 1) ? model.episodes[k - 1].label : nil
            let after = model.episodes.indices.contains(k) ? model.episodes[k] : nil
            var lines: [(String, UIFont, UIColor)] = []
            let muted = UIColor(comicPaper.inkMutedColor)
            if let before { lines.append(("\(before) 끝", .systemFont(ofSize: 12), muted)) }
            lines.append(("—", .systemFont(ofSize: 12), UIColor(red: 0x5A / 255, green: 0x54 / 255, blue: 0x4D / 255, alpha: 1)))
            if let after { lines.append((after.label, .systemFont(ofSize: 19, weight: .bold), .white)) }
            if let work = model.work, let after, let n = work.entries.firstIndex(where: { $0.unit.id == after.unitId }) {
                lines.append(("\(work.title) · \(work.entries.count)화 가운데 \(n + 1)번째", .systemFont(ofSize: 12), muted))
            }
            let stack = UIStackView(arrangedSubviews: lines.map { text, font, color in
                let l = UILabel()
                l.text = text
                l.font = font
                l.textColor = color
                return l
            })
            stack.axis = .vertical
            stack.alignment = .center
            stack.spacing = 6
            stack.translatesAutoresizingMaskIntoConstraints = false
            v.addSubview(stack)
            NSLayoutConstraint.activate([stack.centerXAnchor.constraint(equalTo: v.centerXAnchor), stack.centerYAnchor.constraint(equalTo: v.centerYAnchor)])
            return v
        }

        // MARK: UIScrollViewDelegate

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { content }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            model.scrolled(scrollView.contentOffset.y / scrollView.zoomScale)
            refresh()
        }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            model.zoom = scrollView.zoomScale
            refresh()
        }

        @objc func pinched(_ g: UIPinchGestureRecognizer) {
            guard g.state != .possible else { return }
            onPinch?(g.scale, g.state == .ended || g.state == .cancelled || g.state == .failed)
        }

        @objc func tapped(_ g: UITapGestureRecognizer) {
            // 표지 틀을 고르는 동안 누름은 아무 일도 하지 않는다 — 넘김 · 메뉴가 틀 고르기를 흐트러뜨린다.
            guard let scroll, onPinch == nil else { return }
            let p = g.location(in: scroll.superview)
            let step = Int(TouchZones.companion.of(index: Int32(zones)).step(x: Double(p.x / max(scroll.bounds.width, 1)), rightToLeft: false))
            if step == 0 { onMenu() } else if model.panel == .none { model.step(step) } else { onMenu() }
        }

        // MARK: 자동 스크롤

        func setAuto(_ on: Bool, secondsPerScreen: CGFloat) {
            speed = model.viewSize.height / max(secondsPerScreen, 1)
            if on, link == nil {
                last = 0
                let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
                l.add(to: .main, forMode: .common)
                link = l
            } else if !on, let l = link {
                l.invalidate()
                link = nil
            }
        }

        @objc private func tick(_ l: CADisplayLink) {
            guard let scroll else { return }
            defer { last = l.timestamp }
            guard last > 0, !scroll.isDragging else { return }
            let dy = speed * CGFloat(l.timestamp - last) * scroll.zoomScale
            let maxY = scroll.contentSize.height - scroll.bounds.height
            // 끝(마지막 화의 끝)에 닿으면 끈다 — 알약이 남아 "자동 스크롤" 이라고 하는데 서 있으면 고장으로 보인다.
            // 다음 화를 여는 중이면 기다린다(붙으면 기둥이 길어진다).
            if scroll.contentOffset.y >= maxY - 1 {
                // 다음 화를 열지 못했어도 끝이다 — 알약은 "자동 스크롤" 인데 화면은 멈춰 있었다.
                if model.noMoreEpisodes { onAutoEnd() }
                return
            }
            scroll.contentOffset.y = min(scroll.contentOffset.y + dy, maxY)
        }
    }
}

/// 웹툰 띠 풀기를 한 번에 하나씩. 보이는 칸 수만큼 동시에 풀면 큰 그림 여러 장이 한꺼번에 메모리에 올라 램이 적은 기기에서 한도를 넘었다.
actor WebtoonDecoder {
    static let shared = WebtoonDecoder()

    func run(_ work: () -> UIImage?) -> UIImage? {
        if Task.isCancelled { return nil }
        return work()
    }
}
