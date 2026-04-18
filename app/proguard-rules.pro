# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep BLE-related classes
-keep class android.bluetooth.** { *; }

# Keep mesh model classes for serialization
-keep class com.turbomesh.computingmachine.mesh.** { *; }
-keep class com.turbomesh.computingmachine.data.models.** { *; }

# Keep OkHttp and Jsoup for reflection safety
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }
-keep class okhttp3.** { *; }

# Keep article scanner data models
-keep class com.turbomesh.app.data.model.** { *; }
