# PHASE-4-OPENCODE-REPORT.md

**EVO-Studio Phase 4 — WP-4.05 deliverable**
**Date:** 2026-11-16

## 1. Verified Environment

| Item | Value | Evidence |
|---|---|---|
| Binary | `/home/petr/.opencode/bin/opencode` | `which opencode` |
| Version | **1.19.5** | `opencode --version` |
| Config | `~/.config/opencode/opencode.jsonc`: model `ollama/qwen3-coder-30b-32k:latest`, agent `build`, providers ollama (127.0.0.1:11434/v1) + deepseek | file content |
| Ollama | 0.40.16, models qwen3-coder-30b-32k:latest, qwen3-coder-next:32k, deepseek-coder-v2:16b | `ollama list` |

## 2. Live CLI Probes (this phase, recorded)

| Probe | Result |
|---|---|
| `opencode run --help` | Flags verified: `--print-logs`, `--format json`, `--dir`, `--model provider/model`, `--agent`, `--auto`, `--variant`, `--thinking` |
| Bare model id (`qwen3-coder-30b-32k:latest`) | **FAILS**: `Error: Model not found: qwen3-coder-30b-32k:latest/.` (confirmed live 2026-11-16 19:21) — `provider/model` prefix mandatory |
| Real execution in git repo | **PASS**: created `smoke-marker.txt` containing `dstudio-opencode-smoke-ok` inside target dir, exit 0, ~61 s (probe repo `/tmp/opencode/probe-opencode`) |
| `--format json` | **PASS**: NDJSON on stdout — `step_start` / `text` (with content) / `step_finish` (with `tokens{input,output,reasoning}`) events; logs remain on stderr |
| Cancellation | **PASS**: SIGTERM terminates the process within 3 s (kill probe 19:22) |
| Invalid model | fails with `Model not found` + exit 1 (verified in TRYST Phase 3; re-verified by adapter live test) |

## 3. Adapter Implementation

`eu.kalafatic.dstudio.harness.opencode.OpenCodeHarnessAdapter` (harness bundle, id `opencode`,
type `OPENCODE`, capability `CODE_IMPLEMENTATION`).

- **Invocation:** `opencode run <prompt+constraints> --agent <agent> --model <provider/model>
  --print-logs --format json --dir <workspace> [--auto]`
- **Executable discovery / version probe:** `opencode --version`, 10 s cap
  (`HarnessConnectionTestResult` with version message).
- **Model normalization:** bare id → `ollama/<id>` (live-verified requirement).
- **Hermetic environment:** `CI=1`, `NO_COLOR=1`, `OPENCODE_DISABLE_TELEMETRY=1`,
  `OPENCODE`/`OPENCODE_PID` removed, per-run `XDG_DATA/STATE/CACHE_HOME` under
  `~/.dstudio/harness-runs/<taskId>/`, `XDG_CONFIG_HOME` inherited, stdin `/dev/null`.
- **Structured streaming (new vs EVO/TRYST):** `--format json` NDJSON parsed per line —
  `text` → `EXECUTION_LOG`, `tool` → `TOOL_INVOKED`, `step_start/finish` →
  `PROGRESS_UPDATED` (+token metrics), non-JSON lines fall back to plain log forwarding.
- **Cancellation/timeout:** 500 ms poll loop in `ProcessExecutionSupport`,
  `destroy()` → `destroyForcibly()` (2 s); config `timeoutSeconds` (900) ∩ request timeout.
- **Changed files:** pre-run JGit snapshot → post-run diff collection; containment check
  (change outside `--dir` = hard error); unified diff text per file.
- **Config (persisted via registry/SQLite — fixes TRYST G4 defect):** `binary`, `agent`,
  `model`, `autoApprove`, `timeoutSeconds`, `streamFormat` (`json` default, `default`
  fallback).

## 4. Test Evidence (2026-11-16 run)

| Suite | Result |
|---|---|
| `OpenCodeHarnessAdapterTest` (7) | 7/7 PASS — identity, invalid binary, config round-trip, model normalization, containment, missing workspace, installed-binary probe |
| `OpenCodeLiveExecutionTest` (2, real CLI, no mocks) | 2/2 PASS — (1) full stack EVO-Studio ExecutionService → OpenCode 1.19.5 → `live-marker.txt` with `dstudio-live-ok` created inside workspace; COMPLETED status; EXECUTION_LOG + EXECUTION_COMPLETED events streamed; review auto-created (34.2 s). (2) model `ollama/definitely-not-an-installed-model-xyz` → FAILED with honest diagnostic |
| Full reactor | 57/57 tests, BUILD SUCCESS (Tycho 4.0.8, offline-capable cache now populated) |

## 5. Known Limitations

- `--format json` schema is version-specific (verified on 1.19.5); non-parseable lines are
  passed through, so future format changes degrade to plain logs, never to data loss.
- DeepSeek provider connectivity relies on the user's OpenCode provider config (not probed
  live in this phase — no API-key validation performed).
- Metrics (tokens) are streamed as events but not yet aggregated into the result model.
