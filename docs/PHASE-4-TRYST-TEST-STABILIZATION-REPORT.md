# PHASE-4-TRYST-TEST-STABILIZATION-REPORT.md

**EVO-Studio Phase 4 — WP-4-FINAL.3 deliverable**
**Date:** 2026-11-16

## 1. Reproduction

Full suite was run four times during this work package (runs 1–4). Initial state (run 1):
**325/334 pass, 9 failures** in `ServerLifecycleAndUiAccessTest` (5), `TrystStudioIntegrationTest`
(3), `PortManagementSubsystemTest` (1). All failing classes passed in isolation — classic
cross-test interference. Additional baseline failures surfaced when the suite was re-run
with the Phase 4 additions (337 tests): 3 migration-version assertions (intended consequence
of new migrations) and the 3 studio failures (pre-existing).

## 2. Root Causes (investigated, not dismissed)

### 2.1 OSGi service-registration leaks (the 5 ServerLifecycle failures + 1 PortManagement error)

`ApplicationLifecycleCoordinator.transitionTo()` enforces **monotonic, consecutive**
transitions (`validateTransition` rejects skips with an exception). Two test classes
(`DatabaseAvailabilityAndPersistenceVerificationTest`, `UiServiceResolutionTest`) tore down
with `READY → SHUTDOWN → DISPOSAL` — skipping `WORKBENCH_RUNNING` — inside a `catch
(Exception ignored)`. The rejected transition meant `SERVICE_DISPOSAL` (which unregisters all
OSGi services and stops the HTTP server) never ran: the stale `TrystServerLifecycleService`/
`TrystHttpService` registrations (service.id=288 …) and a **bound HTTP port** leaked into
every subsequent test, breaking the "authoritative coordinator" identity assertions, the
"exactly one HTTP service" assertion, the unregistration assertion, and the port-rebind test.

**Fix (test-side, minimal):** both teardowns now walk the stages consecutively
(`while currentStage < APPLICATION_TERMINATED → transitionTo(next)`), exactly like the
already-correct `ServerLifecycleAndUiAccessTest.cleanEnvironment()`.

### 2.2 Missing studio schema (3 TrystStudioIntegrationTest failures, pre-existing baseline)

`DefaultStudioService` (uncommitted Phase 3 studio work) writes to `studio_alternatives` and
`studio_plans` tables that **never existed in any migration** — inserts failed silently and
reads returned empty (`expected 2/3 but was 0`). Additionally,
`testLiveInstructionsAndCommentPersistence` inserted a review comment whose `review_id`
(`"review-test-1"`) had no parent row in `code_reviews`, violating the
`FOREIGN KEY (review_id) REFERENCES code_reviews(id)` constraint
(`SQLITE_CONSTRAINT_FOREIGNKEY`).

**Fix (minimal, forward-only):**
- **V19 migration** adds `studio_alternatives` and `studio_plans` (with
  `task_id → tasks(id) ON DELETE CASCADE`, consistent with sibling task-child tables).
- The studio test now creates the parent project + code review (`review-test-1`) before
  adding the comment, exercising the real repository path instead of FK-invalid data.
- No product business logic was modified.

### 2.3 Migration-version assertions (3 failures, intended consequence)

The Phase 4 migrations V18 (`dstudio_configurations`, WP-4-FINAL.2) and V19 (studio tables)
extended the forward-only chain; four test classes asserted `getCurrentVersion() == 17`.
**Fix:** assertions updated to 19 (the correct expression of the new chain state).

## 3. Verification

| Run | Result |
|---|---|
| Each previously failing test independently | PASS (e.g. `ServerLifecycleAndUiAccessTest` 6/6 isolated; `TrystStudioIntegrationTest` fails standalone only until the schema fix, then 4/4) |
| Affected classes together (`DatabaseAvailability…`, `UiServiceResolution…`, `ServerLifecycle…`, `TrystStudio…`, `PortManagement…`) | PASS (part of the full runs) |
| Full TRYST suite | **337/337 PASS — BUILD SUCCESS** |
| Full suite repeated (stability) | **337/337 PASS — BUILD SUCCESS** (second consecutive run, no nondeterminism) |

No test was disabled; no coverage was reduced; no serialization was introduced. The suite
remains fully parallel-friendly — the fix restores the cleanup the tests always intended.

## 4. Baseline Exceptions Identified Separately

- **NONE remaining.** All pre-existing and interference failures identified in the audit are
  resolved by the minimal fixes above. The suite is fully green with repeatable results
  (337/337 twice).
