package io.github.kgcaudit.reader.text

import android.graphics.Typeface
import io.github.kgcaudit.reader.layout.conformance.MeasurerConformance
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * 검사 장치 자체를 검사한다.
 *
 * Robolectric 의 기본(레거시) 그래픽스는 `measureText` 가 **글자 수**를 돌려주는
 * 가짜다. 누가 [AndroidTextMeasurerTest] 의 `@GraphicsMode(NATIVE)` 를 지우면 한글도
 * 라틴도 같은 폭으로 재지는데, 그때 conformance 가 조용히 통과해 버리면 이 모듈의
 * 테스트가 전부 빈 껍데기가 된다. 가짜 `Paint` 를 **실제로 걸러 내는지** 여기서 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConformanceHarnessTest {

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `the conformance suite rejects a paint that does not really measure glyphs`() {
        val fake = AndroidTextMeasurer(Typeface.DEFAULT, Typeface.DEFAULT_BOLD, baseSizePx = 42f)
        val problems = MeasurerConformance.check(fake)
        assertTrue(problems.isNotEmpty(), "글자 수를 폭으로 돌려주는 Paint 가 conformance 를 통과했다")
    }
}
