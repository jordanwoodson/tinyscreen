# R8 rules for the minified release build. Libraries ship their own consumer rules (Media3,
# kotlinx.serialization, androidx.webkit, OkHttp, Coil, DataStore), so this file only covers what
# the app itself does. scripts/smoke-test-release.sh launches a release APK to catch gaps.

# Release builds drop verbose and debug logging: R8 removes the calls, and the message building too
# when that has no side effects. Log.i, Log.w and Log.e stay so failures still reach logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# WebView bridge used by the web chat (addJavascriptInterface). JavaScript calls these methods by
# name, so they must keep their names. AGP's default rules contain the same keep; it's repeated so
# the bridge doesn't depend on that file. androidx.webkit message listeners
# (WebViewCompat.addWebMessageListener) need no rule: webkit calls them through its own interfaces.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization models (the library ships rules; keep ours explicit for safety)
-keepclassmembers @kotlinx.serialization.Serializable class io.github.jordanwoodson.tinyscreen.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.jordanwoodson.tinyscreen.**$$serializer { *; }

# Optional TLS providers OkHttp probes for at runtime; not bundled
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
