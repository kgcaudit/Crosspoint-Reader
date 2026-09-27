package io.github.kgcaudit.reader.app

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.Role
import io.github.kgcaudit.reader.ui.design.CpCover
import io.github.kgcaudit.reader.ui.design.CpToast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.kgcaudit.reader.data.library.LibraryBook
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.ui.design.CpBarWeight
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpDivider
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpProgressBar
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpTile
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 첫 화면. 최근 책과 모든 책.
 *
 * 책을 가져오는 방법은 폴더 등록 하나뿐이다. 파일을 한 권씩 고르게 하면 권한 개수 제한
 * (128/512)에 걸리고, 폴더에 책을 넣는 것만으로 목록에 나타나는 편이 쓰기 쉽다.
 */
@Composable
fun LibraryScreen(
    onOpen: (LibraryBook) -> Unit,
    /** 이번 실행에서 아직 훑지 않았다. 책을 닫고 돌아올 때마다 훑지 않게 부르는 쪽이 기억한다. */
    scanOnStart: Boolean = true,
    onStartScan: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.container
    val data = container.data
    val scope = rememberCoroutineScope()
    val colors = CpTheme.colors

    // Flow 를 remember 한다. 부를 때마다 새 Flow 라서, 그대로 두면 다시 그릴 때마다(알림·스캔 표시)
    // 세 질의를 끊고 다시 건다.
    val books by remember { data.library.books() }.collectAsState(initial = null)
    val recent by remember { data.library.recent(limit = RECENT_SHOWN) }.collectAsState(initial = emptyList())
    val percents by remember { data.library.percents() }.collectAsState(initial = emptyMap())
    var folders by remember { mutableStateOf(data.folders.folders()) }
    var scanning by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var manageFolders by remember { mutableStateOf(false) }
    // 길게 눌러 표지를 바꾸려는 책. 사진 고르기에서 돌아올 때까지 기억한다.
    var coverMenu by remember { mutableStateOf<LibraryBook?>(null) }
    var coverFor by remember { mutableStateOf<LibraryBook?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val book = coverFor ?: return@rememberLauncherForActivityResult
        coverFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = container.covers.setCustom(book.id) { context.contentResolver.openInputStream(uri) }
            if (ok) {
                toast = "‘${book.label}’ 표지를 바꿨습니다"
            } else {
                notice = "이 그림을 표지로 쓸 수 없습니다" to "그림 파일(jpg · png 등)을 골라 주세요. 파일이 깨졌을 수도 있습니다."
            }
        }
    }

    fun rescan() {
        // 훑는 중에 새로고침을 또 누르면 두 스캔이 같은 표를 고치고, 먼저 끝난 쪽이 표시를 꺼 버린다.
        if (scanning) return
        scanning = true
        scope.launch {
            val results = try {
                data.rescanAll()
            } catch (e: kotlinx.coroutines.CancellationException) {
                scanning = false
                throw e
            } catch (e: Exception) {
                android.util.Log.w("OloLibrary", "rescan failed", e)
                null
            }
            scanning = false
            if (results == null || results.values.any { !it.complete }) {
            notice = "일부 폴더를 읽지 못했습니다" to
                "그 폴더의 책은 목록에 그대로 둡니다. 저장소가 연결돼 있는지 확인한 뒤 새로고침하세요."
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { data.folders.register(uri) }
                .onFailure { notice = "이 폴더를 등록하지 못했습니다" to it.message }
            folders = data.folders.folders()
            rescan()
        }
    }

    // 앱을 열 때마다 한 번 훑는다. 폴더에 새로 넣은 책이 바로 보여야 한다. 책을 닫고 돌아올 때는 훑지
    // 않는다 — 이 화면은 책을 여는 동안 사라졌다 다시 생기므로, 여기서 기억하면 돌아올 때마다 훑는다.
    LaunchedEffect(Unit) {
        if (scanOnStart && folders.isNotEmpty()) {
            onStartScan()
            rescan()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val list = books
        CpHeader(
            title = "OLO eBook",
            subtitle = when {
                folders.isEmpty() -> "책이 있는 폴더를 추가하세요"
                list == null -> null
                else -> "책 ${list.size}권 · 폴더 ${folders.size}개"
            },
        ) {
            // 폴더에 관한 일(추가·빼기)은 폴더 단추 하나로 모은다. 예전에는 ＋(추가)와
            // 폴더(관리 — 그 안에 다시 추가)가 따로 있어 같은 일로 가는 길이 둘이었다.
            // 폴더가 없을 때는 화면 가운데의 "폴더 추가" 가 그 자리를 대신한다.
            if (folders.isNotEmpty()) {
                CpIconButton(CpIcons.Refresh, "새로고침", { rescan() })
                CpIconButton(CpIcons.Folder, "책 폴더", { manageFolders = true })
            }
            // 앱 정보는 책이 없어도 닿아야 한다 — 판 번호를 묻는 일은 무언가 안 될 때 생긴다.
            CpIconButton(CpIcons.Info, "앱 정보", onAbout)
        }
        if (scanning) CpProgressBar(0.35f, Modifier.padding(horizontal = 16.dp), CpBarWeight.Thin)

        if (folders.isEmpty()) {
            EmptyLibrary { pickFolder.launch(null) }
        } else if (list != null && list.isEmpty() && !scanning) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                CpText("등록한 폴더에 EPUB·TXT 파일이 없습니다", CpTheme.type.subtitle, colors.textMuted, maxLines = 2)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (recent.isNotEmpty()) {
                    item { CpSectionLabel("최근에 읽은 책") }
                    item(key = "shelf") {
                        Shelf(recent, percents, onOpen = onOpen, onLongClick = { coverMenu = it })
                    }
                    item { Spacer(Modifier.height(14.dp)); CpDivider() }
                }
                item { CpSectionLabel("모든 책") }
                items(list.orEmpty(), key = { it.id.value }) { book ->
                    BookRow(book, percents[book.id], onOpen = onOpen, onLongClick = { coverMenu = it })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (manageFolders) {
        CpPopup(title = "책 폴더", message = "폴더를 빼도 그 책들의 진도와 책갈피는 남습니다. 다시 추가하면 이어집니다.", onDismiss = { manageFolders = false }) {
            Spacer(Modifier.height(8.dp))
            CpListRow(
                title = "폴더 추가",
                icon = CpIcons.Plus,
                onClick = { manageFolders = false; pickFolder.launch(null) },
                compact = true,
            )
            folders.forEach { uri ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpTile(CpIcons.Folder, colors.tiles.folder)
                    Spacer(Modifier.width(12.dp))
                    CpText(folderName(uri), CpTheme.type.body, colors.text, Modifier.weight(1f))
                    CpIconButton(CpIcons.Close, "${folderName(uri)} 빼기", {
                        scope.launch {
                            data.removeFolder(uri)
                            folders = data.folders.folders()
                            if (folders.isEmpty()) manageFolders = false
                        }
                    }, tint = colors.textMuted)
                }
            }
        }
    }

    coverMenu?.let { book ->
        CoverMenu(
            book,
            onPick = {
                coverMenu = null
                coverFor = book
                pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRevert = {
                coverMenu = null
                scope.launch {
                    container.covers.clearCustom(book.id)
                    toast = "‘${book.label}’ 표지를 되돌렸습니다"
                }
            },
            onDismiss = { coverMenu = null },
        )
    }

    Box(Modifier.fillMaxSize()) {
        CpToast(toast, { toast = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
    }

    notice?.let { (title, message) ->
        CpPopup(title = title, message = message, onDismiss = { notice = null }) {
            Spacer(Modifier.height(16.dp))
            CpButton("확인", { notice = null })
        }
    }
}

/** 책 한 권의 표지. 메모리에 있으면 바로, 없으면 꺼내는 동안 대신 표지를 보인다. */
@Composable
private fun rememberCover(book: LibraryBook): Cover? {
    val covers = LocalContext.current.container.covers
    val version by covers.version.collectAsState()
    var cover by remember(book.id) { mutableStateOf(covers.cached(book.id)) }
    LaunchedEffect(book.id, version) { cover = covers.cover(book) }
    return cover
}

@Composable
private fun BookCover(book: LibraryBook, modifier: Modifier = Modifier, small: Boolean = false) {
    val tiles = CpTheme.colors.tiles
    CpCover(
        image = rememberCover(book)?.image,
        title = book.label,
        subtitle = book.author ?: book.format.name,
        fallback = when (book.format) {
            BookFormat.EPUB -> tiles.book
            BookFormat.TXT, BookFormat.PDF -> tiles.document
        },
        icon = when (book.format) {
            BookFormat.EPUB -> CpIcons.Book
            BookFormat.TXT -> CpIcons.Text
            BookFormat.PDF -> CpIcons.Pdf
        },
        modifier = modifier,
        small = small,
    )
}

/**
 * 최근에 읽은 책: 큰 표지 · 제목 · 진도 막대. 늘 세 칸으로 나눈다 — 두 권뿐이어도 표지가 커지지 않아야 줄마다 크기가
 * 같다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Shelf(
    books: List<LibraryBook>,
    percents: Map<BookId, Float>,
    onOpen: (LibraryBook) -> Unit,
    onLongClick: (LibraryBook) -> Unit,
) {
    val c = CpTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        books.take(RECENT_SHOWN).forEach { book ->
            val percent = percents[book.id] ?: 0f
            Column(
                Modifier.weight(1f)
                    .combinedClickable(role = Role.Button, onLongClick = { onLongClick(book) }, onClick = { onOpen(book) }),
            ) {
                BookCover(book, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                CpText(book.label, CpTheme.type.label, c.text)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CpProgressBar(percent / 100f, Modifier.weight(1f), CpBarWeight.Thin)
                    Spacer(Modifier.width(6.dp))
                    CpText("${percent.roundToInt()}%", CpTheme.type.caption, c.textMuted)
                }
            }
        }
        repeat(RECENT_SHOWN - books.size.coerceAtMost(RECENT_SHOWN)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun BookRow(
    book: LibraryBook,
    percent: Float?,
    onOpen: (LibraryBook) -> Unit,
    onLongClick: (LibraryBook) -> Unit,
) {
    CpListRow(
        title = book.label,
        subtitle = book.author ?: book.format.name,
        leading = { BookCover(book, Modifier.width(32.dp), small = true) },
        value = percent?.let { "${it.roundToInt()}%" },
        onClick = { onOpen(book) },
        onLongClick = { onLongClick(book) },
    )
}

/** 길게 누른 책의 표지 판(구상안 확정: 사진 · 파일에서 고르기, 되돌리기). */
@Composable
private fun CoverMenu(book: LibraryBook, onPick: () -> Unit, onRevert: () -> Unit, onDismiss: () -> Unit) {
    val cover = rememberCover(book)
    val custom = cover?.custom == true
    val message = when {
        custom -> "직접 고른 표지를 쓰고 있습니다."
        cover?.hasOwn == true && book.format == BookFormat.PDF -> "지금 표지는 파일 첫 쪽입니다."
        cover?.hasOwn == true -> "지금 표지는 책에 든 표지 그림입니다."
        else -> "이 책에는 표지 그림이 없어 대신 표지를 보여 줍니다."
    }
    CpPopup(title = book.label, message = message, onDismiss = onDismiss) {
        Spacer(Modifier.height(8.dp))
        CpListRow("사진 · 파일에서 표지 고르기", onPick, icon = CpIcons.Folder, compact = true)
        CpListRow(
            if (cover?.hasOwn == true) "원래 표지로 되돌리기" else "대신 표지로 되돌리기",
            // 고른 표지가 없으면 되돌릴 것이 없다. 눌러도 판만 닫는다.
            { if (custom) onRevert() else onDismiss() },
            icon = CpIcons.Refresh,
            compact = true,
            enabled = custom,
        )
        Spacer(Modifier.height(12.dp))
        CpButton("닫기", onDismiss, primary = false)
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        CpText("아직 책이 없습니다", CpTheme.type.title, CpTheme.colors.text)
        Spacer(Modifier.height(8.dp))
        CpText(
            "EPUB·TXT 파일이 든 폴더를 고르면\n그 아래의 책을 모두 찾아 보여 줍니다.",
            CpTheme.type.subtitle,
            CpTheme.colors.textMuted,
            maxLines = 3,
            align = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        CpButton("폴더 추가", onAdd)
    }
}

/** `primary:Books/소설` → `소설`. 사람이 알아볼 이름만 남긴다. */
private fun folderName(uri: Uri): String {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
    val path = id.substringAfter(':', id)
    return path.substringAfterLast('/').ifBlank { if (id.startsWith("primary")) "내장 저장소" else id }
}

/** 라이브러리 위쪽 "최근에 읽은 책" 의 줄 수. 한 화면에 목록과 함께 보이는 만큼. */
private const val RECENT_SHOWN = 3
