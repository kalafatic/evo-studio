package eu.kalafatic.dstudio.harness.enr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dstudio.core.inference.InferenceEngineAdapter;

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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * ENR inference-engine adapter for EVO-Studio (Phase 6, WP-6.7). Consumes the ENR 1.0 wire
 * contract (base /enr/v1): health, model discovery, model lifecycle, blocking chat, SSE
 * streaming and runtime status. ENR is the default family inference engine; the Ollama
 * adapter remains registered as an alternative.
 */
public class EnrInferenceEngineAdapter implements InferenceEngineAdapter {

    public static final String ENGINE_ID = "enr";
    public static final String ENGINE_NAME = "ENR - Evolution Neural Runtime";
    public static final String ENGINE_TYPE = "ENR";

    public static final String CONFIG_ENDPOINT = "endpoint";
    public static final String CONFIG_AUTH_TOKEN = "authToken";
    public static final String CONFIG_DEFAULT_MODEL = "defaultModel";
    public static final String CONFIG_TIMEOUT_SECONDS = "timeoutSeconds";

    public static final String DEFAULT_ENDPOINT = "http://localhost:28780";

    private final String engineId;
    private final String engineName;
    private final Map<String, String> config = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public EnrInferenceEngineAdapter() {
        this(ENGINE_ID, ENGINE_NAME);
    }

    /** Multi-instance constructor: several ENR runtimes can be registered under distinct ids. */
    public EnrInferenceEngineAdapter(String engineId, String engineName) {
        this.engineId = engineId;
        this.engineName = engineName;
        config.put(CONFIG_ENDPOINT, DEFAULT_ENDPOINT);
        config.put(CONFIG_AUTH_TOKEN, "");
        config.put(CONFIG_DEFAULT_MODEL, "");
        config.put(CONFIG_TIMEOUT_SECONDS, "120");
    }

    @Override
    public String getEngineId() {
        return engineId;
    }

    @Override
    public String getEngineName() {
        return engineName;
    }

    @Override
    public String getEngineType() {
        return ENGINE_TYPE;
    }

    private String endpoint() {
        return config.getOrDefault(CONFIG_ENDPOINT, DEFAULT_ENDPOINT);
    }

    private Duration timeout() {
        try {
            return Duration.ofSeconds(Long.parseLong(
                    config.getOrDefault(CONFIG_TIMEOUT_SECONDS, "120")));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(120);
        }
    }

    @Override
    public boolean isAvailable() {
        return testConnection().success();
    }

    @Override
    public InferenceConnectionTestResult testConnection() {
        // Canonical ENR 1.0 health path; /health is a legacy fallback for older
        // ENR deployments (family convention, mirror of the EVO-Code EnrProvider).
        try {
            HttpResponse<String> response = send("GET", "/enr/v1/health", null);
            if (response.statusCode() == 200) {
                return InferenceConnectionTestResult.ok("ENR runtime available at "
                        + endpoint());
            }
        } catch (Exception e) {
            // fall through to the legacy probe
        }
        try {
            HttpResponse<String> response = send("GET", "/health", null);
            if (response.statusCode() == 200) {
                return InferenceConnectionTestResult.ok("ENR runtime available at "
                        + endpoint() + " (legacy /health)");
            }
            return InferenceConnectionTestResult.error("ENR at " + endpoint()
                    + " returned HTTP " + response.statusCode());
        } catch (Exception e) {
            return InferenceConnectionTestResult.error("ENR runtime unreachable at "
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

    @Override
    public List<InferenceModel> listModels() {
        try {
            HttpResponse<String> response = send("GET", "/enr/v1/models", null);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ENR model discovery failed: HTTP "
                        + response.statusCode());
            }
            List<InferenceModel> models = new ArrayList<>();
            for (JsonNode node : mapper.readTree(response.body())) {
                models.add(new InferenceModel(node.path("id").asText(""),
                        node.path("name").asText(""), node.path("provider").asText(""),
                        node.path("engine").asText(""), node.path("sizeBytes").asLong(0),
                        node.path("available").asBoolean(true)));
            }
            return models;
        } catch (Exception e) {
            throw new IllegalStateException("ENR model discovery failed: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public InferenceRuntimeStatus runtimeStatus() {
        try {
            HttpResponse<String> response = send("GET", "/enr/v1/runtime", null);
            if (response.statusCode() != 200) {
                return new InferenceRuntimeStatus(false, 0, 0, 0, 0, false, "", 0, 0, 0,
                        List.of());
            }
            JsonNode node = mapper.readTree(response.body());
            boolean running = node.path("running").asBoolean(false);
            boolean ready = node.has("ready") ? node.path("ready").asBoolean(false)
                    : running;
            JsonNode cpu = node.path("cpu");
            JsonNode heap = node.path("heap");
            List<String> engines = new ArrayList<>();
            node.path("activeEngines").forEach(entry -> engines.add(entry.asText()));
            boolean gpuDetected = false;
            String gpuDevice = "";
            long gpuTotalMb = 0;
            long gpuUsedMb = 0;
            long gpuFreeMb = 0;
            for (JsonNode device : node.path("devices")) {
                if ("cuda".equals(device.path("type").asText(""))) {
                    gpuDetected = true;
                    gpuDevice = device.path("name").asText("");
                    gpuTotalMb = device.path("totalMemoryBytes").asLong(0) / 1048576;
                    gpuFreeMb = device.path("freeMemoryBytes").asLong(0) / 1048576;
                    gpuUsedMb = Math.max(0, gpuTotalMb - gpuFreeMb);
                    break;
                }
            }
            return new InferenceRuntimeStatus(ready,
                    cpu.path("processors").asInt(0),
                    cpu.path("loadAverage").asDouble(0),
                    heap.path("maxBytes").asLong(0) / 1048576,
                    heap.path("usedBytes").asLong(0) / 1048576,
                    gpuDetected, gpuDevice, gpuTotalMb, gpuUsedMb, gpuFreeMb, engines);
        } catch (Exception e) {
            return new InferenceRuntimeStatus(false, 0, 0, 0, 0, false, "", 0, 0, 0,
                    List.of());
        }
    }

    @Override
    public boolean loadModel(String modelId) {
        return lifecycle(modelId, "load");
    }

    @Override
    public boolean unloadModel(String modelId) {
        return lifecycle(modelId, "unload");
    }

    private boolean lifecycle(String modelId, String operation) {
        try {
            HttpResponse<String> response = send("POST", "/enr/v1/models/"
                    + encodePath(modelId) + "/" + operation, "{}");
            if (response.statusCode() != 200) {
                return false;
            }
            return mapper.readTree(response.body()).path("success").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public InferenceChatResponse chat(InferenceChatRequest request) {
        try {
            StringBuilder messages = new StringBuilder();
            for (InferenceChatMessage message : request.messages()) {
                if (messages.length() > 0) {
                    messages.append(',');
                }
                messages.append(mapper.writeValueAsString(Map.of(
                        "role", message.role(), "content", message.content())));
            }
            String body = "{\"model\":\"" + escape(request.model())
                    + "\",\"messages\":[" + messages + "],\"temperature\":"
                    + request.temperature() + ",\"maxTokens\":" + request.maxTokens() + "}";
            long start = System.currentTimeMillis();
            HttpResponse<String> response = send("POST", "/enr/v1/chat", body);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ENR chat failed: HTTP "
                        + response.statusCode() + " " + response.body());
            }
            JsonNode node = mapper.readTree(response.body());
            return new InferenceChatResponse(node.path("content").asText(""),
                    node.path("finishReason").asText("stop"),
                    node.path("engine").asText(""), node.path("model").asText(request.model()),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new IllegalStateException("ENR chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void streamChat(InferenceChatRequest request, Consumer<InferenceChatChunk> chunkConsumer,
            BooleanSupplier cancellationSupplier) throws Exception {
        StringBuilder messages = new StringBuilder();
        for (InferenceChatMessage message : request.messages()) {
            if (messages.length() > 0) {
                messages.append(',');
            }
            messages.append(mapper.writeValueAsString(Map.of(
                    "role", message.role(), "content", message.content())));
        }
        String body = "{\"model\":\"" + escape(request.model())
                + "\",\"messages\":[" + messages + "],\"temperature\":"
                + request.temperature() + ",\"maxTokens\":" + request.maxTokens() + "}";
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint() + "/enr/v1/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader())
                .timeout(timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("ENR streaming chat failed: HTTP "
                    + response.statusCode());
        }
        try (java.util.stream.Stream<String> lines = response.body()) {
            try {
                lines.forEach(line -> {
                    if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                        lines.close();
                        return;
                    }
                    if (line == null || !line.startsWith("data:")) {
                        return;
                    }
                    try {
                        JsonNode node = mapper.readTree(line.substring("data:".length()).trim());
                        String chunk = node.path("chunk").asText("");
                        boolean done = node.path("done").asBoolean(false);
                        if (!chunk.isEmpty()) {
                            chunkConsumer.accept(new InferenceChatChunk(chunk, false,
                                    node.path("engine").asText("")));
                        }
                        if (done) {
                            chunkConsumer.accept(InferenceChatChunk.done(
                                    node.path("engine").asText("")));
                        }
                    } catch (Exception ignored) {
                        // non-data or malformed line: skip
                    }
                });
            } catch (java.io.UncheckedIOException e) {
                // cancellation closes the body from within the consumer; the
                // resulting "closed" read failure is the expected abort path
                if (cancellationSupplier == null || !cancellationSupplier.getAsBoolean()) {
                    throw e;
                }
            }
        }
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader())
                .timeout(timeout());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String authHeader() {
        String token = config.getOrDefault(CONFIG_AUTH_TOKEN, "");
        return token.isBlank() ? "" : "Bearer " + token;
    }

    private static String encodePath(String value) {
        return value.replace("/", "%2F");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
