package eu.kalafatic.dstudio.application.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dstudio.application.DStudioRuntime;
import eu.kalafatic.dstudio.core.config.DStudioConfiguration;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * RCP-side client for the unified model management surface served by the
 * local EVO-Studio backend ({@code /api/models}, delegated to the shared ENR
 * service). Returns plain DTO records — no Jackson types cross the OSGi
 * bundle boundary. The web UI calls the same backend surface.
 */
public final class ModelManagementClient {

    /** Registered model provider with its declared capabilities. */
    public record ModelProviderInfo(String id, String displayName,
                                    List<String> capabilities, boolean reachable) {
    }

    /** One installed (or available) model on a provider. */
    public record ManagedModel(String provider, String id, long sizeBytes, String digest) {
    }

    /** Handle returned when a pull is accepted. */
    public record DownloadJobHandle(String jobId, String provider, String model,
                                    String status) {
    }

    /** Download progress snapshot. */
    public record DownloadStatusInfo(String jobId, String provider, String model,
                                     String status, String operation, long totalBytes,
                                     long completedBytes, int percent, String error) {
    }

    private final Supplier<Integer> portSupplier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .proxy(ProxySelector.of(null))
            .build();

    public ModelManagementClient() {
        this(ModelManagementClient::defaultPort);
    }

    public ModelManagementClient(Supplier<Integer> portSupplier) {
        this.portSupplier = portSupplier;
    }

    private static int defaultPort() {
        var coordinator = DStudioRuntime.getCoordinator();
        return coordinator == null || coordinator.getBoundTeipPort() <= 0
                ? DStudioConfiguration.DEFAULT_TEIP_PORT
                : coordinator.getBoundTeipPort();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + portSupplier.get();
    }

    public List<ModelProviderInfo> providers() {
        JsonNode root = get("/api/models/providers");
        List<ModelProviderInfo> result = new ArrayList<>();
        for (JsonNode entry : root.path("providers")) {
            List<String> capabilities = new ArrayList<>();
            for (JsonNode capability : entry.path("capabilities")) {
                capabilities.add(capability.asText(""));
            }
            result.add(new ModelProviderInfo(entry.path("id").asText(""),
                    entry.path("display_name").asText(""), capabilities,
                    entry.path("reachable").asBoolean(false)));
        }
        return result;
    }

    public List<ManagedModel> installed(String provider) {
        JsonNode root = get("/api/models/providers/" + encode(provider) + "/installed");
        List<ManagedModel> result = new ArrayList<>();
        for (JsonNode model : root.path("models")) {
            result.add(new ManagedModel(model.path("provider").asText(provider),
                    model.path("id").asText(""), model.path("size_bytes").asLong(-1),
                    model.path("digest").asText("")));
        }
        return result;
    }

    public DownloadJobHandle pull(String provider, String model) {
        JsonNode root = send("POST", "/api/models/providers/" + encode(provider) + "/pull",
                "{\"model\":\"" + model.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        return new DownloadJobHandle(
                root.path("jobId").asText(root.path("job_id").asText("")),
                root.path("provider").asText(provider),
                root.path("model").asText(model),
                root.path("status").asText("QUEUED"));
    }

    /** All shared download jobs (newest first), including terminal ones. */
    public List<DownloadStatusInfo> downloads() {
        JsonNode root = get("/api/models/jobs");
        List<DownloadStatusInfo> result = new ArrayList<>();
        for (JsonNode job : root.path("downloads")) {
            result.add(parseStatus(job));
        }
        return result;
    }

    public DownloadStatusInfo status(String jobId) {
        return parseStatus(get("/api/models/jobs/" + encode(jobId)));
    }

    public boolean cancel(String jobId) {
        JsonNode root = send("DELETE", "/api/models/jobs/" + encode(jobId), null);
        return root.path("cancelled").asBoolean(false);
    }

    public boolean remove(String provider, String modelId) {
        JsonNode root = send("DELETE", "/api/models/providers/" + encode(provider) + "/"
                + encode(modelId), null);
        return root.path("removed").asBoolean(false);
    }

    private DownloadStatusInfo parseStatus(JsonNode job) {
        return new DownloadStatusInfo(job.path("job_id").asText(""),
                job.path("provider").asText(""), job.path("model").asText(""),
                job.path("status").asText(""), job.path("operation").asText(""),
                job.path("total_bytes").asLong(-1), job.path("completed_bytes").asLong(0),
                job.path("percent").asInt(0), job.path("error").asText(""));
    }

    private JsonNode get(String path) {
        return send("GET", path, null);
    }

    private JsonNode send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ModelManagementException("Model management backend unreachable at "
                    + baseUrl() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelManagementException("Model management request interrupted");
        }
        String responseBody = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = responseBody;
            try {
                JsonNode parsed = mapper.readTree(responseBody);
                String nested = parsed.path("message").asText("");
                if (!nested.isBlank()) {
                    message = nested;
                }
            } catch (IOException ignored) {
                // keep the raw body as message
            }
            throw new ModelManagementException("Model management error (HTTP "
                    + response.statusCode() + "): " + message);
        }
        if (responseBody.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(responseBody);
        } catch (IOException e) {
            throw new ModelManagementException("Model management returned invalid JSON");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Transport/backend failure of the model management client. */
    public static final class ModelManagementException extends RuntimeException {
        public ModelManagementException(String message) {
            super(message);
        }
    }
}
