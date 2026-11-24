# PHASE-4-ARCHITECTURE-AUDIT.md

**EVO-Studio Phase 4 — WP-4.01 deliverable**
**Date:** 2026-11-16
**Status:** Evidence-based audit of the actual workspace state. All findings verified against filesystem and source code, not design documents.

---

## 1. Verified Repository State

| Repository | Path | State | Evidence |
|---|---|---|---|
| EVO-Studio | `/home/petr/git/EVO-Studio-121126` | **Skeleton** — `README.md` only (9 lines), 3 commits, no bundles, no build, no `AGENTS.md`, no docs | directory listing; `git log` = `df857a9 init`, `2fd0c37 README`, `cb39e18 Initial commit` |
| EVO (Evolution) | `/home/petr/git/evolution-121126` | Full product, Java 21 RCP, 26-plugin feature `eu.kalafatic.evolution.feature` 2.6.11.qualifier; Tycho 4.0.5, Eclipse 2025-12 | `pom.xml`, `feature.xml`, HEAD = PR #1796 "Integrate Developer mode with Darwin AI and Eclipse Java tooling" |
| TRYST | `/home/petr/git/tryst-121126` | Full product, Java 21 RCP + headless server, 6-plugin feature 2.6.11.qualifier; Tycho 4.0.8, Eclipse 2024-03 | `pom.xml`, `feature.xml`, HEAD = `9ae5e07 SYNC` |
| EVO-Code/DCoder | `/home/petr/git/DCoder-121126` | **Skeleton** — `README.md` only ("native coding agent of EVO-Studio") | directory listing |
| TEIP | protocol, not a repo | TEIP 1.1 canonical spec = `evolution-121126/docs/protocol/TEIP-1.1.md` (471 lines, NORMATIVE) | file content |

Other directories under `/home/petr/git` are snapshots/working copies of EVO/TRYST (`evolution-161126`, `evolution-170126`, `tryst-161126`, `tryst-031126`, `tryst-11126`) or unrelated projects (llama.cpp, portal, gemini, …). The authoritative Phase-4 working repos are the four listed above.

## 2. Phase 3 Prerequisite Verification

**Phase 3 (EVO↔TRYST TEIP 1.1) status: COMPLETE and verified live** per TRYST
`PHASE-3-FINAL-CLOSURE.md` (2026-11-16): TEIP 1.1 works in the actual runtime (live EVO server
session), 37/37 EVO TEIP tests, 10/10 EVO harness tests, 7/7 TRYST OpenCode adapter tests,
305-test TRYST suite, both products build.

**EVO-Studio had no Phase 1–3 work** — TRYST `PHASE-3-RECOVERY-AUDIT.md` decision D11: "EVO-Studio, EVO-Code
(and TEIP) are out of scope unless a Phase 1/2 contract requires it. EVO-Studio/DCoder repos contain
only skeleton files (no Phase 3 work)". Verified: no PHASE-* documents or implementation exist in
the EVO-Studio repository.

**Consequences for Phase 4:**
- There is no existing EVO-Studio architecture, MVC layer, persistence, build or tests to preserve.
  EVO-Studio Phase 4 is a **greenfield implementation** of the product, not a modification.
- Phase 4 dependencies that ARE verified available: canonical TEIP 1.1 spec; working EVO TEIP 1.1
  server (28080); working TRYST TEIP 1.1 client (`TeipClient`); both products' harness SPIs and
  OpenCode adapters (reusable knowledge, not reusable code).
- No Phase 4 activity depends on unverified Phase 3 EVO-Studio functionality because none existed;
  the only Phase 3 prerequisite (TEIP 1.1 contract + runtimes) is verified.

## 3. Installed Runtime Environment (verified)

| Item | Value | Evidence |
|---|---|---|
| OpenCode CLI | **1.19.5** at `/home/petr/.opencode/bin/opencode` | `opencode --version`; `opencode run --help` (flags incl. `--format json`, `--dir`, `--model provider/model`, `--agent`, `--auto`, `--print-logs`) |
| Aider CLI | **0.86.1** at `/home/petr/.local/bin/aider` | `aider --version`, `aider --help` (flags incl. `--model`, `--message`, `--yes-always`, `--no-auto-commits`, `--openai-api-base`) |
| Ollama | **0.40.16** at `127.0.0.1:11434` | `ollama --version`, `ollama list` |
| Ollama models | `qwen3-coder-30b-32k:latest` (OpenCode default), `qwen3-coder-next:32k`, `qwen3-coder:30b`, `deepseek-coder-v2:16b-lite-instruct-q4_K_M`, `evo*` models | `ollama list` |
| OpenCode config | `~/.config/opencode/opencode.jsonc`: model `ollama/qwen3-coder-30b-32k:latest`, agent `build`, providers ollama + deepseek (`deepseek-v4-pro`) | file content |
| Java | JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (system default is JDK 8 — builds MUST set `JAVA_HOME`) | `ls /usr/lib/jvm` |
| Maven | system 3.8.7 (incompatible per TRYST docs); **use wrapper dist 3.9.12** at `~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn` | TRYST `TRYST_BUILD.md`; filesystem |
| Tycho | 4.0.8 (TRYST) and 4.0.5 (EVO) cached in `~/.m2` | `~/.m2/repository/org/eclipse/tycho` |
| p2 cache | 415 bundles cached: **Eclipse 2024-03** (SWT 3.125, OSGi 3.19) and **Eclipse 2025-12** (SWT 3.132, OSGi 3.24, JDT 3.44, EGit/JGit 7.5, Search, Compare, Common Navigator, forms) | `~/.m2/repository/p2/osgi/bundle` |
| sqlite-jdbc | 3.45.2.0 cached; TRYST ships `lib/sqlite-jdbc.jar` in its infrastructure bundle | `~/.m2`; TRYST `plugins/eu.kalafatic.tryst.infrastructure/lib/` |
| X server / xvfb | **ABSENT** — `xvfb-run`, `Xvfb` not installed | `which` → empty. Desktop UI runtime tests are constrained; headless and plugin tests (useUIHarness=false) are unaffected |

## 4. Existing Family Architecture Findings (verified in source)

### 4.1 TEIP 1.1 (canonical, EVO repo)
HTTP/JSON under `/teip/v1`; endpoints: health, capabilities, `POST /executions` (202 new / 200
idempotent replay, `Idempotency-Key`), `GET /executions/{id}` (status), `GET .../events`
(`afterSequence` cursor, `nextSequence`, limit ≤ 500), `GET .../result` (409 CONFLICT until
terminal), `POST .../cancel` (idempotent). Error envelope top-level
`{protocol,version,code,message,retryable,details}`. Mandatory Bearer auth with explicit
dev-mode loopback opt-out. Events: EXECUTION_QUEUED/STARTED/PROGRESS_UPDATED/EXECUTION_LOG/
TOOL_INVOKED/COMPLETED/FAILED/CANCELLED. §10: EVO-Studio/EVO-Code participant roles are 2.0 material —
**1.1 does not define EVO-Studio-specific endpoints**; EVO-Studio must expose the §4 endpoint set as a
TEIP provider.

### 4.2 EVO (server side of TEIP, Darwin orchestrator)
- TEIP 1.1 server: `eu.kalafatic.evolution.controller.teip.*` — `TeipApiController`
  (NanoHTTPD session handler), `TeipExecutionManager` (executes via `KernelFacade` Darwin stack,
  NOT via OpenCode directly), `TeipPersistenceStore`/`TeipEventStore` (SQLite `data/evolution.db`,
  tables `teip_executions`/`teip_idempotency`/`teip_events`, no migration framework), auth via
  `evo.teip.auth.token` + `evo.teip.devMode` loopback opt-out. Ports: HTTP primary **28080**.
- Harness SPI: `controller/harness/CodingHarnessAdapter.java` (`executeTask(targetDir,
  instructions, prompt, logConsumer, cancellationSupplier, timeout)` + nested result records),
  `ConnectedApplicationRegistry` (singleton, JSON persistence `data/connected-applications.json`),
  `OpenCodeCodingHarnessAdapter` (verified v1.19.5 quirks: `--dir` required, stdin `/dev/null`,
  hermetic XDG redirection, `ollama/` model prefix normalization, CI=1, post-run git-status
  changed-file collection). No Aider adapter.
- DEVELOPER mode: `DeveloperEngine` + `CodingHarnessTool` (`coding_harness_execute`),
  UI entry `AiCommanderPage`. Tests: `DarwinDeveloperModeIntegrationTest`,
  `harness/ConnectedApplicationRegistryTest`.

### 4.3 TRYST (client side of TEIP, autonomous task platform)
- TEIP 1.1 client: `plugins/eu.kalafatic.tryst.evo.integration/.../client/TeipClient.java`
  (JDK HttpClient, 202/200, idempotency, cursor, error envelope, default endpoint 28080).
- Harness SPI: `plugins/eu.kalafatic.tryst.domain/.../domain/harness/` —
  `CodingHarnessAdapter`, `ConnectedApplicationRegistry`, `DefaultConnectedApplicationRegistry`,
  `ConnectedApplicationDescriptor`, `ConnectedApplicationRepository` (SQLite V17
  `connected_applications` table), `HarnessAiDelegator` (bridge to `TaskWorker`).
- Adapters: `OpenCodeCodingHarnessAdapter` (infrastructure bundle; same verified v1.19.5 CLI
  patterns as EVO; config binary/agent/model/autoApprove currently NOT persisted — defect),
  `EvoCodingHarnessAdapter` (TEIP bridge to EVO; type "EVO"; config persisted via
  `EvoConfiguration`/`SqliteEvoConfigurationRepository`).
- Runtime: single product, headless via `-headless` flag or second IApplication
  `eu.kalafatic.tryst.application.server` (`TrystServerApplication`); lifecycle state machine
  `ApplicationLifecycleStage` 9 stages; `ApplicationLifecycleCoordinator` boots core services in
  both modes; HTTP server = JDK `com.sun.net.httpserver.HttpServer` (port 38080, loopback);
  persistence `~/.tryst/tryst.db` + `~/.tryst/tryst.properties`; UI never touched in headless path.
- Desktop: `TrystApplication` → `PlatformUI.createAndRunWorkbench`, main perspective with
  navigator + console + multipage editor; workbench state via `setSaveAndRestore(true)`.
- Tests: 77 classes, tycho-surefire `useUIHarness=false`, `mvn clean verify` offline.

### 4.4 EVO-Code/DCoder
README-only skeleton. Defines itself as "the native coding agent of EVO-Studio" with a "defined
communication interface" (unspecified), standalone operation, Java 21 RCP/OSGi/Tycho. **No
implementation exists** — Phase 4 integration for EVO-Code can only define the contract and
dependencies, not implement a connector against a non-existent runtime.

## 5. Architectural Gaps and Risks (Phase 4 input)

| # | Gap / Risk | Severity | Mitigation |
|---|---|---|---|
| G1 | EVO-Studio has no code, build, docs or tests — everything must be created | HIGH | Greenfield build modeled on TRYST's proven Tycho/RCP/headless structure; incremental WPs with build+test gates |
| G2 | No xvfb/X server → desktop workbench runtime cannot be verified on this host | HIGH | tycho-surefire plugin tests (headless harness) + honest documentation; mark desktop runtime checks "not verified on this host" |
| G3 | TEIP 1.1 defines EVO-Studio only as a family name; provider endpoint set = §4 reuse; EVO-Studio-specific fields must stay open-world extensions | MED | Implement exactly the §4 canonical endpoints; any EVO-Studio-specific request field goes under `parameters.*` (open-world rule) |
| G4 | TRYST OpenCode adapter does not persist binary/agent/model config (restart loses it) | MED | EVO-Studio harness config MUST be persisted (SQLite) — EVO-Studio becomes the authoritative, persistent harness platform |
| G5 | EVO/TRYST each have their own local harness registries/adapters — duplication Phase 4 must end | MED | Add EVO-Studio TEIP adapters into the EXISTING registries (integration boundary only); no registry rewrites |
| G6 | opencode 1.19.5 model naming quirk (bare id → `Model not found: <id>/`) | MED | `provider/model` normalization in EVO-Studio adapter (pattern verified in both EVO and TRYST adapters) |
| G7 | aider 0.86.1 behavior is NOT documented in any family repo (no adapter exists anywhere) | MED | WP-4.06 performs live CLI probes before finalizing the adapter (per EVO AGENTS.md §6 / TRYST AGENTS.md §13) |
| G8 | Port 28080 occupied by unrelated third-party app (per TRYST E2E report) | LOW | EVO-Studio uses its own port family: TEIP 48080, diagnostics 48180 (family convention +10000 offset); configurable |
| G9 | EVO `TeipExecutionManager` executes the Darwin engine (not a harness) — TEIP semantics vary per provider | LOW | EVO-Studio TEIP provider executes registered harnesses; semantics per TEIP §4 (external execution states) |
| G10 | EVO has no TEIP *client* class today (only server); TRYST `TeipClient` is in `evo.integration` bundle | LOW | EVO connector implements a small EVO-Studio TEIP client in its controller harness package; TRYST connector reuses its existing `TeipClient` |

## 6. Reusable Knowledge Inventory (patterns to replicate, not code to copy)

| Pattern | Source (verified) | EVO-Studio use |
|---|---|---|
| Tycho 4.0.8 parent POM, thin module POMs, eclipse-target-definition, feature/product/tests modules | TRYST `pom.xml` + module layout | EVO-Studio build skeleton |
| `-headless` flag + second IApplication + lifecycle stage machine + shutdown latch; SWT untouched in headless | TRYST `TrystApplication`/`TrystServerApplication`/`ApplicationLifecycleCoordinator` | EVO-Studio dual-mode runtime |
| JDK `HttpServer` with Bearer auth, rate limiting, dev-mode explicit opt-out | TRYST `TrystHttpServer` | EVO-Studio TEIP HTTP layer |
| SQLite via bundled sqlite-jdbc, forward-only migrations V1..N, `~/.product/db` location | TRYST `SqliteDatabaseManager`/`SchemaMigrationService` | EVO-Studio persistence |
| Harness SPI shape (executeTask with logConsumer/cancellationSupplier/timeout; result records; registry + descriptors + selection + persistence) | EVO + TRYST `CodingHarnessAdapter`/`ConnectedApplicationRegistry` | EVO-Studio authoritative SPI (extended: event sink, persisted config, capability discovery) |
| OpenCode v1.19.5 verified execution recipe (flags, stdin /dev/null, hermetic env, model normalization, post-run git-status collection, containment check) | EVO + TRYST `OpenCodeCodingHarnessAdapter` in-code comments + live probes | EVO-Studio OpenCode adapter |
| TEIP 1.1 server implementation semantics (202/200, idempotency persisted, cursor events, 409 result, idempotent cancel, reconcile-on-restart) | EVO `teip/*` | EVO-Studio TEIP provider |
| tycho-surefire `useUIHarness=false` + offline `mvn clean verify` | TRYST tests POM | EVO-Studio test infrastructure |

## 7. Current-State vs Target-State Summary

| Concern | Current (EVO-Studio) | Target (Phase 4) |
|---|---|---|
| Code | none | 6 bundles: core, infrastructure, harness, teip, application, ui |
| Build | none | Tycho 4.0.8, Java 21, Eclipse 2025-12 target, feature/product/tests |
| Desktop | none | Professional RCP IDE: JDT editors, project explorer, console, harness execution, diff review, perspectives |
| Headless | none | `-headless` / `-application ...server`, TEIP 1.1 provider on 48080, no SWT |
| Harness platform | none | One SPI + registry (persisted); OpenCode + Aider adapters |
| Family integration | none | EVO + TRYST connectors (existing registries); EVO-Code contract documented |
| Persistence | none | `~/.dstudio/dstudio.db` + `~/.dstudio/dstudio.properties` + Eclipse `-data` for workbench state |
