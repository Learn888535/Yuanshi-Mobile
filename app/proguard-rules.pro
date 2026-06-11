# 原世数字人 ProGuard 规则

# 保留 DUIX SDK
-keep class ai.guiji.duix.sdk.client.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
