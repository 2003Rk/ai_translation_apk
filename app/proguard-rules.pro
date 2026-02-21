# Bootstrap Agent — ProGuard rules
# Target: Android 8.1 (API 27), minSdk 26

# ── Kotlin ────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── Our application classes — keep everything so nothing breaks ───────────
-keep class com.bootstrap.agent.** { *; }

# ── Android components — must never be renamed ───────────────────────────
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ── FileProvider (AndroidX Core) ─────────────────────────────────────────
-keep class androidx.core.content.FileProvider { *; }

# ── org.json — built-in but referenced by name in some toolchains ─────────
-keep class org.json.** { *; }
-dontwarn org.json.**

# ── Suppress common harmless warnings ────────────────────────────────────
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**
-ignorewarnings
