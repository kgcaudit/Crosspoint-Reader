package io.github.kgcaudit.reader.app

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.data.library.LibraryBook
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import io.github.kgcaudit.reader.ui.design.CpRadioRow
import io.github.kgcaudit.reader.ui.design.CpIconToggle
import io.github.kgcaudit.reader.data.library.NoteCounts
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
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val shelf by remember { data.library.shelf() }.collectAsState(initial = emptyList())
    val reading = shelf.filter { it.finishedAtEpochMs == null }.map { it.book }
    // 다 읽은 책은 끝낸 차례(최근에 끝낸 책이 앞).
    val finished = shelf.filter { it.finishedAtEpochMs != null }.sortedByDescending { it.finishedAtEpochMs }
    val finishedIds = finished.mapTo(HashSet()) { it.book.id }
    val shelfIds = shelf.mapTo(HashSet()) { it.book.id }
    val percents by remember { data.library.percents() }.collectAsState(initial = emptyMap())
    val notes by remember { data.library.noteCounts() }.collectAsState(initial = emptyMap())
    val layout by container.libraryView.layout.collectAsState()
    val sort by container.libraryView.sort.collectAsState()
    var sortMenu by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(data.folders.folders()) }
    val scanning by container.scan.running.collectAsState()
    // 일부 폴더를 못 읽은 훑기가 새로 생기면 한 번 알린다. 화면이 새로 생길 때 이미 있던 것은 다시 알리지 않는다.
    val incomplete by container.scan.incomplete.collectAsState()
    var seenIncomplete by remember { mutableIntStateOf(container.scan.incomplete.value) }
    var notice by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var manageFolders by remember { mutableStateOf(false) }
    // 길게 눌러 표지를 바꾸려는 책. 사진 고르기에서 돌아올 때까지 기억한다.
    var coverMenu by remember { mutableStateOf<LibraryBook?>(null) }
    // id 로 저장한다(rememberSaveable) — 사진을 고르는 사이 앱이 회수됐다 돌아와도 어느 책의 표지인지 안다.
    var coverForId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = coverForId ?: return@rememberLauncherForActivityResult
        coverForId = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = container.covers.setCustom(BookId(id)) { context.contentResolver.openInputStream(uri) }
            if (ok) {
                val label = data.library.get(BookId(id))?.label
                toast = if (label != null) "‘$label’ 표지를 바꿨습니다" else "표지를 바꿨습니다"
            } else {
                notice = "이 그림을 표지로 쓸 수 없습니다" to "그림 파일(jpg · png 등)을 골라 주세요. 파일이 깨졌을 수도 있습니다."
            }
        }
    }

    fun rescan() = container.scan.request()

    LaunchedEffect(incomplete) {
        if (incomplete > seenIncomplete) {
            seenIncomplete = incomplete
            notice = "일부 폴더를 읽지 못했습니다" to
                "그 폴더의 책은 목록에 그대로 둡니다. 저장소가 연결돼 있는지 확인한 뒤 새로고침하세요."
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
                CpText("등록한 폴더에 EPUB · TXT · PDF 파일이 없습니다.", CpTheme.type.subtitle, colors.textMuted, maxLines = 2)
            }
        } else {
            val columns = shelfColumns()
            LazyColumn(Modifier.fillMaxSize()) {
                if (reading.isNotEmpty()) {
                    item { ShelfLabel("읽는 중 · ${reading.size}권", more = reading.size > columns) }
                    item(key = "reading") {
                        ShelfRow(reading) { book, width -> ShelfItem(book, width, percents[book.id], onOpen, onLongClick = { coverMenu = it }) }
                    }
                }
                if (finished.isNotEmpty()) {
                    item { ShelfLabel("읽은 책 · ${finished.size}권", more = finished.size > columns) }
                    item(key = "finished") {
                        ShelfRow(finished.map { it.book }) { book, width ->
                            val at = finished.first { it.book.id == book.id }.finishedAtEpochMs!!
                            DoneItem(book, width, at, onOpen, onLongClick = { coverMenu = it })
                        }
                    }
                }
                // 읽을 책: 한 번도 열지 않은 책. 모든 책 목록(0.24.x)은 책장의 책을 한 번 더 보여 줘 길기만 했다.
                val toRead = sort.sort(list.orEmpty().filter { it.id !in shelfIds })
                if (toRead.isNotEmpty()) {
                    if (shelf.isNotEmpty()) item { Spacer(Modifier.height(14.dp)); CpDivider() }
                    item(key = "to-read") {
                        ToReadLabel(
                            "읽을 책 · ${toRead.size}권", sort, layout,
                            onSort = { sortMenu = true },
                            onLayout = container.libraryView::setLayout,
                        )
                    }
                    when (layout) {
                        LibraryLayout.Grid -> items(toRead.chunked(columns), key = { "g" + it.first().id.value }) { row ->
                            GridRow(row) { book, width -> GridItem(book, width, onOpen, onLongClick = { coverMenu = it }) }
                        }
                        LibraryLayout.List -> items(toRead, key = { "l" + it.id.value }) { book ->
                            DetailRow(book, notes[book.id], onOpen, onMenu = { coverMenu = it })
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (manageFolders) {
        CpPopup(title = "책 폴더", message = "폴더를 빼도 그 책들의 읽은 자리와 책갈피는 남습니다. 다시 추가하면 이어집니다.", onDismiss = { manageFolders = false }) {
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

    if (sortMenu) {
        CpPopup(title = "읽을 책 차례", onDismiss = { sortMenu = false }) {
            Spacer(Modifier.height(8.dp))
            LibrarySort.entries.forEach { s ->
                CpRadioRow(s.label, s == sort, { container.libraryView.setSort(s); sortMenu = false })
            }
        }
    }

    coverMenu?.let { book ->
        val done = book.id in finishedIds
        CoverMenu(
            book,
            finished = done,
            onUnread = if (book.id in shelfIds) {
                {
                    coverMenu = null
                    scope.launch {
                        withContext(Dispatchers.IO) { data.library.returnToUnread(book.id) }
                        toast = "‘${book.label}’ — 읽을 책으로 되돌렸습니다"
                    }
                }
            } else {
                null
            },
            onFinished = {
                coverMenu = null
                scope.launch {
                    val now = System.currentTimeMillis()
                    // 여러 줄을 한 번에 고치는(트랜잭션) 일이라 화면 스레드에서 하면 Room 이 막는다.
                    withContext(Dispatchers.IO) { data.library.setFinished(book.id, if (done) null else now, now) }
                    // 책 이름 뒤에 조사를 붙이지 않는다 — 받침에 따라 을/를이 갈려 틀리기 쉽다.
                    toast = if (done) "‘${book.label}’ — 읽는 중으로 되돌렸습니다" else "‘${book.label}’ — 읽은 책으로 옮겼습니다"
                }
            },
            onPick = {
                coverMenu = null
                coverForId = book.id.value
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
    var cover by remember(book) { mutableStateOf(covers.cached(book)) }
    LaunchedEffect(book, version) { cover = covers.cover(book) }
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

/** 책장 줄 머리: "읽는 중 · 5권". 세 권을 넘으면 옆으로 넘길 수 있다고 알린다. */
@Composable
private fun ShelfLabel(text: String, more: Boolean) {
    val c = CpTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = CpTheme.metrics.gutter, end = CpTheme.metrics.gutter, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(text, CpTheme.type.label, c.textMuted, Modifier.weight(1f))
        if (more) CpText("옆으로 넘겨 보기 ›", CpTheme.type.caption, c.textMuted)
    }
}

/**
 * 책장 한 줄: 열어 본 책을 모두 옆으로 늘어놓는다(0.23.0 — 세 권만 보이던 때는 네 번째 책을 다시 찾으려면 모든 책
 * 목록을 뒤져야 했다). 칸 폭은 화면에 세 권이 들어가는 만큼으로 늘 같다 — 두 권뿐이어도 표지가 커지지 않는다.
 */
@Composable
private fun ShelfRow(books: List<LibraryBook>, item: @Composable (LibraryBook, androidx.compose.ui.unit.Dp) -> Unit) {
    val gutter = CpTheme.metrics.gutter
    val screen = LocalConfiguration.current.screenWidthDp.dp
    // 넷째 칸이 오른쪽 끝에 조금 보이게 한다(구상안) — 옆으로 넘길 수 있다는 것을 표지 자체가 알린다.
    val columns = shelfColumns()
    val width = (screen - gutter * 2 - SHELF_GAP * (columns - 1) - SHELF_PEEK) / columns
    LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(SHELF_GAP)) {
        items(books, key = { it.id.value }) { item(it, width) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfItem(
    book: LibraryBook,
    width: androidx.compose.ui.unit.Dp,
    percent: Float?,
    onOpen: (LibraryBook) -> Unit,
    onLongClick: (LibraryBook) -> Unit,
) {
    val c = CpTheme.colors
    val p = percent ?: 0f
    Column(Modifier.width(width).combinedClickable(role = Role.Button, onLongClick = { onLongClick(book) }, onClick = { onOpen(book) })) {
        BookCover(book, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CpText(book.label, CpTheme.type.label, c.text)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CpProgressBar(p / 100f, Modifier.weight(1f), CpBarWeight.Thin)
            Spacer(Modifier.width(6.dp))
            CpText("${p.roundToInt()}%", CpTheme.type.caption, c.textMuted)
        }
    }
}

/** 다 읽은 책: 막대 대신 "다 읽음 · 끝낸 날". 표지 위에 띠를 얹지 않는다 — 대신 표지의 제목 · 저자를 가렸다(구상안). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DoneItem(
    book: LibraryBook,
    width: androidx.compose.ui.unit.Dp,
    finishedAtEpochMs: Long,
    onOpen: (LibraryBook) -> Unit,
    onLongClick: (LibraryBook) -> Unit,
) {
    val c = CpTheme.colors
    Column(Modifier.width(width).combinedClickable(role = Role.Button, onLongClick = { onLongClick(book) }, onClick = { onOpen(book) })) {
        BookCover(book, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CpText(book.label, CpTheme.type.label, c.text)
        Spacer(Modifier.height(4.dp))
        CpText("다 읽음 · ${monthDay(finishedAtEpochMs)}", CpTheme.type.caption, c.accent)
    }
}

/** "9월 21일". 해가 바뀌어도 책장에서는 날짜만으로 충분하다 — 해까지 적으면 칸 폭을 넘는다. */
internal fun monthDay(epochMs: Long): String {
    val date = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return "${date.monthValue}월 ${date.dayOfMonth}일"
}

/** 읽을 책 머리: "읽을 책 · 9권" | 차례 ▾ | 격자 · 목록. */
@Composable
private fun ToReadLabel(text: String, sort: LibrarySort, layout: LibraryLayout, onSort: () -> Unit, onLayout: (LibraryLayout) -> Unit) {
    val c = CpTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = CpTheme.metrics.gutter, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(text, CpTheme.type.label, c.textMuted, Modifier.weight(1f))
        CpText(
            "${sort.label} ▾", CpTheme.type.caption, c.text,
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onSort)
                .padding(horizontal = 10.dp, vertical = 10.dp).semantics { contentDescription = "차례: ${sort.label}" },
        )
        CpIconToggle(
            listOf(CpIcons.Grid, CpIcons.Rows),
            LibraryLayout.entries.map { it.label },
            layout.ordinal,
            { onLayout(LibraryLayout.entries[it]) },
        )
    }
}

/** 격자 한 줄(세 칸). 칸 폭은 화면에서 나온다 — 표지는 칸 밑면에 서므로 한 줄의 표지 밑면이 맞는다. */
@Composable
private fun GridRow(books: List<LibraryBook>, item: @Composable (LibraryBook, androidx.compose.ui.unit.Dp) -> Unit) {
    val gutter = CpTheme.metrics.gutter
    val columns = shelfColumns()
    val width = (LocalConfiguration.current.screenWidthDp.dp - gutter * 2 - SHELF_GAP * (columns - 1)) / columns
    Row(Modifier.padding(horizontal = gutter, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(SHELF_GAP)) {
        books.forEach { item(it, width) }
    }
}

/** 읽을 책 한 칸: 표지 · 제목 · 저자(모르면 형식). 진도가 없으니 막대 대신 저자다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(book: LibraryBook, width: androidx.compose.ui.unit.Dp, onOpen: (LibraryBook) -> Unit, onLongClick: (LibraryBook) -> Unit) {
    val c = CpTheme.colors
    Column(Modifier.width(width).combinedClickable(role = Role.Button, onLongClick = { onLongClick(book) }, onClick = { onOpen(book) })) {
        BookCover(book, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CpText(book.label, CpTheme.type.label, c.text)
        Spacer(Modifier.height(4.dp))
        CpText(book.author ?: book.format.name, CpTheme.type.caption, c.textMuted)
    }
}

/**
 * 읽을 책 목록 한 줄(구상안 확정 — 리디보다 풍성하게): 표지 · 제목 · 저자 · 형식 · 크기 · 든 폴더 · 추가한 날 ·
 * 독서노트 수 · ⋮. 저자를 모르면 줄을 뺀다 — 형식 표시와 겹친다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(book: LibraryBook, notes: NoteCounts?, onOpen: (LibraryBook) -> Unit, onMenu: (LibraryBook) -> Unit) {
    val c = CpTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(role = Role.Button, onLongClick = { onMenu(book) }, onClick = { onOpen(book) })
            .padding(start = CpTheme.metrics.gutter, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BookCover(book, Modifier.width(56.dp), small = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            CpText(book.label, CpTheme.type.body, c.text, maxLines = 2)
            book.author?.let { CpText(it, CpTheme.type.subtitle, c.textMuted) }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shape = RoundedCornerShape(4.dp)
                Box(Modifier.border(1.dp, c.outline, shape).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    CpText(book.format.name, CpTheme.type.caption, c.textMuted)
                }
                val meta = listOfNotNull(book.sizeBytes?.let(::sizeLabel), bookFolder(book.id)).joinToString(" · ")
                if (meta.isNotEmpty()) CpText("  $meta", CpTheme.type.caption, c.textMuted)
            }
            book.addedAtEpochMs?.let {
                Spacer(Modifier.height(6.dp))
                CpText("${monthDay(it)} 추가", CpTheme.type.caption, c.textMuted)
            }
            notes?.let(::notesLabel)?.let { CpText(it, CpTheme.type.caption, c.textMuted, Modifier.padding(top = 2.dp)) }
        }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onMenu(book) }
                .semantics { contentDescription = "${book.label} 더 보기" },
            contentAlignment = Alignment.Center,
        ) { CpText("⋮", CpTheme.type.title, c.textMuted) }
    }
}

/** "책갈피 2 · 형광펜 5 · 메모 1". 없는 것은 뺀다. 모두 없으면 null. */
internal fun notesLabel(n: NoteCounts): String? = listOfNotNull(
    n.bookmarks.takeIf { it > 0 }?.let { "책갈피 $it" },
    n.highlights.takeIf { it > 0 }?.let { "형광펜 $it" },
    n.memos.takeIf { it > 0 }?.let { "메모 $it" },
).joinToString(" · ").ifEmpty { null }

/** 책이 든 폴더 이름(`primary:Books/소설/책.epub` → `소설`). 등록 폴더 바로 안이면 그 폴더 이름. 모르면 null. */
private fun bookFolder(id: BookId): String? = runCatching {
    val doc = DocumentsContract.getDocumentId(Uri.parse(id.value))
    doc.substringAfter(':', doc).substringBeforeLast('/', "").substringAfterLast('/').ifBlank { null }
}.getOrNull()

/** 길게 누른 책의 표지 판(구상안 확정: 사진 · 파일에서 고르기, 되돌리기). */
@Composable
private fun CoverMenu(
    book: LibraryBook,
    finished: Boolean,
    /** 책장에 있는 책만: 읽을 책으로 되돌린다. */
    onUnread: (() -> Unit)?,
    onFinished: () -> Unit,
    onPick: () -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
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
        CpListRow(if (finished) "읽는 중으로 되돌리기" else "읽은 책으로 표시", onFinished, icon = CpIcons.Bookmark, compact = true)
        onUnread?.let { CpListRow("읽을 책으로 되돌리기", it, icon = CpIcons.Back, compact = true) }
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
            "EPUB · TXT · PDF 파일이 든 폴더를 고르면\n그 아래의 책을 모두 찾아 보여 줍니다.",
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

/**
 * 한 줄에 보이는 권수(책장 · 격자). 세로 폰은 셋, 넓으면 표지 폭이 [SHELF_ITEM] 쯤 되게 늘린다 — 셋으로 고정하면 가로
 * 화면에서 표지 하나가 263dp 로 화면보다 커져 제목이 화면 밖으로 밀렸다.
 */
@Composable
private fun shelfColumns(): Int {
    val screen = LocalConfiguration.current.screenWidthDp.dp
    val fit = ((screen - CpTheme.metrics.gutter * 2 + SHELF_GAP) / (SHELF_ITEM + SHELF_GAP)).toInt()
    return fit.coerceAtLeast(SHELF_COLUMNS)
}

/** 책장 한 줄에 한 화면으로 보이는 최소 권수(세로 폰). 더 있으면 옆으로 넘긴다. */
private const val SHELF_COLUMNS = 3
private val SHELF_ITEM = 102.dp
private val SHELF_GAP = 14.dp
private val SHELF_PEEK = 28.dp
