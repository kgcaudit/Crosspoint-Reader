import SwiftUI

/// 리더 위의 판들. 휴대폰은 전체 화면, 폭 600 이상(iPad · 가로)은 목차 · 보기 설정을 오른쪽 옆 판(400)으로 —
/// 안드로이드 `CpFullScreen` 과 같다(태블릿에서 휴대폰 화면을 가운데 늘여 두지 않는다, UI 규칙 2).
struct ReaderPanels: View {
    @ObservedObject var model: ReaderModel
    @ObservedObject var prefs: ReaderPrefs
    @ObservedObject var brightness: ScreenBrightness
    let insets: EdgeInsets
    let wide: Bool
    let close: () -> Void

    @State private var contentsTab = 0

    var body: some View {
        ZStack {
            switch model.panel {
            case .none:
                EmptyView()
            case .bar, .view:
                if let frame = model.frame {
                    ReaderBar(model: model, prefs: prefs, brightness: brightness, frame: frame, insets: insets, wide: wide,
                              close: close, openNotes: { contentsTab = 1; model.panel = .contents },
                              openContents: { contentsTab = 0; model.panel = .contents })
                        .transition(.opacity)
                }
            case .settings:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    ViewSettingsScreen(prefs: prefs, back: model.back)
                }
            case .fonts:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    FontsScreen(prefs: prefs, back: model.back)
                }
            case .contents:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    ContentsScreen(model: model, tab: $contentsTab)
                }
            }
        }
        .animation(.easeOut(duration: 0.18), value: model.panel)
    }
}

/// 휴대폰은 전체 화면, 넓은 화면은 오른쪽 옆 판(400) + 바깥을 누르면 닫히는 막.
private struct SidePanel<Content: View>: View {
    let wide: Bool
    let insets: EdgeInsets
    let dismiss: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        if wide {
            HStack(spacing: 0) {
                Color.black.opacity(0.27).contentShape(Rectangle()).onTapGesture(perform: dismiss)
                    .accessibilityLabel("닫기").accessibilityAddTraits(.isButton)
                content
                    .padding(.top, insets.top).padding(.bottom, insets.bottom).padding(.trailing, insets.trailing)
                    .frame(width: 400 + insets.trailing)
                    .background(CpColor.background)
            }
            .transition(.move(edge: .trailing))
        } else {
            content
                .padding(.top, insets.top).padding(.bottom, insets.bottom)
                .padding(.leading, insets.leading).padding(.trailing, insets.trailing)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(CpColor.background)
                .contentShape(Rectangle())
                .transition(.move(edge: .trailing))
        }
    }
}

/// 메뉴(구상안 2A): 위 막대 · 가운데로 지면 · 아래 띠(보기를 열면 보기 줄들 · 진도 막대 · 목차 · 독서노트 · 보기).
private struct ReaderBar: View {
    @ObservedObject var model: ReaderModel
    @ObservedObject var prefs: ReaderPrefs
    @ObservedObject var brightness: ScreenBrightness
    let frame: PageFrame
    let insets: EdgeInsets
    let wide: Bool
    let close: () -> Void
    let openNotes: () -> Void
    let openContents: () -> Void

    var body: some View {
        GeometryReader { g in
            VStack(spacing: 0) {
                top
                // 가운데는 비워 둔다 — 지면이 보여 설정을 바꾸는 대로 다시 짜이는 게 보인다. 누르면 판이 닫힌다.
                Color.clear.contentShape(Rectangle()).onTapGesture { model.panel = .none }
                VStack(spacing: 0) {
                    if model.panel == .view {
                        ScrollView {
                            ViewPanelRows(model: model, prefs: prefs, brightness: brightness)
                                // 태블릿에서는 가운데 560 — 화면 폭을 다 쓰면 이름과 단추가 멀어진다(안드로이드 VIEW_PANEL_MAX_WIDTH).
                                .frame(maxWidth: wide ? 560 : .infinity)
                                .frame(maxWidth: .infinity)
                        }
                        .scrollBounceBehavior(.basedOnSize)
                        .frame(maxHeight: g.size.height * 0.55)
                        .fixedSize(horizontal: false, vertical: true)
                    }
                    SeekBar(percent: frame.percent) { p in Task { await model.seek(p) } }
                        .padding(.horizontal, 24).padding(.top, 10)
                    HStack {
                        tool(.toc, "목차", selected: false, action: openContents)
                        tool(.note, "독서노트", selected: false, action: openNotes)
                        tool(.view, "보기", selected: model.panel == .view) {
                            model.panel = model.panel == .view ? .bar : .view
                        }
                    }
                    .padding(.bottom, 4)
                }
                .padding(.bottom, insets.bottom)
                .padding(.horizontal, max(insets.leading, insets.trailing))
                .background(CpColor.surface)
                .contentShape(Rectangle())
            }
        }
    }

    private var top: some View {
        HStack(spacing: 0) {
            CpIconButton(icon: .back, label: "서재로", action: close)
            VStack(alignment: .leading, spacing: 0) {
                Text(model.title).font(CpFont.title).foregroundStyle(CpColor.text).lineLimit(1)
                Text("\(frame.spine + 1) / \(frame.chapters)장").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            }
            Spacer(minLength: 8)
            // 찾기 · 듣기는 판 구상안을 정한 뒤에 붙인다(지금은 흐리게 — 누를 수 없다).
            CpIconButton(icon: .search, label: "찾기", enabled: false) {}
            CpIconButton(icon: .headphones, label: "듣기", enabled: false) {}
            CpIconButton(icon: frame.bookmarked ? .bookmarkFilled : .bookmark,
                         label: frame.bookmarked ? "책갈피 빼기" : "책갈피 꽂기",
                         color: frame.bookmarked ? CpColor.accent : CpColor.text) { Task { await model.toggleBookmark() } }
        }
        .padding(.trailing, 4)
        .frame(height: CpMetrics.headerHeight)
        .padding(.top, insets.top)
        .padding(.horizontal, max(insets.leading, insets.trailing))
        .background(CpColor.surface)
        .contentShape(Rectangle())
    }

    private func tool(_ icon: CpIcon, _ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                CpIconView(icon: icon, color: selected ? CpColor.accentText : CpColor.text)
                Text(label).font(CpFont.caption).foregroundStyle(selected ? CpColor.accentText : CpColor.text)
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// 보기 판의 줄들(구상안 4A · 8): 배경 · 그림 흰 바탕 · 밝기 · 글자 크기 · 글꼴 · 줄 간격 · 여백 · 모든 보기 설정.
/// 글꼴은 몇 개일지 모르므로 단추를 늘어놓지 않고 글꼴 판을 연다(결정 3A).
private struct ViewPanelRows: View {
    @ObservedObject var model: ReaderModel
    @ObservedObject var prefs: ReaderPrefs
    @ObservedObject var brightness: ScreenBrightness

    var body: some View {
        VStack(spacing: 0) {
            // 배경 바로 아래 그림 흰 바탕과 밝기 — 셋 다 화면이 어떻게 보이는가를 정한다.
            CpThemeSwatches(selected: prefs.theme) { prefs.theme = $0 }
            CpChoice(label: "그림 흰 바탕", options: ["배경색으로", "그대로"], selected: prefs.blendPictures ? 0 : 1) { prefs.blendPictures = $0 == 0 }
            HStack(spacing: 0) {
                Text("밝기").font(CpFont.body).foregroundStyle(CpColor.text).frame(width: 72, alignment: .leading)
                CpSlider(value: brightness.value ?? brightness.phone, label: "밝기",
                         onChange: { brightness.set($0) }, onCommit: { brightness.set($0) })
                Button { brightness.followPhone() } label: {
                    Text("휴대폰").font(CpFont.label)
                        .foregroundStyle(brightness.value == nil ? CpColor.accentText : CpColor.textMuted)
                        .padding(.horizontal, 8).frame(minHeight: CpMetrics.touchTarget)
                }
                .buttonStyle(.plain)
                // 배경 견본에도 "휴대폰" 이 있다. 화면 읽기로는 둘이 같은 말로 들린다.
                .accessibilityLabel("휴대폰 설정 밝기")
            }
            .padding(.horizontal, CpMetrics.gutter)
            CpCaption(text: "움직인 밝기는 이 책을 닫으면 휴대폰 밝기로 돌아갑니다").frame(maxWidth: .infinity, alignment: .leading)
            CpStepper(label: "글자 크기", value: "\(prefs.fontSize)",
                      onMinus: { prefs.fontSize = max(prefs.fontSize - 1, ReaderPrefs.fontRange.lowerBound) },
                      onPlus: { prefs.fontSize = min(prefs.fontSize + 1, ReaderPrefs.fontRange.upperBound) })
            // 값은 실제로 쓰이는 글꼴 — 뺀 글꼴 이름이 남아 있으면 고른 것과 보이는 것이 다르다.
            CpLinkRow(label: "글꼴", value: FontLibrary.shared.item(prefs.effectiveFont)?.label ?? FontsScreen.systemLabel) { model.panel = .fonts }
            CpChoice(label: "줄 간격", options: ["좁게", "보통", "넓게"], selected: prefs.lineSpacing) { prefs.lineSpacing = $0 }
            CpChoice(label: "여백", options: ["좁게", "보통", "넓게"], selected: prefs.margin) { prefs.margin = $0 }
            CpLinkRow(label: "모든 보기 설정") { model.panel = .settings }
        }
        .padding(.top, 8)
        .padding(.bottom, 4)
    }
}

/// 모든 보기 설정(구상안 5A): 이번에 동작하는 줄만. 넘기기 · 형광펜 · 두 쪽 보기 · 하단 정보는 그 기능을 만들 때 더한다.
private struct ViewSettingsScreen: View {
    @ObservedObject var prefs: ReaderPrefs
    let back: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "보기 설정", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    section("문단")
                    // 줄들은 갈래 이름에 속하므로 한 단 들인다(UI 규칙 1).
                    CpChoice(label: "정렬", options: ["원본", "양쪽", "왼쪽"], selected: prefs.align) { prefs.align = $0 }
                        .padding(.leading, CpMetrics.levelIndent)
                    CpChoice(label: "첫 줄 들여쓰기", options: ["원본", "끔"], selected: prefs.indentOff ? 1 : 0) { prefs.indentOff = $0 == 1 }
                        .padding(.leading, CpMetrics.levelIndent)
                    CpChoice(label: "문단 간격", options: ["좁게", "보통", "넓게"], selected: prefs.paragraphSpacing) { prefs.paragraphSpacing = $0 }
                        .padding(.leading, CpMetrics.levelIndent)
                    section("화면")
                    CpChoice(label: "화면 켜짐 유지", options: ["휴대폰", "10분", "항상"], selected: prefs.keepScreenOn.rawValue) {
                        prefs.keepScreenOn = ReaderPrefs.KeepScreenOn(rawValue: $0) ?? .system
                    }
                    .padding(.leading, CpMetrics.levelIndent)
                    CpCaption(text: "10분은 마지막으로 넘긴 뒤 10분입니다. 읽다 잠들어도 밤새 켜져 있지 않습니다.")
                        .padding(.leading, CpMetrics.levelIndent)
                }
                .padding(.bottom, 24)
            }
        }
    }

    private func section(_ title: String) -> some View {
        CpSectionLabel(text: title).padding(.horizontal, CpMetrics.gutter).padding(.top, 18).padding(.bottom, 6)
    }
}

/// 글꼴 판(구상안 7A 확정): 휴대폰 글꼴 · ＋ 사용자 글꼴 · 넣은 글꼴(사용자 글꼴의 자식이라 동그라미가 부모 글자 시작선에 선다,
/// UI 규칙 1). 이름은 그 글꼴로 그린다. 출판사 글꼴 줄은 책 글꼴을 읽게 되면 맨 위에 더한다.
private struct FontsScreen: View {
    static let systemLabel = "휴대폰 글꼴"

    @ObservedObject var prefs: ReaderPrefs
    let back: () -> Void
    @ObservedObject private var library = FontLibrary.shared

    @State private var picking = false
    @State private var notice: (String, String)?
    @State private var removing: UserFontItem?

    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 0) {
                CpHeader(title: "글꼴", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        CpRadioRow(title: Self.systemLabel, selected: prefs.effectiveFont == nil,
                                   subtitle: "iPhone 기본 고딕(Apple SD 산돌고딕 Neo)") { prefs.font = nil }
                        // "사용자 글꼴" 은 넣기 단추이자 넣은 글꼴들의 머리다.
                        CpIconRow(icon: .plus, title: "사용자 글꼴", subtitle: "글꼴 파일 넣기 · TTF · OTF · TTC") { picking = true }
                        ForEach(library.fonts) { font in
                            CpRadioRow(title: font.label, selected: prefs.effectiveFont == font.file,
                                       subtitle: font.hasHangul ? nil : "한글 없음 · 한글은 휴대폰 글꼴로 보입니다",
                                       titleFont: library.preview(font)) { prefs.font = font.file } trailing: {
                                CpIconButton(icon: .close, label: "\(font.label) 빼기", color: CpColor.textMuted) { removing = font }
                            }
                            // 넣은 글꼴은 "사용자 글꼴" 의 자식 — 나란하면 네 번째 선택지로 읽힌다(안드로이드 0.10.2).
                            .padding(.leading, CpMetrics.childIndent)
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
            if let notice {
                CpPopup(onDismiss: { self.notice = nil }) {
                    Text(notice.0).font(CpFont.title).foregroundStyle(CpColor.text)
                    Text(notice.1).font(CpFont.body).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                    HStack { Spacer(); CpButton(title: "확인") { self.notice = nil } }
                }
            }
            if let font = removing {
                CpPopup(onDismiss: { removing = nil }) {
                    Text("‘\(font.label)’ 글꼴을 뺄까요?").font(CpFont.title).foregroundStyle(CpColor.text)
                    Text("이 글꼴로 보던 책은 휴대폰 글꼴로 바뀝니다. 파일은 언제든 다시 넣을 수 있습니다.")
                        .font(CpFont.body).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Spacer()
                        CpButton(title: "취소", kind: .secondary) { removing = nil }
                        CpButton(title: "빼기") {
                            removing = nil
                            // 지운 글꼴을 설정에 남기면 글꼴 ID 가 휴대폰 글꼴과 갈라진 채 캐시에 들어간다.
                            if prefs.font == font.file { prefs.font = nil }
                            library.remove(font)
                        }
                    }
                }
            }
        }
        // 글꼴 파일만 고르게 한다(구상안 7). 그래도 확장자만 글꼴인 파일은 넣을 때 까닭과 함께 거절한다.
        .fileImporter(isPresented: $picking, allowedContentTypes: [.font]) { result in
            guard case let .success(url) = result else { return }
            switch library.importFont(from: url) {
            case let .added(font):
                // 넣은 글꼴로 바로 바꾼다. 넣고 나서 다시 찾아 누르게 하면 한 번 더 헤맨다.
                prefs.font = font.file
                if !font.hasHangul {
                    notice = ("한글이 없는 글꼴입니다", "영문 · 숫자는 이 글꼴로, 한글은 휴대폰 글꼴로 보입니다.")
                }
            case let .rejected(reason):
                notice = ("글꼴을 넣지 못했습니다", FontLibrary.describe(reason))
            }
        }
    }
}

/// 목차 판(구상안 6A): 머리(책 제목 · 지은이) · "목차 | 독서노트 N" 탭. 독서노트 탭은 우선 책갈피 목록.
private struct ContentsScreen: View {
    @ObservedObject var model: ReaderModel
    @Binding var tab: Int

    @State private var rows: [ContentsItem]?
    @State private var current = -1
    @State private var bookmarks: [BookmarkItem]?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: model.title, subtitle: model.author,
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: model.back))) {}
            CpTabBar(tabs: ["목차", bookmarks.map { "독서노트 \($0.count)" } ?? "독서노트"], selected: tab) { tab = $0 }
            if tab == 0 { contents } else { notes }
        }
        .task {
            guard let engine = model.engine else { return }
            if let loaded = try? await engine.contents() { rows = loaded.rows; current = loaded.current }
            bookmarks = (try? await engine.bookmarks()) ?? []
        }
    }

    @ViewBuilder
    private var contents: some View {
        if let rows {
            if rows.isEmpty {
                CpEmptyMessage(text: "목차가 없는 책입니다")
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(rows) { row in
                                CpTocRow(label: row.label, depth: row.depth, current: row.index == current) {
                                    Task { await model.jump { try await $0.goToContents(row.index) } }
                                }
                                .id(row.index)
                            }
                        }
                        .padding(.vertical, 8)
                    }
                    // 지금 장이 위에서 세 줄쯤 아래에 오게 — 맨 위에 붙이면 앞 장이 안 보여 어디쯤인지 모른다.
                    .onAppear { if current > 0 { proxy.scrollTo(max(current - 3, 0), anchor: .top) } }
                }
            }
        } else {
            Spacer()
        }
    }

    @ViewBuilder
    private var notes: some View {
        if let bookmarks {
            if bookmarks.isEmpty {
                CpEmptyMessage(text: "꽂은 책갈피가 없습니다\n메뉴 위의 책갈피 단추로 꽂습니다")
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(bookmarks) { mark in
                            Button {
                                Task { await model.jump { try await $0.goToBookmark(mark.id) } }
                            } label: {
                                HStack(alignment: .top, spacing: 14) {
                                    CpIconView(icon: .bookmarkFilled, color: CpColor.accent)
                                    Text(mark.snippet).font(CpFont.body).foregroundStyle(CpColor.text)
                                        .lineLimit(2).multilineTextAlignment(.leading)
                                    Spacer(minLength: 8)
                                    Text("\(Int(mark.percent.rounded()))%").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                                }
                                .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 12)
                                .frame(minHeight: CpMetrics.rowHeight)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        } else {
            Spacer()
        }
    }
}

/// 진도 막대. 끌면 손을 뗀 자리로 옮긴다(끄는 동안은 숫자만 바뀐다 — 끌 때마다 짜면 막대가 따라오지 못한다).
private struct SeekBar: View {
    let percent: Double
    let seek: (Double) -> Void
    @State private var dragging: Double?

    var body: some View {
        HStack(spacing: 12) {
            CpSlider(value: (dragging ?? percent) / 100, label: "진도",
                     onChange: { dragging = $0 * 100 },
                     onCommit: { v in dragging = nil; seek(v * 100) })
            Text("\(Int((dragging ?? percent).rounded()))%").font(CpFont.label).foregroundStyle(CpColor.text)
                .frame(minWidth: 44, alignment: .trailing)
        }
    }
}
