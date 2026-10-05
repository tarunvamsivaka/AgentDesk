# AgentDesk Project Rules & Architecture Guardrails

## 1. Core Mission & Privacy First
AgentDesk is an adaptive, privacy-first, on-device AI copilot for Android.
- **Zero Cloud Egress**: Cloud sync is architecturally disabled. Do NOT introduce network calls, third-party telemetry, or cloud data egress for user documents, notes, health metrics, or chat logs.
- **Privacy Architecture**: All data must remain local in Room DB (`AgentDeskDatabase`) or DataStore.

## 2. Policy Engine & Safety Constraints
- **PolicyEngine Always Wins**: Every command must pass through `PolicyEngine.evaluate()`. No code path may bypass this gate.
- **Forbidden Intents**: The `PolicyEngine.FORBIDDEN_INTENTS` set is immutable and permanently blocked:
  - `message.send_sms_silent`
  - `message.send_whatsapp_silent`
  - `call.place_silent`
  - `app.install_silent`
  - `file.delete_silent`
  - `accessibility.automate`
  - `sms.read_background`
  - `call_log.read`
  - `scrape.third_party_app`
  - `cloud.sync_silent`
- **Transparent System UI for Sensitive Actions**: All communication actions (SMS, phone call) must launch the native Android system intent (e.g., `smsto:`, `tel:`), requiring the user to manually review and tap Send/Call. Never perform background silent executions.
- **Mandatory Audit Logging**: Every agent decision and tool execution must be recorded to `AuditDao`.

## 3. Engineering & Architecture Guidelines
- **Layered Architecture**: Strict one-way dependencies: `:app` -> `:feature:*` -> `:agent` / `:tools` / `:knowledge` -> `:core:*`. Circular dependencies are strictly prohibited.
- **Coroutines & Thread Safety**: All Room DAO writes and heavy I/O (StatFs, battery broadcast receivers, document parsing) must run on `Dispatchers.IO` (`withContext(ioDispatcher)`). Never perform blocking work on the Android Main thread.
- **Cryptographic Security**: Passwords/PINs must use cryptographically secure hashing (PBKDF2 with 10,000+ iterations and random salt) and constant-time equality checks (`MessageDigest.isEqual`). Never use `String.hashCode()`.
- **Jetpack Compose Best Practices**:
  - Always use stable keys in `LazyColumn` (`key = { it.id }`).
  - Hoist state to ViewModels or use `rememberSaveable` to survive configuration changes.
  - Follow Material 3 design guidelines.
- **Data Protection & Redaction**: When logging debug messages or recording command history, pass inputs through `Redactor` to strip phone numbers, email addresses, credit cards, and auth tokens.
- **Testing Standard**: Every new intent, rule, or policy change must be covered by unit tests (`RuleEngineTest`, `CommandGatewayTest`, `PolicyEngineTest`).
