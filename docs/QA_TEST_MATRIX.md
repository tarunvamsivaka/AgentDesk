# QA Test Matrix

## Scope
MVP acceptance test suite for AgentDesk. All tests must pass before any beta release.

## Command Pipeline Tests

| ID | Test Case | Input | Expected Result | Pass Criteria |
|----|-----------|-------|-----------------|---------------|
| CMD-01 | Set alarm — full match | "Set alarm at 7am" | `CommandResult.Accepted`, intent=`system.set_alarm`, entity time=`7am` | Alarm app opens |
| CMD-02 | Set timer | "Set timer for 5 minutes" | `CommandResult.Accepted`, intent=`system.set_timer` | Timer app opens with 300s |
| CMD-03 | Open app | "Open Maps" | `CommandResult.Accepted`, intent=`app.open` | Maps launches |
| CMD-04 | Draft SMS — confirmation required | "Text John saying hello" | `CommandResult.NeedsConfirmation` | Dialog shown; SMS app opens on confirm |
| CMD-05 | Dial — confirmation required | "Call Mom" | `CommandResult.NeedsConfirmation` | Dialog shown; dialer opens on confirm |
| CMD-06 | Unknown intent | "blah blah nonsense" | `CommandResult.Unknown` | Friendly fallback message |
| CMD-07 | Navigate | "Navigate to airport" | `CommandResult.Accepted`, intent=`maps.navigate` | Maps opens with destination |
| CMD-08 | Calendar event | "Create a meeting tomorrow" | `CommandResult.Accepted`, intent=`calendar.create_event` | Calendar app opens |

## Policy Engine Tests

| ID | Test Case | Intent | Expected Decision | Audit Entry |
|----|-----------|--------|-------------------|-------------|
| POL-01 | Forbidden intent blocked | `message.send_sms_silent` | `CommandResult.Rejected` | DENIED written |
| POL-02 | Forbidden intent blocked | `accessibility.automate` | `CommandResult.Rejected` | DENIED written |
| POL-03 | Forbidden intent blocked | `call.place_silent` | `CommandResult.Rejected` | DENIED written |
| POL-04 | Medium risk requires confirmation | `message.draft_sms` | `CommandResult.NeedsConfirmation` | ACCEPTED_WITH_CONFIRMATION |
| POL-05 | Low risk accepted directly | `system.set_alarm` | `CommandResult.Accepted` | ACCEPTED |
| POL-06 | Every evaluation audited | any intent | AuditEventEntity inserted | Row present in DB |

## Privacy / Security Tests

| ID | Test Case | Expected Behaviour |
|----|-----------|-------------------|
| SEC-01 | No outbound network traffic | Charles Proxy: 0 requests to non-local hosts |
| SEC-02 | Cloud backup disabled | `adb backup` produces empty archive |
| SEC-03 | Audit log persists across restart | Kill app, reopen, audit log intact |
| SEC-04 | Data extraction rules exclude all domains | Verify `data_extraction_rules.xml` with `adb` |
| SEC-05 | No Accessibility permission declared | `adb shell dumpsys package com.agentdesk.app` shows no accessibility |

## UI / UX Tests

| ID | Screen | Test Case | Pass Criteria |
|----|--------|-----------|---------------|
| UI-01 | Onboarding | Screen appears on first launch | Privacy card visible |
| UI-02 | Onboarding | "Get Started" navigates to Chat | Chat screen visible, back stack cleared |
| UI-03 | Chat | Send message shows user bubble | Bubble aligned right |
| UI-04 | Chat | Agent response shows agent bubble | Bubble aligned left |
| UI-05 | Chat | Typing indicator while processing | Spinner visible |
| UI-06 | Chat | Confirmation dialog for SMS draft | AlertDialog shown with Yes/Cancel |
| UI-07 | Chat | Cancel confirmation → "Action cancelled" | Message appended |
| UI-08 | Library | Import button visible | Button rendered |
| UI-09 | Health | Three metric cards visible | Battery, Storage, RAM cards |
| UI-10 | Settings | Cloud Sync shown as disabled | CloudOff icon, no toggle |

## Regression Tests (Non-Negotiable)

| ID | Test | Must Never Happen |
|----|------|--------------------|
| REG-01 | Silent SMS | No SMS sent without user tapping Send in native app |
| REG-02 | Silent call | No call placed without user tapping Call in native app |
| REG-03 | Silent app install | No APK installation without Play/Package Installer UI |
| REG-04 | File deletion | No file deleted without explicit user confirmation |
| REG-05 | Accessibility usage | No `AccessibilityService` bound or used |
| REG-06 | Background SMS read | No `READ_SMS` permission used |
| REG-07 | Cloud upload | No data uploaded to any remote host |
