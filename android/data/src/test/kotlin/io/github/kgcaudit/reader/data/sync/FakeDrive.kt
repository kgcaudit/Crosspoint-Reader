package io.github.kgcaudit.reader.data.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.Instant

/**
 * 시험용 구글 드라이브: 앱 전용 공간(appDataFolder) 하나를 흉내 낸다. [DriveSyncStore] 가 보내는 요청 모양이 진짜 드라이브가 받는
 * 모양인지(공간 · 부모 · 덮어쓰기 머리 · 토큰)를 여기서 따진다 — 어긋나면 400 을 돌려준다. 여러 기기가 같은 [FakeDrive] 를 나눠 쓴다.
 */
class FakeDrive {
    class File(val id: String, val name: String, var bytes: ByteArray, var modified: Long)

    val files = LinkedHashMap<String, File>()
    var clock = 1_000_000L
    var offline = false
    var full = false

    /** 받아 주는 토큰. 바꾸면 낡은 토큰이 401 을 받는다(만료 흉내). */
    var validToken = "token-1"
    val requests = ArrayList<DriveHttp.Request>()
    private var next = 1

    fun text(name: String): String? = files.values.firstOrNull { it.name == name }?.bytes?.toString(Charsets.UTF_8)

    /** 다른 앱(또는 사람)이 둔 파일 · 깨진 파일을 넣는다. */
    fun put(name: String, text: String): File = File("f${next++}", name, text.toByteArray(), ++clock).also { files[it.id] = it }

    val http = DriveHttp { request ->
        if (offline) throw IOException("no network")
        requests += request
        if (request.headers["Authorization"] != "Bearer $validToken") return@DriveHttp json(401, JSONObject().put("error", JSONObject().put("code", 401)))
        val uri = URI(request.url)
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
            part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }
        val path = uri.path
        val method = request.headers["X-HTTP-Method-Override"] ?: request.method
        when {
            method == "GET" && path == "/drive/v3/files" -> {
                if (query["spaces"] != "appDataFolder") return@DriveHttp bad("list outside appDataFolder")
                json(200, JSONObject().put("files", JSONArray(files.values.map(::meta))))
            }
            method == "GET" && path.startsWith("/drive/v3/files/") -> {
                val f = files[path.removePrefix("/drive/v3/files/")] ?: return@DriveHttp json(404, JSONObject())
                if (query["alt"] != "media") return@DriveHttp bad("read without alt=media")
                DriveHttp.Response(200, f.bytes)
            }
            method == "POST" && path == "/upload/drive/v3/files" -> {
                if (full) return@DriveHttp quota()
                if (query["uploadType"] != "multipart") return@DriveHttp bad("create without multipart")
                val body = request.body!!.toString(Charsets.UTF_8)
                val boundary = request.headers["Content-Type"]!!.substringAfter("boundary=")
                val parts = body.split("--$boundary").map { it.trim() }.filter { it.isNotEmpty() && it != "--" }
                val meta = JSONObject(parts[0].substringAfter("\r\n\r\n").trim())
                if (meta.optJSONArray("parents")?.optString(0) != "appDataFolder") return@DriveHttp bad("created outside appDataFolder")
                val content = parts[1].substringAfter("\r\n\r\n")
                val f = File("f${next++}", meta.getString("name"), content.toByteArray(), ++clock)
                files[f.id] = f
                json(200, meta(f))
            }
            method == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> {
                if (full) return@DriveHttp quota()
                val f = files[path.removePrefix("/upload/drive/v3/files/")] ?: return@DriveHttp json(404, JSONObject())
                f.bytes = request.body!!
                f.modified = ++clock
                json(200, meta(f))
            }
            method == "DELETE" && path.startsWith("/drive/v3/files/") ->
                if (files.remove(path.removePrefix("/drive/v3/files/")) != null) DriveHttp.Response(204, ByteArray(0)) else json(404, JSONObject())
            else -> bad("unknown request ${request.method} $path")
        }
    }

    private fun meta(f: File) = JSONObject().put("id", f.id).put("name", f.name)
        .put("modifiedTime", Instant.ofEpochMilli(f.modified).toString()).put("size", f.bytes.size.toString())

    private fun json(code: Int, o: JSONObject) = DriveHttp.Response(code, o.toString().toByteArray())
    private fun bad(why: String) = json(400, JSONObject().put("error", JSONObject().put("message", why)))
    private fun quota() = json(403, JSONObject().put("error", JSONObject().put("errors", JSONArray().put(JSONObject().put("reason", "storageQuotaExceeded")))))
}

/** 기기 하나의 토큰: 드라이브가 바꾼 토큰을 새로 받으면 따라간다. [connected] 가 거짓이면 계정이 없다. */
class FakeToken(private val drive: FakeDrive) : DriveToken {
    var connected = true
    var revoked = false
    var refreshes = 0
    private var held: String? = null

    override suspend fun get(fresh: Boolean): String? {
        if (!connected) return null
        if (fresh) refreshes++
        if (held == null || fresh) held = if (revoked) "revoked" else drive.validToken
        return held
    }
}
