package eu.kalafatic.dstudio.harness.opencode;

import eu.kalafatic.dstudio.core.harness.HarnessAdapter;
import eu.kalafatic.dstudio.core.harness.HarnessCapabilityDescriptor;
import eu.kalafatic.dstudio.core.harness.HarnessConnectionTestResult;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionRequest;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionResult;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionSink;
import eu.kalafatic.dstudio.core.model.ExecutionEventType;
import eu.kalafatic.dstudio.core.service.WorkspaceDiffService;
import eu.kalafatic.dstudio.infrastructure.config.DStudioPaths;
import eu.kalafatic.dstudio.infrastructure.process.ProcessExecutionSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OpenCodeHarnessAdapter implements HarnessAdapter {

    public static final String HARNESS_ID = "opencode";
    public static final String HARNESS_NAME = "OpenCode CLI";
    public static final String HARNESS_TYPE = "OPENCODE";
    public static final String CONFIG_BINARY = "binary";
    public static final String CONFIG_AGENT = "agent";
    public static final String CONFIG_MODEL = "model";
    public static final String CONFIG_AUTO_APPROVE = "autoApprove";
    public static final String CONFIG_TIMEOUT_SECONDS = "timeoutSeconds";
    public static final String CONFIG_STREAM_FORMAT = "streamFormat";
    public static final String STREAM_FORMAT_JSON = "json";
    public static final String STREAM_FORMAT_DEFAULT = "default";

    private final WorkspaceDiffService diffService;
    private final DStudioPaths paths;
    private final Map<String, String> config = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public OpenCodeHarnessAdapter(WorkspaceDiffService diffService, DStudioPaths paths) {
        this.diffService = diffService;
        this.paths = paths;
        this.config.put(CONFIG_BINARY, "opencode");
        this.config.put(CONFIG_AGENT, "build");
        this.config.put(CONFIG_MODEL, "");
        this.config.put(CONFIG_AUTO_APPROVE, "false");
        this.config.put(CONFIG_TIMEOUT_SECONDS, "900");
        this.config.put(CONFIG_STREAM_FORMAT, STREAM_FORMAT_JSON);
    }

    @Override
    public String getHarnessId() {
        return HARNESS_ID;
    }

    @Override
    public String getHarnessName() {
        return HARNESS_NAME;
    }

    @Override
    public String getHarnessType() {
        return HARNESS_TYPE;
    }

    @Override
    public List<String> getCapabilities() {
        return List.of("CODE_IMPLEMENTATION");
    }

    @Override
    public HarnessCapabilityDescriptor getCapabilityDescriptor() {
        List<HarnessCapabilityDescriptor.ModelDescriptor> models = new ArrayList<>();
        String model = config.getOrDefault(CONFIG_MODEL, "");
        if (!model.isBlank()) {
            models.add(new HarnessCapabilityDescriptor.ModelDescriptor(model, model,
                    "OpenCode", true, 0, true));
        }
        return new HarnessCapabilityDescriptor(HARNESS_ID, HARNESS_NAME, HARNESS_TYPE,
                List.of("CODE_IMPLEMENTATION"), List.of("DEFAULT"), models,
                List.of("build"), Map.of("asyncExecution", true, "eventStreaming", true,
                        "cancellation", true),
                List.of("OLLAMA", "OPENAI_COMPATIBLE"));
    }

    @Override
    public boolean isAvailable() {
        return testConnection().success();
    }

    @Override
    public HarnessConnectionTestResult testConnection() {
        String binary = config.getOrDefault(CONFIG_BINARY, "opencode");
        ProcessExecutionSupport.ProcessExecutionResult result =
                ProcessExecutionSupport.run(List.of(binary, "--version"), null,
                        Map.of(), Map.of(), null, null, () -> false, 10000);
        if (result.timedOut()) {
            return HarnessConnectionTestResult.error("OpenCode version probe timed out");
        }
        if (result.success()) {
            String version = result.stdoutLines().isEmpty() ? ""
                    : result.stdoutLines().get(0);
            return HarnessConnectionTestResult.ok("OpenCode CLI available: " + version);
        }
        return HarnessConnectionTestResult.error("OpenCode CLI not found (" + binary
                + "): " + result.stderrTail(2));
    }

    @Override
    public void updateConfiguration(Map<String, String> configuration) {
        if (configuration != null) {
            config.putAll(configuration);
        }
    }

    @Override
    public Map<String, String> getConnectionConfiguration() {
        return Map.copyOf(config);
    }

    @Override
    public HarnessExecutionResult execute(HarnessExecutionRequest request,
            HarnessExecutionSink sink) {
        Path workingDirectory = request.workingDirectory();
        if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
            return HarnessExecutionResult.error(-1,
                    "Working directory does not exist: " + workingDirectory);
        }
        String binary = config.getOrDefault(CONFIG_BINARY, "opencode");
        // The explicit per-task/per-session selection wins; configured values are only
        // fallback defaults. No silent override of the requested agent/model.
        String agent = request.agent() != null && !request.agent().isBlank()
                ? request.agent() : config.getOrDefault(CONFIG_AGENT, "");
        String requestedModel = request.model() != null && !request.model().isBlank()
                ? request.model() : config.getOrDefault(CONFIG_MODEL, "");
        String model = normalizeModel(requestedModel);
        boolean autoApprove = Boolean.parseBoolean(
                config.getOrDefault(CONFIG_AUTO_APPROVE, "false"));
        long timeoutSeconds;
        try {
            timeoutSeconds = Long.parseLong(
                    config.getOrDefault(CONFIG_TIMEOUT_SECONDS, "900"));
        } catch (NumberFormatException e) {
            timeoutSeconds = 900;
        }
        long timeoutMillis = request.timeout() != null
                ? Math.min(request.timeout().toMillis(), timeoutSeconds * 1000L)
                : timeoutSeconds * 1000L;
        String effectivePrompt = request.prompt() != null && !request.prompt().isBlank()
                ? request.prompt()
                : request.instruction() != null && !request.instruction().isBlank()
                        ? request.instruction()
                        : "Perform the assigned task.";
        effectivePrompt = applyContainmentConstraints(effectivePrompt, workingDirectory);
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("run");
        command.add(effectivePrompt);
        if (!agent.isBlank()) {
            command.add("--agent");
            command.add(agent);
        }
        if (!model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        command.add("--print-logs");
        command.add("--dir");
        command.add(workingDirectory.toString());
        String streamFormat = config.getOrDefault(CONFIG_STREAM_FORMAT, STREAM_FORMAT_DEFAULT);
        if (STREAM_FORMAT_JSON.equalsIgnoreCase(streamFormat)) {
            command.add("--format");
            command.add("json");
        }
        if (autoApprove) {
            command.add("--auto");
        }
        Object snapshot = null;
        if (diffService != null && diffService.isGitRepository(workingDirectory)) {
            snapshot = diffService.takeSnapshot(workingDirectory);
        }
        sink.emit(ExecutionEventType.EXECUTION_STARTED,
                "OpenCode execution started in " + workingDirectory);
        java.util.function.Consumer<String> stdoutConsumer = STREAM_FORMAT_JSON
                .equalsIgnoreCase(streamFormat)
                        ? line -> handleJsonEvent(line, sink)
                        : sink::log;
        ProcessExecutionSupport.ProcessExecutionResult result =
                ProcessExecutionSupport.run(command, workingDirectory,
                        hermeticEnvironment(request.taskId()), environmentRemovals(),
                        stdoutConsumer, sink::log, request.cancellationSupplier(),
                        timeoutMillis);
        if (result.cancelled()) {
            return HarnessExecutionResult.cancelled("OpenCode execution cancelled");
        }
        if (result.timedOut()) {
            return HarnessExecutionResult.timedOut("OpenCode execution timed out after "
                    + timeoutMillis + " ms");
        }
        if (!result.success()) {
            return HarnessExecutionResult.error(result.exitCode(),
                    "OpenCode exited with code " + result.exitCode() + ": "
                            + result.stderrTail(5));
        }
        List<String> modifiedFiles = new ArrayList<>();
        if (diffService != null && snapshot != null) {
            for (String file : diffService.collectChangedFiles(snapshot,
                    workingDirectory)) {
                Path resolved = workingDirectory.resolve(file).normalize();
                if (!resolved.startsWith(workingDirectory.normalize())) {
                    return HarnessExecutionResult.error(-1,
                            "OpenCode workspace containment violation: "
                                    + "change outside workspace: " + file);
                }
                modifiedFiles.add(file);
            }
        }
        Map<String, String> diffs = new LinkedHashMap<>();
        StringBuilder allDiffs = new StringBuilder();
        if (diffService != null && snapshot != null) {
            for (String file : modifiedFiles) {
                String fileDiff = diffService.diffText(snapshot, workingDirectory, file);
                diffs.put(file, fileDiff);
                allDiffs.append(fileDiff).append('\n');
            }
        }
        sink.emit(ExecutionEventType.EXECUTION_COMPLETED,
                "OpenCode execution completed; modified files: " + modifiedFiles.size());
        return HarnessExecutionResult.ok("OpenCode execution completed in "
                + workingDirectory.getFileName() + "; modified files: "
                + modifiedFiles.size(), modifiedFiles, allDiffs.toString());
    }

    private void handleJsonEvent(String line, HarnessExecutionSink sink) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = jsonMapper.readTree(line);
            String type = node.path("type").asText("");
            com.fasterxml.jackson.databind.JsonNode part = node.path("part");
            String partType = part.path("type").asText("");
            switch (type) {
                case "step_start" -> sink.emit(ExecutionEventType.PROGRESS_UPDATED,
                        "Agent step started");
                case "step_finish" -> {
                    com.fasterxml.jackson.databind.JsonNode tokens = part.path("tokens");
                    if (tokens.isObject()) {
                        sink.emit(ExecutionEventType.PROGRESS_UPDATED, "Agent step finished",
                                Map.of("inputTokens", tokens.path("input").asLong(0),
                                        "outputTokens", tokens.path("output").asLong(0),
                                        "reasoningTokens", tokens.path("reasoning").asLong(0)));
                    } else {
                        sink.emit(ExecutionEventType.PROGRESS_UPDATED, "Agent step finished");
                    }
                }
                case "text" -> sink.log(part.path("text").asText(""));
                case "tool" -> {
                    String tool = part.path("tool").asText("unknown");
                    String state = part.path("state").isObject()
                            ? part.path("state").path("status").asText("")
                            : "";
                    sink.emit(ExecutionEventType.TOOL_INVOKED, "Tool invoked: " + tool,
                            Map.of("tool", tool, "status", state));
                }
                case "error" -> sink.emit(ExecutionEventType.EXECUTION_LOG,
                        "OpenCode error: " + node.path("message").asText(""));
                default -> sink.log(line);
            }
        } catch (Exception e) {
            sink.log(line);
        }
    }

    private Map<String, String> hermeticEnvironment(String taskId) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CI", "1");
        env.put("NO_COLOR", "1");
        env.put("OPENCODE_DISABLE_TELEMETRY", "1");
        env.put("DSTUDIO_TASK_ID", taskId == null ? "" : taskId);
        Path runDir = paths.getHarnessRunsDirectory()
                .resolve(taskId == null ? "unknown" : taskId);
        Path dataDir = runDir.resolve("data");
        Path stateDir = runDir.resolve("state");
        Path cacheDir = runDir.resolve("cache");
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(stateDir);
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            System.err.println("EVO-Studio: failed to create harness run dirs: "
                    + e.getMessage());
        }
        env.put("XDG_DATA_HOME", dataDir.toString());
        env.put("XDG_STATE_HOME", stateDir.toString());
        env.put("XDG_CACHE_HOME", cacheDir.toString());
        return env;
    }

    private Map<String, String> environmentRemovals() {
        Map<String, String> removals = new LinkedHashMap<>();
        removals.put("OPENCODE", "");
        removals.put("OPENCODE_PID", "");
        return removals;
    }

    public static String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            return "";
        }
        String trimmed = model.trim();
        if (trimmed.contains("/")) {
            return trimmed;
        }
        return "ollama/" + trimmed;
    }

    public static String applyContainmentConstraints(String prompt, Path workingDirectory) {
        return eu.kalafatic.dstudio.harness.ContainmentConstraints.appendConstraints(prompt,
                workingDirectory);
    }
}
