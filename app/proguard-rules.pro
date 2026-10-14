# Descente-Canyon ProGuard rules

# JSoup
-keeppackagenames org.jsoup.nodes

# OkHttp ships its own consumer rules; only silence optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fr.descentecanyon.app.**$$serializer { *; }
-keepclassmembers class fr.descentecanyon.app.** {
    *** Companion;
}
-keepclasseswithmembers class fr.descentecanyon.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Tink / errorprone annotations referenced by androidx security
-dontwarn com.google.errorprone.annotations.**
