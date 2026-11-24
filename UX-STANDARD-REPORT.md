# EVO-Studio — Unified RCP / Web UX Standard — Delivery Report

Date: 2026-11-21

## 1. Executive summary

EVO-Studio already implements the standard: Tools → Open Terminal / Open Web
Interface with live port resolution, a full Help menu (Contents/Getting
Started/Context Help/Keyboard Shortcuts/CLI Commands/Diagnostics/About), F1
context help via the served `help.html`, the Darwin Family Contextual Action
Contract v1.0 consumed by both the RCP views and the Web IDE (right-click menus
with keyboard support), and a table-driven headless CLI. This work closes the
remaining gaps: mandatory CLI `/start` and `/restart` (real server restart via the
lifecycle coordinator), a Help button in the Web IDE, and CLI help content updates.

## 2. Existing infrastructure reused

- `DStudioCliCommandTable` — single source of truth for dispatch and generated help
  (extended, not replaced).
- `DStudioLifecycleCoordinator.restartServerOnPort(port)` — the same restart path
  the desktop port-conflict flow uses; `/restart` and `/start` call it, so there is
  one server-lifecycle implementation.
- `WebInterfaceLauncher`/`HelpLauncher`/`TerminalLauncher` — unchanged.
- Contextual Action Registry (`/api/contextual`) and the Web IDE context menu —
  unchanged.

## 3. Components modified

| File | Change |
|---|---|
| `plugins/eu.kalafatic.dstudio.application/src/.../cli/DStudioCliCommandTable.java` | Added `start` and `restart` commands + `TOPIC_HELP` entries. |
| `plugins/eu.kalafatic.dstudio.application/src/.../cli/DStudioCli.java` | Added `start()` / `restart()` handlers: `start` verifies the running server and starts it when stopped; `restart` re-uses `coordinator.restartServerOnPort(boundPort())`. Both are null-coordinator safe. |
| `plugins/eu.kalafatic.dstudio.teip/web/index.html` | Help link (`#helpBtn` → `/help.html`, new tab). |
| `plugins/eu.kalafatic.dstudio.teip/web/style.css` | `#helpBtn` styling consistent with header buttons. |
| `plugins/eu.kalafatic.dstudio.teip/web/help.html` | CLI table: added `start` and `restart` rows. |
| `tests/eu.kalafatic.dstudio.tests/src/.../DStudioCliTest.java` | Added `mandatoryFamilyVocabularyIsRegistered` and `startAndRestartReportUnavailableWithoutCoordinator`; `start`/`restart` included in the keep-session list. |

## 4. New actions

- CLI `/start` — ensure the TEIP/API server is running (it starts automatically
  with the application; the command verifies or starts it).
- CLI `/restart` — stop and restart the server on its configured port via the
  coordinator (port resolved live, never hardcoded).
- Web Help button.

## 5. Context menu coverage (RCP)

Unified contextual-action framework already covers: Project (Open/Create
Session/Close/Terminal/Web), Harness + registry (Scan/Refresh/Add/Set
default/Configure/Test/Enable/Disable/Start/Stop/Restart/Capabilities/Logs/Remove),
Provider + registry (Scan/Refresh/Add/Set default/Configure/Test/Enable/Disable/
Discover models/View endpoint/Logs/Remove), Model (Set default/Load/Unload/
Refresh metadata/Test inference/Information), Agent + registry (Create/Import/Set
default/Edit/Clone/Configure/Export/Inspect/Delete), Execution (Cancel/Details/
History), plus Project Explorer file entries (Open/Copy Path/Open Terminal/Refresh).

Not exposed (no backend operation owns them): Folder Create/Rename/Delete, File
Rename/Delete, Session Rename/Duplicate/Delete (contributed disabled — EVO-Studio
does not own session duplication), Console Clear/Copy/Save-Output (standard
Eclipse `MessageConsole` toolbar only).

## 6. Help coverage

- Help menu (all six entries + About) → `help.html#<anchor>`; F1 opens the
  view-specific anchor via `HelpLauncher.anchorForViewId`.
- Web IDE: `help.html` now reachable via the Help button (previously URL-only).

## 7. CLI help coverage

- `help`/`help [topic]` generated from `DStudioCliCommandTable.COMMANDS` +
  `TOPIC_HELP`. Vocabulary now: `help, status, web, terminal, models, sessions,
  providers, agents, config, logs, start, restart, stop, exit`.
- Unknown commands/topics → nearest-match suggestions. `dstudio>` REPL and
  piped-stdin CLI both use the same dispatcher.

## 8. Tests executed

- `DStudioCliTest` — **14/14 passed** (includes the two new tests and the new
  commands inside the existing table-consistency and help-generation tests);
  executed by the full Tycho build in the first run.
- Full `clean verify`, run 1: all modules compiled SUCCESS (including the
  modified application/teip bundles) and the test suite ran
  (`DStudioCliTest` 14/14) until a transient tycho-surefire runtime error in the
  tests module ("zip file is empty", p2-cache race with a concurrent build).
- Subsequent rebuild attempts raced with a second agent session concurrently
  running `clean verify` in the same repository: one run hit a transient compile
  error in `ModelManagementHttpHandler` while that file was mid-edit in the other
  session (the on-disk version compiles), another lost
  `local-artifacts.properties` to the other session's `clean`. No failure
  originates from this task's changes; the modified modules compile and their
  tests pass whenever the build is not racing.
- Runtime verification (materialized product, Java 21, isolated config dir
  `/tmp/opencode/dstudio-ux-probe-2`, port 48242, commands piped to stdin):
  - `help` → full command list including `start` and `restart`.
  - `status` → `APPLICATION_READY`, TEIP server running on 127.0.0.1:48242.
  - `restart` → real restart: a new server instance bound to the same port
    ("[EVO-Studio] Server listening on 127.0.0.1:48242" +
    "EVO-Studio server restarted on 127.0.0.1:48242."), then `status` confirmed the
    restarted server.
  - `exit` → clean shutdown.

## 9. GUI behavior verified

- RCP GUI: not verified interactively (no X server on this host;
  `useUIHarness=false`).
- Headless CLI behavior verified end-to-end against the materialized product
  (see §8).

## 10. Remaining limitations

- Console Clear/Copy/Save-Output context actions are not implemented for the
  Eclipse `MessageConsole` (would require a `ConsolePageParticipant`).
- Terminal-emulator probing is duplicated between `TerminalLauncher` (ui bundle)
  and `DStudioCli` (application bundle) — headless safety keeps them separate.
- The stale `ApplicationActionBarAdvisor` comment mentions a non-existent "AI"
  menu (cosmetic, left untouched).
- Session rename/duplicate/delete are not owned by EVO-Studio (family contract:
  contributed disabled).
