import CoreText
import SwiftUI
import UIKit

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
            // 읽기 메뉴는 늘 두고 보이기만 바꾼다 — 열릴 때와 닫힐 때 모두 위 막대는 위로, 아래 막대는 아래로 움직이게(구상안 42).
            // 판을 넣고 빼면 SwiftUI 가 막대 둘을 한 덩이로 옮겨 위 막대까지 아래로 밀렸다.
            if let frame = model.frame {
                ReaderBar(model: model, prefs: prefs, brightness: brightness, frame: frame, insets: insets, wide: wide,
                          visible: model.panel == .bar || model.panel == .view,
                          close: close, openNotes: { contentsTab = 1; model.panel = .contents },
                          openContents: { contentsTab = 0; model.panel = .contents })
            }
            switch model.panel {
            case .none, .bar, .view:
                EmptyView()
            case .settings:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    ViewSettingsScreen(prefs: prefs, isPdf: model.isPdf, isComic: model.isComic,
                                       coverCount: model.isComic ? model.comicCoverCount : prefs.pdfCoverByBook.count,
                                       back: model.back, openFooter: { model.panel = .footer }, openTouch: { model.panel = .touchZones })
                }
            case .touchZones:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    TouchZonesScreen(prefs: prefs, rightToLeft: model.isComic && model.comicRTL, back: model.back)
                }
            case .footer:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    FooterSettingsScreen(prefs: prefs, pick: { model.footerSlot = $0; model.panel = .footerItem }, back: model.back)
                }
            case .footerItem:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    FooterItemScreen(prefs: prefs, slot: model.footerSlot, back: model.back)
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
            case .export:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    NotesExportScreen(model: model, initialFilter: model.exportFilter, back: model.back)
                }
            case .pageGrid:
                if let comic = model.engine as? ComicEngine {
                    SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                        ComicPageGrid(engine: comic, title: model.title,
                                      go: { p, half in Task { await model.jump { _ in await comic.go(p, half: half) } } }, back: model.back)
                    }
                }
            case .comicMarks:
                if let comic = model.engine as? ComicEngine {
                    SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                        ComicBookmarks(engine: comic, go: { p in Task { await model.jump { _ in await comic.go(p) } } }, back: model.back)
                    }
                }
            case .voices:
                SidePanel(wide: wide, insets: insets, dismiss: model.back) {
                    VoiceScreen(prefs: prefs, listening: model.listening, pick: { id in
                        prefs.listenVoice = id
                        model.listening.setVoice(id)
                    }, back: model.back)
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
struct SidePanel<Content: View>: View {
    let wide: Bool
    let insets: EdgeInsets
    let dismiss: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        // 오른쪽에서 밀려 들어오고(넓은 화면 0.35초 · 휴대폰 0.3초), 뒤 막은 같은 길이로 서서히(구상안 42). 막과 판을 따로 옮긴다 —
        // 한 덩이로 옮기면 막까지 옆으로 미끄러진다.
        if wide {
            Color.black.opacity(0.27).contentShape(Rectangle()).onTapGesture(perform: dismiss)
                .accessibilityLabel("닫기").accessibilityAddTraits(.isButton)
                .transition(CpMotion.both(.opacity, in: CpMotion.sideIn, out: CpMotion.sideOut))
            HStack(spacing: 0) {
                Spacer(minLength: 0)
                content
                    .padding(.top, insets.top).padding(.bottom, insets.bottom).padding(.trailing, insets.trailing)
                    .frame(width: 400 + insets.trailing)
                    .background(CpColor.background)
            }
            .transition(CpMotion.both(.move(edge: .trailing), in: CpMotion.sideIn, out: CpMotion.sideOut))
        } else {
            content
                .padding(.top, insets.top).padding(.bottom, insets.bottom)
                .padding(.leading, insets.leading).padding(.trailing, insets.trailing)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(CpColor.background)
                .contentShape(Rectangle())
                .transition(CpMotion.both(.move(edge: .trailing), in: CpMotion.screenIn, out: CpMotion.sideOut))
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
    /// 메뉴가 열려 있나. 닫혀도 판은 남아 막대만 화면 밖으로 움직인다.
    let visible: Bool
    let close: () -> Void
    let openNotes: () -> Void
    let openContents: () -> Void

    /// iPad: 보기 판을 보기 단추에 붙은 시스템 팝오버로(구상안 42 · A). 휴대폰은 가로여도 진행 막대 위로 펼친다.
    private var pad: Bool { min(UIScreen.main.bounds.width, UIScreen.main.bounds.height) >= ReaderPrefs.wideScreen }

    var body: some View {
        GeometryReader { g in
            VStack(spacing: 0) {
                top
                    .cpEdge(atBottom: true)
                    // 위에서 내려온다 — 제 높이만큼 위에서(구상안 42: 0.25초 들어옴 · 0.2초 나감).
                    .visualEffect { [visible] c, p in c.offset(y: visible ? 0 : -p.size.height - 12) }
                    .opacity(visible ? 1 : 0)
                    .animation(visible ? CpMotion.enter(CpMotion.barIn) : CpMotion.exit(CpMotion.barOut), value: visible)
                // 가운데는 비워 둔다 — 지면이 보여 설정을 바꾸는 대로 다시 짜이는 게 보인다. 누르면 판이 닫힌다.
                Color.clear.contentShape(Rectangle()).onTapGesture { model.panel = .none }
                VStack(spacing: 0) {
                    if model.panel == .view && !pad {
                        ScrollView {
                            ViewPanelRows(model: model, prefs: prefs, brightness: brightness)
                                // 태블릿에서는 가운데 560 — 화면 폭을 다 쓰면 이름과 단추가 멀어진다(안드로이드 VIEW_PANEL_MAX_WIDTH).
                                .frame(maxWidth: wide ? 560 : .infinity)
                                .frame(maxWidth: .infinity)
                        }
                        .scrollBounceBehavior(.basedOnSize)
                        .frame(maxHeight: g.size.height * 0.55)
                        .fixedSize(horizontal: false, vertical: true)
                        // 진행 막대 위로 펼쳐진다(0.25초 · 0.2초).
                        .transition(CpMotion.both(.move(edge: .bottom).combined(with: .opacity), in: CpMotion.barIn, out: CpMotion.barOut))
                    }
                    // 진행 줄 40 · 도구 46(안드로이드 0.45 낮은 메뉴 — 두 줄 머리와 큰 단추가 읽던 쪽을 너무 가렸다).
                    // PDF 는 쪽으로: 막대 자리는 쪽 차례, 끄는 동안 "4쪽"(안드로이드와 같다).
                    SeekBar(percent: frame.isPdf || frame.isComic ? Double(frame.page) * 100 / Double(max(frame.pages - 1, 1)) : frame.percent,
                            label: frame.isPdf || frame.isComic ? { "\(Int(($0 / 100 * Double(frame.pages - 1)).rounded()) + 1)쪽" } : nil,
                            rightToLeft: rtl) { p in
                        Task { await model.seek(p) }
                    }
                        .padding(.horizontal, CpMetrics.gutter)
                        .frame(height: 40)
                    HStack(spacing: 0) {
                        Spacer(minLength: 0)
                        if frame.isComic {
                            // 만화: 목차 대신 쪽 목록, 독서노트 대신 책갈피(안드로이드와 같다).
                            CpToolButton(icon: .grid, label: "쪽 목록") { model.panel = .pageGrid }
                            Spacer(minLength: 0)
                            CpToolButton(icon: .bookmark, label: "책갈피") { model.panel = .comicMarks }
                        } else {
                        CpToolButton(icon: .toc, label: "목차", action: openContents)
                        Spacer(minLength: 0)
                        CpToolButton(icon: .note, label: "독서노트", action: openNotes)
                        }
                        Spacer(minLength: 0)
                        CpToolButton(icon: .view, label: "보기", selected: model.panel == .view) {
                            model.panel = model.panel == .view ? .bar : .view
                        }
                        // iPad: 보기 단추를 가리키는 시스템 팝오버(구상안 42 · A). 길이 · 곡선은 시스템이 정한다.
                        .popover(isPresented: Binding(get: { pad && visible && model.panel == .view },
                                                      set: { if !$0 && model.panel == .view { model.panel = .bar } }),
                                 arrowEdge: .bottom) {
                            ScrollView {
                                ViewPanelRows(model: model, prefs: prefs, brightness: brightness).padding(.vertical, 8)
                            }
                            .frame(width: 420)
                            .frame(maxHeight: 620)
                            .presentationCompactAdaptation(.popover)
                            .environment(\.colorScheme, model.darkPaper ? .dark : .light)
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
                // 아래에서 올라온다(위 막대와 같은 값).
                .visualEffect { [visible] c, p in c.offset(y: visible ? 0 : p.size.height + 12) }
                .opacity(visible ? 1 : 0)
                .animation(visible ? CpMotion.enter(CpMotion.barIn) : CpMotion.exit(CpMotion.barOut), value: visible)
            }
        }
        .allowsHitTesting(visible)
        .accessibilityHidden(!visible)
    }

    /// 위 막대 부제: 책 "3 / 12장", PDF "4 / 6쪽", 만화 "45 / 180쪽 · 오→왼"(두 쪽이면 "2–3").
    private var subtitle: String {
        if frame.isComic {
            let pages = frame.hasRight ? "\(frame.page + 1)–\(frame.page + 2)" : "\(frame.page + 1)"
            return "\(pages) / \(frame.pages)쪽" + frame.halfLabel + (rtl ? " · 오→왼" : "")
        }
        return frame.isPdf ? "\(frame.page + 1) / \(frame.pages)쪽" : "\(frame.spine + 1) / \(frame.chapters)장"
    }

    private var rtl: Bool { frame.isComic && model.comicRTL }

    private var top: some View {
        HStack(spacing: 0) {
            CpIconButton(icon: .back, label: "뒤로", action: close)
            VStack(alignment: .leading, spacing: 0) {
                // 서재 머리(19)보다 작은 17 — 제목이 길면 한 줄에서 자른다(두 줄로 늘면 판이 다시 두꺼워진다).
                Text(model.title).font(.system(size: 17, weight: .bold)).foregroundStyle(CpColor.text).lineLimit(1)
                Text(subtitle).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
            }
            Spacer(minLength: 8)
            // PDF 의 찾기 · 듣기는 글자 층을 만든 뒤(2단계) — 그때까지 흐리게.
            if !frame.isComic {
            // PDF(구상안 34A): 단추는 늘 두고, 글자가 그림뿐인 PDF(스캔본)면 누를 때 까닭을 알린다 — 흐린 단추는 고장으로 읽힌다.
            CpIconButton(icon: .search, label: "책에서 찾기") {
                Task { if await model.pdfTextOrNotice() { model.panel = .search } }
            }
            CpIconButton(icon: .headphones, label: "듣기") {
                Task {
                    guard await model.pdfTextOrNotice() else { return }
                    await model.listen(rate: prefs.listenRate, voice: prefs.listenVoice, join: prefs.listenJoin)
                }
            }
            }
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

    /// 밝기 밀대와 그 풀이.
    @ViewBuilder
    private var brightnessRows: some View {
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
    }

    var body: some View {
        VStack(spacing: 0) {
            // 배경 바로 아래 그림 흰 바탕과 밝기 — 셋 다 화면이 어떻게 보이는가를 정한다. 만화는 늘 어두운 바탕이라 둘 다 없고,
            // 밝기는 아래(넘기는 방향 · 두 쪽 다음)에 둔다(구상안 36).
            if !model.isComic {
                CpThemeSwatches(selected: prefs.theme) { prefs.theme = $0 }
                CpChoice(label: "그림 흰 바탕", options: ["배경색으로", "그대로"], selected: prefs.blendPictures ? 0 : 1) { prefs.blendPictures = $0 == 0 }
                brightnessRows
            }
            if model.isComic, let work = model.comicWork {
                // 만화(구상안 36): 보는 방식(구상안 39, 작품마다) · 넘기는 방향(작품마다) · 두 쪽 · 표지(두 쪽일 때, 작품마다).
                let views: [String?] = [nil, "PAGE", "WEBTOON"]
                CpChoice(label: "보는 방식", options: ["자동", "쪽 넘김", "웹툰"], selected: views.firstIndex { $0 == work.view?.name } ?? 0) { i in
                    Task { await model.setComicView(views[i]) }
                }
                CpChoice(label: "넘기는 방향", options: ["왼→오", "오→왼"], selected: work.rtl ? 1 : 0) {
                    model.setComicSetting("RTL", value: $0 == 1 ? "1" : "0")
                }
                // 쪽 그림 다듬기(구상안 38 · 41, 안드로이드 0.55.1 차례): 여백 자르기 → 색 보정 → 두 쪽 보기(표지) → 넓은 그림.
                CpChoice(label: "여백 자르기", options: ["켬", "끔"], selected: prefs.comicTrim ? 0 : 1) { prefs.comicTrim = $0 == 0 }
                CpChoice(label: "색 보정", options: ["끔", "선명하게", "흑백"], selected: prefs.comicColor) { prefs.comicColor = $0 }
                CpChoice(label: "가로에서 두 쪽 보기", options: ["켬", "끔"], selected: prefs.twoPagesLandscape ? 0 : 1) { prefs.twoPagesLandscape = $0 == 0 }
                if model.pdfSpread {
                    CpChoice(label: "표지", options: ["따로", "함께"], selected: (work.coverAlone?.boolValue ?? prefs.pdfCoverAlone) ? 0 : 1) {
                        model.setComicSetting("COVER", value: $0 == 0 ? "1" : "0")
                    }
                    .padding(.leading, CpMetrics.levelIndent)
                    CpCaption(text: "이 작품만 · 겉표지와 속표지가 있으면 \"함께\" 로 두면 본문 펼침이 맞습니다")
                        .padding(.leading, CpMetrics.levelIndent).frame(maxWidth: .infinity, alignment: .leading)
                }
                // 넓은 그림(구상안 41): 두 쪽 보기 줄 바로 아래 — 두 줄이 함께 "어떻게 보이나" 를 정한다. 두 쪽 보기에서도 둔다: 고른
                // 값은 한 쪽 보기로 돌아가면 쓰인다.
                WideRow(chosen: work.splitSpreads?.boolValue) { v in model.setComicSetting("SPLIT", value: v.map { $0 ? "1" : "0" }) }
                brightnessRows
                CpLinkRow(label: "모든 보기 설정") { model.panel = .settings }
            } else if model.isComic && model.bookComic {
                // 그림책을 만화로(구상안 46): 보는 방식(웹툰) · 넓은 그림 줄은 없다 — 넓은 쪽은 자동으로 반씩. 방향 · 표지는 이 책만.
                CpChoice(label: "넘기는 방향", options: ["왼→오", "오→왼"], selected: model.bookComicRTL ? 1 : 0) {
                    model.setBookComicRTL($0 == 1)
                }
                CpChoice(label: "여백 자르기", options: ["켬", "끔"], selected: prefs.comicTrim ? 0 : 1) { prefs.comicTrim = $0 == 0 }
                CpChoice(label: "색 보정", options: ["끔", "선명하게", "흑백"], selected: prefs.comicColor) { prefs.comicColor = $0 }
                CpChoice(label: "가로에서 두 쪽 보기", options: ["켬", "끔"], selected: prefs.twoPagesLandscape ? 0 : 1) { prefs.twoPagesLandscape = $0 == 0 }
                if model.pdfSpread {
                    CpChoice(label: "표지", options: ["따로", "함께"], selected: prefs.coverAlone(model.openBookId) ? 0 : 1) {
                        prefs.pdfCoverByBook[model.openBookId] = $0 == 0
                    }
                    .padding(.leading, CpMetrics.levelIndent)
                    CpCaption(text: "이 책만 · 겉표지와 속표지가 있으면 \"함께\" 로 두면 본문 펼침이 맞습니다")
                        .padding(.leading, CpMetrics.levelIndent).frame(maxWidth: .infinity, alignment: .leading)
                }
                brightnessRows
                CpLinkRow(label: "모든 보기 설정") { model.panel = .settings }
            } else if model.isPdf {
                // PDF(구상안 32A): 글꼴 · 크기 · 줄 간격 · 여백 대신 쪽 맞춤.
                CpChoice(label: "쪽 맞춤", options: ["쪽 전체", "폭", "이어서"], selected: prefs.pdfFit) { prefs.pdfFit = $0 }
                if model.pdfSpread {
                    // 이 책만 — 겉표지와 속표지가 있으면 "함께" 여야 본문 펼침(왼쪽 짝수 쪽)이 맞는다.
                    CpChoice(label: "표지", options: ["따로", "함께"], selected: prefs.coverAlone(model.openBookId) ? 0 : 1) {
                        prefs.pdfCoverByBook[model.openBookId] = $0 == 0
                    }
                    CpCaption(text: "이 책만 · 겉표지와 속표지가 있으면 \"함께\" 로 두면 본문 펼침이 맞습니다").frame(maxWidth: .infinity, alignment: .leading)
                }
                if prefs.pdfFit == PdfFit.continuous.rawValue {
                    CpCaption(text: "이어서: 쪽을 폭에 맞춰 위아래로 이어 붙입니다. 누르면 한 화면씩 내려가고, 두 쪽 보기는 쓰지 않습니다.")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                CpLinkRow(label: "모든 보기 설정") { model.panel = .settings }
            } else {
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
        }
        .padding(.top, 8)
        .padding(.bottom, 4)
    }
}

/// 모든 보기 설정(구상안 5A): 이번에 동작하는 줄만. 넘기기 · 형광펜 · 두 쪽 보기 · 하단 정보는 그 기능을 만들 때 더한다.
struct ViewSettingsScreen: View {
    @ObservedObject var prefs: ReaderPrefs
    /// PDF 면 문단 묶음 · 형광펜 줄을 빼고, 두 쪽 줄에 쪽 맞춤의 까닭 · 표지 줄을 더한다(구상안 32A · 33).
    var isPdf = false
    /// 만화면 문단 · 형광펜 · 하단 정보 줄을 빼고, 표지 줄의 풀이가 "작품마다" 다.
    var isComic = false
    /// 웹툰(구상안 39): 넘기지 않고 밀어 내린다 — 넘김 효과 · 소리 · 진동 · 자동 넘김 · 두 쪽 줄은 바꿔도 아무 일이 없어 숨긴다
    /// (안드로이드 `CpReaderKind.Webtoon`).
    var isWebtoon = false
    /// 책 · 작품마다 바꾼 표지 수.
    var coverCount = 0
    let back: () -> Void
    let openFooter: () -> Void
    let openTouch: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: "보기 설정", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if !isPdf && !isComic {
                    section("문단")
                    // 줄들은 갈래 이름에 속하므로 한 단 들인다(UI 규칙 1).
                    CpChoice(label: "정렬", options: ["원본", "양쪽", "왼쪽"], selected: prefs.align) { prefs.align = $0 }
                        .padding(.leading, CpMetrics.levelIndent)
                    CpChoice(label: "첫 줄 들여쓰기", options: ["원본", "끔"], selected: prefs.indentOff ? 1 : 0) { prefs.indentOff = $0 == 1 }
                        .padding(.leading, CpMetrics.levelIndent)
                    CpChoice(label: "문단 간격", options: ["좁게", "보통", "넓게"], selected: prefs.paragraphSpacing) { prefs.paragraphSpacing = $0 }
                        .padding(.leading, CpMetrics.levelIndent)
                    }
                    // 넘기기(구상안 23A, 안드로이드 차례: 문단 → 넘기기 → 화면).
                    section("넘기기")
                    // 안드로이드의 "음량 단추로 넘기기" 는 두지 않는다(구상안 37 · 2A) — iOS 에는 앱이 음량 단추를 가로챌 공식 길이 없다.
                    CpLinkRow(label: "터치 영역", value: TouchZonesScreen.label(prefs.touchZones), action: openTouch)
                        .padding(.leading, CpMetrics.levelIndent)
                    if !isWebtoon {
                    CpChoice(label: "넘김 효과", options: ["없음", "서서히", "밀기", "덮기", "말림"], selected: prefs.pageTurn) { prefs.pageTurn = $0 }
                        .padding(.leading, CpMetrics.levelIndent)
                    CpChoice(label: "넘김 소리", options: TurnFeedback.names, selected: prefs.turnSound) {
                        prefs.turnSound = $0
                        TurnFeedback.shared.preview($0)
                    }
                    .padding(.leading, CpMetrics.levelIndent)
                    CpCaption(text: "무음 스위치 · 다른 소리가 나는 중에는 내지 않습니다.\n이어폰 · 가로 · 넓은 화면에서는 넘어가는 쪽에서 들립니다.")
                        .padding(.leading, CpMetrics.levelIndent)
                    // 진동 장치가 있는 휴대폰만 — iPad 에서는 눌러도 아무 일이 없는 줄이었다.
                    if UIDevice.current.userInterfaceIdiom == .phone {
                        CpChoice(label: "넘김 진동", options: ["켬", "끔"], selected: prefs.turnHaptic ? 0 : 1) {
                            prefs.turnHaptic = $0 == 0
                            if $0 == 0 { UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.6) }
                        }
                        .padding(.leading, CpMetrics.levelIndent)
                    }
                    CpChoice(label: "자동 넘김", options: ["끔", "15초", "30초", "60초"], selected: prefs.autoTurn) { prefs.autoTurn = $0 }
                        .padding(.leading, CpMetrics.levelIndent)
                    CpCaption(text: "자동 넘김은 가운데를 누르면 멈춥니다. 듣기와 함께 켜면 듣기가 넘김을 맡습니다.")
                        .padding(.leading, CpMetrics.levelIndent)
                    }
                    // 화면(안드로이드 차례: 형광펜 · 메모 → 두 쪽 보기 → 화면 켜짐 유지 → 하단 정보).
                    section("화면")
                    // 화면 회전(구상안 45): 휴대폰만 — iPad 는 창 크기를 바꾸는 앱이 방향을 고정할 수 없다(45 · 2A).
                    if UIDevice.current.userInterfaceIdiom == .phone {
                        CpChoice(label: "화면 회전", options: ReaderPrefs.rotationLabels, selected: prefs.rotation) { prefs.rotation = $0 }
                            .padding(.leading, CpMetrics.levelIndent)
                        CpCaption(text: ReaderPrefs.rotationNote)
                            .padding(.leading, CpMetrics.levelIndent).padding(.bottom, 4)
                    }
                    // 숨겨도 지우지 않는다 — 다시 보이면 그대로 있다. PDF 의 형광펜은 2단계.
                    if !isPdf && !isComic {
                        CpChoice(label: "형광펜 · 메모", options: ["보임", "숨김"], selected: prefs.showHighlights ? 0 : 1) { prefs.showHighlights = $0 == 0 }
                            .padding(.leading, CpMetrics.levelIndent)
                    }
                    if !isWebtoon {
                    CpChoice(label: "가로에서 두 쪽 보기", options: ["켬", "끔"], selected: prefs.twoPagesLandscape ? 0 : 1) {
                        prefs.twoPagesLandscape = $0 == 0
                    }
                    .padding(.leading, CpMetrics.levelIndent)
                    .opacity(pdfOnePage == nil ? 1 : 0.45)
                    .allowsHitTesting(pdfOnePage == nil)
                    if isPdf || isComic {
                        // 가로 두 쪽 줄에 속한다 — 한 단 더 들인다(UI 규칙 1).
                        CpChoice(label: "표지 (처음 여는 책)", options: ["따로", "함께"], selected: prefs.pdfCoverAlone ? 0 : 1) {
                            prefs.pdfCoverAlone = $0 == 0
                        }
                        .padding(.leading, CpMetrics.levelIndent * 2)
                        CpCaption(text: isComic ? "작품마다 보기 판에서 바꾼 값이 이보다 먼저입니다. 바꾼 작품 \(coverCount)개"
                                                : "책마다 보기 판에서 바꾼 값이 이보다 먼저입니다. 바꾼 책 \(coverCount)개")
                            .padding(.leading, CpMetrics.levelIndent * 2)
                    }
                    // 휴대폰에서는 흐리게 두고 누를 수 없다 — 숨기면 iPad 에서 본 설정을 찾지 못하고, 누를 수 있으면 바뀌는 것이 없어
                    // 고장으로 보인다(안드로이드와 같다).
                    CpChoice(label: "세로에서 두 쪽 보기", options: ["켬", "끔"], selected: prefs.twoPagesPortrait ? 0 : 1) {
                        prefs.twoPagesPortrait = $0 == 0
                    }
                    .padding(.leading, CpMetrics.levelIndent)
                    .opacity(wideDevice && pdfOnePage == nil ? 1 : 0.45)
                    .allowsHitTesting(wideDevice && pdfOnePage == nil)
                    if let why = pdfOnePage {
                        CpCaption(text: why).padding(.leading, CpMetrics.levelIndent)
                    } else if !wideDevice {
                        CpCaption(text: "넓은 화면(iPad)에서만 쓸 수 있습니다").padding(.leading, CpMetrics.levelIndent)
                    }
                    }
                    CpChoice(label: "화면 켜짐 유지", options: ["휴대폰", "10분", "항상"], selected: prefs.keepScreenOn.rawValue) {
                        prefs.keepScreenOn = ReaderPrefs.KeepScreenOn(rawValue: $0) ?? .system
                    }
                    .padding(.leading, CpMetrics.levelIndent)
                    CpCaption(text: "10분은 마지막으로 넘긴 뒤 10분입니다. 읽다 잠들어도 밤새 켜져 있지 않습니다.")
                        .padding(.leading, CpMetrics.levelIndent)
                    // 만화에는 아래 줄이 없다.
                    if !isComic {
                        CpLinkRow(label: "하단 정보", value: footerSummary, action: openFooter)
                            .padding(.leading, CpMetrics.levelIndent)
                    }
                }
                .padding(.bottom, 24)
            }
        }
    }

    private func section(_ title: String) -> some View {
        CpSectionLabel(text: title).padding(.horizontal, CpMetrics.gutter).padding(.top, 18).padding(.bottom, 6)
    }

    /// PDF 를 폭 · 이어서로 보면 두 쪽을 쓰지 않는다 — 두 쪽 줄을 흐리게 하고 까닭을 적는다(안드로이드 `pdfOnePageNote`).
    private var pdfOnePage: String? {
        guard isPdf else { return nil }
        switch PdfFit(rawValue: prefs.pdfFit) {
        case .width: return "폭 맞춤에서는 한 쪽씩 보입니다"
        case .continuous: return "이어서에서는 두 쪽 보기를 쓰지 않습니다"
        default: return nil
        }
    }

    /// 세로 두 쪽을 쓸 수 있는 기기(짧은 변 600 이상 — iPad).
    private var wideDevice: Bool {
        let b = UIScreen.main.bounds
        return min(b.width, b.height) >= ReaderPrefs.wideScreen
    }

    /// "책 제목 · 쪽 · %". 다 비면 "없음".
    private var footerSummary: String {
        let parts = prefs.footer.filter { $0 != .none }.map(\.short)
        return parts.isEmpty ? "없음" : parts.joined(separator: " · ")
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
        guard let path = model.bookFontPreview, let first = FontLibrary.descriptor(URL(fileURLWithPath: path)) else { return CpFont.body }
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
            Task {
                switch await library.importFont(from: url) {
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
}

/// 목차 판(구상안 6A): 머리(책 제목 · 지은이) · "목차 | 독서노트 N" 탭. 독서노트 탭은 우선 책갈피 목록.
private struct ContentsScreen: View {
    @ObservedObject var model: ReaderModel
    @Binding var tab: Int

    @State private var rows: [ContentsItem]?
    @State private var current = -1
    @State private var bookmarks: [BookmarkItem]?
    @State private var highlights: [HighlightItem] = []
    /// ⋮ 메뉴가 열린 줄(종류 · 번호).
    @State private var menuFor: String?
    /// 거르기: 0 전체 · 1 책갈피 · 2 형광펜 · 3 메모.
    @State private var filter = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CpHeader(title: model.title, subtitle: model.author,
                     leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: model.back))) {
                // 독서노트를 보고 있고 담을 것이 있을 때만(구상안 22).
                if tab == 1 && !entries.isEmpty {
                    Button {
                        model.exportFilter = filter
                        model.panel = .export
                    } label: {
                        Text("내보내기").font(CpFont.label).foregroundStyle(CpColor.accentText)
                            .padding(.horizontal, 12).frame(minHeight: CpMetrics.touchTarget).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            CpTabBar(tabs: ["목차", bookmarks.map { "독서노트 \($0.count + highlights.count)" } ?? "독서노트"], selected: tab) { tab = $0 }
            if tab == 0 { contents } else { notes }
        }
        .task {
            guard let engine = model.engine else { return }
            if let loaded = try? await engine.contents() { rows = loaded.rows; current = loaded.current }
            await reloadNotes()
        }
        // 메모를 쓰거나 색을 바꾸면 목록도 다시 읽는다(메모 판은 이 판 위에 뜬다).
        .onChange(of: model.notesVersion) { _, _ in Task { await reloadNotes() } }
    }

    private func reloadNotes() async {
        guard let engine = model.engine else { return }
        bookmarks = (try? await engine.bookmarks()) ?? []
        highlights = (try? await engine.noteRows()) ?? []
    }

    /// 책갈피와 형광펜을 한 목록으로: 책 차례 · 같은 자리면 책갈피가 먼저(쪽 머리에 꽂은 것이 쪽 안의 칠보다 앞에 읽힌다).
    private var entries: [NoteEntry] {
        let all = (bookmarks ?? []).map(NoteEntry.bookmark) + highlights.map(NoteEntry.highlight)
        return all.sorted { $0.order < $1.order }
    }

    @ViewBuilder
    private var contents: some View {
        if let rows {
            if rows.isEmpty {
                CpEmptyMessage(text: model.isPdf ? "목차가 없는 파일입니다. 진행 막대로 원하는 쪽에 갈 수 있습니다." : "목차가 없는 책입니다")
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(rows) { row in
                                CpTocRow(label: row.label, depth: row.depth, current: row.index == current, trailing: row.trailing) {
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

    /// 독서노트(구상안 14A · 20, 안드로이드 `CpReadingNotes`): 거르기 칩 · 장마다 갈래 이름 · 책갈피 · 칠(색 동그라미와 그 색
    /// 바탕) · 메모 상자 · 줄마다 ⋮.
    @ViewBuilder
    private var notes: some View {
        if bookmarks != nil {
            let all = entries
            VStack(spacing: 0) {
                // 옆으로 밀어 본다 — 한 줄에 우겨 넣으면 좁은 폰에서 마지막 칩이 찌그러졌다(안드로이드 0.29).
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(0..<4, id: \.self) { f in
                            let on = f == filter
                            Button { filter = f } label: {
                                Text("\(NoteEntry.filters[f]) \(all.filter { $0.shows(f) }.count)").font(CpFont.label)
                                    .foregroundStyle(on ? CpColor.onAccent : CpColor.text)
                                    .padding(.horizontal, 14).frame(minHeight: CpMetrics.touchTarget)
                                    .background(on ? CpColor.accent : Color.clear)
                                    .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall))
                                    .overlay(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall).stroke(on ? CpColor.accent : CpColor.outline, lineWidth: 1))
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(on ? [.isSelected] : [])
                        }
                    }
                    .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 4)
                }
                CpDivider()
                let shown = all.filter { $0.shows(filter) }
                if shown.isEmpty {
                    // 안드로이드 문장 그대로(칠하기 · 책갈피 두 길을 모두 알린다).
                    CpEmptyMessage(text: all.isEmpty
                        ? "독서노트가 비어 있습니다. 글자를 길게 눌러 칠하거나 쪽 오른쪽 위를 눌러 책갈피를 꽂으면 여기에 모입니다."
                        : "\(NoteEntry.filters[filter]) 항목이 없습니다")
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(Array(shown.enumerated()), id: \.element.key) { i, entry in
                                if i == 0 || shown[i - 1].section != entry.section {
                                    CpSectionLabel(text: entry.section)
                                        .padding(.horizontal, CpMetrics.gutter).padding(.top, 18).padding(.bottom, 6)
                                }
                                NoteEntryRow(entry: entry, dark: model.darkPaper, menuOpen: menuFor == entry.key,
                                             open: { open(entry) }, toggleMenu: { menuFor = menuFor == entry.key ? nil : entry.key },
                                             action: { word, pen in menuFor = nil; Task { await act(entry, word, pen) } })
                                    .zIndex(menuFor == entry.key ? 1 : 0)
                            }
                        }
                        .padding(.bottom, 24)
                    }
                }
            }
        } else {
            Spacer()
        }
    }

    private func open(_ entry: NoteEntry) {
        switch entry {
        case .bookmark(let b): Task { await model.jump { try await $0.goToBookmark(b.id) } }
        case .highlight(let h): Task { await model.goToHighlight(h.id) }
        }
    }

    private func act(_ entry: NoteEntry, _ word: String, _ pen: Int?) async {
        switch entry {
        case .bookmark(let b):
            if word == "지우기" { await model.removeBookmark(b.id) }
        case .highlight(let h):
            switch word {
            case "메모": model.memo = MemoDraft(noteId: h.id, start: h.start, end: h.end, quote: h.snippet, pen: h.pen, text: h.note ?? "")
            case "공유": model.shareOut = SharedText(text: shareText(quote: h.snippet, memo: h.note, title: model.title))
            case "지우기": await model.removeHighlight(h)
            case "색": if let pen { await model.recolor(h, pen: pen) }
            default: break
            }
        }
        await reloadNotes()
    }
}

/// 독서노트 한 항목.
enum NoteEntry {
    case bookmark(BookmarkItem)
    case highlight(HighlightItem)

    static let filters = ["전체", "책갈피", "형광펜", "메모"]

    var key: String {
        switch self {
        case .bookmark(let b): "b\(b.id)"
        case .highlight(let h): "h\(h.id)"
        }
    }

    var section: String {
        switch self {
        case .bookmark(let b): b.section
        case .highlight(let h): h.section
        }
    }

    /// 책 차례. 같은 자리면 책갈피가 먼저.
    var order: Double {
        switch self {
        case .bookmark(let b): b.percent * 1000
        case .highlight(let h): h.percent * 1000 + 0.5
        }
    }

    func shows(_ filter: Int) -> Bool {
        switch (filter, self) {
        case (0, _): true
        case (1, .bookmark): true
        case (2, .highlight): true
        case (3, .highlight(let h)): h.note != nil
        default: false
        }
    }
}

/// 진도 막대. 끌면 손을 뗀 자리로 옮긴다(끄는 동안은 숫자만 바뀐다 — 끌 때마다 짜면 막대가 따라오지 못한다).
struct SeekBar: View {
    let percent: Double
    /// 막대 옆 글(PDF 는 "4쪽"). nil 이면 "%".
    var label: ((Double) -> String)?
    /// 오→왼 만화: 막대가 오른쪽에서 차오른다(끌기도 거꾸로).
    var rightToLeft = false
    let seek: (Double) -> Void
    @State private var dragging: Double?

    var body: some View {
        HStack(spacing: 12) {
            CpSlider(value: (dragging ?? percent) / 100, label: "지금 위치", height: 40,
                     onChange: { dragging = $0 * 100 },
                     onCommit: { v in dragging = nil; seek(v * 100) })
                .scaleEffect(x: rightToLeft ? -1 : 1)
            Text(label?(dragging ?? percent) ?? "\(Int((dragging ?? percent).rounded()))%").font(CpFont.label).foregroundStyle(CpColor.text)
        }
    }
}

/// 독서노트 한 줄(안드로이드 `NoteRow`): 들임 gutter + levelIndent(32) — 노트는 그 장에 속한다(UI 규칙 1). 그림 칸 24, 글자는
/// childIndent(38) 선(아래 메모 상자가 같은 선에서 시작해 "이 칠의 메모" 로 읽힌다), 본문 3줄 · "3% · 2026.10.21." · ⋮.
private struct NoteEntryRow: View {
    let entry: NoteEntry
    let dark: Bool
    let menuOpen: Bool
    let open: () -> Void
    let toggleMenu: () -> Void
    let action: (String, Int?) -> Void
    @State private var recolor = false

    private var whereLabel: String {
        switch entry {
        case .bookmark(let b): b.whereLabel
        case .highlight(let h): noteWhere(h.percent, h.created, span: h.pageSpan)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 0) {
                Button(action: open) {
                    HStack(alignment: .top, spacing: 0) {
                        Group {
                            switch entry {
                            case .bookmark: CpIconView(icon: .bookmarkFilled, size: 20, color: CpColor.accent)
                            case .highlight(let h): Circle().fill(Pen.color(h.pen)).frame(width: 12, height: 12)
                            }
                        }
                        .frame(width: 24, height: 24)
                        Spacer().frame(width: CpMetrics.childIndent - 24)
                        VStack(alignment: .leading, spacing: 4) {
                            body(text: entry)
                                .font(CpFont.body).foregroundStyle(CpColor.text).lineSpacing(5).lineLimit(3)
                                .multilineTextAlignment(.leading)
                            Text(whereLabel).font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                CpIconButton(icon: .more, label: "\(kind) 더 보기 · \(whereLabel)", color: CpColor.textMuted) {
                    recolor = false
                    toggleMenu()
                }
            }
            if case .highlight(let h) = entry, let memo = h.note {
                Text(memo).font(CpFont.subtitle).foregroundStyle(CpColor.text).lineLimit(6)
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(CpColor.dialog).clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall))
                    .padding(.leading, CpMetrics.childIndent).padding(.trailing, CpMetrics.touchTarget).padding(.top, 8)
            }
        }
        .padding(.leading, CpMetrics.gutter + CpMetrics.levelIndent).padding(.trailing, 4).padding(.vertical, 10)
        .overlay(alignment: .topTrailing) {
            if menuOpen { menu.offset(x: -8, y: 50) }
        }
    }

    private var kind: String {
        switch entry {
        case .bookmark: "책갈피"
        case .highlight(let h): h.note == nil ? "형광펜" : "메모"
        }
    }

    private func body(text entry: NoteEntry) -> Text {
        switch entry {
        case .bookmark(let b): return Text(b.snippet)
        case .highlight(let h):
            var a = AttributedString(h.snippet)
            a.backgroundColor = Pen.fill(h.pen, dark: dark)
            return Text(a)
        }
    }

    private var words: [String] {
        if case .bookmark = entry { return ["지우기"] }
        return ["메모", "색 바꾸기", "공유", "지우기"]
    }

    private var menu: some View {
        VStack(alignment: .leading, spacing: 0) {
            if recolor, case .highlight(let h) = entry {
                PenDots(current: h.pen, ring: CpColor.text, dot: 24) { action("색", $0) }.padding(.horizontal, 6)
            } else {
                ForEach(words, id: \.self) { w in
                    Button {
                        if w == "색 바꾸기" { recolor = true } else { action(w, nil) }
                    } label: {
                        Text(w).font(CpFont.body).foregroundStyle(CpColor.text)
                            .padding(.horizontal, 16).frame(width: 160, height: CpMetrics.touchTarget, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.vertical, 6)
        .background(CpColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: CpMetrics.cornerSmall + 4))
        .shadow(color: .black.opacity(0.25), radius: 8, y: 3)
    }
}

/// "3% · 2026.10.21."(안드로이드 `noteWhere`).
func noteWhere(_ percent: Double, _ date: Date, span: String? = nil) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "ko_KR")
    f.dateFormat = "yyyy.MM.dd."
    return ["\(Int(percent.rounded()))%", span, f.string(from: date)].compactMap { $0 }.joined(separator: " · ")
}
