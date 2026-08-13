# Velox — R8 full mode is on (see gradle.properties).
# Media3 ships its own consumer rules; nothing extra is needed for the core player.

# Keep line numbers so crash reports from released APKs stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Media3 instantiates renderers/extractors reflectively by class name.
-keep class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# Strip verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
