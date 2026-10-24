import SwiftUI

// OLO 부품(안드로이드 `CpComponents.kt` 의 짝). 이름은 `Cp` 로 시작하고, 시스템 기본 부품(List · NavigationBar · Toolbar)의
// 모양을 쓰지 않는다 — 다른 OLO 앱과 같은 식구로 보여야 한다(2026-10-20 사용자 결정: 구상안 1A · 2A).

/// 화면 머리: 제목 + 부제 + 오른쪽 아이콘 단추들. 높이 64.
struct CpHeader<Actions: View>: View {
    let title: String
    var subtitle: String?
    var leading: AnyView?
    @ViewBuilder var actions: Actions

    var body: some View {
        HStack(spacing: 0) {
            if let leading { leading } else { Spacer().frame(width: CpMetrics.gutter) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(CpFont.title).foregroundStyle(CpColor.text).lineLimit(1)
                if let subtitle {
                    Text(subtitle).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            actions
        }
        .padding(.trailing, 4)
        .frame(height: CpMetrics.headerHeight)
    }
}

/// 아이콘 단추. 누르는 곳은 48pt 아래로 내리지 않는다. 이름은 화면 읽기가 읽는다.
struct CpIconButton: View {
    let icon: CpIcon
    let label: String
    var color: Color = CpColor.text
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            CpIconView(icon: icon, color: color)
                .frame(width: CpMetrics.touchTarget, height: CpMetrics.touchTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.38)
        .accessibilityLabel(label)
    }
}

/// 아이콘 두세 칸 전환(격자 | 목록). 고른 칸만 `accentContainer` 바탕. 고른 것을 화면 읽기에도 알린다(규칙 6-5).
struct CpIconToggle<Value: Hashable>: View {
    let options: [(Value, CpIcon, String)]
    @Binding var selection: Value

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.0) { value, icon, label in
                let chosen = value == selection
                Button { selection = value } label: {
                    CpIconView(icon: icon, size: 20, color: chosen ? CpColor.accentText : CpColor.text)
                        .frame(width: 44, height: 34)
                        .background(chosen ? CpColor.accentContainer : Color.clear)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(label)
                .accessibilityAddTraits(chosen ? [.isSelected] : [])
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall))
        .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall).stroke(CpColor.outline, lineWidth: 1))
        .frame(minHeight: CpMetrics.touchTarget)
    }
}

/// 진행 막대. 얇은(2) · 보통(4). 빈 곳은 `progressTrack`.
struct CpProgressBar: View {
    let fraction: Double
    var thickness: CGFloat = 2
    var fill: Color = CpColor.accent

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Rectangle().fill(CpColor.progressTrack)
                Rectangle().fill(fill).frame(width: g.size.width * min(max(fraction, 0), 1))
            }
        }
        .frame(height: thickness)
        .clipShape(RoundedRectangle(cornerRadius: thickness / 2))
        .accessibilityHidden(true)
    }
}

/// 갈래 이름("읽는 중 · 2권").
struct CpSectionLabel: View {
    let text: String
    var body: some View {
        Text(text).font(CpFont.label).foregroundStyle(CpColor.textMuted).accessibilityAddTraits(.isHeader)
    }
}

struct CpDivider: View {
    var body: some View { Rectangle().fill(CpColor.divider).frame(height: 1).accessibilityHidden(true) }
}

/// 목록 앞머리 네모(40, 모서리 12). 색은 뜻이 있는 색(`tile*`)만.
struct CpTile: View {
    let icon: CpIcon
    let color: Color

    var body: some View {
        RoundedRectangle(cornerRadius: CpMetrics.tileCorner).fill(color)
            .frame(width: CpMetrics.tileSize, height: CpMetrics.tileSize)
            .overlay(CpIconView(icon: icon, size: 22, color: .white))
            .accessibilityHidden(true)
    }
}

/// 목록 행: 타일 · 제목 · 부제. 64 이상. 행 전체가 누르는 곳이다.
struct CpListRow: View {
    let icon: CpIcon
    let tile: Color
    let title: String
    var subtitle: String?
    /// nil 이면 안내만 하는 줄(누를 일이 없다). 흐리게 그리지 않는다 — 흐리면 고장 난 단추로 읽힌다.
    var action: (() -> Void)?

    var body: some View {
        if let action {
            Button(action: action) { content }.buttonStyle(.plain)
        } else {
            content.accessibilityElement(children: .combine)
        }
    }

    private var content: some View {
            HStack(spacing: 14) {
                CpTile(icon: icon, color: tile)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(CpFont.body).foregroundStyle(CpColor.text)
                    if let subtitle {
                        Text(subtitle).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(minHeight: CpMetrics.rowHeight)
            .contentShape(Rectangle())
    }
}

/// 단추: 주(강조색 채움) · 보조(테두리). 모서리 10, 알약 모양을 쓰지 않는다.
struct CpButton: View {
    enum Kind { case primary, secondary }
    let title: String
    var kind: Kind = .primary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).font(CpFont.label)
                .foregroundStyle(kind == .primary ? CpColor.onAccent : CpColor.accentText)
                .padding(.horizontal, 20)
                .frame(minHeight: CpMetrics.touchTarget)
                .background(kind == .primary ? CpColor.accent : Color.clear)
                .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall))
                .overlay {
                    if kind == .secondary {
                        RoundedRectangle(cornerRadius: CpMetrics.cornerSmall).stroke(CpColor.outline, lineWidth: 1)
                    }
                }
        }
        .buttonStyle(.plain)
    }
}

/// 가운데 판(모서리 20, `dialog` 색). 바깥을 누르면 닫힌다.
struct CpPopup<Content: View>: View {
    let onDismiss: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            Color.black.opacity(0.32).ignoresSafeArea().onTapGesture(perform: onDismiss)
                .accessibilityLabel("닫기")
                .accessibilityAddTraits(.isButton)
                .cpFadeIn(CpMotion.popupIn)
            // 가운데에서 0.95 → 1 로 커지며(구상안 42).
            VStack(alignment: .leading, spacing: 12) { content }
                .padding(24)
                .frame(maxWidth: 400, alignment: .leading)
                .background(CpColor.dialog)
                .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerDialog))
                .padding(.horizontal, 24)
                .cpPopIn()
        }
    }
}

/// 아이콘 아래 이름을 단 단추(안드로이드 `CpToolButton`, 0.45): 최소 46 · 그림 22 · 이름 11. 0.44 의 56 + 위아래 여백은 읽기 메뉴
/// 아래 판을 두껍게 했다. 고르면 그림은 강조색, 이름은 강조 글자색.
struct CpToolButton: View {
    let icon: CpIcon
    let label: String
    var selected = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 1) {
                CpIconView(icon: icon, size: 22, color: selected ? CpColor.accent : CpColor.text)
                Text(label).font(.system(size: 11)).foregroundStyle(selected ? CpColor.accentText : CpColor.text)
            }
            .padding(.horizontal, 12).padding(.vertical, 3)
            .frame(minHeight: 46)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// 짧은 알림(안드로이드 `CpToast`): 아래에서 64 띄운 반투명 판, 1.5초 뒤 사라진다. 줄을 바꿔 다 보인다 — 한 줄로 막으면 긴
/// 알림의 앞머리만 보여 무엇을 하라는지 알 수 없었다(안드로이드 0.28.3).
struct CpToast: View {
    let message: String

    var body: some View {
        Text(message).font(CpFont.label).foregroundStyle(.white).multilineTextAlignment(.center).lineLimit(4)
            .padding(.horizontal, 18).padding(.vertical, 10)
            .background(Color(CpColor.uiColor(0x302A24)).opacity(0.9))
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .padding(.horizontal, 24).padding(.bottom, 64)
            .accessibilityAddTraits(.updatesFrequently)
            // VoiceOver 가 읽어 주게(안드로이드 live region, 0.59.2) — "책갈피를 꽂았습니다" 같은 알림이 그 동작의 유일한 대답이다.
            // 초점이 지면에 있으면 알림은 화면에 떴다 사라질 뿐 들리지 않았다.
            .task(id: message) { UIAccessibility.post(notification: .announcement, argument: message) }
    }
}

/// 책갈피가 꽂힌 쪽의 표시(안드로이드 `CpRibbon`): 위에서 내려온 띠, 아래 끝이 V 로 파였다(22×30). 윗여백 안에서 끝나 글자를
/// 가리지 않고, 오른쪽 위 모서리를 누르면 꽂고 빼는 자리와 같은 곳이다 — 표시를 누르면 빠진다는 것이 설명 없이 읽힌다.
struct CpRibbon: View {
    var body: some View {
        Path { p in
            p.move(to: .zero)
            p.addLine(to: CGPoint(x: 22, y: 0))
            p.addLine(to: CGPoint(x: 22, y: 30))
            p.addLine(to: CGPoint(x: 11, y: 30 * 0.72))
            p.addLine(to: CGPoint(x: 0, y: 30))
            p.closeSubpath()
        }
        .fill(CpColor.accent)
        .frame(width: 22, height: 30)
        .accessibilityLabel("책갈피 꽂힌 쪽")
    }
}
