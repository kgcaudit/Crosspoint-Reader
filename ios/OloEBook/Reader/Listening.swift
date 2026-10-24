import AVFoundation
import MediaPlayer
import SwiftUI
import UIKit

/// 듣기가 읽는 한 문장(장 안 글자 구간, Kotlin `SpeechSentence`).
struct SpokenSentence: Equatable {
    let start: Int
    let end: Int
}

/// 잠자기 타이머(안드로이드 `ListenTimer`). 저장하지 않는다 — 어젯밤 30분 타이머가 오늘 아침 듣기를 30분 만에 끊으면 안 된다.
enum ListenTimer: Int, CaseIterable {
    case off, min30, min60, chapterEnd

    var label: String { ["끔", "30분", "60분", "장 끝"][rawValue] }
    var minutes: Int? { [nil, 30, 60, nil][rawValue] }
}

/// 듣기가 리더에 바라는 것. 리더(ReaderModel)가 엔진에 물어 채운다.
@MainActor
protocol ListenSource: AnyObject {
    var title: String { get }
    /// 장 하나의 문장들. 깨졌거나 없는 장이면 빈 목록 — 듣기는 그 장을 건너뛴다.
    func speech(_ spine: Int) async -> [SpokenSentence]
    /// 엔진에 넘길 글(숨은 문자 정리 · 어절 쉼 줄이기). [join] 0 일반 · 1 속독.
    func spoken(_ spine: Int, _ sentence: SpokenSentence, join: Int) async -> String
    func unitCount() async -> Int
    /// 그 문장을 칠하고, [follow] 면 그 문장이 보이게 쪽을 옮긴다(이미 보이면 옮기지 않는다).
    func follow(_ spine: Int, _ sentence: SpokenSentence, follow: Bool) async
}

/// 듣기 한 번(구상안 24 · 25A, 안드로이드 `Listening` 을 옮김). 문장 하나씩 iOS 음성(AVSpeechSynthesizer)에 넘기고, 끝나면 다음
/// 문장, 장이 끝나면 다음 장으로 간다.
///
/// 화면과 떨어져 돈다 — 화면을 꺼도 읽기는 계속되고(백그라운드 오디오), 쪽을 따라 넘기는 것도 여기서 [ListenSource.follow] 를 직접
/// 부른다. 쪽이 넘어가면 리더가 진도를 저장하므로, 화면을 끈 채 듣다가 앱을 닫아도 들은 곳에서 다시 연다.
///
/// 음성에는 지금 문장과 **다음 한 문장**을 줄 세워 둔다. 하나씩 주면 문장이 끝난 알림과 다음 문장 사이에 틈이 생겨, 화면이 꺼진 동안
/// 그 틈에 앱이 잠들면 읽기가 멈춘다.
@MainActor
final class Listening: NSObject, ObservableObject {
    /// 듣기가 켜져 있다(조종판이 떠 있다). 멈춰 있어도 켜져 있다.
    @Published private(set) var active = false
    @Published private(set) var playing = false
    @Published private(set) var preparing = false
    @Published private(set) var spine = -1
    @Published private(set) var sentence: SpokenSentence?
    @Published private(set) var rate = 1.0
    @Published private(set) var timer = ListenTimer.off
    @Published private(set) var timerEndsAt: Date?
    /// 사람에게 알릴 말(책 끝). 리더가 띄운 뒤 지운다.
    @Published var message: String?

    weak var source: ListenSource?

    private let synth = AVSpeechSynthesizer()
    private var join = 0
    private var voiceId: String?
    private var sentenceText = ""
    private var chapter: (spine: Int, sentences: [SpokenSentence])?
    private var index = -1
    private var queued = -1
    /// 끊고 새로 읽을 때마다 는다. 끊기 전에 줄 서 있던 문장의 알림이 늦게 와도 무시하려고.
    private var generation = 0
    /// 음성에 넘긴 문장들(알림이 오면 어느 문장인지 찾는다). 음성이 끝낼 때까지 붙들어 둔다.
    private var utterances: [ObjectIdentifier: (utterance: AVSpeechUtterance, gen: Int, spine: Int, index: Int)] = [:]
    private var timerTask: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []
    /// 전화 · 알람이 끊어 멈췄다 — 끝나면 이어 읽는다. 사람이 멈춘 것은 이어 읽지 않는다.
    private var pausedByInterruption = false

    override init() {
        super.init()
        synth.delegate = self
    }

    // ── 여닫기 ────────────────────────────────────────────────────────────

    /// [offset] 부터 듣기를 연다. [pageEnd] 를 주면(보이는 쪽에서 켰다) 그 쪽에서 **시작하는** 첫 문장부터 읽는다 — 쪽 첫 글자가 든
    /// 문장부터 읽으면 그 문장은 앞 쪽에서 시작해, 듣기를 누르자 앞 쪽으로 튀어 돌아갔다(안드로이드).
    func start(spine: Int, offset: Int, pageEnd: Int?, rate: Double, voice: String?, join: Int) async {
        let since = generation
        self.rate = rate
        voiceId = voice
        self.join = join
        active = true
        preparing = true
        message = nil
        openSession()
        let c = await load(spine)
        let exact = c.sentences.firstIndex { $0.start == offset }
        let onPage = exact == nil ? pageEnd.flatMap { end in c.sentences.firstIndex { $0.start >= offset && $0.start < end } } : nil
        let n = exact ?? onPage ?? indexAt(c.sentences, offset)
        // 쪽 전체가 한 문장 안이면 그 문장을 읽되 쪽은 사람이 둔 곳에 둔다.
        let keepPage = pageEnd != nil && exact == nil && onPage == nil
        preparing = false
        // 준비하는 사이 끄거나 멈췄다. 멈춘 것이면 자리는 잡아 둔다 — 다음 "이어 듣기" 가 장 첫 문장이 아니라 여기서 읽는다.
        if generation != since {
            if index < 0 && n < c.sentences.count { index = n }
            return
        }
        await speakFrom(spine, n, since, follow: !keepPage)
    }

    /// 듣기를 끈다(조종판 ✕ · 책 닫기). 문장 칠은 리더가 지운다.
    func close() {
        generation += 1
        timerTask?.cancel()
        synth.stopSpeaking(at: .immediate)
        utterances.removeAll()
        chapter = nil
        index = -1
        queued = -1
        active = false
        playing = false
        preparing = false
        spine = -1
        sentence = nil
        timer = .off
        timerEndsAt = nil
        closeSession()
    }

    // ── 조종 ─────────────────────────────────────────────────────────────

    /// 재생/멈춤. 준비하는 동안은 조종판이 "멈춤" 을 보이므로 누르면 멈춘다.
    func toggle() { if playing || preparing { pause() } else { play() } }

    func play() {
        guard let c = chapter, !playing, active else { return }
        pausedByInterruption = false
        let since = generation
        Task { await speakFrom(c.spine, max(index, 0), since) }
    }

    func pause() {
        generation += 1
        synth.stopSpeaking(at: .immediate)
        utterances.removeAll()
        queued = -1
        playing = false
        updateNowPlaying()
    }

    /// 다음 · 앞 문장(조종판 · 잠금 화면 · 이어폰 단추). 멈춰 있어도 옮기고, 읽던 중이면 거기서 읽는다.
    func next() { step(1) }
    func previous() { step(-1) }

    private func step(_ by: Int) {
        guard let c = chapter else { return }
        let wasPlaying = playing
        let since = generation
        Task {
            if wasPlaying { await speakFrom(c.spine, index + by, since) } else { await moveTo(c.spine, index + by) }
        }
    }

    /// 빠르기 · 어절 쉼 · 목소리는 지금 문장부터 바로 바뀌어 들린다 — 다음 문장부터 먹으면 고른 값을 들어 볼 수 없다.
    func setRate(_ value: Double) {
        rate = value
        restartSentence()
    }

    func setJoin(_ level: Int) {
        join = level
        restartSentence()
    }

    func setVoice(_ id: String?) {
        voiceId = id
        restartSentence()
    }

    private func restartSentence() {
        guard playing, let c = chapter else { return }
        let since = generation
        Task { await speakFrom(c.spine, index, since) }
    }

    func setTimer(_ t: ListenTimer) {
        timerTask?.cancel()
        timer = t
        timerEndsAt = t.minutes.map { Date().addingTimeInterval(Double($0) * 60) }
        updateNowPlaying()
        guard let minutes = t.minutes else { return }
        timerTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Double(minutes) * 60))
            guard !Task.isCancelled, let self else { return }
            self.pause()
            self.timer = .off
            self.timerEndsAt = nil
            self.updateNowPlaying()
        }
    }

    /// 잠자기 타이머의 남은 분(올림). 1분 미만이 남아도 "1분" — "0분" 이면 이미 멈춘 것처럼 읽힌다.
    func timerLabel(now: Date) -> String? {
        if let end = timerEndsAt { return "\(max(Int((end.timeIntervalSince(now) / 60).rounded(.up)), 1))분" }
        return timer == .chapterEnd ? "장 끝" : nil
    }

    /// 사람이 쪽을 옮겼다(넘기기 · 목차 · 진도 막대). 지금 문장이 그 쪽에 없으면 그 쪽에서 **시작하는** 첫 문장으로 옮긴다 — 넘긴
    /// 쪽을 보면서 앞 쪽의 문장을 듣는 일이 없게. 듣기가 쪽을 따라 넘긴 것이면 문장이 그 쪽에 있어 그대로다.
    func onPageShown(spine: Int, start: Int, end: Int) {
        guard active, !preparing else { return }
        if self.spine == spine, let s = sentence, s.start >= start && s.start < end { return }
        let since = generation
        Task {
            let c = await load(spine)
            let starting = c.sentences.firstIndex { $0.start >= start && $0.start < end }
            let n = starting ?? indexAt(c.sentences, start)
            // 쪽 전체가 한 문장 안이면 그 문장을 읽되 쪽은 사람이 둔 곳에 둔다 — 따라가면 넘긴 쪽이 앞 쪽으로 튀어 돌아온다.
            if playing { await speakFrom(spine, n, since, follow: starting != nil) } else { await moveTo(spine, n, follow: starting != nil) }
        }
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private func load(_ spine: Int) async -> (spine: Int, sentences: [SpokenSentence]) {
        if let c = chapter, c.spine == spine { return c }
        let c = (spine, await source?.speech(spine) ?? [])
        chapter = c
        return c
    }

    /// [offset] 이 든 문장의 번호. 문장 사이면 그 뒤 첫 문장, 없으면 끝(다음 장으로 넘어간다).
    private func indexAt(_ sentences: [SpokenSentence], _ offset: Int) -> Int {
        sentences.firstIndex { offset < $0.end } ?? sentences.count
    }

    /// [spine] 장의 [n] 번째 문장. 장 끝을 넘으면 다음 장(빈 장은 건너뛴다), 앞을 넘으면 앞 장의 끝. 책 끝이면 nil.
    private func resolve(_ spine: Int, _ n: Int) async -> ((spine: Int, sentences: [SpokenSentence]), Int)? {
        var s = spine
        var i = n
        var c = await load(s)
        let units = await source?.unitCount() ?? 0
        var guardCount = 0
        while i < 0 || i >= c.sentences.count {
            guardCount += 1
            if guardCount > 10_000 { return nil }
            if i < 0 {
                if s == 0 { if c.sentences.isEmpty { return nil }; i = 0; break }
                s -= 1
                c = await load(s)
                i = c.sentences.count - 1
            } else {
                s += 1
                if s >= units { return nil }
                c = await load(s)
                i = 0
            }
        }
        return (c, i)
    }

    /// 멈춘 채로 문장만 옮긴다(칠과 쪽만 따라간다).
    private func moveTo(_ spine: Int, _ n: Int, follow: Bool = true) async {
        guard let (c, i) = await resolve(spine, n) else { return }
        index = i
        await show(c, i, follow: follow)
    }

    /// [n] 번째 문장부터 읽는다. 장 끝이면 다음 장, 책 끝이면 멈추고 알린다.
    private func speakFrom(_ spine: Int, _ n: Int, _ since: Int, follow: Bool = true) async {
        let from = chapter?.spine
        let resolved = await resolve(spine, n)
        // 엔진에 넘길 글을 **먼저** 받는다 — 받는 사이에 멈춤 · 끄기가 오면(이어폰이 빠짐 · 전화 · 사람) 아래 세대 확인이 막는다.
        // 받은 뒤에 세대를 올리면 멈춘 듣기가 스피커로 다시 읽기 시작했다(안드로이드에서 실제로 났던 일).
        var texts: [String] = []
        if let (c, i) = resolved {
            for k in i..<min(i + 2, c.sentences.count) { texts.append(await source?.spoken(c.spine, c.sentences[k], join: join) ?? "") }
        }
        if generation != since { return }
        guard let (c, i) = resolved else {
            pause()
            message = "책을 끝까지 읽었습니다"
            return
        }
        // "장 끝" 타이머: 다음 장으로 넘어가려는 순간 멈춘다.
        if timer == .chapterEnd, playing, let from, c.spine > from {
            pause()
            timer = .off
            index = i
            await show(c, i)
            return
        }
        generation += 1
        synth.stopSpeaking(at: .immediate)
        utterances.removeAll()
        index = i
        for (k, text) in texts.enumerated() { enqueue(text, spine: c.spine, index: i + k) }
        queued = i + texts.count - 1
        playing = true
        await show(c, i, follow: follow)
    }

    private func enqueue(_ text: String, spine: Int, index: Int) {
        // 빈 글(기호만 남은 문장)도 줄 세운다 — 건너뛰면 끝 알림이 오지 않아 다음 문장으로 가지 못한다.
        let u = AVSpeechUtterance(string: text.isEmpty ? " " : text)
        u.voice = voice
        u.rate = Self.speechRate(rate)
        utterances[ObjectIdentifier(u)] = (u, generation, spine, index)
        synth.speak(u)
    }

    private func queueNext(_ c: (spine: Int, sentences: [SpokenSentence])) async {
        let next = queued + 1
        guard next < c.sentences.count else { return }
        let gen = generation
        let text = await source?.spoken(c.spine, c.sentences[next], join: join) ?? ""
        guard gen == generation, playing else { return }
        enqueue(text, spine: c.spine, index: next)
        queued = next
    }

    /// 지금 문장을 알리고(칠 · 잠금 화면 카드), [follow] 면 그 문장이 보이게 쪽을 옮긴다.
    private func show(_ c: (spine: Int, sentences: [SpokenSentence]), _ i: Int, follow: Bool = true) async {
        let s = c.sentences[i]
        spine = c.spine
        sentence = s
        sentenceText = await source?.spoken(c.spine, s, join: 0) ?? ""
        updateNowPlaying()
        await source?.follow(c.spine, s, follow: follow)
    }

    private func started(_ key: ObjectIdentifier) async {
        guard let u = utterances[key], u.gen == generation, playing else { return }
        let c = await load(u.spine)
        guard c.sentences.indices.contains(u.index) else { return }
        // 이미 보인 문장의 시작 알림이면 쪽을 건드리지 않는다. 알림이 늦게 오는 사이 사람이 넘긴 쪽을 이 문장 자리로 되돌리면
        // "넘겼는데 앞 쪽으로 튀어 돌아온다".
        if u.index == index && spine == u.spine && sentence == c.sentences[u.index] { return }
        index = u.index
        await show(c, u.index)
    }

    private func finished(_ key: ObjectIdentifier) async {
        guard let u = utterances.removeValue(forKey: key), u.gen == generation, playing else { return }
        let c = await load(u.spine)
        if u.index >= c.sentences.count - 1 {
            await speakFrom(u.spine + 1, 0, u.gen) // 장의 마지막 문장 — 다음 장으로.
        } else if queued <= u.index + 1 {
            await queueNext(c)
        }
    }

    private var voice: AVSpeechSynthesisVoice? {
        voiceId.flatMap(AVSpeechSynthesisVoice.init(identifier:)) ?? AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
    }

    /// 1.0× 를 iOS 기본 빠르기(0.5)에 맞춘다. 느리게는 기본의 절반까지, 빠르게는 iOS 최고(1.0)까지 고르게 — iOS 의 빠르기 값은
    /// 배수가 아니라서 그대로 곱하면 2.0× 가 최고를 넘는다.
    static func speechRate(_ r: Double) -> Float {
        let base = Double(AVSpeechUtteranceDefaultSpeechRate)
        let v = r <= 1 ? base * r : base + (Double(AVSpeechUtteranceMaximumSpeechRate) - base) * (r - 1)
        return Float(min(max(v, Double(AVSpeechUtteranceMinimumSpeechRate)), Double(AVSpeechUtteranceMaximumSpeechRate)))
    }

    // ── 오디오 세션 · 잠금 화면 ──────────────────────────────────────────────

    /// 듣는 동안은 무음 스위치와 상관없이 소리 내고 화면을 꺼도 이어 읽는다(.playback · 백그라운드 오디오). 끄면 넘김 소리의
    /// .ambient 로 돌린다 — 그대로 두면 넘김 소리가 무음 스위치를 무시한다.
    private func openSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let options = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            MainActor.assumeIsolated { self?.interrupted(type, resume: options.contains(.shouldResume)) }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
            // 이어폰을 뺐다 — 스피커로 계속 읽으면 지하철에서 책을 소리 내어 읽게 된다.
            if reason == .oldDeviceUnavailable { MainActor.assumeIsolated { self?.pause() } }
        })
        let remote = MPRemoteCommandCenter.shared()
        remote.playCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.play() }; return .success }
        remote.pauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.pause() }; return .success }
        remote.togglePlayPauseCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.toggle() }; return .success }
        remote.nextTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.next() }; return .success }
        remote.previousTrackCommand.addTarget { [weak self] _ in MainActor.assumeIsolated { self?.previous() }; return .success }
    }

    private func closeSession() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        let remote = MPRemoteCommandCenter.shared()
        for c in [remote.playCommand, remote.pauseCommand, remote.togglePlayPauseCommand, remote.nextTrackCommand, remote.previousTrackCommand] {
            c.removeTarget(nil)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        try? session.setCategory(.ambient, options: [.mixWithOthers])
    }

    private func interrupted(_ type: AVAudioSession.InterruptionType?, resume: Bool) {
        switch type {
        case .began where playing:
            pause()
            pausedByInterruption = true
        case .ended:
            // 끊김이 끝나면 늘 내린다 — "이어 재생" 없이 끝난 뒤에 남아 있으면, 다음 끊김이 끝날 때 사람이 멈춘 듣기까지 저절로 다시 돌았다.
            let wasPlaying = pausedByInterruption
            pausedByInterruption = false
            if wasPlaying && resume {
                try? AVAudioSession.sharedInstance().setActive(true)
                play()
            }
        default:
            break
        }
    }

    /// 잠금 화면 · 제어 센터 카드(구상안 25): 책 제목 · 지금 문장 · 듣는 중/멈춰 있음(+ 잠자기). iOS 카드에는 "듣기 끝내기" 단추를
    /// 둘 수 없다 — 끄는 것은 앱의 조종판에서.
    private func updateNowPlaying() {
        guard active else { return }
        var state = playing ? "듣는 중" : "멈춰 있음"
        if let end = timerEndsAt { state += " · 잠자기 \(max(Int((end.timeIntervalSinceNow / 60).rounded(.up)), 1))분" }
        if timer == .chapterEnd { state += " · 장 끝에서 멈춤" }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: source?.title ?? "",
            MPMediaItemPropertyArtist: sentenceText.isEmpty ? "듣기 준비 중" : sentenceText,
            MPMediaItemPropertyAlbumTitle: state,
            MPMediaItemPropertyArtwork: Self.artwork,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
        ]
        MPNowPlayingInfoCenter.default().playbackState = playing ? .playing : .paused
    }

    /// 카드의 그림: 강조색 바탕에 책 그림 글자(구상안 25). 표지는 책마다 모양이 달라 카드에서 무엇인지 알아보기 어렵다.
    private static let artwork: MPMediaItemArtwork = {
        let renderer = ImageRenderer(content:
            RoundedRectangle(cornerRadius: 24).fill(CpColor.accent).frame(width: 160, height: 160)
                .overlay(CpIconView(icon: .book, size: 80, color: .white)))
        renderer.scale = 2
        let image = renderer.uiImage ?? UIImage()
        return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }()
}

extension Listening: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        let key = ObjectIdentifier(utterance)
        Task { @MainActor in await self.started(key) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        let key = ObjectIdentifier(utterance)
        Task { @MainActor in await self.finished(key) }
    }
}
