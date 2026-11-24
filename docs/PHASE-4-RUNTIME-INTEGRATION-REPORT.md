# PHASE-4-RUNTIME-INTEGRATION-REPORT.md

**EVO-Studio Phase 4 — WP-4.03 + WP-4.08 deliverables**
**Date:** 2026-11-16

## 1. Headless Runtime (WP-4.03)

### Composition
One product, two runtime faces (verified TRYST-family pattern):
- `eu.kalafatic.dstudio.application.DStudioApplication` (IApplication `...application`)
  — desktop workbench, or headless when `-headless` is passed.
- `eu.kalafatic.dstudio.application.DStudioServerApplication` (IApplication `...server`)
  — always headless.
- `DStudioLifecycleCoordinator` stages: `APPLICATION_START → CONFIGURATION_INITIALIZATION →
  CORE_SERVICE_INITIALIZATION → APPLICATION_READY → (WORKBENCH_RUNNING) →
  APPLICATION_SHUTDOWN → SERVICE_DISPOSAL → APPLICATION_TERMINATED`; monotonic advance;
  shutdown hooks; SIGTERM-safe.
- Shared core wiring at `CORE_SERVICE_INITIALIZATION`: SQLite + migrations, harness registry
  (OpenCode + Aider adapters), execution/review/project services, EventBus, JGit diff
  service, restart reconciliation; TEIP+diagnostics server at `APPLICATION_READY`.

### Runtime evidence (real product, non-graphical host)
| Check | Result |
|---|---|
| `./dstudio -headless` starts without X/GTK/SWT | PASS — full startup, no SWT/workbench messages in log |
| Server binds 48080 (`/teip/v1`, `/api/status`, `/api/diagnostics`) | PASS — live HTTP responses captured |
| Health: 2 harnesses (OpenCode+Aider) discoverable via TEIP capabilities | PASS |
| SIGTERM → clean shutdown, port freed, shutdown hook + db close | PASS (twice: 19:19 and family-E2E run) |
| Config dir override (`-Ddstudio.config.dir`) + `dstudio.properties` | PASS |
| `-application eu.kalafatic.dstudio.application.server` alternate entry | compiled and registered in plugin.xml (equivalent code path; primary entry verified) |

## 2. Shared Desktop/Headless Execution (WP-4.08)

Single authoritative core: desktop controllers and the TEIP provider invoke the identical
`ExecutionService`, `HarnessRegistry`, `ReviewService`, `ProjectService`, `EventBus` and
persistence instances supplied by the same `DStudioLifecycleCoordinator`. The UI bundle is
a presentation layer only (see MVC boundary verification in the Desktop IDE report).

Cross-mode evidence:
| Direction | Test | Result |
|---|---|---|
| TEIP → core | `TeipOpenCodeEndToEndTest` — external HTTP client submits; task visible/updated in `ExecutionService`; events+result served | PASS (real OpenCode, 20.2 s) |
| core → TEIP | `desktopSubmittedTaskVisibleThroughTeipSurface` — task submitted through the service (desktop path) is queryable via `/teip/v1/executions/{id}` + events | PASS |
| Persistence parity | both paths persist to the same SQLite `executions`/`execution_events` tables; restart reconciliation marks open tasks FAILED | PASS (`reconcileAfterRestartFailsOpenExecutions`) |
| Lifecycle parity | `DStudioLifecycleCoordinatorTest` — server start/stop, monotonic stages, diagnostics endpoints | PASS |

## 3. Operational Reliability Checks Performed

| Scenario | Evidence |
|---|---|
| Startup/shutdown | headless product runs (twice) + coordinator tests |
| Cancellation | fake-harness cancel (QUEUED + RUNNING), TEIP idempotent cancel + 404, real OpenCode SIGTERM probe (process gone ≤3 s) |
| Timeout | adapter-level timeout → `TIMED_OUT` (TEIP-mapped to FAILED); service test `timeoutProducesTimedOutStatus` |
| Concurrent tasks | `concurrentSubmissionsAreIsolated`; TEIP concurrency via async executor |
| Invalid harness config / missing executable | adapter connection-test failures; `unknownHarnessRejectedWith422`; invalid binary tests (EVO/TRYST/EVO-Studio) |
| Unavailable model | real OpenCode invalid model → honest FAILED with diagnostic (EVO-Studio + TRYST suites) |
| Duplicate requests / idempotency | persisted `execution_idempotency`; 202/200 replay over HTTP; service-level idempotency |
| Server restart recovery | `reconcileAfterRestartFailsOpenExecutions` |
| Network interruption | TEIP error envelope `SERVICE_UNAVAILABLE` reserved; TRYST client retry semantics exist upstream; EVO-Studio side returns structured errors on adapter failures |

## 4. Known Limitations

- No SSE/WebSocket push — TEIP 1.1 is pull-based by design (2.0 material).
- Client-disconnect detection is HTTP-native (no server-side keepalive beyond request
  timeouts); polling clients resume via the persisted event cursor.
- Recovery of interrupted executions is "fail with diagnostic" (canonical TEIP §7.4),
  not resumption.
