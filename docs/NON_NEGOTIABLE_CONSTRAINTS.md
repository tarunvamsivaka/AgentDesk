# Non-Negotiable Constraints

These constraints are hardcoded in the application and **cannot be overridden** by configuration, feature flags, AI model responses, or future product decisions without a formal architecture review.

## Forbidden Intents (PolicyEngine.FORBIDDEN_INTENTS)

The following intent identifiers are permanently blocked in `PolicyEngine.kt`:

| Intent | Reason |
|--------|--------|
| `message.send_sms_silent` | No silent SMS sending — user must tap Send |
| `message.send_whatsapp_silent` | No silent WhatsApp messages |
| `call.place_silent` | No silent calling — user must tap Call |
| `app.install_silent` | No silent app installation |
| `file.delete_silent` | No silent file deletion |
| `accessibility.automate` | No Accessibility Service UI automation |
| `sms.read_background` | No background SMS reading |
| `call_log.read` | No call log access |
| `scrape.third_party_app` | No third-party app scraping |
| `cloud.sync_silent` | No silent cloud data uploads |

## Privacy Architecture Rules

1. **PolicyEngine always wins.** No code path may bypass `PolicyEngine.evaluate()`.
2. **Every agent decision is audited.** `AuditDao.insert()` is called on every `evaluate()` call.
3. **No cloud by default.** `data_extraction_rules.xml` and `backup_rules.xml` exclude ALL domains.
4. **No data in network logs.** `Redactor` must be used before logging any user data.
5. **Confirmation before communication.** All `MEDIUM`-risk intents (SMS draft, dialer) open system UI. The user controls the final action.
6. **No Accessibility in MVP.** The `ACCESS_ACCESSIBILITY_SERVICES` permission is not declared and must not be added without explicit approval.

## Consequences of Violation

Any PR that:
- Removes an item from `FORBIDDEN_INTENTS`
- Bypasses `PolicyEngine.evaluate()`
- Adds cloud backup/sync without explicit user opt-in
- Adds background SMS or call log reading
- Introduces Accessibility Service usage

...must be rejected during code review and will block release.
