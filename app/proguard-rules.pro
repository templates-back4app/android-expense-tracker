# Parse Android SDK: keep subclasses and reflection-based encoders.
-keep class com.parse.** { *; }
-dontwarn com.parse.**
-keep class com.back4app.expensetracker.sync.** { *; }
# OkHttp (transitive dependency of Parse)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.back4app.expensetracker.**$$serializer { *; }
-keepclassmembers class com.back4app.expensetracker.** { *** Companion; }
-keepclasseswithmembers class com.back4app.expensetracker.** { kotlinx.serialization.KSerializer serializer(...); }
