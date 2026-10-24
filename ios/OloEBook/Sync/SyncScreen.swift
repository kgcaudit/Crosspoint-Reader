import OloCore
import SwiftUI

/// 동기화 설정 화면(구상안 50, 안드로이드 `SyncScreen` — 칸 셋 + 고급 설정, FTP · FTPS · SFTP). 켬에 속한 줄(서버 · 맞춘 때 · 설정 칸)은
/// 한 단 들인다(UI 규칙 1). 넓은 화면은 두 판: 왼쪽 켬 · 서버 · 맞춘 때, 오른쪽 함께 읽는 기기 · 맞추는 것(반응형 규칙).
struct SyncScreen: View {
    @ObservedObject private var sync = SyncCenter.shared
    let back: () -> Void

    /// 서버 설정 칸. 글자 그대로 들고 있다가 확인할 때 설정으로 바꾼다 — 주소 한 칸이 포트 · 방식까지 정한다.
    private struct Form {
        var address = ""
        var user = ""
        var password = ""
        var path = FtpConfig.companion.DEFAULT_PATH
        /// 새 서버는 자동. 저장된 서버는 확인해서 정해진 쪽 그대로(자동으로 되돌리면 내려가기를 다시 허락하는 셈이다).
        var security: FtpSecurity = .auto_
        var passive = true
        var encoding: FtpEncoding = .auto_

        var sftp: Bool { FtpAddress.shared.parse(text: address)?.protocol == .sftp }
    }

    private enum Pick: Identifiable {
        case security, mode, encoding
        var id: Self { self }
    }

    private struct TrustAsk {
        let config: FtpConfig
        let fingerprint: String
        let password: String
    }

    @State private var form: Form?
    @State private var advanced = false
    @State private var picking: Pick?
    @State private var checking = false
    /// 연결 확인 차례. 취소 · 끔 · 다시 누름이면 늘려서, 늦게 온 옛 결과가 서버를 저장하고 켜지 않게 한다.
    @State private var attempt = 0
    @State private var formError: String?
    @State private var trustAsk: TrustAsk?
    @State private var forgetting: SyncDevice?
    @State private var clearing = false
    @State private var renaming = false
    @State private var nameDraft = ""
    @State private var toast: String?

    private static let explain = "읽은 자리 · 책갈피 · 다 읽은 날만 내가 정한 서버 폴더에 기기마다 작은 파일(몇 KB)로 저장합니다. 책 파일은 올리지 않습니다. " +
        "집의 NAS · 공유기 · 웹 호스팅의 FTP · SFTP 를 쓸 수 있습니다."
    private static let plainWarning = "암호화되지 않은 연결입니다(일반 FTP). 비밀번호와 기록이 그대로 오가니 집 안에서만 쓰세요."

    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 0) {
                CpHeader(title: "동기화 설정", leading: AnyView(CpIconButton(icon: .back, label: "뒤로", action: back))) {}
                GeometryReader { g in
                    if IosAdaptive.shared.twoPane(width: g.size.width, height: g.size.height) {
                        HStack(alignment: .top, spacing: 0) {
                            ScrollView { serverPart.frame(maxWidth: .infinity, alignment: .leading) }
                            Rectangle().fill(CpColor.divider).frame(width: 1)
                            ScrollView { devicesPart.frame(maxWidth: .infinity, alignment: .leading) }
                        }
                    } else {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                serverPart
                                devicesPart
                            }
                            .frame(maxWidth: CGFloat(IosAdaptive.shared.CONTENT_MAX), alignment: .leading)
                            .frame(maxWidth: .infinity)
                        }
                    }
                }
            }
            .background(CpColor.background)
            popups
            if let toast {
                CpToast(message: toast)
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .allowsHitTesting(false)
                    .task(id: toast) {
                        try? await Task.sleep(for: .seconds(3))
                        if !Task.isCancelled { self.toast = nil }
                    }
            }
        }
        // 화면을 열 때마다 맞춘다 — "함께 읽는 기기" 의 마지막 때가 맞게.
        .task {
            #if DEBUG
            // 화면 확인용(시뮬레이터는 명령으로 누를 수 없다): -olo-sync-form <주소> 는 서버 칸을 채워 펼치고, -olo-sync-check 는 연결 확인을
            // 누르고, -olo-sync-advanced 는 고급 설정을 연다. 출시 빌드에는 없다.
            let args = CommandLine.arguments
            if let i = args.firstIndex(of: "-olo-sync-form"), i + 1 < args.count {
                edit()
                form?.address = args[i + 1]
                form?.user = "reader"
                form?.password = "secret"
                if args.contains("-olo-sync-check") { check() }
                if args.contains("-olo-sync-advanced") { advanced = true }
                return
            }
            #endif
            if sync.enabled { await sync.syncNow() }
        }
        .sheet(isPresented: $advanced) { advancedSheet }
        // 화면을 닫으면 진행 중인 연결 확인의 결과도 버린다.
        .onDisappear { attempt += 1 }
    }

    // ── 서버 묶음 ─────────────────────────────────────────────

    private var showsOn: Bool { sync.enabled || form != nil }

    @ViewBuilder
    private var serverPart: some View {
        VStack(alignment: .leading, spacing: 0) {
            label("FTP 서버")
            CpChoice(label: "FTP 서버로 맞추기", options: ["켬", "끔"], selected: showsOn ? 0 : 1) { turn($0 == 0) }
            if let form {
                formPart(form)
            } else if !sync.enabled {
                note(Self.explain)
                deviceNameNote(indent: false)
            } else {
                connectedPart
            }
        }
    }

    @ViewBuilder
    private var connectedPart: some View {
        let server = sync.server
        let plain = server.map { !$0.encrypted } ?? false
        Button(action: edit) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("서버").font(CpFont.body).foregroundStyle(CpColor.text)
                    Text((server?.label ?? "") + (plain ? " · 암호화 안 됨" : "")).font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Text("바꾸기").font(CpFont.label).foregroundStyle(CpColor.accentText)
            }
            .padding(.leading, CpMetrics.gutter + CpMetrics.levelIndent).padding(.trailing, CpMetrics.gutter)
            .frame(minHeight: CpMetrics.rowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        if plain { note(Self.plainWarning, indent: true, color: CpColor.error) }
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("마지막으로 맞춘 때").font(CpFont.body).foregroundStyle(CpColor.text)
                Text(statusLine).font(CpFont.subtitle).foregroundStyle(sync.problem != nil ? CpColor.error : CpColor.textMuted)
                    .lineLimit(3).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            CpButton(title: "지금 맞추기", kind: .secondary) { Task { await sync.syncNow() } }
        }
        .padding(.leading, CpMetrics.gutter + CpMetrics.levelIndent).padding(.trailing, CpMetrics.gutter).padding(.vertical, 8)
        deviceNameNote(indent: true)
    }

    /// "마지막으로 맞춘 때" 의 부제. 맞추지 못했으면 까닭과 할 일.
    private var statusLine: String {
        if sync.busy { return "맞추는 중…" }
        if let p = sync.problem { return SyncText.shared.problem(problem: p) }
        if let last = sync.lastSynced { return SyncWhen.text(last) }
        return "아직 맞춘 적이 없습니다"
    }

    /// 이 기기 이름(3A): 누르면 바꾼다. 이름은 강조 글자색 — 누를 수 있다는 표시.
    private func deviceNameNote(indent: Bool) -> some View {
        Button {
            nameDraft = sync.deviceName
            renaming = true
        } label: {
            (Text("이 기기 이름: ").foregroundStyle(CpColor.textMuted) + Text(sync.deviceName).foregroundStyle(CpColor.accentText))
                .font(CpFont.caption)
                .padding(.leading, CpMetrics.gutter + (indent ? CpMetrics.levelIndent : 0)).padding(.trailing, CpMetrics.gutter)
                .frame(minHeight: 32, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("이 기기 이름 \(sync.deviceName), 바꾸기")
    }

    /// 서버 설정 칸 셋 + 고급 설정 한 줄(구상안 C1). 켬에 속하므로 한 단 들인다.
    @ViewBuilder
    private func formPart(_ f: Form) -> some View {
        let binding = Binding(get: { form ?? f }, set: { form = $0 })
        Group {
            CpTextField(label: "서버 주소", text: binding.address, placeholder: "nas.local 또는 sftp://nas.local", keyboard: .URL)
            note("포트가 21 이 아니면 nas.local:2121 처럼 붙여 쓰세요.", indent: false)
            CpTextField(label: "사용자", text: binding.user)
            CpTextField(label: "비밀번호", text: binding.password, secret: true)
            CpLinkRow(label: "고급 설정", value: f.path + " · " + (f.sftp ? "SFTP" : SyncText.shared.security(security: f.security))) { advanced = true }
            if let formError { note(formError, indent: false, color: CpColor.error) }
            HStack(spacing: 8) {
                Spacer()
                CpButton(title: "취소", kind: .secondary, action: cancelEdit)
                CpButton(title: checking ? "확인 중…" : "연결 확인") { if !checking { check() } }
            }
            .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 8)
        }
        .padding(.leading, CpMetrics.levelIndent)
    }

    /// 고급 설정 아래 판(구상안 C2). 모두 처음 값이면 되는 것들 — 창을 짧게 두려고 따로 뺐다.
    private var advancedSheet: some View {
        let binding = Binding(get: { form ?? Form() }, set: { form = $0 })
        return VStack(alignment: .leading, spacing: 0) {
            Text("고급 설정").font(CpFont.title).foregroundStyle(CpColor.text).padding(.horizontal, CpMetrics.gutter).padding(.top, 20).padding(.bottom, 8)
            CpTextField(label: "저장 폴더", text: binding.path, placeholder: FtpConfig.companion.DEFAULT_PATH)
            // SFTP 는 늘 암호화되고 연결 하나로 오가 두 줄이 뜻이 없다.
            if form?.sftp != true {
                CpLinkRow(label: "보안 연결", value: SyncText.shared.security(security: form?.security ?? .auto_)) { picking = .security }
                CpLinkRow(label: "연결 모드", value: SyncText.shared.mode(passive: form?.passive ?? true)) { picking = .mode }
            }
            CpLinkRow(label: "파일 이름 인코딩", value: SyncText.shared.encoding(encoding: form?.encoding ?? .auto_)) { picking = .encoding }
            Text("모두 처음 값 그대로 두면 됩니다. 연결이 안 될 때만 바꾸세요.").font(CpFont.caption).foregroundStyle(CpColor.textMuted)
                .padding(.horizontal, CpMetrics.gutter).padding(.vertical, 12)
            Spacer(minLength: 0)
        }
        .overlay { if picking != nil { pickPopup } }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(CpColor.surface)
        .presentationCornerRadius(CpMetrics.cornerSheet)
    }

    // ── 함께 읽는 기기 ────────────────────────────────────────

    @ViewBuilder
    private var devicesPart: some View {
        if sync.enabled && form == nil {
            VStack(alignment: .leading, spacing: 0) {
                label("함께 읽는 기기")
                if sync.devices.isEmpty {
                    note("아직 다른 기기의 기록이 없습니다. 다른 기기에서도 같은 서버 · 같은 저장 폴더로 이 설정을 켜면 여기에 보입니다.")
                } else {
                    ForEach(sync.devices, id: \.id) { d in
                        Button { forgetting = d } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(d.name).font(CpFont.body).foregroundStyle(CpColor.text)
                                Text("\(SyncWhen.text(Date(timeIntervalSince1970: Double(d.updatedAt) / 1000))) · 책 \(d.titles)권")
                                    .font(CpFont.subtitle).foregroundStyle(CpColor.textMuted)
                            }
                            .padding(.horizontal, CpMetrics.gutter)
                            .frame(maxWidth: .infinity, minHeight: CpMetrics.rowHeight, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    note("오래 쓰지 않은 기기는 눌러서 지울 수 있습니다.")
                }
                label("맞추는 것")
                note("읽은 자리 · 책갈피 · 다 읽은 날. 책은 파일 이름과 크기로 알아봅니다 — 기기마다 같은 책 파일을 두어야 이어 읽을 수 있습니다.")
                Button { clearing = true } label: {
                    Text("서버에 저장한 기록 지우기").font(CpFont.label).foregroundStyle(CpColor.accentText)
                        .padding(CpMetrics.gutter).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    // ── 부품 ─────────────────────────────────────────────────

    private func label(_ text: String) -> some View {
        CpSectionLabel(text: text).padding(.horizontal, CpMetrics.gutter).padding(.top, 14).padding(.bottom, 4)
    }

    private func note(_ text: String, indent: Bool = false, color: Color = CpColor.textMuted) -> some View {
        Text(text).font(CpFont.caption).foregroundStyle(color).fixedSize(horizontal: false, vertical: true)
            .padding(.leading, CpMetrics.gutter + (indent ? CpMetrics.levelIndent : 0)).padding(.trailing, CpMetrics.gutter)
            .padding(.top, 4).padding(.bottom, 8)
    }

    // ── 흐름(안드로이드 `SyncScreenUi`) ────────────────────────

    private func turn(_ on: Bool) {
        if on == showsOn { return }
        if on {
            // 이미 확인한 서버가 있으면 묻지 않고 켠다(껐다 다시 켤 때).
            if sync.server != nil && !sync.savedPassword.isEmpty { sync.turnOn() } else { edit() }
            return
        }
        cancelEdit()
        sync.turnOff()
    }

    private func edit() {
        formError = nil
        var f = Form()
        if let s = sync.server {
            f.address = s.address
            f.user = s.user
            f.password = sync.savedPassword
            f.path = s.path
            f.security = s.security
            f.passive = s.passive
            f.encoding = s.encoding
        }
        form = f
    }

    private func cancelEdit() {
        attempt += 1
        checking = false
        form = nil
        formError = nil
        advanced = false
        picking = nil
        trustAsk = nil
    }

    /// "연결 확인": 잇고 · 로그인 · 폴더 만들기 · 시험 파일 쓰고 지우기. 되면 저장하고 켠다. 안 되면 칸을 그대로 두고 까닭을 보인다.
    private func check() {
        guard let f = form else { return }
        if let missing = FtpAddress.shared.missing(address: f.address, user: f.user) {
            formError = missing
            return
        }
        guard let config = FtpAddress.shared.configOf(address: f.address, user: f.user, path: f.path, security: f.security, passive: f.passive,
                                                      encoding: f.encoding, previous: sync.server) else { return }
        run(config, password: f.password)
    }

    private func run(_ config: FtpConfig, password: String) {
        attempt += 1
        let mine = attempt
        checking = true
        formError = nil
        Task {
            let r = await sync.check(config, password: password)
            // 그사이 취소 · 끔 · 화면 닫기였으면 버린다(느린 서버는 확인에 1분까지 걸린다).
            guard mine == attempt, form != nil else { return }
            checking = false
            if r.problem == nil {
                guard sync.connected(r.tried, password: password) else {
                    formError = "비밀번호를 이 기기에 저장하지 못했습니다. 잠금을 풀고 다시 해 주세요."
                    return
                }
                cancelEdit()
                toast = "연결했습니다"
            } else if r.problem == .untrusted, let fp = r.fingerprint, fp != r.tried.trusted {
                // 믿은 지문으로 다시 했는데 또 다르면 묻지 않는다 — 확인하는 사이 바뀐 것이다.
                trustAsk = TrustAsk(config: r.tried, fingerprint: fp, password: password)
            } else if let p = r.problem {
                formError = SyncText.shared.problem(problem: p)
            }
        }
    }

    // ── 팝업 ─────────────────────────────────────────────────

    @ViewBuilder
    private var popups: some View {
        if let ask = trustAsk {
            let sftp = ask.config.protocol == .sftp
            let what = sftp ? "서버 키" : "인증서"
            CpPopup(onDismiss: { trustAsk = nil }) {
                Text(ask.config.trusted != nil ? "\(what)가 바뀌었습니다" : "처음 보는 \(what)입니다").font(CpFont.title).foregroundStyle(CpColor.text)
                Text(sftp ? "\(ask.config.host) 에 처음 잇습니다. SSH 서버는 처음 이을 때 키를 확인해야 합니다. 지문이 서버 설정 화면의 것과 같으면 믿어도 됩니다."
                     : "\(ask.config.host) 의 인증서는 공인 기관이 서명하지 않았습니다(대부분 NAS 가 스스로 만든 인증서). 지문이 서버 설정 화면의 것과 같으면 믿어도 됩니다.")
                    .font(CpFont.body).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                Text("SHA-256\n" + ask.fingerprint).font(.system(size: 12, design: .monospaced)).foregroundStyle(CpColor.text)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                Text("믿으면 이 서버의 이 \(what)만 기억합니다. 나중에 바뀌면 다시 묻습니다.").font(CpFont.body).foregroundStyle(CpColor.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Spacer()
                    CpButton(title: "취소", kind: .secondary) { trustAsk = nil }
                    CpButton(title: "이 \(what) 믿기") {
                        trustAsk = nil
                        run(ask.config.withTrusted(trusted: ask.fingerprint), password: ask.password)
                    }
                }
            }
        }
        if let d = forgetting {
            CpPopup(onDismiss: { forgetting = nil }) {
                Text("‘\(d.name)’ 를 지울까요?").font(CpFont.title).foregroundStyle(CpColor.text)
                Text("이 기기가 서버에 남긴 기록을 지웁니다. 그 기기에서 이어 읽기가 켜져 있으면 다음에 다시 올라옵니다.")
                    .font(CpFont.body).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Spacer()
                    CpButton(title: "취소", kind: .secondary) { forgetting = nil }
                    CpButton(title: "지우기") {
                        forgetting = nil
                        Task { if await sync.forget(d) { toast = "‘\(d.name)’ 의 기록을 지웠습니다" } }
                    }
                }
            }
        }
        if clearing {
            CpPopup(onDismiss: { clearing = false }) {
                Text("서버의 기록을 지울까요?").font(CpFont.title).foregroundStyle(CpColor.text)
                Text("모든 기기가 저장 폴더에 남긴 읽은 자리 · 책갈피 기록을 지우고, 이 기기에서 이어 읽기를 끕니다. 폴더의 다른 파일과 이 기기의 책 · 책갈피는 그대로입니다.")
                    .font(CpFont.body).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Spacer()
                    CpButton(title: "취소", kind: .secondary) { clearing = false }
                    CpButton(title: "지우기") {
                        clearing = false
                        Task {
                            if await sync.clearRemote() {
                                toast = "서버의 기록을 지우고 이 기기에서 껐습니다. 다른 기기에서도 끄지 않으면 그 기기의 기록이 다시 올라옵니다"
                            }
                        }
                    }
                }
            }
        }
        if renaming {
            CpPopup(onDismiss: { renaming = false }) {
                Text("이 기기 이름").font(CpFont.title).foregroundStyle(CpColor.text)
                Text("다른 기기의 ‘함께 읽는 기기’ 와 ‘더 읽었습니다’ 띠에 보이는 이름입니다. 비우면 모델 이름(\(DeviceModel.name))으로 돌아갑니다.")
                    .font(CpFont.body).foregroundStyle(CpColor.textMuted).fixedSize(horizontal: false, vertical: true)
                CpTextField(label: "이름", text: $nameDraft, placeholder: DeviceModel.name).padding(.horizontal, -CpMetrics.gutter)
                HStack(spacing: 8) {
                    Spacer()
                    CpButton(title: "취소", kind: .secondary) { renaming = false }
                    CpButton(title: "저장") {
                        renaming = false
                        sync.setDeviceName(nameDraft)
                    }
                }
            }
        }
    }

    /// 고르기 판 하나(고급 설정 위). 고르면 닫힌다.
    @ViewBuilder
    private var pickPopup: some View {
        switch picking {
        case .security:
            pick("보안 연결", [
                (FtpSecurity.auto_, "자동", "FTPS 로 먼저 해 보고, 안 되면 일반 FTP 로(알림)"),
                (FtpSecurity.explicit_, "FTPS 명시적 (AUTH TLS)", "포트 21 · 대부분의 NAS"),
                (FtpSecurity.implicit, "FTPS 암묵적", "포트 990 · 오래된 서버"),
                (FtpSecurity.none, "암호화 없음", "일반 FTP · 집 안에서만"),
            ], selected: form?.security ?? .auto_) { form?.security = $0 }
        case .mode:
            pick("연결 모드", [
                (true, "수동(패시브)", "권장 · 공유기 뒤의 휴대폰에서도 됩니다"),
                (false, "능동(액티브)", "서버가 휴대폰으로 거꾸로 잇습니다 · 대부분 안 됩니다"),
            ], selected: form?.passive ?? true) { form?.passive = $0 }
        case .encoding:
            pick("파일 이름 인코딩", [
                (FtpEncoding.auto_, "자동(UTF-8)", "대부분의 서버"),
                (FtpEncoding.euckr, "EUC-KR", "한글 폴더 이름이 깨지는 옛 NAS"),
            ], selected: form?.encoding ?? .auto_) { form?.encoding = $0 }
        case nil:
            EmptyView()
        }
    }

    private func pick<T: Equatable>(_ title: String, _ options: [(T, String, String)], selected: T, onPick: @escaping (T) -> Void) -> some View {
        CpPopup(onDismiss: { picking = nil }) {
            Text(title).font(CpFont.title).foregroundStyle(CpColor.text)
            VStack(spacing: 0) {
                ForEach(options.indices, id: \.self) { i in
                    let o = options[i]
                    CpRadioRow(title: o.1, selected: o.0 == selected, subtitle: o.2, inset: 0) {
                        onPick(o.0)
                        picking = nil
                    }
                }
            }
        }
    }
}
