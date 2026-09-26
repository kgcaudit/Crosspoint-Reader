package io.github.kgcaudit.reader.listen

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.text.BasicText
import io.github.kgcaudit.reader.layout.book.pauseCount
import io.github.kgcaudit.reader.layout.book.joinWords
import io.github.kgcaudit.reader.layout.book.WordJoin
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpDivider
import io.github.kgcaudit.reader.ui.design.CpFullScreen
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpIcon
import io.github.kgcaudit.reader.ui.design.CpIconButton
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpLinkRow
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpStepper
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** "1.2×". 소수 한 자리. */
internal fun rateLabel(rate: Float): String = "${(rate * 10f).roundToInt() / 10f}×"

/** 잠자기 타이머의 남은 분(올림). 1분 미만이 남아도 "1분" — "0분" 이면 이미 멈춘 것처럼 읽힌다. */
internal fun minutesLeft(endsAtMs: Long, nowMs: Long): Int = (((endsAtMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000).toInt()

/**
 * 듣는 동안 아래에 떠 있는 작은 조종판(L2): 앞 문장 · 읽기/멈춤 · 다음 문장 | 빠르기 · 타이머 · 끝.
 * 지면은 그대로 보이고, 쪽 넘기기 · 칠하기도 그대로 된다 — 조종판 밖을 누른 것은 지면이 받는다.
 */
@Composable
fun ListenPlayer(
    state: ListenState,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CpTheme.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.timerEndsAtMs) {
        while (state.timerEndsAtMs != null) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val timer = state.timerEndsAtMs?.let { "${minutesLeft(it, now)}분" } ?: if (state.timer == ListenTimer.ChapterEnd) "장 끝" else null
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(28.dp))
            // 조종판 안의 빈 곳을 눌러도 뒤의 지면이 쪽을 넘기지 않게.
            .clickable(indication = null, interactionSource = null) {}
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = "듣기 조종판" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpIconButton(CpIcons.SkipBack, "앞 문장", onPrevious)
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(c.accent)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = if (state.playing) "멈춤" else "읽기" },
            contentAlignment = Alignment.Center,
        ) { CpIcon(if (state.playing || state.preparing) CpIcons.Pause else CpIcons.Play, c.onAccent, size = 22.dp) }
        CpIconButton(CpIcons.SkipForward, "다음 문장", onNext)
        Box(Modifier.width(1.dp).height(24.dp).background(c.divider))
        CpText(
            rateLabel(state.rate), CpTheme.type.label, c.text,
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onSettings)
                .padding(horizontal = 12.dp, vertical = 12.dp).semantics { contentDescription = "듣기 설정" },
        )
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onSettings).padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CpIcon(CpIcons.Timer, if (timer != null) c.accent else c.text, size = 20.dp)
            if (timer != null) CpText(timer, CpTheme.type.label, c.accent, Modifier.padding(start = 4.dp))
        }
        CpIconButton(CpIcons.Close, "듣기 끝내기", onClose)
    }
}

/**
 * 듣기 판(L4): 빠르기 · 목소리 · 타이머 · 어절 쉼 줄이기. 조종판의 빠르기 · 타이머를 누르면 올라온다.
 * "비교 들어 보기" 를 누르면 같은 판이 비교 판으로 바뀐다(판을 하나 더 올리면 뒤로 두 번 닫아야 한다).
 */
@Composable
fun ListenSheet(
    prefs: ListenPrefs,
    timer: ListenTimer,
    onRate: (ListenPrefs) -> Unit,
    onVoices: () -> Unit,
    onTimer: (ListenTimer) -> Unit,
    onClose: () -> Unit,
    /** 어절 쉼 줄이기를 바꿨다(저장 · 지금 듣기에 적용). */
    onJoin: (WordJoin) -> Unit = {},
    /** 비교할 문장(지금 읽는 문장). 비었으면 비교 들어 보기를 두지 않는다. */
    sample: String = "",
    previewing: WordJoin? = null,
    onPreview: (WordJoin) -> Unit = {},
) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    var comparing by remember { mutableStateOf(false) }
    BackHandler(onBack = { if (comparing) comparing = false else onClose() })
    Box(
        Modifier.fillMaxSize().background(Color(0x44000000))
            .clickable(indication = null, interactionSource = null, onClick = onClose),
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.surface)
                .clickable(indication = null, interactionSource = null) {}
                .padding(top = 10.dp, bottom = 20.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(c.divider))
            if (comparing) {
                CompareJoin(sample, prefs.join, previewing, onPreview, onPick = { onJoin(it); comparing = false }, onBack = { comparing = false })
                return@Column
            }
            CpText("듣기", CpTheme.type.title, c.text, Modifier.padding(horizontal = m.gutter, vertical = 12.dp))
            CpStepper("읽는 속도", rateLabel(prefs.rate), { onRate(prefs.stepRate(-1)) }, { onRate(prefs.stepRate(+1)) })
            CpLinkRow("목소리", prefs.voiceLabel ?: "휴대폰 기본", onVoices)
            val timers = ListenTimer.entries
            CpChoice("타이머", timers.map { it.label }, timers.indexOf(timer), { onTimer(timers[it]) })
            val joins = WordJoin.entries
            CpChoice("어절 쉼 줄이기", joins.map { it.label }, joins.indexOf(prefs.join), { onJoin(joins[it]) })
            CpText(
                "속독 · 뜻이 이어지는 어절을 붙여서 엔진에 넘겨 쉼을 줄입니다. 화면의 글은 그대로입니다. 억양이 어색하면 일반으로 두세요.",
                CpTheme.type.caption, c.textMuted,
                Modifier.padding(start = m.gutter + m.levelIndent, end = m.gutter, top = 2.dp, bottom = 4.dp), maxLines = 3,
            )
            // 위계: "어절 쉼 줄이기" 에 딸린 줄이라 글자만 있는 줄의 한 단(levelIndent) 안쪽에서 시작한다.
            if (sample.isNotBlank()) CpLinkRow("비교 들어 보기", "같은 문장을 단계마다", { comparing = true }, Modifier.padding(start = m.levelIndent))
            CpDivider(Modifier.padding(vertical = 8.dp))
            CpText(
                "휴대폰의 음성 엔진으로 읽습니다. 인터넷을 쓰지 않습니다.",
                CpTheme.type.caption, c.textMuted, Modifier.padding(horizontal = m.gutter, vertical = 4.dp), maxLines = 2,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                CpButton("닫기", onClose, primary = false)
            }
        }
    }
}

/**
 * 비교 들어 보기: 지금 문장을 세기(일반 · 속독)마다 한 줄씩 — 누르면 그 세기로 들려준다. "│" 는 엔진이 쉬는 자리.
 * 마지막으로 들은 세기로 "…로 정하기". 고르지 않고 닫으면 설정은 그대로다.
 */
@Composable
private fun CompareJoin(
    sample: String,
    current: WordJoin,
    previewing: WordJoin?,
    onPreview: (WordJoin) -> Unit,
    onPick: (WordJoin) -> Unit,
    onBack: () -> Unit,
) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    var heard by remember { mutableStateOf(current) }
    CpText("비교 들어 보기", CpTheme.type.title, c.text, Modifier.padding(horizontal = m.gutter, vertical = 12.dp))
    CpText("지금 읽는 문장으로 들어 봅니다", CpTheme.type.caption, c.textMuted, Modifier.padding(horizontal = m.gutter))
    for (level in WordJoin.entries) {
        val text = joinWords(sample, level)
        val playing = previewing == level
        Row(
            Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                .then(if (playing) Modifier.background(c.accent.copy(alpha = 0.10f)) else Modifier)
                .border(1.dp, if (playing) c.accent else c.divider, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { heard = level; onPreview(level) }
                .padding(12.dp)
                .semantics { contentDescription = "${level.withRo()} 들어 보기" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(if (playing) c.accent else c.divider), contentAlignment = Alignment.Center) {
                CpIcon(if (playing) CpIcons.Pause else CpIcons.Play, if (playing) c.onAccent else c.text, size = 18.dp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row {
                    CpText(level.label, CpTheme.type.label, if (playing) c.accent else c.text)
                    CpText("  쉬는 자리 ${pauseCount(text)}", CpTheme.type.caption, c.textMuted)
                }
                BasicText(
                    buildAnnotatedString {
                        for (ch in text) if (ch == ' ') withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Bold)) { append("│") } else append(ch)
                    },
                    style = CpTheme.type.caption.copy(color = c.text),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = m.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
        CpButton("닫기", onBack, primary = false)
        Spacer(Modifier.width(10.dp))
        CpButton("${heard.withRo()} 정하기", { onPick(heard) })
    }
}

/**
 * 목소리 고르기(L5). 엔진별로 묶고, 목소리 줄은 엔진 이름보다 한 단(levelIndent) 안쪽 — 동그라미가 있는 줄이라
 * 글자는 동그라미 뒤 childIndent 에서 시작한다. "들어 보기" 는 고르지 않고 소리만 들려준다.
 */
@Composable
fun VoiceScreen(
    kit: ListenKit,
    current: ListenPrefs,
    onPick: (ListenPrefs) -> Unit,
    onBack: () -> Unit,
) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    var voices by remember { mutableStateOf<List<VoiceChoice>?>(null) }
    LaunchedEffect(kit) { voices = runCatching { kit.voices() }.getOrDefault(emptyList()) }
    val scope = rememberCoroutineScope()
    // 들어 보기용 엔진 하나. 화면을 떠나면 끈다(켜 둔 엔진은 메모리를 쥐고 있다).
    var preview by remember { mutableStateOf<Pair<String, Speaker>?>(null) }
    DisposableEffect(Unit) { onDispose { preview?.second?.shutdown() } }
    fun listen(v: VoiceChoice) = scope.launch {
        val speaker = preview?.takeIf { it.first == v.engine }?.second ?: run {
            preview?.second?.shutdown()
            kit.speaker(v.engine).also { preview = v.engine to it }
        }
        if (!speaker.prepare()) return@launch
        speaker.setRate(current.rate)
        speaker.setVoice(v.name)
        speaker.speak("preview", "안녕하세요. 이 목소리로 책을 읽어 드립니다.", flush = true)
    }
    BackHandler(onBack = onBack)
    CpFullScreen {
        CpHeader(title = "목소리", subtitle = "휴대폰에 깔린 음성 엔진", onBack = onBack)
        val list = voices
        when {
            list == null -> CpText("목소리를 찾는 중…", CpTheme.type.subtitle, c.textMuted, Modifier.padding(m.gutter))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { CpSectionLabel("기본") }
                item {
                    VoiceRow("휴대폰 기본 목소리", current.engine == null && current.voice == null, onPick = {
                        onPick(current.copy(engine = null, voice = null, voiceLabel = null))
                    }, onListen = null)
                }
                val groups = list.groupBy { it.engineLabel }
                for ((engine, rows) in groups) {
                    item { CpSectionLabel(engine) }
                    items(rows, key = { it.engine + "/" + it.name }) { v ->
                        VoiceRow(
                            v.label + if (v.needsDownload) " (내려받기 필요)" else "",
                            current.engine == v.engine && current.voice == v.name,
                            onPick = { onPick(current.copy(engine = v.engine, voice = v.name, voiceLabel = "${v.engineLabel} · ${v.label}")) },
                            onListen = { listen(v) },
                        )
                    }
                }
                if (list.isEmpty()) {
                    item {
                        CpText(
                            "한국어 목소리를 찾지 못했습니다. 휴대폰 설정 › 일반 › 텍스트 음성 변환에서 한국어를 받아 주세요.",
                            CpTheme.type.subtitle, c.textMuted, Modifier.padding(m.gutter), maxLines = 3,
                        )
                    }
                }
                item {
                    CpText(
                        "목소리를 더 받으려면: 휴대폰 설정 › 일반 › 텍스트 음성 변환",
                        CpTheme.type.caption, c.textMuted, Modifier.padding(horizontal = m.gutter, vertical = 16.dp), maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(name: String, selected: Boolean, onPick: () -> Unit, onListen: (() -> Unit)?) {
    val c = CpTheme.colors
    val m = CpTheme.metrics
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (selected) c.accentContainer else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onPick)
            .padding(start = m.gutter + m.levelIndent, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(50)).border(2.dp, if (selected) c.accent else c.outline, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(c.accent)) }
        Spacer(Modifier.width(m.childIndent - 20.dp))
        CpText(name, CpTheme.type.body, c.text, Modifier.weight(1f))
        if (onListen != null) {
            CpText(
                "들어 보기", CpTheme.type.label, c.accent,
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onListen).padding(12.dp)
                    .semantics { contentDescription = "$name 들어 보기" },
            )
        }
    }
}

/** "일반으로" · "속독으로" — 받침이 있으면 "으로"(받침 없는 이름이 생겨도 "…로" 가 맞게). */
internal fun WordJoin.withRo(): String {
    val last = label.last()
    val batchim = last in '\uAC00'..'\uD7A3' && (last - '\uAC00') % 28 != 0
    return label + if (batchim) "으로" else "로"
}
