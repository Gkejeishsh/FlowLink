# LoopJet release rules. The engine avoids reflection entirely; these keep the few library entry
# points that R8 cannot see (QR encoding is reached only through zxing's public classes).
-keep class com.google.zxing.** { *; }
-keep class com.loopjet.app.core.** { *; }
-dontwarn com.google.zxing.**
# Keep line numbers for readable crash reports without leaking source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
