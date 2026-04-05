# Keep bitcoinj classes
-keep class org.bitcoinj.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn org.bitcoinj.**
-dontwarn com.google.protobuf.**
-dontwarn org.slf4j.**

# Keep ECKey and related crypto
-keep class org.spongycastle.** { *; }
-dontwarn org.spongycastle.**
