package io.github.kgcaudit.reader.layout

import io.github.kgcaudit.reader.layout.css.CssLength
import io.github.kgcaudit.reader.layout.css.CssUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 그림이 지면에서 받는 상자.
 *
 * 사례는 모두 실제 책 세 권에서 나왔다(Calibre 변환본 · 편집기 제작본 · Sigil 제작본).
 * 고치기 전에는 172개 중 171개가 똑같은 1124×843 상자를 받아, 세로 표지는 납작해지고
 * 작은 로고는 폭 가득 부풀었다.
 *
 * 지면은 그 스크린샷을 찍은 폰과 같다: 본문 1124×1736px, 1 CSS px = 2.6px.
 */
class ImageSizingTest {

    private val spec = LayoutSpec(
        viewportWidthPx = 1248f,
        viewportHeightPx = 1970f,
        margin = Insets(62f, 88f, 62f, 146f),
        baseSizePx = 47f,
        cssPxScale = 2.6f,
    )
    private val contentW = spec.contentWidthPx // 1124
    private val contentH = spec.contentHeightPx // 1736

    private fun place(
        fileW: Int = 0,
        fileH: Int = 0,
        width: CssLength? = null,
        height: CssLength? = null,
        maxWidth: CssLength? = null,
    ): PlacedImage {
        val block = Block.Image(
            "x", 0, 1, fileW, fileH,
            sizing = ImageSizing(width = width, height = height, maxWidth = maxWidth),
        )
        return Paginator(spec, FakeMeasurer(47f)).paginate("￼", listOf(block)).single().images.single()
    }

    private fun pct(v: Float) = CssLength(v, CssUnit.Percent)
    private fun px(v: Float) = CssLength(v, CssUnit.Px)

    private fun assertBox(w: Float, h: Float, image: PlacedImage) {
        assertTrue(abs(image.widthPx - w) < 1f && abs(image.heightPx - h) < 1f, "기대 ${w}x$h, 실제 ${image.widthPx}x${image.heightPx}")
    }

    /** 파일과 같은 비율인가. 여기가 깨지면 그림이 늘어나 보인다. */
    private fun assertShape(fileW: Int, fileH: Int, image: PlacedImage) {
        val expected = fileH.toFloat() / fileW
        val actual = image.heightPx / image.widthPx
        assertTrue(abs(actual - expected) / expected < 0.01f, "비율이 달라졌다: 파일 $expected, 지면 $actual")
    }

    // ── 실제 책의 사례 ──────────────────────────────────────────────

    @Test
    fun `a portrait cover sized by a css class keeps its shape`() {
        // Sigil 책: .w100 { width: 100% }, 표지 591×839.
        val image = place(591, 839, width = pct(100f))
        assertBox(contentW, contentW * 839f / 591f, image)
        assertShape(591, 839, image)
    }

    @Test
    fun `a chapter heading meant for 45 percent is not stretched to full width`() {
        // Calibre 책: .calibre4 { width: 45% }, 장 제목 띠 600×244.
        val image = place(600, 244, width = pct(45f))
        assertBox(contentW * 0.45f, contentW * 0.45f * 244f / 600f, image)
    }

    @Test
    fun `a banner with an html width of 100 percent spans the page`() {
        // 편집기 책: <img width="100%">, 1000×272.
        assertBox(contentW, contentW * 0.272f, place(1000, 272, width = pct(100f)))
    }

    @Test
    fun `a page image taller than the screen is shrunk to fit, not squashed`() {
        // 판권 페이지 그림 600×1008: 폭에 맞추면 1888px 로 지면(1736)을 넘는다.
        val image = place(600, 1008, width = pct(100f))
        assertEquals(contentH, image.heightPx, 1f)
        assertShape(600, 1008, image)
    }

    @Test
    fun `an 80 percent width with an 85 percent height fits inside both`() {
        // 높이 퍼센트는 기준이 없어 최대 높이로 다룬다.
        val image = place(1000, 1497, width = pct(80f), height = pct(85f))
        assertTrue(image.widthPx <= contentW * 0.8f + 1f)
        assertTrue(image.heightPx <= contentH * 0.85f + 1f)
        assertShape(1000, 1497, image)
    }

    @Test
    fun `a small logo without any size stays small and sharp`() {
        // 문단 앞의 118×23 로고. 폭 가득 늘리면 흐린 덩어리가 된다(스크린샷의 "체스 기호").
        // 크기 지정이 없으면 원래 크기를 dp 로: 118 × 2.6.
        assertBox(118f * 2.6f, 23f * 2.6f, place(118, 23))
    }

    @Test
    fun `an svg cover sized in css pixels is scaled by density and fits the width`() {
        // Calibre 표지: <image width="600" height="889">. dp 로 옮기면 1560px 로 폭을 넘어
        // 폭에 맞춰진다. 밀도를 무시하면 600px(화면의 절반)로 작게 나왔다.
        val image = place(600, 889, width = px(600f), height = px(889f))
        assertBox(contentW, contentW * 889f / 600f, image)
    }

    // ── 지키는 규칙 ─────────────────────────────────────────────────

    @Test
    fun `width and height that disagree with the file do not distort it`() {
        // 두 변을 다 적었는데 파일과 비율이 다르면(찌꺼기 속성), 상자 안에 비율대로 넣는다.
        val image = place(600, 889, width = px(200f), height = px(200f))
        assertShape(600, 889, image)
        assertTrue(image.heightPx <= 200f * 2.6f + 1f && image.widthPx <= 200f * 2.6f + 1f)
    }

    @Test
    fun `a percent height on a small image does not blow it up`() {
        // 높이 퍼센트는 "이만큼까지" 다. 실제 높이로 읽으면 100px 아이콘이 화면 가득 커진다.
        assertBox(100f * 2.6f, 100f * 2.6f, place(100, 100, height = pct(85f)))
    }

    @Test
    fun `max-width limits an image that would otherwise be larger`() {
        val image = place(1000, 500, maxWidth = pct(50f))
        assertBox(contentW * 0.5f, contentW * 0.25f, image)
    }

    @Test
    fun `an image with only a height set gets its width from the file shape`() {
        val image = place(200, 100, height = CssLength(2f, CssUnit.Em))
        assertBox(4f * 47f, 2f * 47f, image)
    }

    // ── 깨진 입력 ───────────────────────────────────────────────────

    @Test
    fun `an image whose file size could not be read still gets a sensible place`() {
        // 파일이 없거나 머리가 깨진 경우. 폭에 맞춰 3:4 로 자리를 잡고 넘어간다.
        assertBox(contentW, contentW * 0.75f, place())
        // 폭만 적혀 있으면 그 폭을 지킨다.
        assertBox(contentW * 0.45f, contentW * 0.45f * 0.75f, place(width = pct(45f)))
    }

    @Test
    fun `absurd sizes never produce a zero or overflowing box`() {
        // 파서가 걸러도, 다른 경로로 들어온 극단값에 조판이 무너지면 안 된다.
        val tiny = place(1, 1)
        assertTrue(tiny.widthPx >= 1f && tiny.heightPx >= 1f)
        val huge = place(100_000, 20, width = pct(1000f))
        assertTrue(huge.widthPx <= contentW + 0.5f && huge.heightPx >= 1f)
    }
}
