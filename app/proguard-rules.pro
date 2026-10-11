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

# I2P EdDSA also accepts Sun JDK X509Key objects. sshj uses EdDSAPublicKey
# (including its Ed25519PublicKey subclass), so that JDK-only fallback is never
# entered on Android. ReleaseCryptoTest exercises signing and verification in R8.
-dontwarn sun.security.x509.X509Key

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.herdroid.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.herdroid.**$$serializer { *; }
