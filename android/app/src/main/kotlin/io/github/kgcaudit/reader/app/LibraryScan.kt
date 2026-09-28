package io.github.kgcaudit.reader.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 등록 폴더 훑기. 앱 전체에 하나 — 라이브러리 화면이 아니라 앱의 [scope] 에서 돈다.
 *
 * 화면 안에서 돌리던 때(0.24.0 까지) 두 가지가 샜다.
 * - 앱을 켜자마자 책을 열면 라이브러리 화면이 사라지며 훑기가 중간에 취소됐다. 돌아와도 다시 훑지 않아, 끊긴 뒤의 폴더에
 *   넣은 새 책이 보이지 않았다.
 * - 훑는 중에 폴더를 더하면 "이미 훑는 중" 이라 그냥 돌아왔다. 도는 훑기는 시작할 때 받은 폴더 목록만 보므로 새 폴더는
 *   새로고침을 눌러야 나타났다.
 *
 * 훑는 중에 온 요청은 버리지 않고 한 번으로 모아, 지금 훑기가 끝나면 한 번 더 훑는다.
 */
class LibraryScan(
    private val scope: CoroutineScope,
    /** 모든 폴더를 훑는다. 모두 끝까지 읽었으면 true, 일부를 못 읽었으면 false. 예외는 못 읽은 것으로 본다. */
    private val scanAll: suspend () -> Boolean,
) {
    private val _running = MutableStateFlow(false)
    private val _incomplete = MutableStateFlow(0)
    private var again = false

    /** 훑는 중인가(라이브러리 위의 진행 막대). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 일부 폴더를 읽지 못한 훑기의 수. 늘어날 때마다 화면이 한 번 알린다. */
    val incomplete: StateFlow<Int> = _incomplete.asStateFlow()

    /** 훑기를 청한다. 훑는 중이면 끝난 뒤 한 번 더 — 여러 번 청해도 한 번이다. [scope] 의 스레드(메인)에서 부른다. */
    fun request() {
        if (_running.value) {
            again = true
            return
        }
        _running.value = true
        scope.launch {
            try {
                do {
                    again = false
                    val complete = try {
                        scanAll()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("OloLibrary", "rescan failed", e)
                        false
                    }
                    if (!complete) _incomplete.value++
                } while (again)
            } finally {
                _running.value = false
            }
        }
    }
}
