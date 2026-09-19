# Keep Retrofit and Gson data models from being obfuscated/stripped
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
