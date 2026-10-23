package io.github.kgcaudit.reader.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 구글 계정을 잇는 한 걸음의 결과. */
internal sealed interface ConnectStep {
    /** 이어졌다. 계정 메일을 모르면 null(구글이 알려 주지 않는 경우). */
    data class Done(val email: String?) : ConnectStep

    /** 계정 고르기 · 허락 화면을 사람에게 보여야 한다. 끝나면 [DriveAccount.finish] 에 결과를 넘긴다. */
    data class NeedsUser(val sender: IntentSender) : ConnectStep

    /** 잇지 못했다. [message] 를 그대로 보인다. */
    data class Failed(val message: String) : ConnectStep
}

/**
 * 이어 읽기가 쓰는 구글 계정(0.57.0). 앱은 비밀번호를 다루지 않는다 — 계정 고르기 · 허락은 Google Play 서비스의 화면이 하고, 앱은
 * 드라이브 앱 공간(`drive.appdata`)만 쓰는 짧은 토큰을 받는다. 시험은 가짜를 꽂는다([AppContainer.driveAccount]).
 */
internal interface DriveAccount {
    /** 이은 계정의 메일. 잇지 않았으면 null. */
    val email: StateFlow<String?>
    val connected: Boolean

    suspend fun connect(): ConnectStep
    suspend fun finish(data: Intent?): ConnectStep

    /** 조용히 받는 토큰(화면 없이). 허락이 취소되었거나 잇지 않았으면 null — 동기화가 "다시 연결" 을 묻는다. */
    suspend fun token(fresh: Boolean): String?

    /** 이 기기에서 계정을 잊는다(드라이브의 기록은 그대로). */
    suspend fun disconnect()
}

/** Google Play 서비스의 AuthorizationClient 로 잇는 진짜 계정. */
internal class GoogleDriveAccount(private val app: Application) : DriveAccount {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _email = MutableStateFlow(prefs.getString(EMAIL, null)?.takeIf { prefs.getBoolean(CONNECTED, false) })
    override val email: StateFlow<String?> = _email.asStateFlow()
    override val connected: Boolean get() = prefs.getBoolean(CONNECTED, false)

    /** 마지막으로 받은 토큰. 드라이브가 거절하면(만료) 이것을 지우고 새로 받는다. */
    @Volatile
    private var last: String? = null

    private val client get() = Identity.getAuthorizationClient(app)
    private val request: AuthorizationRequest get() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    override suspend fun connect(): ConnectStep = try {
        val result = client.authorize(request).await()
        if (result.hasResolution()) {
            result.pendingIntent?.intentSender?.let { ConnectStep.NeedsUser(it) } ?: ConnectStep.Failed(FAILED)
        } else {
            done(result)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(e)
    }

    override suspend fun finish(data: Intent?): ConnectStep = try {
        // 사람이 뒤로 가기로 닫으면 결과가 비어 온다 — 오류가 아니라 "켜지 않음" 이다.
        if (data == null) ConnectStep.Failed(CANCELLED) else done(client.getAuthorizationResultFromIntent(data))
    } catch (e: ApiException) {
        if (e.statusCode == CommonStatusCodes.CANCELED) ConnectStep.Failed(CANCELLED) else failed(e)
    }

    private fun done(result: AuthorizationResult): ConnectStep {
        if (SCOPE !in result.grantedScopes) return ConnectStep.Failed("구글 드라이브 허락을 받지 못했습니다. 다시 켜고 ‘허용’을 눌러 주세요.")
        last = result.accessToken
        val mail = result.toGoogleSignInAccount()?.email
        prefs.edit().putBoolean(CONNECTED, true).putString(EMAIL, mail).apply()
        _email.value = mail
        return ConnectStep.Done(mail)
    }

    private fun failed(e: Exception): ConnectStep {
        android.util.Log.w("OloSync", "google account not connected", e)
        val code = (e as? ApiException)?.statusCode
        return ConnectStep.Failed(
            when (code) {
                // 이 앱(패키지 + 서명)이 구글 클라우드에 등록되지 않았다 — 사람이 고칠 수 없는 앱 쪽 준비 문제다.
                CommonStatusCodes.DEVELOPER_ERROR -> "이 판은 아직 구글 드라이브 연결이 준비되지 않았습니다(앱 등록 필요)."
                CommonStatusCodes.NETWORK_ERROR -> "인터넷에 연결되지 않아 구글 계정을 잇지 못했습니다."
                else -> FAILED
            },
        )
    }

    override suspend fun token(fresh: Boolean): String? {
        if (!connected) return null
        if (fresh) last?.let { old -> withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(app, old) } } }
        if (!fresh) last?.let { return it }
        return try {
            val result = client.authorize(request).await()
            // 다시 허락을 물어야 한다 = 사람이 허락을 거뒀다. 화면 없이는 받을 수 없다.
            if (result.hasResolution()) null else result.accessToken.also { last = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiException) {
            // 인터넷이 없을 때도 여기로 온다. 토큰이 없다고 하면 "허락 취소" 로 잘못 보이니 옛 토큰을 준다 — 드라이브 호출이 인터넷
            // 없음으로 실패해 바른 까닭이 남는다.
            if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) last ?: OFFLINE_PLACEHOLDER else null
        }
    }

    override suspend fun disconnect() {
        last?.let { old -> withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(app, old) } } }
        last = null
        prefs.edit().putBoolean(CONNECTED, false).remove(EMAIL).apply()
        _email.value = null
    }

    private companion object {
        const val PREFS = "drive_account"
        const val CONNECTED = "connected"
        const val EMAIL = "email"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val FAILED = "구글 계정을 잇지 못했습니다. 잠시 뒤 다시 시도해 주세요."
        const val CANCELLED = "구글 계정을 고르지 않아 켜지 않았습니다."

        /** 인터넷이 없을 때 넘기는 자리표. 드라이브 호출이 어차피 연결 단계에서 실패한다. */
        const val OFFLINE_PLACEHOLDER = "offline"
    }
}

/** Play 서비스의 Task 를 기다린다(따로 라이브러리를 싣지 않으려고 직접). */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val e = task.exception
        if (e != null) cont.resumeWith(Result.failure(e)) else cont.resume(task.result)
    }
}
