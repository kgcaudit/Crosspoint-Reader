package io.github.kgcaudit.reader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.CpIcon
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTextButton
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.blockTouches

/**
 * 읽기 잠금이 화면 맨 위에 띄우는 것: 통화 뒤 띠 · 안내. 리더 · 서재 어느 화면 위에서도 같은 자리에 뜨도록 앱의 맨 위
 * 한 곳에서 그린다(잠금은 서재 ↔ 리더를 오가도 이어진다).
 */
@Composable
internal fun ReadLockLayer(lock: ReadLockController) {
    Box(Modifier.fillMaxSize()) {
        lock.bannerText?.let { text -> ReadLockBanner(text, lock::relock, Modifier.align(Alignment.BottomCenter)) }
        CpToast(lock.toast, lock::toastShown, Modifier.align(Alignment.BottomCenter), key = lock.toastKey, durationMs = 2_500)
    }
}

/**
 * 통화 뒤 띠(구상안 3 · 6): 대화 판 색 · 열린 자물쇠 · 말 · "다시 잠그기". 리더의 아래 도구줄 위에도 뜬다 — 통화에서 돌아온
 * 사람이 가장 먼저 할 일이 이것이다.
 */
@Composable
internal fun ReadLockBanner(text: String, onRelock: () -> Unit, modifier: Modifier = Modifier) {
    val c = CpTheme.colors
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(CpTheme.metrics.cornerDialog))
            .background(c.dialog)
            // 띠의 빈 곳을 누른 손이 뒤의 쪽을 넘기지 않게.
            .blockTouches()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpIcon(CpIcons.Unlock, c.textMuted, Modifier.padding(end = 8.dp))
        CpText(text, CpTheme.type.body, c.text, Modifier.weight(1f), maxLines = 3)
        CpTextButton("다시 잠그기", onRelock)
    }
}
