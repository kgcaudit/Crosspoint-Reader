package io.github.kgcaudit.reader.text

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import java.io.InputStream

/**
 * 앱에서 받을 수 있는 추천 글꼴. 모두 Google Fonts 의 OFL 글꼴이다.
 *
 * @property family Google Fonts 의 이름. 받을 때 쓴다.
 * @property fileFamily 받은 파일 안의 가족 이름(소문자). 나눔 글꼴은 Google 이름("Nanum Myeongjo")과
 *   파일 이름("NanumMyeongjo")이 달라서, 받은 뒤 "이미 받았다" 를 알려면 파일 쪽 이름이 필요하다.
 *   google/fonts 저장소의 파일에서 확인한 값이다.
 */
data class RecommendedFont(
    val family: String,
    val fileFamily: String,
    val label: String,
    val serif: Boolean,
    val weights: List<Int>,
) {
    /** 받은 뒤 사용자 글꼴 목록에서의 키. */
    val key: String get() = UserFonts.KEY_PREFIX + fileFamily
}

object RecommendedFonts {
    /**
     * 본문용으로 고른 목록. 명조를 앞에 둔다 — 삼성 등 한국어 명조가 빠진 기기에서 이 기능이 필요한
     * 이유가 명조다. 굵게가 있는 글꼴은 굵게도 함께 받는다(합성 굵게보다 곱다).
     */
    val all: List<RecommendedFont> = listOf(
        RecommendedFont("Nanum Myeongjo", "nanummyeongjo", "나눔명조", serif = true, weights = listOf(400, 700)),
        RecommendedFont("Gowun Batang", "gowun batang", "고운바탕", serif = true, weights = listOf(400, 700)),
        RecommendedFont("Noto Serif KR", "noto serif kr", "본명조 (Noto Serif KR)", serif = true, weights = listOf(400, 700)),
        RecommendedFont("Nanum Gothic", "nanumgothic", "나눔고딕", serif = false, weights = listOf(400, 700)),
        RecommendedFont("Gowun Dodum", "gowun dodum", "고운돋움", serif = false, weights = listOf(400)),
        RecommendedFont("IBM Plex Sans KR", "ibm plex sans kr", "IBM Plex Sans KR", serif = false, weights = listOf(400, 700)),
    )

    fun byKey(key: String): RecommendedFont? = all.firstOrNull { it.key == key }
}

/** 글꼴 파일 하나를 받아 오는 곳. 테스트는 가짜를 넣는다. */
fun interface FontSource {
    /** 막히는 호출이다(내려받기). 입출력 스레드에서 부른다. */
    fun fetch(family: String, weight: Int): Fetched
}

sealed interface Fetched {
    /** 받았다. [open] 으로 파일을 읽는다(부른 쪽이 닫는다). */
    class File(val open: () -> InputStream?) : Fetched

    data class Failed(val reason: DownloadFailure) : Fetched
}

enum class DownloadFailure {
    /** Google Play 서비스가 없거나 글꼴 제공자를 믿을 수 없다(인증서가 다르다). */
    NoProvider,

    /** 받지 못했다. 대개 인터넷이 없다. */
    Network,

    /** 그런 글꼴이 없다. */
    NotFound,

    /** 받은 파일을 글꼴로 읽을 수 없다. */
    Broken,
}

/**
 * Google Play 서비스의 글꼴 제공자(Downloadable Fonts)에서 받는다.
 *
 * 직접 내려받지 않는 이유: 앱에 인터넷 권한이 없다(책은 기기 안의 파일만 읽는다). 제공자가 받아 기기에
 * 캐시하고 우리는 그 파일을 읽기만 한다. 다른 앱이 같은 글꼴을 이미 받았으면 바로 온다.
 * 받은 파일은 [UserFonts] 로 복사한다 — 제공자의 캐시는 언제든 비워질 수 있고, 그러면 오프라인에서
 * 글꼴이 사라진다.
 */
class GmsFontSource(private val context: Context) : FontSource {
    override fun fetch(family: String, weight: Int): Fetched {
        val request = FontRequest(PROVIDER_AUTHORITY, PROVIDER_PACKAGE, "name=$family&weight=$weight&besteffort=true", R.array.com_google_android_gms_fonts_certs)
        val result = try {
            FontsContractCompat.fetchFonts(context, null, request)
        } catch (e: PackageManager.NameNotFoundException) {
            return Fetched.Failed(DownloadFailure.NoProvider)
        } catch (e: RuntimeException) {
            return Fetched.Failed(DownloadFailure.Network)
        }
        if (result.statusCode != FontsContractCompat.FontFamilyResult.STATUS_OK) return Fetched.Failed(DownloadFailure.NoProvider)
        val font = result.fonts.firstOrNull() ?: return Fetched.Failed(DownloadFailure.NotFound)
        return when (font.resultCode) {
            FontsContractCompat.Columns.RESULT_CODE_OK -> Fetched.File { context.contentResolver.openInputStream(font.uri) }
            FontsContractCompat.Columns.RESULT_CODE_FONT_NOT_FOUND, FontsContractCompat.Columns.RESULT_CODE_MALFORMED_QUERY ->
                Fetched.Failed(DownloadFailure.NotFound)
            else -> Fetched.Failed(DownloadFailure.Network)
        }
    }

    companion object {
        const val PROVIDER_AUTHORITY = "com.google.android.gms.fonts"
        const val PROVIDER_PACKAGE = "com.google.android.gms"
    }
}

/** 추천 글꼴을 받아 사용자 글꼴로 넣는다. */
class FontDownloader(private val user: UserFonts, @get:androidx.annotation.VisibleForTesting var source: FontSource) {

    sealed interface Result {
        /** 넣었다. [key] 로 고르면 된다. */
        data class Done(val key: String) : Result

        data class Failed(val reason: DownloadFailure) : Result
    }

    fun isInstalled(font: RecommendedFont): Boolean = user.family(font.key) != null

    /**
     * [font] 의 굵기들을 받아 넣는다. **막히는 호출** — 입출력 스레드에서.
     *
     * 보통 굵기를 못 받으면 실패다. 굵게만 못 받으면 보통만으로 넣는다(굵게는 합성) — 글꼴을 아예
     * 못 쓰는 것보다 낫다.
     */
    fun download(font: RecommendedFont): Result {
        var key: String? = null
        for ((i, weight) in font.weights.withIndex()) {
            val file = when (val fetched = source.fetch(font.family, weight)) {
                is Fetched.Failed -> if (i == 0) return Result.Failed(fetched.reason) else continue
                is Fetched.File -> fetched
            }
            val imported = runCatching { file.open()?.use { user.import(it) } }.getOrNull()
            when (imported) {
                is UserFonts.ImportResult.Added -> key = key ?: imported.families.firstOrNull()?.key
                else -> if (i == 0) return Result.Failed(if (imported == null) DownloadFailure.Network else DownloadFailure.Broken)
            }
        }
        return key?.let(Result::Done) ?: Result.Failed(DownloadFailure.Broken)
    }
}
