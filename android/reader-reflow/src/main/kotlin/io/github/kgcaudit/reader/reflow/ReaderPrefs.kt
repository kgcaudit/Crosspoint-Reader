package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.ui.design.ScreenRotation

/**
 * 사용자가 고르는 보기 설정.
 *
 * 화면 방향([rotation])을 뺀 전부가 조판 결과를 바꾸므로 [toSpec] 을 거쳐 [LayoutSpec] 에 들어간다(규칙 4).
 */
data class ReaderPrefs(
    val fontSizeSp: Int = DEFAULT_SIZE_SP,
    /** 글꼴 키([io.github.kgcaudit.reader.text.FontOption.key]). null 은 기본(휴대폰 글꼴). */
    val font: String? = null,
    val lineSpacing: LineSpacing = LineSpacing.Normal,
    /**
     * 책에 든 글꼴(출판사 글꼴)을 쓸지. 켜 두면 글꼴이 든 책은 출판사가 정한 모양으로 열린다.
     *
     * 책마다가 아니라 **하나의 설정**이다. 다른 글꼴을 고른 사람은 출판사 글꼴이 싫은 것이라,
     * 다음 책에서 또 출판사 글꼴이 나오면 매번 끄게 된다.
     */
    val publisherFonts: Boolean = true,
    /**
     * 화면 방향. 조판 설정([LayoutSpec])에는 넣지 않는다 — 방향이 바뀌면 화면 크기가 바뀌고, 크기는 이미
     * 조판 설정에 들어 있다(같은 방향이면 같은 조판이다).
     */
    val rotation: ScreenRotation = ScreenRotation.Auto,
) {
    enum class LineSpacing(val multiplier: Float, val label: String) {
        Tight(1.4f, "좁게"),
        Normal(1.65f, "보통"),
        Loose(1.9f, "넓게"),
    }

    fun withSize(delta: Int) = copy(fontSizeSp = (fontSizeSp + delta).coerceIn(MIN_SIZE_SP, MAX_SIZE_SP))

    /**
     * 조판에 쓸 본문 글꼴. 출판사 글꼴로 조판할 때는 **휴대폰 글꼴**(null) — 책이 글꼴을 정하지 않은 곳을
     * 채우는 글꼴이다. 사용자 글꼴을 섞으면 한 쪽 안에 출판사 명조와 사용자 글꼴이 번갈아 나와 어느 쪽이
     * 책의 모양인지 알 수 없다(0.10.0 까지는 마지막에 고른 글꼴이 들어갔는데, 화면 어디에도 보이지 않았다).
     *
     * [font] 는 지우지 않는다. 출판사 글꼴을 끄면 고른 사용자 글꼴로 돌아간다.
     */
    fun bodyFont(bookFontsInUse: Boolean): String? = if (bookFontsInUse) null else font

    companion object {
        const val DEFAULT_SIZE_SP = 18
        const val MIN_SIZE_SP = 12
        const val MAX_SIZE_SP = 36
    }
}

/**
 * 화면 크기와 보기 설정에서 조판 설정을 만든다.
 *
 * 여백은 px 로 받는다(밀도 환산은 화면 쪽 일). 아래 여백은 상태바 자리를 포함한다 —
 * 본문이 상태바 밑으로 들어가면 마지막 줄이 가려진다.
 */
fun ReaderPrefs.toSpec(
    widthPx: Float,
    heightPx: Float,
    margin: Insets,
    pxPerSp: Float,
    /** 1dp 가 몇 px 인가. 그림의 CSS px 을 dp 로 옮기는 데 쓴다. */
    pxPerDp: Float,
    /** [io.github.kgcaudit.reader.text.FontCatalog.layoutFontId] 의 값. 글자 폭 지문이 붙어 있다. */
    fontId: String,
    /** 이 책에 글꼴이 있고 [ReaderPrefs.publisherFonts] 가 켜져 있으면 true. */
    useBookFonts: Boolean = false,
): LayoutSpec = LayoutSpec(
    viewportWidthPx = widthPx,
    viewportHeightPx = heightPx,
    margin = margin,
    baseSizePx = fontSizeSp * pxPerSp,
    lineHeightMultiplier = lineSpacing.multiplier,
    align = TextAlign.Justify,
    paragraphIndentEm = 1f,
    paragraphSpacingEm = 0.25f,
    fontId = fontId,
    cssPxScale = pxPerDp,
    useBookFonts = useBookFonts,
)

/**
 * 이 책을 출판사 글꼴로 조판하는가. 설정이 켜져 있고 책에 쓸 수 있는 글꼴이 있을 때만.
 *
 * 한 곳에서만 판단한다 — 조판(캐시 키)·보기 판의 이름·글꼴 목록의 선택 표시가 서로 다르게 판단하면
 * "출판사 글꼴" 이 켜져 보이는데 휴대폰 글꼴로 조판되는 식으로 어긋난다.
 */
fun BookReader.usesBookFonts(prefs: ReaderPrefs): Boolean = prefs.publisherFonts && hasBookFonts
