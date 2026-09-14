# AgentDesk Bug Report — Pre-QA Security & Reliability Review

**Reviewer:** Senior Android Security + Reliability Engineer  
**Date:** 2025-09-14  
**Commit:** 3486101 (batch 4)  
**Lint Status:** BUILD SUCCESSFUL — 0 ERRORS, 10 warnings  
**Test Status:** 51 passing, 0 failures

---

## Bug Summary Table

| ID | Severity | File:Line | Description | Repro | Suggested Fix |
|----|----------|-----------|-------------|-------|---------------|
| **BUG-001** | **CRITICAL** | `AppLockManager.kt:34,39,55` | **PIN stored in plaintext memory** — `pinHash = pin.hashCode()` uses Java `String.hashCode()` (non-cryptographic, reversible, collision-prone). PIN never persisted but also never encrypted; `verifyPin` compares raw hash codes. Process memory dump or serialization leaks PIN hash. | 1. Enable app lock with PIN "1234" 2. Dump process memory (`adb shell am dumpheap`) 3. Find `pinHash` integer (e.g., `3030312`) 4. Reverse via hash collision or rainbow table | • Store **bcrypt/Argon2** hash via `EncryptedSharedPreferences` or `Jetpack Security` • Use `SecretKey` with `AndroidKeyStore` for key derivation • Never use `String.hashCode()` for secrets |
| **BUG-002** | **CRITICAL** | `AndroidManifest.xml:42-43` | **Missing `SET_ALARM` permission** — `ToolExecutor.setAlarm()` and `setTimer()` use `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER` which require `com.android.alarm.permission.SET_ALARM`. Missing declaration causes `SecurityException` on some OEMs (Samsung, Xiaomi). | 1. Install on Samsung One UI 6+ 2. Trigger "set alarm at 7am" 3. Observe `SecurityException: Neither user 10XXX nor current process has android.permission.SET_ALARM` | Add `<uses-permission android:name="com.android.alarm.permission.SET_ALARM" />` to manifest |
| **BUG-003** | **HIGH** | `ToolExecutor.kt:230-236` | **`smsto:` URI uses raw contact string** — `draftSms()` builds `Uri.parse("smsto:$contact")` where `contact` is a **display name** (e.g., "Mom") not a phone number. Android `smsto:` scheme expects a **phone number**; name fails to resolve in SMS app. | 1. Say "text mom saying hi" 2. SMS app opens with recipient "Mom" (text) not "+15551234567" 3. User must manually select contact | • Require `contact` parameter to be a **phone number** (E.164) • In RuleEngine, extract phone number via ContactsProvider lookup • Fallback: if not a number, open SMS app empty (`smsto:`) |
| **BUG-004** | **HIGH** | `ToolExecutor.kt:238-245` | **`tel:` URI uses raw contact string** — Same issue: `openDialer()` uses `Uri.parse("tel:$contact")` with display name. Fails to pre-fill dialer. | 1. Say "call mom" 2. Dialer opens with "mom" in number field 3. Call fails | • Same fix as BUG-003: require normalized phone number |
| **BUG-005** | **HIGH** | `PolicyEngine.kt:124-136` | **`runBlocking` audit write on caller thread** — `writeAudit()` is `suspend` but `evaluate()` calls it **without dispatcher shift**. If caller is on Main (e.g., UI), Room DB write blocks UI thread. `PolicyEngine` docs claim "All DAO writes are suspend calls — no main-thread work" but no `withContext(Dispatchers.IO)` is used. | 1. Trigger MEDIUM intent from UI thread 2. Add `Thread.sleep(500)` in `AuditDao.insert()` 3. Observe UI jank / ANR | Wrap `auditDao.insert()` and `policyDecisionDao.insert()` in `withContext(Dispatchers.IO)` |
| **BUG-006** | **HIGH** | `ChatViewModel.kt:26` | **`ChatMessage.id` collision risk** — `id = System.currentTimeMillis()` has **millisecond precision**. Rapid messages (e.g., quick chips, typing) can generate duplicate IDs. `LazyColumn` uses `key = { it.id }` → Compose crashes or misbehaves on duplicate keys. | 1. Tap 3 quick chips rapidly 2. Observe `IllegalArgumentException: Duplicate key` or visual glitches | Use `AtomicLong` counter or `UUID.randomUUID().toString()` for stable unique IDs |
| **BUG-007** | **MEDIUM** | `CommandGateway.kt:51-58` | **`CommandRecord.rawInput` stored without Redactor** — `CommandRecordEntity.rawInput` stores raw user text (may contain PII: phone, email, card). `Redactor` class exists but is **never used** in `CommandGateway`. Audit log writes `summary = reason` (safe), but `CommandRecord.rawInput` is unredacted PII. | 1. Send "text 555-123-4567 saying hi" 2. Query `command_record` table 3. See raw phone number in `raw_input` column | Apply `Redactor.redact()` to `rawInput` before `commandDao.insert()` |
| **BUG-008** | **MEDIUM** | `AgentDeskDatabase.kt:39` | **Destructive migration fallback present** — `fallbackToDestructiveMigration()` is used in `PersistenceModule.kt:26`. No versioned migrations exist (`version = 4`). Schema changes **wipe all user data** (notes, documents, audit log). Unacceptable for production. | 1. Bump DB version to 5 2. Run app 3. All notes, documents, audit log deleted | • Write incremental `Migration` classes for each version bump • Remove `fallbackToDestructiveMigration()` before release • Test migration path from v1 → v4 |
| **BUG-009** | **MEDIUM** | `SettingsViewModel.kt:70-78` | **`deleteAllLocalData()` not atomic; FTS triggers may not fire** — Deletes `chunks → documents → sources → shared_items → notes → audit` in **separate DAO calls** (no transaction). If crash mid-sequence, DB left partially cleared. FTS4 `text_chunk_fts` is `contentEntity = TextChunkEntity` — deleting `text_chunk` rows **should** trigger FTS sync, but Room's `DELETE` on content table may not fire FTS triggers if not in same transaction. | 1. Trigger "delete all data" 2. Kill process after `deleteAllChunks()` 3. Relaunch: notes gone, but FTS index may still have orphan entries | • Wrap all deletes in `RoomDatabase.runInTransaction()` • Verify FTS sync by querying `text_chunk_fts` after partial delete |
| **BUG-010** | **MEDIUM** | `LibraryViewModel.kt:53-57` | **WorkManager polling flow not cancelled on screen exit** — `viewModelScope.launch { importController.observeIndexState().collect { ... } }` runs indefinitely. `viewModelScope` is cancelled when `ViewModel` is cleared (navigation back), but `WorkManagerImportController.observeIndexState()` uses `flow { while (isActive) { ... delay() } }` — **`delay()` respects cancellation**, so it *should* stop. However, `WorkManager.getWorkInfosForUniqueWork(...).get()` is a **blocking call** on IO dispatcher; if cancelled during `.get()`, `CancellationException` may not propagate cleanly. | 1. Start folder import 2. Navigate away immediately 3. Observe logcat for `CancellationException` or leaked coroutine | • Use `supervisorScope` or explicit `Job` with `ensureActive()` checks • Prefer `WorkManager.getWorkInfosForUniqueWorkLiveData()` + `asFlow()` to avoid manual polling |
| **BUG-011** | **MEDIUM** | `IndexWorker.kt:127-133` | **File handle leak / partial chunks on cancellation** — `readText()` uses `input.bufferedReader().readText()` inside `use { }` (auto-close OK). However, `indexFile()` processes files **sequentially**; if worker is cancelled mid-loop (`Result.failure()`/`retry()`), already-indexed files remain in DB (no rollback). Partial chunks for the current file may be inserted before cancellation check. | 1. Start import of 50 files 2. Cancel worker after file 25 3. Observe: files 1-25 indexed, 26-50 not; file 25 may have partial chunks | • Wrap `indexFile()` in `withContext(cancellable)` + check `isStopped` per file • Use `knowledgeDao.runInTransaction()` per file for atomicity • On cancellation, delete partially indexed source |
| **BUG-012** | **MEDIUM** | `RuleEngine.kt:40-61` | **Intent ordering conflict: `open maps` vs `app.open`** — Rule `APP_OPEN` pattern `(?:open|launch|start)\s+(.+)` matches `"open maps"` before `NAVIGATE_MAP` pattern `(?:navigate|directions|take me)\s+(?:to\s+)?(.+)`. Since `RULES` list has `APP_OPEN` **before** `NAVIGATE_MAP`, `"open maps"` → `app.open` (launch Maps app) instead of `maps.navigate` (start navigation). Order-dependent. | 1. Say "open maps" 2. RuleEngine returns `app.open` with `appName="maps"` 3. AppLaunchTool launches Maps home, not navigation | • Reorder `RULES`: put `NAVIGATE_MAP` before `APP_OPEN` • Or make `APP_OPEN` pattern exclude known navigation targets • Add integration test for ambiguous phrases |
| **BUG-013** | **MEDIUM** | `ToolExecutor.kt:180-190` | **DAO call potentially on main thread** — `deviceHealth()` calls `deviceDao.latestCapabilitySnapshot()` (suspend) but `deviceHealth()` is **not** marked `suspend` — wait, it **is** `suspend` (line 155). However, `legacyRun()` is `suspend` and calls `deviceHealth()`. `runTool()` calls `legacyRun()` with `withContext`? No — `runTool` is `suspend` and calls `legacyRun()` directly. `execute()` (ToolRunner) is called from `CommandGateway.executeCommand()` which runs on `dispatcher` (IO). **OK** — but `deviceHealth()` uses `context.registerReceiver(null, ...)` which is a **main-thread blocking call** (sticky broadcast). Should run on IO. | 1. Profile `device_health` tool 2. Observe `StrictMode` violation for disk/network on main thread | Move `readBattery()` and `statFs` calls to `withContext(Dispatchers.IO)` |
| **BUG-014** | **LOW** | `LibraryViewModel.kt:74` | **`Locale.getDefault()` in `DATE_FORMAT` constant** — `SimpleDateFormat` with `Locale.getDefault()` captured at class load. If user changes locale at runtime, dates remain in old locale until process restart. Lint warns: `[ConstantLocale]`. | 1. Set device locale to English 2. Open Library → note date shows "Sep 14, 2024" 3. Change locale to French 4. Reopen Library → still "Sep 14, 2024" (not "14 sept. 2024") | Move `SimpleDateFormat` creation inside `toUi()` method (per-call) |
| **BUG-015** | **LOW** | `AuditScreen.kt:120` | Same `ConstantLocale` issue — `DATE_FORMAT` constant with `Locale.getDefault()` | Same as BUG-014 | Move `SimpleDateFormat` instantiation into `formatDate()` function |
| **BUG-016** | **LOW** | `LibraryScreen.kt` | **Missing `key` in `LazyColumn` items for notes** — `items(notes, key = { it.id })` is correct, but `NoteUi.id` is `String` (UUID). If note is updated (same ID), Compose may not recompose correctly because `NoteUi` is data class and `==` compares all fields. Use `key = { it.id }` (current) is fine but `NoteUi` should be `data class` with stable ID. | 1. Add note 2. Update note via re-insert (same ID, new body) 3. Observe if UI updates | Ensure `NoteUi` equals/hashCode based on `id` only, or use `key = { it.id }` as-is |
| **BUG-017** | **LOW** | `SettingsScreen.kt` | **Compose `remember` state leaks** — `showPinDialog`, `showDeleteDialog`, `auditExpanded` are `remember { mutableStateOf(false) }` at top level of composable. If `SettingsScreen` is recomposed (e.g., config change), state resets — **expected** but may lose user input in PIN dialog. | 1. Open Settings 2. Tap "Enable App Lock" → PIN dialog opens 3. Rotate device 3. Dialog dismissed, PIN lost | Use `rememberSaveable` for dialog state, or hoist state to ViewModel |
| **BUG-018** | **LOW** | `IndexWorker.kt:105-119` | **`collectIndexableFiles()` stack overflow risk** — Uses `ArrayDeque` + `while` loop (iterative, OK). But `current.listFiles()` may return **thousands** of files in deep hierarchies. `result.size < MAX_FILES` (50) limits processing but not traversal — still walks entire tree. | 1. Create folder with 10,000 nested dirs 2. Start import 3. Observe OOM or ANR during traversal | Add `MAX_TRAVERSAL_DEPTH` or `MAX_TOTAL_FILES_VISITED` guard; use `DocumentFile` API limits |

---

## Severity Count

| Severity | Count |
|----------|-------|
| **CRITICAL** | 2 |
| **HIGH** | 4 |
| **MEDIUM** | 6 |
| **LOW** | 6 |
| **TOTAL** | **18** |

---

## Lint Errors

**`gradlew lintDebug` → BUILD SUCCESSFUL (0 errors, 10 warnings)**

All lint errors suppressed. Warnings only (constant locale, obsolete SDK int, inlined API, etc.).

---

## Test Status

**`gradlew test` → BUILD SUCCESSFUL (51 tests passing, 0 failures)**

| Suite | Tests |
|-------|-------|
| CommandGatewayTest | 5 |
| RuleEngineTest | 14 |
| FtsQueryBuilderTest | 15 |
| NoteDaoTest | 2 |
| HealthSummaryTest | 6 |
| ConfirmationFlowTest | 4 |
| NotesListTest | 5 |
| **Total** | **51** |

---

## Recommendations Priority

1. **Immediate (Pre-Release):** BUG-001, BUG-002, BUG-003, BUG-004, BUG-005, BUG-006
2. **Sprint 1:** BUG-007, BUG-008, BUG-009, BUG-010, BUG-011, BUG-012, BUG-013
3. **Sprint 2:** BUG-014 through BUG-018 (polish/edge cases)

---

*Generated by automated review + manual code audit. All line numbers reference commit 3486101.*