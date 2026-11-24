# PHASE-6-DSTUDIO-INTEGRATION-REPORT.md

Phase 6 — EVO-Studio integration summary (master report: EVO repository
`PHASE-6-DSTUDIO-INTEGRATION-REPORT.md`)
Date: 2026-11-17

## Result

- **EVO-Code is the default coding harness**: `eu.kalafatic.dstudio.harness.dcode.
  DCodeHarnessAdapter` (FHC 1.0 wire mapping over EVO-Code `/api/v1`), registered in
  `DStudioLifecycleCoordinator.onCoreServiceInitialization()` and selected as default
  when nothing is explicitly selected; OpenCode/Aider remain registered.
- **ENR is the default inference engine**: new `eu.kalafatic.dstudio.core.inference`
  subsystem — `InferenceEngineAdapter` SPI, `InferenceEngineRegistry` (+ SQLite store,
  migration V2), `EnrInferenceEngineAdapter` (default) and `OllamaInferenceEngineAdapter`
  (alternative). Harness and inference engines are independently configurable and
  replaceable.
- **Headless access**: `InferenceHttpHandler` mounted under `/api/inference` on the
  EVO-Studio server (engines, models, load/unload, runtime status, chat); TEIP 1.1 surface
  unchanged.
- **IDE access**: new `InferenceView` (engines + models, set-default/enable/test/
  refresh/load/unload) added to the Harness perspective; EVO-Code is manageable through the
  existing HarnessView.
- **Persistence**: inference-engine state persists in `dstudio.db` (V2); execution
  history/review persistence unchanged for EVO-Code executions (same `ExecutionService`
  path with restart reconciliation).

## Verification (this repository)

- `mvn -o clean verify` — **BUILD SUCCESS — 102 tests, 0 failures, 0 errors, 0 skipped**
  (includes `DCodeHarnessAdapterTest` 9/9, `InferenceEngineAdapterWireTest` 8/8,
  `InferenceEngineRegistryTest` 5/5, `InferenceHttpApiTest` 7/7).
