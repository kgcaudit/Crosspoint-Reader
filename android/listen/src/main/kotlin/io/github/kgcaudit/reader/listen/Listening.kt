package io.github.kgcaudit.reader.listen

import io.github.kgcaudit.reader.layout.book.Sentence
import io.github.kgcaudit.reader.layout.book.indexAt
import io.github.kgcaudit.reader.layout.book.WordJoin
import io.github.kgcaudit.reader.layout.book.joinWords
import io.github.kgcaudit.reader.layout.book.speakable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 잠자기 타이머(L4). [minutes] 가 null 이면 시간이 아니라 "장 끝" 이거나 끔. */
enum class ListenTimer(val label: String, val minutes: Int?) {
    Off("끔", null),
    Min30("30분", 30),
    Min60("60분", 60),
    ChapterEnd("장 끝", null),
}

/** 듣기의 지금 모습. 조종판 · 문장 칠 · 잠금 화면 카드가 모두 이것만 본다. */
data class ListenState(
    /** 듣기가 켜져 있다(조종판이 떠 있다). 멈춰 있어도 켜져 있다. */
    val active: Boolean = false,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    /** 지금 문장이 든 장과 그 문장. 칠하기 · 멈춘 자리. */
    val spine: Int = -1,
    val sentence: Sentence? = null,
    /** 지금 문장의 글(잠금 화면 카드에 보인다). */
    val sentenceText: String = "",
    val rate: Float = 1f,
    val timer: ListenTimer = ListenTimer.Off,
    /** 시간 타이머가 끝나는 때(epoch ms). */
    val timerEndsAtMs: Long? = null,
    /** 사람에게 알릴 말(엔진 없음 · 책 끝). 보인 뒤 [Listening.consumeMessage]. */
    val message: String? = null,
    /** 어절 쉼 줄이기의 지금 세기. */
    val join: WordJoin = WordJoin.Off,
)

/**
 * 듣기 한 번(책 하나)의 진행. 문장 하나씩 엔진에 넘기고, 끝나면 다음 문장, 장이 끝나면 다음 장으로 간다.
 *
 * 화면(Compose)과 떨어져 돈다. 화면을 끄면 그림은 멈추지만 읽기는 계속되어야 한다(L6) — 그래서 쪽을 따라가는
 * 것도 화면의 효과가 아니라 여기서 [ListenSource.follow] 를 직접 부른다. 쪽이 넘어가면 리더가 진도를 저장하므로,
 * 화면을 끈 채 듣다가 앱을 닫아도 들은 곳에서 다시 연다.
 *
 * 엔진에는 지금 문장과 **다음 한 문장**을 줄 세워 둔다. 하나씩 주면 문장이 끝난 알림이 오고 다음 문장을 넘기는
 * 사이에 틈이 생겨, 화면이 꺼진 동안 그 틈에 기기가 잠들면 읽기가 멈춘다.
 */
class Listening(
    private val reader: ListenSource,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    val title: String get() = reader.title

    /** 이 듣기가 [other] 책의 것인가. 화면이 다시 만들어져도 같은 책이면 조종판을 이어 보인다. */
    fun belongsTo(other: ListenSource): Boolean = reader === other

    private var chapter: SpeechChapter? = null
    private var index = -1
    private var queued = -1
    /** 끊고 새로 읽을 때마다 는다. 끊기 전에 줄 서 있던 문장의 알림이 늦게 와도 무시하려고. */
    private var generation = 0
    private var timerJob: Job? = null
    private var prepared = false

    private var join = WordJoin.Off

    init {
        speaker.events = object : SpeakerEvents {
            override fun onStart(id: String) { scope.launch { started(id) } }
            override fun onDone(id: String) { scope.launch { finished(id) } }
            override fun onError(id: String) { scope.launch { failed(id) } }
        }
    }

    /**
     * 듣기를 켜고 [spine] 장의 글자 [offset] 에서 읽기 시작한다(보이는 쪽의 첫 문장). 엔진이 없으면 알리고 켜지 않는다.
     */
    suspend fun start(spine: Int, offset: Int, rate: Float, voice: String?, join: WordJoin = WordJoin.Off) {
        this.join = join
        _state.value = _state.value.copy(active = true, preparing = true, rate = rate, message = null, join = join)
        if (!prepared) {
            prepared = speaker.prepare()
            if (!prepared) {
                _state.value = ListenState(message = "음성 엔진을 찾지 못했습니다. 휴대폰 설정 › 텍스트 음성 변환에서 엔진을 설치해 주세요")
                return
            }
        }
        speaker.setRate(rate)
        speaker.setVoice(voice)
        val c = load(spine)
        val n = c.sentences.indexAt(offset).let { if (it < 0) c.sentences.size else it }
        _state.value = _state.value.copy(preparing = false)
        speakFrom(spine, n)
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        val c = chapter ?: return
        if (_state.value.playing || !_state.value.active) return
        scope.launch { speakFrom(c.spine, index.coerceAtLeast(0)) }
    }

    fun pause() {
        generation++
        speaker.stop()
        queued = -1
        _state.value = _state.value.copy(playing = false)
    }

    /** 다음 · 앞 문장(조종판 · 잠금 화면 · 이어폰 단추). 멈춰 있어도 옮기고, 읽던 중이면 거기서 읽는다. */
    fun next() = step(+1)

    fun previous() = step(-1)

    private fun step(by: Int) {
        val c = chapter ?: return
        val playing = _state.value.playing
        scope.launch {
            val target = index + by
            if (playing) {
                speakFrom(c.spine, target)
            } else {
                moveTo(c.spine, target)
            }
        }
    }

    fun setRate(rate: Float) {
        speaker.setRate(rate)
        _state.value = _state.value.copy(rate = rate)
        // 빠르기는 다음 문장부터 먹는다. 지금 문장부터 바로 바뀌어야 고른 값을 들어 볼 수 있다.
        if (_state.value.playing) chapter?.let { c -> scope.launch { speakFrom(c.spine, index) } }
    }

    /** 어절 쉼 줄이기의 세기. 빠르기처럼 지금 문장부터 바뀌어 들린다. */
    fun setJoin(level: WordJoin) {
        join = level
        _state.value = _state.value.copy(join = level)
        if (_state.value.playing) chapter?.let { c -> scope.launch { speakFrom(c.spine, index) } }
    }

    fun setVoice(voice: String?) {
        speaker.setVoice(voice)
        if (_state.value.playing) chapter?.let { c -> scope.launch { speakFrom(c.spine, index) } }
    }

    fun setTimer(timer: ListenTimer) {
        timerJob?.cancel()
        val minutes = timer.minutes
        _state.value = _state.value.copy(timer = timer, timerEndsAtMs = minutes?.let { clock() + it * 60_000L })
        if (minutes != null) {
            timerJob = scope.launch {
                delay(minutes * 60_000L)
                pause()
                _state.value = _state.value.copy(timer = ListenTimer.Off, timerEndsAtMs = null)
            }
        }
    }

    /**
     * 사람이 쪽을 옮겼다(넘기기 · 목차 · 진행 막대). 지금 문장이 그 쪽에 없으면 그 쪽의 첫 문장으로 옮긴다 —
     * 넘긴 쪽을 보면서 앞 쪽의 문장을 듣는 일이 없게. 듣기가 쪽을 따라 넘긴 것이면 문장이 그 쪽에 있어 그대로다.
     */
    fun onPageShown(spine: Int, start: Int, endExclusive: Int) {
        val st = _state.value
        if (!st.active || st.preparing) return
        val s = st.sentence
        if (st.spine == spine && s != null && s.start in start until endExclusive) return
        scope.launch {
            val c = load(spine)
            val n = c.sentences.indexAt(start).let { if (it < 0) c.sentences.size else it }
            if (_state.value.playing) speakFrom(spine, n) else moveTo(spine, n)
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** 듣기를 끈다(조종판 ✕ · 책 닫기 · 잠금 화면 ✕). */
    fun close() {
        generation++
        timerJob?.cancel()
        speaker.stop()
        speaker.shutdown()
        prepared = false
        _state.value = ListenState()
    }

    // ── 내부 ────────────────────────────────────────────────────────

    private suspend fun load(spine: Int): SpeechChapter =
        chapter?.takeIf { it.spine == spine } ?: reader.speech(spine).also { chapter = it }

    /** 멈춘 채로 문장만 옮긴다(칠과 쪽만 따라간다). 장 경계를 넘으면 그 장으로. */
    private suspend fun moveTo(spine: Int, n: Int) {
        val (c, i) = resolve(spine, n) ?: return
        index = i
        show(c, i)
    }

    /**
     * [spine] 장의 [n] 번째 문장부터 읽는다. 장 끝을 넘으면 다음 장(빈 장은 건너뛴다), 앞을 넘으면 앞 장의 끝.
     * 책 끝이면 멈추고 알린다.
     */
    private suspend fun speakFrom(spine: Int, n: Int) {
        val from = chapter?.spine
        val resolved = resolve(spine, n)
        if (resolved == null) {
            pause()
            _state.value = _state.value.copy(message = "책을 끝까지 읽었습니다")
            return
        }
        val (c, i) = resolved
        // "장 끝" 타이머: 다음 장으로 넘어가려는 순간 멈춘다.
        if (_state.value.timer == ListenTimer.ChapterEnd && from != null && c.spine > from && _state.value.playing) {
            pause()
            _state.value = _state.value.copy(timer = ListenTimer.Off)
            index = i
            show(c, i)
            return
        }
        generation++
        index = i
        speaker.speak(id(c.spine, i), spoken(c, i), flush = true)
        queued = i
        queueNext(c)
        _state.value = _state.value.copy(playing = true)
        show(c, i)
    }

    private fun queueNext(c: SpeechChapter) {
        val next = queued + 1
        if (next < c.sentences.size) {
            speaker.speak(id(c.spine, next), spoken(c, next), flush = false)
            queued = next
        }
    }

    private suspend fun resolve(spine: Int, n: Int): Pair<SpeechChapter, Int>? {
        var s = spine
        var i = n
        var c = load(s)
        var guard = 0
        while (i >= c.sentences.size || i < 0) {
            if (++guard > 10_000) return null
            if (i < 0) {
                if (s == 0) { i = 0; if (c.sentences.isEmpty()) return null; break }
                s--
                c = load(s)
                i = c.sentences.size - 1
            } else {
                s++
                if (s >= reader.unitCount()) return null
                c = load(s)
                i = 0
            }
        }
        return c to i
    }

    private suspend fun show(c: SpeechChapter, i: Int) {
        val sentence = c.sentences[i]
        _state.value = _state.value.copy(spine = c.spine, sentence = sentence, sentenceText = speakable(c.text, sentence))
        runCatching { reader.follow(c.spine, sentence.start) }
    }

    /** 엔진에 넘길 글: 숨은 문자를 정리하고([speakable]) 어절 쉼 줄이기를 적용한다. 화면의 글 · 칠은 그대로다. */
    private fun spoken(c: SpeechChapter, i: Int): String = joinWords(speakable(c.text, c.sentences[i]), join)

    private fun id(spine: Int, i: Int) = "$generation:$spine:$i"

    private fun parse(id: String): Triple<Int, Int, Int>? {
        val p = id.split(':')
        if (p.size != 3) return null
        return Triple(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
    }

    private suspend fun started(id: String) {
        val (gen, spine, i) = parse(id) ?: return
        if (gen != generation || !_state.value.playing) return
        val c = load(spine)
        if (i !in c.sentences.indices) return
        // 이미 보인 문장(speakFrom 이 막 보인 것)의 시작 알림이면 쪽을 건드리지 않는다. 엔진의 알림은 늦게 올 수 있어
        // (바인더 · 부하), 그 사이 사람이 넘긴 쪽을 이 문장 자리로 되돌리면 "넘겼는데 앞 쪽으로 튀어 돌아온다".
        val st = _state.value
        if (i == index && st.spine == spine && st.sentence == c.sentences[i]) return
        index = i
        show(c, i)
    }

    private suspend fun finished(id: String) {
        val (gen, spine, i) = parse(id) ?: return
        if (gen != generation || !_state.value.playing) return
        val c = load(spine)
        if (i >= c.sentences.size - 1) {
            // 장의 마지막 문장까지 읽었다 — 다음 장으로.
            speakFrom(spine + 1, 0)
        } else if (queued <= i + 1) {
            queueNext(c)
        }
    }

    private suspend fun failed(id: String) {
        val (gen, spine, i) = parse(id) ?: return
        if (gen != generation || !_state.value.playing) return
        // 읽지 못한 문장(엔진이 거절한 기호 등)은 건너뛴다. 한 문장 때문에 듣기 전체가 멈추면 안 된다.
        speakFrom(spine, i + 1)
    }
}

/** 미리 듣기 알림의 머리. 읽기의 알림("세대:장:문장")과 섞이지 않게 따로 둔다. */
