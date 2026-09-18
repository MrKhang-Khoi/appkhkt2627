# Proguard rules for CVA-SmartGuardian
-keep class vn.edu.cva.smartguardian.data.** { *; }
-keep class vn.edu.cva.smartguardian.service.** { *; }
-keep class vn.edu.cva.smartguardian.receiver.** { *; }
-keep class vn.edu.cva.smartguardian.ui.** { *; }

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
