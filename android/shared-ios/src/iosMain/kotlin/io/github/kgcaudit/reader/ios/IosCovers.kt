package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.document.image.ImageSize

/**
 * 고른 표지(구상안 44, 안드로이드 `CoverStore` · `CoverMenu` · `PagePickBar`)의 규칙과 글. 그림을 풀고 쓰는 일은 Swift(ImageIO ·
 * UIKit)가 하고, 무엇을 얼마나 줄여 어떤 말을 띄울지는 여기서 정해 시험한다.
 */
object IosCovers {
    /** 저장하는 표지 높이(안드로이드 `COVER_HEIGHT`). 서재 칸은 이보다 작다 — 더 크게 두면 파일만 커진다. */
    const val HEIGHT: Int = 480

    /** 가로로 긴 그림의 폭 상한(안드로이드 `MAX_COVER_WIDTH`). 높이만 맞추면 파노라마 사진이 수천 픽셀 폭이 된다. */
    const val MAX_WIDTH: Int = 960

    const val JPEG_QUALITY: Double = 0.85

    /**
     * 작품의 표지 자리(안드로이드 `CoverStore.workId`). 책 id 와 겹치지 않게 앞에 붙인다 — 같은 이름의 책과 작품이 한 파일을 나눠
     * 쓰면 책 표지를 바꿨는데 작품 표지가 바뀐다.
     */
    fun workId(workKey: String): String = "comic-work|$workKey"

    /** 저장할 크기: 높이 [HEIGHT] 에 맞추되 키우지 않고, 폭은 [MAX_WIDTH] 까지. 크기를 모르는 그림이면 null(쓰지 않는다). */
    fun fit(width: Int, height: Int): ImageSize? {
        if (width <= 0 || height <= 0) return null
        var scale = minOf(1.0, HEIGHT.toDouble() / height)
        if (width * scale > MAX_WIDTH) scale = MAX_WIDTH.toDouble() / width
        return ImageSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
    }

    // ── 책 판(구상안 44 ①) ───────────────────────────────────────────────

    /** 판의 설명 줄: 지금 어떤 표지인가. 표지 줄이 생겨 무엇을 바꾸는지 먼저 알린다(구상안 44 · 2A). */
    fun bookMessage(custom: Boolean, hasOwn: Boolean, pdf: Boolean): String = when {
        custom -> "직접 고른 표지를 쓰고 있습니다."
        hasOwn && pdf -> "지금 표지는 파일 첫 쪽입니다."
        hasOwn -> "지금 표지는 책에 든 표지 그림입니다."
        else -> "이 책에는 표지 그림이 없어 대신 표지를 보여 줍니다."
    }

    /** 되돌리기 줄: 되돌아갈 곳의 이름. 책에 표지가 없으면 "원래 표지" 는 없다. */
    fun revertLabel(hasOwn: Boolean): String = if (hasOwn) "원래 표지로 되돌리기" else "대신 표지로 되돌리기"

    const val PICK_ROW: String = "사진 · 파일에서 표지 고르기"
    const val SCENE_ROW: String = "보던 장면에서 표지 고르기"
    const val WORK_REVERT_ROW: String = "원래 표지로 되돌리기"

    /** 책 이름 뒤에 조사를 붙이지 않는다 — 받침에 따라 을/를이 갈려 틀리기 쉽다(안드로이드와 같다). */
    fun changed(label: String?): String = label?.let { "‘$it’ 표지를 바꿨습니다" } ?: "표지를 바꿨습니다"
    fun reverted(label: String): String = "‘$label’ 표지를 되돌렸습니다"

    const val CANNOT_USE_TITLE: String = "이 그림을 표지로 쓸 수 없습니다"
    const val CANNOT_USE_BODY: String = "그림 파일(jpg · png 등)을 골라 주세요. 파일이 깨졌을 수도 있습니다."

    // ── 보던 장면에서(쪽 넘김, 구상안 44 ④) ──────────────────────────────

    /** 넓은 쪽에서 고를 곳(안드로이드 `PICK_HALVES`). 차례가 [halfAt] 과 맞아야 한다. */
    val PICK_HALVES: List<String> = listOf("왼쪽", "오른쪽", "통째로")

    fun halfAt(index: Int): SpreadSplit.Half = when (index) {
        0 -> SpreadSplit.Half.LEFT
        1 -> SpreadSplit.Half.RIGHT
        else -> SpreadSplit.Half.WHOLE
    }

    fun indexOf(half: SpreadSplit.Half): Int = when (half) {
        SpreadSplit.Half.LEFT -> 0
        SpreadSplit.Half.RIGHT -> 1
        SpreadSplit.Half.WHOLE -> 2
    }

    /** 펼침면의 앞(표지) 쪽 반: 왼→오 책은 오른쪽, 오→왼 책은 왼쪽 — 자동 표지(`CoverCrop.front`)와 같은 쪽을 처음에 골라 둔다. */
    fun frontHalf(rightToLeft: Boolean): SpreadSplit.Half = if (rightToLeft) SpreadSplit.Half.LEFT else SpreadSplit.Half.RIGHT

    /**
     * 머리의 작은 글(안드로이드 `pickSubtitle`). [half] 는 지금 보이는 반(나눠 보는 중이면 LEFT · RIGHT), [wide] 는 통째로 보이는
     * 넓은 쪽인가. 쪽 번호는 사람이 세는 1부터.
     */
    fun pickSubtitle(page: Int, half: SpreadSplit.Half, wide: Boolean, failed: Boolean): String = when {
        failed -> "이 쪽을 표지로 쓸 수 없습니다"
        half == SpreadSplit.Half.LEFT -> "${page + 1}쪽 · 왼쪽 반"
        half == SpreadSplit.Half.RIGHT -> "${page + 1}쪽 · 오른쪽 반"
        wide -> "${page + 1}쪽 — 표지로 쓸 곳을 고릅니다"
        else -> "넘겨서 쪽을 고릅니다"
    }

    const val PICK_TITLE: String = "표지로 쓸 쪽"
    const val PICK_BUTTON: String = "이 쪽을 표지로"
    const val SAVING: String = "저장하는 중…"

    /** 고르지 않은 반을 덮는 어둠(안드로이드 `COVER_DIM`). */
    const val DIM: Double = 0.6

    /**
     * 저장할 곳: 나눠 보는 중이면 그 반, 통째로 보이는 넓은 쪽이면 고른 곳, 아니면 쪽 전체. 넓지 않은 쪽에 남은 선택(앞 쪽에서 고른
     * "왼쪽")이 새어 들면 세로 쪽의 반만 표지가 된다.
     */
    fun savedHalf(shown: SpreadSplit.Half, wide: Boolean, chosen: SpreadSplit.Half): SpreadSplit.Half = when {
        shown != SpreadSplit.Half.WHOLE -> shown
        wide -> chosen
        else -> SpreadSplit.Half.WHOLE
    }
}
