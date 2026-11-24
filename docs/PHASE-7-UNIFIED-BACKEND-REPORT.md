# PHASE-7-UNIFIED-BACKEND-REPORT.md

**EVO-Studio Phase 7 — S4 Unified Backend deliverable**
**Date:** 2026-11-19
**Status:** Implemented and verified. This report describes actual implemented behavior;
all evidence below was produced by running the code (build, tests, live headless server).

---

## 1. Scope

Stage S4 completed the backend so that every client (RCP, Web IDE, TEIP consumers)
operates on one authoritative, headless-capable core with:

1. Live event streaming (SSE) with reconnection replay.
2. Shared API authentication across every HTTP endpoint.
3. Encryption at rest for all secrets (API keys, tokens).
4. Persisted direct-inference sessions (multi-turn, streaming, cancellation).

No behavior was removed; TEIP 1.1 wire behavior is unchanged (verified by the existing
TEIP tests). No new frameworks were introduced (JDK `HttpServer` SSE, SQLite, Jackson as
before).

## 2. Implemented

### 2.1 Secrets: encryption at rest

- `SecretCodec` (infrastructure): AES/GCM/NoPadding, 12-byte IV per value, 128-bit tag.
  Master key = 32 random bytes in `<config-dir>/secrets.key`, owner-only permissions
  (600 on POSIX), generated on first use. Wire format `enc:<b64 iv>:<b64 ct>`.
- Sensitive-key detection: keys matching `token|secret|password|credential|api[_-]?key`
  are encrypted transparently on save and decrypted on load.
- `SqliteHarnessStore` and `SqliteInferenceEngineStore` now require a `SecretCodec` and
  apply it to `saveConfiguration`/`findConfiguration`. Verified by test: raw
  `config_json` in SQLite never contains the plaintext secret.
- Existing masking behavior preserved: EVO-Code/ENR adapters still return `******` from
  `getConnectionConfiguration`; no API route returns credentials.

### 2.2 Global event stream (SSE)

- `EventStream`/`DefaultEventStream` (core): bounded ring buffer (10 000 entries)
  fed by a bus subscription; monotonic sequences; `after(seq)` replay and blocking
  `awaitNext(seq, timeout)`.
- `GET /api/events` (teip): chunked `text/event-stream`; frames carry
  `id:` (sequence), `event:` (payload type), `data:` (JSON). Reconnection contract:
  `?afterSequence=N` or `Last-Event-ID` header replays the buffered window first, then
  continues live. Heartbeat comment every 15 s. No cursor = live-only.
- The stream is a convenience channel, not a durable record: clients must reconcile
  against authoritative REST state after reconnecting (documented in the handler).
- Clean shutdown: `DStudioServer.stop()` ends every open SSE loop before stopping the
  HTTP server (verified: SIGTERM → process exit, port released, no stray threads).

### 2.3 Shared API authentication

- `ApiAuth` (teip): every `/api/*` context (`/api/status`, `/api/diagnostics`,
  `/api/inference`, `/api/events`) now enforces the same bearer token
  (`dstudio.teip.auth.token` / `DSTUDIO_TEIP_AUTH_TOKEN`, constant-time compare) with
  the same explicit dev-mode loopback opt-out (`dstudio.teip.devMode=true`) as TEIP.
  Denials return `401 {"code":"AUTHENTICATION_FAILED",...}`.
- Browser note: EventSource cannot set headers; cookie-based session auth is deferred
  to the Web IDE stage. Programmatic clients use the bearer header.

### 2.4 Direct-inference sessions

- Core: `InferenceSession` / `InferenceSessionMessage` models,
  `InferenceSessionStore` + `SqliteInferenceSessionStore` (schema V3:
  `inference_sessions`, `inference_messages`), `InferenceService` +
  `DefaultInferenceService`, `InferenceStreamEvent`, `InferenceException` (codes:
  NOT_FOUND, CONFLICT, VALIDATION_FAILED, INFERENCE_FAILED).
- Semantics: engine+model pinned at creation (never silently switched); system prompt
  + full history sent to the engine each turn; user and assistant messages persisted;
  failure marks the session FAILED with the engine's error; cancellation marks
  CANCELLED (idempotent, partial content preserved); terminal sessions reject new
  messages with 409.
- HTTP (extended `InferenceHttpHandler`):
  - `POST /api/inference/sessions` → 201
  - `GET /api/inference/sessions`, `GET /api/inference/sessions/{id}` (with messages)
  - `POST /api/inference/sessions/{id}/messages` → blocking answer
  - `POST /api/inference/sessions/{id}/messages/stream` → SSE `chunk`/`done`/`error`
  - `POST /api/inference/sessions/{id}/cancel` → idempotent
- Existing `/api/inference` routes (engines, models, load/unload, runtime, `/chat`)
  are unchanged.

### 2.5 Adapter fixes

- ENR and Ollama `streamChat` now honor `cancellationSupplier` by closing the HTTP
  body stream (aborts the exchange); a cancelled stream surfaces as CANCELLED, never
  FAILED. Ollama streaming errors now include the provider's error body.

## 3. Verification

| Evidence | Result |
|---|---|
| `mvn -o clean verify` (offline, Tycho 4.0.8) | **135/135 tests pass** |
| New unit/wire tests | SecretCodecTest 7, EventStreamTest 4, SseEventsApiTest 3, InferenceSessionServiceTest 7, InferenceSessionApiTest 7 |
| Live Ollama streaming | real `qwen3-coder-30b-32k:latest`, 632 ms, done chunk, session persisted (`OllamaLiveInferenceTest`) |
| Live Ollama cancellation | real stream aborted in 78 ms, session CANCELLED (`OllamaLiveInferenceTest`) |
| Headless product runtime | materialized product started on 48991; `/api/status` UP; SSE delivered `InferenceStreamEvent` (CREATED/CHUNK/DONE); session streamed "OK"/"NO" over HTTP SSE with 4-message persisted history; reconnection `afterSequence=1` replayed events 2-3; live HTTP cancel → CANCELLED; SIGTERM → clean exit, port released |
| TEIP regression | TeipApiEndpointTest 14, TeipOpenCodeEndToEndTest (live OpenCode E2E) pass unchanged |
| EVO MCP live test | now skips when port 28280 hosts a non-cognitive MCP server (dependency probe via `tools/list`) |

## 4. Known limitations (honest disclosure)

- Ollama `runtimeStatus()` still reports local JVM stats (not Ollama runtime data) —
  tracked for the S3 multi-runtime stage.
- SSE authentication for browsers requires cookie support (deferred to Web IDE stage).
- A client disconnect during an inference stream cancels the session (partial content
  is persisted). A cancel racing end-of-stream wins as CANCELLED.
- The event stream buffer is in-memory; events are not replayed across server restarts
  (REST state remains authoritative).
- ENR/Ollama live verification is against Ollama only; ENR has wire-level tests but no
  live runtime was available on this host.

## 5. Architectural notes

- Dependency direction preserved: core has no SWT/workbench/TEIP references; the TEIP
  bundle consumes core SPI only; the lifecycle coordinator remains the single wiring
  point for the shared core.
- One event system: the existing `EventBus` feeds both the (new) SSE channel and the
  existing RCP console subscription. No second event bus was introduced.
- One persistence: sessions live in the existing SQLite database (schema V3,
  forward-only migration), managed by the existing `SchemaMigrationService`.

## 6. Port family convention and conflict handling (S4 follow-up)

### 6.1 Ports

Family convention (EVO 28xxx → TRYST 38xxx → EVO-Studio 48xxx → EVO-Code 58xxx; additional
services step by +100 inside each block):

| Product | Primary port | Diagnostics / additional |
|---|---|---|
| EVO | 28080 | 28180, 28280 (MCP), 28380, 28480, 28580 |
| TRYST | 38080 | 38280, 38480 |
| EVO-Studio | **48080** (TEIP/API/events) | **48180** (diagnostics, config-only) |
| EVO-Code/DCoder | 58080 | 58180+ |

- `DStudioConfiguration.DEFAULT_TEIP_PORT` reverted to **48080** (a stray uncommitted
  48081 change had broken the convention); `DEFAULT_DIAGNOSTICS_PORT` = 48180.
- `DCodeHarnessAdapter.DEFAULT_ENDPOINT` corrected to **http://localhost:58080** — it
  previously pointed at EVO-Studio's own TEIP port.

### 6.2 Port conflicts are interactive and actionable

- `DStudioServer.start()` maps `BindException` to a typed `PortConflictException`
  (address, port, remediation text). Never silently switches ports or configuration.
- **Desktop**: `DStudioApplication` catches it and shows an interactive
  `PortConflictDialog` — what happened, what the port is used for, what to fix
  (stop the conflicting process / choose another port / exit). The chosen free port
  (pre-filled via `PortProbe`) is retried and **persisted** to
  `~/.dstudio/dstudio.properties` through `restartServerOnPort()`, then the IDE
  continues normally. Any other startup failure ends in an actionable
  `ErrorDialog` with the failure details, never a bare "An error has occurred".
- **Headless**: prints actionable STARTUP FAILED guidance (port, occupying-process
  hints, exact config keys to change), preserves configuration, disposes services and
  exits promptly (non-zero).
- Stage-machine fix: `terminateAfterStartupFailure()` shuts down without re-running the
  failed `APPLICATION_READY` stage (a plain `advance(TERMINATED)` would retry the bind
  and rethrow).
- Launcher exit quirk (verified live): after a failed application start the embedded
  Eclipse launcher does not exit on `System.exit`/`Runtime.halt`, and
  `ProcessHandle.destroy()` is forbidden on the current process; `DStudioStartupExit`
  therefore terminates via SIGKILL to its own PID after disposal (prompt, deterministic).
- Verified live: occupied 48080 → guidance printed, config intact, exit in ~3.3 s;
  free 48080 → server binds the family port; conflict/restart/persistence covered by
  `PortConflictHandlingTest` (4 tests).

## 7. Stage 2 — Multi-harness completion (S2)

- **Claude Code harness** (`ClaudeCodeHarnessAdapter`, harness bundle): headless CLI
  contract (`claude --print --output-format stream-json --verbose [-p prompt]`),
  Anthropic-native provider type, cancellation, containment + changed-file collection.
  **Execution recipe NOT live-verified on this host (no `claude` binary installed) — the
  live test skips silently; run `ClaudeCodeHarnessAdapterTest.liveExecutionSkipsWithoutCli`
  on a host with Claude Code to complete verification.**
- **Harness/provider compatibility matrix foundation**: `HarnessCapabilityDescriptor`
  gained `supportedProviderTypes` — OpenCode: OLLAMA/OPENAI_COMPATIBLE; Aider:
  OLLAMA/OPENAI_COMPATIBLE/ANTHROPIC; EVO-Code: DCODE_NATIVE; Claude: ANTHROPIC. Exposed
  per harness in `/api/diagnostics` so clients can hide incompatible combinations.
- Explicit selection wins: OpenCode/Aider now honor the per-task `request.model()`
  (and `request.agent()` for OpenCode) instead of silently overriding with config
  defaults; configured values remain fallback defaults only.

## 8. Stage 3 — Multi-runtime completion (S3)

- **OpenAI-compatible engine** (`OpenAiCompatibleInferenceEngineAdapter`): `/models`,
  `/chat/completions` blocking + streaming, cancellation (stream abort), single done
  emission, masked apiKey, encrypted-at-rest apiKey. **Live-verified against real
  Ollama `/v1`** (21 models listed, live chat and live streaming pass;
  `OpenAiCompatibleLiveTest`).
- **Anthropic engine** (`AnthropicInferenceEngineAdapter`): `/v1/models`,
  `/v1/messages` blocking + streaming, x-api-key/anthropic-version headers, system
  prompt mapping. Wire-tested against in-process stub; **live requires an API key —
  not executed on this host** (test skips without a key).
- **Multi-instance runtimes**: ENR/Ollama/OpenAI/Anthropic adapters accept
  (id, name) instance constructors; new `InferenceEngineFactory` SPI (core) implemented
  in the application bundle (dependency direction preserved); HTTP administration:
  `POST /api/inference/engines` (create instance, 409 on duplicate id, 422 on unknown
  type) and `DELETE /api/inference/engines/{id}` (permanent removal incl. persisted
  descriptor). Registry gained `removeEngine`. Two Ollama endpoints (local + GPU server)
  verified live via the API.
- No silent fallback remains: engines are pinned per session; failures mark sessions
  FAILED with the engine's error.

## 9. Verification totals (this stage)

- `mvn -o clean verify`: **154/154 tests pass** (was 139) — new: ClaudeCodeHarnessAdapterTest 4,
  OpenAiAnthropicAdapterWireTest 6, OpenAiCompatibleLiveTest 3 (live), EngineInstanceApiTest 2.
- Live: OpenAI-compatible blocking + streaming against real Ollama /v1; full multi-turn
  session through the `openai` engine ("OPENAI_OK"); instance create/duplicate/remove
  via HTTP on the headless product; 4 harnesses + 4 engines discoverable in diagnostics.
- Remaining platform stages: S5 RCP IDE professionalization, S6 Web IDE, S7 cross-client
  verification (not started).

## 10. Stage 5 — RCP IDE professionalization (S5)

- **Full standard menu bar** (`ApplicationActionBarAdvisor`): File (Save/Save As/Save
  All/Revert/Import/Export/Close/Close All/Quit), Edit (Undo/Redo/Cut/Copy/Paste/
  Delete/Select All), Navigate (Back/Forward history, Forward), Search (Find), Window
  (New Window/Reset Perspective/Preferences), Help (About) — all real workbench
  actions. **Project** and **Run** menus are contributed by the UI bundle via
  `org.eclipse.ui.menus` (layering preserved) with the working openProject/
  runHarness/cancelExecution/refreshProjects commands.
- **RunHarnessDialog**: hardcoded model list removed — models are now discovered from
  the registered inference engines and **filtered by the harness's supported provider
  types** (compatibility matrix, §3.5: incompatible combinations are never offered);
  free-text model entry remains for explicit provider/model values.
- GUI runtime verification requires a graphical host (this host has no X server):
  compile-verified + unit-verified; runtime acceptance remains to be executed on a
  desktop host.

## 11. Stage 6 — Interactive Web IDE (S6)

- **One backend serves the frontend**: embedded `web/` assets (index.html, app.js,
  style.css — vanilla JS, no external frameworks/CDN) served by `WebAssetsHandler` at
  `/` and `/web` from the existing `DStudioServer`; no second server.
- **Workspace/file API** (`/api/workspace`): project open/close/list;
  `GET /api/workspace/files` (directory listing / file read), `PUT` (save, creates new
  files); strict path containment (traversal → 400); shared API auth; 10 MB web limit.
- **Web IDE features (real backend actions only)**: project open/browse, file tree,
  multi-tab editor with Save, AI chat (engine/model selectors, sessions, SSE-streamed
  answers), coding tasks via the canonical TEIP surface with event polling, live
  `/api/events` stream, diagnostics.
- Verified: `WebIdeWorkspaceApiTest` (assets served, open/browse/read/save/containment/
  auth); live headless product: index.html served, project opened, new file saved to
  disk, and a real OpenCode coding task driven through the TEIP surface created
  `web-marker.txt` with the requested content. Browser UI interaction itself needs a
  graphical browser (not verified on this host); the API layer underneath is
  fully exercised.
- Known limitation (documented): non-git projects cannot produce git diffs, so TEIP
  result `modifiedFiles` is empty for non-git workspaces (file effects are still real).

## 12. Stage 7 — Cross-client and family verification (S7)

- **`CrossClientStateTest`**: one backend, two clients — a task submitted through the
  TEIP/Web surface is immediately visible in the RCP core services and diagnostics;
  a runtime instance added over HTTP is immediately present in the RCP registry;
  execution events are shared through the same store/event stream. No duplicate
  tasks, sessions or registries exist (the architecture has exactly one of each).
- **TEIP compatibility**: full canonical endpoint tests remain green
  (`TeipApiEndpointTest` 14, live `TeipOpenCodeEndToEndTest`) — EVO/TRYST wire
  contracts unchanged.
- Family note: EVO/TRYST live family tests (`DStudioLiveE2eTest`) run in their own
  repositories against a running EVO-Studio at 48080; the EVO-Studio side they exercise is
  covered here by the same TEIP surface tests.

## 13. Final verification totals (this report)

- `mvn -o clean verify`: **158/158 tests pass**.
- Live: headless product — Web IDE assets + workspace API + real OpenCode task through
  the TEIP surface; OpenAI-compatible adapter against real Ollama `/v1`; direct
  inference sessions (streaming, multi-turn, cancellation); SSE + reconnection;
  port-conflict guidance and clean termination; multi-instance engine administration.

## 14. Addendum 2026-11-22 — Web IDE coherence round

- **Backend sessions in the web UI (S6 follow-up)**: the Inference page now lists
  `/api/inference/sessions` from the backend instead of localStorage-only
  conversations. Selecting a server session lazy-loads its messages; deletion,
  rename and multi-turn continuation hit the real session API. localStorage keeps
  only unsent drafts and UI preferences (never a second session manager). A
  cancelled server session is removed and the conversation returns to draft state
  (server sessions reject messages after cancel, 409). Model/engine switches on a
  server session delete the pinned session and create a fresh one.
- **Shared UI helpers**: `esc`, `toast`, `fillSelect`, `formatBytes`,
  `applyTheme` and a new XSS-safe `renderMarkdown` (code blocks, inline code,
  bold/italic/links, lists) live once in `common.js`; `app.js` and `settings.js`
  no longer duplicate them. Chat output is markdown-rendered, including during
  streaming.
- **Theme unification**: single key `dstudio.theme.v1` across Inference, Settings
  and Help (settings.js previously used `inference.theme.v1`); Settings and Help
  now have working theme toggles; help.html uses the shared UPM tokens instead of
  its own dark palette.
- **Keyboard shortcuts (web)**: Enter send / Shift+Enter newline, Ctrl/Cmd+K
  focus prompt, Ctrl/Cmd+N new conversation, Esc cancel; documented in
  help.html#shortcuts.
- **Ollama adapter fix**: `strip(modelId)` truncated bare tagged model names
  (`evo_model:latest` → `latest`); it now removes only the `ollama:` engine
  prefix. Live-verified with real streamed inference via the web API.
- **Port family test**: `DCodeFamilyIntegrationTest` default endpoint corrected
  to EVO-Code's 58xxx convention (58080); EVO-Code itself was migrated off 48080 so
  EVO-Studio and EVO-Code no longer collide (simultaneous operation verified live).

## 15. Addendum 2026-11-22 (b) — Harness defaults, web tasks and CLI inference

- **Default harness on fresh install**: `DStudioLifecycleCoordinator` registers
  EVO-Code last and selects it as the default only when no persisted selection
  exists; `/api/diagnostics` now exposes `defaultHarness` plus a per-harness
  `default` flag so every client (web/RCP) can show the selection honestly.
  Unavailable/disabled harnesses fail the execution with an explicit diagnostic —
  there is no silent replacement.
- **Web coding tasks**: new `web/tasks.js` + "Tasks" nav view. Tasks execute
  through the real TEIP 1.1 surface (`POST /teip/v1/executions`, status/events/
  result polling, idempotent cancel) and render real changed files and diffs.
  Live-verified: OpenCode task created `task-marker.txt` (TASK_OK) on disk,
  COMPLETED with `modifiedFiles` reported.
- **CLI inference**: new `chat` command (streaming through the shared
  `InferenceService`, persisted sessions; `chat list` / `chat show <id>` expose
  the sessions of every client). Live-verified: a CLI-created session is visible
  through the web API after restart (same SQLite store) — cross-mode state
  consistency.
- **EVO-Code port lifecycle**: EVO-Code now persists allocated/fallback ports in
  `dcode-config.json` and reuses them on restart; a configured-but-occupied port
  falls back deterministically inside the 58xxx block (warning logged, new port
  persisted). EVO-Code's CLI resolves the authoritative port (bound property →
  `-Ddcode.port` override → persisted config → family default) instead of
  hardcoding 58080. Live-verified: conflict → 58081, restart → 58081 reused.

## 16. Addendum 2026-11-22 (c) — Frontend round and RCP runtime fixes

**Web inference workspace (visible upgrade)**
- Markdown chat output with a dependency-free syntax highlighter (keywords/
  strings/comments/numbers/functions per language, ~12 languages) and per-code-block
  and per-message **copy** actions (`common.js renderMarkdown/highlightCode`).
- Session list: **search filter**, inline **rename** (WebKit-safe modal → real
  `/api/inference/sessions/{id}/rename`), delete, active-session highlight, server
  badge, timestamps.
- Bottom **Events** pane now consumes the live `/api/events` SSE stream (cursor
  replay + reconnect); Console/Events/Problems stay filterable/exportable.
- **Project file browser** in the Context panel (real `/api/workspace/files`:
  directory listing, file preview, "Use in prompt" inserts path+content so the model
  genuinely receives it). Tasks pane (TEIP executions) shows status, events,
  changed files and diffs.
- Settings gains an **Appearance** card (theme select, shared `dstudio.theme.v1`).

**RCP runtime fixes (from the user's launch log)**
- `Theme 'org.eclipse.e4.ui.css.theme.e4_default' not found` — resolved by adding
  `org.eclipse.ui.themes` to the product (provides e4_default/e4_dark); the product
  now requests the `e4_dark` CSS theme (`cssTheme` property) for a professional dark
  workbench. Verified: message absent from a fresh RCP launch log.
- `No SLF4J providers were found` — resolved with an OSGi **fragment**
  (`plugins/org.slf4j.nop`, Fragment-Host `slf4j.api`) carrying the NOP provider —
  the only mechanism SLF4J 2.x discovery honors in Equinox. Verified: 0 SLF4J lines
  in headless and RCP runs.
- `Unable to create menu item … installationDetails` — resolved by auto-starting
  `org.eclipse.equinox.p2.ui.sdk` (its command contributions register at bundle
  activation). Verified absent from the fresh RCP log.
- Felix SCR circular reference (m2e MavenProjectManager/MavenModelManager/
  WorkspaceStateWriter) — **development environment only**: the product features
  contain no m2e bundles; the cycle comes from the m2e bundles of the host Eclipse
  IDE used for PDE launches. No product change required.
- Workspace recovery message — benign Eclipse workbench-state refresh after
  non-clean exits; EVO-Studio itself shuts down cleanly (verified: server stop, ports
  released, 0 exceptions).

## 17. Addendum 2026-11-22 (d) — Unified frontend delivery architecture

**Root cause of the "UI unchanged" report**: the developer launches EVO-Studio/EVO-Code as
**PDE product launches** from the Eclipse IDE. In that mode the runtime classpath of
each workspace bundle is its output folder (`dev.properties` → `target/classes`),
and the `web/` resources were only packaged by Tycho into the product jars — they
were never copied to the output folder, so the PDE launches could not serve the
edited frontend (and `mvn clean` had removed the IDE's old copies).

**Fix (single authoritative source, standard delivery mechanism)**:
- `web/` is now a **build-input source folder** in `build.properties`
  (`source.web/ = web/`) and `.classpath` for both frontend bundles
  (`eu.kalafatic.dstudio.teip`, `eu.kalafatic.dcode.server`). The standard JDT/PDE
  builder copies it into the runtime classpath (workspace launches) and Tycho
  packages it into the bundle jar (products) — one source, no mode-specific code,
  no special-case serving paths.
- No runtime-specific copies or dev-only serving logic exist; the embedded servers
  keep serving from the bundle classpath only.

**Build-time verification (permanent invariant)**:
- New `FrontendDeliveryTest` (both products) fails the build when the packaged
  bundle content differs from the source tree (staleness) or when the frontend
  build constant drifts from the backend version field.
- Frontend build identifier `web-20261122-r4`: visible badge in the UI, logged at
  boot, and exposed via `/api/status` (`frontendVersion`) and EVO-Code
  `/api/v1/health` (`frontendVersion`).

**Developer workflow**: one edit in `web/` → IDE auto-build copies it to the
workspace launch classpath; `mvn verify` packages it into the products and the
delivery test verifies consistency. A project refresh/rebuild in the IDE picks up
the new source-folder declaration automatically.

## 18. Addendum 2026-11-22 (e) — Eclipse Run/Debug finalization

- **Serving mechanism unified on the OSGi bundle API**: `WebAssetsHandler` now
  resolves `web/…` through `Bundle.getEntry` (the same mechanism EVO-Code's
  `StaticContent.BundleEntry` already used). Packaged products read the jar
  entries; PDE workspace launches read the same logical paths from the
  directory-installed bundle — one source, one mechanism, zero dependency on
  output folders or builder state. The earlier `source.web/ = web/` experiment was
  reverted (JDT would have flattened `web/index.html` to `index.html` in the
  output folder — the exact hazard to avoid).
- **Diagnostics**: `/api/status` now reports `frontendVersion`,
  `resourceSource: "osgi-bundle"` and `executionMode: "pde"|"product"`
  (diagnostic only; never selects a serving path). EVO-Code mirrors this in
  `/api/v1/health`.
- **Shared launch configuration** committed at
  `features/eu.kalafatic.dstudio.feature/DStudio_RCP_Application.launch`
  (product `eu.kalafatic.dstudio.application.product`, workspace bundles selected,
  JavaSE-21, PDE RuntimeWorkbench type) — used identically for Run As and
  Debug As after a workspace refresh.
- **FrontendDeliveryTest extended**: `directoryBundleExposesTheSameLogicalWebPaths`
  installs a directory bundle (the PDE layout) and proves the same `web/…` entry
  API and paths work with unmodified content.
- **Live JDWP (debugger) verification** against the real runtime (the same
  mechanism Eclipse Debug As uses): breakpoints hit in
  `DStudioServer.start` (server init, full lifecycle stack), `WebAssetsHandler.handle`
  (frontend delivery, thread `dstudio-http`) and `DefaultInferenceService.run`
  (inference; stack `run ← sendMessage`, typed locals `sessionId/content/chunkConsumer`);
  thrown `InferenceException` observed at throw time with its catch location;
  threads/variables/stacks inspected; the server served 200 after resume; shutdown
  released both ports.

## 19. Addendum 2026-11-22 (f) — Local no-auth policy and navigation fix

**Cause of the 401s in Eclipse-launched instances**: the teip auth gate
(`ApiAuth.authenticate` + `TeipHttpHandler.authenticate`) required either a
configured bearer token or explicit `teip.devMode=true` even for loopback
requests. PDE-launched desktop instances had neither, so the embedded SWT
Browser's API calls received 401 and the UI showed "offline".

**Policy now (current development phase, no authentication)**:
- **Loopback clients are always local-trusted** — the embedded SWT Browser, the
  desktop application, CLI tools and local integrations require no token, no
  session, no cookie and no dev-mode flag. Works identically in PDE Run, PDE
  Debug and packaged RCP products without any manually supplied properties.
- Non-loopback clients keep the pre-existing optional bearer-token gate
  (`dstudio.teip.auth.token` / `DSTUDIO_TEIP_AUTH_TOKEN`); without a token remote
  access is rejected as before. No new security mechanism was introduced.
- EVO-Code already behaved this way (`DCodeApi.isAuthorized` is open when no token
  is configured) — verified, unchanged.
- Tests updated to the new contract: `LocalLoopbackAuthTest` (loopback API/SSE/
  workspace/TEIP reachable without token and regardless of a configured token);
  the former "loopback requires token" assertions in
  `InferenceSessionApiTest`, `TeipApiEndpointTest`, `SseEventsApiTest` and
  `WebIdeWorkspaceApiTest` were flipped to the new policy.

**Navigation fix**: the grouped nav uses a 2-column grid
(`.nav-group { display: grid; grid-template-columns: 1fr 1fr; }`) so the MANAGE
group labels no longer clip at 280 px panel width (both products).

**Runtime verification (real RCP product, no token, no devMode)**:
- EVO-Studio RCP: `[EVO-Studio] Server listening on 127.0.0.1:48086`; log
  "web IDE title: EVO-Studio — Inference" + "web IDE page loaded"; loopback
  `/api/status` 200 (`executionMode:"product"`, `frontendVersion`),
  `/api/inference/engines` 200, `/teip/v1/health` 200 **without any credential**;
  four live TCP connections from the RCP's WebKit network process (SPA API calls
  + the `/api/events` SSE stream) prove the embedded browser is connected.
- EVO-Code RCP: embedded server RUNNING on 58080, "Web Browser created (WebKit)",
  `/api/v1/health` 200 without credentials; the served `css/app.css` contains the
  grid nav fix.
- Screenshots captured: `/tmp/opencode/ui/dstudio-noauth.png`,
  `/tmp/opencode/ui/dcode-noauth.png`.
