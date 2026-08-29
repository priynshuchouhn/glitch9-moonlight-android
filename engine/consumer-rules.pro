# Bouncy Castle registers algorithms and implementations by class name. R8 cannot
# discover those references, so release builds must preserve the provider graph.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.openssl.** { *; }

# moonlight-core resolves both native entry points and Java callbacks on this
# class by their exact JNI names. Preserve the complete JNI boundary for every
# release app consuming this engine library.
-keep class com.limelight.nvstream.jni.MoonBridge { *; }
-keep class com.limelight.nvstream.jni.MoonBridge$* { *; }
