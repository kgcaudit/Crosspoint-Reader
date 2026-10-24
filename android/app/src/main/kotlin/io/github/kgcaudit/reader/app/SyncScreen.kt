package io.github.kgcaudit.reader.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.data.sync.FtpAddress
import io.github.kgcaudit.reader.data.sync.FtpConfig
import io.github.kgcaudit.reader.data.sync.FtpEncoding
import io.github.kgcaudit.reader.data.sync.FtpProtocol
import io.github.kgcaudit.reader.data.sync.FtpSecurity
import io.github.kgcaudit.reader.data.sync.FtpSyncStore
import io.github.kgcaudit.reader.data.sync.ReadingSync
import io.github.kgcaudit.reader.data.sync.SyncCheck
import io.github.kgcaudit.reader.data.sync.SyncDevice
import io.github.kgcaudit.reader.data.sync.SyncProblem
import io.github.kgcaudit.reader.data.sync.SyncStatus
import io.github.kgcaudit.reader.ui.design.CpBottomSheet
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
import io.github.kgcaudit.reader.ui.design.CpRadioRow
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTextButton
import io.github.kgcaudit.reader.ui.design.CpTextField
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.cpTwoPane
import io.github.kgcaudit.reader.ui.design.syncWhenText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ── 동기화 설정 화면(0.58.0, 사용자 확정 구상안 C — 칸 셋 + 고급 설정, FTP · FTPS · SFTP) ──────────────────

internal const val SYNC_TITLE = "동기화 설정"
internal const val SYNC_SWITCH = "FTP 서버로 맞추기"
internal const val SYNC_EXPLAIN = "읽은 자리 · 책갈피 · 다 읽은 날만 내가 정한 서버 폴더에 기기마다 작은 파일(몇 KB)로 저장합니다. " +
    "책 파일은 올리지 않습니다. 집의 NAS · 공유기 · 웹 호스팅의 FTP · SFTP 를 쓸 수 있습니다."
internal const val SYNC_PLAIN_WARNING = "암호화되지 않은 연결입니다(일반 FTP). 비밀번호와 기록이 그대로 오가니 집 안에서만 쓰세요."
internal const val SYNC_LEGACY_MESSAGE = "다른 기기에서 이어 읽기가 책 폴더(.olo) 대신 서버로 맞추도록 바뀌었습니다. 계속 쓰려면 서버를 " +
    "정하고 다시 켜 주세요. 책 폴더에 남은 .olo 폴더는 더 쓰지 않으니 지워도 됩니다."

internal const val FTP_HOST = "서버 주소"
internal const val FTP_USER = "사용자"
internal const val FTP_PASSWORD = "비밀번호"
internal const val FTP_ADVANCED = "고급 설정"
internal const val FTP_PATH = "저장 폴더"
internal const val FTP_SECURITY = "보안 연결"
internal const val FTP_MODE = "연결 모드"
internal const val FTP_ENCODING = "파일 이름 인코딩"
internal const val FTP_CHECK = "연결 확인"

/** 맞추지 못한 까닭과 할 일. "마지막으로 맞춘 때" 줄과 연결 확인의 결과가 같은 말을 쓴다. */
internal fun syncProblemText(problem: SyncProblem): String = when (problem) {
    SyncProblem.Offline -> "인터넷에 연결되지 않아 맞추지 못했습니다. 연결되면 다시 맞춥니다."
    SyncProblem.Unreachable -> "서버를 찾지 못했습니다. 주소 · 포트가 맞는지, 서버가 켜져 있는지 확인하세요."
    SyncProblem.Asleep -> "서버가 답하지 않습니다(잠들어 있거나 꺼져 있음). 깨어나면 다시 맞춥니다."
    SyncProblem.Auth -> "사용자 이름이나 비밀번호가 맞지 않습니다."
    SyncProblem.NoAccess -> "저장 폴더에 쓸 수 없습니다. 폴더 이름과 그 사용자의 쓰기 권한을 확인하세요."
    SyncProblem.Full -> "서버 저장 공간이 가득 찼습니다. 기록은 몇 KB 라 공간을 비우면 바로 맞춥니다."
    SyncProblem.Tls -> "보안 연결(FTPS)을 맺지 못했습니다. 고급 설정의 보안 연결을 바꿔 보세요."
    SyncProblem.NoTls -> "이 서버는 암호화(FTPS)를 받지 않습니다. 고급 설정의 보안 연결을 ‘자동’ 으로 두세요."
    SyncProblem.Untrusted -> "서버 인증서(키)가 믿은 것과 다릅니다. 누가 가운데서 가로챌 수 있어 맞추지 않았습니다. ‘바꾸기’ 에서 다시 연결 확인을 하세요."
    SyncProblem.Incompatible -> "서버와 맞는 연결 방식이 없습니다(오래된 SSH 키 방식이거나, FTPS 데이터 연결에 세션 재사용을 요구하는 서버). 주소를 sftp:// 로 바꾸거나 고급 설정의 보안 연결을 바꿔 보세요."
    SyncProblem.Server -> "서버가 잠시 답하지 않습니다. 조금 뒤 다시 맞춥니다."
}

/** "마지막으로 맞춘 때" 줄의 부제. 맞추지 못했으면 까닭과 할 일. */
internal fun syncStatusLine(status: SyncStatus, now: Long): String {
    val last = status.lastSyncedAtEpochMs
    return when {
        status.busy -> "맞추는 중…"
        status.problem != null -> syncProblemText(status.problem!!)
        last != null -> syncWhenText(last, now)
        else -> "아직 맞춘 적이 없습니다"
    }
}

internal fun securityLabel(security: FtpSecurity): String = when (security) {
    FtpSecurity.Auto -> "자동"
    FtpSecurity.Explicit -> "FTPS 명시적"
    FtpSecurity.Implicit -> "FTPS 암묵적"
    FtpSecurity.None -> "암호화 없음"
}

internal fun modeLabel(passive: Boolean) = if (passive) "수동(패시브)" else "능동(액티브)"
internal fun encodingLabel(encoding: FtpEncoding) = if (encoding == FtpEncoding.EucKr) "EUC-KR" else "자동(UTF-8)"

/**
 * 서버 설정 칸들. 글자 그대로 들고 있다가 확인할 때 [FtpConfig] 로 바꾼다. 주소 한 칸이 포트 · 프로토콜까지 정한다([FtpAddress]) —
 * 칸을 늘리지 않으려는 것(사용자 결정: 창이 길고 난잡하다).
 */
internal class FtpForm(config: FtpConfig?) {
    var address by mutableStateOf(config?.address.orEmpty())
    var user by mutableStateOf(config?.user.orEmpty())
    var password by mutableStateOf(config?.password.orEmpty())
    var path by mutableStateOf(config?.path ?: FtpSettings.DEFAULT_PATH)

    /** 새 서버는 자동. 저장된 서버는 확인해서 정해진 쪽 그대로(자동으로 되돌리면 내려가기를 다시 허락하는 셈이다). */
    var security by mutableStateOf(config?.security ?: FtpSecurity.Auto)
    var passive by mutableStateOf(config?.passive ?: true)
    var encoding by mutableStateOf(config?.encoding ?: FtpEncoding.Auto)
    private val trusted = config?.trusted
    private val trustedFor = config?.let { it.host to it.port }

    /** 주소 칸이 SFTP 를 가리키는가 — 고급 설정에서 FTP 만의 줄을 감춘다. */
    val sftp: Boolean get() = FtpAddress.parse(address)?.protocol == FtpProtocol.Sftp

    /** 칸이 모자라거나 읽을 수 없으면 무엇이 문제인지. 다 됐으면 null. */
    fun missing(): String? = when {
        address.isBlank() -> "서버 주소를 넣어 주세요."
        FtpAddress.parse(address) == null -> "서버 주소를 읽지 못했습니다. nas.local 또는 nas.local:2121 처럼 넣어 주세요."
        user.isBlank() -> "사용자 이름을 넣어 주세요."
        else -> null
    }

    /** 확인할 설정. 주소의 앞머리(ftps:// · sftp://)가 고급 설정보다 앞선다 — 사람이 칸에 직접 쓴 것이니까. */
    fun toConfig(): FtpConfig {
        val parsed = FtpAddress.parse(address)!!
        val protocol = parsed.protocol ?: FtpProtocol.Ftp
        val security = if (protocol == FtpProtocol.Sftp) FtpSecurity.None else parsed.security ?: security
        val port = parsed.port ?: FtpConfig.defaultPort(protocol, security)
        return FtpConfig(
            host = parsed.host,
            port = port,
            protocol = protocol,
            security = security,
            user = user.trim(),
            password = password,
            path = path.trim().ifEmpty { "/" },
            passive = passive,
            encoding = encoding,
            // 같은 서버면 믿은 지문을 이어 쓴다 — 폴더만 바꿨는데 인증서를 다시 묻지 않게.
            trusted = trusted.takeIf { trustedFor == parsed.host to port },
        )
    }
}

/** 믿을지 물을 것: 그 지문으로 다시 확인할 설정. */
internal data class TrustAsk(val config: FtpConfig, val fingerprint: String)

/** 고급 설정에서 고르는 판. */
internal enum class FtpPick { Security, Mode, Encoding }

/** 화면 상태. 서버 정하기 · 확인 · 맞추기 · 지우기의 흐름을 여기 모은다 — 화면은 그리기만. */
internal class SyncScreenUi(
    private val sync: ReadingSync,
    private val settings: FtpSettings,
    private val scope: CoroutineScope,
    private val context: android.content.Context,
) {
    // 설정을 여는 것은 키 저장소 복호화다 — 화면을 열 때 한 번만.
    var server by mutableStateOf(settings.config())
    var enabled by mutableStateOf(sync.enabled && server != null)
    var devices by mutableStateOf(sync.devices())

    /** 서버 설정 칸을 펼쳤으면 그 칸들. */
    var form by mutableStateOf<FtpForm?>(null)
    var advanced by mutableStateOf(false)
    var picking by mutableStateOf<FtpPick?>(null)
    var checking by mutableStateOf(false)
    var formError by mutableStateOf<String?>(null)
    var trustAsk by mutableStateOf<TrustAsk?>(null)
    var forgetting by mutableStateOf<SyncDevice?>(null)
    var clearing by mutableStateOf(false)
    var legacy by mutableStateOf(sync.legacyWasOn)
    var toast by mutableStateOf<String?>(null)

    val deviceName: String get() = sync.deviceName
    fun titles(device: SyncDevice): Int = sync.titlesOf(device.id)

    fun turn(on: Boolean) {
        if (on == (enabled || form != null)) return
        if (on) {
            // 이미 확인한 서버가 있으면 묻지 않고 켠다(껐다 다시 켤 때).
            if (server != null) start() else edit()
            return
        }
        cancelEdit()
        sync.enabled = false
        enabled = false
        SyncRetry.cancel(context)
    }

    fun edit() {
        formError = null
        form = FtpForm(server)
    }

    /** 돌고 있는 연결 확인. 취소 · 끔이면 거둔다 — 안 거두면 확인이 끝난 뒤 사람이 취소한 설정이 저장되고 켜졌다. */
    private var checkJob: kotlinx.coroutines.Job? = null

    fun cancelEdit() {
        checkJob?.cancel()
        checkJob = null
        checking = false
        form = null
        formError = null
        advanced = false
        picking = null
        trustAsk = null
    }

    /**
     * "연결 확인": 잇고 · 로그인하고 · 폴더를 만들고 · 시험 파일을 써 본다. 되면 저장하고 켠다. 안 되면 칸을 그대로 두고 까닭을 보인다 —
     * 저장된 설정은 건드리지 않는다(확인은 따로 만든 저장소로).
     *
     * 보안 연결 "자동" 은 명시적 FTPS 로 먼저 해 보고, 서버가 암호화를 받지 않으면 일반 FTP 로 다시 한다. 된 쪽을 저장하고, 일반이면
     * 서버 줄에 빨갛게 알린다. 처음 보는 인증서 · 서버 키는 지문을 보이고 묻는다.
     */
    fun check() {
        val f = form ?: return
        f.missing()?.let { formError = it; return }
        val base = f.toConfig()
        val first = if (base.security == FtpSecurity.Auto) base.copy(security = FtpSecurity.Explicit) else base
        run(first, fallBackToPlain = base.security == FtpSecurity.Auto)
    }

    /** 물은 지문을 믿고 다시 확인한다. */
    fun trust() {
        val ask = trustAsk ?: return
        trustAsk = null
        run(ask.config.copy(trusted = ask.fingerprint), fallBackToPlain = false)
    }

    private fun run(config: FtpConfig, fallBackToPlain: Boolean) {
        checking = true
        formError = null
        checkJob?.cancel()
        checkJob = scope.launch {
            var tried = config
            var result: SyncCheck = sync.checkConnection(FtpSyncStore({ tried }))
            if (result.problem == SyncProblem.NoTls && fallBackToPlain) {
                tried = tried.copy(security = FtpSecurity.None)
                result = sync.checkConnection(FtpSyncStore({ tried }))
            }
            checking = false
            val fingerprint = result.fingerprint
            when {
                result.problem == null -> connected(tried)
                // 믿은 지문으로 다시 했는데 또 다르면 묻지 않는다 — 확인하는 사이 바뀐 것이다.
                result.problem == SyncProblem.Untrusted && fingerprint != null && fingerprint != tried.trusted -> trustAsk = TrustAsk(tried, fingerprint)
                else -> formError = syncProblemText(result.problem!!)
            }
        }
    }

    private fun connected(config: FtpConfig) {
        settings.save(config)
        server = settings.config()
        cancelEdit()
        toast = "연결했습니다"
        start()
    }

    private fun start() {
        // 서버를 바꿨어도 다시 켜는 것으로 "이미 올린 내용" 기억이 지워져, 새 서버에 이 기기 기록을 처음부터 올린다.
        sync.enabled = true
        enabled = true
        if (legacy) dismissLegacy()
        refresh()
    }

    /** 이 기기 기록을 올리고 다른 기기 것을 받는다. 켤 때 · 화면을 열 때 · "지금 맞추기". */
    fun refresh() {
        if (!enabled) return
        scope.launch {
            sync.syncNow()
            devices = sync.devices()
            SyncRetry.afterSync(context, sync)
        }
    }

    fun forget(device: SyncDevice) {
        forgetting = null
        scope.launch {
            if (sync.forget(device.id)) toast = "‘${device.name}’ 의 기록을 지웠습니다"
            devices = sync.devices()
        }
    }

    fun clear() {
        clearing = false
        scope.launch {
            if (sync.clearRemote()) {
                enabled = false
                devices = emptyList()
                toast = "서버의 기록을 지우고 이 기기에서 껐습니다. 다른 기기에서도 끄지 않으면 그 기기의 기록이 다시 올라옵니다"
            }
        }
    }

    fun dismissLegacy() {
        sync.dismissLegacy()
        legacy = false
    }
}

@Composable
internal fun SyncScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ui = remember { SyncScreenUi(container.sync, container.ftpSettings, scope, context.applicationContext) }
    // 화면을 열 때마다 맞춘다 — "함께 읽는 기기" 의 마지막 때가 맞게.
    LaunchedEffect(ui) { ui.refresh() }
    val c = CpTheme.colors
    Box(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            CpHeader(SYNC_TITLE, onBack = onBack)
            if (cpTwoPane()) {
                // 넓음: 왼쪽 판은 켬 · 서버 · 맞춘 때, 오른쪽 판은 함께 읽는 기기 · 맞추는 것(반응형 규칙).
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { ServerPart(ui) }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { DevicesPart(ui) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ServerPart(ui)
                    DevicesPart(ui)
                }
            }
        }
        ui.form?.let { if (ui.advanced) AdvancedSheet(ui, it) }
        SyncPopups(ui)
        CpToast(ui.toast, { ui.toast = null }, Modifier.align(Alignment.BottomCenter), durationMs = 3_000)
    }
}

/** 서버 묶음: 켬 · 끔, 켬에 속한 서버 · 맞춘 때 · 설정 칸(한 단 들임 — 위계 규칙). */
@Composable
private fun ServerPart(ui: SyncScreenUi) {
    val c = CpTheme.colors
    val status by LocalContext.current.container.sync.status.collectAsState()
    CpSectionLabel("FTP 서버")
    CpChoice(SYNC_SWITCH, listOf("켬", "끔"), if (ui.enabled || ui.form != null) 0 else 1, { ui.turn(it == 0) })
    val child = Modifier.padding(start = CpTheme.metrics.levelIndent)
    ui.form?.let { form ->
        FormPart(ui, form, child)
        return
    }
    if (!ui.enabled) {
        Note(SYNC_EXPLAIN)
        Note("이 휴대폰 이름: ${ui.deviceName}")
        return
    }
    val server = ui.server
    val plain = server != null && !server.encrypted
    CpListRow("서버", ui::edit, child, subtitle = server?.let { it.label + if (plain) " · 암호화 안 됨" else "" }.orEmpty(), value = "바꾸기")
    if (plain) Note(SYNC_PLAIN_WARNING, indent = true, color = c.error)
    Row(child.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            CpText("마지막으로 맞춘 때", CpTheme.type.body, c.text)
            CpText(syncStatusLine(status, System.currentTimeMillis()), CpTheme.type.subtitle, if (status.problem != null) c.error else c.textMuted, maxLines = 3)
        }
        Spacer(Modifier.width(12.dp))
        CpButton("지금 맞추기", ui::refresh, primary = false)
    }
    Note("이 휴대폰 이름: ${ui.deviceName}", indent = true)
}

/** 서버 설정 칸 셋 + 고급 설정 한 줄(구상안 C1). 켬에 속하므로 한 단 들인다. */
@Composable
private fun FormPart(ui: SyncScreenUi, form: FtpForm, child: Modifier) {
    val c = CpTheme.colors
    CpTextField(FTP_HOST, form.address, { form.address = it }, child, placeholder = "nas.local 또는 sftp://nas.local", keyboard = KeyboardType.Uri)
    Note("포트가 21 이 아니면 nas.local:2121 처럼 붙여 쓰세요.", indent = true)
    CpTextField(FTP_USER, form.user, { form.user = it }, child)
    CpTextField(FTP_PASSWORD, form.password, { form.password = it }, child, secret = true)
    CpLinkRow(FTP_ADVANCED, form.path + " · " + if (form.sftp) "SFTP" else securityLabel(form.security), { ui.advanced = true }, child)
    ui.formError?.let { Note(it, indent = true, color = c.error) }
    Row(child.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp)) {
        Spacer(Modifier.weight(1f))
        CpButton("취소", ui::cancelEdit, primary = false)
        Spacer(Modifier.width(8.dp))
        CpButton(if (ui.checking) "확인 중…" else FTP_CHECK, { if (!ui.checking) ui.check() })
    }
}

/** 고급 설정 아래 판(구상안 C2). 모두 처음 값이면 되는 것들 — 창을 짧게 두려고 따로 뺐다. */
@Composable
private fun AdvancedSheet(ui: SyncScreenUi, form: FtpForm) {
    val c = CpTheme.colors
    CpBottomSheet(onDismiss = { ui.advanced = false }, horizontalPadding = 0.dp) {
        CpText(FTP_ADVANCED, CpTheme.type.title, c.text, Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 12.dp))
        CpTextField(FTP_PATH, form.path, { form.path = it }, placeholder = FtpSettings.DEFAULT_PATH)
        // SFTP 는 늘 암호화되고 연결 하나로 오가 두 줄이 뜻이 없다.
        if (!form.sftp) {
            CpLinkRow(FTP_SECURITY, securityLabel(form.security), { ui.picking = FtpPick.Security })
            CpLinkRow(FTP_MODE, modeLabel(form.passive), { ui.picking = FtpPick.Mode })
        }
        CpLinkRow(FTP_ENCODING, encodingLabel(form.encoding), { ui.picking = FtpPick.Encoding })
        CpText(
            "모두 처음 값 그대로 두면 됩니다. 연결이 안 될 때만 바꾸세요.", CpTheme.type.caption, c.textMuted,
            Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 12.dp), maxLines = 3,
        )
    }
}

@Composable
private fun DevicesPart(ui: SyncScreenUi) {
    if (!ui.enabled || ui.form != null) return
    val now = System.currentTimeMillis()
    CpSectionLabel("함께 읽는 기기")
    if (ui.devices.isEmpty()) {
        Note("아직 다른 기기의 기록이 없습니다. 다른 기기에서도 같은 서버 · 같은 저장 폴더로 이 설정을 켜면 여기에 보입니다.")
    } else {
        ui.devices.forEach { d -> CpListRow(d.name, { ui.forgetting = d }, subtitle = "${syncWhenText(d.updatedAtEpochMs, now)} · 책 ${ui.titles(d)}권") }
        Note("오래 쓰지 않은 기기는 눌러서 지울 수 있습니다.")
    }
    CpSectionLabel("맞추는 것")
    Note("읽은 자리 · 책갈피 · 다 읽은 날. 책은 파일 이름과 크기로 알아봅니다 — 기기마다 같은 책 파일을 두어야 이어 읽을 수 있습니다.")
    Spacer(Modifier.height(8.dp))
    CpTextButton("서버에 저장한 기록 지우기", { ui.clearing = true }, Modifier.padding(CpTheme.metrics.gutter))
}

@Composable
private fun Note(text: String, indent: Boolean = false, color: Color = CpTheme.colors.textMuted) = CpText(
    text, CpTheme.type.caption, color,
    Modifier.padding(start = CpTheme.metrics.gutter + if (indent) CpTheme.metrics.levelIndent else 0.dp, end = CpTheme.metrics.gutter, top = 4.dp, bottom = 8.dp),
    maxLines = Int.MAX_VALUE,
)

/** 고르기 판 하나. 고르면 닫힌다. */
@Composable
private fun <T> PickPopup(ui: SyncScreenUi, title: String, options: List<Triple<T, String, String>>, selected: T, onPick: (T) -> Unit) {
    CpPopup(title = title, onDismiss = { ui.picking = null }) {
        Column {
            options.forEach { (value, label, subtitle) ->
                CpRadioRow(label, value == selected, { onPick(value); ui.picking = null }, subtitle = subtitle, inset = 0.dp)
            }
        }
    }
}

@Composable
private fun SyncPopups(ui: SyncScreenUi) {
    val form = ui.form
    if (form != null) when (ui.picking) {
        FtpPick.Security -> PickPopup(
            ui, FTP_SECURITY,
            listOf(
                Triple(FtpSecurity.Auto, "자동", "FTPS 로 먼저 해 보고, 안 되면 일반 FTP 로(알림)"),
                Triple(FtpSecurity.Explicit, "FTPS 명시적 (AUTH TLS)", "포트 21 · 대부분의 NAS"),
                Triple(FtpSecurity.Implicit, "FTPS 암묵적", "포트 990 · 오래된 서버"),
                Triple(FtpSecurity.None, "암호화 없음", "일반 FTP · 집 안에서만"),
            ),
            form.security,
        ) { form.security = it }
        FtpPick.Mode -> PickPopup(
            ui, FTP_MODE,
            listOf(
                Triple(true, "수동(패시브)", "권장 · 공유기 뒤의 휴대폰에서도 됩니다"),
                Triple(false, "능동(액티브)", "서버가 휴대폰으로 거꾸로 잇습니다 · 대부분 안 됩니다"),
            ),
            form.passive,
        ) { form.passive = it }
        FtpPick.Encoding -> PickPopup(
            ui, FTP_ENCODING,
            listOf(
                Triple(FtpEncoding.Auto, "자동(UTF-8)", "대부분의 서버"),
                Triple(FtpEncoding.EucKr, "EUC-KR", "한글 폴더 이름이 깨지는 옛 NAS"),
            ),
            form.encoding,
        ) { form.encoding = it }
        null -> Unit
    }
    ui.trustAsk?.let { ask ->
        val sftp = ask.config.protocol == FtpProtocol.Sftp
        val what = if (sftp) "서버 키" else "인증서"
        val title = if (ask.config.trusted != null) "${what}가 바뀌었습니다" else "처음 보는 ${what}입니다"
        val message = (
            if (sftp) "${ask.config.host} 에 처음 잇습니다. SSH 서버는 처음 이을 때 키를 확인해야 합니다."
            else "${ask.config.host} 의 인증서는 공인 기관이 서명하지 않았습니다(대부분 NAS 가 스스로 만든 인증서)."
            ) + " 지문이 서버 설정 화면의 것과 같으면 믿어도 됩니다.\n\n" + (if (sftp) ask.fingerprint else "SHA-256\n${ask.fingerprint}") + "\n\n믿으면 이 서버의 이 ${what}만 기억합니다. 나중에 바뀌면 다시 묻습니다."
        CpPopup(title = title, message = message, onDismiss = { ui.trustAsk = null }) {
            CpPopupButtons {
                CpButton("취소", { ui.trustAsk = null }, primary = false)
                CpButton("이 $what 믿기", ui::trust)
            }
        }
    }
    ui.forgetting?.let { d ->
        CpPopup(title = "‘${d.name}’ 를 지울까요?", message = "이 기기가 서버에 남긴 기록을 지웁니다. 그 기기에서 이어 읽기가 켜져 있으면 다음에 다시 올라옵니다.", onDismiss = { ui.forgetting = null }) {
            CpPopupButtons {
                CpButton("취소", { ui.forgetting = null }, primary = false)
                CpButton("지우기", { ui.forget(d) })
            }
        }
    }
    if (ui.clearing) {
        CpPopup(title = "서버의 기록을 지울까요?", message = "모든 기기가 저장 폴더에 남긴 읽은 자리 · 책갈피 기록을 지우고, 이 기기에서 이어 읽기를 끕니다. 폴더의 다른 파일과 이 기기의 책 · 책갈피는 그대로입니다.", onDismiss = { ui.clearing = false }) {
            CpPopupButtons {
                CpButton("취소", { ui.clearing = false }, primary = false)
                CpButton("지우기", ui::clear)
            }
        }
    }
    if (ui.legacy && !ui.enabled && ui.form == null) {
        CpPopup(title = "이어 읽기 방식이 바뀌었습니다", message = SYNC_LEGACY_MESSAGE, onDismiss = ui::dismissLegacy) {
            CpPopupButtons {
                CpButton("나중에", ui::dismissLegacy, primary = false)
                CpButton("서버 정하기", { ui.dismissLegacy(); ui.turn(true) })
            }
        }
    }
}
