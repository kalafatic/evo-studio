# EVO-Studio Inference Workspace (OpenCode-like AI coding chat with harness selection)

## Purpose

The same inference workspace presentation as EVO-Code, over EVO-Studio's own backends:
conversation with streaming, markdown/code rendering, composer, session navigation,
project/agent/profile/search selection, execution status and a prominent Harness
selector connecting to the harness-specific execution backend.

## Architecture

Presentation bundle **`eu.kalafatic.dstudio.ui.inference`** — source-identical port
of `eu.kalafatic.dcode.ui.inference` (same `ChatWorkspaceComposite`, markdown
renderer, conversation panel, composer, toolbar, session list, search panel and
`ChatWorkspaceBackend`/`ChatWorkspaceUi` SPI; package-renamed).

Product side: `eu.kalafatic.dstudio.ui.workspace.InferenceWorkspaceView` (view id
`eu.kalafatic.dstudio.ui.view.inferenceWorkspace`), default right-side view of the
Development perspective.

## Execution flow

- Conversation: `InferenceService` sessions over the selected inference engine
  (Provider/Model selectors). Send streams chunks on a background thread
  (`streamMessage`), Stop cancels cooperatively; sessions persist in SQLite
  (multi-turn, New/Open/Rename/Delete).
- Tasks: the composer's **Run task** action submits through
  `ExecutionService` with the selected harness (EVO-Code / OpenCode / Claude Code /
  Aider). Live `ExecutionEvent`s (QUEUED/STARTED/LOG/TOOL/COMPLETED/FAILED/
  CANCELLED) stream into the conversation; modified files are listed on completion.
- Harness selection is capability-aware: the Agent selector is populated from the
  harness's `HarnessCapabilityDescriptor.agents()` (hidden when the harness exposes
  none), harness connection state is shown, and the task passes provider/model only
  when the harness declares a compatible provider type.
- Profile selector = `AgentProfileRegistry` (passed as `agentProfileId`);
  Search = Disabled / filesystem regex grep over the selected project (no git).

## Backend additions

- `InferenceSession` gained a persisted `title`; schema migration **V5**
  (crash-recoverable: recreates missing V3 tables, adds the column when absent).
- `InferenceService.renameSession/deleteSession` +
  `InferenceSessionStore.deleteSession` (SQLite) +
  `POST /api/inference/sessions/{id}/rename`, `DELETE /api/inference/sessions/{id}`
  (HTTP), title in the session wire format.
- `DStudioUiPlugin.inferenceService()` accessor.
