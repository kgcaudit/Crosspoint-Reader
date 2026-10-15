package io.github.kgcaudit.reader.app

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * 벨 · 통화를 권한 없이 알아챈다. 화면 고정 중에는 전화 알림과 통화 화면이 막힌다(AOSP `isNewTaskLockTaskModeViolation` —
 * 전화 앱 예외는 기기 관리자 기능이 있어야 열린다) — 그래서 벨이 울리는 순간 우리가 풀어야 한다.
 *
 * - 안드로이드 12(API 31)부터: 오디오 모드. 벨은 MODE_RINGTONE, 통화는 MODE_IN_CALL · MODE_IN_COMMUNICATION(인터넷 전화 앱도).
 *   전화 상태(TelephonyManager)는 31 부터 READ_PHONE_STATE 없이는 들을 수 없다 — 그 권한을 묻지 않는다.
 * - 그 앞: 전화 상태의 CALL_STATE_RINGING · OFFHOOK. 31 앞에서는 권한 없이 들린다.
 *
 * [onChange] 는 메인 스레드에서 "통화가 걸려 있다(벨 · 통화)" 를 받는다.
 */
internal class ReadLockCalls(private val context: Context, private val onChange: (Boolean) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var modeListener: Any? = null
    private var phoneListener: PhoneStateListener? = null

    /** 듣기 시작하고 지금 상태를 돌려준다. 시작할 때 이미 통화 중일 수 있다(그때 잠그면 통화 화면이 막힌다). */
    fun start(): Boolean {
        if (modeListener != null || phoneListener != null) return now()
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = AudioManager.OnModeChangedListener { mode -> onChange(activeMode(mode)) }
            runCatching { audio.addOnModeChangedListener(context.mainExecutor, listener) }
                .onSuccess { modeListener = listener }
                .onFailure { android.util.Log.w("ReadLock", "cannot listen to the audio mode", it) }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("PhoneStateListener 는 31 부터 낡았다 — 31 앞에서만 쓴다")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onChange(state != TelephonyManager.CALL_STATE_IDLE)
            }
            val phone = context.getSystemService(TelephonyManager::class.java)
            @Suppress("DEPRECATION")
            runCatching { phone?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
                .onSuccess { phoneListener = listener }
                .onFailure { android.util.Log.w("ReadLock", "cannot listen to the call state", it) }
        }
        return now()
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 31) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { runCatching { audio.removeOnModeChangedListener(it) } }
        } else {
            @Suppress("DEPRECATION")
            phoneListener?.let { l -> runCatching { context.getSystemService(TelephonyManager::class.java)?.listen(l, PhoneStateListener.LISTEN_NONE) } }
        }
        modeListener = null
        phoneListener = null
    }

    private fun now(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        activeMode(audio.mode)
    } else {
        @Suppress("DEPRECATION")
        runCatching { context.getSystemService(TelephonyManager::class.java)?.callState }.getOrNull()?.let { it != TelephonyManager.CALL_STATE_IDLE } ?: false
    }

    companion object {
        /**
         * 이 오디오 모드가 벨 · 통화인가. NORMAL 이 아닌 것은 모두 통화로 본다 — 통화 거르기(CALL_SCREENING) · 다른 기기로 돌린
         * 통화(…_REDIRECT)도 사람이 전화를 받는 중이다. 모르는 값(-1 등)은 통화가 아니다.
         */
        fun activeMode(mode: Int): Boolean = mode > AudioManager.MODE_NORMAL
    }
}
