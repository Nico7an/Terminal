# sshj instantiates algorithms by reflection through factories; BouncyCastle registers its services by name.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
-dontwarn sun.security.**
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.**
# No need for obfuscation: the code is ours and it makes stack traces readable.
-dontobfuscate
