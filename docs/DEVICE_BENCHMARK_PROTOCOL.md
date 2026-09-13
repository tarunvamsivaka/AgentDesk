# Device Benchmark Protocol

## Purpose
Establish minimum performance baselines for AgentDesk across device tiers before public release.

## Device Tier Definitions

| Tier | RAM | CPU | Storage | Example Devices |
|------|-----|-----|---------|-----------------|
| LOW | ≤ 3 GB | ≤ 4 cores | ≤ 32 GB | Entry-level Android Go |
| MEDIUM | 4–6 GB | 6–8 cores | 64–128 GB | Pixel 4a, Galaxy A series |
| HIGH | ≥ 8 GB | 8+ cores | ≥ 128 GB | Pixel 8 Pro, Galaxy S series |

## Benchmarks

### Cold Start (App Launch → Chat Screen Ready)

| Tier | Target | Fail Threshold |
|------|--------|----------------|
| LOW | < 2.5 s | > 4 s |
| MEDIUM | < 1.5 s | > 2.5 s |
| HIGH | < 1.0 s | > 2.0 s |

### Command Processing (Text input → CommandResult)

| Scenario | Target | Fail Threshold |
|----------|--------|----------------|
| Simple intent (alarm, timer) | < 50 ms | > 200 ms |
| Complex regex (SMS draft) | < 100 ms | > 300 ms |
| Unknown intent fallback | < 50 ms | > 150 ms |

### Room DB

| Operation | Target |
|-----------|--------|
| `commandDao.insert()` | < 10 ms |
| `auditDao.insert()` | < 10 ms |
| `ftsSearchDao.searchChunks()` (1,000 docs) | < 100 ms |

### Memory

| Scenario | Target Heap |
|----------|-------------|
| Chat screen idle | < 80 MB |
| After 20 commands | < 120 MB |
| After 1 hour of use | < 150 MB |

## Measurement Tools

- **Cold start**: Android Studio Profiler → App Startup
- **Command latency**: `System.currentTimeMillis()` timestamps in `CommandGateway`
- **DB operations**: Room `RoomDatabase.QueryCallback` + Profiler
- **Memory**: Android Profiler → Memory → Heap Dump

## Benchmark Protocol Steps

1. Factory-reset test device (or clear app data)
2. Install release APK (`./gradlew assembleRelease`)
3. Grant required permissions
4. Record cold start time × 5 runs, take median
5. Run 20 commands in Chat screen
6. Record memory heap after commands
7. Record command processing latency for each intent type
8. Compare against thresholds above — FAIL if any threshold exceeded

## Reporting

Benchmark results must be reported in the release notes with:
- Device model and Android version
- Results for each metric
- PASS/FAIL against thresholds
