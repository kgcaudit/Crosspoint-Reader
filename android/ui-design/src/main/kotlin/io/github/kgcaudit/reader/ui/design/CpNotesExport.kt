package io.github.kgcaudit.reader.ui.design

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 내보낼 것(0.28.0). 거르개 칩과 달리 "형광펜과 메모" 가 칠 전부다 — 내보낼 때는 메모 달린 칠도 칠이다. */
enum class NoteExportScope(val label: String) {
    All("전체"), Marked("형광펜과 메모"), Memos("메모만");

    fun takes(item: NoteItem): Boolean = when (this) {
        All -> true
        Marked -> !item.isBookmark
        Memos -> item.memo != null
    }

    companion object {
        /** 화면에서 고른 거르개가 처음 값이다. 책갈피 칩에는 맞는 것이 없어 전체로. */
        fun from(filter: NoteFilter): NoteExportScope = when (filter) {
            NoteFilter.All, NoteFilter.Bookmarks -> All
            NoteFilter.Highlights -> Marked
            NoteFilter.Memos -> Memos
        }
    }
}

enum class NoteFormat(val label: String, val hint: String, val extension: String, val mime: String) {
    Text("글", "메모장 · 메신저 · 메일에 그대로", "txt", "text/plain"),
    Markdown("마크다운", "옵시디언 · 노션에서 제목 · 인용이 살아 있음", "md", "text/markdown"),
}

/**
 * 마크다운 내보내기(0.28.0). 장은 `##`, 칠한 글은 인용(`>`), 책갈피는 목록 한 줄.
 *
 * 책 글은 [markdownEscape] 로 감싼다 — 본문의 `*강조*` · 줄머리 `#` · `1.` 이 그대로 들어가면 받는 앱이 제목 · 목록 ·
 * 기울임으로 바꿔 인용한 글이 원문과 달라진다.
 */
fun exportMarkdown(title: String, author: String?, items: List<NoteItem>, nowEpochMs: Long = System.currentTimeMillis()): String = buildString {
    append("# ").append(markdownEscape(title)).append("\n")
    val date = SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date(nowEpochMs))
    append(listOfNotNull(author?.takeIf { it.isNotBlank() }?.let(::markdownEscape), "독서노트 ${items.size}개", date).joinToString(" · ")).append("\n")
    var section: String? = null
    for (item in items) {
        if (item.section != section) {
            section = item.section
            append("\n## ").append(markdownEscape(item.section)).append("\n")
        }
        append('\n')
        if (item.isBookmark) {
            append("- 책갈피 · ").append(item.where).append(" — ").append(markdownEscape(item.text.lines().joinToString(" "))).append('\n')
            continue
        }
        item.text.lines().forEach { append("> ").append(markdownEscape(it)).append('\n') }
        append('\n').append(item.pen!!.label).append(" · ").append(item.where).append('\n')
        item.memo?.let { memo ->
            // 여러 줄 메모는 줄 끝에 두 칸을 붙인다. 없으면 마크다운이 한 줄로 이어 붙여 사용자가 나눈 줄이 사라진다.
            append("**메모** ").append(memo.lines().joinToString("  \n") { markdownEscape(it) }).append('\n')
        }
    }
}

/** 마크다운이 서식으로 읽는 글자를 글자 그대로 읽게 한다. */
fun markdownEscape(line: String): String {
    val inline = buildString {
        for (ch in line) {
            if (ch in "\\`*_[]<>|") append('\\')
            append(ch)
        }
    }
    // 줄머리에서만 서식이 되는 것: 제목(#) · 목록(- + 1.) · 인용(>, 위에서 이미 감쌈).
    return when {
        Regex("^\\s*[#+=-]").containsMatchIn(inline) -> inline.replaceFirst(Regex("^(\\s*)([#+=-])"), "$1\\\\$2")
        Regex("^\\s*\\d+[.)]").containsMatchIn(inline) -> inline.replaceFirst(Regex("^(\\s*\\d+)([.)])"), "$1\\\\$2")
        else -> inline
    }
}

/** 파일 이름에 쓸 수 없는 글자를 뺀다. 책 제목의 "/" 가 폴더로 읽혀 저장이 실패했다. */
fun exportFileName(title: String, format: NoteFormat): String {
    val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "책" }
    return "$safe 독서노트.${format.extension}"
}

/**
 * 독서노트 내보내기 화면(0.28.0, 구상안 가 확정): 담을 것 · 모양을 고르고 미리 본 뒤 보내거나 파일로 저장한다.
 * EPUB · PDF 리더가 같이 쓴다.
 *
 * @param initial 독서노트 화면에서 고른 거르개. 사용자가 이미 "메모" 만 보고 있었으면 메모만 내보내려는 것이다.
 */
@Composable
fun CpNotesExport(
    title: String,
    author: String?,
    items: List<NoteItem>,
    initial: NoteFilter,
    onBack: () -> Unit,
    nowEpochMs: Long = remember { System.currentTimeMillis() },
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = CpTheme.colors
    // 칠이 하나도 없는 갈래(옛 휴대폰의 PDF 는 책갈피뿐)는 고를 수 없게 아예 보이지 않는다 — 고르면 빈 파일이 된다.
    val scopes = NoteExportScope.entries.filter { s -> s == NoteExportScope.All || items.any(s::takes) }
    var chosen by rememberSaveable { mutableStateOf(NoteExportScope.from(initial).takeIf { it in scopes } ?: NoteExportScope.All) }
    var format by rememberSaveable { mutableStateOf(NoteFormat.Markdown) }
    var toast by remember { mutableStateOf<String?>(null) }
    val picked = items.filter(chosen::takes)
    val text = when (format) {
        NoteFormat.Text -> exportNotes(title, author, picked)
        NoteFormat.Markdown -> exportMarkdown(title, author, picked, nowEpochMs)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            toast = if (ok) "파일로 저장했습니다" else "저장하지 못했습니다. 다른 곳을 골라 보세요."
        }
    }

    Box {
        CpFullScreen {
            CpHeader("독서노트 내보내기", subtitle = title, onBack = onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                CpSectionLabel("담을 것")
                scopes.forEach { s ->
                    val count = items.count(s::takes)
                    val detail = if (s == NoteExportScope.All) allDetail(items) else "${count}개"
                    CpRadioRow(s.label, s == chosen, { chosen = s }, subtitle = detail)
                }
                CpDivider()
                CpSectionLabel("모양")
                NoteFormat.entries.forEach { f -> CpRadioRow(f.label, f == format, { format = f }, subtitle = f.hint) }
                CpDivider()
                CpSectionLabel("미리 보기")
                Box(
                    Modifier.padding(horizontal = CpTheme.metrics.gutter).fillMaxWidth()
                        .clip(RoundedCornerShape(CpTheme.metrics.cornerSmall))
                        .background(c.surface)
                        .border(1.dp, c.outline, RoundedCornerShape(CpTheme.metrics.cornerSmall))
                        .padding(12.dp),
                ) {
                    CpText(text, CpTheme.type.caption.copy(lineHeight = 19.sp), c.text, maxLines = PREVIEW_LINES)
                }
                Spacer(Modifier.padding(bottom = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(CpTheme.metrics.gutter), horizontalArrangement = Arrangement.End) {
                CpButton("파일로 저장", { save.launch(exportFileName(title, format)) }, primary = false)
                Spacer(Modifier.width(10.dp))
                CpButton("보내기", { send(context, title, text) })
            }
        }
        CpToast(toast, { toast = null }, Modifier.align(Alignment.BottomCenter), durationMs = 2_500)
    }
}

private const val PREVIEW_LINES = 12

/** "4개 · 책갈피 1 · 형광펜 1 · 메모 2" — 거르개 칩과 같은 갈래로 센다. */
private fun allDetail(items: List<NoteItem>): String {
    val parts = listOf(NoteFilter.Bookmarks, NoteFilter.Highlights, NoteFilter.Memos)
        .map { f -> f to items.count { f.shows(it) } }
        .filter { it.second > 0 }
        .joinToString(" · ") { (f, n) -> "${f.label} $n" }
    return listOf("${items.size}개", parts).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun send(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        // 메일 앱은 제목 칸을 이것으로 채운다.
        .putExtra(Intent.EXTRA_SUBJECT, "$title 독서노트")
    runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
