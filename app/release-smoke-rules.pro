# AndroidJUnitRunner calls this library supplied by the target APK. R8 cannot
# see that external entry point when shrinking the target application.
-keep class androidx.tracing.Trace { *; }
# AndroidX Test is compiled separately and calls stdlib entry points R8 may
# otherwise inline out of the target APK. These rules apply only to smoke builds.
-keep class kotlin.** { *; }
-keep class dev.herdroid.WoolooMobileApp { public *; }
-keep class dev.herdroid.WoolooMobileAppKt { public *; }
-keep class dev.herdroid.data.SshKeyManager { public *; }
-keep class dev.herdroid.data.SshKeyInfo { public *; }
-keep class dev.herdroid.data.SecretStore { public *; }
-keep class dev.herdroid.data.SecretStore$Companion { public *; }
