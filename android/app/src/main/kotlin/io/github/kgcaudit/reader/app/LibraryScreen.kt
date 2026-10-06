package io.github.kgcaudit.reader.app

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.semantics.Role
import io.github.kgcaudit.reader.ui.design.CpCover
import io.github.kgcaudit.reader.ui.design.cpBookGlyph
import io.github.kgcaudit.reader.ui.design.CpToast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.withStyle
import io.github.kgcaudit.reader.ui.design.CpSearchField
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import io.github.kgcaudit.reader.ui.design.CpRadioRow
import io.github.kgcaudit.reader.ui.design.CpTabBar
import io.github.kgcaudit.reader.ui.design.CpIconToggle
import io.github.kgcaudit.reader.data.library.NoteCounts
import io.github.kgcaudit.reader.document.BookFormat
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.ui.design.CpBarWeight
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpDivider
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIcon
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
import io.github.kgcaudit.reader.ui.design.CpProgressBar
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** 만화 한 권을 연다(단위 id, 시작 쪽 — null 이면 읽던 자리). */
    onOpenComic: (String, Int?) -> Unit = { _, _ -> },
    /** 만화 한 화 · 권을 열어 표지로 쓸 장면을 고른다(0.47.0). */
    onPickComicCover: (String) -> Unit = {},
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
    // 만화 작품(0.33.0). 작품이 하나도 없으면 탭을 세우지 않는다 — 만화가 없는 사람에게 빈 탭은 군더더기다.
    val works by remember { data.comics.works() }.collectAsState(initial = emptyList())
    val comicProgress by remember { data.comics.progress() }.collectAsState(initial = emptyMap())
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    // 열어 둔 작품은 이름 열쇠로 기억한다. 작품은 그때그때 묶이므로, 합치기 · 빼기 뒤에도 열쇠로 다시 찾는다.
    var openWork by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var arranging by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var copiesOf by remember { mutableStateOf<String?>(null) }
    // 길게 누른 작품(열쇠). 갈래 옮기기 판을 띄운다.
    var workMenu by remember { mutableStateOf<String?>(null) }
    val layout by container.libraryView.layout.collectAsState()
    val sort by container.libraryView.sort.collectAsState()
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    // 책 찾기(0.26.0). 찾던 말은 화면이 다시 만들어져도(회전) 남는다.
    var searching by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
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
    val writeFailed: (Exception) -> Unit = { notice = WRITE_FAILED to "휴대폰 저장 공간이 넉넉한지 확인하고 다시 해 주세요." }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = coverForId ?: return@rememberLauncherForActivityResult
        coverForId = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = container.covers.setCustom(BookId(id)) { context.contentResolver.openInputStream(uri) }
            if (ok) {
                val label = data.library.get(BookId(id))?.label ?: works.firstOrNull { CoverStore.workId(it.key).value == id }?.title
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
                "그 폴더의 책은 목록에 그대로 둡니다. 저장소가 연결돼 있는지 확인한 뒤 새로고침해 주세요."
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val clash = uri?.let { data.folders.overlapping(it) }
        if (uri != null && clash != null) {
            // 겹친 두 폴더를 모두 등록하면 같은 책이 두 번 보이고, 어느 쪽으로 열었는지에 따라 읽던 자리가 달랐다.
            val (other, inside) = clash
            notice = if (inside) {
                "이미 등록한 폴더 안에 있습니다" to "‘${folderName(uri)}’ 폴더의 책은 이미 등록한 ‘${folderName(other)}’ 폴더에서 보입니다."
            } else {
                "안에 이미 등록한 폴더가 있습니다" to "‘${folderName(uri)}’ 안에 이미 등록한 ‘${folderName(other)}’ 폴더가 있습니다. " +
                    "책이 두 번 보이지 않게, 책 폴더에서 ‘${folderName(other)}’를 뺀 뒤 ‘${folderName(uri)}’를 추가해 주세요."
            }
        } else if (uri != null) {
            runCatching { data.folders.register(uri) }
                .onFailure { notice = "이 폴더를 등록하지 못했습니다" to it.message }
            folders = data.folders.folders()
            rescan()
        }
    }

    // 앱을 열 때(뒤에서 돌아올 때 포함) 한 번 훑는다. 폴더에 새로 넣은 책이 바로 보여야 한다. 책을 닫고 돌아올 때는
    // 훑지 않는다 — 이 화면은 책을 여는 동안 사라졌다 다시 생기므로, 여기서 기억하면 돌아올 때마다 훑는다. 값이 열쇠라,
    // 화면이 떠 있는 채로 앱이 돌아와도 훑는다.
    LaunchedEffect(scanOnStart) {
        if (scanOnStart && folders.isNotEmpty()) {
            onStartScan()
            rescan()
        }
    }

    // 칸 폭은 이 화면이 실제로 쓰는 폭에서 셈한다(0.29.0). 화면 전체 폭으로 세면 가로 화면에서 탐색 막대 · 카메라 구멍만큼
    // 좁아진 자리에 칸이 넘쳐 마지막 칸의 표지만 작아졌다("칸 크기는 같게").
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    androidx.compose.runtime.CompositionLocalProvider(LocalShelfWidth provides maxWidth) {
    Column(Modifier.fillMaxSize()) {
        val list = books
        // 위 탭 [책 · 만화](2026-10-02 사용자 결정 1 가안). 찾기 · 폴더 · 새로고침은 두 탭이 함께 쓴다.
        val comics = folders.isNotEmpty() && works.isNotEmpty()
        // 좌우로 밀면 책 ↔ 만화(0.38.0, 2026-10-03 사용자 결정 6). 그래서 탭 안에는 옆으로 밀리는 줄을 두지 않는다 — 탭 밀기와
        // 같은 몸짓이라 어느 쪽이 받을지 사람이 알 수 없다(Material 탭 지침).
        val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = tab) { 2 }
        LaunchedEffect(pager) { androidx.compose.runtime.snapshotFlow { pager.currentPage }.collect { tab = it } }
        if (folders.isEmpty()) {
            // 처음 켠 사람: 앱 이름과 할 일. 앱 정보는 책이 없어도 닿아야 한다 — 판 번호를 묻는 일은 무언가 안 될 때 생긴다.
            CpHeader(title = "OLO eBook", subtitle = "책이 있는 폴더를 추가해 주세요") { CpIconButton(CpIcons.Info, "앱 정보", onAbout) }
        } else {
            // 머리 줄("OLO eBook · 책 14권 · 만화 5작품")을 없앴다(0.44.0, 2026-10-03 사용자 결정 1) — 앱 이름은 아이콘이
            // 이미 말하고, 권 수는 탭에 있다. 탭이 맨 위, 그 아래 한 줄에 순서 · 찾기 · 보기 · 더 보기.
            if (comics) CpTabBar(listOf("책 ${list?.size ?: 0}", "만화 ${works.size}"), pager.currentPage, { scope.launch { pager.animateScrollToPage(it) } }, Modifier.padding(top = 4.dp))
            LibraryTools(
                sort, layout,
                onSort = { sortMenu = true },
                onLayout = container.libraryView::setLayout,
                onSearch = { searching = true },
                onMore = { moreMenu = true },
            )
            if (scanning) CpProgressBar(0.35f, Modifier.padding(horizontal = CpTheme.metrics.gutter), CpBarWeight.Thin)
        }

        val columns = shelfColumns()
        val bookPage: @Composable () -> Unit = {
            if (list != null && list.isEmpty() && !scanning) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    CpText(
                        if (comics) "등록한 폴더에 EPUB · TXT · PDF 파일이 없습니다. 만화는 만화 탭에 있습니다."
                        else "등록한 폴더에 EPUB · TXT · PDF 파일이 없습니다.",
                        CpTheme.type.subtitle, colors.textMuted, maxLines = 3,
                    )
                }
            } else {
                // 최근 읽은 순: 책장은 이미 연 차례(최근이 앞)로 온다 — 그 자리를 그대로 열쇠로 쓴다.
                val opened = reading.withIndex().associate { (i, b) -> b.id to (reading.size - i).toLong() }
                val readingSorted = sort.sort(reading) { opened[it.id] }
                val finishedAt = finished.associate { it.book.id to it.finishedAtEpochMs }
                val finishedSorted = sort.sort(finished.map { it.book }) { finishedAt[it.id] }
                // 읽을 책: 한 번도 열지 않은 책. 모든 책 목록(0.24.x)은 책장의 책을 한 번 더 보여 줘 길기만 했다.
                val toRead = sort.sort(list.orEmpty().filter { it.id !in shelfIds })
                ShelfWall(layout) { LazyColumn(Modifier.fillMaxSize()) {
                    shelfTop(layout)
                    shelfSection(
                        "reading", "읽는 책 · ${reading.size}권", readingSorted, { it.id.value }, layout, columns,
                        grid = { book, width -> ShelfItem(book, width, percents[book.id], onOpen, onLongClick = { coverMenu = it }) },
                        row = { book -> DetailRow(book, notes[book.id], onOpen, onMenu = { coverMenu = it }) { PercentLine(percents[book.id]) } },
                        shelf = { book, width -> ShelfCover(book, width, onOpen, onLongClick = { coverMenu = it }) },
                    )
                    shelfSection(
                        "finished", "읽은 책 · ${finished.size}권", finishedSorted, { it.id.value }, layout, columns,
                        grid = { book, width -> DoneItem(book, width, finishedAt[book.id]!!, onOpen, onLongClick = { coverMenu = it }) },
                        row = { book -> DetailRow(book, notes[book.id], onOpen, onMenu = { coverMenu = it }) { DoneLine(finishedAt[book.id]) } },
                        shelf = { book, width -> ShelfCover(book, width, onOpen, onLongClick = { coverMenu = it }) },
                    )
                    shelfSection(
                        "to-read", "읽을 책 · ${toRead.size}권", toRead, { it.id.value }, layout, columns,
                        divider = shelf.isNotEmpty(),
                        grid = { book, width -> GridItem(book, width, onOpen, onLongClick = { coverMenu = it }) },
                        row = { book -> DetailRow(book, notes[book.id], onOpen, onMenu = { coverMenu = it }) },
                        shelf = { book, width -> ShelfCover(book, width, onOpen, onLongClick = { coverMenu = it }) },
                    )
                    shelfEnd(layout)
                } }
            }
        }

        if (folders.isEmpty()) {
            EmptyLibrary { pickFolder.launchOr(null) { notice = NO_PICKER to NO_PICKER_HINT } }
        } else if (comics) {
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                if (page == 0) bookPage() else ShelfWall(layout) { LazyColumn(Modifier.fillMaxSize()) {
                    shelfTop(layout)
                    comicShelf(
                        works, comicProgress, sort, layout, columns,
                        onOpen = { openWork = it.key },
                        onResume = { entry -> onOpenComic(entry.unit.id, null) },
                        onMenu = { workMenu = it.key },
                    )
                    shelfEnd(layout)
                } }
            }
        } else {
            bookPage()
        }
    }
    }
    }

    if (searching) {
        androidx.activity.compose.BackHandler { searching = false }
        val readingIds = reading.mapTo(HashSet()) { it.id }
        BookSearch(
            query = query,
            onQuery = { query = it },
            total = books?.size ?: 0,
            hits = findBooks(books.orEmpty(), readingIds, finishedIds, query),
            percents = percents,
            onOpen = onOpen,
            onMenu = { coverMenu = it },
            onBack = { searching = false },
        )
    }

    // 작품 화면 · 작품 정리. 열쇠로 찾지 못하면(합쳐져 사라짐 · 폴더를 뺌) 닫는다.
    val work = openWork?.let { key -> works.firstOrNull { it.key == key } }
    if (openWork != null && work == null && works.isNotEmpty()) {
        LaunchedEffect(openWork) { openWork = null; arranging = false }
    }
    if (work != null) {
        androidx.activity.compose.BackHandler { openWork = null }
        WorkScreen(
            work,
            comicProgress,
            onBack = { openWork = null },
            onArrange = { arranging = true },
            onEntry = { entry, page -> onOpenComic(entry.unit.id, page) },
            onCopies = { copiesOf = it.slot },
        )
        if (arranging) {
            androidx.activity.compose.BackHandler { arranging = false }
            WorkArrange(
                work,
                others = works.filter { it.key != work.key },
                onBack = { arranging = false },
                onMerge = { into ->
                    // 열어 둔 작품을 합친 쪽으로 옮겨 둔다 — 그대로 두면 열쇠를 잃은 화면이 닫혀 서재로 튕긴다.
                    openWork = into.key
                    arranging = false
                    scope.launchWrite(writeFailed, then = { toast = "‘${work.title}’ — ‘${into.title}’ 작품에 합쳤습니다" }) { data.comics.merge(work, into) }
                },
                onSplit = { entry ->
                    scope.launchWrite(writeFailed, then = { toast = "‘${entry.label}’ — 따로 뺐습니다" }) { data.comics.split(entry) }
                },
                onRename = { title ->
                    scope.launchWrite(writeFailed, then = { toast = if (title.isBlank()) "작품 이름을 되돌렸습니다" else "작품 이름을 고쳤습니다" }) {
                        data.comics.rename(work, title)
                    }
                },
                onCopies = { copiesOf = it.slot },
            )
        }
        copiesOf?.let { slot -> work.entries.firstOrNull { it.slot == slot } }?.let { entry ->
            CopiesPopup(
                entry,
                onPick = { unit ->
                    copiesOf = null
                    scope.launchWrite(writeFailed) { data.comics.prefer(entry, unit.id) }
                },
                onDismiss = { copiesOf = null },
            )
        }
    }

    // 작품 갈래 옮기기(길게 눌러, 0.38.0). 책의 표지 판과 같은 자리 · 같은 모양.
    workMenu?.let { key -> works.firstOrNull { it.key == key } }?.let { w ->
        androidx.activity.compose.BackHandler { workMenu = null }
        val status = io.github.kgcaudit.reader.document.comic.WorkStatuses.of(w, comicProgress)
        fun move(mark: io.github.kgcaudit.reader.document.comic.ShelfMark, done: String) {
            workMenu = null
            scope.launchWrite(writeFailed, then = { toast = "‘${w.title}’ — $done" }) { data.comics.setShelf(w, mark) }
        }
        WorkMenu(
            w, status.shelf,
            onOpen = { workMenu = null; openWork = w.key },
            onFinished = { move(io.github.kgcaudit.reader.document.comic.ShelfMark.Finished(System.currentTimeMillis(), w.volumeCount), "읽은 책으로 옮겼습니다") },
            onReading = { move(io.github.kgcaudit.reader.document.comic.ShelfMark.Reading(System.currentTimeMillis()), "읽는 책으로 옮겼습니다") },
            onToRead = { move(io.github.kgcaudit.reader.document.comic.ShelfMark.ToRead(System.currentTimeMillis()), "읽을 책으로 옮겼습니다") },
            onDismiss = { workMenu = null },
            onScene = {
                workMenu = null
                val entry = io.github.kgcaudit.reader.document.comic.ComicReading.resume(w, comicProgress)?.first ?: w.entries.first()
                onPickComicCover(entry.unit.id)
            },
            onPhoto = {
                workMenu = null
                coverForId = CoverStore.workId(w.key).value
                pickCover.launchOr(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) { notice = NO_PICKER to NO_PICKER_HINT }
            },
            onRevert = {
                workMenu = null
                scope.launch {
                    container.covers.clearCustom(CoverStore.workId(w.key))
                    toast = "‘${w.title}’ 표지를 되돌렸습니다"
                }
            },
        )
    }

    if (manageFolders) {
        CpPopup(title = "책 폴더", message = "폴더를 빼도 그 책들의 읽은 자리와 책갈피는 남습니다. 다시 추가하면 이어집니다.", onDismiss = { manageFolders = false }) {
            Spacer(Modifier.height(8.dp))
            // "폴더 추가" 도 폴더 행과 같은 모양 · 같은 시작선(0.29.0). 목록 행(여백 16dp)으로 그리던 때는 이 행만 16dp
            // 안쪽에서 시작해, 같은 급의 선택지인데 폴더들 아래 딸린 것처럼 보였다.
            CpListRow(
                title = "폴더 추가",
                icon = CpIcons.Plus,
                tile = colors.accent,
                onClick = { manageFolders = false; pickFolder.launchOr(null) { notice = NO_PICKER to NO_PICKER_HINT } },
                compact = true,
                inset = 0.dp,
            )
            folders.forEach { uri ->
                Row(Modifier.heightIn(min = CpTheme.metrics.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                    CpTile(CpIcons.Folder, colors.tiles.folder)
                    Spacer(Modifier.width(14.dp))
                    CpText(folderName(uri), CpTheme.type.body, colors.text, Modifier.weight(1f))
                    CpIconButton(CpIcons.Close, "${folderName(uri)} 빼기", {
                        scope.launch {
                            // DB 가 가득 · 깨짐이면 예외 — 잡지 않으면 판에서 ✕ 를 누른 손에 앱이 닫혔다.
                            try {
                                data.removeFolder(uri)
                                folders = data.folders.folders()
                                if (folders.isEmpty()) manageFolders = false
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                notice = "이 폴더를 빼지 못했습니다" to e.message
                            }
                        }
                    }, tint = colors.textMuted)
                }
            }
        }
    }

    if (sortMenu) {
        CpPopup(title = "순서", message = "읽는 책 · 읽은 책 · 읽을 책이 모두 이 차례를 따릅니다.", onDismiss = { sortMenu = false }) {
            Spacer(Modifier.height(8.dp))
            LibrarySort.entries.forEach { s ->
                CpRadioRow(s.label, s == sort, { container.libraryView.setSort(s); sortMenu = false }, inset = 0.dp)
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
                    scope.launchWrite(writeFailed, then = { toast = "‘${book.label}’ — 읽을 책으로 옮겼습니다" }) { data.library.returnToUnread(book.id) }
                }
            } else {
                null
            },
            onFinished = {
                coverMenu = null
                val now = System.currentTimeMillis()
                // 여러 줄을 한 번에 고치는(트랜잭션) 일이라 화면 스레드에서 하면 Room 이 막는다(launchWrite 가 입출력 스레드에서 쓴다).
                // 책 이름 뒤에 조사를 붙이지 않는다 — 받침에 따라 을/를이 갈려 틀리기 쉽다.
                scope.launchWrite(writeFailed, then = { toast = if (done) "‘${book.label}’ — 읽는 책으로 옮겼습니다" else "‘${book.label}’ — 읽은 책으로 옮겼습니다" }) {
                    data.library.setFinished(book.id, if (done) null else now, now)
                }
            },
            onPick = {
                coverMenu = null
                coverForId = book.id.value
                pickCover.launchOr(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) { notice = NO_PICKER to NO_PICKER_HINT }
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

    if (moreMenu) {
        MoreMenu(
            onRefresh = { rescan() },
            onFolders = { manageFolders = true },
            onAbout = onAbout,
            onDismiss = { moreMenu = false },
        )
    }

    Box(Modifier.fillMaxSize()) {
        CpToast(toast, { toast = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
    }

    notice?.let { (title, message) ->
        CpPopup(title = title, message = message, onDismiss = { notice = null }) {
            CpPopupButtons { CpButton("확인", { notice = null }) }
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
        // 세 종류 모두 문서 회청이다(계열 FILEKIND). EPUB 은 색이 아니라 그림(펼친 책)으로 가른다.
        fallback = tiles.document,
        icon = when (book.format) {
            BookFormat.EPUB -> CpIcons.Book
            BookFormat.TXT -> CpIcons.Text
            BookFormat.PDF -> CpIcons.Pdf
        },
        modifier = modifier,
        small = small,
        glyph = if (book.format == BookFormat.EPUB) cpBookGlyph() else null,
    )
}

/** 갈래 머리: "읽는 책 · 5권". 책장 보기에서는 나무 위의 밝은 글자. */
@Composable
internal fun ShelfLabel(text: String, color: androidx.compose.ui.graphics.Color = CpTheme.colors.textMuted) {
    CpText(
        text, CpTheme.type.label, color,
        Modifier.fillMaxWidth().padding(start = CpTheme.metrics.gutter, end = CpTheme.metrics.gutter, top = 18.dp, bottom = 6.dp),
    )
}

/**
 * 서재 갈래 하나(머리 + 칸들). 세 갈래가 모두 같은 보기([layout])를 따른다(0.38.0). 빈 갈래는 머리째 숨긴다.
 *
 * 읽는 중도 옆으로 넘기지 않고 줄을 바꿔 늘어놓는다 — 탭을 좌우로 밀어 책 ↔ 만화를 오가므로, 그 안에서 옆으로 밀리는 줄은
 * 같은 몸짓을 두고 다툰다(2026-10-03 사용자 결정 6).
 */
internal fun <T> LazyListScope.shelfSection(
    key: String,
    label: String,
    items: List<T>,
    id: (T) -> String,
    layout: LibraryLayout,
    columns: Int,
    divider: Boolean = false,
    grid: @Composable (T, androidx.compose.ui.unit.Dp) -> Unit,
    row: @Composable (T) -> Unit,
    /** 책장 보기의 한 칸(표지만). */
    shelf: @Composable (T, androidx.compose.ui.unit.Dp) -> Unit,
) {
    if (items.isEmpty()) return
    // 책장 보기는 판이 갈래를 가른다 — 선을 더 그으면 나무 위에 회색 줄이 뜬다.
    if (layout == LibraryLayout.Shelf) {
        // 책장: 칸마다 위에 판, 첫 판 앞면에 이 칸의 이름(2026-10-03 사용자 정정 — 이름은 책 위 칸막이에). 갈래 사이 회색
        // 선은 긋지 않는다 — 판이 이미 가른다.
        items(items.chunked(columns).withIndex().toList(), key = { "$key-s" + id(it.value.first()) }) { (i, cells) ->
            Column {
                ShelfBoard(if (i == 0) label else null)
                WoodRow(cells, shelf)
            }
        }
        return
    }
    if (divider) item(key = "$key-divider") { Spacer(Modifier.height(14.dp)); CpDivider() }
    item(key = "$key-label") { ShelfLabel(label) }
    when (layout) {
        LibraryLayout.Grid -> items(items.chunked(columns), key = { "$key-g" + id(it.first()) }) { cells -> GridRow(cells, grid) }
        LibraryLayout.List -> items(items, key = { "$key-l" + id(it) }) { row(it) }
        LibraryLayout.Shelf -> Unit
    }
}

/** 갈래들 앞: 책장이면 맨 위 테두리. 다른 보기는 아무것도 두지 않는다 — 칸 이름의 위 여백이 이미 그 몫이다. */
internal fun LazyListScope.shelfTop(layout: LibraryLayout) {
    if (layout == LibraryLayout.Shelf) item(key = "shelf-crown") { ShelfCrown() }
}

/**
 * 갈래들 뒤: 책장이면 판 하나와 빈 칸들을 화면 끝까지, 아니면 바닥 여백. 열쇠를 주지 않는다 — 책 목록이 오기 전에는 이
 * 줄만 있어 첫 줄이 되는데, 열쇠가 있으면 목록이 그 줄을 붙들고 있어 책이 오자 서재가 맨 아래로 굴러가 있었다.
 */
internal fun LazyListScope.shelfEnd(layout: LibraryLayout) {
    if (layout == LibraryLayout.Shelf) {
        item { EmptyShelves(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp) }
    } else {
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 책장 보기의 책 한 권: 표지만. 누르면 열고 길게 누르면 표지 판 — 격자 칸과 같다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCover(book: LibraryBook, width: androidx.compose.ui.unit.Dp, onOpen: (LibraryBook) -> Unit, onLongClick: (LibraryBook) -> Unit) {
    Box(Modifier.width(width).combinedClickable(role = Role.Button, onLongClick = { onLongClick(book) }, onClick = { onOpen(book) })) {
        BookCover(book, Modifier.fillMaxWidth())
    }
}

/** 읽는 중 줄: 진도 막대 · 몇 %. 목록 보기는 오른쪽을 띄우고([modifier] 기본), 격자 칸은 칸 폭을 다 쓴다. */
@Composable
private fun PercentLine(percent: Float?, modifier: Modifier = Modifier.padding(top = 6.dp, end = 12.dp)) {
    val p = percent ?: 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CpProgressBar(p / 100f, Modifier.weight(1f), CpBarWeight.Thin)
        Spacer(Modifier.width(6.dp))
        CpText("${p.roundToInt()}%", CpTheme.type.caption, CpTheme.colors.textMuted)
    }
}

/** 목록 보기의 다 읽은 줄: "다 읽음 · 9월 28일". 날짜를 모르면 "다 읽음". */
@Composable
internal fun DoneLine(finishedAtEpochMs: Long?) {
    CpText(
        listOfNotNull("다 읽음", finishedAtEpochMs?.let(::monthDay)).joinToString(" · "),
        CpTheme.type.caption, CpTheme.colors.accentText, Modifier.padding(top = 4.dp),
    )
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
    Column(Modifier.width(width).combinedClickable(role = Role.Button, onLongClick = { onLongClick(book) }, onClick = { onOpen(book) })) {
        BookCover(book, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CpText(book.label, CpTheme.type.label, c.text)
        PercentLine(percent, Modifier.padding(top = 6.dp))
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
        DoneLine(finishedAtEpochMs)
    }
}

/**
 * 시스템 고르기 창(파일 · 폴더 · 사진)을 연다. 그 창이 없는 기기(일부 Go · TV · 관리 프로필)에서는 열기 자체가 예외라 앱이
 * 닫혔다 — 빈 서재에서 "폴더 추가" 를 누르면 끝이었다. 없으면 [onMissing] 으로 알린다.
 */
internal fun <I> androidx.activity.result.ActivityResultLauncher<I>.launchOr(input: I, onMissing: () -> Unit) {
    try {
        launch(input)
    } catch (e: android.content.ActivityNotFoundException) {
        onMissing()
    }
}

/** 고르기 창이 없을 때 알림. */
internal const val NO_PICKER = "이 휴대폰에서 고르기 창을 열 수 없습니다"

/** [NO_PICKER] 아래에 붙는 까닭. 네 곳(폴더 추가 · 표지 고르기)이 같은 말을 한다. */
internal const val NO_PICKER_HINT = "휴대폰의 ‘파일’ 앱이 꺼져 있으면 켜 주세요."

/** DB 쓰기가 실패했을 때 알림. */
internal const val WRITE_FAILED = "바꾼 것을 저장하지 못했습니다"

/**
 * DB 에 쓰는 일([write])을 입출력 스레드에서 돌리고, 끝나면 [then]. 쓰기가 실패하면(저장 공간이 가득 · DB 가 깨짐) 앱을
 * 닫지 않고 [onError] 로 알린다 — 잡지 않은 예외는 화면 범위를 거쳐 앱을 닫았다(서재의 갈래 옮기기 · 작품 합치기, 만화의
 * 자리 적기 · 책갈피). 취소는 실패가 아니라 그대로 올린다.
 */
internal fun CoroutineScope.launchWrite(onError: (Exception) -> Unit, then: () -> Unit = {}, write: suspend () -> Unit): Job = launch {
    try {
        withContext(Dispatchers.IO) { write() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("OloData", "write failed", e)
        onError(e)
        return@launch
    }
    then()
}

/** "9월 21일". 해가 바뀌어도 책장에서는 날짜만으로 충분하다 — 해까지 적으면 칸 폭을 넘는다. */
internal fun monthDay(epochMs: Long): String {
    val date = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return "${date.monthValue}월 ${date.dayOfMonth}일"
}

/**
 * 책 찾기(구상안 가 확정): 입력칸 · 몇 권 찾았는지 · 결과 목록. 결과마다 홈의 어느 갈래인지(읽는 중은 진도까지)를
 * 붙이고 찾은 글자를 칠한다 — 걸러진 선반 · 격자(나)는 결과가 흩어져 한눈에 보이지 않았다.
 */
@Composable
private fun BookSearch(
    query: String,
    onQuery: (String) -> Unit,
    total: Int,
    hits: List<BookHit>,
    percents: Map<BookId, Float>,
    onOpen: (LibraryBook) -> Unit,
    onMenu: (LibraryBook) -> Unit,
    onBack: () -> Unit,
) {
    val c = CpTheme.colors
    // 서재 찾기는 옆 판이 아니다 — 뒤에 읽던 쪽이 없다. 태블릿에서는 폭만 줄여 결과 줄이 969dp 로 늘어지지 않게.
    CpFullScreen(side = false) {
        CpSearchField(query, onQuery, onClear = { onQuery("") }, placeholder = "책 제목 · 저자 · 파일 이름", onBack = onBack)
        when {
            query.isBlank() -> CpText(
                "책 ${total}권에서 찾습니다", CpTheme.type.subtitle, c.textMuted,
                Modifier.fillMaxWidth().padding(start = CpTheme.metrics.gutter, end = CpTheme.metrics.gutter, top = 80.dp),
            )
            hits.isEmpty() -> CpText(
                "‘${query.trim()}’ — 맞는 책이 없습니다", CpTheme.type.subtitle, c.textMuted,
                Modifier.fillMaxWidth().padding(start = CpTheme.metrics.gutter, end = CpTheme.metrics.gutter, top = 80.dp), maxLines = 2,
            )
            else -> {
                CpText("${hits.size}권 · 제목 · 저자 · 파일 이름에서", CpTheme.type.caption, c.textMuted, Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 6.dp))
                CpDivider()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(hits, key = { it.book.id.value }) { hit -> HitRow(hit, query, percents[hit.book.id], onOpen, onMenu) }
                }
            }
        }
    }
}

/** 찾은 책 한 줄: 작은 표지 · 제목 · 저자(찾은 글자 칠) · 형식 · 크기 · 폴더 · 오른쪽에 갈래. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HitRow(hit: BookHit, query: String, percent: Float?, onOpen: (LibraryBook) -> Unit, onMenu: (LibraryBook) -> Unit) {
    val c = CpTheme.colors
    val book = hit.book
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(role = Role.Button, onLongClick = { onMenu(book) }, onClick = { onOpen(book) })
            .padding(horizontal = CpTheme.metrics.gutter, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BookCover(book, Modifier.width(48.dp), small = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Marked(book.label, query, CpTheme.type.body, c.text, maxLines = 2)
            book.author?.let { Marked(it, query, CpTheme.type.subtitle, c.textMuted) }
            // 제목이 따로 있으면 파일 이름에서 찾은 것일 수 있다 — 그때는 파일 이름을 보여 어디서 맞았는지 알린다.
            if (book.title != null && matchRange(book.label, query) == null && book.author?.let { matchRange(it, query) } == null) {
                Marked(book.displayName, query, CpTheme.type.caption, c.textMuted)
            }
            Spacer(Modifier.height(4.dp))
            val meta = listOfNotNull(book.sizeBytes?.let(::sizeLabel), bookFolder(book.id)).joinToString(" · ")
            CpText(listOf(book.format.name, meta).filter { it.isNotEmpty() }.joinToString("  "), CpTheme.type.caption, c.textMuted)
        }
        Spacer(Modifier.width(8.dp))
        // 글자 꼬리표는 계열의 칩 모서리(6dp, 0.32.2). 알약이었다.
        val shape = RoundedCornerShape(CpTheme.metrics.cornerChip)
        val label = if (hit.shelf == Shelf.Reading) "${hit.shelf.label} ${(percent ?: 0f).roundToInt()}%" else hit.shelf.label
        Box(Modifier.clip(shape).border(1.dp, c.outline, shape).padding(horizontal = 10.dp, vertical = 3.dp)) {
            CpText(label, CpTheme.type.caption, c.textMuted)
        }
    }
}

/** 찾은 글자에 고른 행 바탕색을 칠한 글. */
@Composable
private fun Marked(text: String, query: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color, maxLines: Int = 1) {
    val range = matchRange(text, query)
    val marked = androidx.compose.ui.text.buildAnnotatedString {
        if (range == null) append(text) else {
            append(text.substring(0, range.first))
            withStyle(androidx.compose.ui.text.SpanStyle(background = CpTheme.colors.accentContainer)) { append(text.substring(range.first, range.last + 1)) }
            append(text.substring(range.last + 1))
        }
    }
    androidx.compose.foundation.text.BasicText(
        marked, style = style.copy(color = color), maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/**
 * 탭 아래 도구 한 줄(0.44.0): 차례 ▾ · · · 찾기 · 격자 | 목록 | 책장 · 더 보기(새로고침 · 책 폴더 · 앱 정보). 머리 줄을
 * 없애며 그 단추들을 여기로 모았다 — 자주 쓰는 찾기만 밖에, 가끔 쓰는 셋은 더 보기 안에.
 */
@Composable
private fun LibraryTools(
    sort: LibrarySort,
    layout: LibraryLayout,
    onSort: () -> Unit,
    onLayout: (LibraryLayout) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
) {
    val c = CpTheme.colors
    Row(
        // 차례 글자가 갈래 머리와 같은 시작선에 선다 — 누르는 곳의 안쪽 여백(10dp)만큼 덜 들인다.
        Modifier.fillMaxWidth().padding(start = CpTheme.metrics.gutter - 10.dp, end = 0.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 누르는 곳 48dp(0.29.0 — 36dp 였다). ▾ 는 글자 대신 아이콘: 삼성 글꼴 스타일에 따라 모양이 바뀌었다.
        Row(
            Modifier.heightIn(min = CpTheme.metrics.touchTarget).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onSort)
                .padding(start = 10.dp, end = 4.dp).semantics(mergeDescendants = true) { contentDescription = "순서: ${sort.label}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CpText(sort.label, CpTheme.type.caption, c.text)
            CpIcon(CpIcons.ChevronDown, c.textMuted, size = 18.dp)
        }
        Spacer(Modifier.weight(1f))
        CpIconButton(CpIcons.Search, "책 찾기", onSearch)
        CpIconToggle(
            listOf(CpIcons.Grid, CpIcons.Rows, CpIcons.Shelf),
            LibraryLayout.entries.map { it.label },
            layout.ordinal,
            { onLayout(LibraryLayout.entries[it]) },
        )
        CpIconButton(CpIcons.More, "더 보기", onMore)
    }
}

/**
 * 더 보기 판: 새로고침 · 책 폴더 · 앱 정보. 오른쪽 위에 떠서 서재를 밀어내지 않는다. 판 밖을 누르거나 뒤로 가기면 닫힌다.
 * 줄마다 이전 머리 단추와 같은 이름을 읽힌다(화면 읽기 · 시험이 같은 이름으로 찾는다).
 */
@Composable
private fun MoreMenu(onRefresh: () -> Unit, onFolders: () -> Unit, onAbout: () -> Unit, onDismiss: () -> Unit) {
    val c = CpTheme.colors
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = null, onClick = onDismiss).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = MORE_MENU_TOP, end = 8.dp).width(200.dp)
                .shadow(8.dp, RoundedCornerShape(CpTheme.metrics.cornerSmall + 4.dp))
                .clip(RoundedCornerShape(CpTheme.metrics.cornerSmall + 4.dp)).background(c.surface)
                .clickable(indication = null, interactionSource = null) {}
                .padding(vertical = 6.dp),
        ) {
            listOf(Triple(CpIcons.Refresh, "새로고침", onRefresh), Triple(CpIcons.Folder, "책 폴더", onFolders), Triple(CpIcons.Info, "앱 정보", onAbout))
                .forEach { (icon, label, action) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = CpTheme.metrics.touchTarget)
                            .clickable(role = Role.Button) { onDismiss(); action() }
                            .semantics(mergeDescendants = true) { contentDescription = label }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CpIcon(icon, c.text, size = 22.dp)
                        Spacer(Modifier.width(14.dp))
                        CpText(label, CpTheme.type.body, c.text)
                    }
                }
        }
    }
}

/** 더 보기 판이 뜨는 높이: 탭(48) + 도구 줄 아래. 탭이 없으면 조금 위에 떠도 도구 줄을 가리지 않는다. */
private val MORE_MENU_TOP = 100.dp

/** 격자 한 줄(세 칸). 칸 폭은 화면에서 나온다 — 표지는 칸 밑면에 서므로 한 줄의 표지 밑면이 맞는다. */
@Composable
internal fun <T> GridRow(books: List<T>, item: @Composable (T, androidx.compose.ui.unit.Dp) -> Unit) {
    val gutter = CpTheme.metrics.gutter
    val columns = shelfColumns()
    val width = (LocalShelfWidth.current - gutter * 2 - SHELF_GAP * (columns - 1)) / columns
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
private fun DetailRow(
    book: LibraryBook,
    notes: NoteCounts?,
    onOpen: (LibraryBook) -> Unit,
    onMenu: (LibraryBook) -> Unit,
    /** 갈래에 따른 줄(읽는 중: 진도, 읽은: 끝낸 날). 읽을 책은 없다. */
    status: (@Composable () -> Unit)? = null,
) {
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
            status?.invoke()
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
        CpIconButton(CpIcons.More, "${book.label} 더 보기", { onMenu(book) }, tint = c.textMuted)
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
        // 판 안의 행은 판 글자 시작선에서(inset 0). 판 여백에 행 여백이 더해져 제목 · 문장보다 16dp 안쪽에서 시작하면
        // 이 행들이 문장에 "속한" 것처럼 보였다(0.29.0).
        CpListRow(if (finished) "읽는 책으로 옮기기" else "읽은 책으로 옮기기", onFinished, icon = if (finished) CpIcons.Book else CpIcons.Bookmark, compact = true, inset = 0.dp)
        onUnread?.let { CpListRow("읽을 책으로 옮기기", it, icon = CpIcons.Back, compact = true, inset = 0.dp) }
        CpListRow("사진 · 파일에서 표지 고르기", onPick, icon = CpIcons.Folder, compact = true, inset = 0.dp)
        CpListRow(
            if (cover?.hasOwn == true) "원래 표지로 되돌리기" else "대신 표지로 되돌리기",
            // 고른 표지가 없으면 되돌릴 것이 없다. 눌러도 판만 닫는다.
            { if (custom) onRevert() else onDismiss() },
            icon = CpIcons.Refresh,
            compact = true,
            enabled = custom,
            inset = 0.dp,
        )
        CpPopupButtons { CpButton("닫기", onDismiss, primary = false) }
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
internal fun shelfColumns(): Int {
    val screen = LocalShelfWidth.current
    // 태블릿은 표지를 키운다(0.30.0, 구상안 가안). 102dp 로 펼친 폴더블을 채우면 한 줄에 예닐곱 권이 작게 늘어서
    // 제목 글자가 두세 자에서 잘리고 표지 그림이 알아볼 수 없었다.
    val item = if (io.github.kgcaudit.reader.ui.design.cpTablet()) SHELF_ITEM_TABLET else SHELF_ITEM
    val fit = ((screen - CpTheme.metrics.gutter * 2 + SHELF_GAP) / (item + SHELF_GAP)).toInt()
    return fit.coerceAtLeast(SHELF_COLUMNS)
}

/** 라이브러리가 실제로 쓰는 폭(시스템 막대 · 카메라 구멍을 뺀 것). 칸 수 · 칸 폭을 여기서 셈한다. */
internal val LocalShelfWidth = androidx.compose.runtime.compositionLocalOf { 360.dp }

/** 한 줄에 서는 최소 권수(세로 폰). */
private const val SHELF_COLUMNS = 3
private val SHELF_ITEM = 102.dp
private val SHELF_ITEM_TABLET = 130.dp
internal val SHELF_GAP = 14.dp
