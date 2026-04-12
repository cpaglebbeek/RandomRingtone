# RandomRingtone ProGuard Rules
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# App Serializable classes (backup, remote logger, update manager)
-keep @kotlinx.serialization.Serializable class nl.icthorse.randomringtone.data.** { *; }
-keepclassmembers class nl.icthorse.randomringtone.data.** {
    *** Companion;
    *** serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class nl.icthorse.randomringtone.data.*Dao { *; }
-keep class nl.icthorse.randomringtone.data.*Dao_Impl { *; }
