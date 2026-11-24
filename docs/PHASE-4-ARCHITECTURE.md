# PHASE-4-ARCHITECTURE.md

**EVO-Studio Phase 4 — target architecture (WP-4.01 deliverable)**
**Date:** 2026-11-16
**Status:** Authoritative target architecture for Phase 4 implementation.

---

## 1. Product Definition

EVO-Studio is **one product with two runtime faces** sharing one authoritative domain/application
core:

1. **Desktop mode** — professional Eclipse RCP WYSIWYG IDE (projects, source editing, harness
   execution, monitoring, review).
2. **Headless mode** — server-side Harness Platform exposing the same core through the canonical
   TEIP 1.1 provider endpoint set, without SWT, workbench, X server or browser.

EVO-Studio owns **coding harness infrastructure** for the family. EVO and TRYST keep their domains,
workflows and persistence; they connect to EVO-Studio through TEIP.

## 2. Technology Stack (family-preserving)

- Java 21 (`Bundle-RequiredExecutionEnvironment: JavaSE-21`), Tycho **4.0.8**, Maven 3.9.12.
- Eclipse **2025-12** target platform (cached in `~/.m2`; SWT 3.132, OSGi 3.24). Features:
  `org.eclipse.rcp`, `org.eclipse.platform` (search, navigator.resources, team UI),
  `org.eclipse.jdt` (Java editor/compiler/markers), `org.eclipse.egit` (git UI, JGit 7.5),
  `org.eclipse.equinox.p2.core.feature`, `org.eclipse.e4.rcp`, `org.eclipse.osgi.compatibility.state`,
  JUnit 5, slf4j.
- Persistence: SQLite via bundled `lib/sqlite-jdbc.jar` (3.45.2.0), forward-only migrations.
- HTTP: JDK `com.sun.net.httpserver.HttpServer` (no new frameworks).
- No Spring, Node.js, Python, message brokers, microservices.

## 3. Bundle Architecture (Module Layout)

```
eu.kalafatic.dstudio.core           Domain + application core (NO SWT, NO workbench):
                                    project/workspace domain, harness SPI + registry interfaces,
                                    execution lifecycle, task/session domain, execution events,
                                    result/diff/review models, application services (use cases),
                                    configuration model, event bus interface.
eu.kalafatic.dstudio.infrastructure Persistence (SQLite db + migrations), configuration
                                    persistence (~/.dstudio), process execution utilities,
                                    JGit repository service, logging/diagnostics.
eu.kalafatic.dstudio.harness        Harness adapters: OpenCode, Aider (+ future adapters).
                                    Depends on core only (adapter implementations).
eu.kalafatic.dstudio.teip           TEIP 1.1 provider server: HTTP endpoints, wire DTOs,
                                    request validation/dispatch, idempotency/event persistence
                                    bridge, auth. Depends on core + infrastructure. NO SWT.
eu.kalafatic.dstudio.application    Lifecycle coordinator + both IApplication implementations
                                    (desktop workbench + headless server), product plugin,
                                    action bar advisor. Desktop entry loads workbench;
                                    headless entry never touches SWT.
eu.kalafatic.dstudio.ui             MVC presentation: models (observable presentation state),
                                    views (project explorer, harness view, execution monitor,
                                    console, review/diff views), controllers (commands,
                                    handlers, dialogs), perspectives, editor integrations.
```

Dependency direction (strict, enforced by Tycho):
`ui → application → {teip, harness, infrastructure} → core`.
No bundle may depend on `ui` except `application` (workbench boot) and the product feature.
Core has **zero** compile-time references to `eu.kalafatic.evolution.*` or
`eu.kalafatic.tryst.*`.

## 4. Domain/Application Core (authoritative behavior)

### 4.1 Domain model (`eu.kalafatic.dstudio.core.model`)
- `Project` / `ProjectRegistry` — project identity (name, root path, git repo), open/close/refresh.
- `HarnessDescriptor` — id, name, type, capabilities, enabled, status, config map, timestamps.
- `ExecutionTask` — id, project, harness id, instruction/prompt, model override, agent,
  autoApprove, timeout, status (`QUEUED|RUNNING|COMPLETED|FAILED|CANCELLED|TIMED_OUT`),
  timestamps.
- `ExecutionEvent` — sequence (per task, persisted), type (TEIP-compatible:
  `EXECUTION_QUEUED|EXECUTION_STARTED|PROGRESS_UPDATED|EXECUTION_LOG|TOOL_INVOKED|
  EXECUTION_COMPLETED|EXECUTION_FAILED|EXECUTION_CANCELLED`), timestamp, message/data.
- `ExecutionResult` — summary, exit code, modified files, diff/patch text, metrics
  (durationMs, toolCalls where known), error info.
- `ChangeReview` — per execution: changed files, per-file diff, per-file decision
  (`PENDING|ACCEPTED|REJECTED`), review state.

### 4.2 Application services (use cases — callable from controllers AND TEIP)
- `ProjectService` — open/import/close/refresh projects.
- `HarnessRegistryService` — register adapters, list, enable, select default, persist config
  (`HarnessConfigurationRepository`).
- `ExecutionService` — submit (idempotency key support), cancel (idempotent), status, events
  (cursor), result, recovery on startup (open tasks → FAILED with diagnostic), task visibility
  across modes.
- `ReviewService` — collect changed files + diffs after execution, per-file decisions, accept/reject.
- `ConfigurationService` — `~/.dstudio/dstudio.properties` (TEIP port, auth token flag,
  selected harness, network mode) + system-property/env overrides.

### 4.3 Harness SPI (`eu.kalafatic.dstudio.core.harness`)
```java
public interface HarnessAdapter {
    String getHarnessId();                      // "opencode", "aider"
    String getHarnessName();
    String getHarnessType();
    List<String> getCapabilities();             // "CODE_IMPLEMENTATION", ...
    HarnessCapability getCapabilityDescriptor(); // models, modes, features (async/cancel)
    boolean isAvailable();                      // executable discovery + version probe
    ConnectionTestResult testConnection();
    default void updateConfiguration(Map<String,String> cfg) {}
    default Map<String,String> getConnectionConfiguration() {}
    HarnessExecutionResult execute(HarnessExecutionRequest request, HarnessExecutionSink sink);
}
```
- `HarnessExecutionRequest` — working directory, instruction/prompt, model (optional), agent,
  autoApprove, timeout, cancellation `BooleanSupplier`, task id (correlation).
- `HarnessExecutionSink` — `Consumer<ExecutionEvent>` streaming surface + log consumer; the
  adapter emits typed events (log/progress/tool) instead of raw lines only.
- `HarnessExecutionResult` — handled/success/timedOut/cancelled/exitCode/summary/errorMessage/
  modifiedFiles/diffText.
- `HarnessRegistry` — registration, selection, capability aggregation (TEIP `/capabilities`
  source of truth).
- Provider-specific concepts (agents, provider prefixes, auto-approve) are modeled as adapter
  config keys, NOT universal SPI capabilities (per Phase 4 §5.1).

### 4.4 Event bus
Single in-house synchronous `EventBus` interface in core (publish/subscribe for execution events,
project events); UI wraps deliveries in `Display.asyncExec`. No second event system.

### 4.5 Persistence (infrastructure)
SQLite `~/.dstudio/dstudio.db`, WAL, forward-only `SchemaMigrationService` (V1 baseline):
- `harnesses` (id, name, type, capabilities, enabled, status, config_json, selected, timestamps)
- `projects` (id, name, root_path, opened_at)
- `executions` (id, harness_id, project_id, status, idempotency_key, instruction, model, created/
  started/completed, result_json)
- `execution_events` (execution_id, sequence, event_type, timestamp, data_json) — sequences
  survive restarts (TEIP §4.5 requirement)
- `execution_idempotency` (idempotency_key → execution_id, persisted — TEIP §7.2)
- `reviews` / `review_files` (execution_id, path, diff, decision)
Configuration: `~/.dstudio/dstudio.properties`. Workbench layout: Eclipse `-data` workspace
(standard `setSaveAndRestore(true)`). Secrets never written to db/properties/logs — TEIP token
lives in properties as presence flag + value held in memory/env only (mirrors EVO/TRYST
conventions; exact mechanism per TEIP §6 dev-mode opt-out).

## 5. MVC Boundaries (desktop)

| Layer | Location | Responsibility | Forbidden |
|---|---|---|---|
| Model | core domain + `ui.model` presentation models (observable) | state, presentation-shaped data | SWT, services |
| View | `ui` views/editors/perspectives/dialogs | widgets, layout, rendering, user gestures | business logic, process launch, persistence, TEIP |
| Controller | `ui` commands/handlers + core application services | user interaction coordination, validation, invoking use cases | becoming a domain service, orchestrating processes directly |

Controllers call application services (core); views observe models; core never sees SWT.

## 6. Desktop IDE (WP-4.07)

- **Perspectives:** Development (Project Explorer + editors + Console + Problems/JDT markers +
  Harness view), AI & Harness (Harness registry/config/selection, Execution monitor w/ progress
  + cancel, live console), Review (changed-file navigation, Eclipse Compare side-by-side diff,
  accept/reject per file), Repository (git status/branches/history via EGit views).
- **Project management:** Project Explorer on the Common Navigator framework; open/import
  existing folders or git repos; folder/file create/rename/delete; context menus; refresh.
- **Source editing:** JDT Java editor (full Java tooling), Eclipse text editor for other types —
  multiple tabs, line numbers, find/replace, search (Eclipse Search), dirty tracking, markers.
- **Harness workflow:** select harness per project (or global default); execute against current
  project/selection; progress streaming into console + execution monitor; cancel; on completion
  changed files collected → Review perspective; continue manual editing at any time.
- **Console:** MessageConsole-based, per-execution streams.
- **State:** workbench layout persisted (`setSaveAndRestore(true)`); harness config persisted
  via core registry; review decisions persisted.

## 7. Headless Runtime (WP-4.03/4.09)

- Entry: `DStudioServerApplication` (IApplication `eu.kalafatic.dstudio.application.server`) or
  `-headless` flag on the main application (mirrors verified TRYST pattern).
- Lifecycle coordinator stages: `APPLICATION_START → CONFIGURATION_INITIALIZATION →
  CORE_SERVICE_INITIALIZATION → SERVER_RUNNING → APPLICATION_SHUTDOWN → SERVICE_DISPOSAL →
  APPLICATION_TERMINATED` (core, harness, teip, infrastructure only — ui bundle never loaded).
- Server: JDK HttpServer on **48080** — TEIP base `/teip/v1` plus the operational endpoints
  `/api/status` and `/api/diagnostics` on the same port. Loopback by default; configurable
  bind address (family convention +10000 per product: EVO 28xxx, TRYST 38xxx, EVO-Studio 48xxx).
  Port **48180** is reserved for a future standalone diagnostics listener (config key
  `diagnostics.port` exists; no listener is started on it in Phase 4).
- Shutdown: `CountDownLatch` await + JVM shutdown hook; process cleanup of running harnesses.
- Same `ExecutionService`/`HarnessRegistry` instances as desktop — a TEIP-submitted task and a
  desktop-submitted task are the same domain objects; desktop UI presents them, headless serves
  them (WP-4.08).

## 8. TEIP 1.1 Provider (WP-4.09)

EVO-Studio implements the canonical §4 endpoint set with EVO-Studio as `application`:

| Endpoint | Behavior |
|---|---|
| `GET /teip/v1/health` | `{protocol,version,status,application:"DSTUDIO",ready,details:{activeExecutions,harnesses}}`; DEGRADED/DOWN when essential deps unavailable |
| `GET /teip/v1/capabilities` | aggregated from `HarnessRegistry.getCapabilityDescriptor()`: capabilities[], supportedModes, agents, availableModels, tools, features{asyncExecution,eventStreaming,cancellation} |
| `POST /teip/v1/executions` | validate (instruction required; ≤5 MB), persisted idempotency (202 new / 200 replay), harness selection: `parameters.harness` → selected default → error; model from top-level `model` or `parameters.model`; maps to `ExecutionService.submit` |
| `GET /teip/v1/executions/{id}` | status + progress + phase/statusMessage (optional) |
| `GET /teip/v1/executions/{id}/events` | `afterSequence` cursor, `nextSequence`, limit ≤ 500, persisted sequences |
| `GET /teip/v1/executions/{id}/result` | 200 terminal (summary, patch from diff, modifiedFiles, artifacts, metrics) / 409 CONFLICT `details.currentStatus` |
| `POST /teip/v1/executions/{id}/cancel` | idempotent, 404 unknown |

- Auth (§6): `dstudio.teip.auth.token` set → Bearer required (401 otherwise); unset → explicit
  `dstudio.teip.devMode=true` + loopback only (401 otherwise). Never a silent default.
- Errors: canonical top-level envelope + code table (§5); `VERSION_MISMATCH` for unsupported
  majors; `METHOD_NOT_ALLOWED`; redaction of secrets in events/logs.
- Restart: open executions → FAILED "Server restarted; execution cannot be resumed" (verified EVO
  semantics, TEIP §7.4).
- EVO-Studio-specific request fields ONLY under `parameters.*` (open-world rule, TEIP §2). No
  private TEIP variant.

## 9. Harness Adapters (WP-4.05/4.06)

### OpenCode (v1.19.5, verified recipe)
- Discovery: configured `binary` → PATH `opencode`; version probe `--version` (10 s cap).
- Run: `opencode run <prompt> --agent <agent> --model <provider/model> --print-logs --dir <dir>
  [--auto]`; stdin → `/dev/null`; env: `CI=1`, `NO_COLOR=1`, `OPENCODE_DISABLE_TELEMETRY=1`,
  remove `OPENCODE`/`OPENCODE_PID`, per-run `XDG_DATA/STATE/CACHE_HOME` under
  `~/.dstudio/harness-runs/<taskId>/`, inherit `XDG_CONFIG_HOME`.
- Model normalization: bare id → `ollama/<id>` (v1.19.5 rejects bare ids: `Model not found`).
- Streaming: stdout→log events; `--format json` optional structured-event mode (probed in
  WP-4.05; default-format fallback mandatory).
- Cancellation/timeout: 500 ms poll loop, `destroy()` → `destroyForcibly()` (2 s).
- Changed files: post-run JGit status vs pre-run baseline; containment check (never outside
  `--dir`); non-git dir → empty list + warning.
- Errors: non-zero exit → structured failure with last stderr lines.

### Aider (0.86.1, probed in WP-4.06)
- Discovery/version probe: `aider --version` (10 s cap).
- Run (non-interactive): `aider --model <model> --message <prompt> --yes-always
  --no-auto-commits --no-show-release-notes --analytics-disable --map-refresh files`; env
  `OLLAMA_API_BASE=http://127.0.0.1:11434` for Ollama models; `--openai-api-base` for compatible
  endpoints; DeepSeek via provider env keys.
- Working dir = target dir; aider edits in place, never commits (`--no-auto-commits`).
- Streaming: stdout/stderr line capture; cancellation via process destroy (aider has no
  interactive prompt in `--message` mode).
- Changed files: same JGit post-run collection + containment check.

### Registry & configuration
- Adapters registered at CORE_SERVICE_INITIALIZATION (programmatic, mirroring verified TRYST
  pattern) — OSGi DS not required; single authoritative registry instance in core.
- Config persisted in SQLite `harnesses` table (fixes TRYST G4 defect by design).
- Secrets: provider API keys come from environment/provider tooling config (e.g., OpenCode's
  provider config), never persisted by EVO-Studio.

## 10. Family Integration (WP-4.10)

| Product | Change (integration boundary only) | Owner preserved |
|---|---|---|
| EVO | New `DStudioCodingHarnessAdapter implements CodingHarnessAdapter` (type `DSTUDIO`) in existing `controller/harness` package + small `DStudioTeipClient` (JDK HttpClient, modeled on TRYST `TeipClient` semantics) + registration in `ToolFactory` + tests. DEVELOPER mode/`CodingHarnessTool` unchanged — they already invoke the selected registry adapter. Config keys: endpoint (default `http://localhost:48080`), authToken, timeoutSeconds | EVO: Developer Mode, Darwin orchestration, project context |
| TRYST | New `DStudioCodingHarnessAdapter implements CodingHarnessAdapter` (type `DSTUDIO`) in `evo.integration` bundle reusing existing `TeipClient` (endpoint configurable) + registration in `ApplicationLifecycleCoordinator` + tests + persistence via `EvoConfiguration`-style repository or generic harness config | TRYST: tasks, sandboxes, reviews, Git |
| EVO-Code/DCoder | **Contract only** (skeleton repo): document TEIP client contract EVO-Code will implement, required capabilities, dependencies; implementation plan + blockers | EVO-Code: agent product |
| EVO-Studio | Distinguishes family clients via TEIP `caller.system`; health/capabilities serve discovery | EVO-Studio: harness platform |

No product loads another product's classes; all communication is TEIP JSON over HTTP. Local
execution fallbacks remain available in EVO/TRYST per their existing architectures.

## 11. Configuration, Ports, Deployment

- `~/.dstudio/dstudio.properties` keys: `teip.port` (48080), `teip.bindAddress` (127.0.0.1),
  `teip.networkMode`, `teip.auth.token` (never logged), `teip.devMode`, `diagnostics.port`
  (48180), `harness.default` (id), `harness.defaultModel`, per-harness config in db.
- Override precedence: system property → environment → properties file → defaults
  (mirrors TRYST convention).
- Product: single `.product` (features: `eu.kalafatic.dstudio.feature`, `org.eclipse.platform`,
  `org.eclipse.jdt`, `org.eclipse.egit`, `org.eclipse.rcp`, `org.eclipse.equinox.p2.core.feature`,
  `org.eclipse.e4.rcp`); headless is a runtime mode, not a second product (verified TRYST pattern).
- `mvn clean verify` (offline-capable via warm p2 cache), `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`,
  Maven 3.9.12 wrapper dist.

## 12. Testing Strategy

- tycho-surefire plugin tests (`useUIHarness=false` — headless host constraint): SPI/registry,
  persistence/migrations, TEIP wire tests against live HTTP server, adapter unit tests with
  fake executables, idempotency/cursor/cancel/recovery.
- Real execution tests (no mocks for the CLI seam): isolated temp git repos; opencode smoke
  (marker file inside workspace), aider smoke, cancellation mid-run, invalid model failure,
  containment violation.
- Desktop verification constrained on this host (no X server): plugin tests cover controllers/
  models/views logic; full workbench runtime verification documented as unverified here, with
  explicit launch instructions (same as TRYST practice).
- Family E2E: real EVO-Studio headless TEIP server + EVO adapter suite + TRYST adapter suite.
```

## 13. Key Decisions Requiring Product-Owner Awareness

1. **EVO-Studio port family 48xxx** (TEIP 48080, diagnostics 48180) — follows the family +10000
   convention; configurable. (Decision recorded here; no PO objection expected but flagged.)
2. **Eclipse 2025-12** target (not 2024-03) — matches EVO platform, full JDT/EGit caching.
3. **TEIP request → harness mapping** uses `parameters.harness` (open-world extension) with
   EVO-Studio-side default selection.
4. **EVO-Code integration is contract-only** in Phase 4 (skeleton repo has no runtime).
5. Desktop workbench runtime verification is limited by the missing X server on this host.
