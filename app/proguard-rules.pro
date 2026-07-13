# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# Keep Retrofit models
-keep class com.xeriomy.brawldrafter.data.api.** { *; }
-keep class com.xeriomy.brawldrafter.data.model.** { *; }

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter

# Keep ML Kit
-keep class com.google.mlkit.** { *; }