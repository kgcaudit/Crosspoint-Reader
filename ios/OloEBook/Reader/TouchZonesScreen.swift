import OloCore
import SwiftUI

/// 터치 영역 고르기(구상안 37). 고르면 바로 보기 설정으로 돌아간다(안드로이드와 같다).
struct TouchZonesScreen: View {
    /// 보기 설정 줄의 값. 이 파일만 공통 코어를 가져온다 — 판 파일에서 가져오면 공통 코어의 `Text` 가 SwiftUI 의 것과 겹친다.
    static func label(_ index: Int) -> String { TouchZones.companion.of(index: Int32(index)).label }

    @ObservedObject var prefs: ReaderPrefs
    /// 오→왼으로 읽는 만화에서 열었다 — 글 · 그림을 실제 동작대로 뒤집는다.
    let rightToLeft: Bool
    let back: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "터치 영역", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // 좌우가 뒤집힌 까닭을 먼저 말한다 — 말없이 글만 바뀌면 설정이 저절로 바뀐 것처럼 보인다.
                    if rightToLeft {
                        CpCaption(text: "이 작품은 오→왼으로 읽습니다 — 좌우가 바뀝니다").padding(.vertical, 6)
                    }
                    ForEach(Array(TouchZones.companion.all.enumerated()), id: \.offset) { i, zones in
                        CpRadioRow(title: zones.label, selected: i == prefs.touchZones, subtitle: zones.description(rightToLeft: rightToLeft),
                                   action: { prefs.touchZones = i; back() }) {
                            MiniZones(zones: zones, rightToLeft: rightToLeft)
                        }
                    }
                }
                .padding(.top, 8)
            }
        }
    }
}

/// 터치 영역 그림: 앞 쪽(푸른 회색) · 메뉴 · 다음 쪽(클레이). 색은 실제 넘김 판단(`TouchZones.step`)에서 가져온다 — 따로 적으면
/// 오→왼에서 글은 고쳐도 그림이 반대로 남는다(안드로이드 0.54.9).
private struct MiniZones: View {
    let zones: TouchZones
    let rightToLeft: Bool

    var body: some View {
        HStack(spacing: 0) {
            side(0.1).frame(width: 36 * 0.3)
            Color(red: 0xC9 / 255, green: 0xC0 / 255, blue: 0xB6 / 255).frame(width: 36 * 0.4)
            side(0.9).frame(width: 36 * 0.3)
        }
        .frame(width: 36, height: 60)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(CpColor.outline, lineWidth: 1))
        .padding(.trailing, 12)
        .accessibilityHidden(true)
    }

    private func side(_ x: Double) -> Color {
        zones.step(x: x, rightToLeft: rightToLeft) > 0 ? CpColor.accent : Color(red: 0x5B / 255, green: 0x6B / 255, blue: 0x7A / 255)
    }
}
