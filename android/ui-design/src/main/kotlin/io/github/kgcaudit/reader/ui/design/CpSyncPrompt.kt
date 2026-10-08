package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 다른 기기에서 더 읽었다는 아래 띠(기기 간 이어 읽기, 결정 8-2): "갤럭시 탭 S9 에서 더 읽었습니다 / 128쪽 · 어제 21:40 [거기로]".
 *
 * 자리를 저절로 옮기지 않고 묻기만 한다 — 다시 처음부터 읽는 중인 사람을 다른 기기의 옛 자리로 끌고 가면 안 된다. 그래서
 * 알림 판(팝업)이 아니라 지면 아래의 띠다: 읽기를 막지 않고, 무시하고 넘기면 사라진다(부르는 쪽이 넘김 · 시간으로 거둔다).
 * 책 · PDF · 만화 리더가 같은 띠를 쓴다.
 */
@Composable
fun CpSyncPrompt(title: String, detail: String, onGo: () -> Unit, modifier: Modifier = Modifier, action: String = "거기로") {
    val c = CpTheme.colors
    val shape = RoundedCornerShape(CpTheme.metrics.cornerMedium)
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            // 태블릿 · 가로에서 화면 폭 끝까지 늘면 글과 단추가 양 끝으로 갈라져 한 덩이로 읽히지 않는다.
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            .shadow(6.dp, shape)
            .clip(shape)
            .background(c.surface)
            // 띠 바깥의 누르기는 지면이 받는다. 띠 안을 누른 것이 아래 쪽 넘김으로 새지 않게 띠가 먹는다.
            .blockTouches()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            CpText(title, CpTheme.type.body, c.text, maxLines = 2)
            CpText(detail, CpTheme.type.caption, c.textMuted)
        }
        CpTextButton(action, onGo)
    }
}

/** "갤럭시 탭 S9 에서 더 읽었습니다". 기기 이름 뒤를 띄운다 — 이름이 영문 · 숫자로 끝나면 붙인 조사가 이름의 일부로 읽힌다. */
fun syncPromptTitle(deviceName: String): String = "$deviceName 에서 더 읽었습니다"

/**
 * 다른 기기가 읽은 때: 오늘이면 "오늘 21:40", 어제면 "어제 21:40", 올해면 "10월 3일 21:40", 그 전이면 "2025년 10월 3일". 24시간제로
 * 적는다 — 띠의 한 줄 안에 "오후" 까지 넣으면 쪽 수가 밀려 잘린다.
 */
fun syncWhenText(epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day: LocalDate = at.toLocalDate()
    val clock = "%d:%02d".format(at.hour, at.minute)
    return when {
        day == today -> "오늘 $clock"
        day == today.minusDays(1) -> "어제 $clock"
        day.year == today.year -> "${day.monthValue}월 ${day.dayOfMonth}일 $clock"
        else -> "${day.year}년 ${day.monthValue}월 ${day.dayOfMonth}일"
    }
}
