package io.github.kgcaudit.reader.ui.design

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ── 책갈피 리본 · 짧은 안내 ─────────────────────────────────────────

/**
 * 책갈피가 꽂힌 쪽의 표시: 위에서 내려온 띠, 아래 끝이 V 로 파였다(22×30dp).
 *
 * 윗여백(34dp) 안에서 끝나 글자를 가리지 않는다. 오른쪽 위 모서리를 누르면 꽂고 빼는 자리와 같은 곳이다
 * — 표시가 있는 곳을 누르면 빠진다는 것이 따로 설명하지 않아도 읽힌다.
 */
@Composable
fun CpRibbon(modifier: Modifier = Modifier) {
    val color = CpTheme.colors.accent
    Canvas(modifier.size(width = 22.dp, height = 30.dp).semantics { contentDescription = "책갈피 꽂힌 쪽" }) {
        val path = Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
            lineTo(size.width / 2, size.height * 0.72f); lineTo(0f, size.height); close()
        }
        drawPath(path, color)
    }
}

/**
 * 잠깐 떴다 사라지는 안내("책갈피를 꽂았습니다"). [message] 가 null 이 아니면 보이고, [durationMs] 뒤
 * [onDone] 을 부른다. 같은 말을 연달아 띄우려면 부르는 쪽이 [key] 를 바꾼다.
 *
 * 시스템 토스트를 쓰지 않는 이유: 테마(검정 지면)를 따르지 않고, 읽는 동안 숨긴 시스템 바 자리에 떠서
 * 하단 정보를 가린다.
 */
@Composable
fun CpToast(message: String?, onDone: () -> Unit, modifier: Modifier = Modifier, key: Any? = null, durationMs: Long = 1_500) {
    if (message == null) return
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(message, key) {
        delay(durationMs)
        done()
    }
    Box(
        modifier.padding(bottom = 64.dp).clip(RoundedCornerShape(20.dp))
            .background(Color(0xE6302A24)).padding(horizontal = 18.dp, vertical = 10.dp),
    ) { CpText(message, CpTheme.type.label, Color.White) }
}

// ── 하단 정보 ──────────────────────────────────────────────────────

/**
 * 하단 정보가 보일 수 있는 것들. 시각 · 배터리는 [CpReadingFooter] 가 스스로 읽는다.
 *
 * @param page "3 / 12" 처럼 이미 꾸민 쪽 표시(PDF 는 인쇄된 쪽 번호가 앞에 붙는다).
 * @param chapterPagesLeft 이 장(PDF 는 이 목차 항목)에서 지금 쪽 뒤로 남은 쪽 수. 모르면 null.
 */
data class FooterInfo(
    val bookTitle: String,
    val chapterTitle: String?,
    val page: String,
    val percent: Float,
    val chapterPagesLeft: Int?,
    /** 이 장 · 책을 다 읽는 데 남은 분(읽는 속도로 셈). 모르면 null — 빈칸으로 둔다. */
    val chapterMinutesLeft: Int? = null,
    val bookMinutesLeft: Int? = null,
)

/** 하단 한 줄: 진행 막대 + 왼쪽 · 가운데 · 오른쪽. CrossPoint `drawStatusBar` 의 자리. */
@Composable
fun CpReadingFooter(info: FooterInfo, footer: Footer, color: Color, modifier: Modifier = Modifier) {
    val needsClock = FooterItem.Clock in footer.slots || FooterItem.Battery in footer.slots
    val now = if (needsClock) rememberMinuteTick() else 0L
    val context = LocalContext.current
    fun text(item: FooterItem): String = when (item) {
        FooterItem.None -> ""
        FooterItem.BookTitle -> info.bookTitle
        FooterItem.ChapterTitle -> info.chapterTitle.orEmpty()
        FooterItem.Clock -> clockText(context, now)
        FooterItem.Battery -> batteryPercent(context)?.let { "배터리 $it%" }.orEmpty()
        FooterItem.Page -> info.page
        FooterItem.Percent -> "${kotlin.math.round(info.percent).toInt()}%"
        FooterItem.ChapterLeft -> when (val left = info.chapterPagesLeft) {
            null -> ""
            0 -> "이 장 마지막 쪽"
            else -> "이 장 ${left}쪽 남음"
        }
        FooterItem.ChapterTime -> info.chapterMinutesLeft?.let { "이 장 ${minutesText(it)} 남음" }.orEmpty()
        FooterItem.BookTime -> info.bookMinutesLeft?.let { "책 ${minutesText(it)} 남음" }.orEmpty()
    }
    Column(modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter)) {
        CpProgressBar(info.percent / 100f, weight = CpBarWeight.Thin)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // 양쪽 칸이 같은 몫을 가져야 가운데가 화면 가운데에 선다. 제목이 길면 말줄임으로 끊긴다.
            CpText(text(footer.left), CpTheme.type.caption, color, Modifier.weight(1f))
            CpText(text(footer.center), CpTheme.type.caption, color, Modifier.padding(horizontal = 12.dp))
            CpText(text(footer.right), CpTheme.type.caption, color, Modifier.weight(1f), align = TextAlign.End)
        }
    }
}

private val Footer.slots: List<FooterItem> get() = listOf(left, center, right)

/** 4 → "4분", 130 → "2시간 10분", 0 → "1분 미만". */
internal fun minutesText(minutes: Int): String = when {
    minutes <= 0 -> "1분 미만"
    minutes < 60 -> "${minutes}분"
    minutes % 60 == 0 -> "${minutes / 60}시간"
    else -> "${minutes / 60}시간 ${minutes % 60}분"
}

/** 분이 바뀔 때마다 새 값. 초 단위로 돌리면 읽는 내내 화면을 다시 그린다. */
@Composable
private fun rememberMinuteTick(): Long = produceState(System.currentTimeMillis()) {
    while (true) {
        val now = System.currentTimeMillis()
        value = now
        delay(60_000 - now % 60_000 + 50)
    }
}.value

private fun clockText(context: Context, millis: Long): String {
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "a h:mm"
    return android.text.format.DateFormat.format(pattern, millis).toString()
}

/** 배터리 잔량(%). 끈끈한(sticky) 방송을 읽기만 한다 — 권한도, 받는 쪽 등록도 필요 없다. */
private fun batteryPercent(context: Context): Int? {
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else level * 100 / scale
}

// ── 보기 판의 줄들 ─────────────────────────────────────────────────

/** 배경 고르기: 동그라미 견본. 첫째 "시스템" 은 반반 칠(휴대폰 다크 모드를 따름). */
@Composable
fun CpThemeSwatches(selected: PaperTheme, onSelect: (PaperTheme) -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText("배경", CpTheme.type.body, c.text, Modifier.weight(1f))
        PaperTheme.entries.forEach { theme ->
            val on = theme == selected
            Column(
                Modifier.padding(start = 6.dp).clip(RoundedCornerShape(CpTheme.metrics.cornerSmall))
                    .clickable { onSelect(theme) }
                    .semantics { contentDescription = "배경 ${theme.label}" }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val fill = theme.swatch
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(50))
                        .background(
                            if (fill == null) Brush.linearGradient(0.5f to LightColors.paper, 0.5f to DarkColors.paper)
                            else Brush.linearGradient(listOf(fill, fill)),
                        )
                        .border(if (on) 3.dp else 1.dp, if (on) c.accent else c.outline, RoundedCornerShape(50)),
                )
                CpText(theme.label, CpTheme.type.caption, c.textMuted, Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * 밝기: 막대 + "시스템". 막대를 움직이면 앱 안에서만 그 밝기가 되고(권한 불필요), "시스템" 을 누르면
 * 휴대폰 밝기(자동 밝기 포함)로 돌아간다. 배경 바로 아래 둔다 — 둘 다 화면이 얼마나 눈부신가를 정한다.
 */
@Composable
fun CpBrightnessRow(value: Float?, onChange: (Float?) -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText("밝기", CpTheme.type.body, c.text, Modifier.width(72.dp))
        CpSlider(value ?: 0.5f, onChange = { onChange(it) }, onCommit = { onChange(it) }, Modifier.weight(1f), description = "밝기")
        CpText(
            "시스템",
            CpTheme.type.label,
            if (value == null) c.accent else c.textMuted,
            Modifier.padding(start = 8.dp).clip(RoundedCornerShape(CpTheme.metrics.cornerSmall))
                .clickable { onChange(null) }
                // 배경 견본에도 "시스템" 이 있다. 화면 읽기로는 둘이 같은 말로 들린다.
                .semantics { contentDescription = "시스템 밝기" }
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

// ── 창에 거는 것: 밝기 · 화면 켜짐 ─────────────────────────────────

/**
 * 읽는 동안 창에 밝기와 화면 켜짐을 건다. 리더를 떠나면 되돌린다 — 라이브러리까지 어두우면 앱이 고장 난
 * 것처럼 보인다.
 *
 * @param activity 사용자가 뭔가 한 표시(넘긴 쪽). [KeepScreenOn.TenMinutes] 는 이 값이 바뀔 때마다 10분을
 *   새로 센다.
 */
@Composable
fun ReadingWindow(prefs: ScreenPrefs, activity: Any?) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, prefs.brightness) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = prefs.brightness?.coerceIn(MIN_BRIGHTNESS, 1f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    LaunchedEffect(view, prefs.keepScreenOn, activity) {
        when (prefs.keepScreenOn) {
            KeepScreenOn.System -> view.keepScreenOn = false
            KeepScreenOn.Always -> view.keepScreenOn = true
            KeepScreenOn.TenMinutes -> {
                view.keepScreenOn = true
                delay(10 * 60_000L)
                view.keepScreenOn = false
            }
        }
    }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }
}

/** 0 으로 두면 화면이 완전히 꺼져 보여 되돌릴 막대도 안 보인다. */
private const val MIN_BRIGHTNESS = 0.02f

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ── 볼륨키 ─────────────────────────────────────────────────────────

/**
 * 음량 단추를 리더로 보내는 길목. 액티비티가 `onKeyDown` · `onKeyUp` 에서 [dispatch] 를 부른다.
 *
 * 컴포즈의 키 이벤트는 초점을 가진 곳에만 가서, 누를 곳이 없는 지면(초점 없음)에서는 받지 못한다 — 그래서
 * 창 단위에서 가로챈다.
 */
class VolumeKeyRouter {
    /** 리더가 켜 둔 동안만 null 이 아니다. forward = 다음 쪽. */
    var handler: ((forward: Boolean) -> Unit)? = null

    /** 음량 단추를 먹었으면 true(시스템 음량이 바뀌지 않는다). */
    fun dispatch(event: KeyEvent): Boolean {
        val h = handler ?: return false
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> return false
        }
        // 누를 때 한 번. 누르고 있을 때의 되풀이까지 받으면 한 번 눌렀는데 몇 쪽씩 넘어간다. 떼는 이벤트도
        // 먹어야 시스템이 음량 판을 띄우지 않는다.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) h(forward)
        return true
    }
}

val LocalVolumeKeys = staticCompositionLocalOf<VolumeKeyRouter?> { null }

/** [enabled] 인 동안 음량↓ = 다음 쪽, 음량↑ = 앞 쪽. */
@Composable
fun VolumeKeyPaging(enabled: Boolean, onPage: (forward: Boolean) -> Unit) {
    val router = LocalVolumeKeys.current ?: return
    val latest by rememberUpdatedState(onPage)
    DisposableEffect(router, enabled) {
        val mine: (Boolean) -> Unit = { latest(it) }
        if (enabled) router.handler = mine
        onDispose { if (router.handler === mine) router.handler = null }
    }
}

// ── 보기 설정(전체 화면) ────────────────────────────────────────────

/**
 * 모든 보기 설정. 묶음 제목(글자만) 아래 설정 줄은 한 단([CpMetrics.levelIndent]) 안쪽이다(위계 규칙).
 *
 * 터치 영역 · 하단 정보는 이 화면 안에서 한 겹 더 들어간다. 뒤로 가기는 한 겹씩 나온다.
 *
 * @param paragraph 문단 묶음(정렬 · 들여쓰기 · 문단 간격). PDF 는 없다 — 글자를 다시 앉히지 않는다.
 */
@Composable
fun CpViewSettingsScreen(
    prefs: ScreenPrefs,
    onChange: (ScreenPrefs) -> Unit,
    onBack: () -> Unit,
    paragraph: (@Composable (child: Modifier) -> Unit)? = null,
    /** PDF 에서 열었을 때만 "PDF" 묶음(두쪽보기의 표지)을 보인다. EPUB 에서 보이면 무엇을 바꾸는지 알 수 없다. */
    pdf: Boolean = false,
) {
    var sub by remember { mutableStateOf(SettingsPage.Main) }
    androidx.activity.compose.BackHandler(enabled = sub != SettingsPage.Main) { sub = SettingsPage.Main }
    when (sub) {
        SettingsPage.Main -> CpFullScreen {
            CpHeader(title = "보기 설정", onBack = onBack)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val child = Modifier.padding(start = CpTheme.metrics.levelIndent)
                if (paragraph != null) {
                    CpSectionLabel("문단")
                    paragraph(child)
                }
                CpSectionLabel("넘기기")
                CpLinkRow("터치 영역", prefs.touch.label, { sub = SettingsPage.Touch }, child)
                CpChoice("볼륨키로 넘기기", listOf("켬", "끔"), if (prefs.volumeKeys) 0 else 1, {
                    onChange(prefs.copy(volumeKeys = it == 0))
                }, child)
                val turns = PageTurn.entries
                CpChoice("넘김 효과", turns.map { it.label }, turns.indexOf(prefs.pageTurn), {
                    onChange(prefs.copy(pageTurn = turns[it]))
                }, child)
                CpSectionLabel("화면")
                val rotations = ScreenRotation.entries
                CpChoice("화면 회전", rotations.map { it.label }, rotations.indexOf(prefs.rotation), {
                    onChange(prefs.copy(rotation = rotations[it]))
                }, child)
                CpChoice("가로에서 두쪽보기", listOf("켬", "끔"), if (prefs.twoPagesLandscape) 0 else 1, {
                    onChange(prefs.copy(twoPagesLandscape = it == 0))
                }, child)
                // 휴대폰에서는 흐리게 두고 까닭을 적는다. 줄을 빼 버리면 태블릿에서 본 설정을 휴대폰에서 찾아 헤맨다.
                val wide = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= ScreenPrefs.WIDE_SCREEN_DP
                Column(child) {
                    CpChoice(
                        "세로에서 두쪽보기",
                        listOf("켬", "끔"),
                        if (prefs.twoPagesPortrait) 0 else 1,
                        { if (wide) onChange(prefs.copy(twoPagesPortrait = it == 0)) },
                        if (wide) Modifier else Modifier.alpha(0.45f),
                    )
                    if (!wide) {
                        CpText(
                            "넓은 화면(태블릿 · 폴더블)에서만 쓸 수 있습니다",
                            CpTheme.type.caption,
                            CpTheme.colors.textMuted,
                            Modifier.padding(start = CpTheme.metrics.gutter, bottom = 6.dp),
                        )
                    }
                }
                val keep = KeepScreenOn.entries
                CpChoice("화면 켜짐 유지", keep.map { it.label }, keep.indexOf(prefs.keepScreenOn), {
                    onChange(prefs.copy(keepScreenOn = keep[it]))
                }, child)
                CpLinkRow("하단 정보", prefs.footer.summary, { sub = SettingsPage.Footer }, child)
                CpChoice("왼쪽 끝 밀어 밝기", listOf("켬", "끔"), if (prefs.brightnessGesture) 0 else 1, {
                    onChange(prefs.copy(brightnessGesture = it == 0))
                }, child)
                if (pdf) {
                    CpSectionLabel("PDF")
                    CpChoice("두쪽보기에서 표지", listOf("따로", "함께"), if (prefs.pdfCoverAlone) 0 else 1, {
                        onChange(prefs.copy(pdfCoverAlone = it == 0))
                    }, child)
                }
            }
        }
        SettingsPage.Touch -> TouchZonePicker(prefs.touch, { onChange(prefs.copy(touch = it)); sub = SettingsPage.Main }) {
            sub = SettingsPage.Main
        }
        SettingsPage.Footer -> FooterSettings(prefs.footer, { onChange(prefs.copy(footer = it)) }) { sub = SettingsPage.Main }
    }
}

private enum class SettingsPage { Main, Touch, Footer }

@Composable
private fun TouchZonePicker(current: TouchZones, onPick: (TouchZones) -> Unit, onBack: () -> Unit) {
    CpFullScreen {
        CpHeader(title = "터치 영역", onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TouchZones.entries.forEach { zones ->
                CpRadioRow(title = zones.label, selected = zones == current, onClick = { onPick(zones) }, subtitle = zones.description) {
                    MiniZones(zones)
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

/** 터치 영역 그림: 앞 쪽(푸른 회색) · 메뉴 · 다음 쪽(클레이). 말보다 그림이 빨리 읽힌다. */
@Composable
private fun MiniZones(zones: TouchZones) {
    val prev = Color(0xFF5B6B7A)
    val menu = Color(0xFFC9C0B6)
    val next = CpTheme.colors.accent
    val cols = when (zones) {
        TouchZones.Default -> listOf(prev, menu, next)
        TouchZones.Reversed -> listOf(next, menu, prev)
        TouchZones.OneHand -> listOf(next, menu, next)
    }
    Row(
        Modifier.size(width = 36.dp, height = 60.dp).clip(RoundedCornerShape(6.dp))
            .border(1.dp, CpTheme.colors.outline, RoundedCornerShape(6.dp)),
    ) {
        cols.forEachIndexed { i, c ->
            Box(Modifier.weight(if (i == 1) 0.4f else 0.3f).fillMaxHeight().background(c))
        }
    }
}

@Composable
private fun FooterSettings(footer: Footer, onChange: (Footer) -> Unit, onBack: () -> Unit) {
    var slot by remember { mutableStateOf<Int?>(null) }
    androidx.activity.compose.BackHandler(enabled = slot != null) { slot = null }
    val names = listOf("왼쪽", "가운데", "오른쪽")
    val values = listOf(footer.left, footer.center, footer.right)
    val open = slot
    if (open != null) {
        CpFullScreen {
            CpHeader(title = "${names[open]}에 보일 것", onBack = { slot = null })
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                FooterItem.entries.forEach { item ->
                    CpRadioRow(title = item.label, selected = item == values[open], onClick = {
                        onChange(
                            when (open) {
                                0 -> footer.copy(left = item)
                                1 -> footer.copy(center = item)
                                else -> footer.copy(right = item)
                            },
                        )
                        slot = null
                    })
                }
            }
        }
        return
    }
    CpFullScreen {
        CpHeader(title = "하단 정보", onBack = onBack)
        // 미리 보기: 지금 고른 세 자리를 실제 하단 정보 부품으로 그린다(숫자는 본보기).
        Box(
            Modifier.padding(CpTheme.metrics.gutter).fillMaxWidth()
                .clip(RoundedCornerShape(CpTheme.metrics.cornerMedium)).background(CpTheme.colors.paper)
                .border(1.dp, CpTheme.colors.divider, RoundedCornerShape(CpTheme.metrics.cornerMedium))
                .padding(top = 12.dp, bottom = 14.dp),
        ) {
            CpText("미리 보기", CpTheme.type.caption, CpTheme.colors.textMuted, Modifier.align(Alignment.TopStart).padding(start = 14.dp))
            CpReadingFooter(
                FooterInfo(bookTitle = "책 제목", chapterTitle = "1장", page = "3 / 12", percent = 29f, chapterPagesLeft = 9),
                footer,
                CpTheme.colors.inkMuted,
                Modifier.padding(top = 48.dp),
            )
        }
        names.forEachIndexed { i, name -> CpLinkRow(name, values[i].label, { slot = i }) }
    }
}


// ── 밝기 밀기(E6) ──────────────────────────────────────────────────

/**
 * 왼쪽 끝([EDGE])에서 위아래로 밀면 밝기가 바뀐다. 위로 밀면 밝게.
 *
 * 가장 먼저(Initial 단계) 받아서, 세로로 움직였다고 판단한 순간부터는 이 손가락을 먹는다 — 그래야 지면의
 * 누르기(다음 쪽) · 옆으로 밀기(넘김)가 같은 손가락에 반응하지 않는다. 옆으로 먼저 움직이면 놓아준다(넘김).
 * 휴대폰의 "뒤로" 제스처(가장자리에서 옆으로)와도 방향이 달라 겹치지 않는다.
 *
 * @param current 밀기 시작할 때의 밝기(시스템 밝기면 그 값을 읽어 온다).
 * @param onDrag 미는 동안의 새 밝기. null 이면 손을 뗐다.
 */
fun Modifier.brightnessEdge(
    enabled: Boolean,
    current: () -> Float,
    onDrag: (Float?) -> Unit,
): Modifier = if (!enabled) this else this.pointerInput(Unit) {
    val edge = EDGE.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        if (down.position.x > edge) return@awaitEachGesture
        val start = current()
        var claimed = false
        var travel = androidx.compose.ui.geometry.Offset.Zero
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            travel += change.position - change.previousPosition
            if (!claimed) {
                if (kotlin.math.abs(travel.x) > slop && kotlin.math.abs(travel.x) > kotlin.math.abs(travel.y)) return@awaitEachGesture
                if (kotlin.math.abs(travel.y) > slop) claimed = true
            }
            if (claimed) {
                change.consume()
                // 화면 높이의 60% 를 밀면 끝에서 끝까지.
                onDrag((start - travel.y / (size.height * 0.6f)).coerceIn(0.02f, 1f))
            }
        }
        if (claimed) onDrag(null)
    }
}

private val EDGE = 32.dp

/** 미는 동안 뜨는 밝기 표시(해 · 막대 · %). */
@Composable
fun CpBrightnessOverlay(value: Float?, modifier: Modifier = Modifier) {
    if (value == null) return
    Column(
        modifier.padding(start = 40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6302A24))
            .padding(horizontal = 14.dp, vertical = 16.dp)
            .semantics { contentDescription = "밝기 ${(value * 100).toInt()}%" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CpIcon(CpIcons.Sun, Color.White, size = 22.dp)
        Box(Modifier.padding(vertical = 10.dp).size(width = 6.dp, height = 140.dp).clip(RoundedCornerShape(50)).background(Color(0x55FFFFFF))) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp * value).clip(RoundedCornerShape(50)).background(Color.White))
        }
        CpText("${(value * 100).toInt()}%", CpTheme.type.label, Color.White)
    }
}

/** 지금 창의 밝기(0..1). 앱이 정하지 않았으면 휴대폰 밝기 설정을 읽는다 — 읽기에는 권한이 필요 없다. */
fun systemBrightness(context: Context): Float = runCatching {
    android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
}.getOrDefault(0.5f).coerceIn(0.02f, 1f)

// ── 넘김 효과(E7) ─────────────────────────────────────────────────

/**
 * 쪽이 바뀔 때의 효과. [key] 가 바뀌면 옛 쪽에서 새 쪽으로 — 서서히(겹쳐 사라짐) 또는 밀기(옆으로 빠짐).
 * [forward] 는 앞으로 넘겼는지(밀기의 방향). [PageTurn.None] 이면 그대로 바꿔 그린다(지금까지와 같다).
 */
@Composable
fun <K> CpPageTurn(key: K, effect: PageTurn, forward: (from: K, to: K) -> Boolean, content: @Composable (K) -> Unit) {
    if (effect == PageTurn.None) {
        content(key)
        return
    }
    androidx.compose.animation.AnimatedContent(
        targetState = key,
        transitionSpec = {
            val ahead = forward(initialState, targetState)
            when (effect) {
                PageTurn.Fade -> androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(TURN_MS)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(TURN_MS))
                else -> androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(TURN_MS)) { w -> if (ahead) w else -w } togetherWith
                    androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(TURN_MS)) { w -> if (ahead) -w else w }
            }
        },
        label = "page turn",
    ) { content(it) }
}

/** 넘김 효과의 길이. 길면 빠르게 넘기는 사람을 붙잡는다. */
private const val TURN_MS = 220
