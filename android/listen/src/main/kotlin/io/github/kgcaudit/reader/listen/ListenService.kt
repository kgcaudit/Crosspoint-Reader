package io.github.kgcaudit.reader.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 앱에 하나뿐인 "지금 듣는 것". 리더 화면이 만들어 붙이고, 서비스가 여기서 찾아 잠금 화면 카드를 그린다.
 *
 * 화면(액티비티)과 서비스가 서로를 직접 쥐지 않게 가운데 둔다. 화면을 끄면 액티비티는 멈추지만 서비스는 계속 돌고,
 * 둘 다 이 한 곳만 보면 같은 듣기를 조종한다.
 */
object ListenHub {
    /** 듣기가 쓰는 코루틴 범위. 화면(Compose)이 멈춰도 읽기는 이어져야 하므로 화면의 범위를 쓰지 않는다. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _current = MutableStateFlow<Listening?>(null)
    val current: StateFlow<Listening?> = _current.asStateFlow()

    /** 새 듣기를 붙이고 서비스를 띄운다. 앞의 듣기가 있으면 끈다(한 번에 한 책만 읽는다). */
    fun attach(context: Context, listening: Listening) {
        _current.value?.takeIf { it !== listening }?.close()
        _current.value = listening
        val intent = Intent(context, ListenService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    /** 듣기를 끈다(조종판 ✕ · 책 닫기). 서비스는 이것을 보고 스스로 내려간다. */
    fun detach(listening: Listening? = _current.value) {
        if (listening == null || _current.value !== listening) return
        listening.close()
        _current.value = null
    }
}

/**
 * 화면을 꺼도 계속 읽게 하는 서비스(L6). 하는 일은 셋뿐이다:
 *
 * 1. 앞에 선 서비스(foreground, 종류 mediaPlayback)로 떠 있어 앱이 뒤로 가도 시스템이 멈추지 않게 한다.
 * 2. 미디어 세션 · 재생 알림: 잠금 화면 · 알림 창의 카드, 이어폰 단추.
 * 3. 오디오 포커스와 "이어폰 빠짐": 전화가 오면 멈췄다 이어 읽고, 이어폰이 빠지면 멈춘다(스피커로 갑자기
 *    책을 읽어 버리지 않게).
 *
 * 읽기 자체는 [Listening] 이 한다. 여기서는 단추를 그쪽으로 넘기기만 한다.
 */
class ListenService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private var wake: PowerManager.WakeLock? = null
    private var foreground = false
    private var noisyRegistered = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) ListenHub.current.value?.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        session = MediaSession(this, "OloListen").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { ListenHub.current.value?.play() }
                override fun onPause() { ListenHub.current.value?.pause() }
                override fun onSkipToNext() { ListenHub.current.value?.next() }
                override fun onSkipToPrevious() { ListenHub.current.value?.previous() }
                override fun onStop() { ListenHub.detach() }
            })
            isActive = true
        }
        channel()
        scope.launch {
            ListenHub.current
                .flatMapLatest { l -> l?.state?.map { l to it } ?: flowOf(null) }
                .collectLatest { pair -> render(pair?.first, pair?.second) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 뒤 몇 초 안에 앞에 서지 않으면 시스템이 앱을 죽인다 — 무엇보다 먼저.
        val listening = ListenHub.current.value
        goForeground(listening, listening?.state?.value ?: ListenState())
        when (intent?.action) {
            ACTION_TOGGLE -> listening?.toggle()
            ACTION_NEXT -> listening?.next()
            ACTION_PREVIOUS -> listening?.previous()
            ACTION_CLOSE -> ListenHub.detach()
        }
        if (listening == null) stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseFocus()
        releaseWake()
        if (noisyRegistered) runCatching { unregisterReceiver(noisy) }
        session.release()
        super.onDestroy()
    }

    private fun render(listening: Listening?, state: ListenState?) {
        if (listening == null || state == null || !state.active) {
            stop()
            return
        }
        if (state.playing) {
            requestFocus()
            acquireWake()
            registerNoisy()
        } else {
            releaseWake()
            if (!resumeOnFocus) releaseFocus()
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, listening.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.sentenceText)
                .build(),
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, state.rate)
                .build(),
        )
        goForeground(listening, state)
    }

    private fun goForeground(listening: Listening?, state: ListenState) {
        val notification = notification(listening, state)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun stop() {
        releaseFocus()
        releaseWake()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    /** 잠금 화면 · 알림 창의 카드. 틀은 휴대폰이 그리고, 여기서는 글과 단추만 준다. */
    private fun notification(listening: Listening?, state: ListenState): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val timer = state.timerEndsAtMs?.let { " · 잠자기 ${((it - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1}분" }
            ?: if (state.timer == ListenTimer.ChapterEnd) " · 장 끝에서 멈춤" else ""
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_listen)
            .setContentTitle(listening?.title ?: "듣기")
            .setContentText(state.sentenceText.ifEmpty { "듣기 준비 중" })
            .setSubText(if (state.playing) "듣는 중$timer" else "멈춤$timer")
            .setContentIntent(open)
            .setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "앞 문장", ACTION_PREVIOUS))
            .addAction(
                if (state.playing) action(android.R.drawable.ic_media_pause, "멈춤", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "읽기", ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_next, "다음 문장", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "듣기 끝내기", ACTION_CLOSE))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(icon: Int, title: String, what: String): Notification.Action {
        val intent = Intent(this, ListenService::class.java).setAction(what)
        val pending = PendingIntent.getService(this, what.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(icon, title, pending).build()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        // 낮은 중요도: 소리 · 진동 없이 카드만. 문장이 바뀔 때마다 울리면 듣기를 방해한다.
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "듣기", NotificationManager.IMPORTANCE_LOW))
    }

    private fun requestFocus() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                val listening = ListenHub.current.value ?: return@setOnAudioFocusChangeListener
                when (change) {
                    // 전화 · 길 안내: 잠깐 멈췄다가 돌려받으면 이어 읽는다. 말소리는 소리를 줄여 겹쳐 읽으면 알아듣기 어렵다.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        resumeOnFocus = listening.state.value.playing
                        listening.pause()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (resumeOnFocus) listening.play()
                        resumeOnFocus = false
                    }
                    // 다른 앱이 음악을 틀었다 — 멈추고 돌아오지 않는다.
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnFocus = false
                        listening.pause()
                        releaseFocus()
                    }
                }
            }
            .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        noisyRegistered = true
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        // 세 시간이 지나면 저절로 놓는다 — 무엇이 잘못돼도 밤새 배터리를 먹지 않게.
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OloEbook:listen").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        wake?.takeIf { it.isHeld }?.release()
        wake = null
    }

    companion object {
        private const val CHANNEL = "listen"
        private const val NOTIFICATION_ID = 7301
        const val ACTION_TOGGLE = "olo.listen.TOGGLE"
        const val ACTION_NEXT = "olo.listen.NEXT"
        const val ACTION_PREVIOUS = "olo.listen.PREVIOUS"
        const val ACTION_CLOSE = "olo.listen.CLOSE"
    }
}
