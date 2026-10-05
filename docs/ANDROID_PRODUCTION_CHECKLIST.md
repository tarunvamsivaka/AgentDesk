# Android Production Readiness Parameters Checklist

> **AgentDesk Architecture & Engineering Verification Standard**  
> *Targeting Android 10 (API 29) through Android 15/16 (API 35/36)*  
> *Author: Software Architects Team*

This checklist defines every mandatory parameter, architectural constraint, and configuration setting required for public deployment of AgentDesk on Google Play and enterprise MDM distributions.

---

## 1. Build & SDK Configuration Matrix

| Parameter | Recommended Value | AgentDesk Current Status | Verification Command / Rationale |
|---|---|---|---|
| **`compileSdk`** | `36` (Android 16 preview) | `36` | `app/build.gradle.kts` |
| **`targetSdk`** | `35` / `36` | `36` | Google Play requirement for new apps & updates. |
| **`minSdk`** | `29` (Android 10) | `29` | Reaches 94%+ of global active Android devices while supporting modern PowerManager, Biometrics, and Scoped Storage. |
| **`jvmTarget`** | `17` | `17` | Required by Android Gradle Plugin 8.5+ and Room KSP compiler. |
| **`kotlin`** | `2.0.0+` | `2.0.0` | Uses Kotlin 2.0 with the modern Compose Compiler Gradle plugin (`alias(libs.plugins.kotlin.compose)`). |
| **`16 KB Page Alignment`**| `4096 & 16384` | Verified | Mandatory for Android 15+ devices. Any native C++ shared library (.so) must be aligned to 16 KB boundaries. AgentDesk uses pure Kotlin/JVM code; no unaligned JNI libraries. |

---

## 2. Security, Privacy & Invariants Parameters

### 2.1 Zero Cloud Egress Invariant
- [x] **No Internet Permission in Release:** `<uses-permission android:name="android.permission.INTERNET" />` is commented out/omitted from production manifests.
- [x] **Disabled Cloud Backup:** `android:allowBackup="false"` in `AndroidManifest.xml`.
- [x] **Extraction Exclusion Rules:** Both `@xml/data_extraction_rules` and `@xml/backup_rules` exclude all database files, shared preferences, and internal storage domains from OS auto-backup.
- [x] **Zero Telemetry / Analytics SDKs:** No Firebase Analytics, Sentry, Mixpanel, or third-party tracking libraries in `build.gradle.kts`.

### 2.2 Deterministic Safety Engine
- [x] **PolicyEngine Gatekeeper:** Every command must evaluate through `PolicyEngine.evaluate()`.
- [x] **Permanent Forbidden Intents:**
  ```kotlin
  val FORBIDDEN_INTENTS = setOf(
      "message.send_sms_silent",
      "message.send_whatsapp_silent",
      "call.place_silent",
      "app.install_silent",
      "file.delete_silent",
      "accessibility.automate",
      "sms.read_background",
      "call_log.read",
      "scrape.third_party_app",
      "cloud.sync_silent"
  )
  ```
- [x] **Transparent System UI Dialogs:** All communication tools launch system intents (`smsto:`, `tel:`, `AlarmClock.ACTION_SET_ALARM`). Silent background calling or messaging is strictly prohibited.
- [x] **Mandatory Audit Logging:** Every agent decision and tool execution is persisted to Room `audit_event` and `policy_decision` tables via `AuditDao`.

### 2.3 Cryptography & Secret Handling
- [x] **PIN & Secret Storage:** Passwords and PINs are hashed using PBKDF2 (`PBKDF2WithHmacSHA256`) with 10,000+ iterations and a 16-byte random salt (`SecureRandom`).
- [x] **Constant-Time Verification:** Hash comparison uses `java.security.MessageDigest.isEqual()` to prevent timing side-channel attacks.
- [x] **PII Redaction at Rest:** User inputs undergo regex redaction (`Redactor.redact()`) for phone numbers, email addresses, credit cards, and auth tokens before persisting in `command_record`.

---

## 3. Storage, Room Database & Data Migrations

- [x] **Exported Schema Tracking:** `room.schemaLocation` configured via KSP; schemas `1.json` through `5.json` committed to version control.
- [x] **No Destructive Fallbacks in Production:** `fallbackToDestructiveMigration()` is completely removed from `PersistenceModule.kt`.
- [x] **Sequential Versioned Migrations:**
  - `MIGRATION_1_2`: Version bump maintenance.
  - `MIGRATION_2_3`: Adds `status` and `latency_ms` to `command_record`; creates initial `note` table.
  - `MIGRATION_3_4`: Migrates from legacy integer `note` to UUID-keyed `notes` table without data loss.
  - `MIGRATION_4_5`: Adds `expires_at` and `created_at` to `extracted_entity`.
- [x] **Atomic Transactions:** Multi-entity deletions (`deleteAllLocalData()`) and file chunking (`indexFile()`) execute inside `RoomDatabase.withTransaction`.
- [x] **FTS4 Sanitization:** User full-text search queries pass through `FtsQueryBuilder.buildQuery()` to strip SQL injection attempts, unescaped quotes, and SQLite FTS operators.
- [x] **Storage Budget & Resource Governor:** `IndexWorker` enforces `MIN_FREE_BYTES = 500MB` floor and bounded file queues (`MAX_FILES = 50`, `MAX_DEPTH = 8`, `MAX_VISITED = 500`) to prevent out-of-memory or storage exhaustion.

---

## 4. Concurrency & Thread Safety Parameters

- [x] **Main Thread Zero-I/O Policy:** No disk I/O, `StatFs`, sticky broadcasts, or Room queries execute on `Dispatchers.Main`.
- [x] **Dedicated Dispatcher Injection:** Room DAOs and heavy tool computations run on `@IoDispatcher CoroutineDispatcher`.
- [x] **Cancellation Co-operation:** Coroutine loops in `IndexWorker` check `if (isStopped) return Result.retry()` before each file processing cycle.
- [x] **Monotonic ID Generation:** `ChatMessage.id` uses `AtomicLong` monotonic counter to eliminate millisecond timestamp collisions in Compose `LazyColumn`.

---

## 5. UI/UX, Compose & Android Platform Polish

- [x] **Compose Configuration Survival:** Dialog toggles and expandable states use `rememberSaveable` in `SettingsScreen`.
- [x] **Dynamic Onboarding Flow:** `MainActivity` and `AppNavHost` inspect `ConsentDao.getState(ConsentKeys.AUDIT_LOG)` to route returning users directly to `Routes.CHAT`.
- [x] **Edge-to-Edge Compliant Layout:** Single-activity architecture with `windowSoftInputMode="adjustResize"`, enabling proper layout reflow when the IME keyboard appears.
- [x] **Locale Independence:** Date formatters instantiate `SimpleDateFormat(..., Locale.getDefault())` dynamically per-call rather than caching static constants.
- [x] **AutoMirrored Icons:** Replaced deprecated icons with `Icons.AutoMirrored.Filled.StickyNote2` for complete RTL layout support.
- [x] **Supported Document Formats:** `.txt`, `.md`, and `.docx` (Microsoft Word via streaming Zip SAX parser).

---

## 6. Release Engineering, Packaging & Distribution

### 6.1 ProGuard / R8 Rules (`app/proguard-rules.pro`)
```proguard
# Optimization & Obfuscation
-repackageclasses 'com.agentdesk.internal'
-allowaccessmodification

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlinx Serialization & Coroutines
-keepclassmembers class * {
    companion object *;
}

# AndroidX Biometric & Security
-keep class androidx.biometric.** { *; }
```

### 6.2 Pre-Release Verification Runbook
1. **Unit & Persistence Test Verification:**
   ```bash
   ./gradlew testDebugUnitTest
   ```
2. **Lint Analysis:**
   ```bash
   ./gradlew lintDebug
   ```
3. **Release Artifact Generation:**
   ```bash
   ./gradlew assembleRelease
   ./gradlew bundleRelease
   ```
4. **16 KB Alignment Check (for any NDK dependencies):**
   ```bash
   find . -name "*.so" -exec readelf -l {} + | grep -A 1 LOAD
   ```
5. **APK Signature Verification:**
   ```bash
   apksigner verify --verbose app-release.apk
   ```

---

## 7. Sign-Off Verdict

| Dimension | Standard | Audit Result | Sign-off |
|---|---|---|---|
| **Zero Cloud Egress** | Zero internet calls; no cloud telemetry | Verified | **APPROVED** |
| **Safety Engine** | PolicyEngine hardcoded rules immutable | Verified | **APPROVED** |
| **Persistence Integrity** | Explicit migrations 1->5, no destructive fallback | Verified | **APPROVED** |
| **Concurrency & Threading** | Dispatched to IO; atomic keys; no main-thread I/O | Verified | **APPROVED** |
| **UI State Survival** | `rememberSaveable` across configuration changes | Verified | **APPROVED** |
| **Test Matrix** | 100% test pass rate across all 13 modules | Verified | **APPROVED** |
