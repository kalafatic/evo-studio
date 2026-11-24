# EVO-Studio Codebase Exploration Summary

## 1. Overall Project Structure

EVO-Studio is a Java 21 Eclipse RCP/Equinox/OSGi application with the following main bundles/modules (dependency direction: `ui → application → {teip, harness, infrastructure} → core`):

| Bundle | Description |
|--------|-------------|
| `eu.kalafatic.dstudio.ui` | Presentation: views, editors, perspectives, commands/handlers (SWT/JFace) |
| `eu.kalafatic.dstudio.application` | Lifecycle coordinator + desktop/headless IApplication |
| `eu.kalafatic.dstudio.teip` | TEIP 1.1 provider (HTTP, DTOs, auth, execution bridge) |
| `eu.kalafatic.dstudio.harness` | Harness adapters (opencode, aider, EVO-Code, ClaudeCode) |
| `eu.kalafatic.dstudio.infrastructure` | SQLite persistence, configuration, JGit, process support |
| `eu.kalafatic.dstudio.core` | Domain, SPI, application services, event bus (no UI deps) |
| `eu.kalafatic.dstudio.ui.inference` | Inference workspace UI (backend-agnostic chat presentation) |

Key files in root:
- `pom.xml` - Maven build (Tycho 4.0.8)
- `features/eu.kalafatic.dstudio.feature/` - Feature definition
- `plugins/` - 7 main plugin directories

## 2. Web Interface, Settings Tab, LLM Provider Selection

### Web Interface (TEIP provider web UI)
- `plugins/eu.kalafatic.dstudio.teip/web/settings.html` - Settings page with Runtime, Defaults, Global execution configuration, Persistence, Projects, Harnesses, Inference engines, Agent profiles
- `plugins/eu.kalafatic.dstudio.teip/web/app.js` - Vanilla JS web client handling engine/model/session selection, chat streaming, events
- `plugins/eu.kalafatic.dstudio.teip/web/style.css` - UPM design system styling (dark theme default)

### Settings UI (RCP desktop)
- `plugins/eu.kalafatic.dstudio.ui/src/eu/kalafatic/dstudio/ui/editor/DStudioMultiPageEditor.java` - Multi-page editor with "Settings" page, loads settings from `/web/settings.html`
- `plugins/eu.kalafatic.dstudio.ui/src/eu/kalafatic/dstudio/ui/workbench/WebInterfaceLauncher.java` - Launches web interface in system browser

### LLM Provider / Inference Engine Selection
- `plugins/eu.kalafatic.dstudio.ui/src/eu/kalafatic/dstudio/ui/workspace/InferenceWorkspaceView.java` (lines 115-134): Sets up 7 selectors:
  - `project` - from project service
  - `harness` - from harness registry
  - `provider` - inference engine/provider (from inference engine registry)
  - `model` - from engine adapter's `listModels()`
  - `agent` - from harness capabilities (capability-dependent)
  - `profile` - agent profile selector
  - `search` - search integration
- `plugins/eu.kalafatic.dstudio.ui.inference/src/eu/kalafatic/dstudio/inference/ui/model/SelectorSpec.java` - Declarative selector spec
- `plugins/eu.kalafatic.dstudio.ui.inference/src/eu/kalafatic/dstudio/inference/ui/model/SelectOption.java` - Select option model
- `plugins/eu.kalafatic.dstudio.ui.inference/src/eu/kalafatic/dstudio/inference/ui/ToolbarBuilder.java` - Renders toolbar from selector specs (SWT `Combo`, `READ_ONLY`)
- `plugins/eu.kalafatic.dstudio.teip/web/settings.html` lines 96-110: Global execution config with Provider and Model dropdowns

## 3. EVO-Code Comparison/Integration

### EVO-Code-related reports
- `docs/PHASE-4-DCODE-COMPATIBILITY-REPORT.md` - Contract-only, runtime deferred to Phase 5
- `docs/PHASE-8-DCODE-DSTUDIO-COORDINATED-REPORT.md` - Coordinated implementation report
- `PHASE-6-DSTUDIO-INTEGRATION-REPORT.md` - EVO-Code is the default coding harness

### EVO-Code harness integration
- EVO-Code is the default harness (`eu.kalafatic.dstudio.harness.dcode.DCodeHarnessAdapter`)
- FHC 1.0 wire mapping over EVO-Code `/api/v1`
- EVO-Code uses port 58xxx (family convention)
- EVO-Code Web IDE is DEFERRED; EVO-Studio Web IDE is live-verified

### Harness SPI (`core/harness`)
- `HarnessRegistry.java` - Authoritative interface
- `DefaultHarnessRegistry.java` - SQLite-persisted implementation
- `HarnessAdapter.java` - Adapter interface
- `HarnessDescriptor.java` - Descriptor model
- `HarnessCapabilityDescriptor.java` - Capability model

### Test harness adapters
- `FakeHarnessAdapter.java` - Fake adapter for testing
- `OpenCodeHarnessAdapterTest.java`, `AiderHarnessAdapterTest.java`, `DCodeHarnessAdapterTest.java`

### TEIP server using harness
- `DStudioServer.java` - Creates server with harness registry
- `ModelManagementHttpHandler.java` - Model management over HTTP

## 4. Error Handling for Image Input

Search results for "Cannot read" / "image input":
- **1 match**: `InferenceWorkspaceView.java:748` - `prompt.append("(unreadable file: ").append(e.getMessage()).append(")\n\n");` in `buildPrompt()`
- `WebAssetsHandler.java:22-23` - Handles `svg` and `ico` MIME types
- File attachment handling in `buildPrompt()` (lines 732-753): reads attachments, handles 200KB size limit, catches `IOException` with "(unreadable file: ...)" message

**No specific "Cannot read image" error messages found in the codebase.**

## 5. Harness-Related Files

### Core harness interfaces (`core/harness/`)
- `HarnessRegistry.java` - Interface with registerAdapter, unregisterHarness, listHarnesses, findHarness, findAdapter, selectDefaultHarness, setHarnessEnabled, updateConfiguration, getConfiguration, testConnection, aggregateCapabilities
- `DefaultHarnessRegistry.java` - Default implementation
- `HarnessAdapter.java` - Adapter interface
- `HarnessDescriptor.java` - Harness description (ID, name, status, enabled)
- `HarnessCapabilityDescriptor.java` - Capability descriptor (supported provider types, agents)

### Harness adapters (`harness/`)
- `opencode` CLI adapter
- `aider` CLI adapter
- `dcode` FHC 1.0 wire adapter
- `claude-code` adapter

### Test adapters
- `FakeHarnessAdapter.java` - Test fake
- `OpenCodeHarnessAdapterTest.java`, `AiderHarnessAdapterTest.java`, `DCodeHarnessAdapterTest.java`

### TEIP server using harness
- `DStudioServer.java` - Server construction with harness registry
- `TeipExecutionManager.java` - Execution bridge

### UI harness view
- `HarnessView.java` - Harness perspective view
- `HarnessConfigDialog.java` - Configuration dialog