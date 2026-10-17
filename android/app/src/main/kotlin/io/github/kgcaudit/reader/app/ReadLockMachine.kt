package io.github.kgcaudit.reader.app

/**
 * 읽기 잠금의 판단만(플랫폼 호출 없음). 무엇을 할지는 [Effect] 로 돌려주고 [ReadLockController] 가 한다 — 벨 · 확인 창 ·
 * 시스템 동작이 뒤섞이는 차례를 기기 없이 시험할 수 있게.
 *
 * 단계:
 * - [Phase.Off] 풀림.
 * - [Phase.Asking] startLockTask 를 불렀고 안드로이드 확인 창의 답을 기다린다. 아직 "잠금 중" 이 아니다 — 거절하면 아무것도
 *   잠기지 않았는데 잠금 중이라고 말하게 된다.
 * - [Phase.On] 고정이 확인됐다. 사람이 최근 앱 화면의 "이 앱 고정" 으로 건 고정도 보이면 여기로 온다(0.54.4).
 * - [Phase.CallFreed] 전화 때문에 풀었다. 통화가 끝나면 "다시 잠그기" 띠가 뜬다.
 */
internal class ReadLockMachine {
    enum class Phase { Off, Asking, On, CallFreed }

    enum class Effect {
        /** startLockTask — 안드로이드 확인 창이 뜬다. */
        Pin,
        /** stopLockTask. */
        Unpin,
        /** 고른 경우 방해 금지를 건다. */
        QuietOn,
        /** 우리가 건 방해 금지를 되돌린다. */
        QuietOff,
        /** 돌던 자동 넘김 · 자동 스크롤을 멈추고 기억한다. */
        PauseAuto,
        /** 기억한 것을 잇는다. */
        ResumeAuto,
        /** 기억한 것을 잊는다(멈춘 채로 둔다). */
        ForgetAuto,
        /** "잠금 중입니다 · …" 안내. */
        ShowLocked,
        /** 통화 중이라 잠그지 않는다는 안내. */
        RefuseInCall,
    }

    var phase = Phase.Off
        private set

    /** 벨이 울리거나 통화 중이다. */
    var inCall = false
        private set

    /** 이번 [Phase.Asking] 이 통화 뒤 "다시 잠그기" 에서 왔다 — 확인되면 멈춘 자동 진행을 잇는다. */
    private var relocking = false

    /** [Phase.Asking] 에 들어온 뒤 창 초점을 잃었다(확인 창이 떴다). 다시 얻으면 창이 닫힌 것이다. */
    private var sawDialog = false

    /**
     * 우리가 고정을 풀었는데([Effect.Unpin]) 아직 풀린 것을 보지 못했다. 그사이 보이는 "고정됨" 은 늦게 따라오는 옛 상태라 사람이
     * 건 고정으로 받지 않는다 — 받으면 자물쇠를 2초 눌러 풀자마자 다시 "잠금 중" 이 되고 방해 금지가 다시 걸린다.
     */
    private var unpinning = false

    private fun unpin(vararg rest: Effect): List<Effect> {
        unpinning = true
        return listOf(Effect.Unpin, *rest)
    }

    /** 자물쇠의 "잠그기"(또는 띠의 "다시 잠그기"). */
    fun request(): List<Effect> {
        // 통화 중에 고정하면 통화 화면으로 돌아갈 길이 막힌다.
        if (inCall) return listOf(Effect.RefuseInCall)
        return when (phase) {
            Phase.Off, Phase.CallFreed -> {
                relocking = phase == Phase.CallFreed
                phase = Phase.Asking
                sawDialog = false
                // 이제부터 보이는 고정은 이번 청에 대한 답이다.
                unpinning = false
                listOf(Effect.Pin)
            }
            // 묻는 중에 또 "잠그기" 를 눌렀다 — 앞의 확인 창은 이미 닫혔다(설명 판을 다시 거쳐야 이 줄에 온다). 다시 묻는다.
            // 0.54.1 까지는 무시해서, 확인 창이 초점을 가져가지 않는 기기에서 "취소" 를 누르면 [Phase.Asking] 에 갇혀 자물쇠를
            // 다시 눌러도 아무 일이 없었다(초점이 오가지 않으니 거절을 알아챌 길이 없다).
            Phase.Asking -> {
                sawDialog = false
                listOf(Effect.Pin)
            }
            // 잠긴 중에 또 누르면 할 일이 없다.
            Phase.On -> emptyList()
        }
    }

    /**
     * 고정을 걸지 못했다(화면이 앞에 서지 않아 안드로이드가 거절 — 확인 창도 뜨지 않는다). 묻는 중에서 빠져나간다: 두면 "묻는 중" 에
     * 머물러 다시 눌러도 아무 일이 없다.
     */
    fun pinFailed(): List<Effect> {
        if (phase != Phase.Asking) return emptyList()
        phase = if (relocking) Phase.CallFreed else Phase.Off
        return emptyList()
    }

    /** 창 초점이 바뀌었다. [pinned] 는 그때 안드로이드가 말하는 고정 상태. */
    fun focus(has: Boolean, pinned: Boolean): List<Effect> {
        if (!has) {
            if (phase == Phase.Asking) sawDialog = true
            return emptyList()
        }
        return observed(pinned, settled = phase == Phase.Asking && sawDialog)
    }

    /**
     * 고정 상태를 봤다(앞으로 돌아옴 · 주기 확인 · 초점). [settled] 면 확인 창이 이미 닫혔다 — 그런데도 고정이 아니면 거절한 것이다.
     * 묻는 중에 고정이 아니라는 것만으로는 거절로 보지 않는다: 확인 창이 아직 떠 있을 수 있다.
     */
    fun observed(pinned: Boolean, settled: Boolean = false): List<Effect> {
        // 통화 중에 걸린 고정(벨이 울리는 사이 확인 창에서 "확인" 을 누름)은 곧바로 푼다 — 두면 전화 화면이 막힌다.
        if (pinned && inCall) return unpin()
        if (!pinned) unpinning = false
        return when (phase) {
            Phase.Asking -> when {
                pinned -> {
                    phase = Phase.On
                    buildList {
                        add(Effect.QuietOn)
                        if (relocking) add(Effect.ResumeAuto)
                        add(Effect.ShowLocked)
                    }
                }
                settled -> {
                    // 거절했다. 다시 잠그기를 거절했으면 띠도 거둔다 — 사람이 "잠그지 않겠다" 고 답했다. 멈춘 자동 진행은
                    // 멈춘 채로 둔다(알약의 ▶ 로 잇는다): 저절로 다시 흐르면 통화 뒤 읽던 곳을 지나친다.
                    phase = Phase.Off
                    if (relocking) listOf(Effect.ForgetAuto) else emptyList()
                }
                else -> emptyList()
            }
            // 시스템 동작(뒤로 + 최근 앱 길게)으로 풀렸다. 방해 금지도 함께 되돌린다.
            Phase.On -> if (!pinned) { phase = Phase.Off; listOf(Effect.QuietOff) } else emptyList()
            // 사람이 최근 앱 화면에서 "이 앱 고정" 으로 걸었다(0.54.4 사용자 결정 — 우리 자물쇠를 거치지 않는 길). 우리 잠금으로
            // 받는다: 안 받으면 고정된 채 "잠금 중" 도 아니고, 벨이 울려도 풀지 않아 전화 화면이 막히고, 자물쇠로 풀 수도 없다.
            // 안내는 띄우지 않는다 — 안드로이드가 제 안내를 띄운다.
            Phase.Off, Phase.CallFreed -> if (pinned && !unpinning) {
                val after = phase == Phase.CallFreed
                phase = Phase.On
                // 통화 뒤 띠에서 왔으면 "다시 잠그기" 와 같다 — 멈춘 자동 진행을 잇는다.
                if (after) listOf(Effect.QuietOn, Effect.ResumeAuto) else listOf(Effect.QuietOn)
            } else emptyList()
        }
    }

    /** 벨이 울리기 시작했거나 통화가 시작됐다(벨 없이 바로 통화 — 블루투스 · 음성 비서로 건 전화). */
    fun callStarted(): List<Effect> {
        inCall = true
        return when (phase) {
            // 고정을 먼저 푼다 — 그래야 전화 알림 · 통화 화면이 뜬다.
            Phase.On -> { phase = Phase.CallFreed; unpin(Effect.QuietOff, Effect.PauseAuto) }
            // 확인 창이 떠 있는 사이 벨: 나중에 "확인" 을 눌러도 풀리게 지금 풀어 둔다. 다시 잠그던 중이었으면 띠로 돌아간다
            // (멈춘 자동 진행을 아직 기억한다).
            Phase.Asking -> { phase = if (relocking) Phase.CallFreed else Phase.Off; unpin() }
            // 이미 풀었다(두 번째 벨 · 통화 중 또 울림) · 잠그지 않았다: 할 일이 없다.
            Phase.Off, Phase.CallFreed -> emptyList()
        }
    }

    /** 통화가 끝났다(벨이 멎음 · 끊음). */
    fun callEnded(): List<Effect> {
        inCall = false
        return emptyList()
    }

    /** 자물쇠를 2초 눌렀다. */
    fun release(): List<Effect> = when (phase) {
        Phase.On -> { phase = Phase.Off; unpin(Effect.QuietOff) }
        else -> emptyList()
    }

    /** 리더를 닫았다. 통화 뒤 띠는 읽던 책에 붙은 것이라 함께 거둔다. */
    fun leftReader(): List<Effect> = when (phase) {
        Phase.CallFreed -> { phase = Phase.Off; listOf(Effect.ForgetAuto) }
        else -> emptyList()
    }

    /** 화면이 끝난다(앱을 닫음). 무엇이든 되돌린다 — 고정 · 방해 금지가 앱보다 오래 남지 않게. */
    fun ended(): List<Effect> {
        val was = phase
        phase = Phase.Off
        return when (was) {
            Phase.On -> unpin(Effect.QuietOff, Effect.ForgetAuto)
            Phase.Asking -> unpin(Effect.ForgetAuto)
            Phase.CallFreed -> listOf(Effect.ForgetAuto)
            Phase.Off -> emptyList()
        }
    }

    /** 새로 뜬 앱이 지난번 잠금이 남긴 고정을 봤다. 푼다 — 늦게 따라오는 "고정됨" 을 사람이 건 고정으로 받지 않게 기계를 거친다. */
    fun leftover(): List<Effect> = unpin()

    /** 통화 뒤 띠를 보일 때: 전화 때문에 풀었고, 통화가 끝났다. 통화 중에 띠의 "다시 잠그기" 를 누르면 통화 화면이 막힌다. */
    val showsBanner: Boolean get() = phase == Phase.CallFreed && !inCall
}
