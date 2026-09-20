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

# Keep Retrofit API and Gson data models from obfuscation or field stripping
-keep class com.example.radioshuffle.*Envelope { *; }
-keep class com.example.radioshuffle.*Data { *; }
-keep class com.example.radioshuffle.*Record { *; }
-keep class com.example.radioshuffle.*Block { *; }
-keep class com.example.radioshuffle.*Wrapper { *; }
-keep class com.example.radioshuffle.*Details { *; }
-keep class com.example.radioshuffle.*Info { *; }
-keep class com.example.radioshuffle.*Hits { *; }
-keep class com.example.radioshuffle.*Hit { *; }
-keep class com.example.radioshuffle.*Source { *; }
-keep class com.example.radioshuffle.ResolvedStation { *; }
-keep class com.example.radioshuffle.RadioGardenService { *; }

# Strip verbose/debug logs in release builds for performance and battery
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Media3 ExoPlayer
-keep class androidx.media3.** { *; }
