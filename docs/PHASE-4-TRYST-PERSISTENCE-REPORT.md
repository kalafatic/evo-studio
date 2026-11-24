# PHASE-4-TRYST-PERSISTENCE-REPORT.md

**EVO-Studio Phase 4 — WP-4-FINAL.2 deliverable**
**Date:** 2026-11-16

## 1. Requirement

The TRYST-side EVO-Studio connection configuration was in-memory per adapter instance; after a
TRYST restart all provider settings were lost. This work package persists the non-secret
EVO-Studio provider configuration using TRYST's existing SQLite/persistence conventions.

## 2. Implementation (reuses existing TRYST architecture)

| File | Change |
|---|---|
| `domain/model/DStudioConfiguration.java` | NEW domain entity (id, endpoint, harness, model, enabled, timeoutSeconds, updatedAt); default endpoint `http://localhost:48080` |
| `domain/repository/DStudioConfigurationRepository.java` | NEW repository interface (`save`, `findById`) |
| `infrastructure/.../SqliteDStudioConfigurationRepository.java` | NEW SQLite implementation, same locking/transaction conventions as `SqliteEvoConfigurationRepository` |
| `infrastructure/.../SchemaMigrationService.java` | **V18 migration** (forward-only): `dstudio_configurations` table |
| `evo.integration/.../DStudioCodingHarnessAdapter.java` | Constructor now accepts the repository; lazy hydration on first use (`isAvailable`/`testConnection`/`executeTask`/`getConnectionConfiguration`); `updateConnectionConfiguration` persists non-secret fields; `enabled` flag gates availability and execution |
| `application/.../ApplicationLifecycleCoordinator.java` | Registers the adapter with the SQLite repository |

**Secrets:** the TEIP auth token is intentionally NOT persisted — it remains runtime-only
(supplied via the adapter config map at runtime, masked in `getConnectionConfiguration`, or
via process environment). This is stricter than the existing EVO-configuration convention
(which stores the token in SQLite) and is documented as a deliberate security-preserving
deviation.

## 3. Runtime Behavior (verified)

| Behavior | Result |
|---|---|
| Configuration survives TRYST restart (new adapter instance hydrates persisted endpoint/harness/model/timeout/enabled) | PASS (`configurationPersistsAcrossAdapterInstances`) |
| Invalid/unreachable endpoint reported correctly without blocking unrelated functions | PASS (`unreachableEndpointIsReportedNotFatal` — structured `ConnectionTestResult` failure) |
| Provider can be enabled/disabled; disabled state persists and gates `isAvailable()`/`executeTask` | PASS (`disabledConfigurationIsUnavailable`) |
| Existing local execution remains intact | PASS (TRYST full suite 337/337 — OpenCode adapter, TaskWorker and EVO adapter regressions all green) |
| Configuration changes do not corrupt active tasks | PASS (config writes are single-row upserts on the `dstudio_configurations` table; no task tables touched; full suite green) |
| Reconnection | PASS (`testConnection` re-checks the live endpoint; live EVO-Studio E2E re-run) |

## 4. Test Evidence

- `DStudioCodingHarnessAdapterTest` (TRYST): **10/10 PASS** (7 wire-level + 3 persistence).
- Full TRYST suite: **337/337 PASS** (two consecutive runs) — migration chain V1..V19
  exercised, `SchemaMigrationTest`/`DatabaseInitializationTest` updated to assert V19.

## 5. Notes

- Forward-only migration rule preserved: V18 only adds a new table; no historical migration
  was modified.
- The migration-test version assertions (17 → 19) were updated as the intended consequence
  of extending the chain; the studio tables added in **V19** (see
  `PHASE-4-TRYST-TEST-STABILIZATION-REPORT.md` §2) complete the schema for TRYST's existing
  studio service.
