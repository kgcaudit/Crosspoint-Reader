package io.github.kgcaudit.reader.document.comic

/** 만화 단위의 종류. */
enum class ComicUnitKind { ARCHIVE, IMAGE_FOLDER }

/**
 * 훑기가 찾은 만화 단위 하나(압축 파일 하나 · 그림 폴더 하나). 묶기의 입력이다.
 *
 * @param id 문서 URI. 진도 · 책갈피 · 손 고침의 열쇠.
 * @param folders 등록 폴더에서 이 단위가 든 폴더까지의 이름(등록 폴더 이름이 맨 앞). 작품 이름이 파일 이름에 없을 때
 *   (예: "001화") 여기서 찾는다. 같은 작품이 어디서 모였는지 보이는 데도 쓴다.
 * @param sizeBytes null 은 "모름" — 0 바이트와 다르다.
 * @param contents 살핀 결과. 아직 살피지 않았으면 null(이름만으로 묶는다).
 */
data class ComicUnit(
    val id: String,
    val name: String,
    val folders: List<String>,
    val kind: ComicUnitKind,
    val sizeBytes: Long? = null,
    val info: ComicInfo? = null,
    val contents: ComicContents? = null,
) {
    /** "Comics › 별을 줍는 아이" — 사람이 읽는 자리. */
    val place: String get() = folders.joinToString(" › ")
}

/**
 * 사용자가 손으로 고친 것. 다시 훑어도 남아야 하므로 단위의 [ComicUnit.id] 와 작품 열쇠로 적는다.
 *
 * @param workOf 단위 → 작품 열쇠. "다른 작품과 합치기" · "이 작품에서 빼기" 가 여기에 적힌다.
 * @param titles 작품 열쇠 → 보이는 이름("작품 이름 고치기"). 파일 이름은 바꾸지 않는다.
 * @param preferred 같은 권이 여러 곳에 있을 때 고른 단위. 열쇠는 [WorkEntry.slot].
 */
data class ComicOverrides(
    val workOf: Map<String, String> = emptyMap(),
    val titles: Map<String, String> = emptyMap(),
    val preferred: Map<String, String> = emptyMap(),
)

/**
 * 작품 화면의 한 줄: 한 권 · 한 화 · 합본 하나 · 외전 하나.
 *
 * @param unit 읽을 파일. 같은 권이 여러 곳이면 고른 것(없으면 첫 곳).
 * @param copies [unit] 말고 같은 권인 다른 곳들. 비어 있지 않으면 "같은 권 n곳" 을 보인다.
 * @param slot 같은 권을 가리는 열쇠. [ComicOverrides.preferred] 에 쓴다.
 * @param sections 합본 안의 권 · 화. 작품 화면에서 이 줄 아래에 들여 보인다.
 */
data class WorkEntry(
    val label: String,
    val name: ComicName,
    val unit: ComicUnit,
    val copies: List<ComicUnit>,
    val slot: String,
    val omnibus: Boolean,
    val sections: List<String>,
)

/**
 * 작품(시리즈) 하나.
 *
 * @param key 이름 열쇠([ComicName.key]). 손 고침이 이것으로 작품을 가리킨다.
 * @param webtoon 모든 줄이 화 단위다(권이 없다).
 * @param places 이 작품의 단위가 들어 있던 폴더들(중복 없이). 둘 이상이면 "2곳에서 모음".
 * @param rightToLeft ComicInfo 가 정한 넘기는 방향. 아무도 정하지 않았으면 null.
 */
data class Work(
    val key: String,
    val title: String,
    val entries: List<WorkEntry>,
    val webtoon: Boolean,
    val complete: Boolean,
    val places: List<String>,
    val rightToLeft: Boolean?,
) {
    val volumeCount: Int get() = entries.size
}

/**
 * 만화 단위들 → 작품들. 순수 함수다: 같은 입력이면 같은 결과(훑을 때마다 작품이 섞이지 않는다).
 *
 * 작품 이름은 이 순서로 정한다. 앞 단계가 "정하지 않음" 이면 다음으로 간다(규칙 5):
 * 손 고침 → ComicInfo `Series` → 파일 이름 → 폴더 이름. 이름 열쇠가 같으면 **어느 폴더에 있든** 한 작품이다
 * (2026-10-02 사용자 결정 3: 흩어진 폴더도 합친다).
 */
object ComicShelf {

    /** 어떤 이름도 얻지 못한 단위가 모이는 작품. */
    const val UNSORTED_KEY: String = ""
    const val UNSORTED_TITLE: String = "분류 안 됨"

    private class Placed(val unit: ComicUnit, val name: ComicName, val series: String)

    fun group(units: List<ComicUnit>, overrides: ComicOverrides = ComicOverrides()): List<Work> {
        val placed = units.map { place(it) }
        val byKey = LinkedHashMap<String, MutableList<Placed>>()
        for (p in placed) {
            val key = overrides.workOf[p.unit.id] ?: ComicName.key(p.series)
            byKey.getOrPut(key) { ArrayList() } += p
        }
        return byKey.map { (key, members) -> work(key, members, overrides) }
            .sortedWith(compareBy<Work> { it.key == UNSORTED_KEY }.thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) })
    }

    /** 단위 하나의 작품 이름과 번호를 정한다. */
    private fun place(unit: ComicUnit): Placed {
        var name = ComicName.parse(unit.name, hasExtension = unit.kind == ComicUnitKind.ARCHIVE)
        // 숫자뿐인 그림 폴더("001")는 웹툰의 화다 — 권으로 읽으면 화 마흔여덟 개가 1~48권이 된다.
        if (unit.kind == ComicUnitKind.IMAGE_FOLDER && name.bareNumber && name.chapter == null) {
            name = name.copy(chapter = name.volume, volume = null, bareNumber = false)
        }
        val info = unit.info
        if (info != null) {
            info.number?.let { n -> if (!name.isRange) name = name.copy(volume = n) }
            if (info.isSpecial) name = name.copy(special = true)
        }
        val series = info?.series?.trim()?.takeIf { it.isNotEmpty() }
            ?: name.series.takeIf { ComicName.key(it).isNotEmpty() }
            ?: fromFolders(unit.folders)
            ?: ""
        return Placed(unit, name, series)
    }

    /**
     * 파일 이름에 작품 이름이 없을 때(예: 웹툰의 "001화", 이름 없는 "01.cbz") 든 폴더에서 찾는다. 가까운 폴더부터 —
     * 단, "01권" · "1부" 처럼 번호뿐인 폴더는 건너뛴다(그 위가 작품 이름이다). 등록 폴더 자체도 마지막 후보다:
     * 사용자가 "전학생" 폴더 하나를 등록하면 그 안의 화 폴더들은 등록 폴더 이름 말고 작품 이름을 얻을 곳이 없다.
     */
    private fun fromFolders(folders: List<String>): String? {
        for (i in folders.indices.reversed()) {
            val series = ComicName.parse(folders[i], hasExtension = false).series
            if (ComicName.key(series).isNotEmpty()) return series
        }
        return null
    }

    private fun work(key: String, members: List<Placed>, overrides: ComicOverrides): Work {
        // 보이는 이름: 고친 이름 → ComicInfo 이름 → 가장 많이 나온 이름(같으면 자연 순서로 앞선 것).
        val title = overrides.titles[key]
            ?: members.firstNotNullOfOrNull { it.unit.info?.series?.trim()?.takeIf(String::isNotEmpty) }
            ?: members.map { it.series }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenComparator { a, b -> NaturalOrder.compare(a.key, b.key) })
                .firstOrNull()?.key
            ?: UNSORTED_TITLE

        // 같은 권 묶기: 번호가 있는 것만. 번호 없는 외전 · 낱장은 이름으로만 같은 것을 가린다.
        val slots = LinkedHashMap<String, MutableList<Placed>>()
        for (m in members) slots.getOrPut(slotOf(m.name)) { ArrayList() } += m
        val entries = slots.map { (slot, copies) ->
            val sorted = copies.sortedWith { a, b -> NaturalOrder.compare(a.unit.place + "/" + a.unit.name, b.unit.place + "/" + b.unit.name) }
            val chosen = overrides.preferred[slot]?.let { id -> sorted.firstOrNull { it.unit.id == id } } ?: sorted.first()
            val name = chosen.name
            val contents = chosen.unit.contents
            val sections = contents?.sections.orEmpty().map { sectionLabel(it.name) }
            WorkEntry(
                label = label(name),
                name = name,
                unit = chosen.unit,
                copies = sorted.filter { it !== chosen }.map { it.unit },
                slot = slot,
                omnibus = name.isRange || sections.size >= 2 || chosen.unit.info?.isOmnibus == true,
                sections = sections,
            )
        }.sortedWith(ENTRY_ORDER)

        return Work(
            key = key,
            title = title,
            entries = entries,
            webtoon = entries.isNotEmpty() && entries.all { it.name.chapter != null && it.name.volume == null },
            complete = members.any { it.name.complete },
            places = members.map { it.unit.place }.distinct(),
            rightToLeft = members.firstNotNullOfOrNull { it.unit.info?.rightToLeft },
        )
    }

    /** 같은 권을 가리는 열쇠. 번호가 없으면 다듬은 이름 — 이름까지 같아야 같은 것이다. */
    private fun slotOf(n: ComicName): String {
        fun d(v: Double?) = v?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } ?: "-"
        return if (n.hasNumber) "p${n.part ?: 0}|v${d(n.volume)}-${d(n.volumeEnd)}|c${d(n.chapter)}-${d(n.chapterEnd)}|s${n.special}"
        else "n|" + ComicName.key(n.cleaned).ifEmpty { n.cleaned }
    }

    private fun num(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    /** "3권" · "4–6권" · "17화" · "2부 3권" · "외전" · 번호가 없으면 다듬은 이름. */
    fun label(n: ComicName): String {
        val head = n.part?.let { "${it}부 " } ?: ""
        val body = when {
            n.volume != null && n.volumeEnd != null -> "${num(n.volume)}–${num(n.volumeEnd)}권"
            n.volume != null && n.special && n.volume == 0.0 -> "0권"
            n.volume != null -> "${num(n.volume)}권" + (n.chapter?.let { " ${num(it)}화" } ?: "")
            n.chapter != null && n.chapterEnd != null -> "${num(n.chapter)}–${num(n.chapterEnd)}화"
            n.chapter != null -> "${num(n.chapter)}화"
            n.special -> n.cleaned.removePrefix(n.series).trim().ifEmpty { "외전" }
            else -> n.cleaned
        }
        return (head + body).trim()
    }

    private fun sectionLabel(raw: String): String {
        if (raw.isEmpty()) return "앞부분"
        val n = ComicName.parse(raw, hasExtension = false)
        return if (n.hasNumber || n.special) label(n) else n.cleaned
    }

    /** 부 → 권(범위는 시작) → 화 → 특별편은 끝 → 번호 없는 것은 자연 순서로 맨 뒤. 12.5권은 12 와 13 사이. */
    private val ENTRY_ORDER: Comparator<WorkEntry> = Comparator { a, b ->
        val x = a.name
        val y = b.name
        compareValuesBy(
            x, y,
            { !it.hasNumber && !it.special },
            { it.special },
            { it.part ?: 0 },
            { it.volume ?: Double.MAX_VALUE },
            { it.chapter ?: -1.0 },
        ).takeIf { it != 0 } ?: NaturalOrder.compare(a.label, b.label).takeIf { it != 0 }
            ?: NaturalOrder.compare(a.unit.name, b.unit.name)
    }
}
