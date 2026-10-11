package io.github.kgcaudit.reader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.kgcaudit.reader.data.TimeItem
import io.github.kgcaudit.reader.data.TimeMode
import io.github.kgcaudit.reader.data.library.ComicLibrary
import io.github.kgcaudit.reader.document.comic.ComicView
import io.github.kgcaudit.reader.document.comic.Webtoon
import io.github.kgcaudit.reader.document.comic.Work
import io.github.kgcaudit.reader.document.image.ImageSize
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.ScreenPrefs
import io.github.kgcaudit.reader.ui.design.rememberScrollTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 넘겨받은 압축을 열었더니 만화가 아니었다. [message] 를 그대로 보인다 — "손상됐습니다" 와 섞으면 멀쩡한 문서 zip 을 다시 받으러 간다.
 */
class NotAComicException(message: String) : Exception(message)

/**
 * 다른 앱이 "연결 프로그램" 으로 넘긴 만화를 서재 밖에서 본다. 받은 책([IncomingFile])과 같은 규칙: 서재에 넣지 않고(읽기 허락은 이
 * 화면이 떠 있는 동안만이라, 서재에 올렸다가 나중에 누르면 열리지 않는다) 읽던 자리 · 책갈피는 받은 URI 를 열쇠로 만화 표에 적는다.
 * 만화 표는 서재 단위와 이어 두지 않아(외래 키 없음) URI 열쇠의 행도 받는다 — 서재 · 위젯 · 백업은 서재 단위와 맞춰 읽으므로 이
 * 행들은 거기에 끼지 않는다.
 *
 * 작품 화면 · 다음 권이 없다(한 권짜리). 넘기는 방향 · 보는 방식은 이 파일만의 "작품" 열쇠([workKey])로 적는다 — 따로 뺀 작품과 같은
 * 머리를 써서 백업에 담기지 않는다(이 휴대폰의 문서 주소라 다른 휴대폰에서는 붙을 곳이 없다).
 *
 * 서재에 같은 권(이름 · 크기)이 있으면 이 화면이 아니라 서재의 권으로 연다(MainActivity) — 여기는 서재에 없는 파일만 온다.
 */
@Composable
fun IncomingComicHost(
    file: IncomingComic,
    prefs: ScreenPrefs,
    onPrefsChange: (ScreenPrefs) -> Unit,
    onClose: () -> Unit,
    onChrome: (Boolean) -> Unit,
    onFail: (String) -> Unit,
) {
    val container = LocalContext.current.container
    val data = container.data
    val scope = rememberCoroutineScope()
    val id = file.uri.toString()
    val key = workKey(id)
    var book by remember(file) { mutableStateOf<ComicBook?>(null) }
    var sizes by remember(file) { mutableStateOf<List<ImageSize?>>(emptyList()) }
    var position by remember(file) { mutableStateOf(0 to 0f) }
    // 사람이 고른 넘기는 방향 · 보는 방식. null 은 고르지 않음 — ComicInfo · 쪽 모양을 따른다(규칙 5).
    var chosenRtl by remember(file) { mutableStateOf<Boolean?>(null) }
    var chosenView by remember(file) { mutableStateOf<ComicView?>(null) }
    LaunchedEffect(file) {
        // 연 책. 크기를 읽다 실패하거나 여는 사이 화면을 떠나 취소되면 여기서 닫는다 — 두면 압축 · 파일 손잡이가 열린 채 남는다.
        var made: ComicBook? = null
        runCatching {
            withContext(Dispatchers.IO) {
                val comic = container.openIncomingComic(file).also { made = it }
                sizes = comic.sizes()
                val p = data.comics.progressOf(id)
                position = when {
                    // 다 읽고 끝에 멈춘 권을 다시 열면 처음부터 — 서재의 만화와 같다(끝에서 열면 곧바로 "다 읽었습니다" 판이 뜬다).
                    p == null || (p.finished && p.page >= comic.pageCount - 1) -> 0 to 0f
                    else -> p.page.coerceIn(0, (comic.pageCount - 1).coerceAtLeast(0)) to (p.offset ?: 0f)
                }
                val (rtl, view) = data.comics.workChoices(key)
                chosenRtl = rtl
                chosenView = view
                comic
            }
        }.onSuccess { book = it }.onFailure {
            made?.close()
            if (it is kotlinx.coroutines.CancellationException) throw it
            android.util.Log.w("OloComic", "cannot open incoming ${file.displayName}", it)
            onFail(describeComicFailure(it))
        }
    }
    DisposableEffect(file) { onDispose { book?.close() } }
    val bookmarks by remember(id) { data.comics.bookmarks(id) }.collectAsState(initial = emptyList())
    val opened = book
    if (opened == null) {
        Box(Modifier.fillMaxSize().background(COMIC_BACKDROP))
        return
    }
    val title = file.displayName.substringBeforeLast('.').ifBlank { file.displayName }
    val info = opened.unit.info
    val rtl = chosenRtl ?: info?.rightToLeft ?: false
    // 만화 뷰어는 작품으로 방향 · 보는 방식을 안다. 이 파일은 한 권짜리 작품이다(다음 권 · 회차 목록이 없다).
    val work = remember(title, rtl, chosenView) {
        Work(key, title, emptyList(), webtoon = false, complete = false, places = emptyList(), rightToLeft = rtl, view = chosenView)
    }
    val now = { System.currentTimeMillis() }
    // 자리 · 책갈피 쓰기가 실패해도(저장 공간이 가득) 알림만 띄우고 계속 본다 — 서재의 만화와 같다.
    var writeError by remember { mutableIntStateOf(0) }
    val failed: (Exception) -> Unit = { writeError++ }
    val onView: (ComicView?) -> Unit = { v ->
        chosenView = v
        scope.launchWrite(failed) { data.comics.setView(work, v) }
    }
    val scrolled = rememberScrollTime<String> { unit, ms -> container.recordTime(TimeItem.COMIC, unit, TimeMode.READ, ms) }
    Box(Modifier.fillMaxSize()) {
        // 웹툰 판별은 서재의 만화와 같다(고른 값 → ComicInfo → 쪽 모양). 세로로 긴 그림을 쪽 넘김으로 보이면 한 쪽이 손톱만 하게 줄어든다.
        when (Webtoon.view(chosenView, info, sizes)) {
            ComicView.PAGE -> ComicReader(
                book = opened,
                title = title,
                work = work,
                startPage = position.first,
                bookmarks = bookmarks,
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                onPage = { p ->
                    position = p to 0f
                    scope.launchWrite(failed) { data.comics.saveProgress(id, p, opened.pageCount, now()) }
                },
                onBookmark = { p -> scope.launchWrite(failed) { data.comics.toggleBookmark(id, p, now()) } },
                onDirection = { r ->
                    chosenRtl = r
                    scope.launchWrite(failed) { data.comics.setRightToLeft(work, r) }
                },
                onNext = {},
                onClose = onClose,
                onChrome = onChrome,
                view = chosenView,
                onView = onView,
                sizes = sizes,
                onReadTime = { container.recordPage(TimeItem.COMIC, id, it) },
            )
            ComicView.WEBTOON -> WebtoonReader(
                first = remember(opened) { WebtoonEpisode(null, opened, sizes) },
                title = title,
                // 작품을 넘기지 않는다 — 회차 목록 단추 · 경계 띠가 빈 작품을 그리지 않게.
                work = null,
                currentId = id,
                startIndex = position.first,
                startOffset = position.second,
                bookmarks = bookmarks,
                progress = emptyMap(),
                prefs = prefs,
                onPrefsChange = onPrefsChange,
                view = chosenView,
                onView = onView,
                openEpisode = { null },
                onEnter = {},
                onPosition = { e, i, f, end ->
                    scrolled(e.unitId)
                    position = i to f
                    scope.launchWrite(failed) { data.comics.saveProgress(id, i, e.book.pageCount, now(), offset = f, atEnd = end) }
                },
                onBookmark = { _, p -> scope.launchWrite(failed) { data.comics.toggleBookmark(id, p, now()) } },
                onJump = {},
                onClose = onClose,
                onChrome = onChrome,
            )
        }
        CpToast(WRITE_FAILED.takeIf { writeError > 0 }, onDone = { writeError = 0 }, Modifier.align(Alignment.BottomCenter), key = writeError)
    }
}

/** 넘겨받은 만화 한 권의 작품 열쇠. 따로 뺀 작품과 같은 머리라 백업에 담기지 않는다(RecordsBackup.collectWorks). */
internal fun workKey(uri: String): String = ComicLibrary.OWN_PREFIX + uri

/**
 * 넘겨받은 만화를 못 열었을 때 보일 문장. 예외 원문은 로그에만 — "end of central directory not found" 는 읽는 사람에게 뜻이 없다.
 * 서재의 만화와 달리 "서재에서 새로고침" 을 권하지 않는다: 서재에 없는 파일이다.
 */
internal fun describeComicFailure(error: Throwable): String = when (error) {
    is NotAComicException -> error.message.orEmpty()
    is java.io.FileNotFoundException, is SecurityException -> "파일을 찾을 수 없습니다. 옮겨졌거나 지워졌을 수 있습니다. 파일 앱에서 다시 열어 주세요."
    is java.io.IOException -> "만화 파일이 손상됐거나 압축 파일이 아닙니다. 다른 곳에서 다시 받아 보세요."
    else -> "만화를 여는 중 문제가 생겼습니다."
}
