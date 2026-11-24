package eu.kalafatic.dstudio.teip.server;

import eu.kalafatic.dstudio.core.agent.AgentProfile;
import eu.kalafatic.dstudio.core.agent.AgentProfileRegistry;
import eu.kalafatic.dstudio.core.config.DStudioConfiguration;
import eu.kalafatic.dstudio.core.harness.HarnessCapabilityDescriptor;
import eu.kalafatic.dstudio.core.harness.HarnessRegistry;
import eu.kalafatic.dstudio.core.model.ChangeReview;
import eu.kalafatic.dstudio.core.model.ExecutionEvent;
import eu.kalafatic.dstudio.core.model.ExecutionResult;
import eu.kalafatic.dstudio.core.model.ExecutionStatus;
import eu.kalafatic.dstudio.core.model.ExecutionTask;
import eu.kalafatic.dstudio.core.service.ExecutionService;
import eu.kalafatic.dstudio.core.service.ReviewService;
import eu.kalafatic.dstudio.teip.client.EvoMcpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TeipExecutionManager {

    public static final String PARAM_HARNESS = "harness";
    public static final String PARAM_WORKING_DIRECTORY = "workingDirectory";
    public static final String PARAM_MODEL = "model";
    public static final String PARAM_TIMEOUT_SECONDS = "timeoutSeconds";
    public static final String PARAM_PROVIDER = "provider";
    public static final String PARAM_AGENT = "agent";
    public static final String PARAM_AUTO_APPROVE = "autoApprove";
    public static final String PARAM_TOOL_PERMISSIONS = "toolPermissions";

    private final HarnessRegistry harnessRegistry;
    private final ExecutionService executionService;
    private final ReviewService reviewService;
    private final DStudioConfiguration configuration;
    private final AgentProfileRegistry agentProfileRegistry;
    private final Instant startedAt = Instant.now();

    public TeipExecutionManager(HarnessRegistry harnessRegistry,
            ExecutionService executionService, ReviewService reviewService,
            DStudioConfiguration configuration) {
        this(harnessRegistry, executionService, reviewService, configuration, null);
    }

    public TeipExecutionManager(HarnessRegistry harnessRegistry,
            ExecutionService executionService, ReviewService reviewService,
            DStudioConfiguration configuration, AgentProfileRegistry agentProfileRegistry) {
        this.harnessRegistry = harnessRegistry;
        this.executionService = executionService;
        this.reviewService = reviewService;
        this.configuration = configuration;
        this.agentProfileRegistry = agentProfileRegistry;
    }

    public TeipWire.HealthResponse health() {
        boolean ready = harnessRegistry.listHarnesses().stream().anyMatch(h -> h.isEnabled());
        List<ExecutionTask> active = executionService.listActiveTasks();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("serverPort", configuration.teipPort());
        details.put("activeExecutions", active.size());
        details.put("harnesses", harnessRegistry.listHarnesses().size());
        return new TeipWire.HealthResponse(TeipWire.PROTOCOL, TeipWire.VERSION, "UP",
                "DSTUDIO", DStudioConfiguration.APP_VERSION, "2.6.11", "dstudio-01",
                Duration.between(startedAt, Instant.now()).getSeconds(), ready,
                Instant.now().toString(), details);
    }

    public TeipWire.CapabilitiesResponse capabilities() {
        List<TeipWire.CapabilityDescriptor> capabilities = new ArrayList<>();
        List<TeipWire.ModelDescriptor> models = new ArrayList<>();
        List<String> supportedModes = new ArrayList<>();
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("asyncExecution", true);
        features.put("eventStreaming", true);
        features.put("cancellation", true);
        for (HarnessCapabilityDescriptor harness : harnessRegistry.aggregateCapabilities()) {
            for (String capability : harness.capabilities()) {
                capabilities.add(new TeipWire.CapabilityDescriptor(capability,
                        harness.name() + " - " + capability,
                        harness.supportedModes() == null ? List.of()
                                : harness.supportedModes(),
                        List.of("read:workspace", "write:workspace", "execute:build")));
            }
            if (harness.supportedModes() != null) {
                for (String mode : harness.supportedModes()) {
                    if (!supportedModes.contains(mode)) {
                        supportedModes.add(mode);
                    }
                }
            }
            if (harness.models() != null) {
                for (HarnessCapabilityDescriptor.ModelDescriptor model : harness.models()) {
                    models.add(new TeipWire.ModelDescriptor(model.id(), model.name(),
                            model.provider(), model.available(), model.contextWindowTokens(),
                            model.recommended()));
                }
            }
        }
        List<TeipWire.AgentDescriptor> agents = new ArrayList<>();
        if (agentProfileRegistry != null) {
            for (AgentProfile profile : agentProfileRegistry.listProfiles()) {
                agents.add(new TeipWire.AgentDescriptor(profile.getId(),
                        profile.getName(), profile.getPreferredHarnessId(),
                        profile.getPreferredProviderId(), profile.getPreferredModel(),
                        profile.getToolPermissions()));
            }
        }
        for (HarnessCapabilityDescriptor harness : harnessRegistry.aggregateCapabilities()) {
            if (harness.agents() != null) {
                for (String agent : harness.agents()) {
                    agents.add(new TeipWire.AgentDescriptor(agent,
                            harness.name() + " agent", "", "", "", List.of()));
                }
            }
        }
        return new TeipWire.CapabilitiesResponse(TeipWire.PROTOCOL, TeipWire.VERSION,
                Instant.now().toString(), capabilities, supportedModes, agents, models,
                List.of(), features);
    }

    public CreateOutcome createExecution(TeipWire.ExecutionRequest request) {
        if (request.instruction() == null || request.instruction().isBlank()) {
            throw new TeipException(TeipWire.ErrorCode.VALIDATION_FAILED,
                    "Missing required field: instruction", Map.of("field", "instruction"));
        }
        checkVersion(request.protocol(), request.version());
        String harnessId = request.parameters() == null ? null
                : asString(request.parameters().get(PARAM_HARNESS));
        if (harnessId == null || harnessId.isBlank()) {
            Optional<String> selected = harnessRegistry.getSelectedDefaultHarnessId();
            harnessId = selected.orElse(null);
        }
        if (harnessId == null || harnessId.isBlank()) {
            throw new TeipException(TeipWire.ErrorCode.VALIDATION_FAILED,
                    "No harness selected and no parameters.harness supplied",
                    Map.of("field", PARAM_HARNESS));
        }
        if (harnessRegistry.findHarness(harnessId).isEmpty()) {
            throw new TeipException(TeipWire.ErrorCode.CAPABILITY_UNSUPPORTED,
                    "Unknown harness: " + harnessId, Map.of("harness", harnessId));
        }
        if (!harnessRegistry.findHarness(harnessId).get().isEnabled()) {
            throw new TeipException(TeipWire.ErrorCode.CAPABILITY_UNSUPPORTED,
                    "Harness is disabled: " + harnessId, Map.of("harness", harnessId));
        }
        if (request.capability() != null && !request.capability().isBlank()
                && !"CODE_IMPLEMENTATION".equals(request.capability())
                && !"CODE_ANALYSIS".equals(request.capability())) {
            throw new TeipException(TeipWire.ErrorCode.CAPABILITY_UNSUPPORTED,
                    "Capability not supported: " + request.capability(),
                    Map.of("capability", request.capability()));
        }
        String workingDirectory = request.parameters() == null ? null
                : asString(request.parameters().get(PARAM_WORKING_DIRECTORY));
        if (workingDirectory == null || workingDirectory.isBlank()) {
            throw new TeipException(TeipWire.ErrorCode.VALIDATION_FAILED,
                    "Missing required field: parameters.workingDirectory",
                    Map.of("field", PARAM_WORKING_DIRECTORY));
        }
        String model = request.model() != null && !request.model().isBlank()
                ? request.model()
                : request.parameters() == null ? null
                        : asString(request.parameters().get(PARAM_MODEL));
        if (model == null || model.isBlank()) {
            model = configuration.defaultModel();
        }
        String providerId = request.parameters() == null ? null
                : asString(request.parameters().get(PARAM_PROVIDER));
        String agentProfileId = request.parameters() == null ? null
                : asString(request.parameters().get(PARAM_AGENT));
        boolean autoApprove = parseBoolean(
                request.parameters() == null ? null
                        : request.parameters().get(PARAM_AUTO_APPROVE));
        List<String> toolPermissions = parseStringList(
                request.parameters() == null ? null
                        : request.parameters().get(PARAM_TOOL_PERMISSIONS));
        if (agentProfileId != null && !agentProfileId.isBlank()) {
            if (agentProfileRegistry == null
                    || agentProfileRegistry.findProfile(agentProfileId).isEmpty()) {
                throw new TeipException(TeipWire.ErrorCode.CAPABILITY_UNSUPPORTED,
                        "Unknown agent profile: " + agentProfileId,
                        Map.of("agent", agentProfileId));
            }
            AgentProfileRegistry.CompatibilityResult compatibility =
                    agentProfileRegistry.checkCompatibility(agentProfileId, harnessId,
                            providerId, model);
            if (!compatibility.compatible()) {
                throw new TeipException(TeipWire.ErrorCode.CAPABILITY_UNSUPPORTED,
                        "Agent profile is incompatible with the requested execution "
                                + "environment: " + compatibility.message(),
                        Map.of("agent", agentProfileId));
            }
        }
        Duration timeout = Duration.ofSeconds(configuration.executionTimeoutSeconds());
        if (request.parameters() != null) {
            String timeoutSeconds = asString(request.parameters().get(PARAM_TIMEOUT_SECONDS));
            if (timeoutSeconds != null && !timeoutSeconds.isBlank()) {
                try {
                    timeout = Duration.ofSeconds(Math.max(1,
                            Math.min(Long.parseLong(timeoutSeconds),
                                    configuration.executionTimeoutSeconds())));
                } catch (NumberFormatException ignored) {
                    timeout = Duration.ofSeconds(configuration.executionTimeoutSeconds());
                }
            }
        }
        String idempotencyKey = request.idempotencyKey() == null
                ? "" : request.idempotencyKey();
        Optional<ExecutionTask> existing = idempotencyKey.isBlank() ? Optional.empty()
                : executionService.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new CreateOutcome(existing.get(), true);
        }
        ExecutionService.ExecutionRequest executionRequest =
                new ExecutionService.ExecutionRequest(harnessId,
                        request.caller() == null ? null : request.caller().projectId(),
                        idempotencyKey.isBlank() ? null : idempotencyKey,
                        augmentInstruction(request), request.instruction(), model, null,
                        autoApprove, workingDirectory, timeout,
                        providerId == null ? "" : providerId,
                        agentProfileId == null ? "" : agentProfileId,
                        Map.of(), toolPermissions);
        ExecutionTask task = executionService.submit(executionRequest);
        return new CreateOutcome(task, false);
    }

    private static boolean parseBoolean(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static List<String> parseStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            list.forEach(item -> result.add(String.valueOf(item)));
            return result;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return List.of();
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1, Math.max(1, trimmed.length() - 1));
        }
        List<String> result = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    /**
     * Optional platform-context pre-step: when EVO Cognitive Services are
     * configured, EVO-Studio retrieves a bounded context package from EVO and
     * prepends it to the harness instruction, then correlates a cognitive
     * session with this execution. When EVO is unavailable or unconfigured
     * the instruction is passed through unchanged (graceful degradation).
     */
    private String augmentInstruction(TeipWire.ExecutionRequest request) {
        String instruction = request.instruction();
        if (instruction == null || instruction.isBlank()) {
            return instruction;
        }
        String endpoint = configuration.evoMcpEndpoint();
        String token = configuration.evoMcpToken();
        if (endpoint == null || endpoint.isBlank() || token == null || token.isBlank()) {
            return instruction;
        }
        try {
            String project = request.caller() == null ? null : request.caller().projectId();
            EvoMcpClient client = new EvoMcpClient(Duration.ofSeconds(15));
            EvoMcpClient.ContextResult context = client.contextGet(endpoint, token, project,
                    null, instruction, 3500,
                    List.of("project", "memory", "continuity", "decisions"));
            if (context == null || context.packageJson() == null) {
                return instruction;
            }
            String correlationId = "exec-" + java.util.UUID.randomUUID();
            String cognitiveSessionId = client.sessionCreate(endpoint, token, "TASK", project,
                    correlationId);
            StringBuilder augmented = new StringBuilder();
            augmented.append("[EVO PLATFORM CONTEXT]\n");
            augmented.append("cognitiveSessionId: ")
                    .append(cognitiveSessionId != null ? cognitiveSessionId : "unavailable").append('\n');
            augmented.append(context.packageJson().toString()).append('\n');
            augmented.append("[TASK]\n").append(instruction);
            return augmented.toString();
        } catch (Exception e) {
            // Degradation: never block the execution pipeline on EVO failures.
            return instruction;
        }
    }

    private void checkVersion(String protocol, String version) {
        if (protocol != null && !protocol.isBlank() && !TeipWire.PROTOCOL.equals(protocol)) {
            throw new TeipException(TeipWire.ErrorCode.VALIDATION_FAILED,
                    "Unsupported protocol: " + protocol, Map.of("field", "protocol"));
        }
        if (version == null || version.isBlank()) {
            throw new TeipException(TeipWire.ErrorCode.VERSION_MISMATCH,
                    "Missing protocol version", Map.of("field", "version"));
        }
        String major = version.contains(".") ? version.substring(0, version.indexOf('.'))
                : version;
        if (!"1".equals(major)) {
            throw new TeipException(TeipWire.ErrorCode.VERSION_MISMATCH,
                    "Unsupported protocol version: " + version + " (supported: "
                            + TeipWire.VERSION + ")",
                    Map.of("supportedVersion", TeipWire.VERSION));
        }
    }

    public TeipWire.StatusResponse status(String executionId) {
        ExecutionTask task = executionService.getTask(executionId)
                .orElseThrow(() -> new TeipException(TeipWire.ErrorCode.NOT_FOUND,
                        "Unknown execution: " + executionId, Map.of()));
        return statusResponse(task);
    }

    private TeipWire.StatusResponse statusResponse(ExecutionTask task) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("harnessId", task.getHarnessId());
        return new TeipWire.StatusResponse(TeipWire.PROTOCOL, TeipWire.VERSION, task.getId(),
                externalStatus(task.getStatus()), null, null, task.getProgress(),
                task.getStatusMessage(), task.getCreatedAt().toString(),
                task.getStartedAt() == null ? null : task.getStartedAt().toString(),
                task.getCompletedAt() == null ? null : task.getCompletedAt().toString(),
                task.isTerminal() ? -1 : 0, details);
    }

    private String externalStatus(ExecutionStatus status) {
        return switch (status) {
            case QUEUED -> "QUEUED";
            case RUNNING -> "RUNNING";
            case COMPLETED -> "COMPLETED";
            case FAILED, TIMED_OUT -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
    }

    public TeipWire.EventsResponse events(String executionId, long afterSequence, int limit) {
        ExecutionTask task = executionService.getTask(executionId)
                .orElseThrow(() -> new TeipException(TeipWire.ErrorCode.NOT_FOUND,
                        "Unknown execution: " + executionId, Map.of()));
        int capped = Math.max(1, Math.min(limit, 500));
        List<ExecutionEvent> loaded = executionService.getEvents(executionId,
                Math.max(0, afterSequence), capped);
        List<TeipWire.Event> events = new ArrayList<>();
        for (ExecutionEvent event : loaded) {
            events.add(new TeipWire.Event("evt-" + event.sequence(), executionId,
                    event.sequence(), event.type().name(), event.timestamp().toString(),
                    null, event.data()));
        }
        long nextSequence = events.isEmpty() ? Math.max(0, afterSequence)
                : events.get(events.size() - 1).sequence() + 1;
        boolean hasMore = events.size() == capped;
        return new TeipWire.EventsResponse(TeipWire.PROTOCOL, TeipWire.VERSION, executionId,
                events, hasMore, nextSequence);
    }

    public TeipWire.ResultResponse result(String executionId) {
        ExecutionTask task = executionService.getTask(executionId)
                .orElseThrow(() -> new TeipException(TeipWire.ErrorCode.NOT_FOUND,
                        "Unknown execution: " + executionId, Map.of()));
        if (!task.isTerminal()) {
            throw new TeipException(TeipWire.ErrorCode.CONFLICT,
                    "Execution is not terminal", Map.of("currentStatus",
                            externalStatus(task.getStatus())));
        }
        List<String> modifiedFiles = List.of();
        String patch = "";
        Optional<ChangeReview> review = reviewService.getReviewForExecution(executionId);
        if (review.isPresent()) {
            modifiedFiles = review.get().getFiles().stream()
                    .map(ChangeReview.ReviewFile::path).toList();
            StringBuilder diff = new StringBuilder();
            for (ChangeReview.ReviewFile file : review.get().getFiles()) {
                diff.append(file.diff()).append('\n');
            }
            patch = diff.toString();
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (task.getStartedAt() != null && task.getCompletedAt() != null) {
            metrics.put("durationMs", Duration.between(task.getStartedAt(),
                    task.getCompletedAt()).toMillis());
        }
        return new TeipWire.ResultResponse(TeipWire.PROTOCOL, TeipWire.VERSION, executionId,
                null, externalStatus(task.getStatus()),
                task.getSummary() == null ? "" : task.getSummary(), patch, modifiedFiles,
                List.of(), metrics,
                task.getCompletedAt() == null ? null : task.getCompletedAt().toString());
    }

    public TeipWire.CancelResponse cancel(String executionId) {
        ExecutionTask task = executionService.getTask(executionId)
                .orElseThrow(() -> new TeipException(TeipWire.ErrorCode.NOT_FOUND,
                        "Unknown execution: " + executionId, Map.of()));
        if (!task.isTerminal()) {
            executionService.cancel(executionId);
        }
        return new TeipWire.CancelResponse(TeipWire.PROTOCOL, TeipWire.VERSION, executionId,
                task.isTerminal() ? externalStatus(task.getStatus()) : "CANCELLED",
                "Execution cancellation accepted.");
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record CreateOutcome(ExecutionTask task, boolean duplicate) {
    }
}
