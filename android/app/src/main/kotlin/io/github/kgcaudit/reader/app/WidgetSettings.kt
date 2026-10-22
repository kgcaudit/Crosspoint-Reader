package io.github.kgcaudit.reader.app

import android.content.Context
import android.util.SizeF

/** 위젯 판의 색(설정). [System] 은 휴대폰의 밝게/어둡게를 따른다(런처가 그릴 때 정한다). */
internal enum class WidgetColor { System, Light, Dark }

/**
 * 위젯 하나의 모양 설정(0.56.0, 사용자 확정 구상안). 위젯마다 따로다 — 홈 화면 두 곳에 놓고 한쪽은 배경 없이, 한쪽은 만화만
 * 보이게 할 수 있다. 설정 화면을 건너뛰고 놓은 위젯은 기본값이다.
 *
 * @param opacity 배경 불투명도(%). [OPACITY_STEPS] 가운데 하나 — 1% 씩 고르게 하면 손가락으로 같은 값을 다시 고르지 못한다.
 */
internal data class WidgetSettings(
    val background: Boolean = true,
    val opacity: Int = 100,
    val color: WidgetColor = WidgetColor.System,
    val recent: RecentKind = RecentKind.All,
) {
    companion object {
        val OPACITY_STEPS = listOf(0, 25, 50, 75, 100)

        /** 가장 가까운 단계. 저장된 값이 망가졌어도(옛 판 · 손으로 고친 파일) 위젯이 그려지게 받아 준다. */
        fun snap(opacity: Int): Int = OPACITY_STEPS.minBy { kotlin.math.abs(it - opacity) }
    }
}

/** 위젯 설정 저장소. 위젯 번호마다 한 벌, 위젯을 지우면 그 벌도 지운다([ContinueWidget.onDeleted]). */
internal object WidgetSettingsStore {
    private const val FILE = "widget_settings"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context, id: Int): WidgetSettings {
        val p = prefs(context)
        val d = WidgetSettings()
        // 모르는 이름(다음 판에서 지운 값)은 기본값으로 — 위젯이 "불러올 수 없음" 이 되는 것보다 낫다.
        return WidgetSettings(
            background = p.getBoolean("bg_$id", d.background),
            opacity = WidgetSettings.snap(p.getInt("opacity_$id", d.opacity)),
            color = WidgetColor.entries.firstOrNull { it.name == p.getString("color_$id", null) } ?: d.color,
            recent = RecentKind.entries.firstOrNull { it.name == p.getString("recent_$id", null) } ?: d.recent,
        )
    }

    fun save(context: Context, id: Int, settings: WidgetSettings) {
        prefs(context).edit()
            .putBoolean("bg_$id", settings.background)
            .putInt("opacity_$id", settings.opacity)
            .putString("color_$id", settings.color.name)
            .putString("recent_$id", settings.recent.name)
            .apply()
    }

    fun remove(context: Context, ids: IntArray) {
        val e = prefs(context).edit()
        for (id in ids) listOf("bg_", "opacity_", "color_", "recent_").forEach { e.remove("$it$id") }
        e.apply()
    }
}

/**
 * 위젯 크기별 배치(0.56.0, 사용자 확정 구상안). [minWidthDp] × [minHeightDp] 는 그 배치가 들어가는 가장 작은 크기다 —
 * 안드로이드 12+ 는 이 값을 런처에 넘겨 런처가 고르고, 그 아래 판은 [of] 로 앱이 고른다.
 *
 * @param rows 최근 목록 줄 수. 넓은 판이 낮으면(4×2 를 낮게 줄임) 세 줄째가 반쯤 잘려 보여 두 줄만 둔다.
 */
internal enum class WidgetSize(val minWidthDp: Float, val minHeightDp: Float, val rows: Int, val layout: Int) {
    Small(0f, 0f, 0, R.layout.widget_continue_small),
    WideShort(WIDE_MIN_WIDTH, 0f, 2, R.layout.widget_continue),
    Wide(WIDE_MIN_WIDTH, WIDE_THREE_ROWS_HEIGHT, 3, R.layout.widget_continue),
    Large(WIDE_MIN_WIDTH, LARGE_MIN_HEIGHT, 3, R.layout.widget_continue_large),
    ;

    val sizeF: SizeF get() = SizeF(minWidthDp, minHeightDp)

    companion object {
        /** 이 크기에 들어가는 가장 큰 배치. */
        fun of(widthDp: Float, heightDp: Float): WidgetSize =
            entries.last { widthDp >= it.minWidthDp && heightDp >= it.minHeightDp }
    }
}

/** 표지 · 글 · 목록이 나란히 들어가는 폭. 이보다 좁으면(2×2 · 3×2) 한 권만. */
private const val WIDE_MIN_WIDTH = 220f

/** 목록 세 줄이 잘리지 않는 높이: 판 여백 28 + "최근에 읽은" 16 + 줄 32 × 3. */
private const val WIDE_THREE_ROWS_HEIGHT = 140f

/** 큰 판이 들어가는 높이: 여백 28 + 위 판 120 + 선 19 + 이름 16 + 줄 48 × 3. 4×3 은 넓은 판으로 남는다. */
private const val LARGE_MIN_HEIGHT = 330f
