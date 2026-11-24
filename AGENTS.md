# EVO-Studio – Agent Instructions

## 1. Project Identity

EVO-Studio is an independent Java 21 Eclipse RCP / Equinox / OSGi application and server platform.

EVO-Studio is **one product with two runtime faces sharing one authoritative domain/application
core**:

1. **Desktop mode** — professional Eclipse RCP WYSIWYG IDE (projects, source editing,
   harness execution, monitoring, review).
2. **Headless mode** — server-side Harness Platform exposing the same core through the
   canonical TEIP 1.1 provider endpoint set (`/teip/v1/*`), without SWT, workbench or X.

EVO-Studio owns the coding-harness infrastructure for the product family (EVO-Code,
OpenCode, Aider, future adapters). EVO, TRYST and other family products connect to
EVO-Studio through TEIP; they keep their own domains, workflows and persistence.

## 2. Fundamental Development Principles

- This is an existing, evolving codebase. Improve its architecture; do not rewrite or replace it.
- Preserve public behavior, APIs, SPI contracts, TEIP behavior, persistence and lifecycle
  compatibility unless explicitly instructed otherwise.
- Prefer minimal, targeted fixes over broad refactoring.
- Before changing code, inspect the relevant implementation, callers, interfaces, services,
  events, persistence and tests.
- Reuse existing services and abstractions. Do not introduce parallel architectures,
  duplicate managers, registries, event buses or competing sources of truth.
- Distinguish confirmed facts from assumptions. Never invent classes, endpoints, database
  fields or test results.
- When asked to implement, implement and verify; do not stop after analysis.
- If blocked, identify the exact obstacle and use another valid route before declaring a blocker.

## 3. Technology Constraints

- Java 21, Eclipse RCP, Equinox/OSGi, SWT/JFace, Maven/Tycho 4.0.8, Eclipse 2025-12 target.
- SQLite (bundled `lib/sqlite-jdbc.jar`), forward-only migrations V1..N in
  `eu.kalafatic.dstudio.infrastructure`.
- JDK `com.sun.net.httpserver.HttpServer` for the TEIP/diagnostics server.
- Jackson 2.20 (bundled in infrastructure, exported) for JSON.
- JGit via `Import-Package` for repository status/diff.
- No Spring, Node.js, Python runtime, message brokers, microservices, web dashboards.

## 4. Bundle Architecture (dependency direction is enforced)

```
eu.kalafatic.dstudio.ui            → presentation (views, editors, perspectives, dialogs,
                                     commands/handlers) — NO business logic, processes,
                                     persistence or TEIP
eu.kalafatic.dstudio.application   → lifecycle coordinator + desktop/headless IApplication
eu.kalafatic.dstudio.teip          → TEIP 1.1 provider (HTTP, DTOs, auth, execution bridge)
eu.kalafatic.dstudio.harness       → harness adapters (opencode, aider)
eu.kalafatic.dstudio.infrastructure→ SQLite persistence, configuration, JGit, process support
eu.kalafatic.dstudio.core          → domain, SPI, application services, event bus (no UI)
```

- `ui → application → {teip, harness, infrastructure} → core`. Nothing may depend upward.
- Core contains zero references to SWT, workbench, `eu.kalafatic.evolution.*` or
  `eu.kalafatic.tryst.*`. Verify before adding any dependency.
- Desktop and headless must keep sharing the same core services; never create a second
  registry, execution service or event system for a mode.

## 5. Harness Platform

- The SPI (`eu.kalafatic.dstudio.core.harness`) is authoritative; provider concepts
  (agents, provider prefixes, auto-approve) are adapter config keys, never universal
  capabilities.
- Adapter additions = new class in the harness bundle + registration in
  `DStudioLifecycleCoordinator.onCoreServiceInitialization()`. No IDE/TEIP changes.
- Harness config is persisted via `HarnessStore` (SQLite). Do not regress to in-memory-only.
- Never write secrets into logs, events, db or configuration exports.
- Real CLI behavior must be verified against the installed version before relying on it;
  record live probes (flags, exit codes, quirks).

## 6. TEIP Provider

- The canonical specification is `evolution-121126/docs/protocol/TEIP-1.1.md` (NORMATIVE).
  EVO-Studio implements it as a provider; no private wire variants. EVO-Studio-specific
  fields go under `parameters.*` only (open-world rule).
- Preserve: 202/200 idempotent create, persisted idempotency, event cursor `nextSequence`,
  409 non-terminal result, idempotent cancel, canonical error envelope/codes, mandatory
  auth with explicit dev-mode opt-out (`dstudio.teip.auth.token` /
  `dstudio.teip.devMode`).
- Port conventions: TEIP 48080, diagnostics reserved 48180 (family +10000 convention;
  configurable via `~/.dstudio/dstudio.properties`).

## 7. Lifecycle and Persistence

- `DStudioLifecycleCoordinator` stages are monotonic:
  `APPLICATION_START → CONFIGURATION_INITIALIZATION → CORE_SERVICE_INITIALIZATION →
  APPLICATION_READY → (WORKBENCH_RUNNING) → APPLICATION_SHUTDOWN → SERVICE_DISPOSAL →
  APPLICATION_TERMINATED`.
- Persistence: `~/.dstudio/dstudio.db` (business state) + `~/.dstudio/dstudio.properties`
  (configuration); Eclipse `-data` workspace holds only workbench layout.
- Open executions are reconciled to FAILED on restart ("cannot be resumed").

## 8. Development Workflow

1. Identify the subsystem and read the relevant docs (`docs/PHASE-4-*.md`).
2. Inspect implementation, callers, services, events and persistence.
3. Implement the smallest complete change consistent with the architecture.
4. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   ~/.m2/wrapper/dists/apache-maven-3.9.12/6068d197/bin/mvn clean verify` (offline `-o`
   works with the populated p2 cache).
5. Run relevant tests; real-execution evidence over mocks at every external seam
   (harness CLIs, TEIP wire).
6. Verify headless behavior with the materialized product
   (`./evostudio -headless -vmargs -Ddstudio.config.dir=<dir>`); verify clean shutdown.
7. Update the relevant PHASE-4 report if architectural relationships change.
8. Report only work and verification that actually occurred.

## 9. Test Conventions

- Tycho Surefire: `useUIHarness=false` (this host has no X server; GUI runtime verification
  happens on graphical hosts).
- Live harness tests skip silently when the CLI is absent; they must assert real file
  effects, not mocked adapters.
- Family live tests (`DStudioLiveE2eTest` in EVO/TRYST) require a running EVO-Studio at
  `http://localhost:48080` (override: `-Ddstudio.live.endpoint=...`).
- JUnit pinned to 5.10.2 (2024-03 location in the .target); do not raise to 5.14/6.0
  without re-verifying Tycho compatibility.

## 10. Git and Destructive Operations

- Never run `git`, `gh` or GitHub operations; never commit, push, pull or modify history.
  Git is controlled exclusively by the user (family convention).
- Do not delete files or perform broad cleanup unless explicitly requested.
- Do not run destructive shell commands.

## 11. Completion Report

Report: confirmed requirement, modified files/classes, architectural reasoning, build and
test commands actually executed, actual results, runtime verification, remaining issues,
and anything not verified. Never present a proposal as completed work.

## 12. Governing Principle

EVO-Studio is one coherent product: a professional IDE and a headless Harness Platform on
one core, serving the whole product family through TEIP. Preserve the shared core, the SPI
authority and the TEIP contract above convenience.
