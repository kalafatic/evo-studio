# PHASE-4-IMPLEMENTATION-PLAN.md

**EVO-Studio Phase 4 — implementation plan (WP-4.01 deliverable)**
**Date:** 2026-11-16
**Status:** Approved-for-execution structure; each WP must be completed, built, tested and
documented before the next major WP. Any deviation requires a recorded reason.

---

## 0. Ground Truth Summary

EVO-Studio is a **greenfield** product (skeleton repo). Phase 4 builds the complete product on the
family conventions verified in the audit: Java 21, Eclipse RCP/OSGi, Tycho 4.0.8, Eclipse
2025-12 target, SQLite persistence, JDK HttpServer, TEIP 1.1 canonical spec
(`evolution-121126/docs/protocol/TEIP-1.1.md`). Environment verified: opencode 1.19.5,
aider 0.86.1, ollama 0.40.16 (qwen3-coder models), JDK 21, Maven 3.9.12 wrapper dist, warm p2
cache (415 bundles), no X server.

## 1. Work Packages, Dependencies, Acceptance Criteria

### WP-4.01 — Architecture and Repository Audit ✅
Deliverables: `docs/PHASE-4-ARCHITECTURE-AUDIT.md`, `docs/PHASE-4-ARCHITECTURE.md`,
`docs/PHASE-4-IMPLEMENTATION-PLAN.md` (this document).

### WP-4.02 — Core MVC and Application Architecture
- Repository skeleton: parent POM, target platform, 6 plugin bundles + feature + product + tests
  module; manifests with strict dependency direction; `mvn clean verify` green.
- Core domain: Project, HarnessDescriptor, ExecutionTask, ExecutionEvent, ExecutionResult,
  ChangeReview; application services (ProjectService, ExecutionService, ReviewService,
  ConfigurationService); harness SPI (`HarnessAdapter`, `HarnessRegistry`, request/sink/result);
  EventBus.
- Infrastructure: SQLite database manager + V1 migrations, `~/.dstudio` config persistence,
  process-executor utilities, JGit repository service.
- Acceptance: reactor builds offline; focused tests: registry semantics, execution lifecycle
  (submit→run→events→result→cancel), persistence round-trips, config precedence.

### WP-4.03 — Headless Runtime Foundation
- `DStudioServerApplication` IApplication + `-headless` flag on main application; lifecycle
  coordinator stages; JDK HttpServer skeleton on 48080/48180 with `/api/status`, diagnostics.
- Acceptance: headless start/shutdown in non-graphical environment verified by script;
  no SWT/workbench classes loaded (documented classpath check); lifecycle tests.

### WP-4.04 — Harness Platform and SPI
- Finalize registry with persistence + capability aggregation; execution service wiring
  (idempotency, cancellation, timeouts, concurrent tasks, recovery-on-restart).
- Acceptance: plugin tests for concurrent submissions, cancel, timeout, restart reconciliation;
  fake-harness E2E through the execution service.

### WP-4.05 — OpenCode Adapter
- `OpenCodeHarnessAdapter` per §9 of the architecture doc; live CLI probes (`--format json`
  structured streaming probe, model naming, failure modes) recorded.
- Acceptance: real execution in an isolated git repo (files changed, diff collected, events
  streamed), cancellation terminates the process, invalid model fails honestly, containment
  check rejects escapes. Deliverable: `docs/PHASE-4-OPENCODE-REPORT.md`.

### WP-4.06 — Aider Adapter
- `AiderHarnessAdapter` per §9; live probes of aider 0.86.1 (model naming for Ollama/DeepSeek/
  OpenAI-compatible, exit behavior, `--message` non-interactivity, no-commit enforcement).
- Acceptance: same real-execution matrix as OpenCode. Deliverable:
  `docs/PHASE-4-AIDER-REPORT.md`.

### WP-4.07 — Professional RCP WYSIWYG IDE
- Workbench boot, perspectives (Development / AI & Harness / Review / Repository), Project
  Explorer (Common Navigator), JDT/text editors with dirty tracking + search, Console,
  Problems view, harness selection + execution UI + live monitor + cancel, review with Compare
  diffs and accept/reject, EGit views, key bindings, persisted workbench state.
- Acceptance: plugin tests for controllers/views/models; application product materializes with
  the full feature set; runtime workbench launch documented (host has no X server — full GUI
  verification instructions provided, marked unverified here). Deliverable:
  `docs/PHASE-4-DESKTOP-IDE-REPORT.md`.

### WP-4.08 — Shared Desktop and Headless Execution
- Prove one core: desktop controllers and TEIP call the same `ExecutionService`/`HarnessRegistry`.
- Acceptance: cross-mode tests (task submitted via service visible through TEIP status/events
  and vice versa), lifecycle/failure parity; report
  `docs/PHASE-4-RUNTIME-INTEGRATION-REPORT.md`.

### WP-4.09 — TEIP Integration
- Full TEIP 1.1 provider endpoint set per architecture §8: health, capabilities, executions
  (202/200 + persisted idempotency), status, events cursor, result (409), cancel, error
  envelope, auth (Bearer + explicit dev-mode loopback opt-out), reconcile-on-restart, 5 MB
  limit, version mismatch.
- Acceptance: wire tests against the live server (contract test classes mirroring EVO's TEIP
  suites); real harness execution through TEIP; concurrency/cancellation/failure over HTTP.
  Deliverable: `docs/PHASE-4-TEIP-INTEGRATION-REPORT.md`.

### WP-4.10 — Complete Family Connectors
- EVO: `DStudioCodingHarnessAdapter` + `DStudioTeipClient` in `controller/harness`, registration
  in `ToolFactory`, config persistence in `connected-applications.json` model, tests. EVO
  Developer Mode can select EVO-Studio as provider (existing selection mechanism reused).
- TRYST: `DStudioCodingHarnessAdapter` in `evo.integration` reusing `TeipClient`, registration
  in `ApplicationLifecycleCoordinator`, persisted config, tests. TRYST tasks delegate to EVO-Studio
  through the existing `HarnessAiDelegator` seam.
- EVO-Code/DCoder: contract documentation + implementation plan (skeleton repo).
- Acceptance: EVO harness suite passes with a live EVO-Studio headless server (real HTTP); TRYST
  harness suite passes against live EVO-Studio; compatibility matrix. Deliverables:
  `docs/PHASE-4-FAMILY-INTEGRATION-REPORT.md`,
  `docs/PHASE-4-FAMILY-COMPATIBILITY-MATRIX.md`.

### WP-4.11 — End-to-End Verification and Final Closure
- Full reactor builds of EVO-Studio + modified EVO + modified TRYST; all test suites re-run.
- E2E scenarios: EVO Developer Mode → TEIP → EVO-Studio → OpenCode → EVO; TRYST task → TEIP →
  EVO-Studio → OpenCode/Aider → TRYST; EVO-Studio desktop harness execution (service-level, host
  GUI-constrained); EVO-Studio headless external client; harness switching; failures/cancellation
  across TEIP.
- Deliverables: `docs/PHASE-4-END-TO-END-TEST-REPORT.md`, `docs/PHASE-4-FINAL-CLOSURE.md`
  (with the §13 closure structure, DoD checklist with evidence pointers, known limitations,
  deferred items, deviations, remaining PO decisions).

## 2. Dependency Order and Rationale

Order 4.01→4.11 as listed. Rationale: core before runtime faces; adapters after SPI; IDE after
core services; TEIP after execution service; family connectors after TEIP provider; verification
last. Deviation risk: WP-4.07 (IDE) is large — execution UI is implemented against the SPI
verified in WP-4.04/4.05 so no rework is expected.

## 3. Execution Rules for Every WP

1. Implement; do not stop at analysis.
2. Build the affected reactor after each WP (`mvn clean verify`, offline, JDK 21, Maven 3.9.12).
3. Run focused tests; record actual output and execution identifiers.
4. Real-execution evidence over mocks wherever the seam is external (harness CLIs, TEIP wire).
5. Update the WP report document; cross-check architecture doc stays true to implementation.
6. No commits/pushes unless explicitly requested; all changes stay in working trees.
7. Family products: only integration-boundary changes (new adapter + registration + tests);
   never core rewrites.
8. Record blockers honestly; distinguish implemented/tested vs documented vs blocked vs deferred.

## 4. Global Test Evidence Requirements

- Unit: SPI/registry, state machines, persistence/migrations, config precedence, TEIP DTOs.
- Integration (plugin tests, headless): live HTTP server + wire assertions; fake executable
  scripts for adapter unit tests; real CLIs for smoke/E2E.
- E2E: isolated temp git repositories; assert actual file modifications, collected diffs,
  streamed events, process termination on cancel; capture logs and timestamps.
- Family: EVO/TRYST suites re-run after their connector changes.

## 5. Known Constraints (recorded up front)

- No X server/xvfb on this host → desktop GUI runtime verification is service/plugin-level;
  workbench launch steps documented for a graphical host (same constraint TRYST Phase 3 faced).
- Port 28080 occupied by a third-party app → all EVO-Studio live TEIP tests use 48080 (or explicit
  override).
- EVO-Code/DCoder has no runtime → contract-only deliverable.
- Family repo AGENTS.md files prohibit git operations by agents — all evidence is filesystem/
  build/test based; no commits.
