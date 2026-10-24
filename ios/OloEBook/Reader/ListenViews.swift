import AVFoundation
import SwiftUI

/// 듣는 동안 아래 가운데에 뜨는 조종판(구상안 24, 안드로이드 `ListenPlayer`): 앞 문장 · 멈춤/이어 듣기(48 원) · 다음 문장 |
/// 빠르기 · 잠자기 타이머 · 끝. 빠르기 · 타이머를 누르면 듣기 판이 열린다. 문장 글은 싣지 않는다 — 지면에 칠한 문장이 그것이다.
struct ListenPlayer: View {
    @ObservedObject var listening: Listening
    let openSheet: () -> Void
    /// 듣기를 끈다 — 문장 칠까지 지워야 해서 리더가 한다.
    let close: () -> Void

    var body: some View {
        TimelineView(.periodic(from: .now, by: 20)) { context in
            let timer = listening.timerLabel(now: context.date)
            HStack(spacing: 0) {
                CpIconButton(icon: .skipBack, label: "앞 문장") { listening.previous() }
                Button { listening.toggle() } label: {
                    Circle().fill(CpColor.accent).frame(width: 48, height: 48)
                        .overlay(CpIconView(icon: listening.playing || listening.preparing ? .pause : .play, size: 22, color: CpColor.onAccent))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(listening.playing || listening.preparing ? "멈춤" : "이어 듣기")
                CpIconButton(icon: .skipForward, label: "다음 문장") { listening.next() }
                Rectangle().fill(CpColor.divider).frame(width: 1, height: 24)
                Button(action: openSheet) {
                    Text(Self.rateLabel(listening.rate)).font(CpFont.label).foregroundStyle(CpColor.text)
                        .padding(.horizontal, 12).frame(minHeight: CpMetrics.touchTarget).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("읽는 속도 \(Self.rateLabel(listening.rate))")
                Button(action: openSheet) {
                    HStack(spacing: 4) {
                        CpIconView(icon: .timer, size: 20, color: timer == nil ? CpColor.text : CpColor.accent)
                        if let timer { Text(timer).font(CpFont.label).foregroundStyle(CpColor.accentText) }
                    }
                    .padding(.horizontal, 8).frame(minHeight: CpMetrics.touchTarget).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(timer.map { "잠자기 타이머 \($0)" } ?? "잠자기 타이머")
                CpIconButton(icon: .close, label: "듣기 끝내기", action: close)
            }
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(CpColor.surface)
            .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium))
            .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(CpColor.divider, lineWidth: 1))
            .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
        }
    }

    /// "1.0×" — 소수 한 자리. 떠돌이 소수(1.2000001)가 보이지 않게 반올림한다.
    static func rateLabel(_ r: Double) -> String { String(format: "%.1f×", (r * 10).rounded() / 10) }
}

/// 듣기 판(구상안 24, 안드로이드 `ListenSheet`): 읽는 속도 · 목소리 · 타이머 · 어절 쉼 줄이기.
struct ListenSheet: View {
    @ObservedObject var listening: Listening
    @ObservedObject var prefs: ReaderPrefs
    let bottom: CGFloat
    let openVoices: () -> Void
    let close: () -> Void

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.32).contentShape(Rectangle()).onTapGesture(perform: close)
                .accessibilityLabel("닫기").accessibilityAddTraits(.isButton)
                .cpFadeIn(CpMotion.sheetIn)
            VStack(alignment: .leading, spacing: 0) {
                Capsule().fill(CpColor.divider).frame(width: 36, height: 4).frame(maxWidth: .infinity).padding(.top, 10)
                Text("듣기").font(CpFont.title).foregroundStyle(CpColor.text).padding(.horizontal, CpMetrics.gutter).padding(.vertical, 12)
                CpStepper(label: "읽는 속도", value: ListenPlayer.rateLabel(prefs.listenRate),
                          onMinus: { stepRate(-1) }, onPlus: { stepRate(1) })
                CpLinkRow(label: "목소리", value: VoiceList.label(prefs.listenVoice), action: openVoices)
                CpChoice(label: "타이머", options: ListenTimer.allCases.map(\.label), selected: listening.timer.rawValue) {
                    listening.setTimer(ListenTimer(rawValue: $0) ?? .off)
                }
                CpChoice(label: "어절 쉼 줄이기", options: ["일반", "속독"], selected: prefs.listenJoin) {
                    prefs.listenJoin = $0
                    listening.setJoin($0)
                }
                Text("속독 · 뜻이 이어지는 어절을 붙여 쉼을 줄입니다. 누르면 지금 문장부터 바로 들립니다.")
                    .font(CpFont.caption).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 32).padding(.trailing, CpMetrics.gutter)
                CpDivider().padding(.vertical, 8)
                Text("휴대폰의 음성으로 읽습니다. 인터넷을 쓰지 않습니다. 화면을 꺼도 이어 읽고, 잠금 화면에서 멈추고 넘길 수 있습니다.")
                    .font(CpFont.caption).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, CpMetrics.gutter)
                HStack { Spacer(); CpButton(title: "닫기", kind: .secondary, action: close) }.padding(CpMetrics.gutter)
            }
            .padding(.bottom, bottom)
            .frame(maxWidth: 560)
            .background(CpColor.surface)
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: CpMetrics.cornerSheet, topTrailingRadius: CpMetrics.cornerSheet))
                .cpRiseIn() // 아래에서 올라온다(구상안 42)
        }
        .transition(.move(edge: .bottom))
    }

    /// 0.5–2.0 을 0.1 씩(안드로이드 `stepRate`). 10배로 세어 떠돌이 소수가 생기지 않게.
    private func stepRate(_ by: Int) {
        let next = Double(min(max(Int((prefs.listenRate * 10).rounded()) + by, 5), 20)) / 10
        prefs.listenRate = next
        listening.setRate(next)
    }
}

/// iPhone 에 깔린 목소리(구상안 25A). iOS 에는 안드로이드처럼 고를 "음성 엔진" 이 없고 한국어 목소리가 음질로 나뉜다 — 엔진 묶음
/// 자리에 음질 묶음을 둔다.
enum VoiceList {
    struct Group: Identifiable {
        let title: String
        let voices: [AVSpeechSynthesisVoice]
        var id: String { title }
    }

    static let qualityNames: [AVSpeechSynthesisVoiceQuality: String] = [.premium: "프리미엄", .enhanced: "향상됨", .default: "기본 음질"]

    /// 프리미엄 → 향상됨 → 기본 음질. 빈 묶음은 보이지 않는다.
    static func groups() -> [Group] {
        let korean = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("ko") }
        return [AVSpeechSynthesisVoiceQuality.premium, .enhanced, .default].compactMap { q in
            let voices = korean.filter { $0.quality == q }.sorted { $0.name < $1.name }
            return voices.isEmpty ? nil : Group(title: qualityNames[q] ?? "", voices: voices)
        }
    }

    /// 듣기 판의 "목소리" 값: "향상됨 · 유나". 고른 목소리를 지웠으면(설정에서 내림) 기본으로 읽으므로 기본이라고 보인다.
    static func label(_ id: String?) -> String {
        guard let id, let v = AVSpeechSynthesisVoice(identifier: id) else { return "iPhone 기본" }
        return "\(qualityNames[v.quality] ?? "") · \(v.name)"
    }
}

/// 목소리 고르기(구상안 25A): 기본 · 음질별 묶음(같은 급이라 묶음 안은 한 단 들인다). "들어 보기" 는 고르지 않고 들려만 준다.
struct VoiceScreen: View {
    @ObservedObject var prefs: ReaderPrefs
    let listening: Listening
    let pick: (String?) -> Void
    let back: () -> Void

    @State private var groups: [VoiceList.Group] = []
    @State private var loaded = false
    @State private var sampler = AVSpeechSynthesizer()

    var body: some View {
        voices.onDisappear {
            sampler.stopSpeaking(at: .immediate)
            guard !listening.active else { return }
            let session = AVAudioSession.sharedInstance()
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            try? session.setCategory(.ambient, options: [.mixWithOthers])
        }
    }

    private var voices: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "목소리", subtitle: "iPhone 에 깔린 한국어 목소리",
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    label("기본")
                    row(title: "iPhone 기본 목소리", sub: nil, id: nil, sample: nil)
                    if !loaded {
                        CpCaption(text: "목소리를 찾는 중…").padding(.top, 8)
                    } else if groups.isEmpty {
                        CpCaption(text: "이 iPhone 에 한국어 목소리가 없습니다. 기본 목소리가 읽습니다.").padding(.top, 8)
                    }
                    ForEach(groups) { g in
                        label(g.title)
                        ForEach(g.voices, id: \.identifier) { v in
                            row(title: v.name, sub: nil, id: v.identifier, sample: v)
                        }
                    }
                    CpCaption(text: "목소리를 더 받으려면: 설정 › 손쉬운 사용 › 읽기 및 말하기 › 음성 › 한국어").padding(.vertical, 16)
                }
            }
        }
        .task {
            // 목소리 목록은 처음 부를 때 수백 ms 걸린다 — 화면을 먼저 띄운다.
            groups = await Task.detached { VoiceList.groups() }.value
            loaded = true
        }
        .onDisappear { sampler.stopSpeaking(at: .immediate) }
    }

    private func label(_ t: String) -> some View {
        CpSectionLabel(text: t).padding(.horizontal, CpMetrics.gutter).padding(.top, 14).padding(.bottom, 2)
    }

    private func row(title: String, sub: String?, id: String?, sample: AVSpeechSynthesisVoice?) -> some View {
        let on = prefs.listenVoice == id || (id == nil && AVSpeechSynthesisVoice(identifier: prefs.listenVoice ?? "") == nil)
        return HStack(spacing: 0) {
            Button { pick(id) } label: {
                HStack(spacing: 16) {
                    Circle().stroke(on ? CpColor.accent : CpColor.outline, lineWidth: 2).frame(width: 22, height: 22)
                        .overlay { if on { Circle().fill(CpColor.accent).frame(width: 12, height: 12) } }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(CpFont.body).foregroundStyle(CpColor.text)
                        if let sub { Text(sub).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted) }
                    }
                    Spacer(minLength: 8)
                }
                .frame(minHeight: 56).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(on ? .isSelected : [])
            if let sample {
                Button {
                    sampler.stopSpeaking(at: .immediate)
                    // 듣는 중이면 멈추고 들려준다(낭독 위에 겹쳐 들렸다). 듣기 밖이면 잠깐 소리를 쥔다 — 평소의 .ambient 는 무음 스위치를 따라
                    // 무음 모드에서 "들어 보기" 가 들리지 않았다. 화면을 닫을 때 돌려 둔다.
                    if listening.playing { listening.pause() }
                    if !listening.active {
                        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.mixWithOthers])
                        try? AVAudioSession.sharedInstance().setActive(true)
                    }
                    let u = AVSpeechUtterance(string: "안녕하세요. 이 목소리로 책을 읽어 드립니다.")
                    u.voice = sample
                    u.rate = Listening.speechRate(prefs.listenRate)
                    sampler.speak(u)
                } label: {
                    Text("들어 보기").font(CpFont.label).foregroundStyle(CpColor.accentText)
                        .padding(.horizontal, 12).frame(minHeight: CpMetrics.touchTarget).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(title) 들어 보기")
            }
        }
        // 위계 규칙: 묶음 이름(글자만)의 자식이라 한 단(16) 들인다.
        .padding(.leading, CpMetrics.gutter + CpMetrics.levelIndent).padding(.trailing, CpMetrics.gutter - 12)
    }
}

/// 자동 넘김 알약(구상안 26, 안드로이드 `CpAutoTurnPill`): "자동 넘김 · 다음 쪽까지 N초" · 멈춤/이어 하기 · 끄기.
struct AutoTurnPill: View {
    let secondsLeft: Int
    let paused: Bool
    let toggle: () -> Void
    let close: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Text(paused ? "자동 넘김 · 멈춰 있음" : "자동 넘김 · 다음 쪽까지 \(secondsLeft)초")
                .font(CpFont.label).foregroundStyle(CpColor.text).monospacedDigit().padding(.leading, 16)
            CpIconButton(icon: paused ? .play : .pause, label: paused ? "자동 넘김 이어 하기" : "자동 넘김 멈춤", action: toggle)
            CpIconButton(icon: .close, label: "자동 넘김 끄기", action: close)
        }
        .padding(.trailing, 4)
        .background(CpColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium))
        .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerMedium).stroke(CpColor.divider, lineWidth: 1))
        .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
    }
}
