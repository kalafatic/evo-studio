package eu.kalafatic.dstudio.ui.execution;

import eu.kalafatic.dstudio.core.agent.AgentProfile;
import eu.kalafatic.dstudio.core.agent.AgentProfileRegistry;
import eu.kalafatic.dstudio.core.config.ExecutionConfiguration;
import eu.kalafatic.dstudio.core.config.ExecutionConfigurationService;
import eu.kalafatic.dstudio.core.harness.HarnessCapabilityDescriptor;
import eu.kalafatic.dstudio.core.harness.HarnessRegistry;
import eu.kalafatic.dstudio.core.model.ExecutionTask;
import eu.kalafatic.dstudio.core.model.HarnessDescriptor;
import eu.kalafatic.dstudio.core.model.Project;
import eu.kalafatic.dstudio.core.service.ExecutionService;
import eu.kalafatic.dstudio.core.service.ProjectService;
import eu.kalafatic.dstudio.ui.DStudioUiPlugin;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

public class RunHarnessDialog extends Dialog {

    private static final int RESTORE_DEFAULTS_ID = IDialogConstants.CLIENT_ID + 1;
    private static final String MODEL_LOADING_PLACEHOLDER = "(loading models…)";
    private static final String MODEL_DISCOVERY_FAILED_PREFIX =
            "(model discovery failed";

    private Combo projectCombo;
    private Combo harnessCombo;
    private Combo providerCombo;
    private Combo modelCombo;
    private Combo agentCombo;
    private Text instructionText;
    private Button autoApproveCheckbox;
    private List<Project> projects = List.of();
    private List<HarnessDescriptor> harnesses = List.of();
    private List<AgentProfile> agentProfiles = List.of();
    private List<String> engineIds = List.of();
    /** Bumps on every refresh request so stale async results are discarded. */
    private int modelLoadGeneration;

    public RunHarnessDialog(Shell parentShell) {
        super(parentShell);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Run Coding Task");
        shell.setSize(620, 520);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        container.setLayout(new GridLayout(2, false));
        ProjectService projectService = DStudioUiPlugin.getInstance().projectService();
        HarnessRegistry registry = DStudioUiPlugin.getInstance().harnessRegistry();
        AgentProfileRegistry agentRegistry = DStudioUiPlugin.getInstance()
                .agentProfileRegistry();
        projects = projectService == null ? List.of() : projectService.listProjects();
        harnesses = registry == null ? List.of() : registry.listHarnesses();
        agentProfiles = agentRegistry == null ? List.of() : agentRegistry.listProfiles();
        var engineRegistry = DStudioUiPlugin.getInstance().inferenceEngineRegistry();
        engineIds = new ArrayList<>();
        if (engineRegistry != null) {
            for (var engine : engineRegistry.listEngines()) {
                if (engine.isEnabled()) {
                    engineIds.add(engine.getId());
                }
            }
        }

        Label header = new Label(container, SWT.WRAP);
        header.setText("Run a coding task. Fields marked * are required; the other fields "
                + "default to your most recently used values.");
        GridData headerData = new GridData(GridData.FILL_HORIZONTAL);
        headerData.horizontalSpan = 2;
        header.setLayoutData(headerData);

        Label projectLabel = new Label(container, SWT.NONE);
        projectLabel.setText("Project *");
        projectCombo = new Combo(container, SWT.READ_ONLY);
        projectCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        projectCombo.setToolTipText("Select the project you want to work with.");
        for (Project project : projects) {
            projectCombo.add(project.name());
        }
        if (!projects.isEmpty()) {
            projectCombo.select(initialProjectIndex());
        }

        Label harnessLabel = new Label(container, SWT.NONE);
        harnessLabel.setText("Harness *");
        harnessCombo = new Combo(container, SWT.READ_ONLY);
        harnessCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        harnessCombo.setToolTipText("The coding harness that executes the task.");
        for (HarnessDescriptor harness : harnesses) {
            harnessCombo.add(harness.getId() + " (" + harness.getType() + ")");
        }

        Label providerLabel = new Label(container, SWT.NONE);
        providerLabel.setText("Provider");
        providerCombo = new Combo(container, SWT.READ_ONLY);
        providerCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        providerCombo.setToolTipText("Inference engine (provider). Optional — leave empty "
                + "to use the harness default.");
        providerCombo.add("");
        for (String engineId : engineIds) {
            providerCombo.add(engineId);
        }

        Label modelLabel = new Label(container, SWT.NONE);
        modelLabel.setText("Model");
        modelCombo = new Combo(container, SWT.BORDER);
        modelCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        modelCombo.setToolTipText("The language model for this task. Optional — leave empty "
                + "to use the harness default; any value can also be typed.");

        Label agentLabel = new Label(container, SWT.NONE);
        agentLabel.setText("Agent Profile");
        agentCombo = new Combo(container, SWT.READ_ONLY);
        agentCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        agentCombo.setToolTipText("Reusable agent persona. Optional — leave empty for no "
                + "profile.");
        agentCombo.add("");
        for (AgentProfile profile : agentProfiles) {
            agentCombo.add(profile.getName());
        }

        Label instructionLabel = new Label(container, SWT.NONE);
        instructionLabel.setText("Instruction *");
        instructionText = new Text(container, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL
                | SWT.WRAP);
        instructionText.setLayoutData(new GridData(GridData.FILL_BOTH));
        instructionText.setToolTipText("Describe what the coding task should do. Required.");

        Label autoLabel = new Label(container, SWT.NONE);
        autoLabel.setText("Auto-approve");
        autoApproveCheckbox = new Button(container, SWT.CHECK);
        autoApproveCheckbox.setSelection(false);
        autoApproveCheckbox.setToolTipText("Automatically accept file changes without "
                + "review. Recommended to leave unchecked.");

        // models must follow the harness selection: only compatible engine types are offered
        harnessCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                refreshModelChoices();
            }
        });
        // switching project re-applies that project's remembered selections
        projectCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                applyDefaultsForSelection();
            }
        });

        applyDefaultsForSelection();
        return container;
    }

    /**
     * Pre-populates the selection controls from the effective execution configuration
     * (GLOBAL defaults overridden by the PROJECT override), falling back to the registry
     * default selections and finally to the first available entry.
     */
    private void applyDefaultsForSelection() {
        ExecutionConfiguration resolved = resolveConfig(selectedProject());
        applyHarness(resolved.harnessId());
        applyProvider(resolved.providerId());
        applyAgent(resolved.agentProfileId());
        applyModel(resolved.modelId());
    }

    private ExecutionConfiguration resolveConfig(Project project) {
        ExecutionConfigurationService service = configService();
        if (service != null) {
            try {
                return service.resolve(project == null ? null : project.id(), null);
            } catch (RuntimeException e) {
                // fall through to the empty container below
            }
        }
        return ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_GLOBAL);
    }

    private void applyHarness(String harnessId) {
        if (harnessId == null || harnessId.isBlank()) {
            harnessId = defaultHarnessId();
        }
        if (harnessId != null && !harnessId.isBlank()) {
            for (int i = 0; i < harnesses.size(); i++) {
                if (harnesses.get(i).getId().equals(harnessId)) {
                    harnessCombo.select(i);
                    return;
                }
            }
        }
        if (harnessCombo.getSelectionIndex() < 0 && !harnesses.isEmpty()) {
            harnessCombo.select(0);
        }
    }

    private void applyProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            providerId = defaultEngineId();
        }
        if (providerId != null && !providerId.isBlank()) {
            int index = engineIds.indexOf(providerId);
            if (index >= 0) {
                providerCombo.select(index + 1);
                return;
            }
        }
        if (providerCombo.getSelectionIndex() < 0) {
            providerCombo.select(0);
        }
    }

    private void applyAgent(String agentProfileId) {
        if (agentProfileId == null || agentProfileId.isBlank()) {
            agentProfileId = defaultProfileId();
        }
        if (agentProfileId != null && !agentProfileId.isBlank()) {
            for (int i = 0; i < agentProfiles.size(); i++) {
                if (agentProfiles.get(i).getId().equals(agentProfileId)) {
                    agentCombo.select(i + 1);
                    return;
                }
            }
        }
        if (agentCombo.getSelectionIndex() < 0) {
            agentCombo.select(0);
        }
    }

    private void applyModel(String modelId) {
        modelCombo.setText(modelId == null ? "" : modelId);
        refreshModelChoices();
    }

    private ExecutionConfigurationService configService() {
        return DStudioUiPlugin.getInstance().executionConfigurationService();
    }

    private ExecutionService executionService() {
        return DStudioUiPlugin.getInstance().executionService();
    }

    private String defaultHarnessId() {
        HarnessRegistry registry = DStudioUiPlugin.getInstance().harnessRegistry();
        return registry == null ? "" : registry.getSelectedDefaultHarnessId().orElse("");
    }

    private String defaultEngineId() {
        var registry = DStudioUiPlugin.getInstance().inferenceEngineRegistry();
        return registry == null ? "" : registry.getSelectedDefaultEngineId().orElse("");
    }

    private String defaultProfileId() {
        var registry = DStudioUiPlugin.getInstance().agentProfileRegistry();
        return registry == null ? "" : registry.getDefaultProfileId().orElse("");
    }

    private int initialProjectIndex() {
        String recent = mostRecentlyUsedProjectId();
        if (recent != null && !recent.isBlank()) {
            for (int i = 0; i < projects.size(); i++) {
                if (projects.get(i).id().equals(recent)) {
                    return i;
                }
            }
        }
        return projects.isEmpty() ? -1 : 0;
    }

    private String mostRecentlyUsedProjectId() {
        ExecutionService service = executionService();
        if (service == null) {
            return null;
        }
        try {
            return service.listTasks().stream()
                    .filter(task -> task.getProjectId() != null
                            && !task.getProjectId().isBlank())
                    .max(Comparator.comparing(ExecutionTask::getCreatedAt))
                    .map(ExecutionTask::getProjectId)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    protected void okPressed() {
        ExecutionService service = executionService();
        if (service == null) {
            MessageDialog.openError(getShell(), "Run Task",
                    "The execution service is not available.");
            return;
        }
        if (projects.isEmpty()) {
            MessageDialog.openInformation(getShell(), "Run Task",
                    "No projects are open. Open a project first (Project → Open Project…).");
            return;
        }
        if (harnesses.isEmpty()) {
            MessageDialog.openInformation(getShell(), "Run Task",
                    "No coding harnesses are available. Configure a harness first.");
            return;
        }
        Project project = selectedProject();
        HarnessDescriptor harness = selectedHarness();
        if (project == null || harness == null) {
            MessageDialog.openError(getShell(), "Run Task",
                    "A project and a harness must be selected.");
            return;
        }
        String providerId = "";
        if (providerCombo.getSelectionIndex() > 0) {
            int engineIndex = providerCombo.getSelectionIndex() - 1;
            if (engineIndex < engineIds.size()) {
                providerId = engineIds.get(engineIndex);
            }
        }
        String agentProfileId = "";
        if (agentCombo.getSelectionIndex() > 0) {
            int profileIndex = agentCombo.getSelectionIndex() - 1;
            if (profileIndex < agentProfiles.size()) {
                agentProfileId = agentProfiles.get(profileIndex).getId();
            }
        }
        String model = modelCombo.getText().trim();
        if (model.startsWith(MODEL_DISCOVERY_FAILED_PREFIX)) {
            // the error placeholder is informative only, never a model id
            model = "";
        }
        String instruction = instructionText.getText().trim();
        if (instruction.isBlank()) {
            MessageDialog.openError(getShell(), "Run Task", "Instruction is required.");
            return;
        }
        ExecutionService.ExecutionRequest request = new ExecutionService.ExecutionRequest(
                harness.getId(), project.id(), null, instruction, null,
                model.isBlank() ? null : model, null,
                autoApproveCheckbox.getSelection(), project.rootPath(),
                Duration.ofMinutes(30), providerId, agentProfileId,
                Map.of(), List.of());
        try {
            service.submit(request);
        } catch (RuntimeException e) {
            MessageDialog.openError(getShell(), "Run Task",
                    "Submission failed: " + e.getMessage());
            return;
        }
        persistLastUsed(project.id(), harness.getId(), providerId, model, agentProfileId);
        super.okPressed();
    }

    /**
     * Persists the successfully used selections so the same operation and new projects are
     * pre-populated next time: a PROJECT override (project-specific memory) plus the GLOBAL
     * defaults (last-used fallback). Only successful submissions are remembered.
     */
    private void persistLastUsed(String projectId, String harnessId, String providerId,
            String model, String agentProfileId) {
        ExecutionConfigurationService service = configService();
        if (service == null) {
            return;
        }
        ExecutionConfiguration configuration = new ExecutionConfiguration(
                nullToEmpty(harnessId), nullToEmpty(providerId), nullToEmpty(model),
                nullToEmpty(agentProfileId), Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_TASK);
        try {
            if (projectId != null && !projectId.isBlank()) {
                service.saveProjectOverride(projectId, configuration);
            }
            service.saveGlobal(configuration);
        } catch (RuntimeException e) {
            System.err.println("EVO-Studio: failed to remember last-used configuration: "
                    + e.getMessage());
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, RESTORE_DEFAULTS_ID, "Restore Defaults", false);
        createButton(parent, IDialogConstants.OK_ID, "Run", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL,
                false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == RESTORE_DEFAULTS_ID) {
            restoreDefaults();
            return;
        }
        super.buttonPressed(buttonId);
    }

    /**
     * Clears the persisted global and project overrides and re-applies the authoritative
     * recommended defaults (registry default selections plus detected environment).
     */
    private void restoreDefaults() {
        ExecutionConfigurationService service = configService();
        Project project = selectedProject();
        if (service != null) {
            try {
                service.removeGlobal();
                if (project != null) {
                    service.removeProjectOverride(project.id());
                }
            } catch (RuntimeException e) {
                System.err.println("EVO-Studio: failed to restore defaults: " + e.getMessage());
            }
        }
        applyDefaultsForSelection();
    }

    /**
     * Populates the model combo from the registered inference engines, filtered by the
     * harness's supported provider types (compatibility matrix) so the user is never
     * offered an incompatible combination. Free text remains available for explicit
     * provider/model values.
     *
     * <p>Model discovery is a blocking network operation (up to ~120s per engine), so it
     * runs on a background thread; the combo shows a placeholder while loading and an
     * explicit error entry when discovery fails. Stale results (a newer refresh request
     * arrived, or the dialog closed) are discarded.</p>
     */
    private void refreshModelChoices() {
        final String current = modelCombo.getText();
        final int request = ++modelLoadGeneration;
        modelCombo.removeAll();
        modelCombo.add(MODEL_LOADING_PLACEHOLDER);
        modelCombo.setText("");
        HarnessDescriptor harness = selectedHarness();
        var engineRegistry = DStudioUiPlugin.getInstance().inferenceEngineRegistry();
        if (engineRegistry == null || harness == null) {
            modelCombo.removeAll();
            modelCombo.add("");
            modelCombo.select(0);
            return;
        }
        // Snapshot the compatibility filter and the engine descriptors on the UI thread;
        // model discovery itself runs in the background.
        java.util.Set<String> supported = new java.util.LinkedHashSet<>(
                harnessRegistryCompatibility(harness));
        List<eu.kalafatic.dstudio.core.inference.InferenceEngineDescriptor> engines =
                new ArrayList<>();
        for (var engine : engineRegistry.listEngines()) {
            engines.add(engine);
        }
        org.eclipse.swt.widgets.Display display = getShell().getDisplay();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            java.util.Set<String> added = new java.util.LinkedHashSet<>();
            List<String> discovered = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (var engine : engines) {
                if (!engine.isEnabled() || (engine.getStatus() != null
                        && engine.getStatus().name().equals("ERROR"))) {
                    continue;
                }
                if (!supported.isEmpty() && !supported.contains(engine.getType())) {
                    continue;
                }
                var adapter = engineRegistry.findAdapter(engine.getId());
                if (adapter.isEmpty()) {
                    continue;
                }
                try {
                    for (var model : adapter.get().listModels()) {
                        if (added.add(model.id())) {
                            discovered.add(model.id());
                        }
                    }
                } catch (RuntimeException discoveryFailed) {
                    failures.add(engine.getId() + ": " + discoveryFailed.getMessage());
                }
                String defaultModel = adapter.get().getConnectionConfiguration()
                        .getOrDefault("defaultModel", "");
                if (!defaultModel.isBlank() && added.add(defaultModel)) {
                    discovered.add(defaultModel);
                }
            }
            final java.util.Set<String> discoveredIds = added;
            final List<String> discoveryFailures = failures;
            display.asyncExec(() -> {
                if (request != modelLoadGeneration || modelCombo == null
                        || modelCombo.isDisposed()) {
                    return;
                }
                modelCombo.removeAll();
                modelCombo.add("");
                for (String model : discovered) {
                    modelCombo.add(model);
                }
                if (!discoveryFailures.isEmpty()) {
                    modelCombo.add(MODEL_DISCOVERY_FAILED_PREFIX + ": "
                            + String.join("; ", discoveryFailures) + ")");
                }
                if (current != null && !current.isBlank()
                        && !MODEL_LOADING_PLACEHOLDER.equals(current)) {
                    if (!discoveredIds.contains(current)) {
                        modelCombo.add(current);
                    }
                    modelCombo.setText(current);
                } else if (modelCombo.getItemCount() > 0) {
                    modelCombo.select(0);
                }
            });
        });
    }

    private java.util.Set<String> harnessRegistryCompatibility(HarnessDescriptor harness) {
        var registry = DStudioUiPlugin.getInstance().harnessRegistry();
        if (registry == null) {
            return java.util.Set.of();
        }
        return registry.aggregateCapabilities(harness.getId()).stream()
                .map(HarnessCapabilityDescriptor::supportedProviderTypes)
                .filter(java.util.Objects::nonNull)
                .flatMap(java.util.List::stream)
                .collect(java.util.stream.Collectors.toSet());
    }

    private HarnessDescriptor selectedHarness() {
        int index = harnessCombo.getSelectionIndex();
        return index >= 0 && index < harnesses.size() ? harnesses.get(index) : null;
    }

    private Project selectedProject() {
        int index = projectCombo.getSelectionIndex();
        return index >= 0 && index < projects.size() ? projects.get(index) : null;
    }
}
