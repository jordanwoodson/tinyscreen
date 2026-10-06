package io.github.jordanwoodson.tinyscreen.chat

import android.content.Context
import android.util.Log
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.net.WebViewCookieJar
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.settings.SettingsRepository
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.Times
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.NumberFormat
import java.util.Locale
import kotlin.random.Random

enum class ConnectionStatus { Connecting, Connected, Disconnected }

/**
 * destiny.gg chat over `wss://chat.destiny.gg/ws`. All state is mutated on a single-threaded
 * dispatcher and exposed as StateFlows for the UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepository(
    context: Context,
    private val appScope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    val assets: ChatAssets,
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(appScope.coroutineContext + dispatcher)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Http.json
    private val settings: AppSettings get() = settingsRepo.settings.value

    val formatter = MessageFormatter(emotes = { assets.emotes.value }, isConnectedUser = { usersMap.containsKey(it) })

    // --- Public state ---
    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()

    private val _me = MutableStateFlow<ChatUser?>(null)
    val me: StateFlow<ChatUser?> = _me.asStateFlow()

    private val _status = MutableStateFlow(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val usersMap = HashMap<String, ChatUser>()
    private val _users = MutableStateFlow<Map<String, ChatUser>>(emptyMap())
    val users: StateFlow<Map<String, ChatUser>> = _users.asStateFlow()
    private val userSeen = HashMap<String, Long>()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private val _pinned = MutableStateFlow<PinnedMessage?>(null)
    val pinned: StateFlow<PinnedMessage?> = _pinned.asStateFlow()

    private val _dismissedPins = MutableStateFlow(prefs.getStringSet("dismissed_pins", emptySet()).orEmpty())
    val dismissedPins: StateFlow<Set<String>> = _dismissedPins.asStateFlow()

    private val _poll = MutableStateFlow<PollState?>(null)
    val poll: StateFlow<PollState?> = _poll.asStateFlow()
    private var lastPoll: PollState? = null
    private var pollHideJob: Job? = null

    private val _events = MutableStateFlow<List<PaidEvent>>(emptyList())
    val events: StateFlow<List<PaidEvent>> = _events.asStateFlow()

    private val _spotlights = MutableStateFlow<Set<String>>(emptySet())
    val spotlights: StateFlow<Set<String>> = _spotlights.asStateFlow()

    private val _muteEndsAt = MutableStateFlow<Long?>(null)
    val muteEndsAt: StateFlow<Long?> = _muteEndsAt.asStateFlow()

    private val _subOnly = MutableStateFlow(0)
    val subOnly: StateFlow<Int> = _subOnly.asStateFlow()

    private val _censors = MutableStateFlow(Censors())

    private val _watchingOnly = MutableStateFlow(false)
    /** "Only show people watching the same stream". */
    val watchingOnly: StateFlow<Boolean> = _watchingOnly.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _unreadWhispers = MutableStateFlow(0)
    val unreadWhispers: StateFlow<Int> = _unreadWhispers.asStateFlow()

    private val _openConversation = MutableStateFlow<String?>(null)
    val openConversation: StateFlow<String?> = _openConversation.asStateFlow()

    private val _conversationMessages = MutableStateFlow<List<WhisperMessage>>(emptyList())
    val conversationMessages: StateFlow<List<WhisperMessage>> = _conversationMessages.asStateFlow()

    /** Text the UI should place in the input box (/r, whisper buttons, saved pre-login text). */
    private val _inputRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val inputRequests: SharedFlow<String> = _inputRequests.asSharedFlow()

    private val _embedRequests = MutableSharedFlow<Embed>(extraBufferCapacity = 4)
    val embedRequests: SharedFlow<Embed> = _embedRequests.asSharedFlow()

    private val _loginRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginRequests: SharedFlow<Unit> = _loginRequests.asSharedFlow()

    /** Incoming whispers and highlights, for notifications. */
    private val _alerts = MutableSharedFlow<ChatAlert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<ChatAlert> = _alerts.asSharedFlow()

    val sentHistory = ArrayDeque<String>()
    private val highlighters = ArrayDeque<String>()
    private var lastWhisperer: String? = null

    private val _currentEmbed = MutableStateFlow<Embed?>(null)

    /** What the bigscreen is currently showing (for /postembed and the watching filter). */
    var currentEmbed: Embed?
        get() = _currentEmbed.value
        set(value) { _currentEmbed.value = value }

    private val filters = combine(settingsRepo.settings, _me) { s, me -> ChatFilter.from(s, me) }.distinctUntilChanged()

    private val watchingNicks: Flow<Set<String>?> = combine(_watchingOnly, _currentEmbed) { on, embed -> embed.takeIf { on } }
        .distinctUntilChanged()
        .flatMapLatest { embed ->
            if (embed == null) {
                flowOf(null)
            } else {
                _users.map { users -> users.values.filter { it.watching?.matches(embed) == true }.mapTo(HashSet()) { it.key } }
            }
        }

    private val rowBuilder = RowBuilder()

    /**
     * The lines as the list shows them, newest first: filtered, with continuation and highlights
     * worked out. Built off the main thread, skipping intermediate states when chat is busy.
     */
    val rows: StateFlow<List<ChatRow>> = combine(_lines, filters, _censors, watchingNicks) { lines, filter, censors, watching ->
        RowInputs(lines, filter, censors, watching)
    }
        .conflate()
        .map { rowBuilder.build(it.lines, it.filter, it.censors, it.watching) }
        .flowOn(Dispatchers.Default)
        .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private class RowInputs(val lines: List<ChatLine>, val filter: ChatFilter, val censors: Censors, val watching: Set<String>?)

    // --- Socket ---
    private var socket: WebSocket? = null
    private var openedAt = 0L
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var wanted = false
    private var stopReconnecting = false
    private var nextKey = 1L
    private val recentHashes = LinkedHashSet<String>()

    /** Whether the list is at the bottom; while the reader is scrolled up, nothing is trimmed. */
    private var atBottom = true

    fun start() {
        scope.launch {
            if (wanted) return@launch
            wanted = true
            assets.loadCached()
            if (assets.emotes.value.byPrefix.isEmpty()) {
                // First run: history is formatted on arrival, so get emotes before connecting
                kotlinx.coroutines.withTimeoutOrNull(6_000) { assets.refresh() }
            } else {
                appScope.launch { assets.refresh() }
            }
            if (wanted) connect()
        }
    }

    fun stop() {
        scope.launch {
            wanted = false
            reconnectJob?.cancel()
            socket?.close(1000, null)
            socket = null
            _status.value = ConnectionStatus.Disconnected
        }
    }

    fun reconnect() {
        scope.launch {
            wanted = true
            stopReconnecting = false
            attempts = 0
            reconnectJob?.cancel()
            val old = socket
            socket = null
            old?.close(1000, null)
            connect()
        }
    }

    private fun connect() {
        if (socket != null) return
        _status.value = ConnectionStatus.Connecting
        info(InfoKind.Status, "Connecting to chat.destiny.gg ...")
        val request = Request.Builder()
            .url(WS_URL)
            .header("Origin", "https://www.destiny.gg")
            .build()
        socket = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (socket !== webSocket) return@launch
                    openedAt = System.currentTimeMillis()
                    _status.value = ConnectionStatus.Connected
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (socket === webSocket) dispatch(text, history = false) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { onDisconnected(webSocket, code) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "chat socket failure: ${t.message} ${response?.code}")
                scope.launch { onDisconnected(webSocket, -1) }
            }
        })
    }

    private fun onDisconnected(webSocket: WebSocket, code: Int) {
        if (socket !== webSocket) return
        socket = null
        _status.value = ConnectionStatus.Disconnected
        endPoll(silent = true)
        if (!wanted) return
        if (stopReconnecting) {
            info(InfoKind.Error, "Disconnected.")
            return
        }
        val healthy = openedAt > 0 && System.currentTimeMillis() - openedAt >= 5_000
        openedAt = 0
        if (healthy) attempts = 0
        val delayMs = if (code == 1001 && healthy) {
            info(InfoKind.Error, "Disconnected.")
            Random.nextLong(0, 1_000)
        } else {
            val ceiling = minOf(60_000L, 3_000L * (1L shl attempts.coerceAtMost(10)))
            val d = 500 + Random.nextLong(0, (ceiling - 500).coerceAtLeast(1))
            info(InfoKind.Error, "Disconnected, retry in ${(d + 999) / 1000} seconds ...")
            d
        }
        attempts++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (wanted && socket == null) connect()
        }
    }

    private fun sendRaw(event: String, payload: JsonElement): Boolean {
        val s = socket
        if (s == null || _status.value != ConnectionStatus.Connected) {
            error("notconnected")
            return false
        }
        return s.send("$event $payload")
    }

    // --- Dispatch ---

    private fun dispatch(text: String, history: Boolean) {
        val space = text.indexOf(' ')
        val event = (if (space < 0) text else text.substring(0, space)).uppercase(Locale.ROOT)
        val payloadText = if (space < 0) "" else text.substring(space + 1)
        val payload: JsonElement = runCatching { json.parseToJsonElement(payloadText) }.getOrElse { JsonPrimitive(payloadText) }
        runCatching { handle(event, payload, payloadText, history) }
            .onFailure { Log.w(TAG, "failed to handle $event", it) }
    }

    private fun handle(event: String, p: JsonElement, rawPayload: String, history: Boolean) {
        val o = p as? JsonObject
        when (event) {
            "PING" -> socket?.send("PONG $rawPayload")
            "PONG" -> Unit
            "ME" -> {
                val user = o?.let { parseUser(it) }
                _me.value = user
                if (user != null) {
                    // Background whisper and mention checks run without a socket, so remember who we are
                    prefs.edit().putString(PREF_NICK, user.nick).apply()
                    upsert(user)
                    appScope.launch { refreshWhispers() }
                    prefs.getString("pre_login_text", null)?.let {
                        prefs.edit().remove("pre_login_text").apply()
                        _inputRequests.tryEmit(it)
                    }
                }
            }
            "HISTORY" -> (p as? JsonArray)?.forEach { line -> (line as? JsonPrimitive)?.contentOrNull?.let { dispatch(it, history = true) } }
            "NAMES" -> {
                usersMap.clear()
                (o?.get("users") as? JsonArray)?.forEach { u -> (u as? JsonObject)?.let { upsert(parseUser(it), publish = false) } }
                publishUsers()
                _connectionCount.value = o?.get("connectioncount").long()?.toInt() ?: 0
                val nick = _me.value?.nick ?: "Guest"
                info(InfoKind.Status, "Connected as $nick. Serving ${fmt(_connectionCount.value)} connections and ${fmt(usersMap.size)} users.")
            }
            "JOIN" -> o?.let {
                val u = parseUser(it)
                upsert(u)
                if (settings.showJoinLeave && !history) info(InfoKind.Info, "${u.nick} joined.")
            }
            "QUIT" -> o?.let {
                val u = parseUser(it)
                userSeen.remove(u.key)
                if (usersMap.remove(u.key) != null) publishUsers()
                if (settings.showJoinLeave && !history) info(InfoKind.Info, "${u.nick} left.")
            }
            "USERSDELTA" -> {
                var changed = false
                (o?.get("users") as? JsonArray)?.forEach { u -> (u as? JsonObject)?.let { if (upsert(parseUser(it), publish = false)) changed = true } }
                (o?.get("removed") as? JsonArray)?.forEach { r ->
                    val nick = (r as? JsonPrimitive)?.contentOrNull ?: (r as? JsonObject)?.get("nick").str()
                    if (nick?.lowercase()?.let { usersMap.remove(it) } != null) changed = true
                }
                if (changed) publishUsers()
            }
            "UPDATEUSER" -> o?.let {
                val u = parseUser(it)
                upsert(u)
                if (u.id != null && u.id == _me.value?.id) _me.value = u
            }
            "MSG" -> o?.let { onMessage(it, history) }
            "PRIVMSG" -> o?.let { onWhisper(it, history) }
            "PRIVMSGSENT" -> info(InfoKind.Info, "Your message has been sent.")
            "MUTE" -> o?.let { onModeration("muted", it, history) }
            "UNMUTE" -> o?.let { onModeration("unmuted", it, history) }
            "BAN" -> o?.let { onModeration("banned", it, history) }
            "UNBAN" -> o?.let { onModeration("unbanned", it, history) }
            "SUBONLY" -> o?.let {
                val on = it["data"].str() == "on"
                val tier = it["tier"].long()?.toInt() ?: 1
                _subOnly.value = if (on) tier else 0
                val by = it["nick"].str()?.let { n -> " by $n" } ?: ""
                info(InfoKind.Command, "Subscriber only mode${if (on && tier > 1) " (Tier $tier+)" else ""} ${if (on) "enabled" else "disabled"}$by.")
            }
            "BROADCAST" -> o?.let {
                val user = (it["user"] as? JsonObject)?.let(::parseUser)?.takeIf { u -> u.id != -1L }
                    ?: it.takeIf { o -> o["nick"] != null && o["nick"].str() != "System" }?.let(::parseUser)
                val text = it["data"].str().orEmpty()
                append(ChatLine.Broadcast(key(), it["timestamp"].long() ?: now(), user, formatter.format(text, null).segments))
            }
            "PIN" -> o?.let {
                val text = it["data"].str().orEmpty()
                _pinned.value = if (text.isEmpty()) {
                    null
                } else {
                    val nick = it["nick"].str().orEmpty()
                    PinnedMessage(it["uuid"].str() ?: text.hashCode().toString(), nick, text, it["timestamp"].long() ?: now(), usersMap[nick.lowercase()] ?: parseUser(it))
                }
            }
            "POLLSTART" -> o?.let { startPoll(it, history) }
            "POLLSTOP" -> endPoll(silent = history)
            "VOTECAST" -> o?.let {
                val poll = _poll.value ?: return@let
                val vote = it["vote"].long()?.toInt() ?: return@let
                val qty = it["quantity"].long() ?: 1
                if (vote !in 1..poll.totals.size) return@let
                val totals = poll.totals.toMutableList().also { t -> t[vote - 1] += qty }
                _poll.value = poll.copy(totals = totals, totalVotes = poll.totalVotes + qty)
            }
            "VOTECOUNTED" -> o?.let {
                val poll = _poll.value ?: return@let
                _poll.value = poll.copy(myVote = it["vote"].long()?.toInt(), canVote = false)
            }
            "DEATH" -> o?.let {
                val user = parseUser(it)
                val ts = it["timestamp"].long() ?: now()
                censor(user.key, ts)
                append(ChatLine.Death(key(), ts, user, it["data"].str().orEmpty()))
                if (user.key == _me.value?.key) startMute(ts, it["duration"].long() ?: 0)
            }
            "SUBSCRIPTION", "GIFTSUB", "MASSGIFT", "DONATION", "XPOST", "SPOTLIGHT" -> o?.let {
                if (event == "SPOTLIGHT") it["key"].str()?.let { k -> _spotlights.value = _spotlights.value + k }
                val ev = parseEvent(event, it) ?: return@let
                if (event != "SPOTLIGHT") append(ChatLine.Event(key(), ev.timestamp, ev))
                if (!history) addEventChip(ev)
            }
            "UNSPOTLIGHT" -> o?.let { it["key"].str()?.let { k -> _spotlights.value = _spotlights.value - k } }
            "SPOTLIGHTS" -> (p as? JsonArray)?.let { arr ->
                _spotlights.value = arr.mapNotNull { (it as? JsonObject)?.get("key").str() }.toSet()
            }
            "PAIDEVENTS" -> (p as? JsonArray)?.let { arr ->
                val parsed = arr.mapNotNull { entry ->
                    val line = (entry as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val sp = line.indexOf(' ')
                    if (sp < 0) return@mapNotNull null
                    val obj = runCatching { json.parseToJsonElement(line.substring(sp + 1)) as? JsonObject }.getOrNull() ?: return@mapNotNull null
                    parseEvent(line.substring(0, sp).uppercase(Locale.ROOT), obj)
                }
                _events.value = parsed.filter { it.expiresAt > now() }.distinctBy { it.uuid }.sortedByDescending { it.timestamp }
                scheduleEventExpiry()
            }
            "ADDPHRASE" -> info(InfoKind.Command, "Phrase \"${o?.get("data").str()}\" added.")
            "REMOVEPHRASE" -> info(InfoKind.Command, "Phrase \"${o?.get("data").str()}\" removed.")
            "REFRESHCHATASSETS" -> appScope.launch { assets.refresh(o?.get("cacheKey").str()) }
            "RELOAD" -> {
                info(InfoKind.Status, "Reload requested by the server.")
                appScope.launch { assets.refresh() }
                scope.launch {
                    delay(Random.nextLong(4_000, 34_000))
                    reconnect()
                }
            }
            "ERR" -> onError(o, p)
            else -> Unit
        }
    }

    // --- Messages ---

    private fun onMessage(o: JsonObject, history: Boolean) {
        val user = parseUser(o)
        upsert(user)
        val text = o["data"].str() ?: return
        val ts = o["timestamp"].long() ?: now()
        val hash = MessageFormatter.hash(ts, user.id, text)
        if (!rememberHash(hash)) return
        val me = _me.value
        val current = _lines.value

        // Our own optimistic line: just confirm it
        if (me != null && user.key == me.key) {
            val idx = current.indexOfLast { it is ChatLine.User && it.pending && it.raw == text }
            if (idx >= 0) {
                val line = current[idx] as ChatLine.User
                _lines.value = current.toMutableList().also { it[idx] = line.copy(pending = false, timestamp = ts, hash = hash, user = user) }
                return
            }
        }

        // Combos: consecutive messages that are exactly one emote
        val textOnly = text.replaceFirst(SLASH_WORD, "").trim()
        val emote = assets.emotes.value.byPrefix[textOnly]
        if (settings.showCombos && emote != null && user.canUse(emote)) {
            when (val last = current.lastOrNull()) {
                is ChatLine.Combo -> if (!last.completed && last.emote.prefix == textOnly) {
                    replaceLast(last.copy(count = last.count + 1, nicks = last.nicks + user.nick))
                    return
                }
                is ChatLine.User -> if (!last.whisper && !last.pending && !last.historical &&
                    last.raw.replaceFirst(SLASH_WORD, "").trim() == textOnly
                ) {
                    replaceLast(ChatLine.Combo(last.key, last.timestamp, emote, 2, completed = false, nicks = listOf(last.user.nick, user.nick)))
                    return
                }
                else -> Unit
            }
        }

        val formatted = formatter.format(text, user)
        val line = ChatLine.User(
            key = key(),
            timestamp = ts,
            user = user,
            raw = text,
            segments = formatted.segments,
            action = formatted.action,
            greentext = formatted.greentext,
            sus = formatted.sus,
            linkTag = formatted.linkTag,
            mentions = formatted.mentions,
            hash = hash,
        )
        append(line)

        val filter = currentFilter()
        if (me != null && user.key != me.key && filter.highlights.mentionsMe(line)) {
            highlighters.remove(user.nick)
            highlighters.addFirst(user.nick)
            while (highlighters.size > 10) highlighters.removeLast()
        }
        // The same rules as the blue highlight in the list, so ignored users and links don't notify
        if (!history && settings.notifyHighlights && !filter.hidesText(line) && filter.highlights.matches(line)) {
            _alerts.tryEmit(ChatAlert.Mention(user.nick, text, ts))
        }
    }

    private var filterSource: Pair<AppSettings, ChatUser?>? = null
    private var filter = ChatFilter()

    /** The list's filter for the current settings, rebuilt only when they change. */
    private fun currentFilter(): ChatFilter {
        val s = settings
        val me = _me.value
        val source = filterSource
        if (source == null || source.first !== s || source.second !== me) {
            filter = ChatFilter.from(s, me)
            filterSource = s to me
        }
        return filter
    }

    private fun onWhisper(o: JsonObject, history: Boolean) {
        val from = o["nick"].str() ?: return
        val text = o["data"].str().orEmpty()
        val ts = o["timestamp"].long() ?: now()
        val id = o["messageid"].long()
        if (from.lowercase() in settings.ignoredUsers.map { it.lowercase() }) return
        lastWhisperer = from
        val open = _openConversation.value
        if (open != null && open.equals(from, ignoreCase = true)) {
            _conversationMessages.value = _conversationMessages.value + WhisperMessage(id, from, _me.value?.nick.orEmpty(), text, ts)
            id?.let { appScope.launch { runCatching { ChatApi.markRead(it) } } }
        } else {
            _unreadWhispers.value = _unreadWhispers.value + 1
            if (!history && settings.notifyWhispers) _alerts.tryEmit(ChatAlert.Whisper(from, text, ts))
        }
        // Bump the conversation to the top
        val existing = _conversations.value.firstOrNull { it.username.equals(from, ignoreCase = true) }
        val updated = Conversation(
            existing?.userId, from, text, ts,
            (existing?.unread ?: 0) + if (open.equals(from, ignoreCase = true)) 0 else 1,
            lastMessageFromMe = false,
        )
        _conversations.value = listOf(updated) + _conversations.value.filterNot { it.username.equals(from, ignoreCase = true) }
        if (settings.whispersInChat) {
            val user = usersMap[from.lowercase()] ?: ChatUser(nick = from)
            val f = formatter.format(text, user)
            append(
                ChatLine.User(key(), ts, user, text, f.segments, f.action, false, false, f.linkTag, f.mentions,
                    MessageFormatter.hash(ts, user.id, text), whisper = true),
            )
        }
    }

    private fun onModeration(kind: String, o: JsonObject, history: Boolean) {
        val moderator = o["nick"].str() ?: "a moderator"
        val target = o["data"].str() ?: return
        val me = _me.value
        val isMe = me != null && target.equals(me.nick, ignoreCase = true)
        val ts = o["timestamp"].long() ?: now()
        when (kind) {
            "muted" -> {
                censor(target.lowercase(), ts)
                if (isMe) {
                    info(InfoKind.Command, "You have been muted by $moderator.")
                    startMute(ts, o["duration"].long() ?: 0)
                } else {
                    info(InfoKind.Command, "$target muted by $moderator.")
                }
            }
            "unmuted" -> if (isMe) {
                _muteEndsAt.value = null
                info(InfoKind.Command, "You have been unmuted by $moderator.")
            } else {
                info(InfoKind.Command, "$target unmuted by $moderator.")
            }
            "banned" -> {
                censor(target.lowercase(), ts)
                if (isMe) {
                    info(InfoKind.Command, "You have been banned by $moderator. Check your profile for more information.")
                    if (!history) runCommand("BANINFO", emptyList(), "")
                } else {
                    info(InfoKind.Command, "$target banned by $moderator.")
                }
            }
            "unbanned" -> if (isMe) {
                _muteEndsAt.value = null
                info(InfoKind.Command, "You have been unbanned by $moderator.")
            } else {
                info(InfoKind.Command, "$target unbanned by $moderator.")
            }
        }
    }

    private fun startMute(timestamp: Long, durationSeconds: Long) {
        val end = timestamp + durationSeconds * 1000
        if (end > now()) _muteEndsAt.value = end
    }

    /** Hides what [nickKey] said up to [at] (server time), like the site does for lines on screen. */
    private fun censor(nickKey: String, at: Long) {
        _censors.value = _censors.value.censor(nickKey, at)
    }

    fun reveal(nickKey: String) {
        scope.launch { _censors.value = _censors.value.reveal(nickKey) }
    }

    fun setWatchingOnly(on: Boolean) {
        _watchingOnly.value = on
    }

    private fun onError(o: JsonObject?, p: JsonElement) {
        val code = o?.get("description").str() ?: (p as? JsonPrimitive)?.contentOrNull ?: "unknown"
        // A rejected send shouldn't leave its optimistic line behind
        if (code in SEND_ERRORS) {
            val current = _lines.value
            val idx = current.indexOfLast { it is ChatLine.User && it.pending }
            if (idx >= 0) _lines.value = current.toMutableList().also { it.removeAt(idx) }
        }
        when (code) {
            "banned" -> {
                stopReconnecting = true
                info(
                    InfoKind.Error,
                    "You have been banned! Check your profile for more information. Subscribing or donating removes non-permanent bans.",
                    link = "https://www.destiny.gg/unban",
                    linkLabel = "Visit this page to appeal.",
                )
            }
            "muted" -> {
                val left = o?.get("muteTimeLeft").long() ?: 0
                _muteEndsAt.value = now() + left * 1000
                info(InfoKind.Error, "You are temporarily muted! You can chat again ${inDuration(left * 1000)}. Subscribe, or donate, to remove the mute immediately.")
            }
            "bannedphrase" -> info(InfoKind.Error, "Your message was blocked because it contained this banned phrase: \"${o?.get("filtered").str().orEmpty()}\".")
            "submode" -> {
                val tier = _subOnly.value
                info(InfoKind.Error, ERRORS.getValue("submode") + if (tier > 1) " (Tier $tier+)" else "")
            }
            "toomanyconnections" -> {
                stopReconnecting = true
                info(InfoKind.Error, ERRORS.getValue(code))
            }
            "needlogin" -> {
                info(InfoKind.Error, ERRORS.getValue(code))
                _loginRequests.tryEmit(Unit)
            }
            else -> info(InfoKind.Error, ERRORS[code] ?: code)
        }
    }

    private fun error(code: String) = info(InfoKind.Error, ERRORS[code] ?: code)

    // --- Polls ---

    private fun startPoll(o: JsonObject, history: Boolean) {
        val options = (o["options"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
        val totals = (o["totals"] as? JsonArray)?.map { it.long() ?: 0 } ?: List(options.size) { 0L }
        val start = Times.parse(o["start"].str())?.toEpochMilli() ?: now()
        val serverNow = Times.parse(o["now"].str())?.toEpochMilli() ?: now()
        val poll = PollState(
            question = o["question"].str().orEmpty(),
            options = options,
            totals = totals,
            totalVotes = o["totalvotes"].long() ?: totals.sum(),
            weighted = (o["weighted"] as? JsonPrimitive)?.booleanOrNull ?: false,
            startedBy = o["nick"].str().orEmpty(),
            startMs = start,
            durationMs = o["time"].long() ?: 30_000,
            serverOffsetMs = serverNow - now(),
            canVote = (o["canvote"] as? JsonPrimitive)?.booleanOrNull ?: true,
            myVote = o["myvote"].long()?.toInt()?.takeIf { it > 0 },
        )
        pollHideJob?.cancel()
        _poll.value = poll
        lastPoll = poll
        if (!history) info(InfoKind.Info, "A poll has been started. Type 1 or 2 in chat to participate.")
    }

    private fun endPoll(silent: Boolean) {
        val poll = _poll.value ?: return
        if (poll.ended) return
        val ended = poll.copy(ended = true, canVote = false)
        _poll.value = ended
        lastPoll = ended
        if (!silent && ended.totalVotes > 0) {
            val winner = ended.totals.indices.maxByOrNull { ended.totals[it] } ?: 0
            val pct = ended.totals[winner] * 100 / ended.totalVotes.coerceAtLeast(1)
            info(InfoKind.Info, "The poll has ended. Option ${winner + 1} won with $pct% of the vote.")
        }
        pollHideJob?.cancel()
        pollHideJob = scope.launch {
            delay(7_000)
            if (_poll.value === ended) _poll.value = null
        }
    }

    fun vote(option: Int) {
        sendRaw("CASTVOTE", buildJsonObject { put("vote", option.toString()) })
    }

    fun dismissPoll() {
        _poll.value = null
    }

    // --- Events ---

    private fun parseEvent(type: String, o: JsonObject): PaidEvent? {
        val user = when (val u = o["user"]) {
            is JsonObject -> parseUser(u)
            is JsonPrimitive -> u.contentOrNull?.let { usersMap[it.lowercase()] ?: ChatUser(nick = it) }
            else -> o["nick"].str()?.let { usersMap[it.lowercase()] ?: ChatUser(nick = it) }
        }
        val name = user?.nick ?: o["handle"].str() ?: "Someone"
        val ts = o["timestamp"].long() ?: now()
        val expires = o["expirationTimestamp"].long() ?: (ts + 60_000)
        val uuid = o["uuid"].str() ?: o["key"].str() ?: "$type$ts$name"
        val tier = o["tier"].long()?.toInt()
        val tierLabel = o["tierLabel"].str() ?: tier?.let { "Tier $it" }
        val message = o["data"].str()?.takeIf { it.isNotBlank() }
        return when (type) {
            "SUBSCRIPTION" -> PaidEvent(uuid, type, user, ts, expires, "$name is now a ${tierLabel ?: "Tier 1"} subscriber",
                o["streak"].long()?.takeIf { it > 1 }?.let { "They're currently on a $it month streak" }, message, tier, null, null, null)
            "GIFTSUB" -> {
                if ((o["fromMassGift"] as? JsonPrimitive)?.booleanOrNull == true) return null
                val recipient = when (val r = o["recipient"]) {
                    is JsonObject -> r["nick"].str()
                    is JsonPrimitive -> r.contentOrNull
                    else -> null
                } ?: "someone"
                PaidEvent(uuid, type, user, ts, expires, "$name gifted $recipient a ${tierLabel ?: "Tier 1"} subscription", null, message, tier, null, null, null)
            }
            "MASSGIFT" -> PaidEvent(uuid, type, user, ts, expires,
                "$name gifted ${o["quantity"].long() ?: 1} ${tierLabel ?: "Tier 1"} subs to the community", null, message, tier, null, null, null)
            "DONATION" -> {
                val cents = o["amount"].long() ?: 0
                PaidEvent(uuid, type, user, ts, expires, "$name donated ${NumberFormat.getCurrencyInstance(Locale.US).format(cents / 100.0)}",
                    null, message, null, cents, o["audio"].str(), null)
            }
            "XPOST" -> PaidEvent(uuid, type, null, ts, expires, "@${o["handle"].str().orEmpty()} posted on X", null,
                o["text"].str(), null, null, null, o["url"].str())
            "SPOTLIGHT" -> {
                val author = (o["user"] as? JsonObject)?.let { parseUser(it) } ?: o["nick"].str()?.let { ChatUser(nick = it) }
                PaidEvent(uuid, type, author, ts, expires, "${author?.nick ?: "Someone"} spotlighted by ${o["spotlightedBy"].str() ?: "a mod"}",
                    null, message, null, null, null, null)
            }
            else -> null
        }
    }

    private fun addEventChip(ev: PaidEvent) {
        if (ev.expiresAt <= now()) return
        if (_events.value.any { it.uuid == ev.uuid }) return
        _events.value = (listOf(ev) + _events.value).sortedByDescending { it.timestamp }
        scheduleEventExpiry()
    }

    private var expiryJob: Job? = null
    private fun scheduleEventExpiry() {
        expiryJob?.cancel()
        val next = _events.value.minOfOrNull { it.expiresAt } ?: return
        expiryJob = scope.launch {
            delay((next - now()).coerceAtLeast(0) + 50)
            _events.value = _events.value.filter { it.expiresAt > now() }
            scheduleEventExpiry()
        }
    }

    fun removeEvent(uuid: String) {
        sendRaw("REMOVEEVENT", buildJsonObject { put("data", uuid) })
    }

    // --- Lines ---

    private fun key() = nextKey++
    private fun now() = System.currentTimeMillis()

    private fun rememberHash(hash: String): Boolean {
        if (!recentHashes.add(hash)) return false
        if (recentHashes.size > 1_000) recentHashes.iterator().let { it.next(); it.remove() }
        return true
    }

    private fun append(line: ChatLine) {
        val current = _lines.value
        val next = ArrayList<ChatLine>(current.size + 1)
        next.addAll(current)
        // Any new line completes a running combo
        val last = next.lastOrNull()
        if (last is ChatLine.Combo && !last.completed) next[next.lastIndex] = last.copy(completed = true)
        next.add(line)
        val cap = lineCap()
        if (next.size > cap) next.subList(0, next.size - cap).clear()
        _lines.value = next
    }

    private fun lineCap(): Int {
        val cap = settings.chatLines.coerceIn(50, AppSettings.CHAT_LINES_UNPINNED)
        return if (atBottom) cap else AppSettings.CHAT_LINES_UNPINNED
    }

    /** The UI reports whether it's following the newest line; scrolled up, chat stops trimming. */
    fun setPinned(atBottom: Boolean) {
        scope.launch {
            if (this@ChatRepository.atBottom == atBottom) return@launch
            this@ChatRepository.atBottom = atBottom
            val current = _lines.value
            val cap = lineCap()
            if (current.size > cap) _lines.value = current.subList(current.size - cap, current.size).toList()
        }
    }

    private fun replaceLast(line: ChatLine) {
        val current = _lines.value
        if (current.isEmpty()) return
        _lines.value = current.toMutableList().also { it[it.lastIndex] = line }
    }

    private fun info(kind: InfoKind, text: String, link: String? = null, linkLabel: String? = null) {
        append(ChatLine.Info(key(), now(), kind, text, link, linkLabel))
    }

    fun postInfo(text: String) = scope.launch { info(InfoKind.Info, text) }

    // --- Users ---

    private fun parseUser(o: JsonObject): ChatUser = runCatching { json.decodeFromJsonElement<ChatUser>(o) }
        .getOrElse { ChatUser(nick = o["nick"].str().orEmpty()) }

    /** Returns whether anything about the user changed. */
    private fun upsert(user: ChatUser, publish: Boolean = true): Boolean {
        if (user.nick.isBlank()) return false
        val old = usersMap.put(user.key, user)
        userSeen[user.key] = now()
        // Every MSG carries its author: only republish the user list when someone actually changed
        val changed = old != user
        if (publish && changed) publishUsers()
        return changed
    }

    private var publishPending = false
    private fun publishUsers() {
        // NAMES/JOIN/QUIT churn: publish at most ~4 times a second
        if (publishPending) return
        publishPending = true
        scope.launch {
            delay(250)
            publishPending = false
            _users.value = HashMap(usersMap)
        }
    }

    fun lastSeen(nickKey: String): Long = userSeen[nickKey] ?: 0
    fun recentHighlighters(): List<String> = highlighters.toList()

    // --- Whispers ---

    suspend fun refreshWhispers() {
        if (_me.value == null) return
        ChatApi.unreadCount()?.let { _unreadWhispers.value = it }
        runCatching { ChatApi.conversations() }.onSuccess { _conversations.value = it }
    }

    suspend fun searchConversations(query: String) {
        runCatching { ChatApi.conversations(query) }.onSuccess { _conversations.value = it }
    }

    fun openWhisper(nick: String?) {
        _openConversation.value = nick
        _conversationMessages.value = emptyList()
        if (nick == null) return
        appScope.launch {
            runCatching { ChatApi.inbox(nick) }.onSuccess { msgs ->
                _conversationMessages.value = msgs
                val read = _conversations.value.firstOrNull { it.username.equals(nick, ignoreCase = true) }?.unread ?: 0
                _conversations.value = _conversations.value.map { if (it.username.equals(nick, ignoreCase = true)) it.copy(unread = 0) else it }
                _unreadWhispers.value = (_unreadWhispers.value - read).coerceAtLeast(0)
            }
        }
    }

    fun sendWhisper(nick: String, text: String) {
        val msg = text.trim()
        if (msg.isEmpty()) return
        scope.launch {
            if (!validNick(nick)) return@launch info(InfoKind.Error, "Invalid nick - /msg nick message")
            if (nick.equals(_me.value?.nick, ignoreCase = true)) return@launch info(InfoKind.Error, "Cannot send a message to yourself")
            if (sendRaw("PRIVMSG", buildJsonObject { put("nick", nick); put("data", msg) })) {
                if (_openConversation.value.equals(nick, ignoreCase = true)) {
                    _conversationMessages.value = _conversationMessages.value + WhisperMessage(null, _me.value?.nick.orEmpty(), nick, msg, now())
                }
                val existing = _conversations.value.firstOrNull { it.username.equals(nick, ignoreCase = true) }
                _conversations.value = listOf(Conversation(existing?.userId, existing?.username ?: nick, msg, now(), 0, true)) +
                    _conversations.value.filterNot { it.username.equals(nick, ignoreCase = true) }
            }
        }
    }

    // --- Sending ---

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        scope.launch {
            sentHistory.remove(text)
            sentHistory.addFirst(text)
            while (sentHistory.size > 20) sentHistory.removeLast()

            val cmd = COMMAND.find(text)
            if (cmd != null && !text.startsWith("/me ", ignoreCase = true)) {
                val name = cmd.groupValues[1].uppercase(Locale.ROOT)
                val rest = text.substring(cmd.range.last + 1)
                runCommand(name, rest.split(' ').filter { it.isNotEmpty() }, rest)
                return@launch
            }
            val me = _me.value
            if (me == null) {
                prefs.edit().putString("pre_login_text", text).apply()
                info(InfoKind.Info, "Want to chat? You need to be signed in. Become part of the community and have your say!")
                _loginRequests.tryEmit(Unit)
                return@launch
            }
            val poll = _poll.value
            val n = text.toIntOrNull()
            if (poll != null && !poll.ended && n != null && n in 1..poll.options.size) {
                if (!poll.canVote) return@launch info(InfoKind.Error, "You have already voted!")
                if (sendRaw("CASTVOTE", buildJsonObject { put("vote", n.toString()) })) info(InfoKind.Info, "Your vote has been cast!")
                return@launch
            }
            sendMessage(text, me)
        }
    }

    private fun sendMessage(text: String, me: ChatUser) {
        if (text.length > 512) return error("msgtoolong")
        val singleEmote = assets.emotes.value.byPrefix[text]?.let { me.canUse(it) } == true
        if (!sendRaw("MSG", buildJsonObject { put("data", text) })) return
        if (!singleEmote) {
            val f = formatter.format(text, me)
            append(ChatLine.User(key(), now(), me, text, f.segments, f.action, f.greentext, f.sus, f.linkTag, f.mentions, hash = "", pending = true))
        }
    }

    // --- Commands ---

    private fun runCommand(name: String, args: List<String>, rest: String) {
        val me = _me.value
        val mod = me?.isModerator == true
        when (name) {
            "HELP" -> {
                info(InfoKind.Info, "Available commands: " + USER_COMMANDS.joinToString(" ") { "/$it" })
                if (mod) info(InfoKind.Info, "Moderator commands: " + MOD_COMMANDS.joinToString(" ") { "/$it" })
            }
            "EMOTES" -> info(InfoKind.Info, "Available emotes: " + assets.emotes.value.byPrefix.values
                .filter { me?.canUse(it) ?: (it.minimumSubTier == 0) }.joinToString(", ") { it.prefix } + ".")
            "HINT" -> info(InfoKind.Info, HINTS.random())
            "IGNORE" -> {
                if (args.isEmpty()) {
                    val list = settings.ignoredUsers
                    return info(InfoKind.Info, if (list.isEmpty()) "Your ignore list is empty" else "Ignoring the following people: ${list.sorted().joinToString(", ")}")
                }
                if (!args.all(::validNick)) return info(InfoKind.Error, "Invalid nick - /ignore <nick>")
                val nicks = args.filterNot { it.equals(me?.nick, ignoreCase = true) || usersMap[it.lowercase()]?.isBot == true }
                updateSettings { it.copy(ignoredUsers = it.ignoredUsers + nicks) }
                info(InfoKind.Command, "Ignoring ${nicks.joinToString(", ")}")
            }
            "UNIGNORE" -> {
                if (args.isEmpty()) return info(InfoKind.Error, "Usage: /unignore <nick>")
                updateSettings { s -> s.copy(ignoredUsers = s.ignoredUsers.filterNot { n -> args.any { it.equals(n, ignoreCase = true) } }.toSet()) }
                info(InfoKind.Command, "${args.joinToString(", ")} has been removed from your ignore list")
            }
            "UNIGNOREALL" -> if (args.firstOrNull()?.lowercase() in setOf("yes", "y")) {
                updateSettings { it.copy(ignoredUsers = emptySet()) }
                info(InfoKind.Command, "Your ignore list has been cleared.")
            } else {
                info(InfoKind.Info, "Type /unignoreall yes to clear your ignore list.")
            }
            "HIGHLIGHT" -> {
                val nick = args.firstOrNull()
                if (nick == null) {
                    val list = settings.highlightNicks
                    return info(InfoKind.Info, if (list.isEmpty()) "Your highlight list is empty" else "Highlighting: ${list.sorted().joinToString(", ")}")
                }
                updateSettings { it.copy(highlightNicks = it.highlightNicks + nick) }
                info(InfoKind.Command, "Highlighting $nick")
            }
            "UNHIGHLIGHT" -> args.firstOrNull()?.let { nick ->
                updateSettings { s -> s.copy(highlightNicks = s.highlightNicks.filterNot { it.equals(nick, ignoreCase = true) }.toSet()) }
                info(InfoKind.Command, "No longer highlighting $nick")
            }
            "TAG" -> {
                val nick = args.getOrNull(0) ?: return info(InfoKind.Info, "Tagged: " + settings.taggedUsers.joinToString(", ").ifEmpty { "nobody" })
                if (!validNick(nick)) return info(InfoKind.Error, "Invalid nick - /tag <nick> <color>")
                if (nick.equals(me?.nick, ignoreCase = true)) return info(InfoKind.Error, "Cannot tag yourself")
                val color = args.getOrNull(1)?.lowercase()?.takeIf { it in TAG_COLORS } ?: TAG_COLORS.keys.random()
                updateSettings { s -> s.copy(taggedUsers = s.taggedUsers.filterNot { it.substringBefore('=').equals(nick, ignoreCase = true) }.toSet() + "${nick.lowercase()}=$color") }
                info(InfoKind.Command, "Tagged $nick as $color")
            }
            "UNTAG" -> args.firstOrNull()?.let { nick ->
                updateSettings { s -> s.copy(taggedUsers = s.taggedUsers.filterNot { it.substringBefore('=').equals(nick, ignoreCase = true) }.toSet()) }
                info(InfoKind.Command, "Un-tagged $nick")
            }
            "UNTAGALL" -> if (args.firstOrNull()?.lowercase() in setOf("yes", "y")) updateSettings { it.copy(taggedUsers = emptySet()) }
            "W", "MSG", "MESSAGE", "WHISPER", "TELL", "T", "NOTIFY" -> {
                val nick = args.getOrNull(0)
                val msg = rest.trim().substringAfter(' ', "").trim()
                if (nick == null || msg.isEmpty()) return info(InfoKind.Error, "Invalid nick - /msg nick message")
                sendWhisper(nick, msg)
            }
            "R", "REPLY" -> {
                val target = lastWhisperer ?: (_lines.value.lastOrNull { it is ChatLine.User && it.user.key != me?.key } as? ChatLine.User)?.user?.nick
                if (target == null) info(InfoKind.Info, "No one to reply to. :(") else _inputRequests.tryEmit("/w $target ")
            }
            "OPEN", "O" -> args.firstOrNull()?.let { openWhisper(it) }
            "EXIT" -> openWhisper(null)
            "EMBED", "E" -> args.firstOrNull()?.let { link ->
                val embed = io.github.jordanwoodson.tinyscreen.stream.LinkParser.parse(link)
                if (embed == null) info(InfoKind.Error, "Invalid link") else _embedRequests.tryEmit(embed)
            }
            "POSTEMBED", "PE" -> {
                val link = args.firstOrNull()
                val embed = link?.let { io.github.jordanwoodson.tinyscreen.stream.LinkParser.parse(it) }
                val msgText = if (embed != null) rest.trim().substringAfter(' ', "") else rest.trim()
                val target = embed ?: currentEmbed ?: return info(InfoKind.Error, "Nothing embedded")
                me?.let { sendMessage("${target.hash} $msgText".trim(), it) }
            }
            "POLL", "VOTE", "V", "SPOLL", "SVOTE" -> startPollCommand(rest, weighted = name.startsWith("S"))
            "POLLSTOP", "VOTESTOP", "VS" -> sendRaw("STOPPOLL", JsonObject(emptyMap()))
            "SHOWPOLL", "SHOWVOTE" -> lastPoll?.let { _poll.value = it } ?: info(InfoKind.Info, "There hasn't been a poll yet.")
            "DIE", "SUICIDE", "BITLY" -> sendRaw("DIE", buildJsonObject { put("data", "") })
            "MUTE" -> {
                val nick = args.getOrNull(0) ?: return info(InfoKind.Error, "Usage: /mute <nick> [<time>]")
                sendRaw("MUTE", buildJsonObject {
                    put("data", nick)
                    args.getOrNull(1)?.let { parseDurationNanos(it) }?.let { put("duration", it) }
                })
            }
            "UNMUTE" -> args.firstOrNull()?.let { sendRaw("UNMUTE", buildJsonObject { put("data", it) }) }
            "UNBAN" -> args.firstOrNull()?.let { sendRaw("UNBAN", buildJsonObject { put("data", it) }) }
            "BAN", "IPBAN" -> {
                if (args.size < 3) return info(InfoKind.Error, "Usage: /ban <nick> <time> <reason> (time can be 'permanent').")
                val reason = args.drop(2).joinToString(" ")
                sendRaw("BAN", buildJsonObject {
                    put("nick", args[0])
                    put("reason", reason)
                    if (args[1].startsWith("perm", ignoreCase = true)) put("ispermanent", true) else parseDurationNanos(args[1])?.let { put("duration", it) }
                    put("banip", name == "IPBAN")
                })
            }
            "SUBONLY" -> {
                val a = args.firstOrNull()?.lowercase() ?: return info(InfoKind.Error, "Usage: /subonly on|off|1-5")
                sendRaw("SUBONLY", buildJsonObject {
                    when {
                        a == "off" -> put("data", "off")
                        a == "on" -> { put("data", "on"); put("tier", 1) }
                        a.toIntOrNull() in 1..5 -> { put("data", "on"); put("tier", a.toInt()) }
                        else -> return info(InfoKind.Error, "Usage: /subonly on|off|1-5")
                    }
                })
            }
            "BROADCAST" -> sendRaw("BROADCAST", buildJsonObject { put("data", rest.trim()) })
            "RELOADUSERS" -> sendRaw("RELOADUSERS", JsonObject(emptyMap()))
            "PIN", "MOTD" -> sendRaw("PIN", buildJsonObject { put("data", rest.trim()) })
            "UNPIN", "UNMOTD" -> sendRaw("PIN", buildJsonObject { put("data", "") })
            "ADDPHRASE", "ADDBAN", "ADDMUTE" -> sendRaw("ADDPHRASE", buildJsonObject { put("data", rest.trim()) })
            "REMOVEPHRASE", "REMOVEBAN", "REMOVEMUTE", "DELETEPHRASE", "DELETEBAN", "DELETEMUTE", "DPHRASE", "DBAN", "DMUTE" ->
                sendRaw("REMOVEPHRASE", buildJsonObject { put("data", rest.trim()) })
            "STALK", "S" -> {
                var nick = args.getOrNull(0) ?: me?.nick ?: return
                var limit = args.getOrNull(1)?.toIntOrNull() ?: 3
                nick.toIntOrNull()?.let { limit = it; nick = me?.nick ?: return }
                appScope.launch {
                    val lines = runCatching { ChatApi.stalk(nick, limit) }.getOrElse { return@launch postInfo("Stalk failed: ${it.message}").let { } }
                    withContext(dispatcher) {
                        if (lines.isEmpty()) return@withContext info(InfoKind.Info, "No messages for $nick")
                        info(InfoKind.Info, "Stalked $nick last seen ${java.text.DateFormat.getDateTimeInstance().format(lines.first().timestamp)}.")
                        lines.reversed().forEach { historical(it) }
                    }
                }
            }
            "MENTIONS", "M" -> {
                val nick = args.getOrNull(0)?.takeIf { it.toIntOrNull() == null } ?: me?.nick ?: return
                val limit = args.mapNotNull { it.toIntOrNull() }.firstOrNull() ?: 3
                appScope.launch {
                    val lines = runCatching { ChatApi.mentions(nick, limit) }.getOrElse { return@launch }
                    withContext(dispatcher) {
                        if (lines.isEmpty()) return@withContext info(InfoKind.Info, "No mentions for $nick")
                        info(InfoKind.Info, "Mentions for $nick last seen ${java.text.DateFormat.getDateTimeInstance().format(lines.first().timestamp)}.")
                        lines.reversed().forEach { historical(it) }
                    }
                }
            }
            "BANINFO" -> appScope.launch {
                val ban = runCatching { ChatApi.banInfo() }.getOrElse { return@launch }
                withContext(dispatcher) {
                    if (ban == null) return@withContext info(InfoKind.Info, "You have no active bans. Thank you.")
                    val by = ban["username"].str() ?: "a moderator"
                    val start = ban["starttimestamp"].str()
                    val end = ban["endtimestamp"].str()
                    info(InfoKind.Info, if (end == null) "Permanent ban by $by started on $start." else "Temporary ban by $by started on $start and ending by $end.")
                    ban["reason"].str()?.let { info(InfoKind.Historical, it) }
                    info(InfoKind.Info, "End of ban information.")
                }
            }
            "HOST" -> {
                var display: String? = null
                var title: String? = null
                var url: String? = null
                var i = 0
                while (i < args.size) {
                    when (args[i]) {
                        "--display-name" -> { display = args.getOrNull(i + 1); i++ }
                        "--title" -> { title = args.getOrNull(i + 1); i++ }
                        else -> url = args[i]
                    }
                    i++
                }
                val u = url ?: return info(InfoKind.Error, "Usage: /host [--display-name X] [--title Y] url")
                appScope.launch { runCatching { ChatApi.host(u, display, title) }.onFailure { postInfo("Host failed: ${it.message}") } }
            }
            "UNHOST" -> appScope.launch { runCatching { ChatApi.unhost() } }
            else -> info(InfoKind.Error, "Unknown command. Try /help")
        }
    }

    private fun historical(l: LoggedLine) {
        val user = usersMap[l.nick.lowercase()] ?: ChatUser(nick = l.nick)
        val f = formatter.format(l.text, user)
        append(ChatLine.User(key(), l.timestamp, user, l.text, f.segments, f.action, f.greentext, f.sus, f.linkTag, f.mentions, "", historical = true))
    }

    private fun startPollCommand(text: String, weighted: Boolean) {
        val usage = "Usage: /poll <question>? <option 1> or <option 2> [or <option 3> [or <option 4> ... [or <option n>]]] [<time>]."
        var body = text.trim()
        if (!body.contains('?')) return info(InfoKind.Error, usage)
        var timeMs = 30_000L
        POLL_TIME.find(body)?.let { m ->
            val v = m.groupValues[1]
            val n = v.dropLast(1).toLong()
            timeMs = if (v.endsWith("m", ignoreCase = true)) n * 60_000 else n * 1000
            body = body.substring(0, m.range.first).trim()
        }
        timeMs = timeMs.coerceIn(5_000, 600_000)
        val question = body.substringBefore('?').trim() + "?"
        val opts = body.substringAfter('?').split(POLL_OR).map { it.trim() }.filter { it.isNotEmpty() }
        val options = opts.ifEmpty { listOf("Yes", "No") }
        if (POLL_OPEN_QUESTION.containsMatchIn(question) && options.size < 2) {
            return info(InfoKind.Error, usage)
        }
        sendRaw("STARTPOLL", buildJsonObject {
            put("weighted", weighted)
            put("time", timeMs)
            put("question", question)
            putJsonArray("options") { options.forEach { add(JsonPrimitive(it)) } }
        })
    }

    private fun updateSettings(block: (AppSettings) -> AppSettings) = settingsRepo.launchUpdate(appScope, block)

    // --- Account ---

    /** Checks the session after a WebView login. */
    suspend fun refreshMe(): Boolean {
        val user = ChatApi.me()
        if (user != null) return true
        val cookies = WebViewCookieJar.cookieHeader("https://www.destiny.gg").orEmpty()
        return cookies.contains("sid=") || cookies.contains("rememberme=")
    }

    fun logout() {
        appScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { Http.getString("https://www.destiny.gg/logout") }
            }
            WebViewCookieJar.clearAll {
                _me.value = null
                _conversations.value = emptyList()
                _unreadWhispers.value = 0
                reconnect()
            }
        }
    }

    fun dismissPin(uuid: String) {
        _dismissedPins.value = _dismissedPins.value + uuid
        prefs.edit().putStringSet("dismissed_pins", _dismissedPins.value).apply()
    }

    fun showPin(uuid: String) {
        _dismissedPins.value = _dismissedPins.value - uuid
        prefs.edit().putStringSet("dismissed_pins", _dismissedPins.value).apply()
    }

    // --- Helpers ---

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    private fun fmt(n: Int) = NumberFormat.getIntegerInstance().format(n)

    companion object {
        private const val TAG = "Chat"
        const val WS_URL = "wss://chat.destiny.gg/ws"
        const val PREFS = "chat"
        const val PREF_NICK = "last_nick"
        private val SLASH_WORD = Regex("^/\\w+ ")
        private val COMMAND = Regex("^/([a-z0-9]+)\\s?", RegexOption.IGNORE_CASE)
        private val NICK = Regex("^\\w{3,20}$")
        private val DURATION = Regex("^(\\d+)(s(?:econds?)?|m(?:inutes?)?|h(?:ours?)?|d(?:ays?)?)?$", RegexOption.IGNORE_CASE)
        private val POLL_TIME = Regex("\\b([0-9]+(?:m|s))$", RegexOption.IGNORE_CASE)
        private val POLL_OR = Regex("\\bor\\b", RegexOption.IGNORE_CASE)
        private val POLL_OPEN_QUESTION = Regex("^(how|why|when|what|where)\\b", RegexOption.IGNORE_CASE)

        fun validNick(nick: String) = NICK.matches(nick)

        /** "10m" → nanoseconds, default unit seconds. */
        fun parseDurationNanos(value: String): Long? {
            val m = DURATION.find(value) ?: return null
            val n = m.groupValues[1].toLong()
            val ms = when (m.groupValues[2].firstOrNull()?.lowercaseChar()) {
                'm' -> n * 60_000
                'h' -> n * 3_600_000
                'd' -> n * 86_400_000
                else -> n * 1000
            }
            return ms * 1_000_000
        }

        fun inDuration(ms: Long): String {
            val minutes = (ms + 59_999) / 60_000
            return when {
                ms < 60_000 -> "in a few seconds"
                minutes < 60 -> "in $minutes minute${if (minutes == 1L) "" else "s"}"
                else -> "in ${(minutes + 59) / 60} hours"
            }
        }

        val TAG_COLORS = linkedMapOf(
            "green" to 0xFF61BD4F, "yellow" to 0xFFF2D600, "orange" to 0xFFFFAB4A, "red" to 0xFFEB5A46,
            "purple" to 0xFFC377E0, "blue" to 0xFF0079BF, "sky" to 0xFF00C2E0, "lime" to 0xFF51E898,
            "pink" to 0xFFFF80CE, "black" to 0xFF4D4D4D,
        )

        val USER_COMMANDS = listOf(
            "help", "emotes", "me", "w", "r", "ignore", "unignore", "unignoreall", "highlight", "unhighlight",
            "tag", "untag", "untagall", "stalk", "mentions", "baninfo", "embed", "postembed", "showpoll", "open", "exit", "die",
        )
        val MOD_COMMANDS = listOf(
            "mute", "unmute", "ban", "ipban", "unban", "subonly", "broadcast", "pin", "unpin", "poll", "spoll",
            "pollstop", "addphrase", "removephrase", "reloadusers", "host", "unhost",
        )

        private val SEND_ERRORS = setOf(
            "throttled", "duplicate", "muted", "submode", "msgtoolong", "needlogin", "nochatting", "bannedphrase",
            "invalidmsg", "msgempty", "banned", "nopermission",
        )

        val ERRORS = mapOf(
            "unknown" to "Unknown error, this usually indicates an internal problem :(",
            "nopermission" to "You do not have the required permissions to use that",
            "protocolerror" to "Invalid or badly formatted",
            "needlogin" to "You have to be logged in to use that",
            "msgempty" to "Message cannot be empty",
            "msgtoolong" to "Message cannot be longer than 512 characters",
            "invalidmsg" to "The message was invalid",
            "throttled" to "Throttled! You were trying to send messages too fast",
            "duplicate" to "The message is identical to the last one you sent",
            "submode" to "The channel is currently in subscriber only mode",
            "needbanreason" to "Providing a reason for the ban is mandatory",
            "privmsgbanned" to "Cannot send private messages while banned",
            "requiresocket" to "This chat requires WebSockets",
            "toomanyconnections" to "Only 5 concurrent connections allowed",
            "socketerror" to "Error contacting server",
            "privmsgaccounttooyoung" to "Your account is too recent to send private messages",
            "notfound" to "The user was not found",
            "notconnected" to "You have to be connected to use that",
            "activepoll" to "Poll already started.",
            "noactivepoll" to "No poll started.",
            "alreadyvoted" to "You have already voted!",
            "nochatting" to "You aren't allowed to chat. Either you haven't picked a username, or a mod disabled your privileges.",
            "duplicatephrase" to "Banned phrase already exists.",
            "nophrase" to "Banned phrase doesn't exist.",
            "invalidphrase" to "Invalid phrase provided. Either the phrase was empty, or its regex was invalid.",
            "cantbanprotected" to "Protected users can't die.",
        )

        private val HINTS = listOf(
            "Tap a username to see their info, whisper, or ignore them.",
            "Long-press an emote to see its name and creator.",
            "Type /help to see all commands.",
            "Use /tag nick colour to colour-code someone.",
            "Tap the chat bubble in landscape to hide or show chat.",
            "Links like #kick/channel switch the player when tapped.",
        )
    }
}
