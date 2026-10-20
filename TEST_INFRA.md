# AgentDesk Test Infrastructure Specification (`TEST_INFRA.md`)

## 1. Overview & Quality Mandate
AgentDesk is an adaptive, privacy-first, on-device AI copilot for Android with strict zero-cloud egress.
The testing infrastructure enforces high-confidence, opaque-box verification across the application's 13 modules:
- `:app` (Application host, navigation, adaptive layouts, E2E test suite)
- `:core:common`, `:core:persistence`, `:core:settings`, `:core:device`, `:core:security`
- `:agent`, `:tools`, `:knowledge`
- `:feature:onboarding`, `:feature:chat`, `:feature:library`, `:feature:health`, `:feature:settings`

All tests in this suite adhere strictly to the opaque-box paradigm: exercising user-facing entry points, public interface contracts, and observable behavior rather than internal private state.

---

## 2. 4-Tier Test Architecture

```
+-------------------------------------------------------------------+
|               Tier 4: Real-World Application Scenarios             |
|   (End-to-End User Journeys, Multi-Step Workflows, Failure States)  |
+-------------------------------------------------------------------+
                                  ^
+-------------------------------------------------------------------+
|             Tier 3: Cross-Feature Combinations                    |
|   (Pairwise Interactions: RAG + Confirm, Persistence + Audit,     |
|    Redaction + Chaining, Fallback Transitions)                    |
+-------------------------------------------------------------------+
                                  ^
+-------------------------------------------------------------------+
|             Tier 2: Boundary & Corner Cases                       |
|   (Empty/Extreme Inputs, Injection, Limits, Vector Edges,          |
|    Pruner Limits, Governor Thresholds)                            |
+-------------------------------------------------------------------+
                                  ^
+-------------------------------------------------------------------+
|             Tier 1: Feature Coverage                              |
|   (Representative Inputs for Features F01 - F25 in PROJECT.md)     |
+-------------------------------------------------------------------+
```

### Tier 1: Feature Coverage
Validates the canonical happy path and fundamental behavior of all 25 features specified in `PROJECT.md` (Phases 6–9):
- **Hardware Telemetry & AICore (F01 - F06 / Phase 6)**:
  - F01: Reactive `DeviceProfiler` CallbackFlows (`observeThermalStatus()`, `observeBatteryState()`).
  - F02: `ResourceGovernor` Battery Floor (exact 20% threshold discharging vs charging, `canIndex()`, `canRunLlm()`).
  - F03: Reactive `HealthViewModel` Collection (live telemetry flows to `HealthUiState`).
  - F04: Resource-Gated `CommandGateway` Throttling (graceful degradation to clarification chips under low battery/thermal).
  - F05: AICore Manifest Package Visibility (`<package android:name="com.google.android.aicore" />` under `<queries>`).
  - F06: Real AICore & Prompt API Integration (4-Tier Fallback Pyramid: Regex -> SLM -> LiteRT -> Clarification chips).
- **LiteRT Embeddings & Ambient Habit Engine (F07 - F14 / Phase 7)**:
  - F07: Dynamic Batching in `EmbeddingGenerator` (`generateEmbeddings(texts)` with configurable batch size).
  - F08: `LiteRtEmbeddingGenerator` Runtime (384-dimension BGE-small INT8 runtime, unit L2 normalization).
  - F09: `IndexWorker` Dynamic Batch Ingestion (adaptive batch sizing 4–16 based on device resources).
  - F10: `HybridRetriever` Dimension Safety (safeguard against vector dimension mismatches across model versions).
  - F11: `UserHabitEntity` & `UserHabitDao` Schema (Room v5->v6 migration, hourly frequency queries).
  - F12: `UsageStatsProvider` & AppOps Guard (`UsageStatsManager` permission checks and offline zero-egress fallback).
  - F13: `HabitPredictionEngine` Intent Boosting (contextual time-of-day habit weighting and suggestion stream).
  - F14: `AmbientHabitWorker` Background Routine (battery-neutral background routine constraints).
- **Automated Zero-Cloud Egress Sandbox & Performance (F15 - F19 / Phase 8)**:
  - F15: Manifest INTERNET Stripping Hardening (`tools:node="remove"` on `android.permission.INTERNET`).
  - F16: NetworkSecurityConfig Defense-in-Depth (`res/xml/network_security_config.xml` zero cleartext and empty trust anchors).
  - F17: Automated `ZeroCloudEgressSandbox` (socket interception and assertion of 0 outbound bytes).
  - F18: Macrobenchmark & Profiling Harness (`docs/DEVICE_BENCHMARK_PROTOCOL.md` cold startup and command latency budgets).
  - F19: Rotation & Configuration Stress Suite (ViewModel state preservation and zero duplicate message IDs across recreation).
- **Release Engineering & Store Readiness (F20 - F25 / Phase 9 & Verification)**:
  - F20: R8 Optimization Mode Activation (`proguard-android-optimize.txt` activation in release build).
  - F21: Decentralized Module Consumer ProGuard (keep rules across `:core:persistence`, `:knowledge`, `:agent`, `:core:device`).
  - F22: Automated 16KB Page Alignment Verification (`p_align >= 16384` ELF verification on native binaries).
  - F23: Google Play Data Safety Specification (`docs/GOOGLE_PLAY_DATA_SAFETY.md` proving zero off-device collection/sharing).
  - F24: Release Signing & Artifact Assembly (`assembleRelease`, `bundleRelease` release build readiness).
  - F25: Full Dual-Track Verification & Audit Gate (100% test pass rate and HMAC-SHA256 audit gate validation).

### Tier 2: Boundary & Corner Cases
Tests edge conditions, extreme parameters, and invalid states:
- Empty, whitespace-only, and ultra-short command inputs.
- Massive payloads (> 10,000 characters) exceeding typical input buffers.
- SQL / FTS4 metacharacter injection and format-string handling.
- Exact battery boundaries: 19% vs 20% discharging vs charging.
- Exact internal storage floor: 499MB (rejected) vs 500MB (allowed).
- Dynamic batching boundaries: 0 items, 1 item, exact batch size (16 items), multi-batch overflow (25 items).
- Prompt prefix token count boundary (599 vs 600+ tokens).
- Vector geometry edge cases (identical, opposite, orthogonal, zero-vector).
- Semantic chunking text boundaries (empty, short, no-punctuation, continuous).
- Conversation context pruning turn boundaries (empty, exact 12 messages, 14 messages pruned to 12).

### Tier 3: Cross-Feature Combinations
Tests pairwise interactions across subsystems:
- **Telemetry + Habit Engine + Command Gateway**: Battery-governed habit suggestion execution.
- **LiteRT Embeddings + Dynamic Batching + Room BLOB Persistence**: Batch vectorization and SQLite BLOB storage.
- **RAG + Tool Execution**: Knowledge retrieval providing context for tool execution.
- **Chat Persistence + Monotonic IDs + Audit Logging**: Multi-turn dialogue execution with HMAC-signed audit logs.
- **Confirmation Preview + User Cancellation**: Medium-risk command triggers confirmation prompt; cancellation logged.
- **PolicyEngine + Forbidden Intent Attempt**: Blocked intent verified with `DENIED` audit receipt.
- **Privacy Redaction + Cryptographic Chaining**: PII redaction preserves cryptographic audit chain integrity.
- **Fallback Pyramid Transition**: Fast-path regex to on-device SLM to clarification chips.

### Tier 4: Real-World Application Scenarios
Full end-to-end user workflows simulating production scenarios:
1. **Daily Personal Productivity**: Note creation -> library search -> alarm setting -> app launch -> health check.
2. **Sensitive Communication Safety**: SMS draft -> PolicyEngine classifies MEDIUM risk -> transparent confirmation -> native intent launch -> audit chaining.
3. **Malicious Exfiltration Resistance**: Injection attempting silent sync/SMS exfiltration -> blocked by PolicyEngine -> zero cloud egress verified -> audit captured.
4. **Offline Knowledge Ingestion & Hybrid Retrieval**: Document parsed -> chunked -> vectorized -> hybrid RRF search returns relevant passage without network.
5. **Low-Resource & Thermal Governance Recovery**: Low storage (< 500MB) or thermal surge suspends AI -> cools/recovers -> resumes execution.
6. **Low-Battery AI Degradation & Recovery**: Battery drops below 20% -> LLM throttles to clarification chips while regex fast path executes -> charger connected -> full SLM execution resumes.
7. **Zero-Cloud Ambient Routine**: Evening habit triggered -> ambient routine executed within `ZeroCloudEgressSandbox` -> 0 outbound network bytes -> cryptographic audit verified.

---

## 3. Test Runner & Execution Commands

### Primary Test Runner
The E2E test suite executes on the host JVM via Gradle:
```bash
# Run the entire E2E test suite
./gradlew :app:testDebugUnitTest

# Run specific E2E test tiers
./gradlew :app:testDebugUnitTest --tests "com.agentdesk.app.e2e.Tier1FeatureCoverageTest"
./gradlew :app:testDebugUnitTest --tests "com.agentdesk.app.e2e.Tier2BoundaryCornerCaseTest"
./gradlew :app:testDebugUnitTest --tests "com.agentdesk.app.e2e.Tier3CrossFeatureCombinationTest"
./gradlew :app:testDebugUnitTest --tests "com.agentdesk.app.e2e.Tier4RealWorldScenarioTest"

# Run full project test verification across all 13 modules
./gradlew testDebugUnitTest
```

### Performance & Latency Budgets
- **Fast-path Regex**: < 5 ms
- **Hybrid Retrieval (RRF FTS4 + Vector)**: < 100 ms
- **HMAC-SHA256 Receipt Generation**: < 2 ms
- **Total E2E Test Suite Execution**: < 30 seconds

---

## 4. Quality Thresholds & Invariant Gates

| Gate | Requirement | Enforcement |
|------|-------------|-------------|
| **Test Success** | 100% pass rate, 0 failures, 0 errors | `./gradlew :app:testDebugUnitTest` |
| **Cloud Egress** | 0 active `android.permission.INTERNET` declarations | `Tier1FeatureCoverageTest.testZeroCloudEgressManifestCheck` |
| **Policy Invariant** | 100% rejection of 10 `FORBIDDEN_INTENTS` | `Tier1FeatureCoverageTest.testForbiddenIntentsAlwaysRejected` |
| **Audit Chaining** | 100% mathematically valid HMAC-SHA256 receipts | `Tier1FeatureCoverageTest.testSequentialHmacSha256AuditChainIntegrity` |
| **Thread Hygiene** | 0 Room writes or heavy I/O on `Dispatchers.Main` | `Tier1FeatureCoverageTest.testDispatcherHygieneNoMainThreadIo` |
| **Lint Gate** | 0 fatal errors | `./gradlew lintDebug` |

---

## 5. Source Code Layout

```
c:\AgentDesk\app\src\test\java\com\agentdesk\app\e2e\
├── Tier1FeatureCoverageTest.kt        # Features F01-F22 coverage
├── Tier2BoundaryCornerCaseTest.kt      # Boundary, limits & negative testing
├── Tier3CrossFeatureCombinationTest.kt # Pairwise cross-subsystem interactions
├── Tier4RealWorldScenarioTest.kt       # End-to-end user workflows
└── harnesses\                          # Test fakes, builders & fixtures
    ├── E2ETestHarness.kt
    └── MockAiCoreHarness.kt
```
