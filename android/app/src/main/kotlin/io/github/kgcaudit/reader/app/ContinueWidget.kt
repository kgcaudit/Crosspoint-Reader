package io.github.kgcaudit.reader.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.kgcaudit.reader.data.ReaderData
import io.github.kgcaudit.reader.data.readingActivity
import io.github.kgcaudit.reader.document.BookId
import io.github.kgcaudit.reader.document.comic.ComicReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 위젯을 누르면 열 것. 책(그림책 · 만화로 보기 PDF 도 책 id)이거나 만화 한 권(단위 id). */
internal sealed interface ContinueTarget {
    data class Book(val id: String) : ContinueTarget
    data class Comic(val unitId: String) : ContinueTarget
}

/**
 * "이어 읽기" 후보 하나: 읽던 책 · 만화 권.
 *
 * @param readAt 마지막으로 읽은 때. 책은 연 때와 자리를 적은 때 가운데 늦은 것 — 연 때만 보면 오래 펴 두고 읽은 책이 방금 잠깐
 *   열어 본 책에 밀린다.
 * @param remainingMinutes 남은 시간. 모르면 null — 위젯이 "남은 …" 을 보이지 않는다(어림 없는 숫자를 지어내지 않는다).
 */
internal data class ContinueCandidate(
    val target: ContinueTarget,
    val title: String,
    val byline: String?,
    /** 0~100. */
    val percent: Float,
    val readAt: Long,
    val finishedAt: Long?,
    val remainingMinutes: Int? = null,
)

internal sealed interface WidgetModel {
    /** 읽은 것이 없다 — "OLO eBook 열기". */
    data object Empty : WidgetModel

    data class Continue(
        val target: ContinueTarget,
        val title: String,
        val byline: String?,
        /** "61%". */
        val percentText: String,
        /** 막대(0~1000). */
        val progress: Int,
        /** "남은 1시간 20분". 모르면 null. */
        val remaining: String?,
        /** 그다음 최근에 읽던 것(0.56.0). 가장 최근 것부터, 많아야 [RECENT_LIMIT] 개. */
        val recent: List<RecentItem> = emptyList(),
    ) : WidgetModel
}

/** 위젯 오른쪽(넓은 판) · 아래(큰 판) 목록의 한 줄. */
internal data class RecentItem(
    val target: ContinueTarget,
    val title: String,
    val byline: String?,
    val percentText: String,
    val progress: Int,
)

/** 최근 목록에 무엇을 올리나(위젯 설정). 맨 위 "이어 읽기" 한 권은 이것과 상관없이 가장 최근 것이다. */
internal enum class RecentKind { All, Books, Comics }

/** 목록 줄 수의 상한. 넓은 판 세 줄 · 큰 판 세 줄(사용자 확정 구상안 — 그보다 많으면 줄마다 글이 작아져 읽히지 않는다). */
internal const val RECENT_LIMIT = 3

/**
 * 위젯에 무엇을 보일지. 저장소 없이 시험하는 순수 함수다.
 *
 * **다 읽지 않은 것 가운데 가장 최근**을 고른다. 방금 다 읽은 책을 보이면 "이어 읽기" 가 할 일이 없다 — 누르면 끝 쪽(또는 다시
 * 처음)이 열릴 뿐이고, 사람은 그 앞에 읽던 다른 책으로 돌아가고 싶다. 다 읽은 뒤 다시 펼친 책(다 읽은 때보다 한참 뒤에 읽음)은
 * 다시 읽는 중이라 후보다. 다 읽지 않은 것이 없으면 빈 판("OLO eBook 열기")이다.
 *
 * 목록(0.56.0)도 같은 규칙으로 다 읽은 것을 뺀다 — "최근에 읽은" 줄을 눌러 다 읽은 책의 끝 쪽이 열리면 쓸모가 없다. 맨 위
 * 한 권은 목록에 다시 올리지 않는다.
 */
internal fun continueModel(candidates: List<ContinueCandidate>, recentKind: RecentKind = RecentKind.All): WidgetModel {
    val open = candidates.filter { !it.isFinished() }.sortedByDescending { it.readAt }
    val pick = open.firstOrNull() ?: return WidgetModel.Empty
    val recent = open.drop(1).filter {
        when (recentKind) {
            RecentKind.All -> true
            RecentKind.Books -> it.target is ContinueTarget.Book
            RecentKind.Comics -> it.target is ContinueTarget.Comic
        }
    }.take(RECENT_LIMIT).map { RecentItem(it.target, it.title, it.byline?.takeIf { b -> b.isNotBlank() }, it.percentText(), it.progressValue()) }
    return WidgetModel.Continue(
        target = pick.target,
        title = pick.title,
        byline = pick.byline?.takeIf { it.isNotBlank() },
        percentText = pick.percentText(),
        progress = pick.progressValue(),
        remaining = pick.remainingMinutes?.takeIf { it > 0 }?.let { "남은 ${minutesWords(it)}" },
        recent = recent,
    )
}

// 내려 센다: 99.6% 를 "100%" 로 올리면 다 읽지 않은 책이 다 읽은 것처럼 보인다.
private fun ContinueCandidate.percentText(): String = "${percent.coerceIn(0f, 100f).toInt()}%"
private fun ContinueCandidate.progressValue(): Int = (percent.coerceIn(0f, 100f) * 10).toInt()

private fun ContinueCandidate.isFinished(): Boolean =
    percent >= FINISHED_PERCENT || (finishedAt != null && finishedAt >= readAt - REOPEN_GRACE_MS)

/** "1시간 20분" · "45분" · "2시간". */
internal fun minutesWords(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return listOfNotNull("${h}시간".takeIf { h > 0 }, "${m}분".takeIf { m > 0 || h == 0 }).joinToString(" ")
}

/** 끝까지 읽음(진도 저장소와 같은 문턱 — 부동소수로 100 이 99.99… 가 되는 것을 받아 준다). */
private const val FINISHED_PERCENT = 99.95f

/** 다 읽은 때와 이만큼 안에 마지막으로 읽었으면 "다 읽고 덮은 것" 이다. 넘으면 다시 펼쳐 읽는 중이다. */
private const val REOPEN_GRACE_MS = 60_000L

/**
 * 홈 화면 "이어 읽기" 위젯(사용자 결정 3-1). 컴포즈(Glance) 대신 RemoteViews 다 — Glance 는 라이브러리를 더 실어 APK 가 커지고,
 * 위젯 하나에 그만한 일이 없다.
 *
 * 정해 둔 때마다 깨어나지 않는다(updatePeriodMillis = 0). 읽은 자리가 바뀌면 앱이 [ContinueWidgets.schedule] 로 고쳐 그린다.
 * 시스템이 그리라고 부르면(위젯을 놓음 · 휴대폰을 켬) 여기서 그린다.
 */
open class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // 그리기는 DB · 표지를 읽는다 — 방송 받는 손(메인 스레드)에서 하면 멈춘다. 끝날 때까지 방송을 붙들어 둔다. 방송 밖에서
        // 바로 불리면(일부 런처 · 시험) 붙들 방송이 없어 null 이다.
        val pending: PendingResult? = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ContinueWidgets.render(context.applicationContext, manager, ids)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("OloWidget", "widget not drawn", e)
            } finally {
                pending?.finish()
            }
        }
    }

    /** 위젯을 지우면 그 위젯의 설정도 지운다 — 남겨 두면 다음에 같은 번호를 받은 위젯이 옛 설정으로 뜬다. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetSettingsStore.remove(context, appWidgetIds)
    }

    /**
     * 크기가 바뀌었다. 안드로이드 12+ 는 크기별 배치를 이미 넘겨 두어 런처가 고른다 — 다시 그릴 일이 없다. 그 아래 판은 지금 크기에
     * 맞는 배치 하나만 넘겼으니 다시 그린다. 안 하면 4×2 를 2×2 로 줄여도 넓은 판이 눌린 채 남는다.
     */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

    companion object {
        /** 위젯이 앱을 열 때의 동작. 다른 앱이 보낼 수 없게 MainActivity 만 받는다(명시적 인텐트). */
        const val ACTION_CONTINUE: String = "io.github.kgcaudit.oloebook.action.CONTINUE"
        private const val EXTRA_BOOK = "book"
        private const val EXTRA_COMIC = "comic"

        internal fun intentFor(context: Context, target: ContinueTarget): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_CONTINUE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply {
                    when (target) {
                        is ContinueTarget.Book -> putExtra(EXTRA_BOOK, target.id)
                        is ContinueTarget.Comic -> putExtra(EXTRA_COMIC, target.unitId)
                    }
                }

        /** 위젯이 보낸 인텐트면 열 것, 아니면 null. 값이 빠진 인텐트(망가진 런처 · 옛 판)는 위젯 것이 아니다. */
        internal fun targetOf(intent: Intent?): ContinueTarget? {
            if (intent?.action != ACTION_CONTINUE) return null
            intent.getStringExtra(EXTRA_BOOK)?.takeIf { it.isNotEmpty() }?.let { return ContinueTarget.Book(it) }
            intent.getStringExtra(EXTRA_COMIC)?.takeIf { it.isNotEmpty() }?.let { return ContinueTarget.Comic(it) }
            return null
        }
    }
}

/**
 * 위젯 고르기 화면의 두 번째 항목 "이어 읽기(한 권)"(0.56.1, 확정 구상안의 고르기 화면). 처음부터 2×2 로 놓이는 것만 다르고,
 * 그리기 · 설정 · 크기에 따른 배치는 [ContinueWidget] 과 같다 — 놓은 뒤 늘이면 넓은 판 · 큰 판이 된다.
 * 0.56.0 은 받는 쪽이 하나라 고르기 화면에 4×2 만 보였다(사용자 지적).
 */
class ContinueWidgetSingle : ContinueWidget()

/** 같은 위젯을 받는 쪽 모두. */
internal val PROVIDERS = listOf(ContinueWidget::class.java, ContinueWidgetSingle::class.java)

/** 위젯 그리기 · 다시 그리기. 앱 전체에 하나([AppContainer.widgets]). */
internal object ContinueWidgets {

    /** 놓인 위젯이 있으면 지금 모습으로 다시 그린다. 없으면 아무것도 읽지 않는다(위젯을 안 쓰는 사람에게 비용이 없다). */
    suspend fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        // 고르기 화면의 두 항목(4×2 · 2×2)은 받는 쪽이 다를 뿐 같은 위젯이다 — 하나만 모으면 다른 쪽이 옛 책에 머문다.
        val ids = PROVIDERS.flatMap { manager.getAppWidgetIds(ComponentName(context, it)).asList() }.toIntArray()
        if (ids.isEmpty()) return
        render(context, manager, ids)
    }

    /**
     * 위젯마다 따로 그린다 — 설정(배경 · 색 · 목록)이 위젯마다 다르다. 읽은 기록 · 표지는 한 번만 읽어 함께 쓴다.
     *
     * 안드로이드 12+ 는 크기별 배치를 모두 넘기고 런처가 지금 크기에 맞는 것을 고른다 — 사람이 위젯 크기를 바꾸는 동안 앱이
     * 깨어나지 않아도 배치가 따라간다. 그 아래 판은 지금 크기를 물어 하나만 넘기고, 크기가 바뀌면 다시 그린다
     * ([ContinueWidget.onAppWidgetOptionsChanged]).
     */
    suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val board = Board.load(context)
        for (id in ids) {
            val settings = WidgetSettingsStore.load(context, id)
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                board.sized(settings, id)
            } else {
                val options = manager.getAppWidgetOptions(id)
                val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                // 처음 놓을 때 런처가 아직 크기를 알리지 않았으면 0 이다 — 작은 판으로 그리면 4×2 칸에 표지 하나만 덩그러니 남는다.
                board.views(if (w <= 0 || h <= 0) WidgetSize.Wide else WidgetSize.of(w.toFloat(), h.toFloat()), settings, id)
            }
            manager.updateAppWidget(id, views)
        }
    }

    /** 지금 모습의 위젯 한 크기. 시험 · 설정 화면의 미리보기가 뷰로 펴 그려 본다. */
    suspend fun views(context: Context, size: WidgetSize = WidgetSize.Wide, settings: WidgetSettings = WidgetSettings(), widgetId: Int = 0): RemoteViews =
        Board.load(context).views(size, settings, widgetId)

    /** 위젯에 올릴 재료: 읽은 것들과 표지. 한 번 그리는 동안 표지를 크기 · 위젯마다 다시 읽지 않는다. */
    private class Board(private val context: Context, private val candidates: List<ContinueCandidate>) {
        private val container = context.container
        private val density = context.resources.displayMetrics.density
        private val covers = HashMap<Pair<ContinueTarget, Int>, Bitmap?>()

        /**
         * 크기별 배치를 한 벌로(안드로이드 12+). 표지는 같은 Bitmap 객체를 넘긴다 — RemoteViews 는 같은 객체를 한 번만 실어
         * 보낸다. 크기마다 새로 줄이면 네 배로 실려 바인더 한도(1MB 남짓)에 걸린다.
         */
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
        suspend fun sized(settings: WidgetSettings, id: Int): RemoteViews {
            if (continueModel(candidates, settings.recent) == WidgetModel.Empty) return views(WidgetSize.Wide, settings, id)
            val map = LinkedHashMap<SizeF, RemoteViews>()
            for (size in WidgetSize.entries) map[size.sizeF] = views(size, settings, id)
            return RemoteViews(map)
        }

        suspend fun views(size: WidgetSize, settings: WidgetSettings, id: Int): RemoteViews {
            val model = continueModel(candidates, settings.recent)
            val views = when (model) {
                WidgetModel.Empty -> RemoteViews(context.packageName, R.layout.widget_continue_empty).apply {
                    val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java)
                    setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, requestCode(id, SLOT_OPEN), open, PENDING_FLAGS))
                }
                is WidgetModel.Continue -> RemoteViews(context.packageName, size.layout).apply { bind(model, size, id) }
            }
            return views.apply { style(settings) }
        }

        private suspend fun RemoteViews.bind(model: WidgetModel.Continue, size: WidgetSize, id: Int) {
            setTextViewText(R.id.widget_title, model.title)
            setTextViewText(R.id.widget_byline, model.byline.orEmpty())
            setViewVisibility(R.id.widget_byline, if (model.byline == null) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_percent, model.percentText)
            setProgressBar(R.id.widget_progress, 1000, model.progress, false)
            setTextViewText(R.id.widget_remaining, model.remaining.orEmpty())
            setViewVisibility(R.id.widget_remaining, if (model.remaining == null) View.GONE else View.VISIBLE)
            val cover = cover(model.target, COVER_PX)
            if (cover != null) {
                setImageViewBitmap(R.id.widget_cover, cover)
                setViewVisibility(R.id.widget_cover_title, View.GONE)
            } else {
                // 표지가 없는 책(TXT · 표지 없는 EPUB)은 서재처럼 제목을 얹은 대신 표지. 칸은 투명한 2:3 그림이 잡는다.
                setImageViewBitmap(R.id.widget_cover, sizer)
                setTextViewText(R.id.widget_cover_title, model.title)
                setViewVisibility(R.id.widget_cover_title, View.VISIBLE)
            }
            setOnClickPendingIntent(R.id.widget_root, open(model.target, id, SLOT_MAIN))

            val shown = model.recent.take(size.rows)
            val list = if (shown.isEmpty()) View.GONE else View.VISIBLE
            setViewVisibility(R.id.widget_recent, list)
            setViewVisibility(R.id.widget_divider, list)
            ROWS.forEachIndexed { i, row ->
                val item = shown.getOrNull(i)
                setViewVisibility(row.root, if (item == null) View.GONE else View.VISIBLE)
                if (item == null) return@forEachIndexed
                setTextViewText(row.title, item.title)
                setTextViewText(row.percent, item.percentText)
                setProgressBar(row.progress, 1000, item.progress, false)
                setTextViewText(row.byline, item.byline.orEmpty())
                setViewVisibility(row.byline, if (item.byline == null) View.GONE else View.VISIBLE)
                if (size == WidgetSize.Large) {
                    // 작은 표지는 큰 판에만 있다. 넓은 판에서 읽으면 쓰지도 않을 그림을 바인더에 싣는다.
                    val thumb = cover(item.target, THUMB_PX)
                    setImageViewBitmap(row.cover, thumb ?: sizer)
                    setViewVisibility(row.blank, if (thumb == null) View.VISIBLE else View.GONE)
                }
                // 줄을 누르면 그 책이 열린다(사용자 확정 구상안). 줄이 판 위에 있어 판 누름(맨 위 책)보다 먼저 받는다.
                setOnClickPendingIntent(row.root, open(item.target, id, SLOT_ROWS + i))
            }
        }

        /** 배경 · 불투명도 · 색(위젯 설정). */
        private fun RemoteViews.style(settings: WidgetSettings) {
            setViewVisibility(R.id.widget_bg, if (settings.background) View.VISIBLE else View.GONE)
            setInt(R.id.widget_bg, "setImageAlpha", settings.opacity * 255 / 100)
            // "휴대폰" 이면 아무것도 칠하지 않는다 — 배치의 색 자원이 밤/낮을 따르고, 런처가 그릴 때 그 값을 고른다. 여기서 칠하면
            // 휴대폰이 어두워져도 그린 때의 색이 남는다.
            val p = palette(context, settings.color) ?: return
            setInt(R.id.widget_bg, "setColorFilter", p.surface)
            setInt(R.id.widget_divider, "setColorFilter", p.track)
            setTextColor(R.id.widget_label, p.accentText)
            for (id in listOf(R.id.widget_title, R.id.widget_percent)) setTextColor(id, p.text)
            for (id in listOf(R.id.widget_byline, R.id.widget_remaining, R.id.widget_recent_label, R.id.widget_empty_hint)) setTextColor(id, p.muted)
            for (row in ROWS) {
                setTextColor(row.title, p.text)
                setTextColor(row.percent, p.muted)
                setTextColor(row.byline, p.muted)
            }
            // 막대 색은 안드로이드 12+ 에서만 바꿀 수 있다(그 아래는 ProgressBar 의 색을 RemoteViews 로 넘길 길이 없다). 밝게/어둡게의
            // 강조색 차이는 작아, 아래 판에서는 휴대폰 쪽 막대 색이 남아도 읽는 데 지장이 없다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val accent = ColorStateList.valueOf(p.accent)
                val track = ColorStateList.valueOf(p.track)
                for (id in listOf(R.id.widget_progress) + ROWS.map { it.progress }) {
                    setColorStateList(id, "setProgressTintList", accent)
                    setColorStateList(id, "setProgressBackgroundTintList", track)
                }
            }
        }

        private fun open(target: ContinueTarget, id: Int, slot: Int): PendingIntent =
            PendingIntent.getActivity(context, requestCode(id, slot), ContinueWidget.intentFor(context, target), PENDING_FLAGS)

        /** 표지(높이 [px] 이하). 없으면 null. 한 번 그리는 동안 같은 책 · 같은 크기는 같은 객체다. */
        private suspend fun cover(target: ContinueTarget, px: Int): Bitmap? {
            val key = target to px
            if (key in covers) return covers[key]
            val image = runCatching { coverOf(container, target) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
            return image?.let { rounded(it, px, 6f * density * px / COVER_PX) }.also { covers[key] = it }
        }

        companion object {
            suspend fun load(context: Context): Board = Board(context, candidates(context.container.data))

            /** 표지 없는 책의 칸을 잡는 투명한 2:3 그림. 크기가 아니라 비율만 쓴다(adjustViewBounds). */
            private val sizer: Bitmap by lazy { Bitmap.createBitmap(34, 50, Bitmap.Config.ALPHA_8) }
        }
    }

    /** 읽은 책 · 만화 권을 후보로 모은다. */
    suspend fun candidates(data: ReaderData): List<ContinueCandidate> {
        val activity = data.readingActivity()
        val books = activity.books().map { b ->
            ContinueCandidate(
                target = ContinueTarget.Book(b.id),
                title = b.title?.takeIf { it.isNotBlank() } ?: b.displayName,
                byline = b.author,
                percent = b.percent ?: 0f,
                readAt = maxOf(b.openedAtEpochMs ?: 0L, b.progressAtEpochMs ?: 0L),
                finishedAt = b.finishedAtEpochMs,
            )
        }
        val read = activity.comics()
        if (read.isEmpty()) return books
        // 권 이름("1권")만으로는 무슨 만화인지 모른다 — 서재처럼 작품 이름을 앞에 붙인다.
        val works = data.comics.works().first()
        val comics = read.map { c ->
            val work = works.firstOrNull { ComicReading.entryOf(it, c.unitId) != null }
            val label = work?.let { ComicReading.entryOf(it, c.unitId)?.label }
            ContinueCandidate(
                target = ContinueTarget.Comic(c.unitId),
                title = listOfNotNull(work?.title, label).joinToString(" ").ifEmpty { c.name },
                byline = "만화",
                percent = c.progress.fraction * 100f,
                readAt = c.progress.updatedAtEpochMs,
                finishedAt = c.progress.finishedAtEpochMs,
            )
        }
        return books + comics
    }

    /** 위젯에 얹을 표지 원본. 줄이기 · 둥글리기는 [rounded]. */
    private suspend fun coverOf(container: AppContainer, target: ContinueTarget): Bitmap? {
        val image = when (target) {
            is ContinueTarget.Book -> container.data.library.get(BookId(target.id))?.let { container.covers.cover(it).image }
            is ContinueTarget.Comic -> {
                val unit = container.data.comics.unit(target.unitId) ?: return null
                val work = container.data.comics.works().first().firstOrNull { ComicReading.entryOf(it, unit.id) != null }
                // 사람이 고른 작품 표지가 먼저 — 서재 책장과 같은 그림이어야 홈 화면에서 알아본다.
                // 작품 방향을 넘긴다 — 펼침면 표지는 앞표지 반만 쓰고(0.52.x), 오→왼 작품의 앞표지는 왼쪽 반이다. 넘기지 않으면 서재와
                // 위젯이 다른 반을 보였다.
                work?.let { container.covers.work(it.key).image } ?: container.covers.comic(unit, work?.rightToLeft ?: false)
            }
        } ?: return null
        return image.asAndroidBitmap()
    }

    /**
     * 작게(높이 [px] 이하) 줄이고 모서리를 둥글린다 — RemoteViews 는 그림을 바인더로 런처에 넘기는데, 서재 표지(480px) 그대로면
     * 위젯 몇 개에 바인더 한도(1MB 남짓)를 넘어 위젯이 "불러올 수 없음" 이 된다. 비율은 그대로다 — 자르면 표지가 아니다(0.56.0).
     */
    private fun rounded(source: Bitmap, px: Int, radius: Float): Bitmap {
        val scale = minOf(1f, px.toFloat() / source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(source, w, h, true) else source.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        return out
    }

    /** 밝게/어둡게를 고른 위젯의 색. 앱 테마와 같은 값(colors.xml 의 낮 · 밤)을 그대로 쓴다 — 따로 적으면 테마를 바꿀 때 어긋난다. */
    private class Palette(val surface: Int, val text: Int, val muted: Int, val accent: Int, val accentText: Int, val track: Int)

    private fun palette(context: Context, color: WidgetColor): Palette? {
        val night = when (color) {
            WidgetColor.System -> return null
            WidgetColor.Light -> Configuration.UI_MODE_NIGHT_NO
            WidgetColor.Dark -> Configuration.UI_MODE_NIGHT_YES
        }
        val config = Configuration(context.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
        val c = context.createConfigurationContext(config)
        return Palette(
            c.getColor(R.color.widget_surface), c.getColor(R.color.widget_text), c.getColor(R.color.widget_text_muted),
            c.getColor(R.color.widget_accent), c.getColor(R.color.widget_accent_text), c.getColor(R.color.widget_track),
        )
    }

    /** 목록 한 줄의 뷰 id. 넓은 판에는 저자 · 작은 표지가 없다 — 없는 id 에 거는 일은 RemoteViews 가 건너뛴다. */
    private class Row(val root: Int, val title: Int, val percent: Int, val progress: Int, val byline: Int, val cover: Int, val blank: Int)

    private val ROWS = listOf(
        Row(R.id.widget_recent_1, R.id.widget_recent_title_1, R.id.widget_recent_percent_1, R.id.widget_recent_progress_1, R.id.widget_recent_byline_1, R.id.widget_recent_cover_1, R.id.widget_recent_blank_1),
        Row(R.id.widget_recent_2, R.id.widget_recent_title_2, R.id.widget_recent_percent_2, R.id.widget_recent_progress_2, R.id.widget_recent_byline_2, R.id.widget_recent_cover_2, R.id.widget_recent_blank_2),
        Row(R.id.widget_recent_3, R.id.widget_recent_title_3, R.id.widget_recent_percent_3, R.id.widget_recent_progress_3, R.id.widget_recent_byline_3, R.id.widget_recent_cover_3, R.id.widget_recent_blank_3),
    )

    /** 표지 높이 상한(px). 큰 판의 표지 칸(높이 약 140dp)을 xxhdpi 에서 채우고도 바인더 한도에 멀다. */
    private const val COVER_PX = 300

    /** 목록 줄의 작은 표지(40dp). 큰 표지를 그대로 쓰면 세 장이 바인더 한도를 갉아 먹는다. */
    private const val THUMB_PX = 120

    /**
     * 누를 거리마다 다른 요청 번호. 같은 번호 · 같은 동작이면 안드로이드가 같은 PendingIntent 로 보고 마지막 것의 책으로 덮어쓴다 —
     * 목록 세 줄이 모두 마지막 줄의 책을 열었다. 위젯 번호도 섞는다(두 위젯의 목록이 다를 수 있다).
     */
    private fun requestCode(widgetId: Int, slot: Int): Int = (widgetId shl 3) or slot
    private const val SLOT_OPEN = 0
    private const val SLOT_MAIN = 1
    private const val SLOT_ROWS = 2
    private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}

/**
 * 읽기 기록이 바뀌면 위젯을 다시 그리고, 기기 간 이어 읽기를 켰으면 서버에 이 기기의 기록을 쓴다. 리더마다 고리를 걸지 않는다 — DB 표가 바뀐 것을 본다
 * (Room 의 바뀜 알림). 리더가 늘어도(만화 · 웹툰 · 그림책) 빠뜨릴 자리가 없다.
 *
 * 쪽을 넘길 때마다 그리지 않게 잠깐 기다렸다 한 번 한다. 앱이 뒤로 갈 때는 기다리지 않고 바로 한다([flush]) — 그 뒤에 프로세스가
 * 거둬지면 마지막 자리가 홈 화면 · 다른 기기에 닿지 않는다.
 */
internal class ReadingChanges(private val context: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    init {
        container.data.readingActivity().observe(::schedule)
    }

    @Synchronized
    fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            run()
        }
    }

    /** 기다리지 않고 지금. 앱이 뒤로 갈 때. */
    @Synchronized
    fun flush() {
        pending?.cancel()
        pending = scope.launch { run() }
    }

    /** 기다림 없이 이 자리에서 끝까지(시험). [flush] 는 다른 스레드에 맡겨, 끝났는지 알 길이 없다. */
    internal suspend fun runNow() = run()

    /** 걸린 일을 거둔다(시험의 끝). 안 거두면 끝난 시험의 늦은 쓰기가 다음 시험의 작업 예약에 끼어든다. */
    internal fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun run() {
        // 하나가 실패해도 다른 하나는 한다 — 위젯을 못 그렸다고 다른 기기에 읽은 자리가 안 가면 안 된다.
        runCatching { ContinueWidgets.refresh(context) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; android.util.Log.w("OloWidget", "widget not refreshed", it) }
        // 기기 간 이어 읽기: 이 기기의 기록을 서버에 적는다(꺼져 있으면 아무것도 하지 않는다). 서버에 닿지 못했으면 나중에 다시
        // 하도록 걸어 둔다 — 앱이 뒤로 갈 때 서버가 잠들어 있으면 마지막 자리가 이 휴대폰에만 남는다.
        runCatching { container.sync.writeOwn() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; android.util.Log.w("OloSync", "reading state not written", it) }
        SyncRetry.afterSync(context, container.sync)
    }

    private companion object {
        const val DEBOUNCE_MS = 1_500L
    }
}
