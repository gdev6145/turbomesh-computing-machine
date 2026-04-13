# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep OkHttp and Jsoup for reflection safety
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }
-keep class okhttp3.** { *; }

# Keep data models
-keep class com.turbomesh.app.data.model.** { *; }
