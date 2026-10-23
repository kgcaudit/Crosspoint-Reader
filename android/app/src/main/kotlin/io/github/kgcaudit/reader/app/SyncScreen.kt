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
import io.github.kgcaudit.reader.data.sync.FtpConfig
import io.github.kgcaudit.reader.data.sync.FtpSyncStore
import io.github.kgcaudit.reader.data.sync.ReadingSync
import io.github.kgcaudit.reader.data.sync.SyncDevice
import io.github.kgcaudit.reader.data.sync.SyncProblem
import io.github.kgcaudit.reader.data.sync.SyncStatus
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
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

// ── 다른 기기에서 이어 읽기 화면(0.58.0, FTP 서버 — 사용자 결정으로 구글 드라이브를 대신한다) ──────────────────

internal const val SYNC_TITLE = "다른 기기에서 이어 읽기"
internal const val SYNC_SWITCH = "FTP 서버로 맞추기"
internal const val SYNC_EXPLAIN = "읽은 자리 · 책갈피 · 다 읽은 날만 내가 정한 FTP 서버 폴더에 기기마다 작은 파일(몇 KB)로 저장합니다. " +
    "책 파일은 올리지 않습니다. 집의 NAS · 공유기 · 웹 호스팅의 FTP 를 쓸 수 있습니다."
internal const val SYNC_PLAIN_WARNING = "일반 FTP 는 비밀번호와 기록이 암호화되지 않고 오갑니다. 집 밖의 서버라면 FTPS 를 고르세요."
internal const val SYNC_LEGACY_MESSAGE = "다른 기기에서 이어 읽기가 책 폴더(.olo) 대신 FTP 서버로 맞추도록 바뀌었습니다. 계속 쓰려면 서버를 " +
    "정하고 다시 켜 주세요. 책 폴더에 남은 .olo 폴더는 더 쓰지 않으니 지워도 됩니다."

internal const val FTP_HOST = "서버 주소"
internal const val FTP_PORT = "포트"
internal const val FTP_USER = "사용자"
internal const val FTP_PASSWORD = "비밀번호"
internal const val FTP_PATH = "서버 안 폴더"
internal const val FTP_CHECK = "연결 확인"

/** 맞추지 못한 까닭과 할 일. "마지막으로 맞춘 때" 줄과 연결 확인의 결과가 같은 말을 쓴다. */
internal fun syncProblemText(problem: SyncProblem): String = when (problem) {
    SyncProblem.Offline -> "인터넷에 연결되지 않아 맞추지 못했습니다. 연결되면 다시 맞춥니다."
    SyncProblem.Unreachable -> "서버를 찾지 못했습니다. 주소 · 포트가 맞는지, 서버가 켜져 있는지 확인하세요."
    SyncProblem.Auth -> "사용자 이름이나 비밀번호가 맞지 않습니다."
    SyncProblem.NoAccess -> "서버 폴더에 쓸 수 없습니다. 경로와 그 사용자의 쓰기 권한을 확인하세요."
    SyncProblem.Full -> "서버 저장 공간이 가득 찼습니다. 기록은 몇 KB 라 공간을 비우면 바로 맞춥니다."
    SyncProblem.Tls -> "보안 연결(FTPS)을 맺지 못했습니다. 서버가 FTPS 를 받는지, 인증서가 올바른지 확인하세요."
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

/** 서버 설정 칸들. 글자 그대로 들고 있다가 확인할 때 [FtpConfig] 로 바꾼다 — 포트 칸을 비워 둔 채 다른 칸으로 넘어갈 수 있게. */
internal class FtpForm(config: FtpConfig?) {
    var host by mutableStateOf(config?.host.orEmpty())
    var port by mutableStateOf((config?.port ?: FtpConfig.DEFAULT_PORT).toString())
    var secure by mutableStateOf(config?.secure ?: false)
    var user by mutableStateOf(config?.user.orEmpty())
    var password by mutableStateOf(config?.password.orEmpty())
    var path by mutableStateOf(config?.path ?: FtpSettings.DEFAULT_PATH)

    /** 칸이 모자라면 무엇이 모자란지. 다 찼으면 null. */
    fun missing(): String? = when {
        host.isBlank() -> "서버 주소를 넣어 주세요."
        port.trim().toIntOrNull()?.takeIf { it in 1..65535 } == null -> "포트는 1 ~ 65535 의 숫자입니다(보통 21)."
        user.isBlank() -> "사용자 이름을 넣어 주세요."
        else -> null
    }

    fun toConfig(): FtpConfig = FtpConfig(
        // "ftp://nas.local/" 처럼 주소창에서 복사해 넣는 사람이 많다. 앞뒤를 떼지 않으면 "서버를 찾지 못했습니다" 만 보고 헤맨다.
        host = host.trim().removePrefix("ftps://").removePrefix("ftp://").trimEnd('/'),
        port = port.trim().toInt(),
        secure = secure,
        user = user.trim(),
        password = password,
        path = path.trim().ifEmpty { "/" },
    )
}

/** 화면 상태. 서버 정하기 · 확인 · 맞추기 · 지우기의 흐름을 여기 모은다 — 화면은 그리기만. */
internal class SyncScreenUi(private val sync: ReadingSync, private val settings: FtpSettings, private val scope: CoroutineScope) {
    var enabled by mutableStateOf(sync.enabled && settings.config() != null)
    var server by mutableStateOf(settings.config())
    var devices by mutableStateOf(sync.devices())

    /** 서버 설정 칸을 펼쳤으면 그 칸들. */
    var form by mutableStateOf<FtpForm?>(null)
    var checking by mutableStateOf(false)
    var formError by mutableStateOf<String?>(null)
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
        form = null
        sync.enabled = false
        enabled = false
    }

    fun edit() {
        formError = null
        form = FtpForm(server)
    }

    fun cancelEdit() {
        form = null
        formError = null
    }

    /**
     * "연결 확인": 잇고 · 로그인하고 · 폴더를 만들고 · 시험 파일을 써 본다. 되면 저장하고 켠다. 안 되면 칸을 그대로 두고 까닭을 보인다 —
     * 저장된 설정은 건드리지 않는다(확인은 따로 만든 저장소로).
     */
    fun check() {
        val f = form ?: return
        f.missing()?.let { formError = it; return }
        val candidate = f.toConfig()
        checking = true
        formError = null
        scope.launch {
            val problem = sync.checkConnection(FtpSyncStore({ candidate }))
            checking = false
            if (problem != null) {
                formError = syncProblemText(problem)
                return@launch
            }
            settings.save(candidate)
            server = settings.config()
            form = null
            toast = "연결했습니다"
            start()
        }
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
    val ui = remember { SyncScreenUi(container.sync, container.ftpSettings, scope) }
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
        SyncPopups(ui)
        CpToast(ui.toast, { ui.toast = null }, Modifier.align(Alignment.BottomCenter), durationMs = 3_000)
    }
}

/** FTP 서버 묶음: 켬 · 끔, 켬에 속한 서버 · 맞춘 때 · 설정 칸(한 단 들임 — 위계 규칙). */
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
    CpListRow("서버", ui::edit, child, subtitle = server?.let { it.label + if (it.secure) " · FTPS" else "" } ?: "", value = "바꾸기")
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

/** 서버 설정 칸. 켬에 속하므로 한 단 들인다. */
@Composable
private fun FormPart(ui: SyncScreenUi, form: FtpForm, child: Modifier) {
    val c = CpTheme.colors
    CpTextField(FTP_HOST, form.host, { form.host = it }, child, placeholder = "nas.local 또는 ftp.example.com", keyboard = KeyboardType.Uri)
    CpTextField(FTP_PORT, form.port, { form.port = it.filter(Char::isDigit).take(5) }, child, keyboard = KeyboardType.Number)
    CpChoice("보안 연결", listOf("FTP", "FTPS"), if (form.secure) 1 else 0, { form.secure = it == 1 }, child)
    if (!form.secure) Note(SYNC_PLAIN_WARNING, indent = true)
    CpTextField(FTP_USER, form.user, { form.user = it }, child)
    CpTextField(FTP_PASSWORD, form.password, { form.password = it }, child, secret = true)
    CpTextField(FTP_PATH, form.path, { form.path = it }, child, placeholder = FtpSettings.DEFAULT_PATH)
    Note("없는 폴더는 만듭니다. 함께 읽을 기기마다 같은 서버 · 같은 폴더를 정하세요.", indent = true)
    ui.formError?.let { Note(it, indent = true, color = c.error) }
    Row(child.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp)) {
        Spacer(Modifier.weight(1f))
        CpButton("취소", ui::cancelEdit, primary = false)
        Spacer(Modifier.width(8.dp))
        CpButton(if (ui.checking) "확인 중…" else FTP_CHECK, { if (!ui.checking) ui.check() })
    }
}

@Composable
private fun DevicesPart(ui: SyncScreenUi) {
    if (!ui.enabled || ui.form != null) return
    val now = System.currentTimeMillis()
    CpSectionLabel("함께 읽는 기기")
    if (ui.devices.isEmpty()) {
        Note("아직 다른 기기의 기록이 없습니다. 다른 기기에서도 같은 서버 · 같은 폴더로 이 설정을 켜면 여기에 보입니다.")
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

@Composable
private fun SyncPopups(ui: SyncScreenUi) {
    ui.forgetting?.let { d ->
        CpPopup(title = "‘${d.name}’ 를 지울까요?", message = "이 기기가 서버에 남긴 기록을 지웁니다. 그 기기에서 이어 읽기가 켜져 있으면 다음에 다시 올라옵니다.", onDismiss = { ui.forgetting = null }) {
            CpPopupButtons {
                CpButton("취소", { ui.forgetting = null }, primary = false)
                CpButton("지우기", { ui.forget(d) })
            }
        }
    }
    if (ui.clearing) {
        CpPopup(title = "서버의 기록을 지울까요?", message = "모든 기기가 서버 폴더에 남긴 읽은 자리 · 책갈피 기록을 지우고, 이 기기에서 이어 읽기를 끕니다. 폴더의 다른 파일과 이 기기의 책 · 책갈피는 그대로입니다.", onDismiss = { ui.clearing = false }) {
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
