package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * 책장 칸의 가로 : 세로. 국판 · 신국판이 1 : 1.4–1.5 라 이 칸을 기준으로 줄을 맞춘다. 표지 그림은 이 칸에 **맞춰 자르지
 * 않는다** — 칸 안에 원래 비율대로 넣는다([CpCover]).
 */
const val COVER_ASPECT: Float = 1f / 1.45f

/**
 * 책 표지. 칸([COVER_ASPECT])의 크기는 늘 같고, 그림은 원래 비율 그대로 칸 안에 가장 크게 넣는다. 큰 표지는 칸의 밑면에,
 * [small](목록 줄의 작은 표지)은 가운데에 둔다. 테두리 · 둥근 모서리는 그림에만 두르고 남는 자리는 바탕 그대로다.
 *
 * 0.24.1 까지는 칸에 맞춰 가운데를 잘라 채웠다. 잡지(약 1 : 1.25)는 양옆이, 가는 문고본은 위아래가 잘려 표지가 온전히
 * 보이지 않았다(씨네21 의 제호가 잘림). 밑면을 맞추면 책꽂이처럼 줄이 가지런하고 제목 · 진도 줄도 흐트러지지 않는다.
 *
 * 그림이 없으면 **대신 표지** — 종류 색(목록 타일과 같은 뜻의 색) 위에 제목과 부제를 얹어 칸을 채운다. 표지 없는 책도
 * 책장에서 제목으로 알아볼 수 있어야 한다. [small] 은 글자를 얹을 자리가 없어 종류 아이콘만 둔다.
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
    if (image != null) {
        val aspect = (image.width.toFloat() / image.height.coerceAtLeast(1)).takeIf { it.isFinite() && it > 0f } ?: COVER_ASPECT
        Box(modifier.aspectRatio(COVER_ASPECT), contentAlignment = if (small) Alignment.Center else Alignment.BottomCenter) {
            Image(
                image, null,
                Modifier
                    // 칸보다 넓으면 폭에, 좁으면 높이에 맞춘다 — 어느 쪽도 칸 밖으로 나가지 않는다.
                    .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < COVER_ASPECT)
                    .clip(shape)
                    .border(1.dp, c.divider, shape)
                    .semantics { contentDescription = "$title 표지" },
                contentScale = ContentScale.FillBounds,
            )
        }
        return
    }
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(shape)
            .border(1.dp, c.divider, shape)
            .background(fallback)
            .semantics { contentDescription = "$title 대신 표지" },
    ) {
        if (small) {
            CpIcon(icon, Color.White, Modifier.align(Alignment.Center), size = 18.dp)
        } else {
            // 글자 밑에 어두운 띠를 깐다(0.29.0, 구상안 가안). 타일 색은 흰 그림을 얹으려고 고른 색이라, 그 위의 흰 글자는
            // 어두운 테마에서 3.0(부제 2.3)으로 읽기 어려웠다. 띠 위에서는 어느 타일이든 7 이상이다.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().background(COVER_BAND).padding(10.dp)) {
                    CpText(title, CpTheme.type.body, Color.White, maxLines = 3)
                }
                Spacer(Modifier.weight(1f))
                if (subtitle != null) {
                    Box(Modifier.fillMaxWidth().background(COVER_BAND).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        CpText(subtitle, CpTheme.type.caption, Color.White)
                    }
                }
            }
        }
    }
}

/** 대신 표지 글자 밑 띠. 60% 검정 — 가장 밝은 타일(다크의 틸)에서도 흰 글자 대비가 7 을 넘는다. */
private val COVER_BAND = Color(0x99000000)
