package io.github.kgcaudit.reader.reflow

import io.github.kgcaudit.reader.layout.Insets
import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextAlign
import io.github.kgcaudit.reader.ui.design.ScreenPrefs

/**
 * 사용자가 고르는 보기 설정.
 *
 * [screen](배경 · 밝기 · 터치 영역 · 화면 방향 …)을 뺀 전부가 조판 결과를 바꾸므로 [toSpec] 을 거쳐
 * [LayoutSpec] 에 들어간다(규칙 4). [screen] 은 PDF 리더와 함께 쓴다.
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
    /** 좌우 여백(문단 너비). */
    val margin: Margin = Margin.Normal,
    val align: ParagraphAlign = ParagraphAlign.Original,
    val indent: Indent = Indent.Original,
    val paragraphSpacing: ParagraphSpacing = ParagraphSpacing.Tight,
    /**
     * 조판과 상관없는 설정. 화면 방향도 여기다 — 방향이 바뀌면 화면 크기가 바뀌고, 크기는 이미 조판 설정에
     * 들어 있다(같은 방향이면 같은 조판이다).
     */
    val screen: ScreenPrefs = ScreenPrefs(),
) {
    enum class LineSpacing(val multiplier: Float, val label: String) {
        Tight(1.4f, "좁게"),
        Normal(1.65f, "보통"),
        Loose(1.9f, "넓게"),
    }

    /** 좌우 여백(dp). 보통이 0.11.0 까지의 고정값이다. */
    enum class Margin(val dp: Int, val label: String) {
        Narrow(16, "좁게"),
        Normal(24, "보통"),
        Wide(36, "넓게"),
    }

    /**
     * 본문 정렬. [Original] 은 책이 정한 곳은 책대로, 정하지 않은 곳은 양쪽(0.11.0 까지와 같다).
     * 양쪽 · 왼쪽을 고르면 책이 정한 본문 정렬도 덮는다 — 가운데 · 오른쪽(시 · 제목)은 그대로.
     */
    enum class ParagraphAlign(val label: String, internal val forced: TextAlign?) {
        Original("원본", null),
        Justify("양쪽", TextAlign.Justify),
        Left("왼쪽", TextAlign.Start),
    }

    /** 첫 줄 들여쓰기. [Original] 은 책이 정한 곳은 책대로, 정하지 않은 곳은 1em. */
    enum class Indent(val label: String) {
        Original("원본"),
        Off("끔"),
    }

    /**
     * 문단 사이 간격(em). 좁게가 0.11.0 까지의 고정값(0.25em)이다 — 기본값을 바꾸면 판을 올린 날 모든 책의
     * 쪽 수가 달라진다. 책이 문단 여백을 정했으면 둘 중 큰 쪽이다(여백 상쇄).
     */
    enum class ParagraphSpacing(val em: Float, val label: String) {
        Tight(0.25f, "좁게"),
        Normal(0.6f, "보통"),
        Loose(1f, "넓게"),
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
 * 여백은 px 로 받는다(밀도 환산은 화면 쪽 일 — 좌우 폭은 [ReaderPrefs.margin] 을 화면이 dp 로 바꿔 넣는다).
 * 아래 여백은 상태바 자리를 포함한다 — 본문이 상태바 밑으로 들어가면 마지막 줄이 가려진다.
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
    paragraphSpacingEm = paragraphSpacing.em,
    alignOverride = align.forced,
    indentOff = indent == ReaderPrefs.Indent.Off,
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
