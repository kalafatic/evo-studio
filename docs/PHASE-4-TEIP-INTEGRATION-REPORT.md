# PHASE-4-TEIP-INTEGRATION-REPORT.md

**EVO-Studio Phase 4 — WP-4.09 deliverable**
**Date:** 2026-11-16

## 1. Scope

EVO-Studio implements the **TEIP 1.1 provider** endpoint set per the canonical specification
(`evolution-121126/docs/protocol/TEIP-1.1.md`, NORMATIVE). EVO-Studio is the first family product
besides EVO to expose the provider interface. Canonical endpoint default for EVO-Studio:
`http://localhost:48080` (family port convention +10000 per product; configurable).

## 2. Implementation

Bundle `eu.kalafatic.dstudio.teip` (pure Java, no SWT — loads in headless and desktop modes):

| Class | Role |
|---|---|
| `TeipWire` | Wire DTOs + canonical error-code table (HTTP status + retryability) |
| `TeipHttpHandler` | HTTP routing for all `/teip/v1/*` endpoints, auth enforcement, JSON parse/serialize (deprecated aliases `aiMode`/`mcpToolScopes`/`caller.app` accepted), 5 MB limit, `Idempotency-Key` header fallback |
| `TeipExecutionManager` | Request validation → `ExecutionService` mapping; harness selection (`parameters.harness` → selected default), `parameters.workingDirectory` (documented EVO-Studio extension, open-world), model resolution, status/events/result/cancel mapping |
| `TeipAuth` | `dstudio.teip.auth.token` (sysprop/env) mandatory Bearer OR explicit `dstudio.teip.devMode=true` + loopback-only (never a silent default) |
| `DStudioServer` | JDK `HttpServer` — `/teip/v1` + `/api/status` + `/api/diagnostics` |

Contract conformance (TEIP 1.1):
- §4.3: **202 Accepted** new / **200 OK** idempotent replay; links map; `QUEUED` initial state.
- §4.4: status + progress; `TIMED_OUT` mapped to `FAILED` (canonical has no TIMED_OUT state).
- §4.5: event cursor `afterSequence`/`nextSequence` (last+1, 0-bounded), `limit` ≤ 500,
  sequences persisted in SQLite (survive restarts).
- §4.6: result 200 terminal / **409 CONFLICT** with `details.currentStatus`; `patch`
  from unified diffs, `modifiedFiles`, `metrics.durationMs`.
- §4.7: idempotent cancel; unknown id → 404 `NOT_FOUND`.
- §5: canonical error envelope + full code table; `VERSION_MISMATCH` for non-1.x majors.
- §6: mandatory auth with explicit dev-mode opt-out; secrets never logged.
- §7: persisted idempotency (`execution_idempotency` table); restart reconciliation
  (open executions → FAILED "cannot be resumed", verified in service tests).
- Event types identical to canonical set (`EXECUTION_QUEUED` … `EXECUTION_CANCELLED`).

## 3. Test Evidence (2026-11-16, real HTTP)

| Suite | Result |
|---|---|
| `TeipApiEndpointTest` (14, live JDK HttpServer) | 14/14 PASS — health, capabilities, 202/200 idempotent replay (body key + header fallback), status, events cursor + `nextSequence` + limit, result 200/409, idempotent cancel + 404, `VERSION_MISMATCH` (2.0 → 400), missing instruction/workingDirectory → `VALIDATION_FAILED` with `details.field`, unknown harness → 422 `CAPABILITY_UNSUPPORTED`, 405 `METHOD_NOT_ALLOWED` + `Allow` header, 404 unknown path, deprecated aliases, Bearer enforcement (401 missing/wrong, 200 correct) |
| `TeipOpenCodeEndToEndTest` (1, REAL OpenCode through HTTP) | 1/1 PASS — external client → `POST /teip/v1/executions` (202) → real OpenCode 1.19.5 executed in isolated git workspace → `teip-marker.txt` with `dstudio-teip-e2e-ok` created → status polling to COMPLETED → result 200 with `modifiedFiles` including the marker → ≥3 streamed events (20.2 s) |
| Full reactor | 73/73 tests, BUILD SUCCESS |

## 4. EVO-Studio-Specific Extensions (open-world, TEIP §2)

- `parameters.harness` — explicit harness selection per request (openCode|aider).
- `parameters.workingDirectory` — target directory for the execution (required unless
  `parameters.harness` absent and a EVO-Studio default is selected; EVO-Studio executes on the
  local host for local family products).
- `parameters.timeoutSeconds` — per-request timeout (capped by configuration).
- `health.details.harnesses`, `health.details.serverPort` — provider diagnostics.

No private TEIP variant: all canonical fields, aliases and error codes behave exactly as the
normative specification requires; clients ignoring EVO-Studio extensions remain fully compatible.

## 5. Known Limitations

- Push streaming (SSE/WebSocket) is TEIP 2.0 material — 1.1 pull-based event polling only
  (canonical behavior).
- `result.artifacts` is always `[]` (no artifact packaging implemented; patch+modifiedFiles
  are authoritative).
- DeepSeek/OpenAI-compatible provider execution paths are configured but were not probed
  with live credentials.
