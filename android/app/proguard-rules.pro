# kotlinx.serialization and OkHttp ship their own consumer rules.
# Keep the @Serializable model classes' generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class app.novachat.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink (end-to-end encryption) references compile-only annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
