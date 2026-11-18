-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.hongguo.watch.data.model.** { *; }
-keep class com.hongguo.watch.upstream.** { *; }

# IJK JNI / reflection entry points (consumer-rules also apply)
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep class tv.danmaku.ijk.media.player.misc.** { *; }
-dontwarn tv.danmaku.ijk.media.player.**

# Media3 / Coil reflection-safe keep for player UI
-dontwarn androidx.media3.**
-keepclassmembers class * implements androidx.media3.common.Player$Listener { *; }
