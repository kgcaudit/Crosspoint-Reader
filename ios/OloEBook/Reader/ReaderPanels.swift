import CoreText
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
                    FontsScreen(model: model, prefs: prefs, back: model.back)
                }
            case .search:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    SearchScreen(search: model.search, onSearch: { model.startSearch() },
                                 onOpen: { i in Task { await model.openHit(i) } }, onBack: model.back,
                                 onCleared: { Task { await model.endSearch() } })
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
                    // 진행 줄 40 · 도구 46(안드로이드 0.45 낮은 메뉴 — 두 줄 머리와 큰 단추가 읽던 쪽을 너무 가렸다).
                    SeekBar(percent: frame.percent) { p in Task { await model.seek(p) } }
                        .padding(.horizontal, CpMetrics.gutter)
                        .frame(height: 40)
                    HStack(spacing: 0) {
                        Spacer(minLength: 0)
                        CpToolButton(icon: .toc, label: "목차", action: openContents)
                        Spacer(minLength: 0)
                        CpToolButton(icon: .note, label: "독서노트", action: openNotes)
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
            }
        }
    }

    private var top: some View {
        HStack(spacing: 0) {
            CpIconButton(icon: .back, label: "뒤로", action: close)
            VStack(alignment: .leading, spacing: 0) {
                // 서재 머리(19)보다 작은 17 — 제목이 길면 한 줄에서 자른다(두 줄로 늘면 판이 다시 두꺼워진다).
                Text(model.title).font(.system(size: 17, weight: .bold)).foregroundStyle(CpColor.text).lineLimit(1)
                Text("\(frame.spine + 1) / \(frame.chapters)장").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            }
            Spacer(minLength: 8)
            // 듣기는 판 구상안을 정한 뒤에 붙인다(지금은 흐리게 — 누를 수 없다).
            CpIconButton(icon: .search, label: "책에서 찾기") { model.panel = .search }
            CpIconButton(icon: .headphones, label: "듣기", enabled: false) {}
            CpIconButton(icon: frame.bookmarked ? .bookmarkFilled : .bookmark,
                         label: frame.bookmarked ? "책갈피 빼기" : "책갈피 꽂기",
                         color: frame.bookmarked ? CpColor.accent : CpColor.text) { Task { await model.toggleBookmark() } }
        }
        .padding(.horizontal, 4)
        .frame(height: 52)
        .padding(.top, insets.top)
        .padding(.horizontal, max(insets.leading, insets.trailing))
        .background(CpColor.surface)
        .contentShape(Rectangle())
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
                        .padding(.horizontal, 12).frame(minHeight: CpMetrics.touchTarget)
                }
                .buttonStyle(.plain)
                // 배경 견본에도 "휴대폰" 이 있다. 화면 읽기로는 둘이 같은 말로 들린다.
                .accessibilityLabel("휴대폰 설정 밝기")
                .padding(.leading, 4)
            }
            .padding(.horizontal, CpMetrics.gutter)
            .padding(.vertical, 4)
            CpCaption(text: "움직인 밝기는 이 책을 닫으면 휴대폰 밝기로 돌아갑니다").frame(maxWidth: .infinity, alignment: .leading)
            CpStepper(label: "글자 크기", value: "\(prefs.fontSize)",
                      onMinus: { prefs.fontSize = max(prefs.fontSize - 1, ReaderPrefs.fontRange.lowerBound) },
                      onPlus: { prefs.fontSize = min(prefs.fontSize + 1, ReaderPrefs.fontRange.upperBound) })
            // 값은 실제로 쓰이는 글꼴 — 뺀 글꼴 이름이 남아 있으면 고른 것과 보이는 것이 다르다.
            CpLinkRow(label: "글꼴", value: prefs.usesBookFonts(model.bookFonts) ? FontsScreen.publisherLabel
                      : FontLibrary.shared.item(prefs.effectiveFont)?.label ?? FontsScreen.systemLabel) { model.panel = .fonts }
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
/// UI 규칙 1). 이름은 그 글꼴로 그린다. "출판사 글꼴" 은 글꼴이 든 책에서만 맨 위에 — 없는 책에서 골라도 아무것도 바뀌지 않는다.
private struct FontsScreen: View {
    static let systemLabel = "휴대폰 글꼴"
    static let publisherLabel = "출판사 글꼴"

    @ObservedObject var model: ReaderModel
    @ObservedObject var prefs: ReaderPrefs
    let back: () -> Void
    @ObservedObject private var library = FontLibrary.shared

    @State private var picking = false
    @State private var notice: (String, String)?

    private var publisherOn: Bool { prefs.usesBookFonts(model.bookFonts) }

    /// "출판사 글꼴" 이름을 책 글꼴로. 아직 꺼내기 전이면 본문 글꼴(안드로이드도 같다).
    private var publisherPreview: Font {
        guard let path = model.bookFontPreview,
              let descriptors = CTFontManagerCreateFontDescriptorsFromURL(URL(fileURLWithPath: path) as CFURL) as? [CTFontDescriptor],
              let first = descriptors.first else { return CpFont.body }
        return Font(CTFontCreateWithFontDescriptor(first, 16, nil))
    }
    @State private var removing: UserFontItem?

    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 0) {
                CpHeader(title: "글꼴", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        // 출판사 글꼴이 켜져 있으면 그 줄에만 불이 들어온다. 책이 정하지 않은 곳은 늘 휴대폰 글꼴이라 아래 줄에 따로
                        // 불을 켤 것이 없다 — 설명 한 줄로 충분하다.
                        if model.bookFonts {
                            CpRadioRow(title: Self.publisherLabel, selected: publisherOn,
                                       subtitle: "이 책에 든 글꼴 · 책이 정하지 않은 곳은 휴대폰 글꼴",
                                       titleFont: publisherPreview) { prefs.publisherFonts = true }
                        }
                        CpRadioRow(title: Self.systemLabel, selected: !publisherOn && prefs.effectiveFont == nil,
                                   subtitle: "iPhone 기본 고딕(Apple SD 산돌고딕 Neo)") { prefs.font = nil; prefs.publisherFonts = false }
                        // "사용자 글꼴" 은 넣기 단추이자 넣은 글꼴들의 머리다.
                        CpIconRow(icon: .plus, title: "사용자 글꼴", subtitle: "글꼴 파일 넣기 · TTF · OTF · TTC") { picking = true }
                        ForEach(library.fonts) { font in
                            CpRadioRow(title: font.label, selected: !publisherOn && prefs.effectiveFont == font.file,
                                       subtitle: font.hasHangul ? nil : "한글 없음 · 한글은 휴대폰 글꼴로 보입니다",
                                       titleFont: library.preview(font)) { prefs.font = font.file; prefs.publisherFonts = false } trailing: {
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
                prefs.publisherFonts = false
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
    /// ⋮ 메뉴가 열린 책갈피.
    @State private var menuFor: Int64?

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
                    }
                    // 지금 장이 위에서 세 줄쯤 아래에 오게 — 맨 위에 붙이면 앞 장이 안 보여 어디쯤인지 모른다.
                    .onAppear { if current > 0 { proxy.scrollTo(max(current - 3, 0), anchor: .top) } }
                }
            }
        } else {
            Spacer()
        }
    }

    /// 독서노트(구상안 14A, 안드로이드 `CpReadingNotes`): 장마다 갈래 이름, 줄마다 ⋮ → 지우기. 거르기 칩(전체 · 책갈피 ·
    /// 형광펜 · 메모)은 형광펜 · 메모가 생길 때 — 지금은 거를 것이 책갈피뿐이다.
    @ViewBuilder
    private var notes: some View {
        if let bookmarks {
            if bookmarks.isEmpty {
                // 안드로이드 문장에서 아직 iOS 에 없는 "글자를 길게 눌러 칠하기" 를 뺐다.
                CpEmptyMessage(text: "독서노트가 비어 있습니다. 쪽 오른쪽 위를 눌러 책갈피를 꽂으면 여기에 모입니다.")
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(bookmarks.enumerated()), id: \.element.id) { i, mark in
                            if i == 0 || bookmarks[i - 1].section != mark.section {
                                CpSectionLabel(text: mark.section)
                                    .padding(.horizontal, CpMetrics.gutter).padding(.top, 18).padding(.bottom, 6)
                            }
                            NoteRow(mark: mark, menuOpen: menuFor == mark.id,
                                    open: { Task { await model.jump { try await $0.goToBookmark(mark.id) } } },
                                    toggleMenu: { menuFor = menuFor == mark.id ? nil : mark.id },
                                    remove: {
                                        menuFor = nil
                                        Task {
                                            await model.removeBookmark(mark.id)
                                            self.bookmarks = (try? await model.engine?.bookmarks()) ?? []
                                        }
                                    })
                                .zIndex(menuFor == mark.id ? 1 : 0)
                        }
                    }
                    .padding(.bottom, 24)
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
            CpSlider(value: (dragging ?? percent) / 100, label: "지금 위치", height: 40,
                     onChange: { dragging = $0 * 100 },
                     onCommit: { v in dragging = nil; seek(v * 100) })
            Text("\(Int((dragging ?? percent).rounded()))%").font(CpFont.label).foregroundStyle(CpColor.text)
        }
    }
}

/// 독서노트 한 줄(안드로이드 `NoteRow`): 들임 gutter + levelIndent(32) — 노트는 그 장에 속한다(UI 규칙 1). 그림 칸 24, 글자는
/// childIndent(38) 선, 본문 3줄 · "3% · 2026.10.21." · 오른쪽 ⋮. ⋮ 메뉴는 그 줄 아래에 붙는 작은 판(서재의 더 보기 판과 같은 모양).
private struct NoteRow: View {
    let mark: BookmarkItem
    let menuOpen: Bool
    let open: () -> Void
    let toggleMenu: () -> Void
    let remove: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Button(action: open) {
                HStack(alignment: .top, spacing: 0) {
                    CpIconView(icon: .bookmarkFilled, size: 20, color: CpColor.accent).frame(width: 24, height: 24)
                    Spacer().frame(width: CpMetrics.childIndent - 24)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(mark.snippet).font(CpFont.body).foregroundStyle(CpColor.text).lineSpacing(5).lineLimit(3)
                            .multilineTextAlignment(.leading)
                        Text(mark.whereLabel).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            // 행마다 "더 보기" 로만 읽으면 어느 노트의 메뉴인지 모른다 — 종류와 자리를 붙인다(안드로이드와 같다).
            CpIconButton(icon: .more, label: "책갈피 더 보기 · \(mark.whereLabel)", color: CpColor.textMuted, action: toggleMenu)
        }
        .padding(.leading, CpMetrics.gutter + CpMetrics.levelIndent).padding(.trailing, 4).padding(.vertical, 10)
        .overlay(alignment: .bottomTrailing) {
            if menuOpen {
                Button(action: remove) {
                    HStack(spacing: 14) {
                        CpIconView(icon: .close, size: 22)
                        Text("지우기").font(CpFont.body).foregroundStyle(CpColor.text)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16).frame(width: 160, height: CpMetrics.touchTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.vertical, 6)
                .background(CpColor.surface)
                .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall + 4))
                .shadow(color: .black.opacity(0.25), radius: 8, y: 3)
                .offset(x: -8, y: 50)
            }
        }
    }
}
