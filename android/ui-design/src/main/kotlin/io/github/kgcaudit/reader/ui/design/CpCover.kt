package io.github.kgcaudit.reader.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 책장 칸의 가로 : 세로. 국판 · 신국판이 1 : 1.4–1.5 라 이 칸을 기준으로 줄을 맞춘다. 표지 그림은 이 칸에 **맞춰 자르지
 * 않는다** — 칸 안에 원래 비율대로 넣는다([CpCover]).
 */
const val COVER_ASPECT: Float = 1f / 1.45f

/**
 * 표지 뒤에 그림자를 깐다(책장 보기, 0.48.6). 표지를 그리는 쪽이 아니라 놓는 쪽(책장)이 정한다 — 격자 · 목록에는 그림자가 없다.
 * 그림자는 칸이 아니라 **그림 크기**로 진다: 칸 크기로 깔던 때는 칸보다 낮거나 좁은 표지(잡지 · 문고본) 위 · 옆으로 빈 그림자
 * 네모가 떠 보였다(사용자 보고).
 */
val LocalCoverShadow = androidx.compose.runtime.compositionLocalOf { false }

/** 뒷벽에 비스듬히(오른쪽 아래로) 지는 그림자. 위는 6dp 내려 시작하고 오른쪽으로 6dp 삐져나온다. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CoverShadow() {
    Box(
        Modifier.matchParentSize().padding(start = 6.dp, top = 6.dp).offset(x = 6.dp)
            .clip(RoundedCornerShape(6.dp)).background(Color(0x66000000)),
    )
}

/**
 * 책 표지. 칸([COVER_ASPECT])의 크기는 늘 같고, 그림은 원래 비율 그대로 칸 안에 가장 크게 넣는다. 큰 표지는 칸의 밑면에,
 * [small](목록 줄의 작은 표지)은 가운데에 둔다. 테두리 · 둥근 모서리는 그림에만 두르고 남는 자리는 바탕 그대로다.
 *
 * 0.24.1 까지는 칸에 맞춰 가운데를 잘라 채웠다. 잡지(약 1 : 1.25)는 양옆이, 가는 문고본은 위아래가 잘려 표지가 온전히
 * 보이지 않았다(씨네21 의 제호가 잘림). 밑면을 맞추면 책꽂이처럼 줄이 가지런하고 제목 · 진도 줄도 흐트러지지 않는다.
 *
 * 그림이 없으면 **대신 표지** — 종류 색(목록 타일과 같은 뜻의 색) 위에 제목과 부제를 얹어 칸을 채운다. 표지 없는 책도
 * 책장에서 제목으로 알아볼 수 있어야 한다. [small] 은 글자를 얹을 자리가 없어 종류 아이콘만 둔다. [glyph] 가 있으면
 * 선 아이콘 대신 그 그림(계열 두 톤 타일 그림)을 그대로 — 색을 입히면 두 톤이 한 색으로 뭉갠다.
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
    glyph: Painter? = null,
    /** 안에서만 쓴다: 대신 표지의 그림자를 이미 깔았다. */
    shadowed: Boolean = false,
) {
    val c = CpTheme.colors
    val shape = RoundedCornerShape(if (small) 4.dp else 8.dp)
    if (image != null) {
        val aspect = (image.width.toFloat() / image.height.coerceAtLeast(1)).takeIf { it.isFinite() && it > 0f } ?: COVER_ASPECT
        val shadow = LocalCoverShadow.current
        Box(modifier.aspectRatio(COVER_ASPECT), contentAlignment = if (small) Alignment.Center else Alignment.BottomCenter) {
            // 칸보다 넓으면 폭에, 좁으면 높이에 맞춘다 — 어느 쪽도 칸 밖으로 나가지 않는다. 그림자는 이 그림 크기 그대로.
            Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < COVER_ASPECT)) {
                if (shadow) CoverShadow()
                Image(
                    image, null,
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .border(1.dp, c.divider, shape)
                        .semantics { contentDescription = "$title 표지" },
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        return
    }
    if (LocalCoverShadow.current && !shadowed) {
        // 대신 표지는 칸을 꽉 채운다 — 그림자도 칸 크기.
        Box(modifier.aspectRatio(COVER_ASPECT)) {
            CoverShadow()
            CpCover(null, title, subtitle, fallback, icon, Modifier.matchParentSize(), small, glyph, shadowed = true)
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
            if (glyph != null) Image(glyph, null, Modifier.align(Alignment.Center).size(22.dp))
            else CpIcon(icon, Color.White, Modifier.align(Alignment.Center), size = 18.dp)
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

/**
 * EBOOK(EPUB) 종류의 타일 그림 — OLO-Design `icons/static/ic_tile_book.xml` 을 그대로 넣었다(손으로 고치지 않는다).
 * 계열은 EPUB 을 문서 회청 위의 펼친 책으로 가른다(2026-10-02 사용자 결정): 0.32.1 까지의 청록은 계열의 CODE 색이라
 * 다른 OLO 앱에서 같은 색이 소스 코드를 뜻했다.
 */
@Composable
fun cpBookGlyph(): Painter = painterResource(R.drawable.ic_tile_book)

/** 만화 대신 표지의 그림: 칸 줄이 그어진 펼친 책(OLO-Design `ic_tile_comic`). 책 그림과 칸 줄로 갈린다. */
@Composable
fun cpComicGlyph(): Painter = painterResource(R.drawable.ic_tile_comic)

/** 대신 표지 글자 밑 띠. 60% 검정 — 가장 밝은 타일(다크의 보관 황토)에서도 흰 글자 대비가 7 을 넘는다. */
internal val COVER_BAND = Color(0x99000000)
