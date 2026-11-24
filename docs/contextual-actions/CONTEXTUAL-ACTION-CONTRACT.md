# Darwin Family — Contextual Action Contract

Canonical owner: **EVO-Studio** (`eu.kalafatic.dstudio` — repository `/home/petr/git/EVO-Studio-121126`).
Contract version: **1.0** (2026-11-20).

This document defines the shared, versioned contract for contextual actions across the four
Darwin family applications: **EVO**, **TRYST**, **EVO-Code**, **EVO-Studio**. Each repository
implements the contract **locally** against its own domain services; there are no shared
runtime bundles and no cross-repository build dependencies.

## 1. Principles

1. **Action availability is computed, never hardcoded per product.** An action is shown as
   executable only when the target object exists, its state permits the operation, the
   required capability is present, and the user is permitted to execute it.
2. **Disabled actions may remain visible with an explanation** (`disabledReason`). Actions
   that are meaningless for a context type are never listed at all.
3. **One business operation = one action id.** RCP and Web render the same ids differently
   but must execute the same domain operation with the same outcome.
4. **No mock handlers.** Every registered handler performs a real domain operation.
5. **No duplicate registries.** Each repository implements one contextual action registry
   that adapts its existing domain services (harness/provider/model/agent registries,
   session/task managers, project/repository services). No new global session manager or
   orchestration pipeline is introduced.
6. **Contract versioning.** All four repositories carry this contract at the same version;
   a change to the catalog below is a contract version bump (documented per repository in
   `docs/contextual-actions/`).

## 2. Context model

A context (`ContextSelection`) is the immutable evaluation input for all actions:

| Field | Type | Meaning |
|---|---|---|
| `application` | string | EVO / TRYST / DCODE / DSTUDIO |
| `surface` | string | RCP / WEB |
| `objectType` | string | one of the context types below (canonical lowercase id) |
| `objectId` | string | identity of the target object (empty for registry-level contexts) |
| `objectLabel` | string | display name |
| `objectState` | map | state keys, e.g. `status=running`, `enabled=true`, `isDefault=false` |
| `multiSelect` | bool | selection contains multiple targets |
| `projectId` / `sessionId` / `harnessId` / `providerId` / `modelId` / `agentId` | string | ambient selection from the surrounding surface |
| `capabilities` | set | capabilities of the target object (or aggregate for registries) |
| `permissions` | set | granted permissions of the acting user |
| `user` | string | acting user (empty = system/local user) |

**Canonical context types:**

`harness-registry`, `harness`, `provider-registry`, `provider`, `model`,
`agent-registry`, `agent`, `session`, `task`, `execution`, `project`, `repository`,
`workspace`, `server`, `endpoint`, `configuration`, `artifact`, `log`, `review`.

Nested and multi-selection contexts: `multiSelect=true` disables single-target mutations
(select/set-default/edit/remove) while retaining read-only actions.

## 3. Action descriptor

```json
{
  "contractVersion": "1.0",
  "actionId": "eu.kalafatic.contextual.harness.select",
  "label": "Select Harness",
  "description": "Makes this harness the default for new executions.",
  "group": "selection",
  "contextTypes": ["harness"],
  "requiredCapabilities": [],
  "confirmation": "NONE",
  "dangerous": false
}
```

| Field | Meaning |
|---|---|
| `actionId` | globally unique, stable identifier (see §5) |
| `group` | menu group for ordering: `selection`, `configuration`, `lifecycle`, `inspection`, `danger` |
| `contextTypes` | the object types the action applies to |
| `requiredCapabilities` | capabilities the target must advertise for the action to be enabled |
| `confirmation` | `NONE`, `CONFIRM` (irreversible state change), `DANGEROUS` (destructive, explicit warning) |
| `dangerous` | marks red styling in RCP/Web |

The schema for descriptor JSON lives next to this file:
`contextual-action.schema.json`.

## 4. Evaluation and execution semantics

**Evaluation order** (per action, per selection):
1. context type match → 2. object state check → 3. capability check → 4. permission check
→ 5. parameter validation. The result is an availability triple
`{visible, enabled, disabledReason}`.

**Execution contract**:
1. Resolve target object from `objectId`/`objectType`; abort with a clear error if missing.
2. Re-evaluate availability; refuse execution if the action is not enabled.
3. `confirmation=CONFIRM|DANGEROUS` → the UI layer requests confirmation before invoking
   the handler.
4. Report progress (where long-running), prevent duplicate execution per target while
   running (per-action per-target execution guard), support cancellation where the domain
   service supports it.
5. Report success/failure; the calling surface refreshes the affected views/lists.
6. Error shape (Web): `{ "ok": false, "error": "<code>", "message": "<human text>" }`;
   RCP: message dialog / status feedback. Codes: `TARGET_NOT_FOUND`, `ACTION_DISABLED`,
   `PERMISSION_DENIED`, `CONFIRMATION_REQUIRED`, `EXECUTION_FAILED`.

## 5. Canonical action id catalog (contract v1.0)

Prefix: `eu.kalafatic.contextual.<domain>.<verb>`.

### harness-registry
| id | label | enabled when |
|---|---|---|
| `eu.kalafatic.contextual.harness-registry.refresh` | Refresh | always (registry exists) |
| `eu.kalafatic.contextual.harness-registry.scan` | Scan Environment | at least one harness registered |
| `eu.kalafatic.contextual.harness-registry.add` | Add Harness | product supports runtime harness registration |
| `eu.kalafatic.contextual.harness-registry.import-config` | Import Configuration | product supports config import |

### harness
| id | label | enabled when |
|---|---|---|
| `eu.kalafatic.contextual.harness.select` | Select Harness | harness available; not already default; single-select |
| `eu.kalafatic.contextual.harness.edit` | Edit Configuration | single-select |
| `eu.kalafatic.contextual.harness.configure` | Configure | single-select |
| `eu.kalafatic.contextual.harness.test` | Test Connection | single-select |
| `eu.kalafatic.contextual.harness.enable` | Enable | disabled harness |
| `eu.kalafatic.contextual.harness.disable` | Disable | enabled harness |
| `eu.kalafatic.contextual.harness.set-default` | Set as Default | not default; single-select |
| `eu.kalafatic.contextual.harness.start` | Start | harness capability `lifecycle:start` |
| `eu.kalafatic.contextual.harness.stop` | Stop | capability `lifecycle:stop` and running |
| `eu.kalafatic.contextual.harness.restart` | Restart | capabilities `lifecycle:start`+`lifecycle:stop` |
| `eu.kalafatic.contextual.harness.view-capabilities` | View Capabilities | always |
| `eu.kalafatic.contextual.harness.view-logs` | View Logs | capability `logs` |
| `eu.kalafatic.contextual.harness.remove` | Remove Registration | confirmation DANGEROUS |

### provider-registry / provider
| id | label | enabled when |
|---|---|---|
| `eu.kalafatic.contextual.provider-registry.refresh` | Refresh | always |
| `eu.kalafatic.contextual.provider-registry.scan` | Scan Environment | ≥1 provider registered |
| `eu.kalafatic.contextual.provider-registry.add` | Add Provider | product supports runtime registration |
| `eu.kalafatic.contextual.provider.select` | Select Provider | available; not default; single-select |
| `eu.kalafatic.contextual.provider.edit` | Configure | single-select |
| `eu.kalafatic.contextual.provider.test` | Test Connection | single-select |
| `eu.kalafatic.contextual.provider.enable` / `disable` | Enable / Disable | state-dependent |
| `eu.kalafatic.contextual.provider.set-default` | Set as Default | not default |
| `eu.kalafatic.contextual.provider.discover-models` | Discover Models | provider available |
| `eu.kalafatic.contextual.provider.view-endpoint` | View Endpoint | configuration exposes endpoint |
| `eu.kalafatic.contextual.provider.view-logs` | View Logs | capability `logs` |
| `eu.kalafatic.contextual.provider.remove` | Remove | confirmation DANGEROUS |

### model
| id | label | enabled when |
|---|---|---|
| `eu.kalafatic.contextual.model.select` | Select Model | model available |
| `eu.kalafatic.contextual.model.set-default` | Set as Default | not default |
| `eu.kalafatic.contextual.model.download` | Download | provider capability `model:download` |
| `eu.kalafatic.contextual.model.load` | Load | provider capability `model:load`; not loaded |
| `eu.kalafatic.contextual.model.unload` | Unload | capability `model:unload`; loaded |
| `eu.kalafatic.contextual.model.remove` | Remove | provider capability `model:remove`; DANGEROUS |
| `eu.kalafatic.contextual.model.refresh` | Refresh Metadata | always |
| `eu.kalafatic.contextual.model.test` | Test Inference | provider available |
| `eu.kalafatic.contextual.model.favorite` | Add to Favorites | not favorite |
| `eu.kalafatic.contextual.model.unfavorite` | Remove from Favorites | favorite |
| `eu.kalafatic.contextual.model.info` | View Model Information | always |
| `eu.kalafatic.contextual.model.copy-info` | Copy Model Information | always (clipboard surface) |

### agent-registry / agent
| id | label | enabled when |
|---|---|---|
| `eu.kalafatic.contextual.agent-registry.create` | Create Agent | always |
| `eu.kalafatic.contextual.agent-registry.import` | Import Agent | product supports import |
| `eu.kalafatic.contextual.agent.select` | Select Agent | agent valid |
| `eu.kalafatic.contextual.agent.edit` | Edit | single-select |
| `eu.kalafatic.contextual.agent.clone` | Clone | single-select |
| `eu.kalafatic.contextual.agent.set-default` | Set as Default | not default |
| `eu.kalafatic.contextual.agent.configure-instructions` | Configure Instructions | single-select |
| `eu.kalafatic.contextual.agent.configure-tools` | Configure Tools | single-select |
| `eu.kalafatic.contextual.agent.configure-permissions` | Configure Permissions | single-select |
| `eu.kalafatic.contextual.agent.export` | Export | single-select |
| `eu.kalafatic.contextual.agent.compatibility` | Inspect Compatibility | ambient harness/provider/model set |
| `eu.kalafatic.contextual.agent.delete` | Delete | confirmation DANGEROUS |

### session / task / execution
| id | label | enabled when |
|---|---|---|
| `eu.kalafatic.contextual.session.open` | Open Session | single-select |
| `eu.kalafatic.contextual.session.select` | Select Session | single-select |
| `eu.kalafatic.contextual.session.save` | Save Session | session dirty (where supported) |
| `eu.kalafatic.contextual.session.duplicate` | Duplicate | single-select |
| `eu.kalafatic.contextual.session.resume` | Resume | paused / interrupted |
| `eu.kalafatic.contextual.session.pause` | Pause | running; product supports pause |
| `eu.kalafatic.contextual.session.cancel` | Cancel | running/queued |
| `eu.kalafatic.contextual.session.archive` | Archive | terminal |
| `eu.kalafatic.contextual.session.export` | Export | single-select |
| `eu.kalafatic.contextual.session.delete` | Delete | confirmation DANGEROUS |
| `eu.kalafatic.contextual.session.history` | View History | always |
| `eu.kalafatic.contextual.session.details` | View Execution Details | always |
| `eu.kalafatic.contextual.session.artifacts` | View Artifacts | artifacts exist |

### project / repository
| id | label | enabled when |
|---|---|---|
| `eu.kalafatic.contextual.project.open` | Open | single-select |
| `eu.kalafatic.contextual.project.select` | Select Project | not current |
| `eu.kalafatic.contextual.project.scan` | Scan Repository | repository exists |
| `eu.kalafatic.contextual.project.analyze` | Analyze Architecture | product supports analysis |
| `eu.kalafatic.contextual.project.build` | Build | product supports build |
| `eu.kalafatic.contextual.project.test` | Test | product supports tests |
| `eu.kalafatic.contextual.project.git-status` | Git Status | git repository detected |
| `eu.kalafatic.contextual.project.commit` | Commit | git repository detected |
| `eu.kalafatic.contextual.project.push` | Push | git repository detected |
| `eu.kalafatic.contextual.project.diagnostics` | Repository Diagnostics | git repository detected |
| `eu.kalafatic.contextual.project.refresh` | Refresh | always |
| `eu.kalafatic.contextual.project.assign-agent` | Assign Agent | ≥1 agent registered |
| `eu.kalafatic.contextual.project.create-session` | Create Session | harness selected |
| `eu.kalafatic.contextual.project.close` | Close Project | project open |

Capability names referenced above: `lifecycle:start`, `lifecycle:stop`, `logs`,
`model:download`, `model:load`, `model:unload`, `model:remove`. Harness adapters advertise
capabilities; a harness without a capability shows the corresponding action disabled with
reason `not supported by this harness/provider`.

## 6. RCP/Web behavior rules

1. RCP renders actions as JFace `Action`s inside `MenuManager` (SWT `PopupMenu`) grouped by
   `group` with separators; `dangerous` actions use a warning style; disabled actions show
   `disabledReason` as tooltip. RCP uses standard `ISelectionService`/structured selection
   where available.
2. Web renders the same actions from a REST surface
   (`GET .../contextual/actions` for the selected object, `POST .../contextual/execute`
   for execution) using event delegation (no inline `onclick`), right-click context menus,
   three-dot overflow menus, confirmation dialogs for `CONFIRM`/`DANGEROUS`, progress and
   notifications; views refresh after execution.
3. The same selection must produce the same enabled action set on both surfaces modulo
   surface-only actions (clipboard, dialogs), which are marked surface-specific.

## 7. Ownership boundaries

- **EVO-Studio** owns harness/provider/model/agent administration capabilities and the
  canonical contract.
- **EVO-Code** owns native coding execution and its local sessions.
- **TRYST** owns task/workflow/repository/sandbox/session lifecycle.
- **EVO** owns Darwin orchestration and its existing session/iteration lifecycle.
Cross-application communication continues through existing TEIP/FHC interfaces; the
contextual action framework never bypasses them.
