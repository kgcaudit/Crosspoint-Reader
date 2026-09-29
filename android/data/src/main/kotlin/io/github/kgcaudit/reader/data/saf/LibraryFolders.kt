package io.github.kgcaudit.reader.data.saf

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 사용자가 등록한 책 폴더.
 *
 * 목록을 따로 저장하지 않고 **OS 가 들고 있는 영속 권한**을 그대로 목록으로 쓴다. DB 에
 * 따로 적어 두면, 사용자가 설정에서 권한을 거둬도 목록에는 남아 "폴더는 보이는데 안
 * 열린다" 가 된다. 원천이 하나면 어긋날 수 없다.
 *
 * 파일이 아니라 폴더를 등록하는 이유: 영속 권한은 앱당 개수 제한이 있다(API 30 이상
 * 512개, 그 전 128개). 책마다 권한을 받으면 그 수를 넘는 순간 오래된 책부터 조용히
 * 열리지 않게 된다.
 */
class LibraryFolders(private val resolver: ContentResolver) {

    /** `ACTION_OPEN_DOCUMENT_TREE` 로 받은 트리를 등록한다. 읽기 권한만 받는다. */
    fun register(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun unregister(treeUri: Uri) {
        // 이미 권한이 없는 폴더(다른 곳에서 풀림, 목록이 오래됨)를 빼면 SecurityException 이 난다. 빼려던
        // 것이 이미 빠져 있는 것이니 실패가 아니다 — 그대로 두면 앱이 죽는다.
        runCatching { resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** 등록된 폴더. 읽기 권한이 살아 있는 트리 URI 만 준다. */
    fun folders(): List<Uri> =
        resolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }

    /**
     * [treeUri] 와 겹치는 등록 폴더와, [treeUri] 가 그 안에 드는지(true) 그것을 감싸는지(false). 겹치지 않거나 같은
     * 폴더면 null.
     *
     * 겹친 두 폴더를 모두 등록하면 같은 파일이 트리마다 다른 URI 로 두 번 보였다 — 진도 · 책갈피도 URI 마다 따로라
     * 어느 쪽으로 열었는지에 따라 읽던 자리가 달랐다.
     */
    fun overlapping(treeUri: Uri): Pair<Uri, Boolean>? {
        val newId = treeId(treeUri) ?: return null
        for (folder in folders()) {
            val id = treeId(folder) ?: continue
            if (folder.authority != treeUri.authority) continue
            when (treeRelation(id, newId)) {
                TreeRelation.Inside -> return folder to true
                TreeRelation.Contains -> return folder to false
                else -> Unit
            }
        }
        return null
    }

    private fun treeId(uri: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
}

enum class TreeRelation { Same, Inside, Contains, Apart }

/**
 * 두 트리 문서 id(`primary:Books` · `primary:Books/소설`)의 관계. [child] 가 [parent] 안이면 Inside. 경로 조각 단위로
 * 본다 — 글자로만 앞부분을 비교하면 `Books2` 가 `Books` 안에 든 것으로 읽힌다.
 */
fun treeRelation(parent: String, child: String): TreeRelation {
    fun path(id: String) = id.trimEnd('/')
    val a = path(parent)
    val b = path(child)
    return when {
        a == b -> TreeRelation.Same
        b.startsWith("$a/") || (a.endsWith(":") && b.startsWith(a)) -> TreeRelation.Inside
        a.startsWith("$b/") || (b.endsWith(":") && a.startsWith(b)) -> TreeRelation.Contains
        else -> TreeRelation.Apart
    }
}
