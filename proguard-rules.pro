# Only the interface libraries (Compose, Skiko) are shrunk. Everything else is kept whole:
# CloudStream extensions are loaded at run time and may use any class of the CloudStream
# library and of its dependencies (Kotlin, coroutines, OkHttp, Jsoup, Jackson, Rhino…).
-dontobfuscate
-dontoptimize
-dontwarn **
-dontnote **
-ignorewarnings
-keepattributes *

-keep class !androidx.compose.**,!androidx.collection.**,!androidx.lifecycle.**,!androidx.annotation.**,!org.jetbrains.compose.**,!androidx.graphics.**,!androidx.savedstate.**,!androidx.navigationevent.** ,** { *; }

# Compose entry points used by the app are found from our classes (kept above); Skiko is called from native code.
-keep class org.jetbrains.skiko.** { *; }
-keep class org.jetbrains.skia.** { *; }
