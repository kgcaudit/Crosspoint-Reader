package io.github.kgcaudit.reader.text

import android.graphics.Typeface
import io.github.kgcaudit.reader.document.ReflowDocument
import io.github.kgcaudit.reader.layout.book.BookFontTable
import io.github.kgcaudit.reader.text.font.SfntReader
import java.io.File
import java.security.MessageDigest

/**
 * 책에 든 글꼴(출판사 글꼴)을 꺼내 읽어 둔 것. [AndroidTextMeasurer] 가
 * [TextStyle.face][io.github.kgcaudit.reader.layout.TextStyle.face] 번호로 서체를 고른다.
 *
 * EPUB 안의 글꼴은 대개 압축돼 있어 `Typeface` 가 바로 읽지 못한다. 그래서 [dir] 에 파일로
 * 꺼낸다 — 캐시 영역이라 공간이 모자라면 시스템이 지우고, 다음에 열 때 다시 꺼낸다.
 */
class BookTypefaces private constructor(private val families: List<Family?>) {

    private class Face(val typeface: Typeface, val weight: Int, val italic: Boolean)

    private class Family(val faces: List<Face>)

    /** 읽을 수 있는 글꼴이 하나도 없다. 목록에 "출판사 글꼴" 을 내놓을 이유가 없다. */
    val isEmpty: Boolean get() = families.all { it == null }

    /** 목록에서 "출판사 글꼴" 이름을 그릴 서체(본문에 가장 많이 쓰일 첫 가족). */
    val preview: Typeface? get() = families.firstNotNullOfOrNull { it }?.let { pick(it, bold = false).first }

    /**
     * 번호 [face] 의 서체와 **굵기를 합성해야 하는지.** 모르는 번호·읽지 못한 가족이면 null —
     * 측정기가 본문 글꼴을 쓴다.
     *
     * 굵기는 `@font-face` 에 적힌 값, 없으면 폰트 파일의 값이다. 퀴즈 책처럼 "바탕B" 라는 **굵은
     * 파일 하나만 든 가족**을 제목에 쓰면서 제목을 굵게(h1 기본값) 두는 책이 흔하다. 적힌 굵기만
     * 보면 보통으로 알고 한 번 더 굵혀 획이 뭉개진다.
     */
    fun select(face: Int, bold: Boolean): Pair<Typeface, Boolean>? {
        val family = families.getOrNull(face - 1) ?: return null
        return pick(family, bold)
    }

    private fun pick(family: Family, bold: Boolean): Pair<Typeface, Boolean> {
        val upright = family.faces.filter { !it.italic }.ifEmpty { family.faces }
        if (bold) {
            upright.filter { it.weight >= 600 }.minByOrNull { kotlin.math.abs(it.weight - 700) }?.let { return it.typeface to false }
        }
        val regular = upright.minWith(compareBy<Face> { kotlin.math.abs(it.weight - 400) }.thenBy { it.weight })
        return regular.typeface to (bold && regular.weight < 600)
    }

    companion object {
        val EMPTY = BookTypefaces(emptyList())

        /**
         * [table] 의 글꼴 파일을 꺼내 읽는다. 없는 파일·웹 폰트·깨진 파일은 빼고 간다(그 가족은
         * 본문 글꼴로 그려진다). 이미 꺼내 둔 파일은 다시 꺼내지 않는다.
         */
        suspend fun prepare(document: ReflowDocument, table: BookFontTable, dir: File): BookTypefaces {
            if (table.isEmpty) return EMPTY
            dir.mkdirs()
            val families = table.families.map { family ->
                val faces = family.files.mapNotNull { file ->
                    val target = File(dir, name(file.path))
                    if (!target.isFile && !extract(document, file.path, target)) return@mapNotNull null
                    val info = runCatching { SfntReader.read(target).firstOrNull() }.getOrNull()
                    // 가변 폰트는 기본 인스턴스가 아니라 보통(400)으로 — 기본이 아주 가는 글꼴이 있다.
                    val axis = info?.variableWeights?.let { 400.coerceIn(it) }
                    val typeface = info?.let { UserFonts.load(target, it.index, axis) }
                    if (info == null || typeface == null) {
                        // 읽지 못하는 파일을 남겨 두면 다음에도 꺼냈다고 믿고 또 실패한다.
                        target.delete()
                        return@mapNotNull null
                    }
                    Face(typeface, file.weight ?: axis ?: info.weight, file.italic ?: info.italic)
                }
                faces.takeIf { it.isNotEmpty() }?.let(::Family)
            }
            return BookTypefaces(families)
        }

        private suspend fun extract(document: ReflowDocument, path: String, target: File): Boolean {
            val temp = File(target.parentFile, target.name + ".part")
            return runCatching {
                val input = document.openFont(path) ?: return false
                input.use { i -> temp.outputStream().use { i.copyTo(it) } }
                temp.renameTo(target)
            }.getOrDefault(false).also { temp.delete() }
        }

        /** 책 안 경로에서 파일 이름. 경로에 한글·공백·`../` 가 섞여도 안전한 이름이 되게 해시한다. */
        private fun name(path: String): String =
            MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + ".font"
    }
}
