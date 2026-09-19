# Keep Retrofit and Gson data models from being obfuscated/stripped
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.example.radioshuffle.** { *; }

# Keep Media3 components
-keep class androidx.media3.** { *; }
