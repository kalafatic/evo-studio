# PHASE-4-FAMILY-REGRESSION-REPORT.md

**EVO-Studio Phase 4 — WP-4-FINAL.4 deliverable**
**Date:** 2026-11-16

## 1. EVO-Studio — PASS

| Check | Result |
|---|---|
| Full Tycho reactor (`mvn -o clean verify`) | **BUILD SUCCESS, 73/73 tests** |
| OpenCode adapter | PASS — unit + live real-CLI tests (marker file, events, honest failure) |
| Aider adapter | PASS — unit + live real-CLI test (marker, artifact filtering) |
| Harness registry | PASS — selection/persistence/capabilities (7 tests) |
| TEIP provider | PASS — 14 wire tests + real OpenCode-over-HTTP E2E |
| Headless startup/shutdown | PASS — product launched/terminated multiple times this session (dev-mode config, 48080) |
| Desktop startup | PASS — see `PHASE-4-DESKTOP-GUI-ACCEPTANCE-REPORT.md` (window, clean logs, clean shutdown) |

New hardening added during this pass (regression-protective, minimal):
- `OpenProjectHandler` null-shell robustness (defect from GUI runtime log).
- UI bundle `Bundle-Activator` (defect from GUI runtime log).
- Lifecycle test now uses ephemeral ports (`teip.port=0` + `DStudioServer.getBoundPort()`),
  removing the test/running-product collision on 48080.
- Aider model fallback chain: request model → harness config → EVO-Studio `harness.defaultModel`
  (wired in the lifecycle coordinator).
- OpenCode live smoke test: bounded retry + diagnostics (documented LLM nondeterminism
  mitigation, not a hidden skip).

## 2. EVO — PASS

| Check | Result |
|---|---|
| Build (controller + controller.tests + 18 dependency modules) | **BUILD SUCCESS** (Tycho 4.0.5) |
| `DStudioCodingHarnessAdapterTest` | 7/7 PASS |
| `ConnectedApplicationRegistryTest` (regression) | 10/10 PASS |
| Real workflow **EVO Developer Mode → TEIP → EVO-Studio → OpenCode → EVO** | **PASS** — `DStudioLiveE2eTest` against the live EVO-Studio headless product (25.2 s, real file created in target workspace) |

EVO Developer Mode orchestration untouched (the EVO-Studio adapter slots into the existing
`ConnectedApplicationRegistry`; `coding_harness_execute` selects it through the existing
flow).

## 3. TRYST — PASS

| Check | Result |
|---|---|
| Build | **BUILD SUCCESS** (offline) |
| `DStudioCodingHarnessAdapterTest` (incl. persistence) | 10/10 PASS |
| `ConnectedApplicationRegistryTest`, `OpenCodeCodingHarnessAdapterTest` (regressions) | 6/6 and 7/7 PASS |
| Full suite | **337/337 PASS, twice** (stabilization per `PHASE-4-TRYST-TEST-STABILIZATION-REPORT.md`) |
| Real workflow **TRYST Task → Sandbox → TEIP → EVO-Studio → OpenCode → TRYST Review** | **PASS** — `DStudioLiveE2eTest` against live EVO-Studio (25.2 s, `modifiedFiles=[tryst-dstudio-marker.txt]` returned through the TEIP result) |

TRYST ownership preserved: repository cloning, sandbox isolation, task workflow, review and
Git finalization all remain TRYST-side (adapter change = one registration line + config
persistence; see `PHASE-4-TRYST-PERSISTENCE-REPORT.md`).

## 4. Harness Switching — PASS

| Scenario | Evidence |
|---|---|
| EVO-Studio desktop can select OpenCode or Aider | `HarnessView` + registry selection (tests 7); the Run Task dialog lists all registered harnesses |
| Same project/workspace, no ownership change | Registry switch is config-only; project identity untouched (registry/project tests) |
| Headless clients request an available harness via TEIP extensions | **Live:** `POST /teip/v1/executions` with `parameters.harness=aider` on the same workspace previously used with opencode → COMPLETED, `modifiedFiles=[.gitignore, switch-marker.txt]`, marker content `dstudio-switch-aider-ok` verified on disk (2026-11-16 21:04) |
| Unknown/unavailable harness → structured error | **Live:** `parameters.harness=nonexistent` → 422 `CAPABILITY_UNSUPPORTED` "Unknown harness: nonexistent" (canonical envelope) |

## 5. Conclusion

No regressions introduced. All three repositories build; all targeted suites green; both
real family workflows re-verified against the live EVO-Studio runtime; harness switching proven
end-to-end over the wire in both directions of the TEIP contract.
