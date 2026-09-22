package io.github.kgcaudit.reader.document.text

import java.nio.charset.Charset

/**
 * TXT 파일을 읽을 때 쓸 문자 인코딩.
 *
 * 한국어 TXT는 상당수가 BOM 없는 CP949(EUC-KR 확장)로 저장돼 있다. 이걸 UTF-8로
 * 읽으면 본문 전체가 깨지므로, 감지를 대충 하면 리더의 가장 눈에 띄는 버그가 된다.
 */
enum class TextEncoding {
    UTF_8,
    UTF_16LE,
    UTF_16BE,

    /**
     * 한국어 완성형.
     *
     * 실제로는 가능한 한 **CP949**(통합 완성형)로 해석한다. CP949는 EUC-KR의
     * 상위집합이고 한국 윈도우가 저장하는 실제 인코딩이므로, EUC-KR로만 읽으면
     * 확장 영역(0x81–0xA0 선행 바이트) 글자가 깨진다. 플랫폼에 CP949 별칭이 없으면
     * EUC-KR로 내려간다.
     */
    EUC_KR,
    ;

    /** 이 인코딩에 해당하는 [Charset]. 플랫폼이 지원하는 별칭 중 가장 넓은 것을 고른다. */
    val charset: Charset
        get() = when (this) {
            UTF_8 -> Charsets.UTF_8
            UTF_16LE -> Charsets.UTF_16LE
            UTF_16BE -> Charsets.UTF_16BE
            EUC_KR -> koreanCharset
        }

    private companion object {
        /**
         * CP949를 우선하고 EUC-KR로 내려가는 한국어 charset.
         *
         * 별칭이 플랫폼마다 다르다(JVM은 `x-windows-949`, ICU 기반은 `windows-949`).
         * 한 번 해석해서 캐시한다. 어느 것도 없으면 UTF-8로 내려가는데, 그 경우
         * 한국어는 깨지지만 앱이 죽지는 않는다.
         */
        val koreanCharset: Charset = sequenceOf(
            "x-windows-949", "windows-949", "MS949", "CP949", "EUC-KR",
        ).firstNotNullOfOrNull { name ->
            runCatching { Charset.forName(name) }.getOrNull()
        } ?: Charsets.UTF_8
    }
}

/**
 * [EncodingDetector]의 결과.
 *
 * @param encoding 쓸 인코딩
 * @param bomLength 건너뛸 선행 BOM 바이트 수(없으면 0). 이걸 안 건너뛰면 본문 첫
 *   글자가 U+FEFF가 되어 조판에 보이지 않는 글자가 섞인다.
 * @param byBom BOM으로 확정됐는지. 추측이 아니라 확정이라는 뜻.
 */
data class DetectedEncoding(
    val encoding: TextEncoding,
    val bomLength: Int = 0,
    val byBom: Boolean = false,
)
