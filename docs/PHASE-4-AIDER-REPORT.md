# PHASE-4-AIDER-REPORT.md

**EVO-Studio Phase 4 — WP-4.06 deliverable**
**Date:** 2026-11-16

## 1. Verified Environment

| Item | Value | Evidence |
|---|---|---|
| Binary | `/home/petr/.local/bin/aider` | `which aider` |
| Version at start | 0.86.1 | `aider --version` |
| Version after probe | **0.86.2** | aider auto-upgraded itself via `uv pip` during the first probe (see §2) |

## 2. Live CLI Probes (this phase — first family-wide Aider verification)

| Probe | Result |
|---|---|
| First run (no update flags) | **aider auto-upgraded 0.86.1 → 0.86.2** ("Newer aider version available... Installation complete. Re-run aider") and aborted the run — adapters MUST pass `--no-check-update` |
| `--no-check-update --model ollama/qwen3-coder-30b-32k:latest --message <p> --yes-always --no-auto-commits --analytics-disable --no-show-release-notes --map-refresh files` | **PASS**: created `aider-marker.txt` with exact content `dstudio-aider-smoke-ok`, exit 0, "Applied edit to aider-marker.txt" on stdout |
| Side effects in repo | **Verified**: aider creates `.aider.chat.history.md`, adds `.aider*` entries via a new `.gitignore`, and **stages** edited files in the git index (no commit — `--no-auto-commits` respected) |
| Output encoding | ANSI escape sequences present (stripped by adapter) |
| Model naming | `ollama/<id>` works (same provider-prefix convention as OpenCode) |

## 3. Adapter Implementation

`eu.kalafatic.dstudio.harness.aider.AiderHarnessAdapter` (harness bundle, id `aider`, type
`AIDER`, capability `CODE_IMPLEMENTATION`).

- **Invocation:** `aider --no-check-update --model <provider/model> --message
  <prompt+constraints> --yes-always --no-auto-commits --analytics-disable
  --no-show-release-notes --map-refresh files [--openai-api-base <base>]`
- **Hard guarantees:** `--no-auto-commits` is unconditional (aider may NEVER commit);
  `--no-check-update` unconditional (prevents the observed auto-upgrade); working dir =
  target dir; stdin `/dev/null`.
- **Env:** `OLLAMA_API_BASE` (config `ollamaBaseUrl`, default `http://127.0.0.1:11434`),
  `CI=1`, `NO_COLOR=1`. OpenAI-compatible endpoints via config `openaiApiBase` +
  `--openai-api-base`; provider API keys come from the process environment only.
- **Streaming:** stdout+stderr forwarded to the execution sink with ANSI sequences stripped.
- **Changed files:** pre-run JGit snapshot → post-run collection, filtering aider's own
  artifacts (`.aider*`), containment check (escape = hard error), per-file unified diffs.
- **Config (persisted):** `binary`, `model`, `ollamaBaseUrl`, `openaiApiBase`,
  `timeoutSeconds`, `mapRefresh`. A blank model fails honestly with "Aider requires a
  configured model".
- **Documented side effects (review semantics):** aider stages edited files and writes
  `.aider*`/`.gitignore` — EVO-Studio review treats only non-`.aider*` changes as reviewable
  files; `.gitignore` additions appear in review as normal source changes.

## 4. Test Evidence (2026-11-16 run)

| Suite | Result |
|---|---|
| `AiderHarnessAdapterTest` (8) | 8/8 PASS — identity, invalid binary, config round-trip, missing workspace, missing model honest failure, model normalization, artifact filter, ANSI stripping |
| `AiderLiveExecutionTest` (1, real CLI, no mocks) | 1/1 PASS — full stack EVO-Studio ExecutionService → Aider 0.86.2 → `aider-live-marker.txt` with `dstudio-aider-live-ok` created inside workspace; COMPLETED; events streamed; `.aider*` artifacts excluded (16.0 s) |
| Full reactor | 57/57 tests, BUILD SUCCESS |

## 5. Known Limitations

- DeepSeek connectivity was not probed live (requires an API key; configuration surface
  implemented via `--openai-api-base` + provider env keys — documented, not tested).
- aider's git-index staging side effect is documented but not neutralized (EVO-Studio never
  runs git commands itself; review uses working-tree diffs).
- aider requires a git repository for full diff behavior; in non-git directories EVO-Studio
  still runs it (with `--map-refresh files`) but collects no diffs.
