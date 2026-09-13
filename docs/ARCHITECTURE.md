# Architecture — AgentDesk

## Overview

AgentDesk uses a **strict layered, modular architecture** with a one-way dependency graph.
Each layer may only depend on layers below it. No circular dependencies are permitted.

```
┌─────────────────────────────────┐
│          :app                   │  Shell, navigation, theme
├─────────────────────────────────┤
│   :feature:*  (5 modules)       │  Compose screens + ViewModels
├────────────┬────────────────────┤
│  :agent    │  :tools            │  Command pipeline │ Tool dispatch
│  :knowledge│                    │  Document indexing│
├────────────┴────────────────────┤
│         :core:*  (5 modules)    │  Shared infrastructure
└─────────────────────────────────┘
```

## Module Dependency Graph

| Module | Depends On |
|--------|-----------|
| `:core:common` | — |
| `:core:persistence` | `:core:common` |
| `:core:settings` | `:core:common`, `:core:persistence` |
| `:core:device` | `:core:common`, `:core:persistence` |
| `:core:security` | `:core:common`, `:core:persistence` |
| `:agent` | `:core:common`, `:core:persistence`, `:core:settings` |
| `:tools` | `:core:common`, `:core:persistence`, `:core:settings` |
| `:knowledge` | `:core:common`, `:core:persistence`, `:core:device` |
| `:feature:onboarding` | `:core:common`, `:core:persistence`, `:core:settings`, `:core:device` |
| `:feature:chat` | `:core:common`, `:core:persistence`, `:agent`, `:tools` |
| `:feature:library` | `:core:common`, `:knowledge` |
| `:feature:health` | `:core:common`, `:core:device` |
| `:feature:settings` | `:core:common`, `:core:settings`, `:core:security`, `:core:persistence` |
| `:app` | all of the above |

## Command Pipeline

```
User Input (ChatScreen)
     │
     ▼
CommandGateway.process()
     │
     ├─▶ RuleEngine.evaluate()
     │        └─ Regex + keyword matching
     │           Returns RuleResult(intentName, entities, confidence)
     │
     ├─▶ PolicyEngine.evaluate()           ← ALWAYS called, ALWAYS wins
     │        ├─ Forbidden intent? → Rejected
     │        ├─ HIGH risk? → NeedsConfirmation
     │        ├─ MEDIUM risk? → NeedsConfirmation (opens system UI)
     │        └─ LOW risk? → Accepted
     │        └─ Writes to AuditLog (always)
     │
     └─▶ CommandDao.insert()               ← Every command persisted
              └─ Returns CommandResult to ChatViewModel
```

## Tool Dispatch

```
CommandResult.Accepted / NeedsConfirmation (confirmed)
     │
     ▼
ToolExecutor.execute(ToolRequest)
     │
     ├─ Maps toolId → Android Intent
     │     AlarmClock, ACTION_SENDTO, ACTION_DIAL, ACTION_INSERT,
     │     ACTION_VIEW, getLaunchIntentForPackage, geo:
     │
     ├─ Writes audit entry
     └─ Returns ToolResult (Success | Error | Cancelled)
```

## Key Design Decisions

### 1. PolicyEngine is deterministic, not AI
The PolicyEngine uses a hardcoded `Set<String>` of forbidden intents and risk mappings. No LLM is involved. This guarantees predictable, auditable behavior.

### 2. RuleEngine is regex-only (MVP)
Intent classification uses precompiled `Regex` patterns with confidence scores. No embedding models or cloud NLP in MVP.

### 3. All sensitive actions open system UI
No tool executes silently. Communication tools (SMS draft, dialer) open Android system UI. The user must manually tap Send/Call.

### 4. FTS4 for local search
`TextChunkFtsEntity` uses Room FTS4 (`@Fts4(contentEntity = TextChunkEntity::class)`) for fast private full-text search on indexed documents.

### 5. No cloud backup
`data_extraction_rules.xml` and `backup_rules.xml` exclude all domains. Cloud sync is architecturally disabled and has no UI toggle in MVP.

### 6. Hilt + WorkManager
`AgentDeskApplication` implements `Configuration.Provider` and injects `HiltWorkerFactory`. The default `WorkManagerInitializer` is removed via `tools:node="remove"` in the manifest.

## Data Flow Diagram

```
User → ChatScreen → ChatViewModel
                         │ CommandGateway
                         │   RuleEngine ──► regex match
                         │   PolicyEngine ─► audit + decision
                         │   CommandDao ──► persist
                         │
                         ▼
                    ToolExecutor
                         │ Android Intent
                         ▼
                    System App (Alarm, SMS, Dialer, Calendar, Maps…)
```
