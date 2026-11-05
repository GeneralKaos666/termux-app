# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android-optimize.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep JNI entry points (native methods are resolved by name from C as Java_<pkg>_<Class>_<method>),
# e.g. com.termux.app.TermuxInstaller.getZip().
-keepclasseswithmembernames class * {
    native <methods>;
}
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# The default file above no longer implies this; keep the release artifact's current
# non-optimized R8 behavior. Re-evaluate when R8 hardening is taken up.
-dontoptimize
