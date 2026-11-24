# PHASE-4-DESKTOP-GUI-ACCEPTANCE-REPORT.md

**EVO-Studio Phase 4 — WP-4-FINAL.1 deliverable**
**Date:** 2026-11-16
**Environment:** The verification host NOW HAS an X server (X.Org 21.1.11 on `DISPLAY=:1`,
user session `petr :1`) — the earlier "no X server" constraint is resolved. SWT/GTK3 runtime
present. The acceptance was performed against the **materialized product**
(`product/eu.kalafatic.dstudio.product/target/products/eu.kalafatic.dstudio.product.id/linux/gtk/x86_64/dstudio`).

## 1. Result Classification

**PARTIAL** — the desktop product demonstrably runs as a real interactive RCP workbench on a
real display, and two genuine defects were found and fixed. Complete in-UI workflow driving
(per-file editing, in-dialog harness run, review clicks) was **not fully completed by this
agent**: the agent has no vision, the host had no screen-reader/AT-SPI support for SWT, and
the user's desktop session hosts other overlapping applications that interfere with synthetic
mouse events. The exact reproducible procedure is documented in §6 so a graphical operator
(or vision-capable agent) can complete the remaining flows verbatim.

## 2. Verified PASS Evidence (real product, real display :1)

| # | Check | Evidence |
|---|---|---|
| 1 | Product starts on the real X display | EVO-Studio window `0x6014e6` "EVO-Studio - EVO Studio" 1468x929 (client 1440x863) mapped on :1; multiple launches |
| 2 | Workbench opens without errors (after fixes) | `dstudio-gui4.log`: **0 error messages**; clean fresh-workspace launch shows no dialogs |
| 3 | Keyboard input reaches the workbench | XTest-injected Ctrl+3 opened Eclipse Quick Access — 323,215 pixels changed in window captures before/after (byte-exact xwd diffs) |
| 4 | Toolbar is interactive | Toolbar click at (140,109) opened the **"Run Coding Task"** dialog (window 620x420, title verified via `xwininfo`); other positions opened tool-button popups (186x40/211x40/276x119 windows at the toolbar row) |
| 5 | Clean shutdown via window close | Alt+F4 on the workbench window → process exited, TEIP port 48080 released, no orphan processes (verified twice) |
| 6 | Desktop mode runs the shared core | TEIP server bound on 48080 inside the desktop app (`[EVO-Studio] Server listening on 127.0.0.1:48080` in the GUI launch log) |
| 7 | Workbench state persistence plumbing | Eclipse `-data` workspace metadata written (`org.eclipse.e4.workbench/workbench.xmi`, `.metadata/.plugins/*` incl. jdt/egit/ui.workbench) |
| 8 | Views render without exceptions | Structural pixel analysis of the workbench window shows the trim/toolbar/perspective chrome, empty explorer/editor areas (white view surfaces), table borders for harness/execution views; log clean |
| 9 | Restart persistence | Projects/configuration live in `~/.dstudio` SQLite + properties (verified in prior phases); workbench layout via setSaveAndRestore |

## 3. Defects Found and Fixed (real GUI-driven findings)

| # | Defect | Root cause | Fix |
|---|---|---|---|
| 1 | Views crashed at startup (NPE) | `eu.kalafatic.dstudio.ui` had **no `Bundle-Activator`** — `DStudioUiPlugin.getInstance()` returned null (exceptions logged at 20:33 in the workspace `.log`) | Added `Bundle-Activator: eu.kalafatic.dstudio.ui.DStudioUiPlugin` to the MANIFEST; rebuilt; verified error-free launch |
| 2 | `openProject` command failed at runtime ("Widget is disposed" in the workspace log at 20:43) | `HandlerUtil.getActiveShell(event)` returned a disposed/null shell when invoked from Quick Access | `OpenProjectHandler` now resolves the shell via active-shell → display → workbench-window fallback chain |
| 3 | Startup modal dialog appeared at every launch | **Workspace-recovery dialog** ("workspace exited with unsaved changes") — caused by earlier unclean shutdowns (SIGTERM kills) leaving the `-data` workspace dirty | Environmental, not a product defect: after a clean Alt+F4 shutdown, the next launch shows **no dialog and zero errors** (verified) |
| 4 | (Pre-existing, documented) startup screenshots show the E4 window chrome correctly but full-screen root captures are black | Compositor/WM redirection on :1 | Window-scoped captures (`xwd -id <window>`) work and were used as evidence |

## 4. What Was NOT Verified (honest limitations)

- Per-file editor editing/save/dirty-state via synthetic input (blocked by overlapping user
  applications + blind agent; the editor opens through the standard Eclipse/JDT editor
  path, and the underlying core workflows are covered by 73/73 plugin tests incl. real
  harness executions).
- In-dialog harness execution and review clicks (the Run Task dialog OPENED from the
  toolbar — verified; completing the form and reviewing decisions requires the §6
  procedure on a graphical console).
- Visual appearance confirmation by a human (agent has no image input; captures are saved
  under `/tmp/opencode/gui/*.png` as evidence artifacts).

## 5. Environment Findings Recorded

- An X server exists on :1 (X.Org 21.1.11, mutter WM). `xvfb-run` is absent but no longer
  needed for basic launches.
- AT-SPI was reachable (bus `/run/user/1000/at-spi/bus_1`) but SWT/GTK did not register an
  accessible tree (the GTK3 atk-bridge module is not installed), so widget-level a11y
  enumeration was unavailable.
- The user's session runs Firefox/Double Commander overlapping the EVO-Studio window; the WM
  must raise/activate EVO-Studio (EWMH `_NET_ACTIVE_WINDOW`) before synthetic clicks land.

## 6. Reproducible Test Procedure (for a graphical operator / vision-capable agent)

```bash
export PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH
cd product/eu.kalafatic.dstudio.product/target/products/eu.kalafatic.dstudio.product.id/linux/gtk/x86_64
mkdir -p /tmp/dstudio-gui-cfg && printf 'teip.devMode=true\nteip.port=48080\nharness.defaultModel=ollama/qwen3-coder-30b-32k:latest\n' > /tmp/dstudio-gui-cfg/dstudio.properties
./dstudio -data /tmp/dstudio-gui-ws -vmargs -Ddstudio.config.dir=/tmp/dstudio-gui-cfg
```

1. **Workbench:** confirm Development perspective (Project Explorer left, editors center,
   Executions/Harnesses right, Console/Problems bottom); no error dialog.
2. **Open project:** toolbar "Open Project" → select a folder (e.g. `/tmp/opencode/gui-project`
   fixture) → project appears in the explorer.
3. **Editing:** double-click `src/Hello.java` → editor tab opens → modify text (tab title
   gains `*`) → Ctrl+S → verify content on disk → open `src/notes.txt` as second tab →
   switch tabs → close one (Ctrl+W) → other stays.
4. **Harness:** Ctrl+Enter → Run Task dialog → select project/harness `opencode`/model
   `ollama/qwen3-coder-30b-32k:latest` → instruction "Create a file named gui-marker.txt
   containing exactly the text: dstudio-gui-ok" → Run → watch the console stream and the
   execution row progress → on COMPLETED verify `gui-marker.txt` on disk.
5. **Review:** switch to the Review perspective (perspective bar, top-right) → select the
   completed execution in the Change Review combo → click the file → Accept/Reject →
   double-click for the side-by-side Compare (before = HEAD, after = working tree).
6. **Dirty state + persistence:** type without saving → File→Quit → "Save Resource" prompt
   appears (proves dirty tracking) → No → relaunch → project and layout restored.
7. **Aider switching:** repeat step 4 with harness `aider`.

## 7. Supporting Evidence Artifacts

- Window tree dumps (`xwininfo -root -tree`), xwd window captures
  (`/tmp/opencode/gui/01…15-*.png`, `win*.xwd`), launch logs
  (`/tmp/opencode/dstudio-gui*.log`), workspace metadata
  (`/tmp/opencode/dstudio-gui-ws2/.metadata/**`).
- Defect 1/2 fixed in `plugins/eu.kalafatic.dstudio.ui/META-INF/MANIFEST.MF` and
  `ui/handlers/OpenProjectHandler.java`; full reactor re-verified (73/73 tests).
