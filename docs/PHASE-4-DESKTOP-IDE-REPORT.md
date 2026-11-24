# PHASE-4-DESKTOP-IDE-REPORT.md

**EVO-Studio Phase 4 — WP-4.07 deliverable**
**Date:** 2026-11-16

## 1. Scope

The EVO-Studio desktop mode is a professional Eclipse RCP WYSIWYG IDE built on the Eclipse
2025-12 platform (classic 3.x workbench), with JDT (Java editing), EGit (repository views),
Eclipse Search, Compare, Console and the Common Navigator primitives available.

## 2. Implemented Capabilities

### Workbench and perspectives (plugin.xml + `ui/perspective/*`)
- **Development** — Project Explorer (left), editors (center), Console + Problems (bottom),
  Executions + Harnesses (right).
- **AI & Harness** — Harnesses view, Executions view, Console.
- **Review** — Change Review view, Executions view, editor area for Compare sessions.
- **Repository** — Project Explorer + EGit Repositories/History/Staging views.
- Workbench state persistence via `setSaveAndRestore(true)` (Eclipse `-data` workspace);
  perspective switching via the standard perspective bar/shortcuts.

### Project and repository management
- `ProjectExplorerView` (TreeViewer, content/label providers, file-system backed,
  hidden-file filtering, folder-first sorting, project + folder + file icons, multi-select).
- Commands/handlers: Open Project (`DirectoryDialog`), Refresh (F5), Close Project
  (context menu), Run Harness Here (context menu).
- `EclipseWorkspaceBridge` links every EVO-Studio project into the Eclipse workspace
  (linked project for external roots), enabling IFile-based editing, markers and JDT.

### Source editing (WYSIWYG)
- Double-click opens files in the default Eclipse editor (`IDE.openEditorOnFileStore` /
  IFile editors via the linked workspace) — full text editing, multiple editor tabs, line
  numbers, dirty-state tracking, find/replace, JDT Java editor with syntax highlighting,
  diagnostics markers (Problems view) and Eclipse Search for workspace projects.

### Development environment
- Execution console: `org.eclipse.ui.console` MessageConsole factory ("EVO-Studio Execution
  Console"); `DStudioUiStartup` subscribes to the core EventBus and streams execution
  events into the console (EXECUTION_LOG, TOOL_INVOKED, lifecycle events).
- Problems view (`org.eclipse.ui.views.ProblemView`), EGit views in the Repository
  perspective.

### Harness workflow
- `HarnessView`: harness table (name/type/enabled/status/default), Set Default,
  Enable/Disable, Test Connection, Configure (config dialog with adapter-specific keys,
  persisted through the core registry).
- `RunHarnessDialog`: project + harness + model + instruction + auto-approve → submits
  through the same `ExecutionService` used by the headless TEIP runtime.
- `ExecutionView`: live task table (id/harness/status/message), auto-refresh via EventBus
  subscription (UI-thread-safe `asyncExec`), Cancel Selected, Run Task.
- Key bindings: Ctrl+Enter (Run Task), F5 (Refresh).

### Code review
- `ReviewView`: pick a completed execution with a review → file table with decisions;
  Accept / Reject / Reset-to-Pending persisted through `ReviewService`; double-click opens
  an Eclipse Compare side-by-side session (before = HEAD content via JGit, after = current
  working-tree content; new files show empty base).

## 3. MVC Boundary Verification

- Model: core domain (`eu.kalafatic.dstudio.core.model`) + core presentation data; views
  read only via `DStudioUiPlugin` accessors to core services.
- View: all SWT/JFace code in `eu.kalafatic.dstudio.ui`; no view launches processes,
  touches SQLite or speaks TEIP.
- Controller: commands/handlers (`ui/handlers/*`) + dialogs call core application services
  (`ProjectService`, `ExecutionService`, `ReviewService`, `HarnessRegistry`) — the same
  services the headless TEIP runtime invokes (verified in WP-4.08).
- Core bundles contain zero references to SWT/workbench types (verified by compilation
  against UI-free manifests).

## 4. Test Evidence

| Check | Result |
|---|---|
| Full reactor with UI bundle + product materialization | BUILD SUCCESS (Tycho 4.0.8), 74/74 tests |
| plugin.xml schema validity (perspectives/views/commands/bindings/menus/console factory/startup) | validated by Tycho during build |
| Headless launch loads ui bundle extensions without SWT (singleton fixed) | verified in headless product run log |
| Workbench GUI runtime | **NOT VERIFIED ON THIS HOST** — no X server / xvfb (same constraint TRYST Phase 3 faced); launch procedure below |

Launch procedure on a graphical host:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./product/eu.kalafatic.dstudio.product/target/products/eu.kalafatic.dstudio.product.id/linux/gtk/x86_64/dstudio
```

## 5. Known Limitations

- GUI runtime verification blocked by missing X server on this host (documented, not
  silently assumed).
- Editor default for external (non-workspace) files is the Eclipse text editor; full JDT
  capabilities apply once a project is opened (linked into the workspace).
- No custom language servers; JDT covers Java, other languages use the standard text
  editor (per constraints, no invented tooling).
- EGit views require repositories to be opened through EGit's own import flow
  (EVO-Studio's Project Explorer + JGit diff collection are independent of that).
