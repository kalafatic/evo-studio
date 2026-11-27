package eu.kalafatic.dstudio.harness.dcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dstudio.core.harness.HarnessAdapter;
import eu.kalafatic.dstudio.core.harness.HarnessCapabilityDescriptor;
import eu.kalafatic.dstudio.core.harness.HarnessConnectionTestResult;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionRequest;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionResult;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionSink;
import eu.kalafatic.dstudio.core.model.ExecutionEventType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EVO-Code coding-harness adapter for EVO-Studio (Phase 6, WP-6.7). Delegates executions to
 * the native EVO-Code harness through its stable /api/v1 surface (FHC 1.0 wire mapping).
 * EVO-Code owns agent execution and its harness-specific state; EVO-Studio owns executions,
 * review and persistence. EVO-Code never commits in delegated mode — sessions with changes
 * end in WAITING_FOR_APPROVAL and the diff is collected for EVO-Studio review.
 */
public class DCodeHarnessAdapter implements HarnessAdapter {

    public static final String HARNESS_ID = "dcode";
    public static final String HARNESS_NAME = "EVO-Code";
    public static final String HARNESS_TYPE = "DCODE";

    public static final String CONFIG_ENDPOINT = "endpoint";
    public static final String CONFIG_AUTH_TOKEN = "authToken";
    public static final String CONFIG_PROVIDER_ID = "providerId";
    public static final String CONFIG_MODEL = "model";
    public static final String CONFIG_TIMEOUT_SECONDS = "timeoutSeconds";

    /**
     * Family port convention: EVO-Code owns the 48xxx block (48080 default); it
     * must never point at EVO-Studio's own TEIP port (58xxx block, 58080 primary).
     */
    public static final String DEFAULT_ENDPOINT = "http://localhost:48080"; // EVO-Code owns 48xxx (48080)

    private final Map<String, String> config = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public DCodeHarnessAdapter() {
        config.put(CONFIG_ENDPOINT, DEFAULT_ENDPOINT);
        config.put(CONFIG_AUTH_TOKEN, "");
        config.put(CONFIG_PROVIDER_ID, "");
        config.put(CONFIG_MODEL, "");
        config.put(CONFIG_TIMEOUT_SECONDS, "1800");
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
                    "EVO-Code", true, 0, true));
        }
        return new HarnessCapabilityDescriptor(HARNESS_ID, HARNESS_NAME, HARNESS_TYPE,
                List.of("CODE_IMPLEMENTATION", "CODE_ANALYSIS"),
                List.of("DEFAULT"), models, List.of("default"),
                Map.of("asyncExecution", true, "eventStreaming", true, "cancellation", true),
                List.of("DCODE_NATIVE"));
    }

    @Override
    public boolean isAvailable() {
        return testConnection().success();
    }

    @Override
    public HarnessConnectionTestResult testConnection() {
        try {
            HttpResponse<String> response = send("GET", "/health", null);
            if (response.statusCode() == 200) {
                return HarnessConnectionTestResult.ok("EVO-Code harness available at "
                        + endpoint());
            }
            return HarnessConnectionTestResult.error("EVO-Code at " + endpoint()
                    + " returned HTTP " + response.statusCode());
        } catch (Exception e) {
            return HarnessConnectionTestResult.error("EVO-Code harness unreachable at "
                    + endpoint() + ": " + e.getMessage());
        }
    }

    @Override
    public void updateConfiguration(Map<String, String> configuration) {
        if (configuration != null) {
            config.putAll(configuration);
        }
    }

    @Override
    public Map<String, String> getConnectionConfiguration() {
        Map<String, String> result = new LinkedHashMap<>(config);
        if (result.containsKey(CONFIG_AUTH_TOKEN)
                && !result.get(CONFIG_AUTH_TOKEN).isBlank()) {
            result.put(CONFIG_AUTH_TOKEN, "****** (configured)");
        }
        return result;
    }

    private String endpoint() {
        return config.getOrDefault(CONFIG_ENDPOINT, DEFAULT_ENDPOINT);
    }

    private Duration timeout() {
        try {
            return Duration.ofSeconds(Long.parseLong(
                    config.getOrDefault(CONFIG_TIMEOUT_SECONDS, "1800")));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(1800);
        }
    }

    @Override
    public HarnessExecutionResult execute(HarnessExecutionRequest request,
            HarnessExecutionSink sink) {
        if (request.workingDirectory() == null
                || !java.nio.file.Files.isDirectory(request.workingDirectory())) {
            return HarnessExecutionResult.error(-1,
                    "EVO-Code working directory does not exist: "
                            + request.workingDirectory());
        }
        Duration effectiveTimeout = timeout();
        if (request.timeout() != null) {
            effectiveTimeout = Duration.ofMillis(Math.min(request.timeout().toMillis(),
                    effectiveTimeout.toMillis()));
        }
        sink.emit(ExecutionEventType.EXECUTION_STARTED,
                "EVO-Code execution started in " + request.workingDirectory());
        String sessionId = null;
        try {
            String workspaceId = createWorkspace(
                    "dstudio-" + sanitize(request.taskId()),
                    request.workingDirectory().toAbsolutePath().toString());
            addRepository(workspaceId, request.workingDirectory().getFileName().toString(),
                    request.workingDirectory().toAbsolutePath().toString());
            String model = request.model() != null && !request.model().isBlank()
                    ? request.model() : config.getOrDefault(CONFIG_MODEL, "");
            sessionId = createSession("EVO-Studio execution " + sanitize(request.taskId()),
                    workspaceId, config.getOrDefault(CONFIG_PROVIDER_ID, ""), model);
            String effectivePrompt = request.prompt() != null && !request.prompt().isBlank()
                    ? request.prompt() : request.instruction();
            startSession(sessionId, effectivePrompt);
            sink.emit(ExecutionEventType.EXECUTION_LOG,
                    "EVO-Code session " + sessionId + " submitted");

            long deadline = System.currentTimeMillis() + effectiveTimeout.toMillis();
            int loggedMessages = 0;
            while (true) {
                if (request.cancellationSupplier().getAsBoolean()) {
                    cancelSession(sessionId);
                    sink.emit(ExecutionEventType.EXECUTION_CANCELLED,
                            "EVO-Code execution cancelled");
                    return HarnessExecutionResult.cancelled("EVO-Code execution cancelled");
                }
                if (System.currentTimeMillis() > deadline) {
                    cancelSession(sessionId);
                    sink.emit(ExecutionEventType.EXECUTION_LOG, "EVO-Code execution timed out");
                    return HarnessExecutionResult.timedOut("EVO-Code execution timed out");
                }
                List<String> messages = messages(sessionId);
                for (int i = loggedMessages; i < messages.size(); i++) {
                    sink.log("[EVO-Code] " + messages.get(i));
                }
                loggedMessages = messages.size();

                String state = sessionState(sessionId);
                switch (state) {
                    case "COMPLETED", "WAITING_FOR_APPROVAL" -> {
                        ChangeResult changes = changes(sessionId);
                        String summary = lastMessage(messages);
                        if ("WAITING_FOR_APPROVAL".equals(state)) {
                            summary = summary.isBlank()
                                    ? "Execution completed with changes pending review"
                                    : summary + " (changes pending review)";
                        }
                        sink.progress(1.0, "completed");
                        sink.emit(ExecutionEventType.EXECUTION_COMPLETED, summary,
                                Map.of("modifiedFiles", changes.files()));
                        return HarnessExecutionResult.ok(
                                "EVO-Code execution completed: " + summary, changes.files(),
                                changes.diff());
                    }
                    case "FAILED" -> {
                        sink.emit(ExecutionEventType.EXECUTION_FAILED,
                                "EVO-Code execution failed");
                        return HarnessExecutionResult.error(-1,
                                "EVO-Code execution failed: " + lastMessage(messages));
                    }
                    case "CANCELLED" -> {
                        return HarnessExecutionResult.cancelled(
                                "EVO-Code execution was cancelled");
                    }
                    default -> Thread.sleep(2000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HarnessExecutionResult.cancelled("EVO-Code execution interrupted");
        } catch (Exception e) {
            sink.emit(ExecutionEventType.EXECUTION_FAILED, "EVO-Code execution failed: "
                    + e.getMessage());
            return HarnessExecutionResult.error(-1, "EVO-Code execution failed: "
                    + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Wire operations
    // ------------------------------------------------------------------

    private String createWorkspace(String name, String rootPath) throws Exception {
        String body = mapper.writeValueAsString(Map.of("name", name, "rootPath", rootPath));
        JsonNode node = requireJson(send("POST", "/api/v1/workspaces", body), 200,
                "workspace creation");
        return node.path("id").asText("");
    }

    private void addRepository(String workspaceId, String name, String localPath)
            throws Exception {
        String body = mapper.writeValueAsString(Map.of("name", name,
                "localPath", localPath, "accessPolicy", "READ_WRITE"));
        requireJson(send("POST", "/api/v1/workspaces/" + workspaceId
                + "/repositories", body), 200, "repository registration");
    }

    private String createSession(String title, String workspaceId, String providerId,
            String model) throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("workspaceId", workspaceId);
        if (providerId != null && !providerId.isBlank()) {
            payload.put("providerId", providerId);
        }
        if (model != null && !model.isBlank()) {
            payload.put("modelId", model);
        }
        JsonNode node = requireJson(send("POST", "/api/v1/sessions",
                mapper.writeValueAsString(payload)), 200, "session creation");
        return node.path("id").asText("");
    }

    private void startSession(String sessionId, String task) throws Exception {
        requireJson(send("POST", "/api/v1/sessions/" + sessionId + "/start",
                mapper.writeValueAsString(Map.of("task", task))), 200,
                "execution submission");
    }

    private void cancelSession(String sessionId) {
        try {
            send("POST", "/api/v1/sessions/" + sessionId + "/cancel", "{}");
        } catch (Exception ignored) {
        }
    }

    private String sessionState(String sessionId) throws Exception {
        JsonNode node = requireJson(send("GET", "/api/v1/sessions/" + sessionId, null),
                200, "session lookup");
        return node.path("state").asText("");
    }

    private List<String> messages(String sessionId) throws Exception {
        JsonNode node = requireJson(send("GET", "/api/v1/sessions/" + sessionId
                + "/messages", null), 200, "message lookup");
        List<String> contents = new ArrayList<>();
        for (JsonNode message : node) {
            String content = message.path("content").asText("");
            if (!content.isBlank()) {
                contents.add(content);
            }
        }
        return contents;
    }

    private ChangeResult changes(String sessionId) throws Exception {
        JsonNode node = requireJson(send("GET", "/api/v1/sessions/" + sessionId
                + "/changes", null), 200, "change lookup");
        List<String> files = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        for (JsonNode repository : node.path("repositories")) {
            for (JsonNode file : repository.path("changedFiles")) {
                files.add(file.asText(""));
            }
            String repositoryDiff = repository.path("diff").asText("");
            if (!repositoryDiff.isBlank()) {
                if (diff.length() > 0) {
                    diff.append('\n');
                }
                diff.append(repositoryDiff);
            }
        }
        return new ChangeResult(files, diff.toString());
    }

    private record ChangeResult(List<String> files, String diff) {
    }

    private JsonNode requireJson(HttpResponse<String> response, int expected,
            String operation) throws Exception {
        if (response.statusCode() != expected) {
            throw new IllegalStateException("EVO-Code " + operation + " failed: HTTP "
                    + response.statusCode() + " " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint() + path))
                .header("Content-Type", "application/json")
                .timeout(timeout());
        String token = config.getOrDefault(CONFIG_AUTH_TOKEN, "");
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String lastMessage(List<String> messages) {
        String content = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            content = messages.get(i);
            if (!content.isBlank()) {
                return content;
            }
        }
        return content;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "task";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "-");
    }
}
