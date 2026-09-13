# AgentDesk

> **Your private, local-first copilot for files, notes, actions, and device health.**

AgentDesk is a privacy-first, adaptive, on-device AI assistant for Android. It helps users search personal files, complete everyday phone actions, draft communications, and understand device health through natural language commands — entirely on-device, with no data leaving the device by default.

## Features (MVP)

| Feature | Description |
|---------|-------------|
| 🤖 Natural Language Commands | Set alarms, timers, notes, navigate, draft SMS — via typed commands |
| 🔒 Policy Engine | Hardcoded rule-based safety gate that blocks all forbidden actions |
| 📁 Library | Import and locally index personal documents for private search |
| 💊 Device Health | Battery, storage, and memory overview |
| ⚙️ Settings | Consent dashboard, permission center, audit log, app lock |

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| Min SDK | 29 (Android 10) |
| UI | Jetpack Compose + Material 3 |
| Architecture | Modular Android + MVVM |
| DI | Hilt 2.51.1 |
| Local DB | Room 2.6.1 + FTS4 |
| Background Work | WorkManager 2.9.0 |
| Async | Coroutines + Flow |
| Serialization | Kotlinx Serialization |
| Build | Gradle Kotlin DSL + libs.versions.toml |

## Project Structure

```
AgentDesk/
├── app/                      # Application shell, navigation, theme
├── core/
│   ├── common/               # Shared enums and models
│   ├── persistence/          # Room DB, DAOs, entities, FTS
│   ├── settings/             # DataStore preferences, consent keys
│   ├── device/               # Device profiling, resource governance
│   └── security/             # App lock, data redaction
├── agent/                    # Command pipeline: RuleEngine → PolicyEngine → CommandGateway
├── tools/                    # Tool registry + ToolExecutor (Android Intents only)
├── knowledge/                # Local document indexing (post-MVP)
├── feature/
│   ├── onboarding/           # First-run privacy consent screen
│   ├── chat/                 # Main chat UI + ChatViewModel
│   ├── library/              # Document library screen
│   ├── health/               # Device health overview
│   └── settings/             # Consent, permissions, audit log, app lock
└── docs/                     # Architecture docs, QA, benchmarks
```

## Non-Negotiable Privacy Constraints

- ❌ No silent SMS sending
- ❌ No silent calling
- ❌ No silent app installation
- ❌ No destructive file deletion without confirmation
- ❌ No Accessibility-based UI automation
- ❌ No SMS background reading
- ✅ Cloud sync disabled by default (no toggle in MVP)
- ✅ Every agent action is written to the local Audit Log

## Getting Started

```bash
# 1. Clone the repo
git clone https://github.com/your-org/AgentDesk.git

# 2. Open in Android Studio Ladybug or newer

# 3. Build debug APK
./gradlew assembleDebug

# 4. Install on device
./gradlew installDebug
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [MVP Feature Freeze](docs/MVP_FEATURE_FREEZE.md)
- [Non-Negotiable Constraints](docs/NON_NEGOTIABLE_CONSTRAINTS.md)
- [QA Test Matrix](docs/QA_TEST_MATRIX.md)
- [Device Benchmark Protocol](docs/DEVICE_BENCHMARK_PROTOCOL.md)
