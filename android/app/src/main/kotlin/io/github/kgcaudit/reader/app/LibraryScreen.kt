package io.github.kgcaudit.reader.app

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.kgcaudit.reader.ui.design.CpTile
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 첫 화면. 최근 책과 모든 책.
 *
 * 책을 가져오는 방법은 폴더 등록 하나뿐이다. 파일을 한 권씩 고르게 하면 권한 개수 제한
 * (128/512)에 걸리고, 폴더에 책을 넣는 것만으로 목록에 나타나는 편이 쓰기 쉽다.
 */
@Composable
fun LibraryScreen(onOpen: (LibraryBook) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val data = container.data
    val scope = rememberCoroutineScope()
    val colors = CpTheme.colors

    val books by data.library.books().collectAsState(initial = null)
    val recent by data.library.recent(limit = 3).collectAsState(initial = emptyList())
    val percents by data.library.percents().collectAsState(initial = emptyMap())
    var folders by remember { mutableStateOf(data.folders.folders()) }
    var scanning by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var manageFolders by remember { mutableStateOf(false) }

    fun rescan() = scope.launch {
        scanning = true
        val results = runCatching { data.rescanAll() }.getOrDefault(emptyMap())
        scanning = false
        if (results.values.any { !it.complete }) {
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

    // 앱을 열 때마다 한 번 훑는다. 폴더에 새로 넣은 책이 바로 보여야 한다.
    LaunchedEffect(Unit) { if (folders.isNotEmpty()) rescan() }

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
                    items(recent, key = { "r" + it.id.value }) { book ->
                        BookRow(book, percents[book.id], onOpen = onOpen, onUnsupported = { notice = it })
                    }
                    item { Spacer(Modifier.height(8.dp)); CpDivider() }
                }
                item { CpSectionLabel("모든 책") }
                items(list.orEmpty(), key = { it.id.value }) { book ->
                    BookRow(book, percents[book.id], onOpen = onOpen, onUnsupported = { notice = it })
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

    notice?.let { (title, message) ->
        CpPopup(title = title, message = message, onDismiss = { notice = null }) {
            Spacer(Modifier.height(16.dp))
            CpButton("확인", { notice = null })
        }
    }
}

@Composable
private fun BookRow(
    book: LibraryBook,
    percent: Float?,
    onOpen: (LibraryBook) -> Unit,
    onUnsupported: (Pair<String, String?>) -> Unit,
) {
    val supported = book.format != BookFormat.PDF
    val tiles = CpTheme.colors.tiles
    CpListRow(
        title = book.label,
        subtitle = book.author ?: book.format.name,
        icon = when (book.format) {
            BookFormat.EPUB -> CpIcons.Book
            BookFormat.TXT -> CpIcons.Text
            BookFormat.PDF -> CpIcons.Pdf
        },
        // OLO Explorer 와 같은 뜻의 같은 색: TXT·PDF 는 문서(슬레이트). EPUB 은 Explorer 표에
        // 없어 팔레트 3차색(틸)을 쓴다 — 목록에서 가장 흔한 종류가 한눈에 갈려야 한다.
        tile = when (book.format) {
            BookFormat.EPUB -> tiles.book
            BookFormat.TXT, BookFormat.PDF -> tiles.document
        },
        value = when {
            !supported -> "준비 중"
            percent != null -> "${percent.roundToInt()}%"
            else -> null
        },
        enabled = supported,
        onClick = {
            if (supported) onOpen(book)
            else onUnsupported("PDF 는 아직 열 수 없습니다" to "PDF 보기는 다음 판에서 지원합니다. 목록에는 미리 보여 둡니다.")
        },
    )
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

/** 저장된 책 id 로 목록의 책을 찾는다(앱이 다시 켜졌을 때 열던 책으로 돌아가기). */
suspend fun io.github.kgcaudit.reader.data.library.Library.find(id: String): LibraryBook? = get(BookId(id))
