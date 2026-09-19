# Proguard / R8 rules for Radio Shuffler

# Retain generic signatures and annotations for Retrofit & Gson reflection
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Retrofit 2
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepclassmembers enum * { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }

# Keep all data models and services in our app package from obfuscation or stripping
-keep class com.example.radioshuffle.** { *; }
-keepclassmembers class com.example.radioshuffle.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Media3 ExoPlayer
-keep class androidx.media3.** { *; }
