package eu.kalafatic.dstudio.teip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import eu.kalafatic.dstudio.core.inference.InferenceEngineAdapter;
import eu.kalafatic.dstudio.core.inference.InferenceEngineRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unified model management HTTP surface ({@code /api/models}): provider pull,
 * installed-model listing, shared download jobs with live progress,
 * cancellation and removal. EVO-Studio owns no model management state — every
 * operation is delegated to the shared family model management service
 * hosted by ENR ({@code /api/v1/models/...}). The ENR endpoint follows the
 * configured ENR inference engine (config key {@code endpoint}), the
 * {@code dstudio.enr.endpoint} system property, or the family default.
 *
 * <p>The web UI and the RCP Model Manager view both call this same surface.</p>
 */
public class ModelManagementHttpHandler implements HttpHandler {

    public static final String PATH = "/api/models";
    public static final String DEFAULT_ENR_ENDPOINT = "http://localhost:28780";

    private final InferenceEngineRegistry registry;
    private final boolean devModeConfigured;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            // ENR runs on loopback; loopback traffic must bypass environment proxies.
            .proxy(ProxySelector.of(null))
            .build();

    public ModelManagementHttpHandler(InferenceEngineRegistry registry,
            boolean devModeConfigured) {
        this.registry = registry;
        this.devModeConfigured = devModeConfigured;
    }

    private String enrEndpoint() {
        String configured = System.getProperty("dstudio.enr.endpoint");
        if (configured != null && !configured.isBlank()) {
            return stripTrailingSlash(configured);
        }
        if (registry != null) {
            Optional<InferenceEngineAdapter> enr = registry.findAdapter("enr");
            if (enr.isPresent()) {
                String endpoint = enr.get().getConnectionConfiguration()
                        .getOrDefault("endpoint", "");
                if (!endpoint.isBlank()) {
                    return stripTrailingSlash(endpoint);
                }
            }
        }
        return DEFAULT_ENR_ENDPOINT;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            ApiAuth.authenticate(exchange, devModeConfigured);
        } catch (ApiAuth.ApiAuthException e) {
            respond(exchange, 401, error("AUTHENTICATION_FAILED", e.getMessage()));
            return;
        }
        String method = exchange.getRequestMethod();
        String rest = exchange.getRequestURI().getPath().substring(PATH.length());
        try {
            if ("GET".equals(method) && rest.equals("/providers")) {
                forward(exchange, "GET", "/api/v1/models/providers", null);
                return;
            }
            if (rest.startsWith("/providers/")) {
                String tail = rest.substring("/providers/".length());
                String[] parts = tail.split("/", 2);
                String provider = decode(parts[0]);
                if (parts.length == 1) {
                    respond(exchange, 404, error("NOT_FOUND", "Missing provider action"));
                    return;
                }
                String action = parts[1];
                if ("installed".equals(action) && "GET".equals(method)) {
                    forward(exchange, "GET", "/api/v1/models/providers/"
                            + encode(provider) + "/installed", null);
                    return;
                }
                if ("available".equals(action) && "GET".equals(method)) {
                    forward(exchange, "GET", "/api/v1/models/providers/"
                            + encode(provider) + "/available", null);
                    return;
                }
                if ("pull".equals(action) && "POST".equals(method)) {
                    JsonNode body = mapper.readTree(exchange.getRequestBody());
                    String model = body.path("model").asText("");
                    if (model.isBlank()) {
                        respond(exchange, 400, error("VALIDATION_FAILED",
                                "Field 'model' is required"));
                        return;
                    }
                    Map<String, Object> request = new LinkedHashMap<>();
                    request.put("model", model);
                    forward(exchange, "POST", "/api/v1/models/providers/"
                            + encode(provider) + "/pull", mapper.writeValueAsString(request));
                    return;
                }
                if ("DELETE".equals(method)) {
                    forward(exchange, "DELETE", "/api/v1/models/providers/"
                            + encode(provider) + "/" + encode(decode(action)), null);
                    return;
                }
                respond(exchange, 404, error("NOT_FOUND", "Unknown model route: " + rest));
                return;
            }
            if (rest.equals("/jobs") && "GET".equals(method)) {
                forward(exchange, "GET", "/api/v1/models/downloads", null);
                return;
            }
            if (rest.startsWith("/jobs/")) {
                String jobId = decode(rest.substring("/jobs/".length()));
                if ("GET".equals(method)) {
                    forward(exchange, "GET", "/api/v1/models/downloads/"
                            + encode(jobId), null);
                    return;
                }
                if ("DELETE".equals(method)) {
                    forward(exchange, "DELETE", "/api/v1/models/downloads/"
                            + encode(jobId), null);
                    return;
                }
                respond(exchange, 405, error("METHOD_NOT_ALLOWED", "GET or DELETE"));
                return;
            }
            respond(exchange, 404, error("NOT_FOUND", "Unknown model route: " + rest));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error("VALIDATION_FAILED", e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error("INTERNAL_ERROR", e.getClass().getSimpleName()
                    + ": " + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    /**
     * Forwards a request to the shared ENR model management service and streams
     * the response back verbatim (status + JSON body). Upstream failures are
     * mapped to the EVO-Studio envelope with {@code UPSTREAM_UNAVAILABLE}.
     */
    private void forward(HttpExchange exchange, String method, String path,
                         String body) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(enrEndpoint() + path))
                .header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            respond(exchange, 503, error("UPSTREAM_UNAVAILABLE",
                    "ENR model management unreachable at " + enrEndpoint()
                            + " (" + e.getMessage() + ")"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, error("UPSTREAM_UNAVAILABLE",
                    "ENR model management request interrupted"));
            return;
        }
        String responseBody = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = responseBody;
            try {
                JsonNode parsed = mapper.readTree(responseBody);
                JsonNode nested = parsed.path("error");
                if (!nested.isMissingNode()) {
                    message = nested.path("message").asText(message);
                }
            } catch (IOException ignored) {
                // keep the raw body as message
            }
            respond(exchange, 503, error("UPSTREAM_UNAVAILABLE",
                    "ENR model management error (HTTP " + response.statusCode() + "): "
                            + message));
            return;
        }
        respond(exchange, response.statusCode(), responseBody);
    }

    private void respond(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        respond(exchange, status, mapper.writeValueAsString(body));
    }

    private void respond(HttpExchange exchange, int status, String jsonBody)
            throws IOException {
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return body;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
