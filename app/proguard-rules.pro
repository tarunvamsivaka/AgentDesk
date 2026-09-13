# ============================================================
# AgentDesk ProGuard Rules
# ============================================================

# --- Room ---
-keep class com.agentdesk.core.persistence.entity.** { *; }
-keep class com.agentdesk.core.persistence.dao.** { *; }
-keep class com.agentdesk.core.persistence.db.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}

# --- WorkManager ---
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **.*$serializer {
    static **.$serializer INSTANCE;
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Coroutines ---
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# --- Kotlin ---
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @kotlin.jvm.JvmStatic <methods>;
    @kotlin.jvm.JvmField <fields>;
}

# --- AgentDesk Agent/Policy Engine ---
-keep class com.agentdesk.agent.policy.PolicyEngine { *; }
-keepclassmembers class com.agentdesk.agent.policy.PolicyEngine {
    public static ** FORBIDDEN_INTENTS;
}
-keep class com.agentdesk.agent.rule.Intents { *; }

# --- Compose ---
-keep class androidx.compose.ui.tooling.preview.** { *; }
-dontwarn androidx.compose.**

# --- DataStore ---
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
