package eu.kalafatic.dstudio.teip.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * MCP client for EVO Cognitive Services (port 28280, JSON-RPC 2.0 over
 * HTTP POST /mcp, protocol 2024-11-05).
 *
 * <p>Wire-only integration: EVO-Studio never imports EVO classes. Every call
 * carries the EVO-issued application token; identity parameters are hints
 * validated by EVO against the token. When EVO is unavailable every method
 * degrades gracefully (null/false); EVO-Studio's local execution remains
 * unchanged.</p>
 *
 * <p>Phase 1 identity: application token. The operator name can be sent as
 * a metadata hint via {@link #contextGet} when EVO-Studio is configured with
 * a per-user mapping.</p>
 */
public class EvoMcpClient {
    private static final Logger LOGGER = Logger.getLogger(EvoMcpClient.class.getName());

    private final HttpClient httpClient;
    private final Duration timeout;
    private final AtomicLong requestIds = new AtomicLong(0);
    private final ObjectMapper mapper = new ObjectMapper();

    public EvoMcpClient() {
        this(Duration.ofSeconds(30));
    }

    public EvoMcpClient(Duration timeout) {
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    /** Lists MCP tools; null when EVO is unreachable or the call fails. */
    public JsonNode listTools(String endpoint, String authToken) {
        try {
            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestIds.incrementAndGet());
            request.put("method", "tools/list");
            String base = normalizeEndpoint(endpoint);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (authToken != null ? authToken : ""))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode envelope = mapper.readTree(response.body());
            if (envelope.has("error")) {
                return null;
            }
            return envelope.get("result");
        } catch (Exception e) {
            LOGGER.warning("EvoMcpClient: tools/list failed (degrading): " + e.getMessage());
            return null;
        }
    }

    /** Result of context_get: raw package JSON + token estimate. */
    public record ContextResult(JsonNode packageJson, int tokenEstimate) {}

    /** Sends one JSON-RPC tools/call request; throws on transport or RPC errors. */
    public JsonNode callTool(String endpoint, String authToken, String toolName,
                             ObjectNode arguments) throws IOException, InterruptedException {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIds.incrementAndGet());
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", toolName);
        params.set("arguments", arguments != null ? arguments : mapper.createObjectNode());

        String base = normalizeEndpoint(endpoint);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(base + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (authToken != null ? authToken : ""))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("EVO MCP HTTP " + response.statusCode());
        }
        JsonNode envelope = mapper.readTree(response.body());
        if (envelope.has("error")) {
            JsonNode error = envelope.get("error");
            throw new IOException("EVO MCP error " + error.path("code").asInt()
                    + ": " + error.path("message").asText(""));
        }
        return envelope.get("result");
    }

    /**
     * Reconstructs cognitive context from EVO. Returns null when EVO is
     * unreachable so callers degrade gracefully.
     */
    public ContextResult contextGet(String endpoint, String authToken, String project,
                                    String sessionId, String intent, int budgetTokens,
                                    List<String> categories) {
        try {
            ObjectNode args = mapper.createObjectNode();
            if (project != null && !project.isBlank()) args.put("project", project);
            if (sessionId != null && !sessionId.isBlank()) args.put("sessionId", sessionId);
            if (intent != null && !intent.isBlank()) args.put("intent", intent);
            if (budgetTokens > 0) args.put("budgetTokens", budgetTokens);
            if (categories != null && !categories.isEmpty()) {
                var array = args.putArray("categories");
                for (String category : categories) {
                    array.add(category);
                }
            }
            JsonNode result = callTool(endpoint, authToken, "context_get", args);
            return new ContextResult(result, result != null
                    ? result.path("tokenEstimate").asInt(0) : 0);
        } catch (Exception e) {
            LOGGER.warning("EvoMcpClient: context_get failed (degrading): " + e.getMessage());
            return null;
        }
    }

    /** Stores one memory record in EVO; false on failure. */
    public boolean memoryStore(String endpoint, String authToken, String content, String type,
                               String scope, String project, double confidence) {
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("content", content);
            args.put("type", type != null ? type : "EPISODIC");
            args.put("scope", scope != null ? scope : "PROJECT");
            if (project != null && !project.isBlank()) args.put("project", project);
            args.put("confidence", confidence);
            args.put("source", "TOOL");
            JsonNode result = callTool(endpoint, authToken, "memory_store", args);
            return result != null && result.has("entryId");
        } catch (Exception e) {
            LOGGER.warning("EvoMcpClient: memory_store failed (degrading): " + e.getMessage());
            return false;
        }
    }

    /** Registers a correlated cognitive session in EVO; null on failure. */
    public String sessionCreate(String endpoint, String authToken, String kind, String project,
                                String correlationId) {
        try {
            ObjectNode args = mapper.createObjectNode();
            args.put("kind", kind != null ? kind : "TASK");
            if (project != null && !project.isBlank()) args.put("project", project);
            if (correlationId != null && !correlationId.isBlank()) args.put("correlationId", correlationId);
            JsonNode result = callTool(endpoint, authToken, "session_create", args);
            return result != null && result.has("sessionId") ? result.get("sessionId").asText() : null;
        } catch (Exception e) {
            LOGGER.warning("EvoMcpClient: session_create failed (degrading): " + e.getMessage());
            return null;
        }
    }

    /** Records a context snapshot reference in EVO; false on failure. */
    public boolean contextRecord(String endpoint, String authToken, String sessionId, String summary) {
        try {
            ObjectNode args = mapper.createObjectNode();
            if (sessionId != null && !sessionId.isBlank()) args.put("sessionId", sessionId);
            args.put("summary", summary != null ? summary : "");
            JsonNode result = callTool(endpoint, authToken, "context_record", args);
            return result != null && result.has("contextId");
        } catch (Exception e) {
            LOGGER.warning("EvoMcpClient: context_record failed (degrading): " + e.getMessage());
            return false;
        }
    }

    /** Records meaningful work results after execution; never throws. */
    public void recordOutcome(String endpoint, String authToken, String sessionId,
                              String project, boolean success, String summary) {
        try {
            String cognitiveSessionId = sessionId;
            if (cognitiveSessionId == null) {
                cognitiveSessionId = sessionCreate(endpoint, authToken, "TASK", project, null);
            }
            memoryStore(endpoint, authToken,
                    "Execution outcome (" + (success ? "SUCCESS" : "FAILURE") + "): " + summary,
                    "EPISODIC", "PROJECT", project, success ? 0.9 : 0.7);
            contextRecord(endpoint, authToken, cognitiveSessionId, summary);
        } catch (Exception e) {
            LOGGER.warning("EvoMcpClient: recordOutcome failed (degrading): " + e.getMessage());
        }
    }

    /** Reads the session history resource (diagnostics); null on failure. */
    public JsonNode sessionHistory(String endpoint, String authToken, String sessionId) {
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("uri", "evo://sessions/history?sessionId=" + sessionId);
            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", requestIds.incrementAndGet());
            request.put("method", "resources/read");
            request.set("params", params);
            String base = normalizeEndpoint(endpoint);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (authToken != null ? authToken : ""))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            return mapper.readTree(response.body());
        } catch (Exception e) {
            LOGGER.warning("EvoMcpClient: sessionHistory failed (degrading): " + e.getMessage());
            return null;
        }
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("EVO MCP endpoint is required");
        }
        String base = endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** Convenience factory for category lists. */
    public static List<String> categories(String... categories) {
        List<String> list = new ArrayList<>();
        for (String category : categories) {
            list.add(category);
        }
        return list;
    }
}
