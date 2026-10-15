package io.github.kgcaudit.reader.app

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy

/**
 * 읽기 잠금의 "알림도 끄기": 잠긴 동안 방해 금지. 전화는 울린다(잠금이 전화에 풀리는 것과 짝).
 *
 * 우리가 건 것은 반드시 되돌린다 — 앱이 죽어도. 걸 때 "우리가 바꿨다" 를 저장소에 먼저 적고, 다음에 켤 때 남아 있으면
 * 되돌린다([recover]). 적지 않으면 잠금 중에 앱이 거둬졌을 때 사용자의 휴대폰이 영영 방해 금지에 머문다.
 *
 * 안드로이드 15(API 35) 에서 targetSdk 35 이상이면 자동 규칙(AutomaticZenRule)을 쓴다. 15 부터 그런 앱의
 * setInterruptionFilter 는 시스템이 암묵 규칙으로 바꿔 다루는데, 우리 이름 · 정책을 붙인 규칙을 직접 만들면 설정의 "모드"
 * 목록에 "읽기 잠금" 으로 보이고, 지울 때 사용자가 따로 켜 둔 방해 금지를 건드리지 않는다(규칙만 사라진다). 그보다
 * 앞에서는 지금 거르기 · 정책을 적어 두었다가 그대로 돌려놓는다. 이 앱의 targetSdk 는 35 다(app/build.gradle.kts).
 */
internal class ReadLockQuiet(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** 사람이 마지막으로 고른 "알림도 끄기". 처음은 끔. 조판과 상관없어 LayoutSpec 이 아니라 여기에 둔다. */
    var chosen: Boolean
        get() = store.getBoolean(KEY_CHOSEN, false)
        set(value) { store.edit().putBoolean(KEY_CHOSEN, value).apply() }

    /** 방해 금지를 바꿀 허락(설정 › 방해 금지 권한)이 있다. */
    fun granted(): Boolean = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)

    /** targetSdk 35 이상(이 앱). 기기 판 검사는 부르는 자리에 따로 둔다 — lint 가 API 35 호출을 그 자리에서 확인한다. */
    private val targets35: Boolean get() = context.applicationInfo.targetSdkVersion >= 35

    /** 건다. 허락이 없으면(그사이 거둠) 아무것도 하지 않는다 — 잠금은 그대로 건다. */
    fun on() {
        if (store.getBoolean(KEY_CHANGED, false) || !granted()) return
        if (Build.VERSION.SDK_INT >= 35 && targets35) onWithRule() else onWithFilter()
    }

    /** 우리가 건 것이 있으면 되돌린다. 없으면 아무것도 하지 않는다 — 사용자가 따로 켠 방해 금지를 끄지 않게. */
    fun off() {
        if (!store.getBoolean(KEY_CHANGED, false)) return
        runCatching {
            val rule = store.getString(KEY_RULE, null)
            if (rule != null) {
                nm.removeAutomaticZenRule(rule)
            } else {
                restorePolicy()
                nm.setInterruptionFilter(store.getInt(KEY_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL).takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN } ?: NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
        // 되돌리기가 실패해도(허락을 거둠) 표시는 지운다 — 남기면 다음에 켤 때마다 다시 되돌리려 들어, 사람이 그 뒤에 직접 바꾼
        // 방해 금지를 엎는다.
        store.edit().putBoolean(KEY_CHANGED, false).remove(KEY_RULE).apply()
    }

    /** 앱이 새로 떴다. 지난번에 건 것이 남아 있으면(잠금 중에 앱이 거둬짐) 되돌린다. */
    fun recover() = off()

    private fun onWithFilter() {
        // 정책을 한 번도 정하지 않은 기기는 null 을 준다 — 그때는 거르기만 되돌린다(정책은 우리가 정한 채 남지만 거르기가
        // "모두 허용" 이면 쓰이지 않는다).
        val before: NotificationManager.Policy? = runCatching { nm.notificationPolicy }.getOrNull()
        val filter = nm.currentInterruptionFilter
        // 되돌릴 값을 **먼저** 적는다. 걸고 나서 적으면 그 사이 앱이 죽었을 때 되돌릴 값이 없다.
        val edit = store.edit().putBoolean(KEY_CHANGED, true).putInt(KEY_FILTER, filter)
        if (before != null) {
            edit.putInt(KEY_CATEGORIES, before.priorityCategories)
                .putInt(KEY_CALLERS, before.priorityCallSenders)
                .putInt(KEY_MESSAGERS, before.priorityMessageSenders)
                .putInt(KEY_SUPPRESSED, before.suppressedVisualEffects)
            if (Build.VERSION.SDK_INT >= 30) edit.putInt(KEY_CONVERSATIONS, before.priorityConversationSenders)
        } else {
            edit.remove(KEY_CATEGORIES).remove(KEY_CONVERSATIONS)
        }
        edit.commit()
        runCatching {
            nm.notificationPolicy = NotificationManager.Policy(
                QUIET_CATEGORIES,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
                QUIET_SUPPRESSED,
            )
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        }
    }

    private fun restorePolicy() {
        if (!store.contains(KEY_CATEGORIES)) return
        val categories = store.getInt(KEY_CATEGORIES, 0)
        val callers = store.getInt(KEY_CALLERS, NotificationManager.Policy.PRIORITY_SENDERS_ANY)
        val messagers = store.getInt(KEY_MESSAGERS, NotificationManager.Policy.PRIORITY_SENDERS_ANY)
        val suppressed = store.getInt(KEY_SUPPRESSED, 0)
        nm.notificationPolicy = if (Build.VERSION.SDK_INT >= 30 && store.contains(KEY_CONVERSATIONS)) {
            NotificationManager.Policy(categories, callers, messagers, suppressed, store.getInt(KEY_CONVERSATIONS, 0))
        } else {
            NotificationManager.Policy(categories, callers, messagers, suppressed)
        }
    }

    @androidx.annotation.RequiresApi(35)
    private fun onWithRule() {
        val policy = ZenPolicy.Builder()
            .disallowAllSounds()
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowRepeatCallers(true)
            // 알람 · 미디어는 그대로 — 설명 판이 "소리는 그대로" 라고 했다. 막으면 듣기(TTS) · 넘김 소리가 끊긴다.
            .allowAlarms(true)
            .allowMedia(true)
            .showPeeking(false)
            .build()
        val rule = AutomaticZenRule.Builder(RULE_NAME, RULE_URI)
            .setType(AutomaticZenRule.TYPE_IMMERSIVE)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setZenPolicy(policy)
            // 규칙에는 주인(조건 서비스)이나 설정 화면이 있어야 한다. 따로 둘 화면이 없어 이 앱을 연다.
            .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
            .setManualInvocationAllowed(false)
            .build()
        runCatching {
            val id = nm.addAutomaticZenRule(rule)
            store.edit().putBoolean(KEY_CHANGED, true).putString(KEY_RULE, id).commit()
            nm.setAutomaticZenRuleState(id, Condition(RULE_URI, RULE_NAME, Condition.STATE_TRUE, Condition.SOURCE_USER_ACTION))
        }
    }

    companion object {
        private const val STORE = "read_lock"
        private const val KEY_CHOSEN = "quiet"
        private const val KEY_CHANGED = "quiet.changed"
        private const val KEY_FILTER = "quiet.filter"
        private const val KEY_CATEGORIES = "quiet.categories"
        private const val KEY_CALLERS = "quiet.callers"
        private const val KEY_MESSAGERS = "quiet.messagers"
        private const val KEY_SUPPRESSED = "quiet.suppressed"
        private const val KEY_CONVERSATIONS = "quiet.conversations"
        private const val KEY_RULE = "quiet.rule"
        private const val RULE_NAME = "읽기 잠금"
        private val RULE_URI: Uri = Uri.parse("olo://read-lock")

        /**
         * 전화(누구든) · 같은 사람이 다시 건 전화 · 알람 · 미디어. 미디어를 빼면 안드로이드 9 부터 방해 금지가 미디어 소리를 꺼
         * 듣기와 넘김 소리가 사라진다.
         */
        internal const val QUIET_CATEGORIES = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
            NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
            NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA

        /** 걸러진 알림이 화면 위로 내려오지 않게(엿보기 · 불빛). 잠금이 막고 싶은 것이 그 내려옴이다. */
        private const val QUIET_SUPPRESSED = NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
            NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS
    }
}
