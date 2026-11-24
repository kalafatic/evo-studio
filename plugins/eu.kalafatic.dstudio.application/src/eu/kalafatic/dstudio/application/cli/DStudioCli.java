package eu.kalafatic.dstudio.application.cli;

import eu.kalafatic.dstudio.application.lifecycle.DStudioLifecycleCoordinator;
import eu.kalafatic.dstudio.core.agent.AgentProfile;
import eu.kalafatic.dstudio.core.agent.AgentProfileRegistry;
import eu.kalafatic.dstudio.core.config.DStudioConfiguration;
import eu.kalafatic.dstudio.core.event.EventEntry;
import eu.kalafatic.dstudio.core.event.EventStream;
import eu.kalafatic.dstudio.core.harness.HarnessConnectionTestResult;
import eu.kalafatic.dstudio.core.harness.HarnessRegistry;
import eu.kalafatic.dstudio.core.inference.InferenceEngineAdapter;
import eu.kalafatic.dstudio.core.inference.InferenceEngineDescriptor;
import eu.kalafatic.dstudio.core.inference.InferenceEngineRegistry;
import eu.kalafatic.dstudio.core.inference.InferenceService;
import eu.kalafatic.dstudio.core.inference.InferenceSession;
import eu.kalafatic.dstudio.core.inference.InferenceSessionMessage;
import eu.kalafatic.dstudio.core.model.ExecutionTask;
import eu.kalafatic.dstudio.core.model.HarnessDescriptor;
import eu.kalafatic.dstudio.core.service.ExecutionService;
import eu.kalafatic.dstudio.core.service.ProjectService;
import eu.kalafatic.dstudio.teip.server.DStudioServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Headless CLI of the EVO-Studio server runtime. Reads single lines, dispatches them
 * against {@link DStudioCliCommandTable} (a leading '/' is optional) and prints the
 * results. Every command only reads capabilities that actually exist on the shared
 * core services exposed by the lifecycle coordinator; nothing is invented and no
 * secrets are ever printed.
 */
public final class DStudioCli {

    public static final String PROMPT = "evostudio> ";
    public static final String BANNER =
            "EVO-Studio headless CLI — type 'help' for commands, 'exit' to stop.";

    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_EDIT_DISTANCE = 2;
    private static final int LOG_TAIL = 50;

    private final DStudioLifecycleCoordinator coordinator;
    private final PrintWriter out;
    private final Map<String, DStudioCliCommandTable.Command> commands;

    public DStudioCli(DStudioLifecycleCoordinator coordinator, PrintWriter out) {
        this.coordinator = coordinator;
        this.out = out;
        Map<String, DStudioCliCommandTable.Command> index = new LinkedHashMap<>();
        for (DStudioCliCommandTable.Command command : DStudioCliCommandTable.COMMANDS) {
            index.put(command.name(), command);
        }
        this.commands = Map.copyOf(index);
    }

    /**
     * Dispatches one input line. Returns {@code false} when the session should end
     * ({@code stop} / {@code exit}); blank lines are ignored.
     */
    public boolean dispatch(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.startsWith("/")) {
            line = line.substring(1).trim();
        }
        if (line.isEmpty()) {
            return true;
        }
        String[] tokens = line.split("\\s+");
        String name = tokens[0].toLowerCase(Locale.ROOT);
        String[] args = java.util.Arrays.copyOfRange(tokens, 1, tokens.length);
        DStudioCliCommandTable.Command command = commands.get(name);
        if (command == null) {
            out.println("Unknown command '" + tokens[0] + "'."
                    + suggestions(name, commandNames()));
            out.println("Type 'help' for the list of commands.");
            return true;
        }
        run(command.name(), args);
        return !command.terminatesRepl();
    }

    // ------------------------------------------------------------- command bodies

    private void run(String name, String[] args) {
        switch (name) {
            case "help" -> help(args);
            case "status" -> status();
            case "web" -> web();
            case "terminal" -> terminal();
            case "models" -> models(args);
            case "sessions" -> sessions(args);
            case "chat" -> chat(args);
            case "providers" -> providers(args);
            case "agents" -> agents(args);
            case "config" -> config(args);
            case "logs" -> logs(args);
            case "start" -> start();
            case "restart" -> restart();
            case "stop" -> out.println("Stopping EVO-Studio…");
            case "exit" -> out.println("Exiting the EVO-Studio CLI.");
            default -> {
                // table lookup guarantees a known name
            }
        }
    }

    private void help(String[] args) {
        if (args.length == 0) {
            out.println("EVO-Studio headless CLI commands (a leading '/' is optional):");
            for (DStudioCliCommandTable.Command command : DStudioCliCommandTable.COMMANDS) {
                out.println("  " + command.syntax() + " — " + command.shortHelp());
            }
            return;
        }
        String topic = args[0].toLowerCase(Locale.ROOT);
        String detail = DStudioCliCommandTable.TOPIC_HELP.get(topic);
        if (detail == null) {
            out.println("Unknown topic '" + args[0] + "'."
                    + suggestions(topic,
                            new ArrayList<>(DStudioCliCommandTable.TOPIC_HELP.keySet())));
            out.println("Type 'help' for the list of commands.");
            return;
        }
        out.println(detail);
    }

    private void status() {
        if (coordinator == null) {
            out.println("Lifecycle stage: unavailable (no coordinator)");
            return;
        }
        out.println("Lifecycle stage: " + coordinator.getStage());
        DStudioServer server = coordinator.getServer();
        boolean running = server != null && server.isRunning();
        out.println("TEIP server: " + (running ? "running" : "not running")
                + " on " + bindAddress() + ":" + boundPort());
        out.println("Web interface: http://127.0.0.1:" + boundPort() + "/");
    }

    private void web() {
        out.println("Web interface: http://127.0.0.1:" + boundPort() + "/");
    }

    private void terminal() {
        String directory = workspaceDirectory();
        out.println("Workspace directory: " + directory);
        String[] command = terminalCommand(directory);
        if (command == null) {
            out.println("No supported terminal emulator found (tried x-terminal-emulator, "
                    + "gnome-terminal, konsole, xterm).");
            return;
        }
        try {
            new ProcessBuilder(command).start();
            out.println("Opened terminal in " + directory);
        } catch (IOException e) {
            out.println("Failed to open terminal: " + e.getMessage());
        }
    }

    private void models(String[] args) {
        if (args.length == 0) {
            usage("models");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        InferenceEngineRegistry registry = coordinator == null ? null
                : coordinator.getInferenceEngineRegistry();
        if (registry == null) {
            out.println("Inference engine registry unavailable.");
            return;
        }
        if ("list".equals(sub)) {
            List<InferenceEngineDescriptor> engines = registry.listEngines();
            if (engines.isEmpty()) {
                out.println("No inference engines registered.");
                return;
            }
            String defaultId = registry.getSelectedDefaultEngineId().orElse("");
            for (InferenceEngineDescriptor engine : engines) {
                out.println(engine.getId() + " [" + engine.getType() + "]"
                        + (engine.isEnabled() ? "" : " (disabled)")
                        + (engine.getId().equals(defaultId) ? " (default)" : "")
                        + " — " + engine.getStatus());
                Optional<InferenceEngineAdapter> adapter =
                        registry.findAdapter(engine.getId());
                if (adapter.isEmpty()) {
                    continue;
                }
                List<InferenceEngineAdapter.InferenceModel> discovered;
                try {
                    discovered = adapter.get().listModels();
                } catch (RuntimeException e) {
                    out.println("  (models unavailable: " + e.getMessage() + ")");
                    continue;
                }
                if (discovered.isEmpty()) {
                    out.println("  (no models discovered)");
                }
                for (InferenceEngineAdapter.InferenceModel model : discovered) {
                    out.println("  - " + model.id() + " (" + model.provider() + ", "
                            + (model.available() ? "available" : "unavailable")
                            + (model.sizeBytes() > 0
                                    ? ", " + model.sizeBytes() + " bytes" : "") + ")");
                }
            }
            return;
        }
        if ("show".equals(sub)) {
            if (args.length < 2) {
                usage("models");
                return;
            }
            String id = args[1];
            for (InferenceEngineDescriptor engine : registry.listEngines()) {
                Optional<InferenceEngineAdapter> adapter =
                        registry.findAdapter(engine.getId());
                if (adapter.isEmpty()) {
                    continue;
                }
                try {
                    for (InferenceEngineAdapter.InferenceModel model
                            : adapter.get().listModels()) {
                        if (id.equals(model.id())) {
                            out.println("Model: " + model.id());
                            out.println("  Engine: " + engine.getId()
                                    + " (" + model.engine() + ")");
                            out.println("  Provider: " + model.provider());
                            out.println("  Available: "
                                    + (model.available() ? "yes" : "no"));
                            if (model.sizeBytes() > 0) {
                                out.println("  Size: " + model.sizeBytes() + " bytes");
                            }
                            return;
                        }
                    }
                } catch (RuntimeException e) {
                    // engine unreachable; keep searching the remaining engines
                }
            }
            out.println("Model '" + id + "' not found.");
            return;
        }
        usage("models");
    }

    private void sessions(String[] args) {
        if (args.length == 0) {
            usage("sessions");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        ExecutionService executionService = coordinator == null ? null
                : coordinator.getExecutionService();
        if (executionService == null) {
            out.println("Execution service unavailable.");
            return;
        }
        switch (sub) {
            case "list" -> {
                List<ExecutionTask> tasks = executionService.listTasks();
                if (tasks.isEmpty()) {
                    out.println("No executions recorded.");
                    return;
                }
                for (ExecutionTask task : tasks) {
                    out.println(task.getId() + "  " + task.getStatus()
                            + "  harness=" + task.getHarnessId()
                            + "  project=" + task.getProjectId()
                            + "  created=" + task.getCreatedAt()
                            + (task.getInstruction() == null
                                    || task.getInstruction().isBlank() ? ""
                                            : "  \"" + truncate(task.getInstruction(), 60)
                                                    + "\""));
                }
            }
            case "show" -> {
                if (args.length < 2) {
                    usage("sessions");
                    return;
                }
                Optional<ExecutionTask> task = executionService.getTask(args[1]);
                if (task.isEmpty()) {
                    out.println("Execution '" + args[1] + "' not found.");
                    return;
                }
                ExecutionTask t = task.get();
                out.println("Id: " + t.getId());
                out.println("Status: " + t.getStatus());
                out.println("Harness: " + t.getHarnessId());
                out.println("Project: " + t.getProjectId());
                out.println("Model: " + t.getModel());
                out.println("Agent: " + t.getAgentProfileId());
                out.println("Working directory: " + t.getWorkingDirectory());
                out.println("Created: " + t.getCreatedAt());
                if (t.getErrorMessage() != null && !t.getErrorMessage().isBlank()) {
                    out.println("Error: " + t.getErrorMessage());
                }
                out.println("Instruction: " + t.getInstruction());
            }
            case "cancel" -> {
                if (args.length < 2) {
                    usage("sessions");
                    return;
                }
                executionService.cancel(args[1]);
                out.println("Cancellation requested for '" + args[1] + "' (idempotent; "
                        + "terminal executions are unchanged).");
            }
            default -> usage("sessions");
        }
    }

    /**
     * One-shot streaming chat through the shared inference service — the same
     * service and SQLite session store the web interface and RCP use. The
     * subcommands list/show expose the persisted inference sessions of every
     * client; nothing is mocked or frontend-only.
     */
    private void chat(String[] args) {
        if (args.length == 0) {
            usage("chat");
            return;
        }
        if ("list".equalsIgnoreCase(args[0])) {
            chatList();
            return;
        }
        if ("show".equalsIgnoreCase(args[0])) {
            chatShow(args.length > 1 ? args[1] : null);
            return;
        }
        InferenceService service = coordinator == null ? null
                : coordinator.getInferenceService();
        if (service == null) {
            out.println("Inference service unavailable.");
            return;
        }
        String engineId = null;
        String modelId = null;
        List<String> promptParts = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("engine".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                engineId = args[++i];
            } else if ("model".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                modelId = args[++i];
            } else {
                promptParts.add(args[i]);
            }
        }
        String prompt = String.join(" ", promptParts).trim();
        if (prompt.isEmpty()) {
            usage("chat");
            return;
        }
        if (engineId == null || engineId.isBlank()) {
            InferenceEngineRegistry registry = coordinator.getInferenceEngineRegistry();
            engineId = registry == null ? ""
                    : registry.getSelectedDefaultEngineId().orElse("");
        }
        if (engineId.isBlank()) {
            out.println("No inference engine selected. Use 'models list' and specify "
                    + "'engine <id>'.");
            return;
        }
        if (modelId == null || modelId.isBlank()) {
            DStudioConfiguration config = coordinator.getConfiguration();
            modelId = config == null ? "" : config.defaultModel();
        }
        if (modelId.isBlank()) {
            out.println("No model given. Specify 'model <id>' (or set the default model "
                    + "in the configuration).");
            return;
        }
        try {
            InferenceSession session = service.createSession(engineId, modelId, "",
                    "cli-chat");
            out.println("session " + session.id() + " — streaming…");
            service.streamMessage(session.id(), prompt, chunk -> {
                if (chunk.text() != null) {
                    out.print(chunk.text());
                    out.flush();
                }
            });
            out.println();
            out.println("[done] session " + session.id());
        } catch (Exception e) {
            out.println("Chat failed: " + e.getMessage());
        }
    }

    private void chatList() {
        InferenceService service = coordinator == null ? null
                : coordinator.getInferenceService();
        if (service == null) {
            out.println("Inference service unavailable.");
            return;
        }
        List<InferenceSession> sessions = service.listSessions();
        if (sessions.isEmpty()) {
            out.println("No inference sessions recorded.");
            return;
        }
        for (InferenceSession session : sessions) {
            out.println(session.id() + "  " + session.status()
                    + "  engine=" + session.engineId()
                    + "  model=" + session.model()
                    + "  created=" + session.createdAt()
                    + (session.title() == null || session.title().isBlank() ? ""
                            : "  \"" + truncate(session.title(), 48) + "\""));
        }
    }

    private void chatShow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            usage("chat");
            return;
        }
        InferenceService service = coordinator == null ? null
                : coordinator.getInferenceService();
        if (service == null) {
            out.println("Inference service unavailable.");
            return;
        }
        Optional<InferenceSession> session = service.getSession(sessionId);
        if (session.isEmpty()) {
            out.println("Inference session '" + sessionId + "' not found.");
            return;
        }
        InferenceSession s = session.get();
        out.println("Id: " + s.id());
        out.println("Status: " + s.status());
        out.println("Engine: " + s.engineId());
        out.println("Model: " + s.model());
        out.println("Created: " + s.createdAt());
        if (s.statusMessage() != null && !s.statusMessage().isBlank()) {
            out.println("Status message: " + s.statusMessage());
        }
        for (InferenceSessionMessage message : service.getMessages(sessionId)) {
            out.println("[" + message.role() + "] " + message.content());
        }
    }

    private void providers(String[] args) {
        if (args.length == 0) {
            usage("providers");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        HarnessRegistry harnessRegistry = coordinator == null ? null
                : coordinator.getHarnessRegistry();
        if (harnessRegistry == null) {
            out.println("Harness registry unavailable.");
            return;
        }
        if ("list".equals(sub)) {
            List<HarnessDescriptor> harnesses = harnessRegistry.listHarnesses();
            if (harnesses.isEmpty()) {
                out.println("No harness providers registered.");
                return;
            }
            String defaultId = harnessRegistry.getSelectedDefaultHarnessId().orElse("");
            for (HarnessDescriptor harness : harnesses) {
                out.println(harness.getId() + " [" + harness.getType() + "]"
                        + (harness.isEnabled() ? "" : " (disabled)")
                        + (harness.getId().equals(defaultId) ? " (default)" : "")
                        + " — " + harness.getStatus());
            }
            return;
        }
        if ("test".equals(sub)) {
            if (args.length < 2) {
                usage("providers");
                return;
            }
            if (harnessRegistry.findHarness(args[1]).isEmpty()) {
                out.println("Provider '" + args[1] + "' not found.");
                return;
            }
            try {
                HarnessConnectionTestResult result = harnessRegistry.testConnection(args[1]);
                out.println(result.success()
                        ? "OK: " + result.message()
                        : "FAILED: " + result.message());
            } catch (RuntimeException e) {
                out.println("FAILED: " + e.getMessage());
            }
            return;
        }
        usage("providers");
    }

    private void agents(String[] args) {
        if (args.length == 0 || !"list".equals(args[0].toLowerCase(Locale.ROOT))) {
            usage("agents");
            return;
        }
        AgentProfileRegistry registry = coordinator == null ? null
                : coordinator.getAgentProfileRegistry();
        if (registry == null) {
            out.println("Agent profile registry unavailable.");
            return;
        }
        List<AgentProfile> profiles = registry.listProfiles();
        if (profiles.isEmpty()) {
            out.println("No agent profiles defined.");
            return;
        }
        String defaultId = registry.getDefaultProfileId().orElse("");
        for (AgentProfile profile : profiles) {
            out.println(profile.getId() + " (" + profile.getName() + ")"
                    + (profile.getId().equals(defaultId) ? " (default)" : "")
                    + (profile.getRole().isBlank() ? "" : "  role=" + profile.getRole())
                    + (profile.getPreferredHarnessId().isBlank() ? ""
                            : "  harness=" + profile.getPreferredHarnessId())
                    + (profile.getPreferredProviderId().isBlank() ? ""
                            : "  provider=" + profile.getPreferredProviderId())
                    + (profile.getPreferredModel().isBlank() ? ""
                            : "  model=" + profile.getPreferredModel()));
        }
    }

    private void config(String[] args) {
        if (args.length == 0 || !"show".equals(args[0].toLowerCase(Locale.ROOT))) {
            usage("config");
            return;
        }
        DStudioConfiguration configuration = coordinator == null ? null
                : coordinator.getConfiguration();
        if (configuration == null) {
            out.println("Configuration unavailable.");
            return;
        }
        out.println("Application: " + configuration.appName() + " "
                + configuration.appVersion());
        out.println("Configuration directory: " + configuration.configurationDirectory());
        out.println("TEIP bind: " + configuration.teipBindAddress() + ":"
                + configuration.teipPort());
        out.println("Network mode: " + configuration.teipNetworkMode());
        out.println("Diagnostics port: " + configuration.diagnosticsPort());
        out.println("Dev mode: " + (configuration.teipDevMode() ? "on" : "off"));
        out.println("Default harness: " + noneFor(configuration.defaultHarnessId()));
        out.println("Default model: " + noneFor(configuration.defaultModel()));
        out.println("Execution timeout: " + configuration.executionTimeoutSeconds() + "s");
        out.println("EVO MCP endpoint: " + noneFor(configuration.evoMcpEndpoint()));
        // evoMcpToken and all other secrets are deliberately never printed.
    }

    private void logs(String[] args) {
        if (args.length == 0 || !"show".equals(args[0].toLowerCase(Locale.ROOT))) {
            usage("logs");
            return;
        }
        EventStream eventStream = coordinator == null ? null
                : coordinator.getEventStream();
        if (eventStream == null) {
            out.println("Event stream unavailable.");
            return;
        }
        long last = eventStream.lastSequence();
        List<EventEntry> entries = eventStream.after(Math.max(0, last - LOG_TAIL),
                LOG_TAIL);
        if (entries.isEmpty()) {
            out.println("No events recorded yet.");
            return;
        }
        for (EventEntry entry : entries) {
            out.println(entry.sequence() + " " + entry.timestamp() + " " + entry.type()
                    + ": " + truncate(String.valueOf(entry.payload()), 160));
        }
    }

    private void start() {
        if (coordinator == null) {
            out.println("Lifecycle coordinator unavailable.");
            return;
        }
        DStudioServer server = coordinator.getServer();
        if (server != null && server.isRunning()) {
            out.println("EVO-Studio server already running on " + bindAddress() + ":"
                    + boundPort() + ".");
            return;
        }
        try {
            coordinator.restartServerOnPort(boundPort());
            out.println("EVO-Studio server started on " + bindAddress() + ":"
                    + boundPort() + ".");
        } catch (RuntimeException e) {
            out.println("Failed to start EVO-Studio server: " + e.getMessage());
        }
    }

    private void restart() {
        if (coordinator == null) {
            out.println("Lifecycle coordinator unavailable.");
            return;
        }
        try {
            coordinator.restartServerOnPort(boundPort());
            out.println("EVO-Studio server restarted on " + bindAddress() + ":"
                    + boundPort() + ".");
        } catch (RuntimeException e) {
            out.println("Failed to restart EVO-Studio server: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private void usage(String name) {
        DStudioCliCommandTable.Command command = commands.get(name);
        out.println("Usage: " + (command == null ? name : command.syntax())
                + " — type 'help " + name + "' for details.");
    }

    private String bindAddress() {
        if (coordinator != null && coordinator.getConfiguration() != null) {
            String address = coordinator.getConfiguration().teipBindAddress();
            if (address != null && !address.isBlank()) {
                return address;
            }
        }
        return "127.0.0.1";
    }

    private int boundPort() {
        if (coordinator != null) {
            DStudioServer server = coordinator.getServer();
            if (server != null && server.isRunning() && server.getBoundPort() > 0) {
                return server.getBoundPort();
            }
            if (coordinator.getConfiguration() != null
                    && coordinator.getConfiguration().teipPort() > 0) {
                return coordinator.getConfiguration().teipPort();
            }
        }
        return DStudioConfiguration.DEFAULT_TEIP_PORT;
    }

    private String workspaceDirectory() {
        if (coordinator != null) {
            ProjectService projectService = coordinator.getProjectService();
            if (projectService != null) {
                List<eu.kalafatic.dstudio.core.model.Project> projects =
                        projectService.listProjects();
                if (!projects.isEmpty()) {
                    String root = projects.get(0).rootPath();
                    if (root != null && !root.isBlank()
                            && Files.isDirectory(Path.of(root))) {
                        return root;
                    }
                }
            }
        }
        return System.getProperty("user.home", ".");
    }

    /** Terminal probing mirrors the family convention (headless-safe, no SWT). */
    private static String[] terminalCommand(String directory) {
        if (hasCommand("x-terminal-emulator")) {
            return new String[] {"x-terminal-emulator",
                "--working-directory=" + directory};
        }
        if (hasCommand("gnome-terminal")) {
            return new String[] {"gnome-terminal", "--working-directory=" + directory};
        }
        if (hasCommand("konsole")) {
            return new String[] {"konsole", "--workdir", directory};
        }
        if (hasCommand("xterm")) {
            return new String[] {"xterm", "-e",
                "cd " + shellQuote(directory) + " && $SHELL"};
        }
        return null;
    }

    private static boolean hasCommand(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(":")) {
            if (Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private List<String> commandNames() {
        List<String> names = new ArrayList<>();
        for (DStudioCliCommandTable.Command command : DStudioCliCommandTable.COMMANDS) {
            names.add(command.name());
        }
        return names;
    }

    /**
     * Nearest-match suggestions: case-insensitive prefixes first, then names within a
     * small edit distance. Empty when nothing is close.
     */
    private static String suggestions(String input, List<String> candidates) {
        List<String> close = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.startsWith(input)) {
                close.add(candidate);
            }
        }
        for (String candidate : candidates) {
            if (editDistance(input, candidate) <= MAX_EDIT_DISTANCE
                    && !close.contains(candidate)) {
                close.add(candidate);
            }
        }
        if (close.isEmpty()) {
            return "";
        }
        return " Did you mean: "
                + String.join(", ", close.subList(0, Math.min(MAX_SUGGESTIONS, close.size())))
                + "?";
    }

    /** Classic Levenshtein distance with O(min) memory. */
    static int editDistance(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value
                : value.substring(0, maxLength) + "…";
    }

    private static String noneFor(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }
}
