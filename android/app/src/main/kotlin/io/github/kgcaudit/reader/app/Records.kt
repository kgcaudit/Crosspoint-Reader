package io.github.kgcaudit.reader.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.data.backup.ImportPlan
import io.github.kgcaudit.reader.data.backup.ImportResult
import io.github.kgcaudit.reader.data.backup.RecordsBackup
import io.github.kgcaudit.reader.data.backup.RecordsSummary
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 마지막으로 백업 파일을 만든 때. 기기에만 두는 표시라 백업 파일에는 넣지 않는다. */
internal class LastBackupStore(context: Context) {
    private val sp = context.getSharedPreferences("records", Context.MODE_PRIVATE)
    fun load(): Long? = sp.getLong(KEY, 0L).takeIf { it > 0 }
    fun save(at: Long) = sp.edit().putLong(KEY, at).apply()
    private companion object { const val KEY = "lastBackupAt" }
}

/** "9월 30일", 올해가 아니면 "2025년 9월 30일". */
// 이름이 서재의 monthDay(연도 없음)와 같던 때(0.47.0 까지)는 인자 하나로 부르면 그쪽이 골라져, 지난해 백업에도 연도가 안
// 붙었다. 이름을 달리해 섞이지 않게 한다.
internal fun backupDate(epochMs: Long, today: LocalDate = LocalDate.now()): String {
    val d = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (d.year == today.year) "${d.monthValue}월 ${d.dayOfMonth}일" else "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
}

/** 백업 파일 이름. 날짜가 들어가야 여러 벌 가운데 최근 것을 고른다. */
internal fun backupFileName(today: LocalDate = LocalDate.now()): String = "OLO eBook 읽기 기록 $today.json"

internal fun summaryLine(s: RecordsSummary): String = when {
    s.books > 0 || s.comics > 0 -> "${volumes(s.books, s.comics)} · 책갈피 ${s.bookmarks}개 · 형광펜 · 메모 ${s.annotations}개"
    // 작품 설정(이름 · 방향 · 보는 방식)만 있어도 백업할 것이 있다 — "기록이 없습니다" 라고 하면서 백업 단추가 켜져 있으면
    // 무엇을 담는지 알 수 없다.
    s.works > 0 -> "작품 설정 ${s.works}개"
    else -> "아직 기록이 없습니다"
}

/**
 * "책 3권 · 만화 2권". 만화 기록(0.49.0)이 없으면 예전 그대로 "책 3권" — 만화를 읽지 않는 사람에게 "만화 0권" 을 보이지 않는다.
 * 책이 없고 만화만 있으면 "만화 2권".
 */
internal fun volumes(books: Int, comics: Int): String =
    listOfNotNull("책 ${books}권".takeIf { books > 0 || comics == 0 }, "만화 ${comics}권".takeIf { comics > 0 }).joinToString(" · ")

/**
 * 백업 파일 하나에 든 것: "책 3권 · 만화 2권", 작품 설정이 있으면 " · 작품 설정 4개". 작품 설정만 든 파일(만화 이름 · 방향만
 * 고쳐 두고 아직 펼친 권이 없음)은 "작품 설정 4개" — 0.49.0 에는 "책 0권의 기록입니다" 라고 해 빈 파일처럼 읽혔다.
 */
internal fun backupContents(books: Int, comics: Int, works: Int): String =
    listOfNotNull(volumes(books, comics).takeIf { books > 0 || comics > 0 || works == 0 }, "작품 설정 ${works}개".takeIf { works > 0 })
        .joinToString(" · ")

/**
 * "백업 파일 만들기" 를 누를 수 있는가: 담을 것이 있을 때. 만화만 읽은 사람도 만들 수 있어야 한다 — 0.49.0 은 책 수만 보아
 * 만화 기록이 있어도 줄이 흐렸다. 작품 설정만 가진 사람도 같다 — 권 수만 보면 고쳐 둔 작품 이름 · 방향을 옮길 길이 없다.
 */
internal fun canBackUp(s: RecordsSummary?): Boolean = s != null && (s.books > 0 || s.comics > 0 || s.works > 0)

/** 가져오기 · 만들기의 판. 한 번에 하나만 뜬다. */
internal sealed interface RecordsPopup {
    class Preview(val plan: ImportPlan) : RecordsPopup
    /** [works] 는 가져온 작품 설정 수 — 작품 설정은 이름으로 붙어 늘 다 들어간다(ImportResult 에는 수가 없다). */
    class Done(val result: ImportResult, val works: Int = 0) : RecordsPopup
    class NotBackup(val name: String) : RecordsPopup
    class Failed(val title: String) : RecordsPopup
}

/**
 * 앱 정보 안 "읽기 기록" 묶음의 상태(0.27.0, 사용자 결정: 가안). 자주 쓰지 않는 일이라 홈 머리에 단추를 두지 않는다.
 *
 * 행([ReadingRecordsRows])과 판([ReadingRecordsPopups])을 나눠 그린다 — 판을 행 옆에 두면 목록 사이에 끼어 그려져
 * 화면을 덮지 못한다.
 */
internal class RecordsUi(
    val records: RecordsBackup,
    val lastBackup: LastBackupStore,
    /**
     * 만들기 · 가져오기를 돌리는 범위. 앱 화면 전체의 것을 받는다 — 앱 정보 화면의 범위에서 돌리면 가져오는 중에 뒤로
     * 가는 순간 일이 끊겨, 결과 판이 뜨지 않고 반쯤 쓴 0바이트 백업 파일이 남았다.
     */
    val scope: CoroutineScope,
) {
    /** 만드는 중 · 가져오는 중. 이 동안은 판이 떠 있어 다른 것을 누를 수 없다. */
    var busy by mutableStateOf<String?>(null)
    var summary by mutableStateOf<RecordsSummary?>(null)
    var last by mutableStateOf(lastBackup.load())
    var popup by mutableStateOf<RecordsPopup?>(null)
    var toast by mutableStateOf<String?>(null)
    var refresh by mutableIntStateOf(0)
}

@Composable
internal fun ReadingRecordsRows(ui: RecordsUi) {
    val context = LocalContext.current
    val scope = ui.scope
    val records = ui.records
    LaunchedEffect(ui.refresh) { ui.summary = withContext(Dispatchers.IO) { records.summary() } }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ui.busy = "백업 파일을 만드는 중…"
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { records.export(it) } }.getOrNull()
            }
            ui.busy = null
            if (made == null) {
                ui.popup = RecordsPopup.Failed("백업 파일을 만들지 못했습니다")
            } else {
                val now = System.currentTimeMillis()
                ui.lastBackup.save(now)
                ui.last = now
                ui.toast = "백업 파일을 만들었습니다 · ${volumes(made.books, made.comics)}"
            }
        }
    }
    // 종류를 거르지 않는다(*/*). 파일 관리자 · 드라이브마다 .json 을 다른 종류로 알려, 거르면 백업 파일이 흐리게 보여 못 고른다.
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ui.busy = "백업 파일을 읽는 중…"
        scope.launch {
            // 이름 묻기도 입출력 스레드에서 한다. 드라이브 같은 제공자는 대답이 느려 화면이 멈췄다.
            val popup = withContext(Dispatchers.IO) {
                val plan = runCatching { context.contentResolver.openInputStream(uri)?.use { records.read(it) }?.let { records.plan(it) } }.getOrNull()
                plan?.let { RecordsPopup.Preview(it) } ?: RecordsPopup.NotBackup(Incoming.describe(uri, context.contentResolver).first)
            }
            ui.busy = null
            ui.popup = popup
        }
    }

    CpSectionLabel("읽기 기록")
    val s = ui.summary
    val can = canBackUp(s)
    CpListRow(
        "백업 파일 만들기",
        // 담을 것이 없으면 빈 파일을 만들게 두지 않는다. 흐린 줄도 눌리므로(CpListRow) 여기서 막고 까닭을 알린다 — 0.49.0 까지는
        // 흐리게만 하고 눌러 빈 백업 파일이 만들어졌다.
        { if (can) create.launchOr(backupFileName()) { ui.popup = RecordsPopup.Failed(NO_PICKER) } else ui.toast = "아직 담을 읽기 기록이 없습니다" },
        subtitle = s?.let(::summaryLine) ?: " ",
        enabled = can,
    )
    CpListRow(
        "백업 파일에서 가져오기",
        { open.launchOr(arrayOf("*/*")) { ui.popup = RecordsPopup.Failed(NO_PICKER) } },
        subtitle = ui.last?.let { "마지막 백업 ${backupDate(it)}" } ?: "다른 휴대폰에서 만든 백업 파일을 고릅니다",
    )
}

@Composable
internal fun ReadingRecordsPopups(ui: RecordsUi) {
    val scope = ui.scope
    ui.busy?.let { what ->
        // 도는 동안 뒤로 가기를 받아 둔다(아무것도 하지 않음). 앱 정보가 닫혀도 일은 끝나지만, 결과 판을 볼 자리가 없어진다.
        BackHandler { }
        CpPopup(title = what, message = "잠시만 기다려 주세요. 책이 많으면 몇 초 걸립니다.", onDismiss = null)
        return
    }
    val shown = ui.popup ?: return
    val close = { ui.popup = null }
    // 판이 떠 있을 때 뒤로 가기는 판만 닫는다. 앱 정보가 닫히면 판이 남아 있다가 다음에 열 때 다시 뜬다.
    BackHandler(onBack = close)
    when (shown) {
        is RecordsPopup.Preview -> PreviewPopup(shown.plan, close) {
            ui.popup = null
            ui.busy = "기록을 가져오는 중…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ui.records.apply(shown.plan) }.getOrNull() }
                ui.busy = null
                ui.popup = result?.let { RecordsPopup.Done(it, works = shown.plan.file.works.size) } ?: RecordsPopup.Failed("기록을 가져오지 못했습니다")
                ui.refresh++
            }
        }
        is RecordsPopup.Done -> DonePopup(shown.result, shown.works, close)
        is RecordsPopup.NotBackup -> CpPopup(
            title = "백업 파일이 아닙니다",
            message = "고른 파일(${shown.name})은 OLO eBook 백업 파일이 아니어서 아무것도 바꾸지 않았습니다. " +
                "이름이 ‘OLO eBook 읽기 기록’으로 시작하는 파일을 골라 주세요.",
            onDismiss = close,
        ) { OkButton(close) }
        is RecordsPopup.Failed -> CpPopup(
            title = shown.title,
            message = "파일을 읽거나 쓰지 못했습니다. 다른 곳을 골라 다시 해 주세요.",
            onDismiss = close,
        ) { OkButton(close) }
    }
}

@Composable
private fun OkButton(onClick: () -> Unit) {
    CpPopupButtons { CpButton("확인", onClick) }
}

@Composable
private fun PreviewPopup(plan: ImportPlan, onCancel: () -> Unit, onImport: () -> Unit) {
    val c = CpTheme.colors
    val file = plan.file
    if (file.books.isEmpty() && file.comics.isEmpty() && file.works.isEmpty()) {
        CpPopup(title = "백업 파일에 기록이 없습니다", message = "가져올 읽기 기록이 없어 아무것도 바꾸지 않았습니다.", onDismiss = onCancel) { OkButton(onCancel) }
        return
    }
    val made = if (file.createdAtEpochMs > 0) "${backupDate(file.createdAtEpochMs)}에 만든 백업 · " else ""
    CpPopup(
        title = "백업 파일에서 가져오기",
        message = "${made}${backupContents(file.books.size, file.comics.size, file.works.size)}의 기록입니다. 이 휴대폰의 기록과 합칩니다. 읽은 자리는 더 많이 읽은 쪽을 따르고, 책갈피 · 형광펜 · 메모는 양쪽 것을 모두 남깁니다.",
        onDismiss = onCancel,
    ) {
        // 작품 설정만 든 파일에는 찾을 책이 없다 — "찾은 책 0권" 은 아무것도 못 가져오는 것처럼 읽힌다.
        if (file.books.isNotEmpty() || file.comics.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            CpText("이 휴대폰에서 찾은 ${volumes(plan.foundBooks, plan.foundComics)}", CpTheme.type.body, c.text)
        }
        if (plan.missing.isNotEmpty() || plan.comicsMissing.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            CpText("아직 못 찾은 ${volumes(plan.missing.size, plan.comicsMissing.size)}", CpTheme.type.body, c.text)
            CpText("책 폴더를 추가하면 그때 이어집니다", CpTheme.type.caption, c.textMuted)
        }
        CpPopupButtons {
            CpButton("취소", onCancel, primary = false)
            CpButton("가져오기", onImport)
        }
    }
}

@Composable
private fun DonePopup(result: ImportResult, works: Int, onClose: () -> Unit) {
    val c = CpTheme.colors
    val counts = buildList {
        add("책갈피 ${result.bookmarks}개")
        add("형광펜 · 메모 ${result.annotations}개")
        if (result.finished > 0) add("읽은 책 ${result.finished}권")
    }.joinToString(" · ")
    val waiting = if (result.missing.isEmpty()) "" else "못 찾은 ${result.missing.size}권의 기록은 기억해 두었다가 책 폴더를 추가하면 이어 붙입니다."
    val found = result.books > 0 || result.comics > 0
    CpPopup(
        title = doneTitle(result, works),
        message = when {
            found -> "$counts. $waiting".trim()
            works > 0 -> "고쳐 둔 작품 이름 · 넘기는 방향 · 보는 방식이 그 작품에 붙었습니다. $waiting".trim()
            else -> waiting
        },
        onDismiss = onClose,
    ) {
        if (result.missing.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CpText("못 찾은 책", CpTheme.type.caption, c.textMuted)
            // 수백 권이면 판이 화면을 넘는다 — 앞의 몇 권과 나머지 수만 보인다.
            result.missing.take(MISSING_SHOWN).forEach { CpText(it, CpTheme.type.subtitle, c.text, Modifier.padding(top = 4.dp)) }
            if (result.missing.size > MISSING_SHOWN) {
                CpText("그 밖에 ${result.missing.size - MISSING_SHOWN}권", CpTheme.type.subtitle, c.textMuted, Modifier.padding(top = 4.dp))
            }
        }
        OkButton(onClose)
    }
}

private const val MISSING_SHOWN = 5

/**
 * 가져오기 끝 판의 제목. 찾은 책 · 만화가 없어도 작품 설정은 붙었으면 그것을 말한다 — 작품 설정만 든 백업을 가져오면
 * 0.49.0 은 "이 휴대폰에서 찾은 책이 없습니다" 라고 해, 고친 이름 · 방향이 들어왔는데도 실패한 것처럼 보였다.
 */
internal fun doneTitle(result: ImportResult, works: Int): String = when {
    result.books > 0 || result.comics > 0 -> "${volumes(result.books, result.comics)}의 기록을 가져왔습니다"
    works > 0 -> "작품 설정 ${works}개를 가져왔습니다"
    else -> "이 휴대폰에서 찾은 책이 없습니다"
}
