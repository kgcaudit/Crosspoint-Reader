import AVFoundation
import OloCore
import UIKit

/// 넘김 소리 · 진동(구상안 23A, 안드로이드 `SystemTurnFeedback`). 소리 파일은 안드로이드와 바이트까지 같은 합성음(사락 · 휙 · 톡) —
/// 외부 녹음이 아니라 사용 조건을 따질 것이 없다.
///
/// 내지 않는 때: 무음 스위치(오디오 세션 `.ambient` 가 지킨다) · 다른 앱 소리가 나는 중(음악 위에 종이 소리가 겹치면 거슬린다).
/// 좌우가 진짜 좌우일 때만 넘어가는 쪽에서 쓸려 간다(디자인 인계 §6-7, 셈은 Kotlin `TurnFx`).
@MainActor
final class TurnFeedback {
    static let shared = TurnFeedback()

    /// 0 끔 · 1 사락 · 2 휙 · 3 톡(안드로이드 `TurnSound` 차례).
    static let names = ["끔", "사락", "휙", "톡"]
    private static let files = [nil, "turn_rustle", "turn_swish", "turn_tap"]

    private var players: [String: AVAudioPlayer] = [:]
    private let haptic = UIImpactFeedbackGenerator(style: .light)

    private init() {
        // 무음 스위치를 따르고, 다른 앱 소리를 끊지 않는다. 듣기가 이미 소리를 쥐고 있으면(.playback) 건드리지 않는다 — 듣는 중 처음
        // 손으로 넘길 때 여기서 .ambient 로 바꿔, 무음 스위치가 켜진 기기에서 낭독이 무음이 되고 화면을 잠그면 낭독이 멈췄다.
        // 듣기를 끝내면 Listening 이 .ambient 로 돌려 둔다.
        let session = AVAudioSession.sharedInstance()
        if session.category != .playback { try? session.setCategory(.ambient, options: [.mixWithOthers]) }
    }

    /// 사람이 넘겼다. [forward] 는 다음 쪽인가 — 소리가 시작하는 쪽(넘어가는 종이 쪽)을 정한다.
    func turned(sound: Int, haptic on: Bool, forward: Bool, widthPt: CGFloat, landscape: Bool) {
        if on { haptic.impactOccurred(intensity: 0.6) }
        guard sound > 0 else { return }
        if AVAudioSession.sharedInstance().secondaryAudioShouldBeSilencedHint { return }
        let spatial = TurnFx.shared.spatial(headphones: headphones, landscape: landscape, widthPt: Float(widthPt))
        play(sound, side: spatial ? (forward ? 1 : -1) : 0)
    }

    /// 설정에서 소리를 골랐다 — 한 번 들려 준다(가운데).
    func preview(_ sound: Int) {
        guard sound > 0 else { return }
        play(sound, side: 0)
    }

    /// 이어폰으로 칠 출력: 유선 · 블루투스 · USB. 블루투스 스피커는 가리지 못해 이어폰으로 친다(안드로이드와 같다 — 대부분이 이어폰).
    private var headphones: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains {
            [.headphones, .bluetoothA2DP, .bluetoothLE, .bluetoothHFP, .usbAudio].contains($0.portType)
        }
    }

    /// 고른 소리의 세 갈래(가운데 · 오른쪽에서 · 왼쪽에서)를 뒤에서 미리 만든다(안드로이드 0.59.2 `warm`). 처음 넘길 때 만들면 주
    /// 스레드에서 소리를 읽고 · 늘리고 · 쓸어 그 넘김 그림의 첫 몇 장면이 빠졌다.
    func warm(_ sound: Int) {
        guard sound > 0, sound < Self.files.count, let name = Self.files[sound] else { return }
        let missing = [0, 1, -1].filter { players["\(name):\($0)"] == nil }
        guard !missing.isEmpty else { return }
        Task.detached(priority: .utility) {
            let made = missing.compactMap { side in Self.bytes(name, side: side).map { (side, $0) } }
            await MainActor.run {
                for (side, bytes) in made where self.players["\(name):\(side)"] == nil {
                    self.players["\(name):\(side)"] = try? AVAudioPlayer(data: bytes)
                    self.players["\(name):\(side)"]?.prepareToPlay()
                }
            }
        }
    }

    /// 소리 파일의 바이트. [side] 가 0 이 아니면 그쪽에서 쓸려 가게 만든 것.
    nonisolated private static func bytes(_ name: String, side: Int) -> Data? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "wav"), let data = try? Data(contentsOf: url) else { return nil }
        return side == 0 ? data : (TurnFx.shared.sweptWav(wav: data, forward: side > 0).map { $0 as Data } ?? data)
    }

    /// [side] 0 가운데 · 1 오른쪽에서 · -1 왼쪽에서. 미리 만들지 못했으면([warm]) 여기서 만든다.
    private func play(_ sound: Int, side: Int) {
        guard sound > 0, sound < Self.files.count, let name = Self.files[sound] else { return }
        let key = "\(name):\(side)"
        if players[key] == nil {
            guard let bytes = Self.bytes(name, side: side) else { return }
            players[key] = try? AVAudioPlayer(data: bytes)
            players[key]?.prepareToPlay()
        }
        guard let player = players[key] else { return }
        try? AVAudioSession.sharedInstance().setActive(true, options: [])
        player.currentTime = 0
        player.play()
    }
}
