package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.document.comic.CoverCrop
import io.github.kgcaudit.reader.document.comic.SpreadSplit
import io.github.kgcaudit.reader.document.comic.WebtoonPiece
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

    // ── 보던 장면에서(웹툰 틀, 구상안 44 ⑤) ─────────────────────────────────

    const val TOON_TITLE: String = "표지로 쓸 부분"
    const val TOON_HINT: String = "틀을 끌어 옮기고, 두 손가락으로 크기를 바꿉니다"
    const val TOON_BUTTON: String = "이 부분을 표지로"
    const val TOON_FAILED: String = "이 부분을 표지로 쓸 수 없습니다"

    /** 틀 모양 = 표지 칸 모양(세로 ÷ 가로). 다른 모양으로 고르면 서재 칸에서 위아래나 양옆이 빈다. */
    const val ASPECT: Double = CoverCrop.ASPECT.toDouble()

    /** 틀로 고른 표지의 크기: 높이 [HEIGHT], 폭은 모양대로. */
    val TOON_OUT_WIDTH: Int = (HEIGHT / ASPECT).toInt()

    /** 화면 위 틀(왼쪽 위 · 폭, 높이는 [ASPECT] 로 정해진다). */
    data class FrameBox(val x: Double, val y: Double, val width: Double) {
        val height: Double get() = width * ASPECT
    }

    /** 가장 큰 틀: 그림 기둥 폭, 그리고 틀 전체가 화면 높이 안에 드는 폭. */
    private fun maxWidth(viewHeight: Double, columnWidth: Double) = minOf(columnWidth, viewHeight / ASPECT)

    /**
     * 처음 틀(안드로이드 `ComicWebtoon`): 기둥 폭의 80%, 단 화면 높이의 70% 를 넘지 않게. 가로 가운데, 틀 가운데는 화면 높이의 45% —
     * 아래 단추에 가리지 않게 조금 위.
     */
    fun startFrame(viewWidth: Double, viewHeight: Double, columnWidth: Double): FrameBox {
        val w = minOf(columnWidth * 0.8, viewHeight * 0.7 / ASPECT)
        return clampFrame(FrameBox((viewWidth - w) / 2, viewHeight * 0.45 - w * ASPECT / 2, w), viewWidth, viewHeight, columnWidth)
    }

    /**
     * 틀을 화면 안으로: 폭은 기둥의 25% ~ [maxWidth], 자리는 화면 안. 기둥 밖(옆 바탕)으로는 나가도 된다 — 그 부분은 바탕색으로
     * 남는다(늘이지 않는다). 화면이 돌거나 그림 폭을 바꾼 뒤에도 이것으로 되돌려 놓는다.
     */
    fun clampFrame(f: FrameBox, viewWidth: Double, viewHeight: Double, columnWidth: Double): FrameBox {
        val most = maxWidth(viewHeight, columnWidth)
        val w = f.width.coerceIn(minOf(columnWidth * 0.25, most), most)
        val h = w * ASPECT
        return FrameBox(f.x.coerceIn(0.0, maxOf(viewWidth - w, 0.0)), f.y.coerceIn(0.0, maxOf(viewHeight - h, 0.0)), w)
    }

    /** 두 손가락으로 [scale] 배 — 가운데를 지킨다(손가락 사이가 아니라 틀이 커진다). */
    fun resizeFrame(base: FrameBox, scale: Double, viewWidth: Double, viewHeight: Double, columnWidth: Double): FrameBox {
        val most = maxWidth(viewHeight, columnWidth)
        val w = (base.width * scale).coerceIn(minOf(columnWidth * 0.25, most), most)
        val cx = base.x + base.width / 2
        val cy = base.y + base.height / 2
        return clampFrame(FrameBox(cx - w / 2, cy - w * ASPECT / 2, w), viewWidth, viewHeight, columnWidth)
    }

    /** 그림 한 장의 어느 픽셀을 표지의 어느 픽셀로 옮기나. */
    data class PieceDraw(
        val srcX: Int, val srcY: Int, val srcWidth: Int, val srcHeight: Int,
        val dstX: Double, val dstY: Double, val dstWidth: Double, val dstHeight: Double,
    )

    /**
     * 틀에 걸친 조각([piece], 공통 `WebtoonChain.pieces`) 하나를 표지([outWidth] × [outHeight])에 놓을 자리. 틀은 기둥 폭의
     * [columnFrom] ~ [columnTo](0 = 기둥 왼쪽, 1 = 오른쪽 — 밖으로 나가면 0 미만 · 1 초과), 높이 [frameHeight](기둥 픽셀).
     * 기둥 밖으로 나간 부분은 그리지 않는다 — 그림을 늘여 채우면 틀과 다른 표지가 된다. 놓을 것이 없으면 null.
     */
    fun pieceDraw(
        piece: WebtoonPiece, columnFrom: Double, columnTo: Double, frameHeight: Double,
        imageWidth: Int, imageHeight: Int, outWidth: Int, outHeight: Int,
    ): PieceDraw? {
        val span = columnTo - columnFrom
        if (span <= 0 || frameHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val a = maxOf(columnFrom, 0.0)
        val b = minOf(columnTo, 1.0)
        if (b <= a) return null
        val srcX = (a * imageWidth).toInt()
        val srcY = (piece.from * imageHeight).toInt()
        val srcW = maxOf(1, (b * imageWidth).toInt() - srcX)
        val srcH = maxOf(1, (piece.to * imageHeight).toInt() - srcY)
        val dstX = (a - columnFrom) / span * outWidth
        val dstW = (b - a) / span * outWidth
        val dstY = piece.top / frameHeight * outHeight
        val dstH = (piece.bottom - piece.top) / frameHeight * outHeight
        return PieceDraw(srcX, srcY, srcW, srcH, dstX, dstY, dstW, dstH)
    }
}
