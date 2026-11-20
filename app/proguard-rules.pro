# Keep kotlinx.serialization models for transfer metadata and HTTP manifest
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.example.transfer.**$$serializer { *; }
-keepclassmembers class com.example.transfer.** { *** Companion; }

# Keep Nearby Connections classes
-keep class com.google.android.gms.nearby.** { *; }
