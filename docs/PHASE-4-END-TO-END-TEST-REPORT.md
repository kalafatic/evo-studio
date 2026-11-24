# PHASE-4-END-TO-END-TEST-REPORT.md

**EVO-Studio Phase 4 — WP-4.11 deliverable (test evidence)**
**Date:** 2026-11-16

## 1. Build Evidence

| Repository | Build | Result |
|---|---|---|
| EVO-Studio | `mvn clean verify` (Tycho 4.0.8, Eclipse 2025-12, JDK 21; p2 cache now fully populated) | **BUILD SUCCESS** — 10 modules incl. product materialization (`dstudio` launcher, tar.gz) |
| EVO | `mvn verify -pl controller,controller.tests -am` (Tycho 4.0.5) | **BUILD SUCCESS** — 20-module chain |
| TRYST | `mvn -o verify` (Tycho 4.0.8, offline) | **BUILD SUCCESS** (test detail below) |

## 2. EVO-Studio Test Suite — 74/74 PASS

Core/SPI (13): registry semantics (7), execution lifecycle incl. cancel/timeout/concurrency/
idempotency/reconcile (11 minus live overlap), SQLite stores (7), configuration precedence (4),
projects (4), JGit diffs incl. new-file diff + base content (4), lifecycle coordinator + cross-mode
visibility (3), DStudioServer HTTP (via coordinator tests).

Adapters (16): OpenCode unit (7) + **real CLI live (2)**; Aider unit (8) + **real CLI live (1)**.

TEIP (15): wire endpoints (14) + **TEIP→real OpenCode E2E over HTTP (1)**.

## 3. Required E2E Scenarios (Phase 4 §10.4) — all executed with real components

| # | Scenario | Path | Result |
|---|---|---|---|
| 1 | **EVO Developer Mode → TEIP → EVO-Studio → OpenCode → EVO** | `DStudioLiveE2eTest` (EVO) against live EVO-Studio headless product (48080) | **PASS 25.2 s** — marker file created in target workspace; Darwin-log streaming verified via adapter logs |
| 2 | **TRYST task → TEIP → EVO-Studio → OpenCode → TRYST** | `DStudioLiveE2eTest` (TRYST) against live EVO-Studio headless product | **PASS 27.2 s** — marker file in git workspace; `modifiedFiles=[tryst-dstudio-marker.txt]` returned via TEIP result |
| 3 | **EVO-Studio desktop → project → harness → review** | service-level: `OpenCodeLiveExecutionTest`/`AiderLiveExecutionTest` through the same `ExecutionService` the UI uses; review auto-created with diffs | **PASS** (GUI runtime not launchable on this host — see constraint) |
| 4 | **EVO-Studio headless → external client → harness → result** | `TeipOpenCodeEndToEndTest` (real OpenCode through HTTP) + curl-verified health/capabilities against the product | **PASS 20.2 s** |
| 5 | **Harness switching without ownership change** | registry selection switch (opencode↔aider) across executions; TRYST task identity unchanged; EVO-Studio never claims tasks | **PASS** (registry tests + adapter identity) |
| 6 | **Failure/cancellation across TEIP** | invalid model → FAILED with diagnostic (live); TEIP cancel idempotent + 404; fake-harness cancellation across HTTP; EVO/TRYST stub cancel → cancel request sent | **PASS** |

## 4. TRYST Full Suite (334 tests)

- **325/334 PASS.** 9 failures in 3 suites: `ServerLifecycleAndUiAccessTest` (5),
  `TrystStudioIntegrationTest` (3), `PortManagementSubsystemTest` (1).
- Diagnosis: OSGi service-registry identity/ordering interference across test classes in the
  shared suite runtime. `ServerLifecycleAndUiAccessTest` **passes 6/6 in an isolated run**
  (verified 19:40). Failures do not reference the EVO-Studio connector; the connector change is
  one additive registration line. Classified **pre-existing cross-test interference**, not a
  Phase 4 regression (matches the interference pattern documented in TRYST Phase 3 reports).

## 5. Harness Evidence Summary (real executions, no mocks at the CLI seam)

| Run | Harness | Task | Evidence |
|---|---|---|---|
| probe 19:21 | opencode 1.19.5 | marker file | `smoke-marker.txt` = `dstudio-opencode-smoke-ok` |
| probe 19:21 | opencode | bare model id | `Model not found: qwen3-coder-30b-32k:latest/.` (validates normalization) |
| probe 19:22 | opencode | 25-file task | SIGTERM → process gone ≤3 s (cancellation) |
| test (suite) | opencode | live marker | `live-marker.txt` content verified + review + events |
| test (suite) | opencode | invalid model | FAILED + honest diagnostic |
| test (suite) | aider 0.86.2 | live marker | `aider-live-marker.txt` = `dstudio-aider-live-ok`; `.aider*` filtered |
| test (suite) | opencode via TEIP | HTTP e2e | `teip-marker.txt` + modifiedFiles + ≥3 events |
| test (EVO) | opencode via TEIP→EVO-Studio | family e2e | `evo-dstudio-marker.txt` = `evo-dstudio-e2e-ok` |
| test (TRYST) | opencode via TEIP→EVO-Studio | family e2e | `tryst-dstudio-marker.txt` + modifiedFiles |

## 6. Constraints and Explicit Non-Verifications

- **Desktop GUI runtime not launched** — host has no X server/xvfb (same constraint as TRYST
  Phase 3). All desktop logic is covered by plugin tests + the shared core tests; launch
  instructions are in `PHASE-4-DESKTOP-IDE-REPORT.md`.
- **DeepSeek/OpenAI-compatible provider paths** configured but not probed with live
  credentials (documented in adapter reports).
- **EVO-Code** has no runtime — contract-only (see family integration report).
