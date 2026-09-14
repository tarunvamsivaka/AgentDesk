# AgentDesk — Project Walkthrough

A running history of the AgentDesk build batches, with scope, tests, and commit hashes.

## v0.1 — MVP Skeleton (commit `ace8369`)

- Full modular skeleton: `:app`, 5×`:feature:*`, `:agent`, `:tools`, `:knowledge`, 5×`:core:*`
- Room database (25 entities, 8 DAOs) + FTS4 full-text search schema
- RuleEngine (regex-only intent classifier), PolicyEngine (hardcoded forbidden intents + risk gates)
- Compose screens: Onboarding (privacy consent + device tier scan), Chat, Library, Health, Settings (placeholders)
- Hilt DI across all modules, WorkManager + Hilt worker factory, DataStore preferences
- **Tests: 27 passing** (RuleEngineTest 14, FtsQueryBuilderTest 15 minus batch-2 additions)

## Batch 2 — Command Pipeline Wiring (commit `0fe744a`)

- End-to-end flow: ChatViewModel → CommandGateway → RuleEngine → PolicyEngine → ToolExecutor
- `CommandGateway.submitText()` — CommandRecord persisted (RECEIVED), intent recognized,
  policy evaluated (always wins, always audited), tool dispatched, status/latency updated
- Concrete tools: `AlarmTool` (AlarmClock intent with hour/minute), `NoteTool` (Room write),
  `AppLaunchTool` (PackageManager launch)
- Audit: one `AuditEvent` per policy decision + one per tool invocation
- `CommandGatewayTest` (5 tests) verifies alarm/note/app-launch end-to-end, the error path,
  and that unknown input is never executed
- **Tests: 34 passing** (CommandGatewayTest 5, RuleEngineTest 14, FtsQueryBuilderTest 15)

## Batch 3 — Real Notes, Real FTS Search, SAF Import, Time Parsing (commit `0cddd63`)

- `NoteEntity`/`NoteDao` reworked (String PK, title, body, createdAt); DB version 3 → 4 with
  `fallbackToDestructiveMigration()` (dev stage)
- `ToolExecutor.createNote()` — parses `text`, derives a title from the first line (≤30 chars),
  persists via NoteDao, returns "Note saved: '<title>'"
- `ToolExecutor.librarySearch()` — real FTS4 lookup via `FtsSearchDao.safeSearch()`,
  "Found N results" / "No local documents match" formatting; navigation stub removed
- `ToolExecutor.setAlarm()` + `parseTimeToHourMinute()` — parses "7am", "7:30 pm", "14:00"
  into 24-hour time; graceful fallback (opens alarm app) on unparseable input
- `IndexWorker` (Hilt `CoroutineWorker`) — SAF tree walk, first 50 `.txt`/`.md` files,
  500-char chunks → `TextChunkEntity` (FTS4 auto-syncs), >500MB free-space check
- `LibraryScreen` wired to `ActivityResultContracts.OpenDocumentTree()` with
  "Indexing…" / "Library ready" states
- `NoteDaoTest` (2 tests: insert + observe)
- **Tests: 36 passing**

## Batch 4 — Notes UI, Real Device Health, Audit/Settings UI, Confirmation Persistence (this commit)

- **Notes UI in Library**: notes section listing `NoteDao.observeAll()` — title, first 80
  chars, formatted date; tap expands inline to the full body; empty state
  "No notes yet. Try: note buy milk"
- **Real device health**: `HealthViewModel` (DeviceProfiler + BatteryManager + latest
  DeviceCapabilitySnapshotEntity tier); HealthScreen cards for Storage (progress bar),
  Battery, Device Tier badge; `ToolExecutor.device_health` returns a real summary
  ("Storage 87% used (1.2 GB free), battery 78% charging, tier BALANCED")
- **Real audit/settings UI**: `AuditViewModel` (recent events + rejected policy decisions),
  `AuditScreen` lazy list; `SettingsScreen` with real App Lock toggle (PIN via
  AppLockManager), Consent switches persisted via ConsentDao, and "Delete all local data"
  behind a REQUIRED confirmation dialog (clears notes, documents, chunks, sources,
  shared items, audit)
- **Confirmation persistence**: `ChatViewModel` writes a `ConfirmationRecordEntity` on
  NeedsConfirmation (PROMPTED: `user_confirmed = null`), records APPROVED on confirm and
  REJECTED on dismiss — using existing columns, no schema change needed
- New tests: `NotesListTest` (5), `HealthSummaryTest` (6), `ConfirmationFlowTest` (4)
- **Tests: 51 passing**

## Non-Negotiables Maintained Throughout

- PolicyEngine always wins; every agent decision audited
- No silent SMS/call/install; all communication opens system UI for the user to confirm
- No Accessibility service usage; no new manifest permissions since v0.1
- Cloud sync permanently disabled; no cloud egress code paths
- Destructive "delete all local data" requires an explicit confirmation dialog
