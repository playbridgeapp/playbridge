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

# TabManager accesses this private field through getDeclaredField().
-keepclassmembers class mozilla.components.browser.engine.gecko.GeckoEngineSession {
    org.mozilla.geckoview.GeckoSession geckoSession;
}

# SnakeYAML derives logger names from Class.getPackage(). R8 otherwise moves these
# classes into the default package, where Android 16 returns null and GeckoRuntime
# crashes during startup in DebugConfig.fromFile(). Members may still be optimized.
-keepnames class org.yaml.snakeyaml.**

# Mozilla Nimbus can report through the optional Glean telemetry runtime.
# PlayBridge does not package or initialize Glean.
-dontwarn mozilla.telemetry.glean.Glean
-dontwarn mozilla.telemetry.glean.GleanInternalAPI
-dontwarn mozilla.telemetry.glean.internal.CommonMetricData
-dontwarn mozilla.telemetry.glean.internal.DynamicLabelType
-dontwarn mozilla.telemetry.glean.internal.Lifetime
-dontwarn mozilla.telemetry.glean.internal.TimeUnit
-dontwarn mozilla.telemetry.glean.private.EventExtras
-dontwarn mozilla.telemetry.glean.private.EventMetricType
-dontwarn mozilla.telemetry.glean.private.PingType
-dontwarn mozilla.telemetry.glean.private.TimingDistributionMetricType

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile