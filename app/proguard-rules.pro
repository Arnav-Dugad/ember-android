# Firestore maps documents onto these classes reflectively.
-keep class io.github.arnavdugad.ember.data.remote.dto.** { *; }
-keepclassmembers class io.github.arnavdugad.ember.data.remote.dto.** { <init>(); }

# kotlinx.serialization: keep generated serializers for @Serializable classes (routes, GitHub API).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Debug App Check provider is looked up reflectively and only exists in debug builds.
-dontwarn com.google.firebase.appcheck.debug.**

# Crashlytics: readable stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
