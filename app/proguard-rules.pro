# sshj and BouncyCastle use reflection for algorithm discovery.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.ietf.jgss.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.herdroid.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.herdroid.**$$serializer { *; }
