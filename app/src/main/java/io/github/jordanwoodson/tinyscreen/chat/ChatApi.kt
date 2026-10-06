package io.github.jordanwoodson.tinyscreen.chat

import android.net.Uri
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.net.HttpException
import io.github.jordanwoodson.tinyscreen.stream.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class UserInfo(
    val user: ChatUser,
    val bio: String?,
    val gender: String?,
    val age: String?,
)

data class LoggedLine(val nick: String, val text: String, val timestamp: Long)

/** destiny.gg HTTP endpoints used by chat (all cookie-authenticated). */
object ChatApi {
    private const val BASE = "https://www.destiny.gg"
    private val json = Http.json

    private suspend fun get(path: String): JsonElement = json.parseToJsonElement(Http.getString(BASE + path))

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    suspend fun me(): ChatUser? = runCatching {
        val el = get("/api/chat/me").obj() ?: return null
        val nick = el["nick"].str() ?: el["username"].str() ?: return null
        ChatUser(
            id = el["userId"].long() ?: el["id"].long(),
            nick = nick,
            roles = (el["roles"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
            features = (el["features"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
            createdDate = el["createdDate"].str(),
        )
    }.getOrNull()

    /** Null when it couldn't be fetched, so callers keep what they had rather than showing 0. */
    suspend fun unreadCount(): Int? = runCatching {
        get("/api/messages/unread-count").obj()?.get("data").obj()?.get("count").long()?.toInt() ?: 0
    }.getOrNull()

    suspend fun conversations(search: String? = null): List<Conversation> {
        val q = search?.takeIf { it.isNotBlank() }?.let { "?search=" + Uri.encode(it) } ?: ""
        val results = get("/api/messages/conversations$q").obj()?.get("data").obj()?.get("results") as? JsonArray ?: return emptyList()
        return results.mapNotNull { r ->
            val o = r.obj() ?: return@mapNotNull null
            Conversation(
                userId = o["userId"].long(),
                username = o["username"].str() ?: return@mapNotNull null,
                lastMessage = o["lastMessage"].str().orEmpty(),
                timestamp = parseTime(o["timestamp"]),
                unread = o["unread"].long()?.toInt() ?: 0,
                lastMessageFromMe = (o["lastMessageFromMe"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    /** Oldest first. Fetching marks the conversation read. */
    suspend fun inbox(nick: String): List<WhisperMessage> {
        val arr = get("/api/messages/usr/${Uri.encode(nick)}/inbox") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { m ->
            val o = m.obj() ?: return@mapNotNull null
            WhisperMessage(
                id = o["id"].long(),
                from = o["from"].str() ?: o["fromUsername"].str() ?: "",
                to = o["to"].str() ?: o["targetUsername"].str() ?: "",
                text = o["message"].str().orEmpty(),
                timestamp = parseTime(o["timestamp"]),
            )
        }.reversed()
    }

    suspend fun markRead(messageId: Long) {
        post("/api/messages/msg/$messageId/open", "{}")
    }

    suspend fun stalk(nick: String, limit: Int): List<LoggedLine> {
        val data = get("/api/chat/stalk?username=${Uri.encode(nick)}&limit=$limit").obj()?.get("data").obj() ?: return emptyList()
        val who = data["nick"].str() ?: nick
        return (data["lines"] as? JsonArray).orEmpty().mapNotNull { l ->
            val o = l.obj() ?: return@mapNotNull null
            LoggedLine(who, o["text"].str().orEmpty(), (o["timestamp"].long() ?: 0) * 1000)
        }
    }

    suspend fun mentions(nick: String, limit: Int): List<LoggedLine> {
        val arr = get("/api/chat/mentions?username=${Uri.encode(nick)}&limit=$limit").obj()?.get("data") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { l ->
            val o = l.obj() ?: return@mapNotNull null
            LoggedLine(o["nick"].str().orEmpty(), o["text"].str().orEmpty(), (o["date"].long() ?: 0) * 1000)
        }
    }

    /** Null when there's no active ban. */
    suspend fun banInfo(): JsonObject? {
        val el = get("/api/chat/me/ban")
        return el as? JsonObject
    }

    suspend fun userInfo(nick: String): UserInfo? {
        val el = try {
            get("/api/chat/userinfo?username=${Uri.encode(nick)}")
        } catch (e: HttpException) {
            if (e.code == 404) return null else throw e
        }
        val data = el.obj()?.get("data").obj() ?: return null
        val user = runCatching { json.decodeFromJsonElement<ChatUser>(data) }.getOrNull() ?: return null
        return UserInfo(user, data["bio"].str(), data["gender"].str(), data["age"].str())
    }

    suspend fun rustleSearch(nick: String): List<LoggedLine> {
        val body = Http.getString("https://api-v2.rustlesearch.dev/anon/search?username=${Uri.encode(nick)}&channel=Destinygg")
        val msgs = json.parseToJsonElement(body).obj()?.get("data").obj()?.get("messages") as? JsonArray ?: return emptyList()
        return msgs.take(50).mapNotNull { m ->
            val o = m.obj() ?: return@mapNotNull null
            LoggedLine(o["username"].str() ?: nick, o["text"].str().orEmpty(), Times.parse(o["ts"].str())?.toEpochMilli() ?: 0)
        }
    }

    suspend fun host(url: String, displayName: String?, title: String?) {
        val body = buildString {
            append("{\"url\":").append(JsonPrimitive(url))
            displayName?.let { append(",\"displayName\":").append(JsonPrimitive(it)) }
            title?.let { append(",\"title\":").append(JsonPrimitive(it)) }
            append("}")
        }
        post("/api/stream/host", body)
    }

    suspend fun unhost() = post("/api/stream/unhost", "{}")

    private suspend fun post(path: String, jsonBody: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(BASE + path)
            .header("X-CSRF-Guard", "YEE")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { if (!it.isSuccessful) throw HttpException(it.code, path) }
    }

    private fun parseTime(el: JsonElement?): Long {
        val p = el as? JsonPrimitive ?: return 0
        p.longOrNull?.let { return if (it < 10_000_000_000L) it * 1000 else it }
        return Times.parse(p.contentOrNull)?.toEpochMilli() ?: 0
    }

}
