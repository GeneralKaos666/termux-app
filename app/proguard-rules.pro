# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
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
