# PHASE-4-DCODE-COMPATIBILITY-REPORT.md

**EVO-Studio Phase 4 — WP-4-FINAL.5 deliverable (read-only inspection)**
**Date:** 2026-11-16
**Status:** EVO-Code runtime integration remains **DEFERRED to the parallel Phase 5 work** (Product
Owner decision). This report verifies contract compatibility as far as the current Phase 5
state permits. No EVO-Code files were modified.

## 1. EVO-Code Current State (verified by inspection)

- Repository `/home/petr/git/DCoder-121126`: Phase 5 actively in progress. 7 bundles
  (`config`, `core`, `harness`, `java.tooling`, `persistence`, `repositories`, `server`),
  Tycho build, tests, and WP reports (`PHASE-5-ARCHITECTURE-BASELINE.md`,
  `PHASE-5-WP-5.6-JDT-IMPLEMENTATION-REPORT.md`, `PHASE-5-WP-5.9-API-SSE-AUDIT.md`).
- EVO-Code owns a NanoHTTPD HTTP server (`/api/v1`) with Bearer auth **only when a token is
  configured** (explicit opt-out, mirroring TEIP 1.1 §6 conventions) and a TEIP-shaped
  top-level error envelope.
- `ExecutionState` = `QUEUED | RUNNING | WAITING | COMPLETED | FAILED | CANCELLED` — the
  **exact canonical TEIP 1.1 external state set** (§4.4).
- **No TEIP client and no EVO-Studio connector exist yet** — EVO-Code currently has no
  EVO-Studio-facing code (expected: family integration is its WP5.12).
- EVO-Code `AGENTS.md`: family integration is wire-only (TEIP 1.1 / FHC), DTOs owned per
  product, no product-to-product dependencies — consistent with the Phase 4 architecture.

## 2. EVO-Studio Contract (what EVO-Code connects to — implemented and live-tested)

EVO-Studio exposes the canonical TEIP 1.1 provider endpoint set at `http://localhost:48080`
(`GET /teip/v1/health`, `GET /teip/v1/capabilities`, `POST /teip/v1/executions` (202/200 +
persisted idempotency), `GET .../status`, `GET .../events` (`afterSequence`/`nextSequence`
cursor), `GET .../result` (409 until terminal), `POST .../cancel`; canonical error
envelope/codes; mandatory Bearer or explicit `dstudio.teip.devMode` loopback opt-out).
EVO-Studio-specific request fields are confined to `parameters.*` (open-world):
`parameters.workingDirectory` (required), `parameters.harness` (`opencode` | `aider`),
`parameters.timeoutSeconds`. The provider is proven live by EVO and TRYST connectors
(`DStudioLiveE2eTest` in both products, real OpenCode/Aider executions, 2026-11-16).

## 3. Compatibility Verification Matrix (EVO-Code ↔ EVO-Studio)

| Requirement | EVO-Studio provides | EVO-Code state | Verdict |
|---|---|---|---|
| TEIP 1.1 wire compatibility | ✅ full provider | no client yet | 📄 contract sufficient; client to be implemented |
| Client identity (`caller.system=DCODE`) | ✅ accepted uniformly (any system value) | no caller yet | ✅ no EVO-Studio change needed |
| Harness discovery (`/capabilities`) | ✅ | not consumed yet | ✅ contract sufficient |
| Request structure (canonical fields + `parameters.*`) | ✅ | not implemented yet | ✅ contract sufficient |
| `parameters.workingDirectory` | ✅ implemented | EVO-Code has sandbox/workspace concept | ✅ compatible |
| `parameters.harness` | ✅ (`opencode`, `aider`, structured 422 on unknown) | EVO-Code harness SPI exists (native agent) | ✅ compatible (EVO-Code may use native agent locally; EVO-Studio path is optional) |
| Session/task identity | ✅ opaque ids + `correlationId` | session-centric ids | ✅ compatible (opaque strings) |
| Streaming/event correlation | ✅ `EXECUTION_*` events, persisted cursor | EVO-Code SSE lacks monotonic ids (own server, WP5.9 gap) | ⚠️ EVO-Code client must implement cursor polling; EVO-Studio side is standard |
| Cancellation | ✅ idempotent | — | ✅ contract sufficient |
| Result + changed files | ✅ `modifiedFiles`, `patch`, metrics | — | ✅ contract sufficient |
| Error mapping | ✅ canonical envelope/codes | EVO-Code server already uses TEIP error shape | ✅ compatible |
| Auth | ✅ Bearer / explicit dev-mode opt-out | same explicit opt-out model on EVO-Code server | ✅ compatible |

## 4. Missing Implementation (EVO-Code side)

1. A TEIP 1.1 client (reference implementations: EVO `DStudioTeipClient`, TRYST `TeipClient`).
2. A `DStudioCodingHarnessAdapter` equivalent registered in EVO-Code's harness SPI, with
   `caller.system = "DCODE"`.
3. Event-cursor handling (`afterSequence`/`nextSequence`) in the client polling loop.
4. Timeout/cancellation mapping to EVO-Code's task lifecycle (EVO-Code stays sovereign).

## 5. Required Changes on the EVO-Studio Side

**None identified.** The provider contract is generic over callers and proven by two
independent client implementations. If EVO-Code needs a model for its native agent advertised
via `availableModels`, EVO-Studio already aggregates per-harness model descriptors.

## 6. Sufficiency of the Current EVO-Studio Contract

**Sufficient.** The same contract satisfied EVO and TRYST live end-to-end. Nothing in
EVO-Code's verified state (execution-state set, error model, auth conventions, sandbox
concept) conflicts with it. One documented nuance: the Phase 5 baseline document
(`PHASE-5-ARCHITECTURE-BASELINE.md`) was written before the Phase 4 EVO-Studio implementation
existed and states "EVO-Studio ... Phase 4 never implemented" — that statement is now stale;
EVO-Studio Phase 4 IS implemented and live-tested. The Phase 5 WP5.12 planning should treat
EVO-Studio as an available TEIP provider rather than deferring EVO-Studio roles to TEIP 2.0.

## 7. Exact Next Integration Steps (when Phase 5 reaches WP5.12)

1. EVO-Code implements a TEIP 1.1 client (JDK HttpClient; no new dependencies).
2. EVO-Code registers a `EVO-Studio` harness adapter (type `DSTUDIO`) in its existing harness
   registry, endpoint default `http://localhost:48080`, config keys endpoint/authToken/
   harness/model/timeoutSeconds (persisted per EVO-Code conventions; token never persisted).
3. Wire the adapter into EVO-Code's task/execution orchestration exactly like TRYST's
   `HarnessAiDelegator` seam.
4. Run the shared live test template: start EVO-Studio headless (`./dstudio -headless`),
   then `EVO-Code → POST /teip/v1/executions {caller.system:"DCODE", parameters.workingDirectory,
   parameters.harness:"opencode"|"aider"}` → poll status/events → assert real file changes
   in EVO-Code's sandbox and `modifiedFiles` in the result.
5. No EVO-Studio, EVO or TRYST changes required.

## 8. Classification

- **EVO-Code runtime E2E: DEFERRED** to parallel Phase 5 (no connector exists yet; nothing to
  test against EVO-Code).
- **Contract compatibility: PASS** (verified against EVO-Code's actual Phase 5 code/docs;
  no conflicts; EVO-Studio contract sufficient).
- **EVO-Studio-side changes required: none.**
