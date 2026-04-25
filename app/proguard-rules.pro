# NanoHTTPd
-keep class fi.iki.elonen.** { *; }

# WebRTC — native JNI references classes by name
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Gson data classes — serialized/deserialized by reflection
-keep class dev.serverpages.server.ChatMessage { *; }
-keep class dev.serverpages.server.CodeInfo { *; }
-keep class dev.serverpages.server.ConversationSummary { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Hilt / Dagger generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-dontwarn dagger.hilt.**

# WorkManager
-keep class androidx.work.** { *; }
-keep class dev.serverpages.service.ServiceWatchdogWorker { *; }

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# gomobile-bound libtailscale — JNI lookups by class/method name
-keep class go.** { *; }
-keep class libtailscale.** { *; }
-keep class dev.serverpages.tailscale.** { *; }
-dontwarn go.**
-dontwarn libtailscale.**
