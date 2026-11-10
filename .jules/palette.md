# Palette's UX & Accessibility Journal

## 2026-10-21 - Clear Action Trailing Icon Accessibility Pattern in Compose TextFields
**Learning:** In Compose `OutlinedTextField` or `TextField`, clear buttons embedded as trailing icons must provide explicit `contentDescription = "Clear input"` and be conditionally displayed only when text is non-empty and the field is enabled. Without conditional gating, assistive tech encounters inert or misleading clear controls.
**Action:** When implementing clearable text inputs across chat and search screens, always tie `trailingIcon` to `if (text.isNotEmpty() && isEnabled)` and supply an explicit localized action description for screen readers.
