-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-keep class com.hongguo.watch.data.model.** { *; }
-keep class com.hongguo.watch.upstream.** { *; }
-keep class org.bouncycastle.** { *; }
