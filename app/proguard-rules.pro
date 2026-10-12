-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
-dontobfuscate

-keepclasseswithmembers public class androidx.recyclerview.widget.RecyclerView { *; }
-keep class com.beemdevelopment.aegis.ui.fragments.preferences.*
-keep class com.beemdevelopment.aegis.importers.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# JSch resolves negotiated SSH algorithms by class name at runtime.
-keep class com.jcraft.jsch.** { *; }
# Optional desktop connectors, logging adapters, GSSAPI and BC ML-KEM are unused on Android.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
-dontwarn org.bouncycastle.crypto.kems.MLKEMExtractor
-dontwarn org.bouncycastle.crypto.params.MLKEM**

-dontwarn javax.naming.**
