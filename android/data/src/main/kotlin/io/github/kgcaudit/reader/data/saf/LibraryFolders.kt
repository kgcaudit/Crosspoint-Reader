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
        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 등록된 폴더. 읽기 권한이 살아 있는 트리 URI 만 준다. */
    fun folders(): List<Uri> =
        resolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }
}
