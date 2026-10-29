# kotlinx.serialization and OkHttp ship their own consumer rules.
# Keep the @Serializable model classes' generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class app.novachat.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
