package io.github.kgcaudit.reader.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.cpTwoPane
import io.github.kgcaudit.reader.ui.design.readLockBlocks
import io.github.kgcaudit.reader.ui.design.syncWhenText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ── 다른 기기에서 이어 읽기 화면(0.57.0, 사용자 확정 구상안 drive/1~4) ──────────────────

internal const val SYNC_TITLE = "다른 기기에서 이어 읽기"
internal const val SYNC_SWITCH = "구글 드라이브로 맞추기"
internal const val SYNC_EXPLAIN = "읽은 자리 · 책갈피 · 다 읽은 날만 내 구글 드라이브의 앱 전용 공간에 저장합니다. 책 파일은 올리지 않습니다. " +
    "이 공간은 드라이브 목록에 보이지 않고 다른 앱이 읽을 수 없습니다."
internal const val SYNC_CONNECT_TITLE = "구글 계정으로 연결합니다"
internal const val SYNC_CONNECT_MESSAGE = "다음 화면에서 계정을 고르고 ‘허용’을 누르세요. OLO eBook 은 드라이브의 앱 전용 공간만 쓰며, " +
    "내 드라이브의 다른 파일은 보거나 바꾸지 못합니다."
internal const val SYNC_LEGACY_MESSAGE = "다른 기기에서 이어 읽기가 책 폴더(.olo) 대신 구글 드라이브로 맞추도록 바뀌었습니다. 계속 쓰려면 다시 켜 " +
    "주세요. 책 폴더에 남은 .olo 폴더는 더 쓰지 않으니 지워도 됩니다."

/** "마지막으로 맞춘 때" 줄의 부제. 맞추지 못했으면 까닭과 할 일. */
internal fun syncStatusLine(status: SyncStatus, now: Long): String {
    val last = status.lastSyncedAtEpochMs
    return when {
    status.busy -> "맞추는 중…"
    status.problem == SyncProblem.Offline -> "인터넷에 연결되지 않아 맞추지 못했습니다. 연결되면 다시 맞춥니다."
    status.problem == SyncProblem.SignedOut -> "구글 드라이브 허락이 취소되었습니다. 다시 연결해야 다른 기기와 맞춥니다."
    status.problem == SyncProblem.Full -> "드라이브 저장 공간이 가득 찼습니다. 기록은 몇 KB 라 공간을 비우면 바로 맞춥니다."
    status.problem == SyncProblem.Server -> "구글 드라이브가 잠시 답하지 않습니다. 조금 뒤 다시 맞춥니다."
    last != null -> syncWhenText(last, now)
    else -> "아직 맞춘 적이 없습니다"
    }
}

/** 화면 상태. 계정 잇기 · 맞추기 · 지우기의 흐름을 여기 모은다 — 화면은 그리기만. */
internal class SyncScreenUi(private val sync: ReadingSync, private val account: DriveAccount, private val scope: CoroutineScope) {
    var enabled by mutableStateOf(sync.enabled && account.connected)
    var devices by mutableStateOf(sync.devices())
    var asking by mutableStateOf(false)
    var forgetting by mutableStateOf<SyncDevice?>(null)
    var clearing by mutableStateOf(false)
    var legacy by mutableStateOf(sync.legacyWasOn)
    var toast by mutableStateOf<String?>(null)

    /** 계정 고르기 화면을 띄울 것. 화면이 런처로 띄우고 결과를 [connected] 로 넘긴다. */
    var launch by mutableStateOf<android.content.IntentSender?>(null)

    val deviceName: String get() = sync.deviceName
    fun titles(device: SyncDevice): Int = sync.titlesOf(device.id)

    fun turn(on: Boolean) {
        if (on == enabled) return
        if (on) {
            // 이미 이어 둔 계정이면 묻지 않고 켠다(껐다 다시 켤 때).
            if (account.connected) start() else asking = true
            return
        }
        sync.enabled = false
        enabled = false
    }

    /** 안내 판의 "계정 고르기". */
    fun connect() {
        asking = false
        scope.launch { step(account.connect()) }
    }

    /** 계정 고르기 화면에서 돌아왔다. */
    fun connected(data: android.content.Intent?) {
        launch = null
        scope.launch { step(account.finish(data)) }
    }

    /** 다른 계정으로: 잊고 다시 고른다. */
    fun changeAccount() {
        scope.launch {
            account.disconnect()
            step(account.connect())
        }
    }

    private fun step(step: ConnectStep) {
        when (step) {
            is ConnectStep.Done -> start()
            is ConnectStep.NeedsUser -> launch = step.sender
            is ConnectStep.Failed -> {
                toast = step.message
                if (!account.connected) {
                    sync.enabled = false
                    enabled = false
                }
            }
        }
    }

    private fun start() {
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
                toast = "드라이브의 기록을 지우고 이 기기에서 껐습니다. 다른 기기에서도 끄지 않으면 그 기기의 기록이 다시 올라옵니다"
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
    val ui = remember { SyncScreenUi(container.sync, container.driveAccount, scope) }
    // 화면을 열 때마다 맞춘다 — "함께 읽는 기기" 의 마지막 때가 맞게.
    LaunchedEffect(ui) { ui.refresh() }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { ui.connected(it.data) }
    val context = LocalContext.current
    LaunchedEffect(ui.launch) {
        val sender = ui.launch ?: return@LaunchedEffect
        // 읽기 잠금 중에는 다른 화면(계정 고르기)을 띄우지 않는다.
        if (context.readLockBlocks()) ui.launch = null else chooser.launch(IntentSenderRequest.Builder(sender).build())
    }
    val c = CpTheme.colors
    Box(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            CpHeader(SYNC_TITLE, onBack = onBack)
            if (cpTwoPane()) {
                // 넓음: 왼쪽 판은 켬 · 계정 · 맞춘 때, 오른쪽 판은 함께 읽는 기기 · 맞추는 것(반응형 규칙).
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { DrivePart(ui) }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { DevicesPart(ui) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DrivePart(ui)
                    DevicesPart(ui)
                }
            }
        }
        SyncPopups(ui)
        CpToast(ui.toast, { ui.toast = null }, Modifier.align(Alignment.BottomCenter), durationMs = 3_000)
    }
}

/** 구글 드라이브 묶음: 켬 · 끔, 켬에 속한 계정 · 맞춘 때(한 단 들임 — 위계 규칙). */
@Composable
private fun DrivePart(ui: SyncScreenUi) {
    val c = CpTheme.colors
    val status by LocalContext.current.container.sync.status.collectAsState()
    val email by LocalContext.current.container.driveAccount.email.collectAsState()
    CpSectionLabel("구글 드라이브")
    CpChoice(SYNC_SWITCH, listOf("켬", "끔"), if (ui.enabled) 0 else 1, { ui.turn(it == 0) })
    if (!ui.enabled) {
        Note(SYNC_EXPLAIN)
        Note("이 휴대폰 이름: ${ui.deviceName}")
        return
    }
    val child = Modifier.padding(start = CpTheme.metrics.levelIndent)
    CpListRow("계정", ui::changeAccount, child, subtitle = email ?: "구글 계정", value = "바꾸기")
    if (status.problem == SyncProblem.SignedOut) {
        CpListRow("구글 드라이브 허락이 취소되었습니다", ui::changeAccount, child, subtitle = "다시 연결해야 다른 기기와 맞춥니다.", value = "다시 연결")
    } else {
        Row(child.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CpText("마지막으로 맞춘 때", CpTheme.type.body, c.text)
                CpText(syncStatusLine(status, System.currentTimeMillis()), CpTheme.type.subtitle, c.textMuted, maxLines = 3)
            }
            Spacer(Modifier.width(12.dp))
            CpButton("지금 맞추기", ui::refresh, primary = false)
        }
    }
    Note("이 휴대폰 이름: ${ui.deviceName}", indent = true)
}

@Composable
private fun DevicesPart(ui: SyncScreenUi) {
    if (!ui.enabled) return
    val now = System.currentTimeMillis()
    CpSectionLabel("함께 읽는 기기")
    if (ui.devices.isEmpty()) {
        Note("아직 다른 기기의 기록이 없습니다. 다른 기기에서도 같은 구글 계정으로 이 설정을 켜면 여기에 보입니다.")
    } else {
        ui.devices.forEach { d -> CpListRow(d.name, { ui.forgetting = d }, subtitle = "${syncWhenText(d.updatedAtEpochMs, now)} · 책 ${ui.titles(d)}권") }
        Note("오래 쓰지 않은 기기는 눌러서 지울 수 있습니다.")
    }
    CpSectionLabel("맞추는 것")
    Note("읽은 자리 · 책갈피 · 다 읽은 날. 책은 파일 이름과 크기로 알아봅니다 — 기기마다 같은 책 파일을 두어야 이어 읽을 수 있습니다.")
    Spacer(Modifier.height(8.dp))
    CpTextButton("드라이브에 저장한 기록 지우기", { ui.clearing = true }, Modifier.padding(CpTheme.metrics.gutter))
}

@Composable
private fun Note(text: String, indent: Boolean = false) = CpText(
    text, CpTheme.type.caption, CpTheme.colors.textMuted,
    Modifier.padding(start = CpTheme.metrics.gutter + if (indent) CpTheme.metrics.levelIndent else 0.dp, end = CpTheme.metrics.gutter, top = 4.dp, bottom = 8.dp),
    maxLines = Int.MAX_VALUE,
)

@Composable
private fun SyncPopups(ui: SyncScreenUi) {
    if (ui.asking) {
        CpPopup(title = SYNC_CONNECT_TITLE, message = SYNC_CONNECT_MESSAGE, onDismiss = { ui.asking = false }) {
            CpPopupButtons {
                CpButton("취소", { ui.asking = false }, primary = false)
                CpButton("계정 고르기", ui::connect)
            }
        }
    }
    ui.forgetting?.let { d ->
        CpPopup(title = "‘${d.name}’ 를 지울까요?", message = "이 기기가 드라이브에 남긴 기록을 지웁니다. 그 기기에서 이어 읽기가 켜져 있으면 다음에 다시 올라옵니다.", onDismiss = { ui.forgetting = null }) {
            CpPopupButtons {
                CpButton("취소", { ui.forgetting = null }, primary = false)
                CpButton("지우기", { ui.forget(d) })
            }
        }
    }
    if (ui.clearing) {
        CpPopup(title = "드라이브의 기록을 지울까요?", message = "모든 기기가 드라이브에 남긴 읽은 자리 · 책갈피 기록을 지우고, 이 기기에서 이어 읽기를 끕니다. 이 기기의 책 · 책갈피는 그대로입니다.", onDismiss = { ui.clearing = false }) {
            CpPopupButtons {
                CpButton("취소", { ui.clearing = false }, primary = false)
                CpButton("지우기", ui::clear)
            }
        }
    }
    if (ui.legacy && !ui.enabled && !ui.asking) {
        CpPopup(title = "이어 읽기 방식이 바뀌었습니다", message = SYNC_LEGACY_MESSAGE, onDismiss = ui::dismissLegacy) {
            CpPopupButtons {
                CpButton("나중에", ui::dismissLegacy, primary = false)
                CpButton("지금 켜기", { ui.dismissLegacy(); ui.turn(true) })
            }
        }
    }
}
