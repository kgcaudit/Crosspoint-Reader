package io.github.kgcaudit.reader.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
    ) : WidgetModel
}

/**
 * 위젯에 무엇을 보일지(사용자 결정 3-1: 한 권, 크게). 저장소 없이 시험하는 순수 함수다.
 *
 * **다 읽지 않은 것 가운데 가장 최근**을 고른다. 방금 다 읽은 책을 보이면 "이어 읽기" 가 할 일이 없다 — 누르면 끝 쪽(또는 다시
 * 처음)이 열릴 뿐이고, 사람은 그 앞에 읽던 다른 책으로 돌아가고 싶다. 다 읽은 뒤 다시 펼친 책(다 읽은 때보다 한참 뒤에 읽음)은
 * 다시 읽는 중이라 후보다. 다 읽지 않은 것이 없으면 빈 판("OLO eBook 열기")이다.
 */
internal fun continueModel(candidates: List<ContinueCandidate>): WidgetModel {
    val pick = candidates.filter { !it.isFinished() }.maxByOrNull { it.readAt } ?: return WidgetModel.Empty
    val percent = pick.percent.coerceIn(0f, 100f)
    return WidgetModel.Continue(
        target = pick.target,
        title = pick.title,
        byline = pick.byline?.takeIf { it.isNotBlank() },
        // 내려 센다: 99.6% 를 "100%" 로 올리면 다 읽지 않은 책이 다 읽은 것처럼 보인다.
        percentText = "${percent.toInt()}%",
        progress = (percent * 10).toInt(),
        remaining = pick.remainingMinutes?.takeIf { it > 0 }?.let { "남은 ${minutesWords(it)}" },
    )
}

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
class ContinueWidget : AppWidgetProvider() {
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

/** 위젯 그리기 · 다시 그리기. 앱 전체에 하나([AppContainer.widgets]). */
internal object ContinueWidgets {

    /** 놓인 위젯이 있으면 지금 모습으로 다시 그린다. 없으면 아무것도 읽지 않는다(위젯을 안 쓰는 사람에게 비용이 없다). */
    suspend fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
        if (ids.isEmpty()) return
        render(context, manager, ids)
    }

    suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    /** 지금 모습의 위젯. 시험이 뷰로 펴 그려 본다. */
    suspend fun views(context: Context): RemoteViews {
        val container = context.container
        val model = continueModel(candidates(container.data))
        return when (model) {
            WidgetModel.Empty -> RemoteViews(context.packageName, R.layout.widget_continue_empty).apply {
                val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java)
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, REQUEST_OPEN, open, PENDING_FLAGS))
            }
            is WidgetModel.Continue -> RemoteViews(context.packageName, R.layout.widget_continue).apply {
                setTextViewText(R.id.widget_title, model.title)
                setTextViewText(R.id.widget_byline, model.byline.orEmpty())
                setViewVisibility(R.id.widget_byline, if (model.byline == null) View.GONE else View.VISIBLE)
                setTextViewText(R.id.widget_percent, model.percentText)
                setProgressBar(R.id.widget_progress, 1000, model.progress, false)
                setTextViewText(R.id.widget_remaining, model.remaining.orEmpty())
                setViewVisibility(R.id.widget_remaining, if (model.remaining == null) View.GONE else View.VISIBLE)
                val cover = runCatching { coverOf(container, model.target, context.resources.displayMetrics.density) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
                if (cover != null) {
                    setImageViewBitmap(R.id.widget_cover, cover)
                    setViewVisibility(R.id.widget_cover_title, View.GONE)
                } else {
                    // 표지가 없는 책(TXT · 표지 없는 EPUB)은 서재처럼 제목을 얹은 대신 표지.
                    setTextViewText(R.id.widget_cover_title, model.title)
                    setViewVisibility(R.id.widget_cover_title, View.VISIBLE)
                }
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(context, REQUEST_CONTINUE, ContinueWidget.intentFor(context, model.target), PENDING_FLAGS),
                )
            }
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

    /**
     * 위젯에 얹을 표지. 작게(높이 [COVER_PX] 이하) 줄이고 모서리를 둥글린다 — RemoteViews 는 그림을 바인더로 런처에 넘기는데,
     * 서재 표지(480px) 그대로면 위젯 몇 개에 바인더 한도(1MB 남짓)를 넘어 위젯이 "불러올 수 없음" 이 된다.
     */
    private suspend fun coverOf(container: AppContainer, target: ContinueTarget, density: Float): Bitmap? {
        val image = when (target) {
            is ContinueTarget.Book -> container.data.library.get(BookId(target.id))?.let { container.covers.cover(it).image }
            is ContinueTarget.Comic -> {
                val unit = container.data.comics.unit(target.unitId) ?: return null
                val work = container.data.comics.works().first().firstOrNull { ComicReading.entryOf(it, unit.id) != null }
                // 사람이 고른 작품 표지가 먼저 — 서재 책장과 같은 그림이어야 홈 화면에서 알아본다.
                work?.let { container.covers.work(it.key).image } ?: container.covers.comic(unit)
            }
        } ?: return null
        return rounded(image.asAndroidBitmap(), 6f * density)
    }

    private fun rounded(source: Bitmap, radius: Float): Bitmap {
        val scale = minOf(1f, COVER_PX.toFloat() / source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(source, w, h, true) else source.copy(Bitmap.Config.ARGB_8888, false)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        return out
    }

    /** 표지 높이 상한(px). 위젯의 표지 칸(높이 약 120dp)을 xxhdpi 에서 채우고도 바인더 한도에 멀다. */
    private const val COVER_PX = 300

    private const val REQUEST_OPEN = 1
    private const val REQUEST_CONTINUE = 2
    private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}

/**
 * 읽기 기록이 바뀌면 위젯을 다시 그린다. 리더마다 고리를 걸지 않는다 — DB 표가 바뀐 것을 본다
 * (Room 의 바뀜 알림). 리더가 늘어도(만화 · 웹툰 · 그림책) 빠뜨릴 자리가 없다.
 *
 * 쪽을 넘길 때마다 그리지 않게 잠깐 기다렸다 한 번 한다. 앱이 뒤로 갈 때는 기다리지 않고 바로 한다([flush]) — 그 뒤에 프로세스가
 * 거둬지면 마지막 자리가 홈 화면에 닿지 않는다.
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

    private suspend fun run() {
        runCatching { ContinueWidgets.refresh(context) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; android.util.Log.w("OloWidget", "widget not refreshed", it) }
    }

    private companion object {
        const val DEBOUNCE_MS = 1_500L
    }
}
