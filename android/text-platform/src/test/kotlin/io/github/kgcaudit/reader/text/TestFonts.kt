package io.github.kgcaudit.reader.text

import android.graphics.Typeface
import java.io.File

/**
 * 테스트 전용 글꼴("Olo Test Sans", Pretendard 부분집합 · OFL). 앱은 폰트를 싣지 않으므로
 * 사용자가 폰트를 넣은 경우는 이 파일로 흉내 낸다. Robolectric 에는 한국어 명조가 없어서,
 * 시스템 고딕과 **한글 모양이 다른** 글꼴이 있어야 "글꼴을 바꾸면 조판이 바뀐다" 를 검증할 수 있다.
 */
object TestFonts {
    const val KEY = "test"

    val regular: Typeface by lazy { load("olo-test-regular.ttf") }
    val bold: Typeface by lazy { load("olo-test-bold.ttf") }

    /** 한글 글리프가 없는 폰트. 사용자 폰트 경고 검사가 실제로 걸리는지 볼 때 쓴다. */
    val latinOnly: Typeface by lazy { load("olo-test-latin.ttf") }

    /** 시스템 글꼴에 테스트 글꼴 하나를 사용자 글꼴로 더한 목록. */
    fun catalog(): FontCatalog = object : FontCatalog() {
        override fun options() = super.options() + FontOption(KEY, "테스트", FontOption.Kind.User)
        override fun pair(key: String) = if (key == KEY) FontPair(regular, bold) else super.pair(key)
    }

    /**
     * Typeface 는 파일 경로로만 읽으므로 리소스를 임시 파일로 꺼낸다. 리소스 폴더를 `fonts/` 로
     * 두면 Robolectric 이 자기 시스템 폰트(클래스패스의 `fonts/`) 대신 이걸 읽어 폰트 맵 로딩이
     * NPE 로 죽는다 — 그래서 이름이 `olo-test-fonts/` 다.
     */
    fun file(name: String): File {
        val out = File.createTempFile(name.substringBefore('.'), ".ttf").apply { deleteOnExit() }
        val stream = checkNotNull(TestFonts::class.java.getResourceAsStream("/olo-test-fonts/$name")) { "no resource $name" }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun load(name: String): Typeface = Typeface.Builder(file(name)).build()
}
