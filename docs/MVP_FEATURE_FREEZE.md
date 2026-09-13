# MVP Feature Freeze

This document defines what is IN and OUT of scope for the AgentDesk MVP release.

## ✅ IN SCOPE (MVP)

### Natural Language Commands
- Set alarm at a specified time
- Set countdown timer (minutes/seconds/hours)
- Create a note (opens share sheet for user to save)
- Open an installed app by name
- Create a calendar event (opens Calendar app)
- Draft an SMS to a contact (opens SMS app, user taps Send)
- Open dialer with a contact pre-filled (user taps Call)
- Search the local knowledge library
- Open browser with a web search query
- Navigate to a destination (opens Google Maps)
- View device health summary

### Chat Interface
- Message list with user/agent bubbles
- Quick-action chip suggestions
- Typing indicator
- Confirmation dialog for MEDIUM-risk actions

### Onboarding
- Single-screen privacy consent
- Device tier detection (LOW/MEDIUM/HIGH)
- Navigate to Chat on completion

### Library
- Placeholder screen with import affordance
- Local document indexing schema (Room + FTS4)

### Device Health
- Battery, storage, RAM placeholder cards
- Live metrics wiring: post-MVP

### Settings
- Consent Dashboard (placeholder navigation)
- Permission Center (placeholder navigation)
- Audit Log (placeholder navigation)
- App Lock (placeholder navigation)
- Cloud Sync: permanently shown as DISABLED

### Infrastructure
- Hilt DI across all modules
- Room database with 25 entities and 8 DAOs
- FTS4 full-text search
- WorkManager + Hilt worker factory
- DataStore preferences
- Audit log for every agent decision

## ❌ OUT OF SCOPE (Post-MVP)

| Feature | Reason |
|---------|--------|
| On-device LLM (Gemini Nano) | Binary size + device tier gate required |
| Silent SMS/call | Forbidden by policy engine — never allowed |
| Accessibility automation | Forbidden — security risk |
| SMS background reading | Forbidden — privacy violation |
| Cloud sync | Disabled by design — post-MVP opt-in only |
| Destructive file deletion | Forbidden without explicit confirmation |
| Document indexing pipeline | Worker + chunking pipeline: post-MVP |
| Live device metrics | DeviceProfiler wiring: post-MVP |
| Biometric app lock UI | AppLockManager built; UI wire-up: post-MVP |
| Voice input | Requires microphone permission gate + UX design |
| Widget / notification AI | Post-MVP |
| Multi-turn conversation memory | Post-MVP |
| App-to-app automation | Forbidden in MVP |
