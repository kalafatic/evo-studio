# EVO-Studio — Unified Model Management Integration (shared ENR infrastructure)

Date: 2026-11-22 (updated after the initial functional-testing iteration)
Status: Implemented, tested and live-verified (EVO-Studio 2.6.11).

## 1. Objective

EVO-Studio consumes the shared family model management service owned by ENR —
it holds **no** model management state, no download jobs and no provider
registry of its own. Pulls, progress, cancellation and removal execute in
ENR; EVO-Studio serves them through its embedded backend and both UIs.

## 2. Components

| Component | Location | Role |
|---|---|---|
| `ModelManagementHttpHandler` | `eu.kalafatic.dstudio.teip.server` (path `/api/models`) | Passthrough to ENR `/api/v1/models/*`. ENR endpoint: the `dstudio.enr.endpoint` system property, then the configured ENR inference engine (config key `endpoint`), then the family default `http://localhost:28780`. The lifecycle coordinator resolves the live ENR endpoint at startup (explicit override → live probe of the well-known endpoints — standalone 8111, EVO-embedded 28780 → default) and persists it through the engine registry, so this handler and the ENR adapter follow one authoritative endpoint. Upstream failures → `UPSTREAM_UNAVAILABLE` (503) |
| `DStudioServer` registration | `eu.kalafatic.dstudio.teip.server` | Serves the handler whenever the inference registry exists (desktop and headless alike) |
| `ModelManagementClient` | `eu.kalafatic.dstudio.application.models` | RCP-side client over the local backend; plain DTO records (no Jackson types cross bundles) |
| Web Model Manager panel | `teip/web/index.html` + `app.js` | Provider select, model input, Pull, installed list, live downloads (1 s polling), Cancel/Remove |
| RCP `ModelManagerView` | `eu.kalafatic.dstudio.ui.inference` (id `eu.kalafatic.dstudio.ui.view.modelmanager`, Harness perspective) | Same operations; single-thread executor + `Display.asyncExec`, timer polling cancelled on dispose |

## 3. Compatibility note

The backend keeps serving the inference-engine surface (`/api/inference`)
unchanged; model management is a separate surface. Authentication follows
the same bearer-token/dev-mode rules as every other `/api/*` route.

## 4. Verification

- `eu.kalafatic.dstudio.tests.ModelManagementHttpApiTest` — 8 hermetic cases
  against a mock ENR. Full suite: 224 tests, 0 failures.
- Live headless product (this host): the ENR endpoint is auto-resolved and
  persisted (`enr → http://127.0.0.1:8111`) on a fresh configuration
  directory; `/api/models/providers` (ollama reachable through ENR),
  installed models (real Ollama models), pull → job `QUEUED` →
  `COMPLETED` ("already installed"), jobs list shared with ENR.
  See ENR `ENR-MODEL-MANAGEMENT.md` for the family view.
