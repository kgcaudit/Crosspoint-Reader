package io.github.kgcaudit.reader.reflow

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.text.FontCatalog
import io.github.kgcaudit.reader.text.DownloadFailure
import io.github.kgcaudit.reader.text.FontDownloader
import io.github.kgcaudit.reader.text.FontOption
import io.github.kgcaudit.reader.text.RecommendedFonts
import io.github.kgcaudit.reader.text.UserFonts.ImportResult
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpRadioRow
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 글꼴 고르기 · 넣기 · 빼기. 보기 판의 "글꼴 ›" 에서 전체 화면으로 연다.
 *
 * 구성은 삼성 설정 › 글꼴 스타일과 같다: 동그라미 목록, 이름은 그 글꼴로, 맨 아래 "＋". 사람들이
 * 이미 아는 화면이라 설명이 필요 없다.
 *
 * @param onFontsChanged 글꼴을 넣거나 뺐을 때. 같은 가족에 굵은 파일이 더해지면 설정 값은 그대로인데
 *   조판이 달라지므로(글꼴 ID 가 바뀐다) 화면이 글꼴 ID 를 다시 재게 해야 한다.
 */
/** 이 책에 든 글꼴. [preview] 는 이름을 그릴 서체(아직 꺼내기 전이면 null). */
internal class PublisherFonts(val preview: android.graphics.Typeface?)

internal const val PUBLISHER_LABEL = "출판사 글꼴"

@Composable
internal fun FontsPanel(
    catalog: FontCatalog,
    /** 책에 글꼴이 들어 있으면. 없는 책에서는 이 줄을 보이지 않는다(골라도 아무것도 바뀌지 않는다). */
    publisher: PublisherFonts?,
    prefs: ReaderPrefs,
    onPrefsChange: (ReaderPrefs) -> Unit,
    onFontsChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = CpTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    val options = remember(revision) { catalog.options() }
    // 출판사 글꼴이 켜져 있으면 그 줄에만 불이 들어온다. 본문 글꼴 줄까지 켜 두면 "둘 다 쓰인다"
    // 는 사실(책이 정하지 않은 곳은 본문 글꼴)이 오히려 "무엇을 골랐나" 를 흐린다.
    val publisherOn = publisher != null && prefs.publisherFonts
    val current = if (publisherOn) null else catalog.effectiveKey(prefs.font)
    // 진행 중인 일의 이름(글꼴 넣기·받기). null 이면 한가하다.
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    var removing by remember { mutableStateOf<FontOption?>(null) }

    fun changed() {
        revision++
        onFontsChanged()
    }

    // 종류를 "*/*" 로 연다. 폰트의 MIME 은 파일 관리자마다 제각각이라(.otf 를 OpenDocument
    // 수식 서식으로 아는 곳도 있다) 좁히면 진짜 폰트가 회색으로 눌리지 않는다. 폰트가 아닌
    // 파일은 넣을 때 이유와 함께 거절한다.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val user = catalog.user
        if (uri == null || user == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "글꼴을 넣는 중…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { user.import(it) }
                        ?: ImportResult.Rejected(ImportResult.Reason.Unreadable)
                }.getOrElse { e ->
                    Log.w(TAG, "font import failed: $uri", e)
                    ImportResult.Rejected(ImportResult.Reason.Unreadable)
                }
            }
            busy = null
            when (result) {
                is ImportResult.Added -> {
                    changed()
                    // 넣은 글꼴로 바로 바꾼다. 넣고 나서 다시 찾아 누르게 하면 한 번 더 헤맨다.
                    result.families.firstOrNull()?.let { onPrefsChange(prefs.copy(font = it.key, publisherFonts = false)) }
                    if (result.withoutHangul) {
                        notice = "한글이 없는 글꼴입니다" to "영문·숫자는 이 글꼴로, 한글은 휴대폰 글꼴로 보입니다."
                    }
                }
                is ImportResult.Rejected -> notice = "글꼴을 넣지 못했습니다" to describe(result.reason)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .clickable(indication = null, interactionSource = null) {},
    ) {
        CpHeader(title = "글꼴", onBack = onBack)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            val system = options.filter { it.kind == FontOption.Kind.System }
            val user = options.filter { it.kind == FontOption.Kind.User }
            if (publisher != null) {
                item(key = "publisher") {
                    val family = remember(publisher.preview) { publisher.preview?.let { FontFamily(it) } }
                    CpRadioRow(
                        title = PUBLISHER_LABEL,
                        selected = publisherOn,
                        onClick = { onPrefsChange(prefs.copy(publisherFonts = true)) },
                        subtitle = "이 책에 든 글꼴 · 책이 정하지 않은 곳은 아래 고른 글꼴",
                        titleStyle = if (family != null) CpTheme.type.body.copy(fontFamily = family) else CpTheme.type.body,
                    )
                }
            }
            items(system, key = { it.key }) { option ->
                FontRow(catalog, option, option.key == current, subtitle = systemNote(option)) {
                    onPrefsChange(prefs.copy(font = option.key, publisherFonts = false))
                }
            }
            if (user.isNotEmpty()) item { CpSectionLabel("추가한 글꼴") }
            items(user, key = { it.key }) { option ->
                FontRow(
                    catalog,
                    option,
                    option.key == current,
                    subtitle = if (option.hasHangul) null else "한글 없음 · 한글은 휴대폰 글꼴로 보입니다",
                    onRemove = { removing = option },
                ) { onPrefsChange(prefs.copy(font = option.key, publisherFonts = false)) }
            }
            val downloads = catalog.downloads
            val offered = if (downloads == null) emptyList() else RecommendedFonts.all.filterNot(downloads::isInstalled)
            if (offered.isNotEmpty()) {
                item { CpSectionLabel("받을 수 있는 글꼴 · Google Fonts") }
                items(offered, key = { "get:" + it.family }) { font ->
                    CpListRow(
                        title = font.label,
                        subtitle = (if (font.serif) "명조" else "고딕") + (if (700 in font.weights) " · 보통·굵게" else " · 보통"),
                        icon = CpIcons.Download,
                        onClick = {
                            scope.launch {
                                busy = "‘${font.label}’ 받는 중…"
                                val result = withContext(Dispatchers.IO) { downloads!!.download(font) }
                                busy = null
                                when (result) {
                                    is FontDownloader.Result.Done -> {
                                        changed()
                                        onPrefsChange(prefs.copy(font = result.key, publisherFonts = false))
                                    }
                                    is FontDownloader.Result.Failed -> notice = "글꼴을 받지 못했습니다" to describe(result.reason)
                                }
                            }
                        },
                    )
                }
            }
            if (catalog.user != null) {
                item {
                    Spacer(Modifier.height(8.dp))
                    CpListRow(
                        title = "글꼴 추가",
                        subtitle = "TTF · OTF · TTC 파일",
                        icon = CpIcons.Plus,
                        onClick = { picker.launch(arrayOf("*/*")) },
                    )
                }
            }
        }
    }

    busy?.let { CpPopup(title = it, progress = null) }
    notice?.let { (title, message) ->
        CpPopup(title = title, message = message, onDismiss = { notice = null }) {
            Spacer(Modifier.height(16.dp))
            CpButton("확인", { notice = null })
        }
    }
    removing?.let { option ->
        CpPopup(
            title = "‘${option.label}’ 글꼴을 뺄까요?",
            message = "이 글꼴로 보던 책은 기본 글꼴로 바뀝니다. 파일은 언제든 다시 넣을 수 있습니다.",
            onDismiss = { removing = null },
        ) {
            Spacer(Modifier.height(16.dp))
            Row {
                CpButton("취소", { removing = null }, Modifier.weight(1f), primary = false)
                Spacer(Modifier.width(12.dp))
                CpButton("빼기", {
                    removing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { catalog.user?.remove(option.key) }
                        // 지운 글꼴을 설정에 남기면 글꼴 ID 가 기본 글꼴과 갈라진 채 캐시에 들어간다.
                        if (prefs.font == option.key) onPrefsChange(prefs.copy(font = null))
                        changed()
                    }
                }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FontRow(
    catalog: FontCatalog,
    option: FontOption,
    selected: Boolean,
    subtitle: String?,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val family = remember(option.key) { FontFamily(catalog.typeface(option.key)) }
    CpRadioRow(
        title = option.label,
        selected = selected,
        onClick = onClick,
        subtitle = subtitle,
        titleStyle = CpTheme.type.body.copy(fontFamily = family),
    ) {
        if (onRemove != null) {
            CpIconButton(CpIcons.Close, "${option.label} 빼기", onRemove, tint = CpTheme.colors.textMuted)
        }
    }
}

private fun systemNote(option: FontOption): String? = when (option.key) {
    FontCatalog.SANS -> "휴대폰 설정의 글꼴 스타일을 따릅니다"
    else -> null
}

/** 거절 이유를 사람의 말로. 무엇을 하면 되는지까지 말한다. */
internal fun describe(reason: ImportResult.Reason): String = when (reason) {
    ImportResult.Reason.NotAFont -> "글꼴 파일이 아닙니다. TTF · OTF · TTC 파일을 골라 주세요."
    ImportResult.Reason.WebFont -> "웹 글꼴(WOFF)은 넣을 수 없습니다. 같은 글꼴의 TTF 나 OTF 파일을 골라 주세요."
    ImportResult.Reason.Broken -> "글꼴 파일이 손상됐습니다. 덜 받아졌을 수 있으니 다시 받아 보세요."
    ImportResult.Reason.TooLarge -> "글꼴 파일이 너무 큽니다. 64MB 이하만 넣을 수 있습니다."
    ImportResult.Reason.Unreadable -> "파일을 읽지 못했습니다. 저장소가 연결돼 있는지 확인해 보세요."
}

/** 받지 못한 이유를 사람의 말로. */
internal fun describe(reason: DownloadFailure): String = when (reason) {
    DownloadFailure.NoProvider -> "이 기기에서는 Google Play 서비스로 글꼴을 받을 수 없습니다. 글꼴 파일(TTF·OTF)을 직접 추가해 주세요."
    DownloadFailure.Network -> "인터넷 연결을 확인하고 다시 시도해 주세요."
    DownloadFailure.NotFound -> "이 글꼴을 찾지 못했습니다. 잠시 뒤 다시 시도해 주세요."
    DownloadFailure.Broken -> "받은 파일을 글꼴로 읽을 수 없습니다. 다시 시도해 주세요."
}

private const val TAG = "OloFonts"
