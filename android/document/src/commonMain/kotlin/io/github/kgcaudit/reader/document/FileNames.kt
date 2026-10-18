package io.github.kgcaudit.reader.document

/**
 * 파일 이름(압축 안 경로여도 된다 — 마지막 `/` 뒤만 본다)의 확장자, 소문자. 점이 없거나, 점으로 시작하는 이름(`.nomedia`
 * · `.zip`)이거나, 점으로 끝나면 null.
 *
 * 한 곳에 두는 까닭: 곳마다 손으로 꺼내다 점 없는 이름을 저마다 다르게 다뤘다. `substringAfterLast('.')` 는 점이 없으면
 * 이름 전체를 돌려줘, 0.48.0 에서 "zip" 이라는 이름의 파일이 압축 속 권으로 잡혔다.
 */
fun extensionOf(name: String): String? {
    val base = name.substringAfterLast('/')
    val dot = base.lastIndexOf('.')
    if (dot <= 0 || dot == base.length - 1) return null
    return base.substring(dot + 1).lowercase()
}
