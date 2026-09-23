# kotlinx.serialization keeps generated serializers reachable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.pocket.agent.**$$serializer { *; }
-keepclassmembers class com.pocket.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.pocket.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional references that are not present on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
