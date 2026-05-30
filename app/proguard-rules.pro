# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ========== Kotlin Coroutines 混淆保护 ==========
# 保护协程内部类和异常处理，防止由于反射 getClass() 导致的 NPE 闪退
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.** { *; }

# ========== Google ML Kit 混淆保护 ==========
# 保护机器学习引擎类名，防止识别引擎因找不到对应类引发崩溃
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-dontwarn com.google.mlkit.**

# ========== AndroidX & Compose 基础保护 ==========
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod