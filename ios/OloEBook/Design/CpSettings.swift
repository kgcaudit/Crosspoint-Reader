import SwiftUI

// 설정 · 목록 부품(안드로이드 `CpComponents.kt` · `CpReading.kt` · `CpTocList.kt` 의 짝).

/// 설정 한 줄: 이름 · 선택지 몇 개 중 하나. 한 줄에 들어가면 나란히, 모자라면 선택지를 이름 아래 줄로 내린다 — 아래 줄의
/// 선택지는 이름 글자 시작선에서 시작한다(이 줄에 속한 것이다, UI 규칙 1). 고른 칸만 강조색으로 채운다.
struct CpChoice: View {
    let label: String
    let options: [String]
    let selected: Int
    let onSelect: (Int) -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                labelText
                Spacer(minLength: 0)
                chips
            }
            VStack(alignment: .leading, spacing: 0) {
                labelText.padding(.top, 10)
                chips
            }
            // 큰 글자 · 좁은 폰에서 아래 줄도 넘치면 옆으로 밀어 본다 — 그냥 두면 끝 선택지가 화면 밖에 잘려 누를 수 없다.
            VStack(alignment: .leading, spacing: 0) {
                labelText.padding(.top, 10)
                ScrollView(.horizontal, showsIndicators: false) { chips }
            }
        }
        .padding(.horizontal, CpMetrics.gutter)
        .padding(.vertical, 2)
    }

    private var labelText: some View {
        Text(label).font(CpFont.body).foregroundStyle(CpColor.text).fixedSize(horizontal: true, vertical: false)
    }

    private var chips: some View {
        HStack(spacing: 6) {
            ForEach(options.indices, id: \.self) { i in
                let on = i == selected
                Button { onSelect(i) } label: {
                    Text(options[i]).font(CpFont.label).foregroundStyle(on ? CpColor.onAccent : CpColor.text)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 40)
                        .background(on ? CpColor.accent : Color.clear)
                        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall))
                        .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall).stroke(on ? CpColor.accent : CpColor.outline, lineWidth: 1))
                        .frame(minHeight: CpMetrics.touchTarget) // 누르는 곳은 48, 보이는 칸은 40.
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(label) \(options[i])")
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
        .fixedSize()
    }
}

/// 설정 한 줄: 이름 · [−] 값 [+].
struct CpStepper: View {
    let label: String
    let value: String
    let onMinus: () -> Void
    let onPlus: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Text(label).font(CpFont.body).foregroundStyle(CpColor.text)
            Spacer(minLength: 8)
            CpIconButton(icon: .minus, label: "\(label) 줄이기", action: onMinus)
            Text(value).font(CpFont.label).foregroundStyle(CpColor.text).frame(minWidth: 44)
                .accessibilityLabel("\(label) \(value)")
            CpIconButton(icon: .plus, label: "\(label) 늘리기", action: onPlus)
        }
        .padding(.leading, CpMetrics.gutter)
    }
}

/// 설정 한 줄: 이름 · 지금 값 · ›. 누르면 고르는 화면이 따로 열린다.
struct CpLinkRow: View {
    let label: String
    var value = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(label).font(CpFont.body).foregroundStyle(CpColor.text)
                Spacer(minLength: 8)
                if !value.isEmpty {
                    Text(value).font(CpFont.body).foregroundStyle(CpColor.accentText).lineLimit(1).frame(maxWidth: 200, alignment: .trailing)
                }
                CpIconView(icon: .forward, size: 20, color: CpColor.textMuted)
            }
            .padding(.leading, CpMetrics.gutter).padding(.trailing, 8).padding(.vertical, 4)
            .frame(minHeight: CpMetrics.touchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(value.isEmpty ? label : "\(label), \(value)")
    }
}

/// 배경 고르기: 동그라미 견본. "휴대폰" 은 반반 칠(휴대폰 다크 모드를 따름). 고른 것의 이름만 "배경" 밑에 —
/// 견본마다 이름을 달면 좁은 폰 · 큰 글자에서 "배경" 이 사라지고 마지막 견본이 찌그러졌다(안드로이드 0.29.0).
struct CpThemeSwatches: View {
    let selected: PaperTheme
    let onSelect: (PaperTheme) -> Void

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text("배경").font(CpFont.body).foregroundStyle(CpColor.text)
                Text(selected == .system ? "휴대폰 설정" : selected.label).font(CpFont.caption).foregroundStyle(CpColor.accentText)
            }
            Spacer(minLength: 8)
            ForEach(PaperTheme.allCases) { theme in
                let on = theme == selected
                Button { onSelect(theme) } label: {
                    swatch(theme)
                        .frame(width: 32, height: 32)
                        .clipShape(Circle())
                        .overlay(Circle().stroke(on ? CpColor.accent : CpColor.outline, lineWidth: on ? 3 : 1))
                        .frame(width: 40, height: CpMetrics.touchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(theme == .system ? "배경 휴대폰 설정" : "배경 \(theme.label)")
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
        .padding(.horizontal, CpMetrics.gutter)
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func swatch(_ theme: PaperTheme) -> some View {
        if theme == .system {
            LinearGradient(stops: [.init(color: Paper.light.paperColor, location: 0.5), .init(color: Paper.dark.paperColor, location: 0.5)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
        } else {
            theme.paper(.light).paperColor
        }
    }
}

/// 막대(진도 · 밝기). 손잡이는 누르는 표적이라 동그라미를 쓴다(디자인 인계 §4 모서리 줄).
struct CpSlider: View {
    let value: Double // 0...1
    let label: String
    var onChange: (Double) -> Void
    var onCommit: (Double) -> Void

    @State private var dragging: Double?

    var body: some View {
        GeometryReader { g in
            let v = dragging ?? value
            let thumb: CGFloat = 22
            let x = (g.size.width - thumb) * min(max(v, 0), 1)
            ZStack(alignment: .leading) {
                Rectangle().fill(CpColor.progressTrack).frame(height: 4)
                Rectangle().fill(CpColor.accent).frame(width: x + thumb / 2, height: 4)
                Circle().fill(CpColor.accent).frame(width: thumb, height: thumb).offset(x: x)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { d in
                    let nv = min(max((d.location.x - thumb / 2) / (g.size.width - thumb), 0), 1)
                    dragging = nv
                    onChange(nv)
                }
                .onEnded { _ in
                    if let d = dragging { onCommit(d) }
                    dragging = nil
                })
        }
        .frame(height: CpMetrics.touchTarget)
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityValue("\(Int((value * 100).rounded()))%")
        .accessibilityAdjustableAction { direction in
            let step = direction == .increment ? 0.05 : -0.05
            onCommit(min(max(value + step, 0), 1))
        }
    }
}

/// 글자 탭. 고른 탭 아래 3pt 강조색 줄, 나머지는 1pt 구분선.
struct CpTabBar: View {
    let tabs: [String]
    let selected: Int
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(tabs.indices, id: \.self) { i in
                let on = i == selected
                Button { onSelect(i) } label: {
                    // 빈칸(Spacer)으로 밑줄을 바닥에 붙이면 탭 막대가 세로로 늘어나 목록을 화면 아래로 밀어냈다. 높이를 정해 붙인다.
                    VStack(spacing: 0) {
                        Text(tabs[i]).font(CpFont.label).foregroundStyle(on ? CpColor.accentText : CpColor.textMuted)
                            .frame(maxHeight: .infinity)
                        Rectangle().fill(on ? CpColor.accent : CpColor.divider).frame(height: on ? 3 : 1)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: CpMetrics.touchTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? [.isSelected, .isButton] : [.isButton])
            }
        }
        .padding(.horizontal, CpMetrics.gutter)
    }
}

/// 목차 한 줄. 단마다 `levelIndent`(16) 들인다 — 절은 장에 속한다(UI 규칙 1). 지금 장은 `accentContainer` 바탕.
struct CpTocRow: View {
    let label: String
    let depth: Int
    var current = false
    var trailing: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(label).font(CpFont.body).foregroundStyle(current ? CpColor.onAccentContainer : CpColor.text)
                    .lineLimit(2).multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                if let trailing { Text(trailing).font(CpFont.caption).foregroundStyle(CpColor.textMuted) }
            }
            .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 4)
            .frame(minHeight: CpMetrics.touchTarget)
            .background(current ? CpColor.accentContainer : Color.clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.leading, CpMetrics.levelIndent * CGFloat(depth))
        .accessibilityAddTraits(current ? [.isSelected] : [])
    }
}

/// 빈 목록 알림(가운데, 부제 글자).
struct CpEmptyMessage: View {
    let text: String
    var body: some View {
        Text(text).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).multilineTextAlignment(.center)
            .padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 설정 화면의 도움말(줄 아래 작은 글).
struct CpCaption: View {
    let text: String
    var body: some View {
        Text(text).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, CpMetrics.gutter)
    }
}
