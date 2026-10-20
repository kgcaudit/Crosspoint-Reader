@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package io.github.kgcaudit.reader.ios

import io.github.kgcaudit.reader.layout.LayoutSpec
import io.github.kgcaudit.reader.layout.TextMeasurer
import io.github.kgcaudit.reader.layout.TextStyle
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFAttributedStringCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringCreateWithCharacters
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreText.CTFontCreateCopyWithSymbolicTraits
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.CTFontCreateWithFontDescriptor
import platform.CoreText.CTFontDescriptorRef
import platform.CoreText.CTFontRef
import platform.CoreText.CTLineCreateWithAttributedString
import platform.CoreText.CTLineGetBoundsWithOptions
import platform.CoreText.CTLineGetTypographicBounds
import platform.CoreText.CTLineRef
import platform.CoreText.kCTFontAttributeName
import platform.CoreText.kCTFontUIFontEmphasizedSystem
import platform.CoreText.kCTFontTraitBold
import platform.CoreText.kCTFontUIFontSystem
import platform.CoreText.kCTForegroundColorFromContextAttributeName
import platform.CoreText.kCTLineBoundsUseGlyphPathBounds
import platform.CoreText.kCTStrokeWidthAttributeName
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

/**
 * CoreText 로 재는 [TextMeasurer](iOS). 안드로이드 `AndroidTextMeasurer` 와 같은 약속을 지킨다 — 두 앱의 한 쪽에 같은 수의 줄이
 * 들어가야 책갈피 · 진도가 같은 자리를 가리킨다.
 *
 * - **단위**: 공통 코어의 "px" 자리에 iOS 의 포인트(pt)를 그대로 쓴다. 쪽 좌표가 곧 화면 좌표다.
 * - **줄 높이는 1em**, 줄 간격 배수는 조판기가 곱한다. 글꼴의 ascent/descent 를 쓰면 글꼴만 바꿔도 쪽당 줄 수가 달라진다.
 * - **위 높이(ascent)는 실제 글자 윗선**("가漢Hbdhklt" 중 가장 높은 것), 보통 굵기에서만 잰다. 굵게마다 재면 굵은 단어가 든
 *   줄만 기준선이 내려간다.
 * - **기울임은 합성**(그릴 때 기울이기). 한글 글꼴에는 기울임꼴이 없고, 기울이기는 폭을 바꾸지 않는다.
 * - 폭은 CTLine 으로 잰다 — 커닝 · 한글 조합 · 대체 글꼴(SF 에 없는 한글은 Apple SD Gothic Neo)이 그릴 때와 똑같이 들어간다.
 *
 * 글꼴은 **시스템 글꼴**(디자인 인계 §3, 한국어 우선). [language] 를 주면 대체 글꼴의 순서가 그 언어를 따른다.
 * [fontFile] 을 주면 그 글꼴 파일(사용자 글꼴)로 잰다. 그 글꼴에 없는 글자(한글 없는 영문 글꼴의 한글)는 CTLine 이 시스템 글꼴로
 * 채운다 — 안드로이드의 "한글은 휴대폰 글꼴로 보입니다" 와 같다. 파일이 없거나 깨졌으면 시스템 글꼴로 물러선다: 글꼴 파일
 * 하나 때문에 책이 열리지 않는 것이 최악이다(CLAUDE.md 규칙 6).
 *
 * **한 스레드에서만 쓴다**(안드로이드와 같다). 서식별 글꼴 캐시가 잠금 없는 HashMap 이다. 조판용과 그리기용을 따로 만든다 —
 * 같은 설정이면 같은 값이 나온다.
 */
class CoreTextMeasurer(
    override val baseSizePx: Float,
    private val language: String = "ko-KR",
    fontFile: String? = null,
) : TextMeasurer {

    init {
        require(baseSizePx > 0f) { "baseSizePx must be positive, was $baseSizePx" }
    }

    private val faces = FaceCache(fontFile?.let { UserFonts.descriptor(it) })

    /** 사용자 글꼴 파일을 실제로 읽었는지. 아니면(없음 · 깨짐) 시스템 글꼴로 재는 중이다. */
    val usesFontFile: Boolean get() = faces.descriptor != null

    // CF 객체는 Kotlin 이 거두지 않는다. 측정기를 버리면 글꼴도 놓는다.
    @Suppress("unused")
    private val cleaner = createCleaner(faces) { it.releaseAll() }

    private val ascentEm: Float = run {
        val probe = Face.create(PROBE_SIZE_PX, bold = false, italic = false, language = language, descriptor = faces.descriptor)
        try {
            probe.glyphTop(PROBE_GLYPHS)?.let { (it / PROBE_SIZE_PX).coerceIn(0.5f, 1f) } ?: FALLBACK_ASCENT_EM
        } finally {
            probe.release()
        }
    }

    override fun advance(text: CharSequence, start: Int, endExclusive: Int, style: TextStyle): Float {
        if (endExclusive <= start) return 0f
        return face(style).width(text, start, endExclusive)
    }

    override fun lineHeight(style: TextStyle): Float = face(style).sizePx

    override fun ascent(style: TextStyle): Float = face(style).sizePx * ascentEm

    override fun spaceAdvance(style: TextStyle): Float = face(style).space

    /** 이 서식을 그릴 글꼴. 그리는 쪽([PagePainter])은 반드시 이것을 쓴다 — 잰 글꼴과 그린 글꼴이 다르면 양쪽정렬 끝이 어긋난다. */
    internal fun face(style: TextStyle): Face {
        // 밑줄 · 취소선 · 위첨자는 폭을 바꾸지 않으므로 키에서 뺀다. 책 글꼴(face)은 아직 없다 — 모두 본문 글꼴로 잰다.
        val key = Key(style.bold, style.italic, style.sizeScale)
        return faces.map.getOrPut(key) {
            Face.create(baseSizePx * style.sizeScale, style.bold, style.italic, language, faces.descriptor)
        }
    }

    internal data class Key(val bold: Boolean, val italic: Boolean, val sizeScale: Float)

    internal class FaceCache(val descriptor: CTFontDescriptorRef?) {
        val map = HashMap<Key, Face>()

        fun releaseAll() {
            map.values.forEach { it.release() }
            map.clear()
            descriptor?.let { CFRelease(it) }
        }
    }

    companion object {
        /** 글자 윗선을 잴 때의 크기. 반올림 오차를 0.1% 로 줄인다. */
        private const val PROBE_SIZE_PX = 1000f

        /** 윗선을 재는 글자(안드로이드와 같다). 한글 · 한자 · 라틴 대문자 · 어센더 중 가장 높은 것. */
        private const val PROBE_GLYPHS = "가漢Hbdhklt"

        private const val FALLBACK_ASCENT_EM = 0.8f

        /** 합성 기울임(약 14°). 안드로이드의 textSkewX −0.25 와 같은 모양. */
        internal const val ITALIC_SKEW = 0.25

        /**
         * 조판 설정에서 만든다. 글꼴은 [LayoutSpec.fontId] 에서만 고른다 — 캐시 키에 든 글꼴과 잰 글꼴이 갈라지면 바탕으로 잰
         * 쪽을 고딕으로 그려 줄 끝이 어긋난다. 조판용 · 그리기용이 모두 여기서 만들어진다.
         */
        fun forSpec(spec: LayoutSpec): CoreTextMeasurer = CoreTextMeasurer(spec.baseSizePx, fontFile = UserFonts.path(spec.fontId))
    }
}

/** 한 서식(크기 · 굵기 · 기울임)의 CoreText 글꼴과 글자 속성. */
internal class Face private constructor(
    val font: CTFontRef,
    private val attributes: CFDictionaryRef,
    val sizePx: Float,
    val italic: Boolean,
) {
    private var released = false

    val space: Float = width(" ", 0, 1)

    /** [start, end) 의 줄. 부르는 쪽이 CFRelease 한다. */
    fun line(text: CharSequence, start: Int, end: Int): CTLineRef? {
        val length = end - start
        if (length <= 0) return null
        val chars = CharArray(length) { text[start + it] }
        val string = chars.usePinned { CFStringCreateWithCharacters(null, it.addressOf(0).reinterpret(), length.toLong()) } ?: return null
        val attributed = CFAttributedStringCreate(null, string, attributes)
        CFRelease(string)
        attributed ?: return null
        val line = CTLineCreateWithAttributedString(attributed)
        CFRelease(attributed)
        return line
    }

    fun width(text: CharSequence, start: Int, end: Int): Float {
        val line = line(text, start, end) ?: return 0f
        return try {
            CTLineGetTypographicBounds(line, null, null, null).toFloat()
        } finally {
            CFRelease(line)
        }
    }

    /** 기준선에서 글자 윗선까지(위가 +). 그릴 글자가 없으면 null. */
    fun glyphTop(text: String): Float? {
        val line = line(text, 0, text.length) ?: return null
        return try {
            CTLineGetBoundsWithOptions(line, kCTLineBoundsUseGlyphPathBounds).useContents {
                if (size.height <= 0.0) null else (origin.y + size.height).toFloat()
            }
        } finally {
            CFRelease(line)
        }
    }

    fun release() {
        if (released) return
        released = true
        CFRelease(attributes)
        CFRelease(font)
    }

    companion object {
        fun create(sizePx: Float, bold: Boolean, italic: Boolean, language: String, descriptor: CTFontDescriptorRef? = null): Face {
            val (font, fakeBold) = descriptor?.let { fileFont(it, sizePx, bold) } ?: systemFont(sizePx, bold, language)
            val attributes = CFDictionaryCreateMutable(null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                ?: error("CFDictionaryCreateMutable failed")
            CFDictionarySetValue(attributes, kCTFontAttributeName, font)
            // 글자색은 그릴 때의 채움색을 따른다 — 지면 테마마다 속성을 새로 만들지 않게.
            CFDictionarySetValue(attributes, kCTForegroundColorFromContextAttributeName, kCFBooleanTrue)
            if (fakeBold) {
                memScoped {
                    val stroke = alloc<kotlinx.cinterop.DoubleVar>().apply { value = FAKE_BOLD_STROKE }
                    val number = CFNumberCreate(null, kCFNumberDoubleType, stroke.ptr)
                    CFDictionarySetValue(attributes, kCTStrokeWidthAttributeName, number)
                    number?.let { CFRelease(it) }
                }
            }
            return Face(font, attributes, sizePx, italic)
        }

        private fun systemFont(sizePx: Float, bold: Boolean, language: String): Pair<CTFontRef, Boolean> {
            val lang = CFStringCreateWithCString(null, language, kCFStringEncodingUTF8)
            // 굵게는 시스템의 굵은 글꼴을 직접 고른다. 보통 글꼴에 굵게 특성을 달면 Semibold 가 나와 안드로이드(Bold)보다 옅다.
            val type = if (bold) kCTFontUIFontEmphasizedSystem else kCTFontUIFontSystem
            val chosen = CTFontCreateUIFontForLanguage(type, sizePx.toDouble(), lang)
            // 굵은 꼴이 없으면 보통 꼴의 획을 두껍게 그린다. 테두리는 폭을 바꾸지 않는다.
            val fakeBold = bold && chosen == null
            val font: CTFontRef = chosen ?: CTFontCreateUIFontForLanguage(kCTFontUIFontSystem, sizePx.toDouble(), lang)
                ?: error("no system font for $language")
            lang?.let { CFRelease(it) }
            return font to fakeBold
        }

        /**
         * 글꼴 파일의 꼴. 굵게는 같은 가족의 굵은 꼴이 등록돼 있으면 그것, 없으면(파일 하나뿐인 글꼴이 대부분) 획을 두껍게
         * 합성한다. 파일을 읽지 못하면 null — 시스템 글꼴로 물러선다.
         */
        private fun fileFont(descriptor: CTFontDescriptorRef, sizePx: Float, bold: Boolean): Pair<CTFontRef, Boolean>? {
            val regular = CTFontCreateWithFontDescriptor(descriptor, sizePx.toDouble(), null) ?: return null
            if (!bold) return regular to false
            val heavy = CTFontCreateCopyWithSymbolicTraits(regular, 0.0, null, kCTFontTraitBold, kCTFontTraitBold)
            if (heavy == null) return regular to true
            CFRelease(regular)
            return heavy to false
        }

        /** 음수 = 채우고 테두리도 그린다. 글자 크기의 3%. */
        private const val FAKE_BOLD_STROKE = -3.0
    }
}
