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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
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
 * 책갈피를 꽂거나 뺀 뒤의 알림. 책 · PDF · 만화가 같은 말을 쓴다 — 리더마다 말이 다르면 같은 동작이 다른 것으로 읽힌다.
 * @param bookmarked 누른 **뒤** 책갈피가 꽂혀 있는가.
 */
fun bookmarkMessage(bookmarked: Boolean): String = if (bookmarked) "책갈피를 꽂았습니다" else "책갈피를 뺐습니다"

/** 형광펜을 숨긴 채 칠하거나 메모했을 때. 아무 일도 없어 보이면 저장이 안 된 줄 안다(책 · PDF 가 함께 쓴다). */
const val HIDDEN_HIGHLIGHT_HINT = "형광펜을 숨겨 둔 상태라 보이지 않습니다. 보기 설정에서 켤 수 있습니다."

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
    // 줄을 바꿔 다 보인다(0.28.3). 한 줄로 막았을 때 스캔 PDF 안내처럼 긴 알림은 앞머리만 보이고 "…" 로 잘려
    // 무엇을 하라는지 알 수 없었다. 가장자리에서 띄워 두 줄이 되어도 화면 끝에 붙지 않게 한다.
    Box(
        modifier.padding(start = 24.dp, end = 24.dp, bottom = 64.dp).clip(RoundedCornerShape(20.dp))
            .background(CpPill.translucent).padding(horizontal = 18.dp, vertical = 10.dp),
    ) { CpText(message, CpTheme.type.label, Color.White, maxLines = 4, align = TextAlign.Center) }
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
fun CpReadingFooter(
    info: FooterInfo,
    footer: Footer,
    color: Color,
    modifier: Modifier = Modifier,
    /** 막대 빈 곳 색. 만화처럼 어두운 바탕 위면 부르는 쪽이 준다. */
    track: Color = CpTheme.colors.readingTrack,
    /** 막대가 오른쪽에서 차오른다(오→왼 만화, 0.42.0). 글자 칸은 그대로 — 제목은 늘 왼쪽이다. */
    reversed: Boolean = false,
) {
    val needsClock = FooterItem.Clock in footer.slots || FooterItem.Battery in footer.slots
    val now = if (needsClock) rememberMinuteTick() else 0L
    val context = LocalContext.current
    // 배터리는 시계와 같이 분마다 한 번 읽는다 — 그릴 때마다 읽으면 쪽을 넘길 때마다 시스템을 부른다.
    val battery = remember(now) { if (FooterItem.Battery in footer.slots) batteryPercent(context) else null }
    fun text(item: FooterItem): String = when (item) {
        FooterItem.None -> ""
        FooterItem.BookTitle -> info.bookTitle
        FooterItem.ChapterTitle -> info.chapterTitle.orEmpty()
        FooterItem.Clock -> clockText(context, now)
        FooterItem.Battery -> battery?.let { "배터리 $it%" }.orEmpty()
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
        // 빈 곳은 지면의 흐린 글자색을 옅게(0.29.0). UI 의 progressTrack 은 세피아 1.09 · 회색 1.04 로 지면에 묻혀 얼마
        // 남았는지 보이지 않았다.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides
                if (reversed) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.platform.LocalLayoutDirection.current,
        ) {
            CpProgressBar(
                info.percent / 100f,
                Modifier.semantics { contentDescription = if (reversed) "진행 오른쪽부터" else "진행 왼쪽부터" },
                weight = CpBarWeight.Thin,
                track = track,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // 양쪽 칸이 같은 몫을 가져야 가운데가 화면 가운데에 선다. 제목이 길면 말줄임으로 끊긴다.
            CpText(text(footer.left), CpTheme.type.caption, color, Modifier.weight(1f))
            CpText(text(footer.center), CpTheme.type.caption, color, Modifier.padding(horizontal = 12.dp))
            CpText(text(footer.right), CpTheme.type.caption, color, Modifier.weight(1f), align = TextAlign.End)
        }
    }
}

private val Footer.slots: List<FooterItem> get() = listOf(left, center, right)

/** 지면 위 진행 막대의 빈 곳: 그 지면의 흐린 글자색을 옅게. */
internal val CpColors.readingTrack: Color get() = inkMuted.copy(alpha = 0.28f)

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

private fun clockText(context: Context, millis: Long): String =
    formatClock(millis, android.text.format.DateFormat.is24HourFormat(context))

/**
 * 하단 시계 글자. 오전 · 오후는 한국어로 못 박는다(0.28.3) — 휴대폰 언어를 따르게 두었더니 영어로 둔 휴대폰에서 한국어
 * 화면 한가운데 "AM 9:05" 가 나왔다.
 */
fun formatClock(millis: Long, twentyFour: Boolean, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    val format = java.text.SimpleDateFormat(if (twentyFour) "HH:mm" else "a h:mm", java.util.Locale.KOREA)
    format.timeZone = zone
    return format.format(java.util.Date(millis))
}

/** 배터리 잔량(%). 끈끈한(sticky) 방송을 읽기만 한다 — 권한도, 받는 쪽 등록도 필요 없다. */
private fun batteryPercent(context: Context): Int? {
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else level * 100 / scale
}

// ── 보기 판의 줄들 ─────────────────────────────────────────────────

/**
 * 그림 · PDF 쪽에 씌울 색 거르개(0.24.0, 구상안 확정). 책 속 그림(차례 · 간지 · 설명 그림)과 PDF 쪽은 흰 바탕으로 만들어져,
 * 아이보리 · 세피아 지면 위에서 흰 네모가 따로 떠 보였다.
 *
 * - 밝은 지면: 그림에 지면색을 **곱한다**. 흰 곳은 정확히 지면색, 검은 글자는 검은 그대로, 색은 지면 빛이 조금 밴다.
 *   흰색만 빼는 방식(밝은 곳만 지면색으로)은 글자 가장자리의 옅은 테두리가 남았다.
 * - 어두운 지면: 곱하면 그림이 통째로 검어지고, 흰색만 빼면 그림 속 검은 글자가 지면에 묻혀 사라진다. 밝기를 78% 로만
 *   낮춰 눈부심을 줄인다(사진도 안전). 밝기 뒤집기는 사진이 음화가 되어 뺐다.
 * - 흰 지면 · "그대로": 거르지 않는다.
 *
 * 색 행렬이라 모든 안드로이드 판에서 같다(BlendMode 는 10 이상).
 */
fun paperImageFilter(paper: Color, blend: ImageBlend): ColorFilter? {
    if (blend == ImageBlend.Original) return null
    if (paper.luminance() < 0.5f) return ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(DARK_DIM, DARK_DIM, DARK_DIM, 1f) })
    if (paper.red > 0.995f && paper.green > 0.995f && paper.blue > 0.995f) return null
    return ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(paper.red, paper.green, paper.blue, 1f) })
}

/** 어두운 지면에서 그림 밝기. 흰 바탕이 #C7 쯤 — 검정 지면 옆에서 눈부시지 않으면서 사진 속 어두운 곳이 뭉개지지 않는다. */
private const val DARK_DIM = 0.78f

/** 리더가 지금 지면에 맞춰 둔 그림 거르개. 쪽을 그리는 곳마다 설정을 넘겨받지 않게 한 곳에서 준다. */
val LocalPaperImageFilter = androidx.compose.runtime.staticCompositionLocalOf<ColorFilter?> { null }

/** 보기 판의 "그림 흰 바탕" 줄. 배경 바로 아래 — 배경을 바꾸면 그림이 어떻게 보일지가 함께 바뀐다. */
@Composable
fun CpImageBlendRow(selected: ImageBlend, onSelect: (ImageBlend) -> Unit, modifier: Modifier = Modifier) {
    val options = ImageBlend.entries
    CpChoice("그림 흰 바탕", options.map { it.label }, options.indexOf(selected), { onSelect(options[it]) }, modifier)
}

/** 배경 고르기: 동그라미 견본. 첫째 "휴대폰" 은 반반 칠(휴대폰 다크 모드를 따름). */
@Composable
fun CpThemeSwatches(selected: PaperTheme, onSelect: (PaperTheme) -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 견본마다 이름을 달던 때는 360dp 폰 · 큰 글자에서 "배경" 이 사라지고 마지막 견본이 찌그러졌다(0.29.0, 구상안
        // 가안). 고른 것의 이름만 "배경" 밑에 보인다 — 나머지는 화면 읽기가 이름을 읽는다.
        Column(Modifier.weight(1f)) {
            CpText("배경", CpTheme.type.body, c.text)
            CpText(if (selected == PaperTheme.System) "휴대폰 설정" else selected.label, CpTheme.type.caption, c.accentText)
        }
        PaperTheme.entries.forEach { theme ->
            val on = theme == selected
            Box(
                Modifier.size(width = 40.dp, height = CpTheme.metrics.touchTarget)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(theme) }
                    .semantics { contentDescription = if (theme == PaperTheme.System) "배경 휴대폰 설정" else "배경 ${theme.label}" },
                contentAlignment = Alignment.Center,
            ) {
                val fill = theme.swatch
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(50))
                        .background(
                            if (fill == null) Brush.linearGradient(0.5f to LightColors.paper, 0.5f to DarkColors.paper)
                            else Brush.linearGradient(listOf(fill, fill)),
                        )
                        .border(if (on) 3.dp else 1.dp, if (on) c.accent else c.outline, RoundedCornerShape(50)),
                )
            }
        }
    }
}

/**
 * 밝기: 막대 + "휴대폰". 막대를 움직이면 앱 안에서만 그 밝기가 되고(권한 불필요), "휴대폰" 을 누르면
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
        CpTextButton(
            "휴대폰",
            { onChange(null) },
            // 배경 견본에도 "휴대폰" 이 있다. 화면 읽기로는 둘이 같은 말로 들린다.
            Modifier.padding(start = 4.dp).semantics { contentDescription = "휴대폰 설정 밝기" },
            color = if (value == null) c.accentText else c.textMuted,
        )
    }
}

/**
 * 웹툰 그림 폭: 막대 + "85%"(0.47.0, 사용자 결정 ⑧). 끄는 동안 [onPreview] 로 위의 그림을 바로 좁히고, 손을 떼면
 * [onCommit] 으로 저장한다 — 판을 연 채 맞춰 보며 정한다. 값은 [ScreenPrefs.WEBTOON_COLUMN_STEP] 칸으로 끊는다.
 */
@Composable
fun CpWebtoonWidthRow(percent: Int, onPreview: (Int) -> Unit, onCommit: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    val range = ScreenPrefs.WEBTOON_COLUMN_RANGE
    fun snap(f: Float): Int {
        val raw = range.first + f * (range.last - range.first)
        val step = ScreenPrefs.WEBTOON_COLUMN_STEP
        return (kotlin.math.round(raw / step) * step).toInt().coerceIn(range)
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText("그림 폭", CpTheme.type.body, c.text, Modifier.width(72.dp))
        CpSlider(
            (percent - range.first).toFloat() / (range.last - range.first),
            onChange = { onPreview(snap(it)) },
            onCommit = { onCommit(snap(it)) },
            Modifier.weight(1f),
            description = "그림 폭",
        )
        // 폭이 바뀌어도 막대 길이가 출렁이지 않게 숫자 칸을 고정한다("100%" 가 가장 길다).
        CpText("$percent%", CpTheme.type.label, c.text, Modifier.padding(start = 12.dp).width(44.dp))
    }
}

// ── 창에 거는 것: 밝기 · 화면 켜짐 ─────────────────────────────────

/**
 * 읽는 동안 창에 밝기와 화면 켜짐을 건다. 리더를 떠나면 되돌린다 — 라이브러리까지 어두우면 앱이 고장 난
 * 것처럼 보인다.
 *
 * @param activity 사용자가 뭔가 한 표시(넘긴 쪽). [KeepScreenOn.TenMinutes] 는 이 값이 바뀔 때마다 10분을
 *   새로 센다.
 * @param autoRunning 자동 넘김 · 자동 스크롤이 도는 중. 그동안은 설정과 상관없이 화면을 켜 둔다 — 꺼지면 넘김도 멈춘 채
 *   다음 쪽을 못 본다. 리더마다 따로 챙기던 때(0.49.0 까지)는 웹툰이 빠뜨려 자동 스크롤 중에 화면이 꺼졌다.
 */
@Composable
fun ReadingWindow(prefs: ScreenPrefs, activity: Any?, autoRunning: Boolean = false) {
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
    val keep = if (autoRunning) KeepScreenOn.Always else prefs.keepScreenOn
    LaunchedEffect(view, keep, activity) {
        when (keep) {
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

/** 보기 설정을 연 리더. 그 리더가 따르는 줄만 보인다. */
enum class CpReaderKind { Book, Pdf, Comic, Webtoon }

/**
 * 두 쪽 보기 두 줄(가로에서 · 세로에서). 책 · PDF · 만화가 같은 줄 · 같은 값을 쓴다(0.42.0) — 만화만 따로 "끔 / 넓은 화면에서 /
 * 가로에서 / 늘" 을 두었을 때는 모든 보기 설정의 이 두 줄을 바꿔도 만화가 따르지 않았다.
 *
 * @param fitWidth PDF 폭 맞춤: 두 쪽이 꺼진다. 줄을 흐리게 하고 까닭을 적는다 — 말없이 꺼지면 고장으로 보인다.
 */
@Composable
fun CpTwoPageRows(prefs: ScreenPrefs, onChange: (ScreenPrefs) -> Unit, modifier: Modifier = Modifier, fitWidth: Boolean = false) {
    // 휴대폰에서는 흐리게 두고 까닭을 적는다. 줄을 빼 버리면 태블릿에서 본 설정을 휴대폰에서 찾아 헤맨다.
    val wide = cpTablet()
    val note = Modifier.padding(start = CpTheme.metrics.gutter, bottom = 6.dp)
    Column(modifier) {
        CpChoice(
            "가로에서 두 쪽 보기", listOf("켬", "끔"), if (prefs.twoPagesLandscape) 0 else 1,
            { if (!fitWidth) onChange(prefs.copy(twoPagesLandscape = it == 0)) },
            if (fitWidth) Modifier.alpha(0.45f) else Modifier,
        )
        CpChoice(
            "세로에서 두 쪽 보기", listOf("켬", "끔"), if (prefs.twoPagesPortrait) 0 else 1,
            { if (wide && !fitWidth) onChange(prefs.copy(twoPagesPortrait = it == 0)) },
            if (wide && !fitWidth) Modifier else Modifier.alpha(0.45f),
        )
        when {
            fitWidth -> CpText("폭 맞춤에서는 한 쪽씩 보입니다", CpTheme.type.caption, CpTheme.colors.textMuted, note)
            // 세로 줄 바로 아래라 "세로 두 쪽은" 을 붙이지 않는다(0.30.0 부터의 문구 그대로).
            !wide -> CpText("넓은 화면(태블릿 · 폴더블)에서만 쓸 수 있습니다", CpTheme.type.caption, CpTheme.colors.textMuted, note)
        }
    }
}

/** 두 쪽 보기에서 표지(1쪽)를 따로 둘지. PDF · 만화. */
@Composable
private fun CoverAloneRow(prefs: ScreenPrefs, onChange: (ScreenPrefs) -> Unit, modifier: Modifier) {
    CpChoice("두 쪽 보기에서 표지", listOf("따로", "함께"), if (prefs.pdfCoverAlone) 0 else 1, {
        onChange(prefs.copy(pdfCoverAlone = it == 0))
    }, modifier)
}

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
    /** "형광펜 · 메모" 줄을 보인다. PDF 는 글자를 꺼낼 수 있는 휴대폰(안드로이드 15+)에서만 칠이 있다. */
    highlights: Boolean = !pdf,
    /**
     * 어느 리더에서 열었나(0.42.0). 그 리더가 따르지 않는 줄은 보이지 않는다 — 0.41 까지 만화 · 웹툰은 PDF 인 척 열어
     * 두 쪽 보기 · 넘김 효과 · 자동 넘김 줄이 보였는데, 바꿔도 아무 일이 없었다.
     */
    reader: CpReaderKind = if (pdf) CpReaderKind.Pdf else CpReaderKind.Book,
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
                CpChoice("음량 단추로 넘기기", listOf("켬", "끔"), if (prefs.volumeKeys) 0 else 1, {
                    onChange(prefs.copy(volumeKeys = it == 0))
                }, child)
                // 만화도 책과 같은 넘김 효과를 따른다(0.45.0, 2026-10-04 사용자 결정 — 0.40 의 "만화는 밀기만" 을 바꿈). "밀기" 는
                // 만화에서 앞뒤 쪽이 붙어 미끄러지는 넘김이다. 웹툰은 넘기지 않고 내린다.
                if (reader != CpReaderKind.Webtoon) {
                    val turns = PageTurn.entries
                    CpChoice("넘김 효과", turns.map { it.label }, turns.indexOf(prefs.pageTurn), {
                        onChange(prefs.copy(pageTurn = turns[it]))
                    }, child)
                }
                if (reader != CpReaderKind.Webtoon) {
                    // 소리 · 진동(0.32.0). 고르면 한 번 들려 준다 — "들어 보기" 단추를 따로 두면 줄이 하나 더 늘고, 고른 뒤 또 눌러야 한다.
                    val feedback = rememberTurnFeedback()
                    val view = androidx.compose.ui.platform.LocalView.current
                    val sounds = TurnSound.entries
                    CpChoice("넘김 소리", sounds.map { it.label }, sounds.indexOf(prefs.turnSound), {
                        onChange(prefs.copy(turnSound = sounds[it]))
                        feedback.preview(sounds[it])
                    }, child)
                    // 켬 · 끔 차례는 다른 줄과 같게(0.42.0) — 이 줄만 끔이 앞이라 같은 자리를 눌러 반대로 골랐다.
                    CpChoice("넘김 진동", listOf("켬", "끔"), if (prefs.turnHaptic) 0 else 1, {
                        onChange(prefs.copy(turnHaptic = it == 0))
                        if (it == 0) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }, child)
                    CpText(
                        "소리를 고르면 한 번 들려 줍니다. 휴대폰이 무음 · 진동이면 이어폰이 연결됐을 때만 소리가 납니다. 듣기 · 자동 넘김 중에는 소리가 나지 않습니다. " +
                            "휴대폰의 \"애니메이션 제거\" 가 켜져 있으면 효과 없이 넘깁니다.",
                        CpTheme.type.caption, CpTheme.colors.textMuted,
                        Modifier.padding(start = CpTheme.metrics.gutter + CpTheme.metrics.levelIndent, end = CpTheme.metrics.gutter, top = 4.dp, bottom = 4.dp),
                        maxLines = Int.MAX_VALUE,
                    )
                    val autos = AutoTurn.entries
                    CpChoice("자동 넘김", autos.map { it.label }, autos.indexOf(prefs.autoTurn), {
                        onChange(prefs.copy(autoTurn = autos[it]))
                    }, child)
                    CpText(
                        "자동 넘김은 가운데를 누르면 멈춥니다. 듣기와 함께 켜면 듣기가 넘김을 맡습니다.",
                        CpTheme.type.caption, CpTheme.colors.textMuted,
                        Modifier.padding(start = CpTheme.metrics.gutter + CpTheme.metrics.levelIndent, end = CpTheme.metrics.gutter, top = 4.dp, bottom = 4.dp),
                        maxLines = 2,
                    )
                }
                CpSectionLabel("화면")
                val rotations = ScreenRotation.entries
                CpChoice("화면 회전", rotations.map { it.label }, rotations.indexOf(prefs.rotation), {
                    onChange(prefs.copy(rotation = rotations[it]))
                }, child)
                if (highlights) {
                    // 형광펜 · 메모 숨기기(3-5). 칠이 없는 곳(안드로이드 14 이하의 PDF)에는 줄을 두지 않는다.
                    CpChoice("형광펜 · 메모", listOf("보임", "숨김"), if (prefs.showHighlights) 0 else 1, {
                        onChange(prefs.copy(showHighlights = it == 0))
                    }, child)
                    CpText(
                        "숨겨도 지워지지 않습니다. 독서노트에는 그대로 있고, 숨긴 동안 새로 칠한 것도 저장됩니다.",
                        CpTheme.type.caption, CpTheme.colors.textMuted,
                        Modifier.padding(start = CpTheme.metrics.gutter + CpTheme.metrics.levelIndent, end = CpTheme.metrics.gutter, top = 4.dp, bottom = 4.dp),
                        maxLines = 2,
                    )
                }
                if (reader != CpReaderKind.Webtoon) {
                    CpTwoPageRows(prefs, onChange, child, fitWidth = reader == CpReaderKind.Pdf && prefs.pdfFit == PdfFit.Width)
                    // 만화도 PDF 처럼 표지(1쪽)를 따로 둔다 — 2쪽부터 짝이 맞아야 펼침면 그림이 이어진다.
                    if (reader == CpReaderKind.Comic) CoverAloneRow(prefs, onChange, child)
                }
                val keep = KeepScreenOn.entries
                CpChoice("화면 켜짐 유지", keep.map { it.label }, keep.indexOf(prefs.keepScreenOn), {
                    onChange(prefs.copy(keepScreenOn = keep[it]))
                }, child)
                // 쪽 만화는 아래 정보 줄 없이 그림이 화면 끝까지 찬다(0.45.0, 사용자 결정) — 바꿔도 보이지 않는 줄은 숨긴다. 웹툰도
                // 아래 정보 줄을 그리지 않는다(0.49.0 까지 줄만 남아 있었다).
                if (reader != CpReaderKind.Comic && reader != CpReaderKind.Webtoon) {
                    CpLinkRow("하단 정보", prefs.footer.summary, { sub = SettingsPage.Footer }, child)
                }
                // 웹툰은 세로로 밀어 내린다 — 왼쪽 끝의 위아래 밀기와 같은 몸짓이다.
                if (reader != CpReaderKind.Webtoon) {
                    CpChoice("왼쪽 끝을 밀어 밝기 조절", listOf("켬", "끔"), if (prefs.brightnessGesture) 0 else 1, {
                        onChange(prefs.copy(brightnessGesture = it == 0))
                    }, child)
                }
                if (reader == CpReaderKind.Pdf) {
                    CpSectionLabel("PDF")
                    CoverAloneRow(prefs, onChange, child)
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
        modifier.padding(start = 40.dp).clip(RoundedCornerShape(20.dp)).background(CpPill.translucent)
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

/**
 * 리더 하나의 밝기 밀기 한 벌: 미는 동안의 값([value]) · 지면에 다는 수식어([modifier]) · 창에 줄 설정([applied]).
 * EPUB · PDF · 만화가 같은 것을 쓴다 — 셋이 사본을 들고 있을 때 아래 판(메모 · 듣기 · 각주)이 열린 동안 막는 조건을 EPUB 만
 * 고쳤다(0.28.3). 막는 조건은 리더마다 판이 달라 [rememberBrightnessDrag] 의 enabled 로 받는다.
 */
class BrightnessDrag internal constructor(
    private val live: androidx.compose.runtime.MutableState<Float?>,
    /** 지면(판들의 바깥 상자)에 단다. 꺼져 있으면 아무것도 하지 않는 수식어다. */
    val modifier: Modifier,
) {
    /** 미는 동안의 밝기. 손을 떼면 null — 그때 설정으로 저장했다. [CpBrightnessOverlay] 에 그대로 준다. */
    val value: Float? get() = live.value

    /** 창([ReadingWindow])에 줄 설정: 미는 중이면 그 밝기, 아니면 저장된 설정 그대로. */
    fun applied(prefs: ScreenPrefs): ScreenPrefs = live.value?.let { prefs.copy(brightness = it) } ?: prefs
}

/**
 * 왼쪽 끝 밝기 밀기를 단다([Modifier.brightnessEdge]). 미는 동안은 창 밝기만 바꾸고, 손을 떼면 [onCommit] 으로 한 번 저장한다 —
 * 미는 내내 저장하면 설정 쓰기가 프레임마다 쌓인다.
 *
 * @param saved 저장된 밝기(null = 휴대폰 밝기를 따름). 밀기를 시작할 때의 값이다.
 * @param enabled 설정이 켜져 있고, 지면 위에 아무 판도 떠 있지 않을 때만 true. 판의 왼쪽 끝을 끄는 손(메모 칸 고르기 · 판
 *   굴리기)을 밝기로 읽으면 판 대신 화면이 어두워진다.
 */
@Composable
fun rememberBrightnessDrag(saved: Float?, enabled: Boolean, onCommit: (Float) -> Unit): BrightnessDrag {
    val live = remember { mutableStateOf<Float?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 수식어는 켜고 끌 때만 새로 단다 — 람다가 옛 값을 쥐지 않게 최신 값을 읽는다.
    val latestSaved by rememberUpdatedState(saved)
    val commit by rememberUpdatedState(onCommit)
    val modifier = Modifier.brightnessEdge(
        enabled = enabled,
        current = { latestSaved ?: systemBrightness(context) },
        onDrag = { value ->
            if (value != null) {
                live.value = value
            } else {
                live.value?.let(commit)
                live.value = null
            }
        },
    )
    // 미는 도중에 판이 떠서 꺼지면(손을 떼는 알림이 오지 않는다) 미던 값을 버린다 — 남기면 창이 그 밝기에 묶인다.
    LaunchedEffect(enabled) { if (!enabled) live.value = null }
    return BrightnessDrag(live, modifier)
}

/** 지금 창의 밝기(0..1). 앱이 정하지 않았으면 휴대폰 밝기 설정을 읽는다 — 읽기에는 권한이 필요 없다. */
fun systemBrightness(context: Context): Float = runCatching {
    android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
}.getOrDefault(0.5f).coerceIn(0.02f, 1f)

