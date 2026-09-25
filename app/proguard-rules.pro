# kotlinx.serialization keeps its generated serializers via @Serializable; the
# plugin ships its own consumer rules, so nothing extra is required here.

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep the WorkManager worker constructor reachable by reflection.
-keep class com.michalkulik.mkbackup.work.** { *; }
