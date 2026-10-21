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
        // 오른쪽에도 gutter(안드로이드와 같다). 없으면 [+] 가 화면 끝에 붙어 둥근 모서리 폰에서 누르기 어렵다.
        .padding(.horizontal, CpMetrics.gutter)
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
                    Text(value).font(CpFont.label).foregroundStyle(CpColor.accentText).lineLimit(1).frame(maxWidth: 200, alignment: .trailing)
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

/// 막대(진도 · 밝기). 손잡이 18 · 막대 4 는 둥근 끝(안드로이드 `CpSlider`). 손잡이는 누르는 표적이라 동그라미(디자인 인계 §4).
/// [height] 는 누르는 높이 — 읽기 메뉴의 진행 줄은 40 에 줄여 넣는다(손잡이 18 이라 40 안에서도 잡힌다).
struct CpSlider: View {
    let value: Double // 0...1
    let label: String
    var height: CGFloat = CpMetrics.touchTarget
    var onChange: (Double) -> Void
    var onCommit: (Double) -> Void

    @State private var dragging: Double?

    var body: some View {
        GeometryReader { g in
            let v = dragging ?? value
            let thumb: CGFloat = 18
            let x = (g.size.width - thumb) * min(max(v, 0), 1)
            ZStack(alignment: .leading) {
                Capsule().fill(CpColor.progressTrack).frame(height: 4)
                Capsule().fill(CpColor.accent).frame(width: x + thumb / 2, height: 4)
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
        .frame(height: height)
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

/// 동그라미 하나 고르기 줄(글꼴 목록). 동그라미 22 · 간격 16 — 그래서 자식 행의 들임은 `childIndent`(38)이고,
/// 자식의 동그라미가 부모 글자 시작선에 선다(UI 규칙 1). [titleFont] 는 글꼴 이름을 그 글꼴로 그릴 때.
struct CpRadioRow<Trailing: View>: View {
    let title: String
    let selected: Bool
    var subtitle: String?
    var titleFont: Font = CpFont.body
    /// 왼쪽 안쪽 여백. 팝업 안에서는 0 — 팝업이 이미 여백을 두므로, 두 겹이면 동그라미가 제목 시작선보다 들어간다.
    var inset: CGFloat = CpMetrics.gutter
    let action: () -> Void
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 0) {
            Button(action: action) {
                HStack(spacing: 16) {
                    Circle().stroke(selected ? CpColor.accent : CpColor.outline, lineWidth: 2)
                        .frame(width: 22, height: 22)
                        .overlay { if selected { Circle().fill(CpColor.accent).frame(width: 12, height: 12) } }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(titleFont).foregroundStyle(CpColor.text).lineLimit(1)
                        if let subtitle {
                            Text(subtitle).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted).lineLimit(2)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(.leading, inset).padding(.vertical, 8)
                .frame(minHeight: CpMetrics.rowHeight)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? [.isSelected] : [])
            trailing.padding(.trailing, 4)
        }
    }
}

extension CpRadioRow where Trailing == EmptyView {
    init(title: String, selected: Bool, subtitle: String? = nil, titleFont: Font = CpFont.body, inset: CGFloat = CpMetrics.gutter,
         action: @escaping () -> Void) {
        self.init(title: title, selected: selected, subtitle: subtitle, titleFont: titleFont, inset: inset, action: action) { EmptyView() }
    }
}

/// 앞머리가 그림 하나(타일 없이)인 줄: "＋ 사용자 글꼴". 그림은 동그라미 칸(22)의 가운데에 서서
/// [CpRadioRow] 와 글자 시작선이 같다 — 같은 목록에서 시작선이 어긋나면 위계로 잘못 읽힌다.
struct CpIconRow: View {
    let icon: CpIcon
    let title: String
    var subtitle: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                CpIconView(icon: icon, size: 24, color: CpColor.text).frame(width: 22, height: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(CpFont.body).foregroundStyle(CpColor.text)
                    if let subtitle {
                        Text(subtitle).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 8)
            .frame(minHeight: CpMetrics.rowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
