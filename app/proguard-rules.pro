# WebView bridge used by the web chat
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization models (the library ships rules; keep ours explicit for safety)
-keepclassmembers @kotlinx.serialization.Serializable class io.github.jordanwoodson.tinyscreen.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.jordanwoodson.tinyscreen.**$$serializer { *; }

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
