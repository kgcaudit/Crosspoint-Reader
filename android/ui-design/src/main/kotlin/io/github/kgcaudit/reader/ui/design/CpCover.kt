package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 책 표지의 가로 : 세로. 국판 · 신국판 · 잡지가 모두 1 : 1.4–1.5 라 한 비율로 맞춰 책장 줄이 가지런하다. */
const val COVER_ASPECT: Float = 1f / 1.45f

/**
 * 책 표지. 그림이 있으면 표지 비율에 맞춰 가운데를 잘라 채우고, 없으면 **대신 표지** — 종류 색(목록 타일과 같은 뜻의 색)
 * 위에 제목과 부제를 얹는다. 표지 없는 책도 책장에서 제목으로 알아볼 수 있어야 한다.
 *
 * [small] 은 목록 줄의 작은 표지: 글자를 얹을 자리가 없어 종류 아이콘만 둔다.
 */
@Composable
fun CpCover(
    image: ImageBitmap?,
    title: String,
    subtitle: String?,
    fallback: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    small: Boolean = false,
) {
    val c = CpTheme.colors
    val shape = RoundedCornerShape(if (small) 4.dp else 8.dp)
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(shape)
            .border(1.dp, c.divider, shape)
            .semantics { contentDescription = if (image != null) "$title 표지" else "$title 대신 표지" },
    ) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            return@Box
        }
        Box(Modifier.fillMaxSize().background(fallback))
        if (small) {
            CpIcon(icon, Color.White, Modifier.align(Alignment.Center), size = 18.dp)
        } else {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                CpText(title, CpTheme.type.body, Color.White, maxLines = 3)
                Spacer(Modifier.weight(1f))
                if (subtitle != null) CpText(subtitle, CpTheme.type.caption, Color.White.copy(alpha = 0.8f))
            }
        }
    }
}
