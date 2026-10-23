package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 이름이 붙은 한 줄 입력칸(0.58.0, 이어 읽기의 FTP 서버 설정). 이름은 칸 위에 늘 보인다 — 칸 안의 흐린 글(placeholder)만 두면
 * 글을 넣는 순간 무슨 칸이었는지 사라져, 주소 · 사용자 · 경로처럼 비슷한 칸이 여럿일 때 헷갈린다.
 *
 * 테두리는 평소 `outline`, 입력 중이면 강조색 — 찾기 칸([CpSearchField])과 같은 모서리 · 강조.
 *
 * @param secret 비밀번호. 글자를 점으로 가리고, 자판의 낱말 추천 · 기억을 끈다(입력한 비밀번호가 추천 목록에 남지 않게).
 */
@Composable
fun CpTextField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val c = CpTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CpTheme.metrics.cornerMedium)
    Column(modifier.fillMaxWidth().padding(horizontal = CpTheme.metrics.gutter, vertical = 6.dp)) {
        CpText(label, CpTheme.type.caption, c.textMuted)
        Box(
            Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 48.dp)
                .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else c.outline, shape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) CpText(placeholder, CpTheme.type.body, c.textMuted)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = CpTheme.type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = !secret && keyboard == KeyboardType.Text,
                    keyboardType = if (secret) KeyboardType.Password else keyboard,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = label; if (secret) password() },
            )
        }
    }
}
