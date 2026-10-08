package io.github.kgcaudit.reader.app

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import io.github.kgcaudit.reader.data.sync.SyncOffer
import io.github.kgcaudit.reader.data.sync.SyncRules
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.pdf.PdfReader
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpSyncPrompt
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.syncPromptTitle
import io.github.kgcaudit.reader.ui.design.syncWhenText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── 앱 정보 "기기 간 이어 읽기"(결정 8-4) ─────────────────────────────

/**
 * 앱 정보 안 "기기 간 이어 읽기" 묶음의 상태. 행([ReadingSyncRows])과 판([ReadingSyncPopups])을 나눠 그린다 — 판을 행 옆에 두면
 * 굴리는 목록 사이에 끼어 화면을 덮지 못한다(읽기 기록 묶음과 같은 까닭).
 */
internal class SyncUi(private val sync: ReadingSync, private val folders: io.github.kgcaudit.reader.data.saf.LibraryFolders, private val scope: CoroutineScope) {
    var enabled by mutableStateOf(sync.enabled)
    var devices by mutableStateOf(sync.devices())

    /** 쓰기 허락을 다시 받아야 하는 폴더(0.50 까지 읽기만 받고 등록). 맨 앞 것을 묻는다. */
    var needWrite by mutableStateOf<List<Uri>>(emptyList())
    var showDevices by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    val deviceName: String get() = sync.deviceName

    fun turn(on: Boolean) {
        if (on == enabled) return
        sync.enabled = on
        enabled = on
        if (!on) {
            needWrite = emptyList()
            return
        }
        needWrite = sync.foldersWithoutWrite()
        refresh()
    }

    /** 이 기기의 기록을 쓰고 다른 기기의 것을 읽는다. 켤 때 · 앱 정보를 열 때 · 폴더를 다시 골랐을 때. */
    fun refresh() {
        if (!enabled) return
        scope.launch {
            runCatching { sync.writeOwn() }
            runCatching { sync.refreshAll() }
            devices = sync.devices()
        }
    }

    /** 다시 고르는 화면에서 돌아왔다. 같은 폴더면 읽기 · 쓰기 허락을 받아 둔다. */
    fun picked(asked: Uri, uri: Uri?) {
        if (uri == null) return
        if (!sameTree(asked, uri)) {
            // 다른 폴더를 등록하지는 않는다 — 이 판은 "이 폴더에 쓰게 해 주세요" 를 묻는 것이다. 엉뚱한 폴더가 서재에 붙으면 놀란다.
            toast = "다른 폴더를 골랐습니다. ‘${folderLabel(asked)}’ 폴더를 골라 주세요"
            return
        }
        runCatching { folders.register(uri) }
        needWrite = needWrite.filter { it != asked && sync.foldersWithoutWrite().contains(it) }
        if (!folders.canWrite(asked)) toast = "이 폴더에는 쓸 수 없습니다. 이 폴더는 건너뜁니다"
        refresh()
    }

    /** 이 폴더는 묻지 않고 건너뛴다(그 폴더의 책은 다른 기기와 맞추지 않는다). */
    fun skip() {
        needWrite = needWrite.drop(1)
    }

    private fun sameTree(a: Uri, b: Uri): Boolean =
        a == b || (a.authority == b.authority && runCatching { DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b) }.getOrDefault(false))
}

/** 폴더 이름("Books"). 문서 id 의 마지막 조각 — 대부분의 제공자에서 폴더 이름이다. */
internal fun folderLabel(uri: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()?.substringAfterLast(':')?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        ?: uri.lastPathSegment ?: uri.toString()

@Composable
internal fun rememberSyncUi(): SyncUi {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val ui = remember { SyncUi(container.sync, container.data.folders, scope) }
    // 앱 정보를 열 때마다 다른 기기의 파일을 다시 읽는다 — "함께 읽는 기기" 의 마지막 때가 맞게.
    LaunchedEffect(ui) { ui.refresh() }
    return ui
}

@Composable
internal fun ReadingSyncRows(ui: SyncUi) {
    val c = CpTheme.colors
    CpSectionLabel("기기 간 이어 읽기")
    CpChoice("책 폴더에 읽은 자리 남기기", listOf("켬", "끔"), if (ui.enabled) 0 else 1, { ui.turn(it == 0) })
    CpText(
        "이 휴대폰 이름: ${ui.deviceName}\n" +
            "책 폴더 안의 ‘.olo’ 폴더에 읽은 자리 · 책갈피를 적습니다. 구글 드라이브 · 원드라이브 · Syncthing 같은 동기화 앱이 그 폴더를 " +
            "다른 기기와 맞춰 주면, 다른 기기에서 읽은 자리로 이어 읽을 수 있습니다. 이 앱은 인터넷을 쓰지 않습니다.",
        CpTheme.type.caption, c.textMuted, Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 6.dp), maxLines = Int.MAX_VALUE,
    )
    if (ui.enabled) {
        val others = ui.devices
        val now = System.currentTimeMillis()
        // 켬 · 끔 줄에 속한 줄이다 — 그 줄의 글자 시작선에서 한 단 들인다(위계 규칙: 글자만 있는 줄의 자식은 levelIndent).
        CpListRow(
            "함께 읽는 기기",
            { ui.showDevices = true },
            Modifier.padding(start = CpTheme.metrics.levelIndent),
            subtitle = others.firstOrNull()?.let { deviceLine(it, now) } ?: "아직 다른 기기의 기록이 없습니다",
            value = others.size.takeIf { it > 0 }?.let { "${it}대" },
        )
    }
}

private fun deviceLine(d: SyncDevice, now: Long) = "${d.name} · ${syncWhenText(d.updatedAtEpochMs, now)}"

@Composable
internal fun ReadingSyncPopups(ui: SyncUi) {
    val asked = ui.needWrite.firstOrNull()
    // 고르는 화면은 묻는 폴더에서 열린다(처음 자리) — 사람이 그 폴더를 다시 찾아 들어가지 않게.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> asked?.let { ui.picked(it, uri) } }
    if (asked != null) {
        CpPopup(
            title = "폴더를 한 번 더 골라 주세요",
            message = "‘${folderLabel(asked)}’ 폴더에 읽은 자리를 적으려면 쓰기 허락이 필요합니다. 고르는 화면에서 이 폴더를 열고 " +
                "‘이 폴더 사용’을 누르세요. 고르지 않으면 이 폴더의 책은 다른 기기와 맞추지 않습니다.",
            onDismiss = ui::skip,
        ) {
            CpPopupButtons {
                CpButton("나중에", ui::skip, primary = false)
                CpButton("폴더 고르기", { pick.launch(asked) })
            }
        }
    }
    if (ui.showDevices) {
        val now = System.currentTimeMillis()
        CpPopup(
            title = "함께 읽는 기기",
            message = ui.devices.joinToString("\n") { deviceLine(it, now) }.ifEmpty { "아직 다른 기기의 기록이 없습니다. 다른 기기에서도 이 설정을 켜고, 동기화 앱이 책 폴더를 맞춘 뒤에 보입니다." },
            onDismiss = { ui.showDevices = false },
        ) { CpPopupButtons { CpButton("확인", { ui.showDevices = false }) } }
    }
}

// ── 리더 위 "더 읽었습니다" 띠(결정 8-2) ─────────────────────────────

/** 띠가 떠 있는 시간. 읽기를 막지 않는 알림이라 오래 두지 않는다 — 놓쳤으면 다시 열 때 또 뜬다. */
private const val PROMPT_MS = 8_000L

private object Unset

/**
 * 띠 공통: [beyond] 일 때 띄우고, 사람이 쪽을 넘기거나([moved] 가 바뀜) [PROMPT_MS] 가 지나면 거둔다. 한 번 거둔 띠는 이 책을 닫을
 * 때까지 다시 뜨지 않는다. 자리를 옮기는 것은 "거기로" 를 누를 때뿐이다.
 */
@Composable
private fun SyncPromptHost(offer: SyncOffer?, beyond: Boolean, detail: String, moved: Any?, onGo: suspend () -> Unit) {
    var done by remember(offer) { mutableStateOf(false) }
    // 띠가 사라져도 "거기로" 의 일은 끝까지 한다 — 띠 안에서 범위를 잡으면 누르는 순간 띠가 거둬지며 그 일도 취소됐다(만화가
    // 그 쪽으로 다시 열리지 않았다).
    val scope = rememberCoroutineScope()
    if (offer == null || !beyond || done) return
    var baseline by remember(offer) { mutableStateOf<Any?>(Unset) }
    LaunchedEffect(offer, moved) {
        // 자리가 처음 생긴 것(막 연 권의 첫 쪽이 적힘)은 넘김이 아니다 — 그것까지 넘김으로 보면 띠가 뜨자마자 사라졌다.
        if (baseline === Unset || baseline == null || baseline == (null to null)) baseline = moved else if (moved != baseline) done = true
    }
    LaunchedEffect(offer) {
        // 사람의 시간으로 센다(화면 그림 시계가 아니라). 그림 시계는 시험이 몰아서 돌려, 띠가 보이기도 전에 시간이 다 갔다.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { delay(PROMPT_MS) }
        done = true
    }
    Box(Modifier.fillMaxSize()) {
        CpSyncPrompt(
            title = syncPromptTitle(offer.deviceName),
            detail = "$detail · ${syncWhenText(offer.updatedAtEpochMs, System.currentTimeMillis())}",
            onGo = {
                done = true
                scope.launch { runCatching { onGo() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 다른 기기의 자리. 꺼져 있으면 아무것도 읽지 않고 null — 띠도 없다. 못 읽어도 null(책은 그대로 읽힌다). */
@Composable
private fun <T : SyncOffer> rememberOffer(key: Any, ask: suspend ReadingSync.() -> T?): T? {
    val sync = LocalContext.current.container.sync
    var offer by remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) {
        offer = try {
            sync.ask()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("OloSync", "no offer for $key", e)
            null
        }
    }
    return offer
}

/** EPUB · TXT 리더 위. 다른 기기의 자리가 지금 보이는 쪽(두쪽이면 오른쪽 쪽)의 끝을 넘었을 때만. */
@Composable
internal fun ReflowSyncPrompt(reader: BookReader) {
    val id = reader.document.meta.id
    val offer = rememberOffer(reader) { offerForBook(id.value) }
    val state by reader.state.collectAsState()
    val page = state.rightPage ?: state.page
    val position = state.position
    val remote = offer?.locator as? Locator.Reflow
    val beyond = remote != null && page != null && position != null && SyncRules.reflowBeyond(remote, position.spineIndex, page.endCharExclusive)
    SyncPromptHost(offer, beyond, "${offer?.percent?.toInt() ?: 0}%", position) {
        if (remote != null) reader.goTo(Bookmark(Bookmark.NO_ID, id, remote, null, 0L))
    }
}

/** PDF 리더 위. 다른 기기의 쪽이 지금 보이는 마지막 쪽보다 뒤일 때만. */
@Composable
internal fun PdfSyncPrompt(reader: PdfReader) {
    val offer = rememberOffer(reader) { offerForBook(reader.book.meta.id.value) }
    val state by reader.state.collectAsState()
    val remote = offer?.locator as? Locator.FixedPage
    val beyond = remote != null && state.ready && SyncRules.pageBeyond(remote.page, state.shown.maxOrNull() ?: state.page)
    SyncPromptHost(offer, beyond, "${(remote?.page ?: 0) + 1}쪽", state.page) {
        if (remote != null) reader.goTo(remote.page)
    }
}

/** 만화 뷰어 위. 자리를 옮기는 것은 부르는 쪽([onGo])이 한다 — 만화는 읽은 자리를 적고 그 권을 다시 연다. */
@Composable
internal fun ComicSyncPrompt(unitId: String, onGo: suspend (SyncOffer.Comic) -> Unit) {
    val data = LocalContext.current.container.data
    val offer = rememberOffer(unitId) { offerForComic(unitId) }
    val progress by remember { data.comics.progress() }.collectAsState(initial = null)
    val local = progress?.get(unitId)
    val beyond = offer != null && progress != null && SyncRules.comicBeyond(offer.page, offer.offset, local?.page ?: 0, local?.offset)
    SyncPromptHost(offer, beyond, "${(offer?.page ?: 0) + 1}쪽", local?.page to local?.offset) { offer?.let { onGo(it) } }
}

/** 만화 뷰어로 연 책(그림책 · 만화로 보기 PDF). 쪽마다 진도가 다르니 진도로 견준다. */
@Composable
internal fun BookComicSyncPrompt(bookId: String, onGo: suspend (SyncOffer.Book) -> Unit) {
    val data = LocalContext.current.container.data
    val offer = rememberOffer(bookId) { offerForBook(bookId) }
    val percents by remember { data.library.percents() }.collectAsState(initial = null)
    val local = percents?.get(BookId(bookId))
    val beyond = offer != null && percents != null && offer.percent > (local ?: 0f) + 0.01f
    SyncPromptHost(offer, beyond, "${offer?.percent?.toInt() ?: 0}%", local) { offer?.let { onGo(it) } }
}
