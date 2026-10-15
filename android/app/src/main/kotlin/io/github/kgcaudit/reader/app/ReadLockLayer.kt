package io.github.kgcaudit.reader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.CpButton
import io.github.kgcaudit.reader.ui.design.CpChoice
import io.github.kgcaudit.reader.ui.design.CpIcon
import io.github.kgcaudit.reader.ui.design.CpIcons
import io.github.kgcaudit.reader.ui.design.CpPopup
import io.github.kgcaudit.reader.ui.design.CpPopupButtons
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTextButton
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToast
import io.github.kgcaudit.reader.ui.design.blockTouches

/**
 * 읽기 잠금이 화면 맨 위에 띄우는 것: 설명 판 · 통화 뒤 띠 · 안내. 리더 · 서재 어느 화면 위에서도 같은 자리에 뜨도록 앱의 맨 위
 * 한 곳에서 그린다(잠금은 서재 ↔ 리더를 오가도 이어진다).
 */
@Composable
internal fun ReadLockLayer(lock: ReadLockController) {
    Box(Modifier.fillMaxSize()) {
        lock.bannerText?.let { text -> ReadLockBanner(text, lock::relock, Modifier.align(Alignment.BottomCenter)) }
        CpToast(lock.toast, lock::toastShown, Modifier.align(Alignment.BottomCenter), key = lock.toastKey, durationMs = 2_500)
        if (lock.explaining) ReadLockExplainer(lock.quiet.chosen, onLock = lock::confirm, onCancel = lock::dismiss)
    }
}

/**
 * 설명 판(구상안 1). 매번 같은 판을 보인다 — 짧고, "알림도 끄기" 는 지난번 고른 것이 그대로 골라져 있다. 판을 건너뛰고 바로
 * 확인 창을 띄우면 처음 보는 사람이 안드로이드 창의 "앱 고정" 이 무엇인지 모른 채 답하게 된다.
 */
@Composable
internal fun ReadLockExplainer(quietChosen: Boolean, onLock: (Boolean) -> Unit, onCancel: () -> Unit) {
    val c = CpTheme.colors
    // 0 = 켬, 1 = 끔. 처음은 끔(사용자 결정 3).
    var quiet by remember { mutableIntStateOf(if (quietChosen) 0 else 1) }
    CpPopup("읽기 잠금", message = "이 책에서 다른 앱 · 홈 · 최근 앱으로 나가지 않게 막습니다.", onDismiss = onCancel) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "알림 창이 내려오지 않습니다. 소리 · 밝기 · 넘기기는 그대로입니다.",
                "전화가 오면 잠금이 저절로 풀려 평소처럼 받습니다.",
                "풀 때: 위 자물쇠를 2초 누릅니다.",
            ).forEach { CpText("· $it", CpTheme.type.body, c.text, maxLines = 3) }
            CpChoice("알림도 끄기", listOf("켬", "끔"), quiet, { quiet = it }, Modifier.padding(top = 4.dp))
            CpText("켜면 방해 금지로 — 전화는 울립니다", CpTheme.type.caption, c.textMuted, maxLines = 2)
            CpText("다음에 안드로이드 확인 창이 한 번 뜹니다. \"확인\" 을 누르세요.", CpTheme.type.caption, c.textMuted, maxLines = 2)
        }
        CpPopupButtons {
            CpTextButton("취소", onCancel)
            CpButton("잠그기", { onLock(quiet == 0) })
        }
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
