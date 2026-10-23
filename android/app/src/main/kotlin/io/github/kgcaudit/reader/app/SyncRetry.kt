package io.github.kgcaudit.reader.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kgcaudit.reader.data.sync.ReadingSync
import java.util.concurrent.TimeUnit

/**
 * 서버에 닿지 못해 못 올린 읽은 자리를 나중에 다시 올린다(사용자 결정 — 잠든 · 꺼진 서버 대비). 앱을 닫는 순간 NAS 가 잠들어 있으면
 * 마지막 자리가 다음에 앱을 쓸 때까지 이 휴대폰에만 남아, 그 사이 다른 기기가 모른다. 안드로이드 작업 예약에 맡기면 앱이 닫혀
 * 있어도 네트워크가 있을 때 돈다.
 *
 * 15분 뒤부터, 안 되면 두 배씩 늘려(최대 5시간 — 작업 예약의 한도) 다시 한다. 되면 멈춘다. 사람이 고쳐야 하는 까닭(비밀번호 ·
 * 권한 · 인증서)은 다시 해도 같아 멈춘다 — 화면이 까닭을 보인다.
 */
internal object SyncRetry {
    const val WORK = "olo-sync-retry"
    const val FIRST_DELAY_MINUTES = 15L

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(FIRST_DELAY_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        // 이미 걸려 있으면 그대로 둔다 — 쪽을 넘길 때마다 다시 걸면 15분이 계속 밀려 영영 돌지 않는다.
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request) }
            .onFailure { android.util.Log.w("OloSync", "retry not scheduled", it) }
    }

    /** 맞추기가 된 뒤 · 끈 뒤. 걸린 다시 하기가 없으면 아무 일도 없다. */
    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    }

    /** 맞추기 결과를 보고 다시 할지. 켜 있고, 시간이 지나면 풀릴 까닭일 때만. */
    fun afterSync(context: Context, sync: ReadingSync) {
        val problem = sync.status.value.problem
        if (sync.enabled && problem != null && problem in ReadingSync.RETRYABLE) schedule(context) else if (problem == null) cancel(context)
    }
}

/** [SyncRetry] 의 일 하나: 이 기기 기록을 올리고 다른 기기 것을 받는다. 받기까지 하는 까닭 — 서버에 실제로 닿아야 까닭이 지워진다. */
internal class SyncRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = applicationContext.container.sync
        if (!sync.enabled) return Result.success()
        sync.syncNow()
        val problem = sync.status.value.problem
        return if (problem != null && problem in ReadingSync.RETRYABLE) Result.retry() else Result.success()
    }
}
