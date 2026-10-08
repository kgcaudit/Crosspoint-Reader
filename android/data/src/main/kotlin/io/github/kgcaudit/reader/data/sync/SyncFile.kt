package io.github.kgcaudit.reader.data.sync

import io.github.kgcaudit.reader.data.backup.BookRecord
import io.github.kgcaudit.reader.data.backup.BookmarkRecord
import io.github.kgcaudit.reader.data.backup.ComicRecord
import io.github.kgcaudit.reader.data.backup.ProgressRecord
import io.github.kgcaudit.reader.data.backup.RecordsCodec
import io.github.kgcaudit.reader.data.backup.RecordsFile
import io.github.kgcaudit.reader.document.Locator
import org.json.JSONException
import org.json.JSONObject

/**
 * 한 기기가 책 폴더 하나에 남긴 읽기 기록(기기 간 이어 읽기, 사용자 결정 8-1). 책 폴더 안 `.olo/sync/<기기 id>.json` 이다.
 *
 * **기기마다 제 파일만 쓴다.** 한 파일을 여러 기기가 고쳐 쓰면 동기화 앱이 두 판을 맞추지 못해 한쪽을 버리거나 "충돌 사본"
 * 을 만든다 — 그러면 읽은 자리가 기기를 오갈 때마다 사라졌다 나타난다. 제 파일만 쓰면 충돌할 것이 없다.
 *
 * 책 · 만화는 백업 파일과 같은 모양([BookRecord] · [ComicRecord])으로 담는다: **파일 이름 + 크기**로 가리킨다. 이 기기의 책
 * id(SAF 주소)는 다른 기기에서 모양이 달라 주소로 적으면 한 권도 맞지 않는다. 형광펜 · 메모는 담지 않는다(결정 8-3).
 */
data class SyncFile(
    val deviceId: String,
    /** 사람이 알아보는 기기 이름("갤럭시 탭 S9"). 알림 띠 · 앱 정보에 그대로 보인다. */
    val deviceName: String,
    val updatedAtEpochMs: Long,
    val books: List<BookRecord> = emptyList(),
    val comics: List<ComicRecord> = emptyList(),
)

/**
 * [SyncFile] 의 글 모양. 겉은 기기 정보, 안(`records`)은 백업 파일([RecordsCodec])과 같은 JSON 이다 — 같은 기록을 두 형식으로
 * 적으면 한쪽을 고칠 때 다른 쪽이 어긋난다.
 *
 * 읽기는 관대하다(규칙 6): 깨진 JSON · 다른 앱의 파일 · 충돌 사본은 그 파일만 null 로 버린다. 모르는 칸은 건너뛴다 — 다음 판이
 * 칸을 더해도 옛 판이 그 기기의 읽은 자리를 읽는다.
 */
object SyncCodec {
    const val FORMAT: String = "olo-ebook-sync"
    const val VERSION: Int = 1

    /** 책 폴더 안의 숨은 폴더. 점으로 시작해 서재 훑기가 보지 않는다(LibraryScanner 가 점 이름을 건너뛴다). */
    const val DIR: String = ".olo"
    const val SUBDIR: String = "sync"

    /**
     * 기기 id 는 UUID 다. 동기화 앱이 만든 충돌 사본("…(1).json", "….sync-conflict-20261008.json", "… - 복사본.json")은 이 모양에서
     * 벗어나 읽지 않는다 — 읽으면 같은 기기가 둘로 보이고, 옛 사본의 자리로 "더 읽었습니다" 가 뜬다.
     */
    private val NAME = Regex("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.json$")

    fun fileName(deviceId: String): String = "$deviceId.json"

    /** 파일 이름이 기기 파일이면 그 기기 id(소문자), 아니면 null. */
    fun deviceIdOf(fileName: String): String? = NAME.matchEntire(fileName)?.groupValues?.get(1)?.lowercase()

    fun encode(file: SyncFile): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("device", file.deviceId)
        .put("deviceName", file.deviceName)
        .put("updatedAt", file.updatedAtEpochMs)
        .put(
            "records",
            JSONObject(RecordsCodec.encode(RecordsFile(file.updatedAtEpochMs, file.books.map { it.copy(annotations = emptyList()) }, file.comics))),
        )
        .toString(1)

    /**
     * 이 앱의 기기 파일이 아니면 null. [expectedDeviceId] 를 주면(파일 이름에서 읽은 id) 안에 적힌 id 가 그와 같아야 한다 — 다른
     * 기기의 파일을 손으로 복사해 이름만 바꾼 것을 두 기기로 세지 않는다.
     */
    fun decode(text: String, expectedDeviceId: String? = null): SyncFile? {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        }
        if (root.optString("format") != FORMAT) return null
        val id = (root.opt("device") as? String)?.lowercase()?.takeIf { deviceIdOf("$it.json") != null } ?: return null
        if (expectedDeviceId != null && id != expectedDeviceId.lowercase()) return null
        val records = root.optJSONObject("records")?.let { RecordsCodec.decode(it.toString()) }
        val name = (root.opt("deviceName") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_DEVICE
        return SyncFile(
            deviceId = id,
            deviceName = name,
            updatedAtEpochMs = (root.opt("updatedAt") as? Number)?.toLong() ?: 0L,
            // 형광펜은 동기화하지 않는다(8-3). 다른 판이 담아 보냈어도 여기서 버린다 — 들이면 이쪽 형광펜과 합쳐지는 규칙이 필요하다.
            books = records?.books.orEmpty().map { it.copy(annotations = emptyList()) },
            comics = records?.comics.orEmpty(),
        )
    }

    /** 이름이 적혀 있지 않은 기기. 깨진 파일이라도 읽은 자리는 쓸 수 있다 — 이름 하나 때문에 버리지 않는다. */
    const val UNKNOWN_DEVICE: String = "다른 기기"
}

/**
 * 기기 간 이어 읽기의 규칙. 화면 · 저장소 없이 시험한다.
 *
 * 읽은 자리는 **저절로 옮기지 않는다**(결정 8-2): 다른 기기가 더 읽었으면 알림 띠로 묻고, 사람이 "거기로" 를 눌러야 간다. 다시
 * 처음부터 읽는 중인 사람의 자리를 다른 기기의 옛 자리가 덮으면 안 된다. 책갈피 · 다 읽은 때만 조용히 합친다 — 합쳐도 지금
 * 읽는 자리가 움직이지 않는다.
 */
object SyncRules {

    /**
     * 다른 기기의 자리 [remote] 가 지금 보이는 쪽보다 **한 쪽 이상** 뒤인가(EPUB · TXT). 보이는 쪽은 장 [shownSpine] 의 글자
     * [shownEndExclusive] 앞에서 끝난다(두쪽보기면 오른쪽 쪽 끝).
     *
     * 글자 수로 "한 쪽" 을 어림하지 않는다. 기기마다 화면이 달라 한 쪽의 글자 수가 다르다 — 태블릿의 한 쪽 안(같은 쪽)에 있는
     * 휴대폰의 자리로 "더 읽었습니다" 를 띄우면, 눌러도 같은 쪽에 머문다. 지금 보이는 쪽의 끝을 넘었으면 적어도 다음 쪽이다.
     * 다음 장은 늘 새 쪽에서 시작하므로 장이 뒤면 한 쪽 이상 뒤다.
     */
    fun reflowBeyond(remote: Locator.Reflow, shownSpine: Int, shownEndExclusive: Int): Boolean =
        remote.spine > shownSpine || (remote.spine == shownSpine && remote.charOffset >= shownEndExclusive)

    /** PDF · 쪽으로 읽는 책: 다른 기기의 쪽이 지금 보이는 마지막 쪽([shownLast], 두쪽이면 오른쪽 쪽)보다 뒤인가. */
    fun pageBeyond(remotePage: Int, shownLast: Int): Boolean = remotePage > shownLast

    /**
     * 만화: 다른 기기의 쪽이 뒤이거나, 같은 그림(웹툰) 안에서 반 장 이상 아래인가. 웹툰 한 그림은 화면 여러 개 길이라 같은 그림
     * 안이라도 반 장이면 몇 화면 아래다 — 그보다 가까우면 지금 화면에 거의 보이는 자리라 묻지 않는다.
     */
    fun comicBeyond(remotePage: Int, remoteOffset: Float?, localPage: Int, localOffset: Float?): Boolean =
        remotePage > localPage || (remotePage == localPage && (remoteOffset ?: 0f) - (localOffset ?: 0f) >= HALF_PICTURE)

    private const val HALF_PICTURE = 0.5f

    /**
     * 여러 기기 가운데 이 기기([local])보다 더 읽은 자리, 그 가운데 가장 뒤. 없으면 null. 자리가 깨진 기록은 고르지 않는다 —
     * [ProgressRecord.isFurtherThan] 이 깨진 쪽을 "앞" 으로 본다.
     */
    fun <T> furthest(local: ProgressRecord?, remotes: List<Pair<T, ProgressRecord>>): Pair<T, ProgressRecord>? =
        remotes.filter { (_, p) -> Locator.decodeOrNull(p.locator) != null && (local == null || p.isFurtherThan(local)) }
            .reduceOrNull { a, b -> if (b.second.isFurtherThan(a.second)) b else a }

    /** 만화판 [furthest]: 쪽(같은 쪽이면 그림 안 비율)이 더 뒤인 기기. */
    fun <T> furthestComic(local: ComicRecord?, remotes: List<Pair<T, ComicRecord>>): Pair<T, ComicRecord>? =
        remotes.filter { (_, r) -> r.page != null && (local == null || r.isFurtherThan(local)) }
            .reduceOrNull { a, b -> if (b.second.isFurtherThan(a.second)) b else a }

    /**
     * 다른 기기의 책갈피 가운데 이 기기에 넣을 것: 이 기기에 없고, 전에 받은 적도 없는 것. 받은 적이 있는데 지금 없으면 이 기기에서
     * 사람이 뺀 것이다 — 다시 넣으면 뺀 책갈피가 다른 기기를 다녀올 때마다 되살아난다(합집합 규칙의 구멍).
     *
     * @param seen 전에 받았거나 이미 있던 것으로 본 열쇠([bookmarkKey]).
     */
    fun bookmarksToImport(item: String, local: Set<String>, remote: List<BookmarkRecord>, seen: Set<String>): List<BookmarkRecord> =
        remote.distinctBy { it.locator }.filter { it.locator !in local && bookmarkKey(item, it.locator) !in seen }

    fun bookmarkKey(item: String, locator: String): String = "b\u0000$item\u0000$locator"

    /**
     * 다 읽은 때를 바꿀 값. 백업과 같이 **가장 이른 때**가 남는다. 이 기기에 없으면 다른 기기의 때를 들이되, 전에 들였던 같은
     * 때([seen])라면 사람이 이 기기에서 "다 읽음" 을 거둔 것이니 다시 넣지 않는다. 바꿀 것이 없으면 null.
     */
    fun finishedToApply(local: Long?, remote: Long?, seen: Boolean): Long? = when {
        remote == null -> null
        local == null -> remote.takeIf { !seen }
        remote < local -> remote
        else -> null
    }
}
