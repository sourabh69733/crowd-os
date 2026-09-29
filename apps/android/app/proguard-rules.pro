# secp256k1 is called through JNI; native code looks classes and methods up by name.
-keep class fr.acinq.secp256k1.** { *; }

# Quartz: only NIP-44 and NIP-49 are used, called directly, so R8 can trace and shrink the rest.
-dontwarn com.vitorpamplona.quartz.**
-dontwarn com.fasterxml.jackson.**

# OkHttp / Okio optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
