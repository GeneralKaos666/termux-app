# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# The com.termux.shared API is the published integration surface the companion apps
# (Termux:API, :X11, :Widget, :Boot, :Styling, :Float) resolve against at runtime — via intents,
# binders, sockets and Java-serialized models (Error, ResultData, ReportInfo, TextIOInfo,
# LocalSocketRunConfig, ...). Keep its class and member names intact in every consuming build.
-keep class com.termux.shared.** { *; }
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable
