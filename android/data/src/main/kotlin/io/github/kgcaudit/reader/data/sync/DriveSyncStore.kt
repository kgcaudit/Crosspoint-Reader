package io.github.kgcaudit.reader.data.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/** 드라이브가 준 접근 토큰. [fresh] 면 지금 것을 버리고 새로 받는다(만료). 연결된 계정이 없으면 null. */
fun interface DriveToken {
    suspend fun get(fresh: Boolean): String?
}

/** HTTP 한 번. 시험이 가짜 드라이브를 꽂는 자리다 — 인터넷 없이 요청 모양과 응답 처리를 본다. 막히는 호출이다(IO 스레드에서 부른다). */
fun interface DriveHttp {
    data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray? = null)
    data class Response(val code: Int, val body: ByteArray)

    fun send(request: Request): Response
}

/**
 * 구글 드라이브의 **앱 전용 공간**(appDataFolder)에 기기별 파일을 둔다(0.57.0, 사용자 확정 구상안).
 *
 * - 범위는 `drive.appdata` 하나 — 이 앱이 만든 숨은 파일만 보고 쓴다. 사람의 드라이브 파일은 보지도 바꾸지도 못한다. 구글의 "민감하지
 *   않은" 범위라 앱 심사도 가볍다.
 * - 이 공간은 드라이브 목록에 보이지 않는다 — 사람이 모르고 지우거나 옮겨 깨뜨릴 일이 없다.
 * - 라이브러리(구글 API 클라이언트)를 싣지 않고 REST 를 직접 부른다: 쓰는 것은 목록 · 읽기 · 만들기 · 덮기 · 지우기 다섯뿐이고,
 *   클라이언트 라이브러리는 APK 를 수 MB 키운다.
 */
class DriveSyncStore(private val token: DriveToken, private val http: DriveHttp = UrlConnectionHttp()) : SyncStore {

    override suspend fun list(): List<SyncStore.Entry> {
        val out = ArrayList<SyncStore.Entry>()
        var page: String? = null
        do {
            val url = "$API/files?spaces=appDataFolder&pageSize=1000&fields=" + enc("nextPageToken,files(id,name,modifiedTime,size)") +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val json = json(call("GET", url))
            val files = json.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) entryOf(files.optJSONObject(i))?.let(out::add)
            page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null)
        return out
    }

    override suspend fun read(entry: SyncStore.Entry, maxBytes: Long): String? {
        if (entry.sizeBytes != null && entry.sizeBytes > maxBytes) return null
        val body = call("GET", "$API/files/${enc(entry.id)}?alt=media")
        if (body.size > maxBytes) return null
        return body.toString(Charsets.UTF_8)
    }

    override suspend fun write(name: String, existing: SyncStore.Entry?, text: String): SyncStore.Entry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val fields = "fields=" + enc("id,name,modifiedTime,size")
        val body = if (existing != null) {
            // 내용만 바꾼다. HttpURLConnection 은 PATCH 를 모른다 — 구글 API 가 받는 덮어쓰기 머리로 POST 를 PATCH 로 바꾼다.
            call("POST", "$UPLOAD/files/${enc(existing.id)}?uploadType=media&$fields", mapOf("X-HTTP-Method-Override" to "PATCH", "Content-Type" to JSON_TYPE), bytes)
        } else {
            val boundary = "olo-" + UUID.randomUUID()
            val meta = JSONObject().put("name", name).put("mimeType", "application/json").put("parents", org.json.JSONArray().put("appDataFolder"))
            val multipart = ByteArrayOutputStream().apply {
                write("--$boundary\r\nContent-Type: $JSON_TYPE\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $JSON_TYPE\r\n\r\n".toByteArray(Charsets.UTF_8))
                write(bytes)
                write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }.toByteArray()
            call("POST", "$UPLOAD/files?uploadType=multipart&$fields", mapOf("Content-Type" to "multipart/related; boundary=$boundary"), multipart)
        }
        return entryOf(json(body)) ?: throw SyncStoreException(SyncProblem.Server, "drive returned no file for $name")
    }

    override suspend fun delete(entry: SyncStore.Entry) {
        try {
            call("DELETE", "$API/files/${enc(entry.id)}")
        } catch (e: NotFound) {
            // 다른 기기가 먼저 지웠다 — 바라던 대로다.
        }
    }

    private class NotFound : IOException()

    /**
     * 토큰을 붙여 부른다. 401 이면 토큰이 낡은 것이니 한 번만 새로 받아 다시 — 그래도 401 이면 허락이 취소된 것이다. 응답 몸.
     * 2xx 가 아니면 [SyncStoreException](사람에게 보일 갈래로 나눈다).
     */
    private suspend fun call(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): ByteArray {
        var fresh = false
        while (true) {
            val bearer = token.get(fresh) ?: throw SyncStoreException(SyncProblem.SignedOut, "no google account connected")
            val response = try {
                http.send(DriveHttp.Request(method, url, headers + ("Authorization" to "Bearer $bearer"), body))
            } catch (e: IOException) {
                throw SyncStoreException(SyncProblem.Offline, "drive unreachable", e)
            }
            when {
                response.code in 200..299 -> return response.body
                response.code == 401 && !fresh -> fresh = true
                response.code == 401 -> throw SyncStoreException(SyncProblem.SignedOut, "drive refused the token")
                response.code == 404 -> throw NotFound()
                response.code == 403 && reason(response.body) == "storageQuotaExceeded" ->
                    throw SyncStoreException(SyncProblem.Full, "drive is full")
                // 범위를 거둔 계정(앱 허락 취소)은 403 insufficientPermissions 로 온다 — 다시 연결해야 한다.
                response.code == 403 && reason(response.body) in PERMISSION_REASONS ->
                    throw SyncStoreException(SyncProblem.SignedOut, "drive scope missing")
                else -> throw SyncStoreException(SyncProblem.Server, "drive answered ${response.code}")
            }
        }
    }

    private fun reason(body: ByteArray): String? = try {
        JSONObject(body.toString(Charsets.UTF_8)).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
    } catch (e: JSONException) {
        null
    }

    private fun json(body: ByteArray): JSONObject = try {
        JSONObject(body.toString(Charsets.UTF_8))
    } catch (e: JSONException) {
        throw SyncStoreException(SyncProblem.Server, "drive answered something that is not json", e)
    }

    private fun entryOf(o: JSONObject?): SyncStore.Entry? {
        val id = o?.optString("id")?.takeIf { it.isNotEmpty() } ?: return null
        return SyncStore.Entry(
            id = id,
            name = o.optString("name"),
            modifiedEpochMs = o.optString("modifiedTime").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            // 드라이브는 크기를 글자로 준다("1234").
            sizeBytes = o.optString("size").toLongOrNull(),
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val JSON_TYPE = "application/json; charset=UTF-8"
        val PERMISSION_REASONS = setOf("insufficientPermissions", "appNotAuthorizedToFile", "authError")
    }
}

/** 진짜 인터넷. 시간 제한을 둔다 — 지하철에서 끊긴 연결에 이어 읽기가 끝없이 매달리지 않게. */
class UrlConnectionHttp(private val timeoutMs: Int = 15_000) : DriveHttp {
    override fun send(request: DriveHttp.Request): DriveHttp.Response {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (request.body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            return DriveHttp.Response(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}
