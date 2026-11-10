# Sentinel Security Journal

## 2026-03-30 - URL-Encoded Path Traversal in IngestSecurityValidator
**Vulnerability:** `IngestSecurityValidator` checked incoming URIs for traversal via raw string matching (`path.contains("..")`) and blacklisted private directory keywords (`/databases`, `/shared_prefs`). Attackers could supply URL-encoded sequences (`%2e%2e`, `%2E%2E`, `%252e%252e`, `%2fdatabases`) in raw or double-encoded format to bypass string containment filters before reaching downstream content handlers.
**Learning:** Checking raw URI paths or partially decoded URI strings without recursive normalization and decoding creates a confused-deputy or path-traversal bypass vector, especially when receiving raw URIs via intents (`dataString` or string extras) where Android components or ContentResolvers may resolve decoded paths downstream.
**Prevention:** Iteratively normalize backslashes and decode URL-encoded segments up to a safe bound prior to evaluating traversal dot-dots (`..`) or private directory roots (`/databases`, `/shared_prefs`, `dataDir`).
