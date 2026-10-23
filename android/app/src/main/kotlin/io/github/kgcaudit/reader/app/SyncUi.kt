package io.github.kgcaudit.reader.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import io.github.kgcaudit.reader.data.sync.ReadingSync
import io.github.kgcaudit.reader.data.sync.SyncOffer
import io.github.kgcaudit.reader.data.sync.SyncRules
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.Bookmark
import io.github.kgcaudit.reader.document.Locator
import io.github.kgcaudit.reader.pdf.PdfReader
import io.github.kgcaudit.reader.reflow.BookReader
import io.github.kgcaudit.reader.ui.design.CpSyncPrompt
import io.github.kgcaudit.reader.ui.design.syncPromptTitle
import io.github.kgcaudit.reader.ui.design.syncWhenText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
