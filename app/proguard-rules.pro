# WorkManager
-keep class androidx.work.** { *; }
-keepclassmembers class androidx.work.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Parcelable models used by map location updates
-keep class com.wim4you.intervene.fbdata.** { *; }

# Keep BuildConfig fields referenced by reflection-free code, but avoid leaking in stack traces
-keepclassmembers class com.wim4you.intervene.BuildConfig {
    public static final java.lang.String GOOGLE_DIRECTIONS_API_KEY;
}

# Strip verbose Android logging in release (errors still go through SecureLog in debug)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile