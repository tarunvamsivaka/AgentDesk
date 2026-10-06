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
|   (Representative Inputs for Features F01 - F22 in PROJECT.md)     |
+-------------------------------------------------------------------+
```

### Tier 1: Feature Coverage
Validates the canonical happy path and fundamental behavior of all features specified in `PROJECT.md`:
- **Adaptive UI & Modern Theme (F01 - F05)**:
  - F01: NavigationSuiteScaffold adaptive layout configuration across compact (phone) and expanded (tablet) viewports.
  - F02: Predictive Back gesture handling (`enableOnBackInvokedCallback="true"`, back-invoked callback registration).
  - F03: Wispr Flow dark developer console theme (#0F172A background, #1E293B surface, #22C55E accent, #F8FAFC text, #475569 border).
  - F04: WCAG 2.2 AA accessibility requirements (minimum 44x44dp touch targets, visible focus rings, edge-to-edge system bar and IME insets).
  - F05: SupportingPaneScaffold dual-pane layout logic on expanded/tablet screens.
- **Core Chat & Tool Execution (F06 - F10)**:
  - F06: Conversation thread and message entity schemas and foreign key cascade rules.
  - F07: Monotonic message ID ordering and state restoration.
  - F08: Conversation context pruner bounded to the last 6 turns (12 messages, ~2,048 tokens).
  - F09: Deterministic resolution of all 11 core capabilities: alarms, timers, calendar, notes, dialer, SMS draft, maps, web search, app launch, library search, device health.
  - F10: Confirmation parameter preview cards with dismiss audit logging.
- **Local Vector RAG & Hybrid Retrieval (F11 - F14)**:
  - F11: FastVectorMath pure Kotlin IEEE-754 vector dot products, vector normalization, and cosine similarity.
  - F12: Room BLOB byte buffer serialization/deserialization for embedding vectors.
  - F13: Semantic recursive chunker preserving sentence/paragraph boundaries with sliding-window overlap.
  - F14: Fused SQLite FTS4 BM25 and dense vector search via Reciprocal Rank Fusion (RRF, k=60) with < 100ms SLA.
- **Hybrid Intent Engine & Gemini Nano (F15 - F18)**:
  - F15: ML Kit Prompt API & `@Generable` structured output schema (`ClassifiedIntent`).
  - F16: Prompt prefix caching (> 600 tokens static persona and tool catalogue).
  - F17: Deterministic 4-tier fallback pyramid: Regex (< 5ms) -> Gemini Nano (~500ms) -> LiteRT -> FTS4 Clarification chips.
  - F18: Universal Mock AICore test harness for CI and emulator execution.
- **Non-Negotiable Invariants (F19 - F22)**:
  - F19: Zero Cloud Egress invariant (0 `android.permission.INTERNET`, no HTTP libraries, socket blocking).
  - F20: PolicyEngine absolute veto authority over all 10 `FORBIDDEN_INTENTS`.
  - F21: Sequential HMAC-SHA256 audit digest chaining and mathematical proof verification.
  - F22: Dispatchers.IO thread safety enforcement (0 Room DAO writes or heavy I/O on Dispatchers.Main).

### Tier 2: Boundary & Corner Cases
Tests edge conditions, extreme parameters, and invalid states:
- Empty, whitespace-only, and ultra-short command inputs.
- Massive payloads (> 10,000 characters) exceeding typical input buffers.
- Format strings, SQL injection patterns, FTS4 query meta-characters, and null bytes.
- Prompt prefix token length boundary (below 600 vs at/above 600 tokens).
- Vector geometry edge cases: identical parallel vectors (similarity 1.0), orthogonal vectors (similarity 0.0), diametrically opposed vectors (similarity -1.0), and zero-vector guard handling.
- Semantic chunking boundaries: empty document, single-character document, documents without punctuation, huge continuous text blocks without whitespace.
- Conversation pruning boundaries: empty history, exactly 6 turns (12 messages), 7+ turns (14 messages pruned to 12), and long message truncation.
- Resource governor boundaries: internal storage < 500MB safety threshold, low memory conditions, and thermal status >= SEVERE.

### Tier 3: Cross-Feature Combinations
Tests pairwise interactions across subsystems:
- **RAG + Tool Execution**: Knowledge retrieval providing context for note creation or SMS drafting.
- **Chat Persistence + Monotonic IDs + Audit Logging**: Multi-turn dialogue execution where each message receives a monotonic ID and generates an HMAC-signed audit entry.
- **Confirmation Preview + User Cancellation**: Medium-risk command triggers confirmation prompt; user rejects/dismisses; `USER_CANCELLED` audit receipt is logged with no tool execution.
- **PolicyEngine + Forbidden Intent Attempt**: Malicious or unauthorized intent evaluation rejected with `DENIED` audit receipt and `CommandOutcome.Rejected`.
- **Privacy Redaction + Cryptographic Chaining**: User input containing PII (phone, email, credentials) is redacted prior to persistence while preserving cryptographic audit chain integrity.
- **Fallback Pyramid Transition**: Fast-path regex miss smoothly routes to secondary SLM classifier, evaluates PolicyEngine, and dispatches tool.

### Tier 4: Real-World Application Scenarios
Full end-to-end user workflows simulating production scenarios:
1. **Daily Personal Productivity**: Create a note -> search knowledge library -> set an alarm -> open an application -> check device health.
2. **Sensitive Communication Safety**: Request SMS draft -> PolicyEngine classifies MEDIUM risk -> UI prompts confirmation preview -> User reviews parameters -> User confirms -> System launches native SMS UI -> Audit receipt chained.
3. **Malicious Exfiltration Resistance**: Attacker attempts silent background sync or exfiltration -> PolicyEngine deterministically blocks -> Zero Cloud Egress verified -> Audit log captures attempt.
4. **Offline Knowledge Ingestion & Hybrid Retrieval**: Document parsed -> chunked with sliding window -> vector serialized -> hybrid RRF search returns relevant passage without internet access.
5. **Resource Governance Throttling & Recovery**: Low storage (< 500MB) or thermal surge suspends background AI/indexing -> resource recovers -> operations resume safely.

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
