package eu.kalafatic.dstudio.harness.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dstudio.core.harness.HarnessAdapter;
import eu.kalafatic.dstudio.core.harness.HarnessCapabilityDescriptor;
import eu.kalafatic.dstudio.core.harness.HarnessConnectionTestResult;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionRequest;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionResult;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionSink;
import eu.kalafatic.dstudio.core.model.ExecutionEventType;
import eu.kalafatic.dstudio.core.service.WorkspaceDiffService;
import eu.kalafatic.dstudio.harness.ContainmentConstraints;
import eu.kalafatic.dstudio.infrastructure.process.ProcessExecutionSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude Code coding-harness adapter for EVO-Studio (Phase 7, S2). Delegates executions to
 * the Anthropic Claude Code CLI through its documented headless interface:
 * {@code claude --print --output-format stream-json --verbose [--model X] -p "<prompt>"}.
 *
 * <p>Claude Code is Anthropic-native: its supported provider type is {@code ANTHROPIC}
 * (authentication via the standard {@code ANTHROPIC_API_KEY} environment). EVO-Studio owns
 * lifecycle, configuration, streaming, cancellation and changed-file collection; the CLI
 * owns the agent loop.</p>
 *
 * <p><b>Verification status:</b> the adapter contract (config keys, capability
 * declaration, result handling) is unit-tested, but the execution recipe has NOT been
 * live-verified on this host (no {@code claude} binary installed). Live tests skip
 * silently when the CLI is absent; before relying on the execution path, run the live
 * tests on a host with Claude Code installed.</p>
 */
public class ClaudeCodeHarnessAdapter implements HarnessAdapter {

    public static final String HARNESS_ID = "claude";
    public static final String HARNESS_NAME = "Claude Code";
    public static final String HARNESS_TYPE = "CLAUDE";

    public static final String CONFIG_BINARY = "binary";
    public static final String CONFIG_MODEL = "model";
    public static final String CONFIG_TIMEOUT_SECONDS = "timeoutSeconds";

    private final WorkspaceDiffService diffService;
    private final Map<String, String> config = new ConcurrentHashMap<>();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public ClaudeCodeHarnessAdapter(WorkspaceDiffService diffService) {
        this.diffService = diffService;
        this.config.put(CONFIG_BINARY, "claude");
        this.config.put(CONFIG_MODEL, "");
        this.config.put(CONFIG_TIMEOUT_SECONDS, "900");
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
        return List.of("CODE_IMPLEMENTATION", "CODE_ANALYSIS");
    }

    @Override
    public HarnessCapabilityDescriptor getCapabilityDescriptor() {
        List<HarnessCapabilityDescriptor.ModelDescriptor> models = new ArrayList<>();
        String model = config.getOrDefault(CONFIG_MODEL, "");
        if (!model.isBlank()) {
            models.add(new HarnessCapabilityDescriptor.ModelDescriptor(model, model,
                    "Anthropic", true, 0, true));
        }
        return new HarnessCapabilityDescriptor(HARNESS_ID, HARNESS_NAME, HARNESS_TYPE,
                List.of("CODE_IMPLEMENTATION", "CODE_ANALYSIS"), List.of("DEFAULT"),
                models, List.of(), Map.of("asyncExecution", true, "eventStreaming", true,
                        "cancellation", true), List.of("ANTHROPIC"));
    }

    @Override
    public boolean isAvailable() {
        return testConnection().success();
    }

    @Override
    public HarnessConnectionTestResult testConnection() {
        String binary = config.getOrDefault(CONFIG_BINARY, "claude");
        ProcessExecutionSupport.ProcessExecutionResult result =
                ProcessExecutionSupport.run(List.of(binary, "--version"), null,
                        Map.of(), Map.of(), null, null, () -> false, 10000);
        if (result.timedOut()) {
            return HarnessConnectionTestResult.error("Claude Code version probe timed out");
        }
        if (result.success()) {
            String version = result.stdoutLines().isEmpty() ? ""
                    : result.stdoutLines().get(0);
            return HarnessConnectionTestResult.ok("Claude Code CLI available: " + version);
        }
        return HarnessConnectionTestResult.error("Claude Code CLI not found (" + binary
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
        if (workingDirectory == null || !java.nio.file.Files.isDirectory(workingDirectory)) {
            return HarnessExecutionResult.error(-1,
                    "Working directory does not exist: " + workingDirectory);
        }
        String binary = config.getOrDefault(CONFIG_BINARY, "claude");
        // The explicit per-task/per-session model wins; the configured model is only a
        // fallback default. There is no silent provider switching: Claude Code is
        // Anthropic-native.
        String model = request.model() != null && !request.model().isBlank()
                ? request.model() : config.getOrDefault(CONFIG_MODEL, "");
        long timeoutMillis = request.timeout() != null
                ? Math.min(request.timeout().toMillis(), timeoutSeconds() * 1000L)
                : timeoutSeconds() * 1000L;

        String effectivePrompt = request.prompt() != null && !request.prompt().isBlank()
                ? request.prompt()
                : request.instruction() != null && !request.instruction().isBlank()
                        ? request.instruction()
                        : "Perform the assigned task.";
        effectivePrompt = ContainmentConstraints.appendConstraints(effectivePrompt,
                workingDirectory);

        Object snapshot = null;
        if (diffService != null && diffService.isGitRepository(workingDirectory)) {
            snapshot = diffService.takeSnapshot(workingDirectory);
        }
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--print");
        command.add("--output-format");
        command.add("stream-json");
        command.add("--verbose");
        if (!model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        command.add("-p");
        command.add(effectivePrompt);

        sink.emit(ExecutionEventType.EXECUTION_STARTED,
                "Claude Code starting in " + workingDirectory
                        + (model.isBlank() ? "" : " (model: " + model + ")"));

        ProcessExecutionSupport.ProcessExecutionResult result =
                ProcessExecutionSupport.run(command, workingDirectory,
                        Map.of(), Map.of(), line -> emitJsonLine(line, sink),
                        sink::log, request.cancellationSupplier(), timeoutMillis);
        if (result.cancelled()) {
            return HarnessExecutionResult.cancelled("Claude Code execution cancelled");
        }
        if (result.timedOut()) {
            return HarnessExecutionResult.timedOut("Claude Code timed out after "
                    + timeoutMillis + " ms");
        }
        if (!result.success()) {
            return HarnessExecutionResult.error(result.exitCode(),
                    "Claude Code exited with code " + result.exitCode() + ": "
                            + result.stderrTail(5));
        }
        List<String> modifiedFiles = new ArrayList<>();
        if (diffService != null && snapshot != null) {
            for (String file : diffService.collectChangedFiles(snapshot,
                    workingDirectory)) {
                Path resolved = workingDirectory.resolve(file).normalize();
                if (!resolved.startsWith(workingDirectory.normalize())) {
                    return HarnessExecutionResult.error(-1,
                            "Claude Code workspace containment violation: "
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
                "Claude Code execution completed; modified files: " + modifiedFiles.size());
        return HarnessExecutionResult.ok("Claude Code execution completed in "
                + workingDirectory.getFileName() + "; modified files: "
                + modifiedFiles.size(), modifiedFiles, allDiffs.toString());
    }

    /** Defensive parse of one stream-json line (never throws; unverified against a live CLI). */
    private void emitJsonLine(String line, HarnessExecutionSink sink) {
        JsonNode node;
        try {
            node = jsonMapper.readTree(line);
        } catch (Exception e) {
            return;
        }
        String type = node.path("type").asText("");
        if ("assistant".equals(type)) {
            for (JsonNode block : node.path("message").path("content")) {
                String text = block.path("text").asText("");
                if (!text.isBlank()) {
                    sink.emit(ExecutionEventType.EXECUTION_LOG, text);
                }
            }
        } else if ("system".equals(type)) {
            String subtype = node.path("subtype").asText("");
            if ("error_during_execution".equals(subtype)) {
                sink.emit(ExecutionEventType.EXECUTION_LOG,
                        "[error] " + node.path("error").asText(""));
            }
        }
    }

    private long timeoutSeconds() {
        try {
            return Long.parseLong(config.getOrDefault(CONFIG_TIMEOUT_SECONDS, "900"));
        } catch (NumberFormatException e) {
            return 900;
        }
    }
}
