# Compose + Compose runtime are reflection-light; keep the module entry points.
-keep class io.github.kvmy666.gboardplus.GboardPlusHook { *; }
-keepnames class io.github.kvmy666.gboardplus.hook.** { *; }

# Xposed entry points are loaded by name from assets/xposed_init.
-keep class io.github.kvmy666.gboardplus.** {
    public <init>(...);
}

# Keep Xposed API classes referenced only via the compileOnly dependency.
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# Keep model/serialization helpers used reflectively.
-keepclassmembers class io.github.kvmy666.gboardplus.GbpPrefsJson { *; }
