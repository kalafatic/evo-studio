package eu.kalafatic.dstudio.ui.workspace;

import eu.kalafatic.dstudio.core.agent.AgentProfile;
import eu.kalafatic.dstudio.core.event.EventBus;
import eu.kalafatic.dstudio.core.harness.HarnessCapabilityDescriptor;
import eu.kalafatic.dstudio.core.inference.InferenceEngineAdapter;
import eu.kalafatic.dstudio.core.inference.InferenceEngineDescriptor;
import eu.kalafatic.dstudio.core.inference.InferenceException;
import eu.kalafatic.dstudio.core.inference.InferenceService;
import eu.kalafatic.dstudio.core.inference.InferenceSession;
import eu.kalafatic.dstudio.core.model.ExecutionEvent;
import eu.kalafatic.dstudio.core.model.ExecutionTask;
import eu.kalafatic.dstudio.core.model.HarnessDescriptor;
import eu.kalafatic.dstudio.core.model.Project;
import eu.kalafatic.dstudio.core.service.ExecutionService;
import eu.kalafatic.dstudio.inference.ui.ChatWorkspaceBackend;
import eu.kalafatic.dstudio.inference.ui.ChatWorkspaceComposite;
import eu.kalafatic.dstudio.inference.ui.ChatWorkspaceUi;
import eu.kalafatic.dstudio.inference.ui.model.MessageKind;
import eu.kalafatic.dstudio.inference.ui.model.SearchResult;
import eu.kalafatic.dstudio.inference.ui.model.SelectOption;
import eu.kalafatic.dstudio.inference.ui.model.SelectorSpec;
import eu.kalafatic.dstudio.inference.ui.model.SessionInfo;
import eu.kalafatic.dstudio.inference.ui.model.StatusKind;
import eu.kalafatic.dstudio.ui.DStudioUiPlugin;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * The EVO-Studio inference workspace: the same OpenCode-like chat presentation as EVO-Code,
 * over EVO-Studio's own backends.
 *
 * <p>The conversation runs on the inference-engine service (streaming chunks,
 * persisted multi-turn sessions). Coding tasks run through the selected
 * <b>harness</b> (EVO-Code / OpenCode / Claude Code / Aider) via the execution service;
 * live execution events stream into the conversation. The Harness selector drives the
 * execution backend and capability-aware configuration: harness agents, connection
 * state and compatible providers.</p>
 */
public final class InferenceWorkspaceView extends ViewPart implements ChatWorkspaceBackend {

    public static final String VIEW_ID = "eu.kalafatic.dstudio.ui.view.inferenceWorkspace";

    private static final int MAX_ATTACHMENT_BYTES = 200 * 1024;
    private static final int MAX_TASK_LOG_CHARS = 24000;
    private static final DateTimeFormatter TITLE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ChatWorkspaceComposite composite;
    private ChatWorkspaceUi ui;

    private String projectId;
    private String harnessId;
    private String engineId;
    private String modelId;
    private String agentId;
    private String profileId;
    private InferenceSession activeSession;
    private String chatEntryId;
    private boolean chatBusy;
    private boolean taskBusy;
    private String taskExecutionId;
    private String taskEntryId;
    private int taskLogChars;
    private final Set<String> renderedMessageIds = new HashSet<>();

    private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dstudio-chat-stream");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService workExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "dstudio-workspace");
        thread.setDaemon(true);
        return thread;
    });
    private AutoCloseable executionSubscription;
    private boolean disposed;

    @Override
    public void createPartControl(Composite parent) {
        composite = new ChatWorkspaceComposite(parent, this);
        ui = composite;
        EventBus bus = DStudioUiPlugin.getInstance().eventBus();
        if (bus != null) {
            executionSubscription = bus.subscribe(ExecutionEvent.class,
                    this::onExecutionEvent);
        }
    }

    // ------------------------------------------------------- ChatWorkspaceBackend

    @Override
    public void init(ChatWorkspaceUi ui) {
        this.ui = ui;
        ui.setSelectors(List.of(
                new SelectorSpec("project", "Project", List.of(), null,
                        "Project the task operates on", true),
                new SelectorSpec("harness", "Harness", List.of(), null,
                        "Execution backend for coding tasks", true),
                new SelectorSpec("provider", "Provider", List.of(), null,
                        "Inference engine used for the conversation"),
                new SelectorSpec("model", "Model", List.of(), null,
                        "Model of the selected provider"),
                new SelectorSpec("agent", "Agent", List.of(), null,
                        "Agent of the selected harness (capability-dependent)"),
                new SelectorSpec("profile", "Profile", List.of(), null,
                        "Agent profile applied to task execution"),
                new SelectorSpec(ChatWorkspaceComposite.SEARCH_SELECTOR, "Search",
                        List.of(new SelectOption(ChatWorkspaceComposite.SEARCH_DISABLED,
                                "Disabled", "No search"),
                                new SelectOption("workspace", "Workspace search",
                                        "Regex search over the project files")),
                        ChatWorkspaceComposite.SEARCH_DISABLED,
                        "Search integration for the workspace")));
        Display.getDefault().asyncExec(this::refreshConfiguration);
    }

    @Override
    public void refreshConfiguration() {
        if (disposed) {
            return;
        }
        loadProjects();
        loadHarnesses();
        loadEngines();
        loadProfiles();
        refreshSessions();
    }

    private void loadProjects() {
        var service = DStudioUiPlugin.getInstance().projectService();
        if (service == null) {
            ui.setStatus("EVO-Studio services not ready", StatusKind.ERROR);
            return;
        }
        List<SelectOption> options = new ArrayList<>();
        for (Project project : service.listProjects()) {
            options.add(new SelectOption(project.id(), project.name(), project.rootPath(),
                    false));
        }
        if (projectId == null && !options.isEmpty()) {
            projectId = options.get(0).id();
        }
        ui.updateSelectorOptions("project", options, projectId);
        if (options.isEmpty()) {
            ui.setStatus("No projects \u2014 open a folder first (Project \u2192 Open"
                    + " Project)", StatusKind.INFO);
        }
    }

    private void loadHarnesses() {
        var registry = DStudioUiPlugin.getInstance().harnessRegistry();
        if (registry == null) {
            return;
        }
        List<SelectOption> options = new ArrayList<>();
        String selected = null;
        for (HarnessDescriptor descriptor : registry.listHarnesses()) {
            if (!descriptor.isEnabled()) {
                continue;
            }
            String label = descriptor.getName();
            if (descriptor.getStatus() == HarnessDescriptor.HarnessStatus.DISCONNECTED) {
                label = label + " (unavailable)";
            }
            options.add(new SelectOption(descriptor.getId(), label,
                    descriptor.getStatusMessage(), false));
            String defaultId = registry.getSelectedDefaultHarnessId().orElse(null);
            if (descriptor.getId().equals(defaultId)) {
                selected = descriptor.getId();
            }
        }
        if (harnessId == null || options.stream().noneMatch(
                option -> option.id().equals(harnessId))) {
            harnessId = selected != null ? selected
                    : options.isEmpty() ? null : options.get(0).id();
        }
        ui.updateSelectorOptions("harness", options, harnessId);
        ui.setTaskButtonVisible(harnessId != null);
        applyHarnessCapabilities();
    }

    private void applyHarnessCapabilities() {
        var registry = DStudioUiPlugin.getInstance().harnessRegistry();
        if (registry == null) {
            ui.setSelectorVisible("agent", false);
            return;
        }
        List<HarnessCapabilityDescriptor> capabilities = harnessId == null ? List.of()
                : registry.aggregateCapabilities(harnessId);
        if (capabilities.isEmpty()) {
            ui.setSelectorVisible("agent", false);
            agentId = null;
        } else {
            HarnessCapabilityDescriptor descriptor = capabilities.get(0);
            List<SelectOption> options = new ArrayList<>();
            for (String agent : descriptor.agents()) {
                options.add(new SelectOption(agent, agent));
            }
            if (options.isEmpty()) {
                ui.setSelectorVisible("agent", false);
                agentId = null;
            } else {
                ui.setSelectorVisible("agent", true);
                if (agentId == null || options.stream().noneMatch(
                        option -> option.id().equals(agentId))) {
                    agentId = options.get(0).id();
                }
                ui.updateSelectorOptions("agent", options, agentId);
            }
        }
        if (harnessId != null) {
            registry.listHarnesses().stream()
                    .filter(descriptor -> descriptor.getId().equals(harnessId))
                    .findFirst()
                    .ifPresent(descriptor -> ui.setStatus("Harness: "
                            + descriptor.getName() + " \u2014 "
                            + descriptor.getStatus().name().toLowerCase()
                            + (descriptor.getStatusMessage().isBlank() ? ""
                            : " (" + descriptor.getStatusMessage() + ")"),
                            descriptor.getStatus()
                                    == HarnessDescriptor.HarnessStatus.CONNECTED
                                    ? StatusKind.SUCCESS : StatusKind.INFO));
        }
    }

    private void loadEngines() {
        var registry = DStudioUiPlugin.getInstance().inferenceEngineRegistry();
        if (registry == null) {
            return;
        }
        List<SelectOption> options = new ArrayList<>();
        for (InferenceEngineDescriptor descriptor : registry.listEngines()) {
            if (!descriptor.isEnabled()) {
                continue;
            }
            options.add(new SelectOption(descriptor.getId(), descriptor.getName(),
                    descriptor.getType(), false));
        }
        if (engineId == null || options.stream().noneMatch(
                option -> option.id().equals(engineId))) {
            engineId = registry.getSelectedDefaultEngineId().orElse(
                    options.isEmpty() ? null : options.get(0).id());
        }
        ui.updateSelectorOptions("provider", options, engineId);
        if (engineId == null) {
            ui.updateSelectorOptions("model", List.of(), null);
            ui.setStatus("No inference engines configured", StatusKind.ERROR);
        } else {
            loadModels();
        }
    }

    private void loadModels() {
        var registry = DStudioUiPlugin.getInstance().inferenceEngineRegistry();
        if (registry == null || engineId == null) {
            return;
        }
        String currentEngine = engineId;
        workExecutor.submit(() -> {
            try {
                var adapter = registry.findAdapter(currentEngine).orElse(null);
                List<SelectOption> options = new ArrayList<>();
                if (adapter != null) {
                    for (InferenceEngineAdapter.InferenceModel model
                            : adapter.listModels()) {
                        if (model.available()) {
                            options.add(new SelectOption(model.id(), model.id(),
                                    model.name(), false));
                        }
                    }
                }
                String selected = modelId != null && options.stream().anyMatch(
                        option -> option.id().equals(modelId)) ? modelId
                        : options.isEmpty() ? null : options.get(0).id();
                modelId = selected;
                ui.updateSelectorOptions("model", options, modelId);
                if (options.isEmpty()) {
                    ui.setStatus("No models published by '" + currentEngine + "'",
                            StatusKind.INFO);
                }
            } catch (RuntimeException e) {
                ui.updateSelectorOptions("model", List.of(), null);
                ui.setStatus("Model discovery failed for '" + currentEngine + "': "
                        + e.getMessage(), StatusKind.ERROR);
            }
        });
    }

    private void loadProfiles() {
        var registry = DStudioUiPlugin.getInstance().agentProfileRegistry();
        if (registry == null) {
            return;
        }
        List<SelectOption> options = new ArrayList<>();
        String defaultId = registry.getDefaultProfileId().orElse(null);
        for (AgentProfile profile : registry.listProfiles()) {
            options.add(new SelectOption(profile.getId(), profile.getName(),
                    profile.getDescription(), profile.getId().equals(defaultId)));
        }
        if (profileId == null || options.stream().noneMatch(
                option -> option.id().equals(profileId))) {
            SelectOption fallback = options.stream().filter(SelectOption::recommended)
                    .findFirst().orElse(options.isEmpty() ? null : options.get(0));
            profileId = fallback == null ? null : fallback.id();
        }
        ui.updateSelectorOptions("profile", options, profileId);
    }

    @Override
    public void selectorChanged(String selectorId, String optionId) {
        switch (selectorId) {
            case "project" -> projectId = optionId;
            case "harness" -> {
                if (optionId != null) {
                    harnessId = optionId;
                    applyHarnessCapabilities();
                }
            }
            case "provider" -> {
                if (optionId != null && !optionId.equals(engineId)) {
                    engineId = optionId;
                    modelId = null;
                    loadModels();
                }
            }
            case "model" -> modelId = optionId;
            case "agent" -> agentId = optionId;
            case "profile" -> profileId = optionId;
            default -> { /* search visibility handled by the presentation */ }
        }
    }

    // ------------------------------------------------------- chat (inference service)

    @Override
    public void sendMessage(String text, List<String> attachmentPaths) {
        if (chatBusy || taskBusy) {
            return;
        }
        workExecutor.submit(() -> {
            String prompt = buildPrompt(text, attachmentPaths);
            Display.getDefault().asyncExec(() -> sendChat(prompt));
        });
    }

    private void sendChat(String prompt) {
        var service = DStudioUiPlugin.getInstance().inferenceService();
        if (service == null) {
            ui.addMessage(MessageKind.ERROR, "Error", "Inference service unavailable");
            return;
        }
        try {
            if (activeSession == null) {
                activeSession = service.createSession(engineId, modelId, "",
                        "Session " + TITLE_TIME.format(Instant.now()));
                ui.setStatus("Session: " + activeSession.title(), StatusKind.NONE);
                refreshSessions();
            }
            InferenceSession session = activeSession;
            chatBusy = true;
            ui.setBusy(true);
            ui.setStatus("Generating\u2026", StatusKind.BUSY);
            chatEntryId = ui.addStreamingMessage(MessageKind.ASSISTANT, "Assistant");
            chatExecutor.submit(() -> stream(session, prompt));
        } catch (InferenceException e) {
            chatFailed(e.getMessage());
        } catch (RuntimeException e) {
            chatFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void stream(InferenceSession session, String prompt) {
        var service = DStudioUiPlugin.getInstance().inferenceService();
        try {
            service.streamMessage(session.id(), prompt, chunk -> {
                if (chunk.text() != null && !chunk.text().isEmpty()) {
                    ui.appendToStreaming(chatEntryId, chunk.text());
                }
                if (chunk.done()) {
                    ui.finishStreaming(chatEntryId, chunk.text());
                }
            });
            Display.getDefault().asyncExec(() -> {
                chatBusy = false;
                ui.setBusy(taskBusy);
                ui.finishStreaming(chatEntryId, null);
                ui.setStatus("Done", StatusKind.SUCCESS);
                chatEntryId = null;
                refreshSessions();
            });
        } catch (InferenceException e) {
            Display.getDefault().asyncExec(() -> chatFailed(e.getMessage()));
        } catch (RuntimeException e) {
            Display.getDefault().asyncExec(() -> chatFailed(e.getClass().getSimpleName()
                    + ": " + e.getMessage()));
        }
    }

    private void chatFailed(String message) {
        chatBusy = false;
        ui.setBusy(taskBusy);
        if (chatEntryId != null) {
            ui.cancelStreaming(chatEntryId);
            chatEntryId = null;
        }
        ui.addMessage(MessageKind.ERROR, "Error", message);
        ui.setStatus(message, StatusKind.ERROR);
    }

    // ------------------------------------------------------- task (execution service)

    @Override
    public void runTask(String text, List<String> attachmentPaths) {
        if (chatBusy || taskBusy) {
            return;
        }
        var service = DStudioUiPlugin.getInstance().executionService();
        if (service == null || harnessId == null) {
            ui.addMessage(MessageKind.ERROR, "Error",
                    "No execution service or harness selected");
            return;
        }
        workExecutor.submit(() -> {
            String prompt = buildPrompt(text, attachmentPaths);
            Display.getDefault().asyncExec(() -> submitTask(service, prompt));
        });
    }

    private void submitTask(ExecutionService service, String prompt) {
        Project project = projectId == null ? null
                : DStudioUiPlugin.getInstance().projectService().listProjects().stream()
                .filter(p -> p.id().equals(projectId)).findFirst().orElse(null);
        String workingDirectory = project == null ? System.getProperty("user.home")
                : project.rootPath();
        String providerForTask = compatibleProvider();
        try {
            ExecutionTask task = service.submit(new ExecutionService.ExecutionRequest(
                    harnessId, projectId, null, null, prompt,
                    providerForTask == null ? null : modelId,
                    agentId, false, workingDirectory, Duration.ofMinutes(30),
                    providerForTask, profileId, Map.of(), List.of()));
            taskBusy = true;
            taskExecutionId = task.getId();
            taskLogChars = 0;
            ui.setBusy(true);
            taskEntryId = ui.addStreamingMessage(MessageKind.EXECUTION,
                    "Task " + task.getId() + " via " + harnessId);
            ui.setStatus("Task " + task.getId() + " queued on " + harnessId,
                    StatusKind.BUSY);
        } catch (IllegalArgumentException e) {
            ui.addMessage(MessageKind.ERROR, "Error",
                    "Task rejected: " + e.getMessage());
            ui.setStatus("Task rejected: " + e.getMessage(), StatusKind.ERROR);
        }
    }

    /**
     * The task passes the chat provider/model only when the harness declares
     * compatibility with the engine type; otherwise the harness configuration is
     * authoritative.
     */
    private String compatibleProvider() {
        var registry = DStudioUiPlugin.getInstance().harnessRegistry();
        var engines = DStudioUiPlugin.getInstance().inferenceEngineRegistry();
        if (registry == null || engines == null || harnessId == null || engineId == null) {
            return null;
        }
        List<HarnessCapabilityDescriptor> capabilities = registry.aggregateCapabilities(
                harnessId);
        if (capabilities.isEmpty() || capabilities.get(0).supportedProviderTypes()
                .isEmpty()) {
            return null;
        }
        var engine = engines.findEngine(engineId).orElse(null);
        if (engine == null) {
            return null;
        }
        return capabilities.get(0).supportedProviderTypes().contains(engine.getType())
                ? engineId : null;
    }

    private void onExecutionEvent(ExecutionEvent event) {
        if (taskExecutionId == null || !taskExecutionId.equals(event.executionId())) {
            return;
        }
        Display.getDefault().asyncExec(() -> renderExecutionEvent(event));
    }

    private void renderExecutionEvent(ExecutionEvent event) {
        switch (event.type()) {
            case EXECUTION_QUEUED, EXECUTION_STARTED ->
                    ui.addStatusEntry(event.message(), StatusKind.BUSY);
            case EXECUTION_LOG -> {
                if (taskEntryId != null && taskLogChars < MAX_TASK_LOG_CHARS) {
                    String line = event.message() + "\n";
                    taskLogChars += line.length();
                    ui.appendToStreaming(taskEntryId, line);
                }
            }
            case PROGRESS_UPDATED -> ui.setStatus("Task: " + event.message(),
                    StatusKind.BUSY);
            case TOOL_INVOKED -> ui.addStatusEntry("Tool: " + event.message(),
                    StatusKind.BUSY);
            case EXECUTION_COMPLETED -> finishTask(true, event);
            case EXECUTION_FAILED, EXECUTION_CANCELLED -> finishTask(false, event);
            default -> { /* ignore */ }
        }
    }

    private void finishTask(boolean success, ExecutionEvent event) {
        taskBusy = false;
        ui.setBusy(chatBusy);
        StringBuilder summary = new StringBuilder();
        if (taskEntryId != null) {
            if (event.message() != null && !event.message().isBlank()) {
                summary.append(event.message()).append("\n");
            }
            Object files = event.data() == null ? null : event.data().get("modifiedFiles");
            if (files instanceof List<?> list && !list.isEmpty()) {
                summary.append("Modified files:\n");
                for (Object file : list) {
                    summary.append("  - ").append(file).append("\n");
                }
            }
            if (summary.length() > 0) {
                ui.finishStreaming(taskEntryId, summary.toString());
            } else {
                ui.finishStreaming(taskEntryId, success ? "Completed"
                        : "Execution failed");
            }
            taskEntryId = null;
        }
        ui.setStatus(success ? "Task completed" : "Task failed/cancelled",
                success ? StatusKind.SUCCESS : StatusKind.ERROR);
        taskExecutionId = null;
    }

    // ------------------------------------------------------- sessions

    @Override
    public void createSession() {
        var service = DStudioUiPlugin.getInstance().inferenceService();
        if (service == null) {
            return;
        }
        try {
            InferenceSession session = service.createSession(engineId, modelId, "",
                    "Session " + TITLE_TIME.format(Instant.now()));
            openSession(session.id());
        } catch (RuntimeException e) {
            ui.setStatus("Cannot create session: " + e.getMessage(), StatusKind.ERROR);
        }
    }

    @Override
    public void openSession(String sessionId) {
        var service = DStudioUiPlugin.getInstance().inferenceService();
        if (service == null) {
            return;
        }
        service.getSession(sessionId).ifPresent(session -> {
            activeSession = session;
            ui.clearConversation();
            renderedMessageIds.clear();
            chatEntryId = null;
            for (var message : service.getMessages(sessionId)) {
                if (!renderedMessageIds.add(message.id())) {
                    continue;
                }
                if ("user".equals(message.role())) {
                    ui.addMessage(MessageKind.USER, "You", message.content());
                } else {
                    ui.addMessage(MessageKind.ASSISTANT, "Assistant", message.content());
                }
            }
            ui.setStatus("Session: " + session.title() + " ("
                    + session.status().name().toLowerCase() + ")", StatusKind.NONE);
            refreshSessions();
        });
    }

    @Override
    public void renameSession(String sessionId, String newTitle) {
        var service = DStudioUiPlugin.getInstance().inferenceService();
        if (service == null) {
            return;
        }
        try {
            InferenceSession renamed = service.renameSession(sessionId, newTitle);
            if (activeSession != null && activeSession.id().equals(sessionId)) {
                activeSession = renamed;
                ui.setStatus("Session: " + renamed.title(), StatusKind.NONE);
            }
        } catch (RuntimeException e) {
            ui.setStatus("Rename failed: " + e.getMessage(), StatusKind.ERROR);
        }
        refreshSessions();
    }

    @Override
    public void deleteSession(String sessionId) {
        var service = DStudioUiPlugin.getInstance().inferenceService();
        if (service == null) {
            return;
        }
        service.deleteSession(sessionId);
        if (activeSession != null && activeSession.id().equals(sessionId)) {
            activeSession = null;
            ui.clearConversation();
            renderedMessageIds.clear();
            ui.setStatus("Session deleted", StatusKind.NONE);
        }
        refreshSessions();
    }

    @Override
    public void refreshSessions() {
        var service = DStudioUiPlugin.getInstance().inferenceService();
        if (service == null) {
            return;
        }
        List<SessionInfo> infos = new ArrayList<>();
        for (InferenceSession session : service.listSessions()) {
            infos.add(SessionInfo.of(session.id(), session.title(),
                    session.status().name(), session.updatedAt()));
        }
        ui.setSessions(infos, activeSession == null ? null : activeSession.id());
    }

    // ------------------------------------------------------- search + stop

    @Override
    public void runSearch(String pattern) {
        Project project = projectId == null ? null
                : DStudioUiPlugin.getInstance().projectService().listProjects().stream()
                .filter(p -> p.id().equals(projectId)).findFirst().orElse(null);
        if (project == null) {
            ui.showSearchResults(pattern, List.of(), "No project selected");
            return;
        }
        Path root = Path.of(project.rootPath());
        workExecutor.submit(() -> {
            List<SearchResult> results = new ArrayList<>();
            String error = "";
            try {
                Pattern compiled = Pattern.compile(pattern);
                try (Stream<Path> stream = Files.walk(root)) {
                    for (Path file : stream.filter(Files::isRegularFile)
                            .filter(InferenceWorkspaceView::notSkipped).toList()) {
                        if (results.size() >= 100) {
                            break;
                        }
                        int lineNo = 0;
                        for (String line : Files.readString(file,
                                StandardCharsets.UTF_8).split("\n", -1)) {
                            lineNo++;
                            if (compiled.matcher(line).find()) {
                                results.add(new SearchResult(root.relativize(file)
                                        .toString(), lineNo, line.strip()));
                                if (results.size() >= 100) {
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (PatternSyntaxException e) {
                error = "Invalid regular expression: " + e.getMessage();
            } catch (IOException e) {
                error = "Search failed: " + e.getMessage();
            }
            final List<SearchResult> finalResults = results;
            final String finalError = error;
            ui.showSearchResults(pattern, finalResults, finalError);
        });
    }

    private static boolean notSkipped(Path path) {
        for (Path part : path) {
            String name = part.getFileName().toString();
            if (name.equals(".git") || name.equals("target") || name.equals("build")
                    || name.equals("out") || name.equals("bin")
                    || name.equals("node_modules")) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void stopGeneration() {
        if (chatBusy && activeSession != null) {
            var service = DStudioUiPlugin.getInstance().inferenceService();
            if (service != null) {
                service.cancel(activeSession.id());
                ui.setStatus("Cancelling chat\u2026", StatusKind.INFO);
            }
            return;
        }
        if (taskBusy && taskExecutionId != null) {
            var service = DStudioUiPlugin.getInstance().executionService();
            if (service != null) {
                service.cancel(taskExecutionId);
                ui.setStatus("Cancelling task\u2026", StatusKind.INFO);
            }
        }
    }

    // ------------------------------------------------------- helpers

    private static String buildPrompt(String text, List<String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return text;
        }
        StringBuilder prompt = new StringBuilder();
        for (String path : attachments) {
            Path file = Path.of(path);
            String fileName = file.getFileName().toString();
            boolean isImage = fileName.toLowerCase().endsWith(".png")
                    || fileName.toLowerCase().endsWith(".jpg")
                    || fileName.toLowerCase().endsWith(".jpeg")
                    || fileName.toLowerCase().endsWith(".gif")
                    || fileName.toLowerCase().endsWith(".webp");
            prompt.append("[Attached file: ").append(fileName).append("]\n");
            try {
                if (Files.size(file) > MAX_ATTACHMENT_BYTES) {
                    prompt.append("(file too large to attach inline)\n\n");
                    continue;
                }
                if (isImage) {
                    prompt.append("(image file attached - model may not support image input; ")
                            .append(fileName)
                            .append(" will be noted in prompt)\n\n");
                    prompt.append("![image: ").append(fileName).append("]\n\n");
                    continue;
                }
                prompt.append("```\n").append(Files.readString(file,
                        StandardCharsets.UTF_8)).append("\n```\n\n");
            } catch (IOException e) {
                prompt.append("(unreadable file: ").append(e.getMessage()).append(")\n\n");
            }
        }
        prompt.append(text);
        return prompt.toString();
    }

    @Override
    public void dispose() {
        disposed = true;
        if (executionSubscription != null) {
            try {
                executionSubscription.close();
            } catch (Exception ignored) {
                // noop
            }
            executionSubscription = null;
        }
        chatExecutor.shutdownNow();
        workExecutor.shutdownNow();
        if (composite != null) {
            composite.dispose();
            composite = null;
        }
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (composite != null) {
            composite.setFocus();
        }
    }
}
