package eu.kalafatic.dstudio.harness.ollama;

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
 * Ollama inference-engine adapter for EVO-Studio (Phase 6, WP-6.7). Alternative family
 * inference engine alongside ENR: model discovery via /api/tags, chat via /api/chat,
 * streaming via /api/chat stream=true, unload via keep_alive 0.
 */
public class OllamaInferenceEngineAdapter implements InferenceEngineAdapter {

    public static final String ENGINE_ID = "ollama";
    public static final String ENGINE_NAME = "Ollama";
    public static final String ENGINE_TYPE = "OLLAMA";

    public static final String CONFIG_ENDPOINT = "endpoint";
    public static final String CONFIG_DEFAULT_MODEL = "defaultModel";
    public static final String CONFIG_TIMEOUT_SECONDS = "timeoutSeconds";

    public static final String DEFAULT_ENDPOINT = "http://localhost:11434";

    private final String engineId;
    private final String engineName;
    private final Map<String, String> config = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public OllamaInferenceEngineAdapter() {
        this(ENGINE_ID, ENGINE_NAME);
    }

    /** Multi-instance constructor: several Ollama runtimes can be registered under distinct ids. */
    public OllamaInferenceEngineAdapter(String engineId, String engineName) {
        this.engineId = engineId;
        this.engineName = engineName;
        config.put(CONFIG_ENDPOINT, DEFAULT_ENDPOINT);
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
        try {
            HttpResponse<String> response = send("GET", "/api/tags", null);
            if (response.statusCode() == 200) {
                return InferenceConnectionTestResult.ok("Ollama available at "
                        + endpoint());
            }
            return InferenceConnectionTestResult.error("Ollama at " + endpoint()
                    + " returned HTTP " + response.statusCode());
        } catch (Exception e) {
            return InferenceConnectionTestResult.error("Ollama unreachable at "
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
        return new LinkedHashMap<>(config);
    }

    @Override
    public List<InferenceModel> listModels() {
        try {
            HttpResponse<String> response = send("GET", "/api/tags", null);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Ollama model discovery failed: HTTP "
                        + response.statusCode());
            }
            List<InferenceModel> models = new ArrayList<>();
            for (JsonNode node : mapper.readTree(response.body()).path("models")) {
                String name = node.path("name").asText("");
                models.add(new InferenceModel("ollama:" + name, name, "ollama", "ollama",
                        node.path("size").asLong(0), true));
            }
            return models;
        } catch (Exception e) {
            throw new IllegalStateException("Ollama model discovery failed: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public InferenceRuntimeStatus runtimeStatus() {
        Runtime runtime = Runtime.getRuntime();
        return new InferenceRuntimeStatus(true,
                runtime.availableProcessors(), -1,
                runtime.maxMemory() / (1024 * 1024),
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                false, "", 0, 0, 0, List.of("ollama"));
    }

    @Override
    public boolean loadModel(String modelId) {
        return listModels().stream().anyMatch(m -> m.name().equals(strip(modelId))
                || m.id().equals(modelId));
    }

    @Override
    public boolean unloadModel(String modelId) {
        try {
            String body = "{\"model\":\"" + escape(strip(modelId)) + "\",\"keep_alive\":0}";
            HttpResponse<String> response = send("POST", "/api/generate", body);
            return response.statusCode() == 200;
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
            String body = "{\"model\":\"" + escape(strip(request.model()))
                    + "\",\"stream\":false,\"messages\":[" + messages
                    + "],\"options\":{\"temperature\":" + request.temperature()
                    + ",\"num_predict\":" + request.maxTokens() + "}}";
            long start = System.currentTimeMillis();
            HttpResponse<String> response = send("POST", "/api/chat", body);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Ollama chat failed: HTTP "
                        + response.statusCode() + " " + response.body());
            }
            JsonNode node = mapper.readTree(response.body());
            return new InferenceChatResponse(
                    node.path("message").path("content").asText(""),
                    node.path("done_reason").asText("stop"), "ollama",
                    request.model(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new IllegalStateException("Ollama chat failed: " + e.getMessage(), e);
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
        String body = "{\"model\":\"" + escape(strip(request.model()))
                + "\",\"stream\":true,\"messages\":[" + messages + "]}";
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint() + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(timeout())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            StringBuilder errorBody = new StringBuilder();
            try (java.util.stream.Stream<String> lines = response.body()) {
                lines.limit(5).forEach(line -> errorBody.append(line).append(' '));
            }
            throw new IllegalStateException("Ollama streaming chat failed: HTTP "
                    + response.statusCode() + " " + errorBody.toString().trim());
        }
        try (java.util.stream.Stream<String> lines = response.body()) {
            try {
                lines.forEach(line -> {
                    if (cancellationSupplier != null && cancellationSupplier.getAsBoolean()) {
                        lines.close();
                        return;
                    }
                    if (line == null || line.isBlank()) {
                        return;
                    }
                    try {
                        JsonNode node = mapper.readTree(line);
                        String chunk = node.path("message").path("content").asText("");
                        if (!chunk.isEmpty()) {
                            chunkConsumer.accept(new InferenceChatChunk(chunk, false, "ollama"));
                        }
                        if (node.path("done").asBoolean(false)) {
                            chunkConsumer.accept(InferenceChatChunk.done("ollama"));
                        }
                    } catch (Exception ignored) {
                        // malformed line: skip
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
                .timeout(timeout());
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Removes the {@code ollama:} engine prefix when present; bare model names
     *  (including tagged ones like {@code llama3.2:latest}) pass through unchanged. */
    private static String strip(String modelId) {
        if (modelId == null) {
            return "";
        }
        return modelId.startsWith(ENGINE_ID + ":")
                ? modelId.substring(ENGINE_ID.length() + 1) : modelId;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
