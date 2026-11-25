package io.github.jordanwoodson.tinyscreen.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

@Suppress("UNUSED_PARAMETER")
internal fun playerRequestInterceptor(context: Context): ((WebResourceRequest) -> WebResourceResponse?)? = null
