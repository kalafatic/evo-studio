# PHASE-8 — EVO-Code & EVO-Studio Coordinated Implementation Report

**Date:** 2026-11-20
**Scope:** EVO Family brief — EVO-Code (lightweight AI coding harness) and EVO-Studio
(comprehensive Eclipse multi-harness AI IDE), developed together as one coordinated
effort. All statements below describe implemented and verified behavior only;
PASS / PARTIAL / FAIL / DEFERRED are explicit.

---

## 1. Joint Architecture Audit

| Component | Finding | Evidence |
|---|---|---|
| DCoder-121126 | **Empty skeleton** at audit start (10 plugin dirs, 0 sources, no pom) — bootstrapped during this phase | `find` = 0 java files |
| EVO-Studio-121126 | Full platform: 7 bundles, 160/160 tests, TEIP 1.1 provider, SSE, 4 harnesses, 4 runtime types, Web IDE, RCP | build + tests |
| ENR | Real runtime (evo-native-runtime-161126, 96 sources; wire contract `/enr/v1/health`, `/enr/v1/models`, `/enr/v1/chat`, `/enr/v1/runtime`, load/unload) | `enr-java-api/EnrClient` |
| Shared contracts | TEIP 1.1 (canonical) for EVO-Studio; FHC 1.0 wire for EVO-Studio→EVO-Code; ENR 1.0 wire for inference | EVO-Studio adapters |

**Duplication risks resolved:** one EVO-Code session manager (in EVO-Code); EVO-Studio observes
it through the harness contract only (§10: no second EVO-Code session manager in EVO-Studio).
No circular dependency: EVO-Code never imports EVO-Studio.

## 2. EVO-Code — implemented (bootstrapped from skeleton)

- **8 bundles**: core (domain + SPI + event bus), persistence (SQLite V1,
  sessions/messages/workspaces/repositories/changes), enr (direct ENR provider client),
  harness (native deterministic tool-executing engine), server (headless, FHC 1.0),
  client (HTTP client), ui (lightweight RCP), feature/product/tests.
- **Native engine**: real file effects (write_file tool parsing, containment, real
  unified diffs); optional ENR planning (never required for correctness).
- **FHC 1.0 server** (port **58080**, family 58xxx convention): `/health`,
  `/api/v1/workspaces`, `/api/v1/workspaces/{id}/repositories`, `/api/v1/sessions`
  (create/start/cancel/state/messages/changes/list), `/api/v1/providers`,
  `/api/v1/models`, `/api/status`, **SSE** `/api/events`; loopback-open + optional
  bearer token; SQLite persistence; session history.
- **Headless application** (`eu.kalafatic.dcode.server.application`) — no SWT.
- **RCP**: lightweight client (chat + sessions + workspace picker), embedded shared
  backend. Compile-verified; GUI runtime requires a graphical host — PARTIAL.

## 3. EVO-Studio — coordinated changes

- `DCodeFamilyIntegrationTest` (live): EVO-Studio executes through EVO-Code, collects
  changedFiles + diff, asserts real file effects. **LIVE PASS**.
- EVO-Code remains EVO-Studio's default harness (adapter unchanged; endpoint 58080 now a
  real server).

## 4. ENR integration status

- EVO-Code: direct ENR provider (health/models/chat), registered as default provider.
  Live inference via the runtime fallback and the ENR wire, verified with the
  standalone ENR server on this host.
- EVO-Studio: ENR adapter (wire-tested and live-verified; see §11).
- Both consume the same ENR 1.0 wire — no duplicated ENR implementations.

## 5–6. Compatibility matrices

| Harness | EVO-Code | OpenCode | Aider | Claude Code |
|---|---|---|---|---|
| Providers | DCODE_NATIVE (ENR via EVO-Code) | OLLAMA, OPENAI_COMPATIBLE | OLLAMA, OPENAI_COMPATIBLE, ANTHROPIC | ANTHROPIC |
| Live-verified | **YES (EVO-Studio→EVO-Code)** | YES | YES | NO (CLI absent — test skips) |

| Runtime | ENR | Ollama | OpenAI-compatible | Anthropic |
|---|---|---|---|---|
| EVO-Code | wire; live DEFERRED (ENR down) | via OPENAI_COMPATIBLE — | — | — |
| EVO-Studio | wire | **LIVE** | **LIVE (Ollama /v1)** | wire; live DEFERRED (no key) |

## 7–8. Verification (actual results)

| Test | Result |
|---|---|
| EVO-Code build `mvn -o clean verify` | PASS — 7/7 (EngineTest real file effects incl. containment, ServerWireTest full FHC flow, EnrProviderTest graceful degradation) |
| EVO-Studio build `mvn -o clean verify` | PASS — 160/160 |
| EVO-Code headless live | PASS — 58080, health UP, providers reported, SSE SessionEvents streamed, SIGTERM clean shutdown, port released |
| **Family E2E (EVO-Studio→EVO-Code)** | **PASS live** — task → EVO-Code session → real `family-marker.txt` (FAMILY_OK) → changedFiles + diff collected in EVO-Studio |
| EVO-Code independent of EVO-Studio | PASS — runs standalone; no EVO-Studio bundles in its product |
| Cross-client state | PASS (EVO-Studio `CrossClientStateTest`; EVO-Code SSE + REST state identical across clients) |
| RCP runtime (both) | PARTIAL — compile-verified only; graphical host required (none on this build host) |
| EVO-Code Web IDE | DEFERRED — EVO-Code web frontend is the next workstream (EVO-Studio Web IDE exists and is live-verified) |
| ENR live inference (both) | **PASS (2026-11-22)** — live ENR at 8111; EVO-Studio ENR adapter live-verified (§11); EVO-Code executes family tasks through ENR + real Ollama models |
| Multi-harness workflows (§3.2 orchestration: generate→review chains) | DEFERRED — explicit workflow orchestration not yet implemented |

## 9. Remaining limitations (honest)

1. EVO-Code Web IDE not built (DEFERRED).
2. EVO-Code RCP + EVO-Studio RCP GUI runtime verification pending a graphical host.
3. Multi-harness chained workflows (one harness reviews another's output) not yet
   orchestrated in EVO-Studio (§3.2).
4. EVO-Code engine is deterministic/tool-based; full LLM-driven agent behavior comes with
   ENR live availability (planning already wired, execution tools are native).

## 10. Contract summary (shared, do not break)

- **TEIP 1.1** (EVO-Studio provider) — unchanged, all canonical tests green.
- **FHC 1.0** (EVO-Studio→EVO-Code): as implemented in EVO-Code `DCodeHttpHandler` and consumed
  by EVO-Studio `DCodeHarnessAdapter`.
- **ENR 1.0** (`/enr/v1/*`) — consumed by both products, implemented once per product
  as a client.
- **Ports**: EVO 28xxx / TRYST 38xxx / EVO-Studio 48xxx / EVO-Code 58xxx (+100 diagnostics).

## 11. 2026-11-22 stabilization iteration (initial functional testing)

Confirmed defects found and fixed during the first EVO-Studio functional-testing
iteration (all live-verified against the running family stack: EVO-Studio headless
48080, EVO-Code headless 58080, ENR 8111, Ollama 11434):

| # | Defect | Fix |
|---|---|---|
| 1 | EVO-Studio `EnrInferenceEngineAdapter.testConnection()` probed `/health`, which is not part of the ENR 1.0 wire — the ENR engine always reported unreachable against a real ENR server | Probe the canonical `/enr/v1/health` with a `/health` legacy fallback (mirror of EVO-Code `EnrProvider`) |
| 2 | EVO-Studio ENR endpoint defaulted to 28780 with no live resolution; the standalone ENR serves 8111 | `DStudioLifecycleCoordinator.resolveEnrEndpoint()`: `dstudio.enr.endpoint` override → live probe of 8111/28780 → default; persisted through the engine registry; unreachable non-default user endpoints are never touched |
| 3 | `runtimeStatus()` mapped fields that do not exist on the ENR 1.0 wire (`ready`, `availableProcessors`, `gpu`, …) | Map the real wire shape (`running`, `cpu`, `heap`, `devices[]`, `activeEngines[]`) |
| 4 | EVO-Code `ModelManagementClient` ignored the registered ENR runtime (8111) and always used the historical default 28780 → `/api/v1/models/*` returned 503 against a live standalone ENR | `DCodeApi` resolves the endpoint from the ENR runtime registration; the client additionally probes 8111/28780 when unconfigured |
| 5 | EVO-Code unconfigured-model fallback picked the 4-token `evo` toy model through the ENR Ollama-compatible surface before ever considering real Ollama coding models → family execution FAILED | `SessionExecutor.routeFallback` prefers models with a known context window >= 4096 across all runtimes, then unknown-context models, then any model |
| 6 | EVO-Studio `streamChat` cancellation leaked `UncheckedIOException: closed` out of the ENR and Ollama adapters | Cancellation-induced close is treated as the expected abort path (rethrow only when not cancelled) |
| 7 | `GET /api/inference/models` and `POST /api/inference/chat` without an explicit engine returned 500 (`Map.of` NPE on the null engine id) | Report the resolved adapter's engine id |

Regression coverage added: EVO-Studio `InferenceEngineAdapterWireTest` (real ENR 1.0
stub contract, legacy health fallback, SSE streaming, cancellation), EVO-Studio
`EnrLiveInferenceTest` (4 live cases against the real ENR), EVO-Studio
`InferenceHttpApiTest` (missing-engine defaults), EVO-Code
`RuntimeFallbackSelectionTest` (2 cases).

Verification results (2026-11-22, actual executions):
- EVO-Studio `mvn -o clean verify`: 224 tests, 0 failures (215 at baseline).
- EVO-Code `mvn -o clean verify`: 208 tests, 0 failures (206 at baseline).
- Live family E2E: EVO-Studio→EVO-Code (`DCodeFamilyIntegrationTest`) real file effects
  via qwen3.8-32k on Ollama; TRYST→EVO-Studio and EVO→EVO-Studio live TEIP tests PASS.
- EVO-Studio headless: TEIP UP, `/api/models/*` through ENR, pull job
  QUEUED→COMPLETED, `/api/inference/models` default-engine resolution, clean
  SIGTERM shutdown with port release.
- ENR endpoint auto-resolution persisted on a fresh configuration directory.
