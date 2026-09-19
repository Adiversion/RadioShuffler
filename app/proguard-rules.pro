# Keep Retrofit and Gson models
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.example.radioshuffle.** { *; }

# Keep Media3 / ExoPlayer components
-keep class androidx.media3.** { *; }
