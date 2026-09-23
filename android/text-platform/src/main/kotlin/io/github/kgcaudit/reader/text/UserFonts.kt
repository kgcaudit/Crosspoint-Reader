package io.github.kgcaudit.reader.text

import android.graphics.Typeface
import io.github.kgcaudit.reader.text.font.FontFace
import io.github.kgcaudit.reader.text.font.FontFormatException
import io.github.kgcaudit.reader.text.font.SfntReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 사용자가 넣은 폰트. 파일은 앱 영역([dir])에 복사해 둔다.
 *
 * 원본 URI 를 붙잡지 않는 이유: 다운로드 폴더의 폰트를 지우거나 SD 카드를 빼면 그 글꼴로
 * 보던 책이 기본 글꼴로 바뀐다. 폰트는 크지 않고(대개 1~20MB) 한 번 넣으면 오래 쓴다.
 *
 * 목록(색인 파일)은 따로 두지 않고 폴더를 읽어 만든다. 색인과 파일이 어긋날 일이 없다 —
 * 복사가 중간에 끊겨도 임시 이름이라 목록에 오르지 않는다.
 */
class UserFonts(private val dir: File) {

    /** 한 가족. 보통·굵게 파일을 짝지었다. 굵게가 없으면 측정기가 굵기를 합성한다. */
    data class Family(
        val key: String,
        val label: String,
        val regular: Face,
        val bold: Face?,
        val hasHangul: Boolean,
        val files: Set<File>,
    )

    data class Face(val file: File, val info: FontFace) {
        /** 짝짓기에 쓰는 굵기. 가변 폰트는 보통(400)을 낼 수 있으면 400 으로 본다. */
        val nominalWeight: Int get() = info.variableWeights?.takeIf { 400 in it }?.let { 400 } ?: info.weight
    }

    sealed interface ImportResult {
        /** 넣었다(또는 이미 있었다). [families] 는 이 파일이 속한 가족. */
        data class Added(val families: List<Family>, val alreadyThere: Boolean) : ImportResult {
            /** 한글이 없는 가족이 있다. 한글은 기본 글꼴로 보인다고 알려야 한다. */
            val withoutHangul: Boolean get() = families.any { !it.hasHangul }
        }

        data class Rejected(val reason: Reason) : ImportResult

        enum class Reason { NotAFont, WebFont, Broken, TooLarge, Unreadable }
    }

    private var cached: List<Family>? = null
    private val typefaces = HashMap<Triple<File, Int, Int?>, Typeface>()

    /** 넣은 글꼴 가족. 이름 순. */
    @Synchronized
    fun families(): List<Family> = cached ?: scan().also { cached = it }

    @Synchronized
    fun family(key: String): Family? = families().firstOrNull { it.key == key }

    /** 보통·굵게 서체. 한 번 읽은 서체는 붙잡아 둔다 — 페이지를 그릴 때마다 파일을 열지 않는다. */
    @Synchronized
    fun pair(family: Family): FontPair {
        val regular = family.regular
        // 가변 폰트 하나뿐인 가족(Pretendard Variable, Noto Serif KR)은 같은 파일에서 굵기 축만 바꿔
        // 굵게를 만든다. 합성 굵게보다 훨씬 곱다.
        val bold = family.bold?.let { typeface(it, 700) }
            ?: regular.info.variableWeights?.takeIf { 700 in it }?.let { typeface(regular, 700) }
        return FontPair(typeface(regular, 400), bold)
    }

    /**
     * 폰트 파일을 넣는다. [input] 은 여기서 닫지 않는다.
     *
     * 순서: 임시 파일로 복사(크기 제한) → 머리 읽기 → 안드로이드가 실제로 읽는지 확인 →
     * 내용 해시 이름으로 옮김. 같은 파일을 두 번 넣으면 하나만 남는다.
     */
    fun import(input: InputStream, maxBytes: Long = MAX_BYTES): ImportResult {
        dir.mkdirs()
        val incoming = File.createTempFile("incoming", ".tmp", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                incoming.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) return ImportResult.Rejected(ImportResult.Reason.TooLarge)
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            } catch (e: IOException) {
                return ImportResult.Rejected(ImportResult.Reason.Unreadable)
            }

            val faces = try {
                SfntReader.read(incoming)
            } catch (e: FontFormatException) {
                return ImportResult.Rejected(
                    when (e.reason) {
                        FontFormatException.Reason.NotAFont -> ImportResult.Reason.NotAFont
                        FontFormatException.Reason.Woff -> ImportResult.Reason.WebFont
                        FontFormatException.Reason.Broken -> ImportResult.Reason.Broken
                    },
                )
            }
            // 머리가 멀쩡해도 글리프 표가 깨졌을 수 있다. 안드로이드가 못 읽는 폰트를 받아 두면
            // 고를 때마다 기본 글꼴로 조용히 바뀐다 — 넣을 때 거절하는 편이 낫다.
            if (faces.any { load(incoming, it.index) == null }) {
                return ImportResult.Rejected(ImportResult.Reason.Broken)
            }

            val name = digest.digest().joinToString("") { "%02x".format(it) }.take(24) + extension(incoming)
            val target = File(dir, name)
            val already = target.exists()
            if (!already && !incoming.renameTo(target)) return ImportResult.Rejected(ImportResult.Reason.Unreadable)

            synchronized(this) {
                cached = null
                return ImportResult.Added(families().filter { target in it.files }, already)
            }
        } finally {
            incoming.delete()
        }
    }

    /** 가족을 지운다. 이 글꼴로 보던 설정은 [FontCatalog.effectiveKey] 가 기본 글꼴로 돌린다. */
    @Synchronized
    fun remove(key: String) {
        val family = family(key) ?: return
        // TTC 한 파일에 여러 가족이 있으면 다른 가족도 함께 사라진다. 드문 경우라 받아들인다.
        family.files.forEach { file ->
            file.delete()
            typefaces.keys.removeAll { it.first == file }
        }
        cached = null
    }

    private fun scan(): List<Family> {
        val faces = dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension in EXTENSIONS }
            .flatMap { file ->
                // 한 번 받아 둔 파일이 읽히지 않으면(저장소 손상) 그 파일만 뺀다.
                val all = runCatching { SfntReader.read(file) }.getOrDefault(emptyList())
                // Noto CJK 같은 TTC 는 한 파일에 일본어·중국어·한국어 가족이 다 들어 있다. 한국어
                // 이름을 가진 가족이 있으면 그것만 올린다 — 비슷한 이름 다섯 개가 뜨면 고를 수가 없다.
                val families = all.map { it.family }.toSet()
                val picked = if (families.size > 1 && all.any { it.hasKoreanName }) all.filter { it.hasKoreanName } else all
                picked.map { Face(file, it) }
            }
        return faces.groupBy { it.info.family.lowercase() }.values
            .map(::family)
            .sortedBy { it.label.lowercase() }
    }

    private fun family(faces: List<Face>): Family {
        // 기울임만 있는 가족이 아니면 바로 선 서체에서 고른다. 본문을 기울임으로 조판하면 안 된다.
        val upright = faces.filter { !it.info.italic }.ifEmpty { faces }
        val regular = upright.minWith(compareBy<Face> { kotlin.math.abs(it.nominalWeight - 400) }.thenBy { it.nominalWeight })
        val bold = upright.filter { it !== regular && it.nominalWeight >= 600 && it.nominalWeight > regular.nominalWeight }
            .minByOrNull { kotlin.math.abs(it.nominalWeight - 700) }
        return Family(
            key = KEY_PREFIX + regular.info.family.lowercase(),
            label = regular.info.label,
            regular = regular,
            bold = bold,
            hasHangul = regular.info.hasHangul,
            files = faces.map { it.file }.toSet(),
        )
    }

    /** [weight] 는 가변 폰트일 때만 쓰인다(축 범위 안으로 자른다). 고정 폰트는 파일 그대로. */
    private fun typeface(face: Face, weight: Int): Typeface {
        val axis = face.info.variableWeights?.let { weight.coerceIn(it) }
        return typefaces.getOrPut(Triple(face.file, face.info.index, axis)) {
            load(face.file, face.info.index, axis) ?: Typeface.DEFAULT
        }
    }

    private fun extension(file: File): String = runCatching {
        file.inputStream().use { input ->
            val tag = ByteArray(4).also { input.read(it) }.toString(Charsets.ISO_8859_1)
            when (tag) {
                "ttcf" -> ".ttc"
                "OTTO" -> ".otf"
                else -> ".ttf"
            }
        }
    }.getOrDefault(".ttf")

    companion object {
        const val KEY_PREFIX = "user:"

        /** 한글 폰트 중 큰 것(가변 폰트 · 한자 포함 TTC)도 40MB 를 넘지 않는다. */
        const val MAX_BYTES = 64L * 1024 * 1024

        private val EXTENSIONS = setOf("ttf", "otf", "ttc")

        /**
         * 파일에서 서체를 읽는다. 실패하면 null.
         *
         * `Typeface.Builder.build()` 는 실패를 예외가 아니라 **대체 서체**로 알린다(대체를 정하지
         * 않으면 판에 따라 null 이거나 기본 서체). 둘 다 실패로 본다.
         */
        fun load(file: File, index: Int, weight: Int? = null): Typeface? = runCatching {
            Typeface.Builder(file).setTtcIndex(index)
                .apply { if (weight != null) setFontVariationSettings("'wght' $weight") }
                .build()
        }.getOrNull()?.takeUnless { it == Typeface.DEFAULT }
    }
}
