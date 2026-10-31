# Keep rules for the `profile` build type (R8 + resource shrinking, plan UI refresh U0).
# Room, Retrofit, OkHttp, kotlinx.serialization, WorkManager and Compose ship their own consumer rules;
# these cover what reaches classes by name or reflection inside Masala and the libraries that don't.

# Line numbers for crash reports from profile builds.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# kotlinx.serialization: generated serializers are looked up through the companion.
-keepclassmembers @kotlinx.serialization.Serializable class com.samosaai.masala.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.samosaai.masala.**$$serializer { *; }

# Retrofit service interfaces are proxied by reflection: keep the members too, so R8
# keeps the HTTP annotations and return types (a bare interface keep only keeps the declaration).
-keep,allowobfuscation interface com.samosaai.masala.**.*Api { *; }
-keep,allowobfuscation interface com.samosaai.masala.llm.ApiService { *; }

# pdfbox-android: optional JPEG 2000 and crypto providers are not bundled.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# Optional annotations and platform classes referenced by libraries.
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
