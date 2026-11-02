# Keep names readable: shrink unused code/resources, but don't obfuscate.
-dontobfuscate

# kotlinx.serialization (generated serializers are looked up via companions)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class se.spinhaus.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class se.spinhaus.**$$serializer { *; }

# Enums are stored by name in the database and settings
-keepclassmembers enum se.spinhaus.** { *; }
