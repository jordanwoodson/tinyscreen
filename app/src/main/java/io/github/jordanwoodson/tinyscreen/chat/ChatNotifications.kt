package io.github.jordanwoodson.tinyscreen.chat

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.net.WebViewCookieJar
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Whisper and mention notifications while you're away. While the chat socket is up they come
 * straight from it; once it idles (or the process is frozen or killed) a background job checks
 * the unread-whisper count and your recent mentions every few minutes.
 */
class ChatNotifications(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val chat: ChatRepository,
) {
    private val notifier by lazy { ChatNotifier(app) }
    private val prefs = app.getSharedPreferences(ChatRepository.PREFS, Context.MODE_PRIVATE)
    private val jobs get() = app.getSystemService(JobScheduler::class.java)
    /** Only used to find links in polled mentions; doesn't touch the socket's user list. */
    private val formatter = MessageFormatter(emotes = { chat.assets.emotes.value }, isConnectedUser = { false })

    @Volatile private var inForeground = false

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                inForeground = true
                JOB_IDS.forEach { jobs.cancel(it) }
                notifier.clearMentions()
            }

            override fun onStop(owner: LifecycleOwner) {
                inForeground = false
                // Only what arrives from now on is news; unread whispers you've already seen aren't
                val now = System.currentTimeMillis()
                prefs.edit { putLong(KEY_AWAY_SINCE, now).putLong(KEY_WHISPERS_SINCE, now).putLong(KEY_MENTIONS_SINCE, now) }
                val s = settings.settings.value
                if ((s.notifyWhispers || s.notifyHighlights) && loggedIn() && notifier.canNotify()) schedule(JOB_IDS[0], FIRST_CHECK_MS)
            }
        })
        scope.launch {
            chat.alerts.collect { alert ->
                if (inForeground) return@collect
                notifier.show(alert, chat.me.value?.nick)
                remember(alert)
            }
        }
        scope.launch {
            chat.openConversation.filterNotNull().collect { notifier.clearWhisper(it) }
        }
    }

    private fun remember(alert: ChatAlert) {
        val key = if (alert is ChatAlert.Whisper) KEY_WHISPERS_SINCE else KEY_MENTIONS_SINCE
        if (alert.timestamp > prefs.getLong(key, 0)) prefs.edit { putLong(key, alert.timestamp) }
    }

    private fun loggedIn(): Boolean {
        if (prefs.getString(ChatRepository.PREF_NICK, null) == null) return false
        val cookies = runCatching { WebViewCookieJar.cookieHeader("https://www.destiny.gg") }.getOrNull().orEmpty()
        return cookies.contains("sid=") || cookies.contains("rememberme=")
    }

    private fun schedule(id: Int, delayMs: Long) {
        val job = JobInfo.Builder(id, ComponentName(app, ChatPollJob::class.java))
            .setMinimumLatency(delayMs)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        runCatching { jobs.schedule(job) }.onFailure { Log.w(TAG, "couldn't schedule the whisper check", it) }
    }

    /** One background check, run by job [jobId]; schedules the next one unless there's no point. */
    suspend fun poll(jobId: Int) {
        if (inForeground || !check()) return
        // Every 5 minutes for the first hour away, then every 15
        val away = System.currentTimeMillis() - prefs.getLong(KEY_AWAY_SINCE, System.currentTimeMillis())
        // Scheduling under the running job's own id would stop it, so the two ids take turns
        schedule(if (jobId == JOB_IDS[0]) JOB_IDS[1] else JOB_IDS[0], if (away < 60 * 60_000L) CHECK_MS else SLOW_CHECK_MS)
    }

    private suspend fun check(): Boolean {
        val s = settings.current()
        if (!s.notifyWhispers && !s.notifyHighlights) return false
        if (!loggedIn() || !notifier.canNotify()) return false
        val me = prefs.getString(ChatRepository.PREF_NICK, null) ?: return false
        if (s.notifyWhispers) runCatching { pollWhispers(s, me) }.onFailure { if (it is CancellationException) throw it }
        if (s.notifyHighlights && s.highlightMentions) runCatching { pollMentions(s, me) }.onFailure { if (it is CancellationException) throw it }
        return true
    }

    private suspend fun pollWhispers(s: AppSettings, me: String) {
        val unread = ChatApi.unreadCount() ?: return
        if (unread == 0) return
        val since = prefs.getLong(KEY_WHISPERS_SINCE, 0)
        val ignored = s.ignoredUsers.mapTo(HashSet()) { it.lowercase() }
        val fresh = ChatApi.conversations()
            .filter { it.unread > 0 && !it.lastMessageFromMe && it.timestamp > since && it.username.lowercase() !in ignored }
            .sortedBy { it.timestamp }
        fresh.forEach { notifier.show(ChatAlert.Whisper(it.username, it.lastMessage, it.timestamp), me) }
        fresh.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong(KEY_WHISPERS_SINCE, it) } }
    }

    private suspend fun pollMentions(s: AppSettings, me: String) {
        val since = prefs.getLong(KEY_MENTIONS_SINCE, 0)
        val filter = ChatFilter.from(s, ChatUser(nick = me))
        val fresh = ChatApi.mentions(me, 10)
            .filter { it.timestamp > since && !it.nick.equals(me, ignoreCase = true) }
            .filter { logged ->
                val user = ChatUser(nick = logged.nick)
                val f = formatter.format(logged.text, user)
                val line = ChatLine.User(0, logged.timestamp, user, logged.text, f.segments, f.action, f.greentext, f.sus, f.linkTag, f.mentions, "")
                !filter.hidesText(line) && filter.highlights.matches(line)
            }
            .sortedBy { it.timestamp }
        fresh.forEach { notifier.show(ChatAlert.Mention(it.nick, it.text, it.timestamp), me) }
        fresh.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong(KEY_MENTIONS_SINCE, it) } }
    }

    companion object {
        private const val TAG = "ChatNotifications"
        private val JOB_IDS = intArrayOf(4_201, 4_202)
        /** Just after the socket idles (2 minutes after leaving when nothing is playing). */
        private const val FIRST_CHECK_MS = 3 * 60_000L
        private const val CHECK_MS = 5 * 60_000L
        private const val SLOW_CHECK_MS = 15 * 60_000L
        private const val KEY_AWAY_SINCE = "bg_away_since"
        private const val KEY_WHISPERS_SINCE = "bg_whispers_since"
        private const val KEY_MENTIONS_SINCE = "bg_mentions_since"
    }
}

/** Runs [ChatNotifications.poll]; the system wakes the app for it even when it was frozen or killed. */
class ChatPollJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = (application as TinyscreenApp).graph
        job = graph.scope.launch {
            try {
                graph.chatNotifications.poll(params.jobId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ChatPollJob", "whisper check failed", e)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        // Ask to run again later
        return true
    }
}
