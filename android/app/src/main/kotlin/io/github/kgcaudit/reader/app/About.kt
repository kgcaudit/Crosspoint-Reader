package io.github.kgcaudit.reader.app

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.kgcaudit.reader.ui.design.CpDivider
import io.github.kgcaudit.reader.ui.design.CpHeader
import io.github.kgcaudit.reader.ui.design.CpListRow
import io.github.kgcaudit.reader.ui.design.CpSectionLabel
import io.github.kgcaudit.reader.ui.design.CpText
import io.github.kgcaudit.reader.ui.design.CpTheme
import io.github.kgcaudit.reader.ui.design.CpToast

/**
 * 앱에 들어간 남의 것 하나. 본문은 원문 그대로 싣는다 — BSD · Apache 모두 "배포할 때 고지문을 함께 싣는다" 가
 * 허가 조건이다. 앱 안에 없으면 0.20.4 부터 넣은 Adobe-KR 표를 허가 없이 배포하는 셈이다.
 *
 * 본문은 res/raw 에 둔다. 0.20.3 에서 클래스 자리를 기준으로 찾던 자원이 출시용 빌드(R8 이 클래스 이름을 줄임)에서
 * 폰에서만 비었다 — 안드로이드 자원은 이름이 R 번호로 묶여 그 일이 없다.
 */
internal class OpenLicense(
    val title: String,
    /** 목록에서 제목 아래 — 무엇이고 어디에 쓰는지. */
    val subtitle: String,
    /** 목록 오른쪽의 짧은 이름. */
    val badge: String,
    /** 본문 화면 머리줄 부제. */
    val licenseName: String,
    /** 본문 위 한 줄 — 출처. */
    val source: String,
    @RawRes val text: Int,
)

internal val OPEN_LICENSES = listOf(
    OpenLicense(
        title = "Adobe-KR 글자 표",
        subtitle = "깨진 PDF 글꼴의 한글을 되살릴 때 씁니다",
        badge = "BSD",
        licenseName = "BSD 3조항 라이선스",
        source = "Adobe cmap-resources의 Adobe-KR-9 표 (github.com/adobe-type-tools/cmap-resources)",
        text = R.raw.license_adobe_kr,
    ),
    OpenLicense(
        title = "Android Jetpack",
        subtitle = "Compose · Room · Core",
        badge = "Apache 2.0",
        licenseName = "Apache 라이선스 2.0",
        source = "Google · Android 오픈소스 프로젝트 (developer.android.com/jetpack)",
        text = R.raw.license_apache_2_0,
    ),
    OpenLicense(
        title = "Kotlin · kotlinx.coroutines",
        subtitle = "JetBrains",
        badge = "Apache 2.0",
        licenseName = "Apache 라이선스 2.0",
        source = "JetBrains (kotlinlang.org · github.com/Kotlin/kotlinx.coroutines)",
        text = R.raw.license_apache_2_0,
    ),
)

/** "2026-09-27" → "2026년 9월 27일". 모양이 다르면 그대로 둔다. */
internal fun koreanDate(iso: String): String {
    val parts = iso.split('-').mapNotNull { it.toIntOrNull() }
    return if (parts.size == 3) "${parts[0]}년 ${parts[1]}월 ${parts[2]}일" else iso
}

/**
 * 앱 정보. 판 번호를 여기서 본다 — 폰에 어느 판이 깔렸는지 몰라 "옛 판이 깔린 것" 으로 잘못 짚은 일이 있었다
 * (0.20.4, 좋은생각 109쪽).
 */
@Composable
internal fun AboutScreen(records: RecordsUi, onBack: () -> Unit, onLicense: (Int) -> Unit) {
    BackHandler(onBack = onBack)
    val c = CpTheme.colors
    Box(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    // 라이선스가 늘거나 글자를 키우면 화면을 넘는다 — 기록 행이 그 아래에 묻히지 않게 굴린다.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CpHeader("앱 정보", onBack = onBack)
        Column(Modifier.padding(horizontal = CpTheme.metrics.gutter, vertical = 12.dp)) {
            CpText("OLO eBook", CpTheme.type.title, c.text)
            Spacer(Modifier.height(4.dp))
            CpText("판 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${koreanDate(BuildConfig.BUILD_DATE)}", CpTheme.type.subtitle, c.textMuted)
            Spacer(Modifier.height(12.dp))
            CpText("EPUB · TXT · PDF를 간편하게 읽고 책갈피를 꽂는 뷰어입니다.", CpTheme.type.body, c.text, maxLines = 3)
            Spacer(Modifier.height(6.dp))
            CpText(
                "인터넷을 쓰지 않습니다. 읽은 자리 · 책갈피 · 형광펜은 이 휴대폰에만 남습니다. 휴대폰을 바꿀 때는 백업 파일로 옮기세요.",
                CpTheme.type.subtitle, c.textMuted, maxLines = 3,
            )
        }
        Spacer(Modifier.height(8.dp))
        CpDivider()
        ReadingRecordsRows(records)
        CpDivider()
        CpSectionLabel("오픈소스 라이선스")
        OPEN_LICENSES.forEachIndexed { i, license ->
            CpListRow(license.title, { onLicense(i) }, subtitle = license.subtitle, value = license.badge)
        }
    }
    ReadingRecordsPopups(records)
    CpToast(records.toast, { records.toast = null }, Modifier.align(Alignment.BottomCenter), durationMs = 2_500)
    }
}

/** 라이선스 본문. 문단마다 한 항목 — Apache 본문(1만 자)을 한 덩이로 그리면 처음 열 때 멈칫한다. */
@Composable
internal fun LicenseScreen(license: OpenLicense, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val c = CpTheme.colors
    val context = LocalContext.current
    val paragraphs = remember(license.text) {
        context.resources.openRawResource(license.text).bufferedReader().use { it.readText() }
            .split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        CpHeader(license.title, subtitle = license.licenseName, onBack = onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = CpTheme.metrics.gutter)) {
            item {
                CpText(license.source, CpTheme.type.subtitle, c.textMuted, Modifier.padding(top = 8.dp, bottom = 14.dp), maxLines = 3)
            }
            items(paragraphs) { p ->
                CpText(p, CpTheme.type.caption, c.text, Modifier.padding(bottom = 16.dp), maxLines = Int.MAX_VALUE)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
