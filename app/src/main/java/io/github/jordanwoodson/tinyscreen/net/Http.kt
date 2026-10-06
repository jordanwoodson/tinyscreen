package io.github.jordanwoodson.tinyscreen.net

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 16; motorola edge 2025) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.header("User-Agent") == null) {
                    chain.proceed(req.newBuilder().header("User-Agent", MOBILE_UA).build())
                } else {
                    chain.proceed(req)
                }
            }
            .build()
    }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpException(resp.code, url)
                resp.body!!.string()
            }
        }
}

class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** destiny.gg itself or one of its subdomains, never a lookalike such as xdestiny.gg. */
fun isDestinyHost(host: String?): Boolean {
    val h = host?.lowercase()?.removeSuffix(".") ?: return false
    return h == "destiny.gg" || h.endsWith(".destiny.gg")
}

/**
 * Shares cookies between OkHttp and WebView, so signing in through the destiny.gg login page
 * in a WebView also authenticates the chat socket and API calls.
 */
object WebViewCookieJar : CookieJar {
    private val manager: CookieManager by lazy {
        CookieManager.getInstance().apply { setAcceptCookie(true) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.endsWith("destiny.gg")) return
        val target = url.toString()
        cookies.forEach { manager.setCookie(target, it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.endsWith("destiny.gg")) return emptyList()
        val header = manager.getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    fun cookieHeader(url: String): String? = manager.getCookie(url)

    fun clearAll(onDone: () -> Unit = {}) {
        manager.removeAllCookies { manager.flush(); onDone() }
    }
}
