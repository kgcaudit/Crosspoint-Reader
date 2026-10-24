package io.github.kgcaudit.reader.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kgcaudit.reader.ui.design.CONTENT_MAX_WIDTH
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpSlider
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.cpTwoPane
import kotlinx.coroutines.launch

/**
 * 위젯 설정(0.56.0, 사용자 확정 구상안): 미리보기 · 배경 · 불투명도 · 색 · 최근 목록. 위젯을 놓을 때와, 놓은 위젯을 길게 눌러
 * "설정" 을 고를 때 런처가 연다. 위젯마다 따로 저장한다.
 *
 * 결과는 처음부터 "취소" 로 둔다 — 처음 놓을 때 뒤로 가기로 닫으면 위젯을 놓지 않는 것이 안드로이드의 약속이다. 저장을 눌러야
 * 놓인다(안드로이드 12+ 는 설정을 건너뛰고 놓을 수도 있다 — 그때는 기본값).
 */
class WidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, result(id))
        // 위젯 번호 없이 열렸다(다른 앱이 그냥 연 것) — 바꿀 위젯이 없다.
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val start = WidgetSettingsStore.load(this, id)
        setContent {
            CpTheme {
                val scope = rememberCoroutineScope()
                WidgetSettingsScreen(
                    start = start,
                    preview = { ContinueWidgets.views(applicationContext, WidgetSize.Wide, it, id) },
                    onCancel = ::finish,
                    onSave = { settings ->
                        WidgetSettingsStore.save(this, id, settings)
                        scope.launch {
                            // 안드로이드 11 아래는 설정 화면이 위젯을 처음 그려야 한다(런처가 그리라고 부르지 않는다).
                            try {
                                ContinueWidgets.render(applicationContext, AppWidgetManager.getInstance(applicationContext), intArrayOf(id))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("OloWidget", "widget not drawn after settings", e)
                            }
                            setResult(RESULT_OK, result(id))
                            finish()
                        }
                    },
                )
            }
        }
    }

    private fun result(id: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
}

internal const val WIDGET_PREVIEW_DESCRIPTION = "위젯 미리보기"

@Composable
internal fun WidgetSettingsScreen(
    start: WidgetSettings,
    preview: suspend (WidgetSettings) -> RemoteViews,
    onCancel: () -> Unit,
    onSave: (WidgetSettings) -> Unit,
) {
    // 화면을 돌리면 활동이 새로 만들어진다 — remember 면 저장 전의 고른 것이 처음 값으로 돌아가, 저장하면 옛 설정이 남았다.
    var settings by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = WIDGET_SETTINGS_SAVER) { mutableStateOf(start) }
    val c = CpTheme.colors
    Column(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
        CpHeader(title = "위젯 설정", onBack = onCancel)
        if (cpTwoPane()) {
            // 넓음: 왼쪽에 미리보기, 오른쪽에 설정(반응형 규칙 — 태블릿에서 휴대폰 화면을 가운데 늘여 두지 않는다).
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp), contentAlignment = Alignment.Center) {
                    PreviewStage(settings, preview, Modifier.fillMaxWidth().height(300.dp))
                }
                Column(Modifier.width(CONTENT_MAX_WIDTH * 0.75f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Controls(settings) { settings = it }
                }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH)) {
                    PreviewStage(settings, preview, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(230.dp))
                    Spacer(Modifier.height(12.dp))
                    Controls(settings) { settings = it }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            CpButton("취소", onCancel, primary = false)
            CpButton("저장", { onSave(settings) })
        }
    }
}

/**
 * 미리보기: 홈 화면에 놓일 RemoteViews 를 그대로 펴서 보인다 — 따로 그린 그림이면 설정 화면과 홈 화면이 어긋난다. 바탕은 벽지
 * 대신 옅은 무늬다(벽지를 읽으려면 저장소 권한이 든다). 배경을 끄거나 흐리게 했을 때 뒤가 비치는 것이 보여야 한다.
 */
@Composable
private fun PreviewStage(settings: WidgetSettings, preview: suspend (WidgetSettings) -> RemoteViews, modifier: Modifier) {
    val context = LocalContext.current
    var views by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(settings) {
        // 그리지 못하면(표지 · DB 오류) 미리보기만 비운다 — 설정은 그대로 고르고 저장할 수 있다.
        views = try {
            preview(settings)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFDDEBE6), Color(0xFFF2E3CF), Color(0xFFBFD8D2))))
            .semantics { contentDescription = WIDGET_PREVIEW_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { FrameLayout(it) },
            modifier = Modifier.size(PREVIEW_WIDTH, PREVIEW_HEIGHT),
            update = { frame ->
                frame.removeAllViews()
                views?.let { v -> runCatching { v.apply(context, frame) }.getOrNull()?.let(frame::addView) }
            },
        )
    }
}

@Composable
private fun Controls(settings: WidgetSettings, onChange: (WidgetSettings) -> Unit) {
    val c = CpTheme.colors
    CpSectionLabel("모양")
    CpChoice("배경", listOf("켬", "끔"), if (settings.background) 0 else 1, { onChange(settings.copy(background = it == 0)) })
    // 배경을 끄면 불투명도는 닿지 않는다 — 흐리게 두어 손대도 바뀌는 것이 없음을 보인다(숨기면 켰을 때 줄이 튀어나온다).
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 6.dp).alpha(if (settings.background) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText("불투명도", CpTheme.type.body, c.text, Modifier.width(96.dp))
        CpSlider(
            settings.opacity / 100f,
            { onChange(settings.copy(opacity = WidgetSettings.snap((it * 100).toInt()))) },
            { onChange(settings.copy(opacity = WidgetSettings.snap((it * 100).toInt()))) },
            Modifier.weight(1f),
            description = "불투명도",
        )
        CpText("${settings.opacity}%", CpTheme.type.label, c.text, Modifier.padding(start = 12.dp).widthIn(min = 40.dp))
    }
    CpChoice("색", listOf("휴대폰", "밝게", "어둡게"), settings.color.ordinal, { onChange(settings.copy(color = WidgetColor.entries[it])) })
    CpSectionLabel("보일 것")
    CpChoice("최근 목록", listOf("책 · 만화", "책만", "만화만"), settings.recent.ordinal, { onChange(settings.copy(recent = RecentKind.entries[it])) })
}

/** 미리보기 크기: 4×2 위젯의 흔한 크기(구상안과 같다). */
private val PREVIEW_WIDTH = 340.dp
private val PREVIEW_HEIGHT = 165.dp

/** 위젯 설정을 화면 돌리기 너머로 넘긴다(묶음에 넣을 수 있는 값만). 모르는 이름은 처음 값. */
private val WIDGET_SETTINGS_SAVER = androidx.compose.runtime.saveable.listSaver<WidgetSettings, Any>(
    save = { listOf(it.background, it.opacity, it.color.name, it.recent.name) },
    restore = { v ->
        WidgetSettings(
            background = v[0] as Boolean,
            opacity = v[1] as Int,
            color = WidgetColor.entries.firstOrNull { it.name == v[2] } ?: WidgetColor.System,
            recent = RecentKind.entries.firstOrNull { it.name == v[3] } ?: RecentKind.All,
        )
    },
)
