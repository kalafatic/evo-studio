package eu.kalafatic.dstudio.tests;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.dstudio.teip.server.DStudioServer;
import eu.kalafatic.dstudio.teip.server.ModelManagementHttpHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unified model management HTTP surface ({@code /api/models}): the EVO-Studio
 * backend delegates provider pull/list/remove and shared download jobs to
 * the family ENR service. ENR is mocked here; the ENR implementation itself
 * is verified in its own repository. Both the web UI and the RCP Model
 * Manager view call this surface.
 */
class ModelManagementHttpApiTest {

    @TempDir
    Path tempDir;

    private HttpServer enr;
    private String enrUrl;
    private eu.kalafatic.dstudio.core.inference.DefaultInferenceEngineRegistry engineRegistry;
    private DStudioServer server;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        enr = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        enr.createContext("/", this::handleEnr);
        enr.start();
        enrUrl = "http://127.0.0.1:" + enr.getAddress().getPort();

        engineRegistry = new eu.kalafatic.dstudio.core.inference.DefaultInferenceEngineRegistry(
                new eu.kalafatic.dstudio.core.store.InferenceEngineStore() {
                    @Override
                    public void saveDescriptor(
                            eu.kalafatic.dstudio.core.inference.InferenceEngineDescriptor descriptor) {
                    }

                    @Override
                    public void deleteDescriptor(String engineId) {
                    }

                    @Override
                    public java.util.Optional<eu.kalafatic.dstudio.core.inference.InferenceEngineDescriptor>
                            findDescriptor(String engineId) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.List<eu.kalafatic.dstudio.core.inference.InferenceEngineDescriptor>
                            listDescriptors() {
                        return java.util.List.of();
                    }

                    @Override
                    public void saveSelectedEngineId(String engineId) {
                    }

                    @Override
                    public java.util.Optional<String> findSelectedEngineId() {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public void clearSelectedEngineId() {
                    }

                    @Override
                    public void saveConfiguration(String engineId, Map<String, String> configuration) {
                    }

                    @Override
                    public Map<String, String> findConfiguration(String engineId) {
                        return Map.of();
                    }
                });
        engineRegistry.registerEngine(new FakeInferenceEngineAdapter("enr", "ENR") {
            @Override
            public Map<String, String> getConnectionConfiguration() {
                return Map.of("endpoint", enrUrl);
            }
        });
        engineRegistry.registerEngine(new FakeInferenceEngineAdapter("ollama", "OLLAMA"));

        server = new DStudioServer("127.0.0.1", 0, null, null, null,
                engineRegistry, exchange -> exchange.close(), true, null, null, null);
        server.start();
        port = server.getBoundPort();
        client = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (enr != null) {
            enr.stop(0);
        }
    }

    private void handleEnr(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("DELETE".equals(method) && "/api/v1/models/downloads/job-1".equals(path)) {
            respond(exchange, 200, "{\"job_id\":\"job-1\",\"cancelled\":true}");
            return;
        }
        if ("DELETE".equals(method)
                && "/api/v1/models/providers/ollama/llama3:8b".equals(path)) {
            respond(exchange, 200, "{\"provider\":\"ollama\",\"model\":\"llama3:8b\","
                    + "\"removed\":true}");
            return;
        }
        switch (path) {
            case "/api/v1/models/providers" -> respond(exchange, 200,
                    "{\"providers\":[{\"id\":\"ollama\",\"display_name\":\"Ollama\","
                            + "\"capabilities\":[\"MODEL_PULL\",\"MODEL_LIST\",\"MODEL_REMOVE\","
                            + "\"DOWNLOAD_PROGRESS\",\"MODEL_METADATA\"],\"reachable\":true}]}");
            case "/api/v1/models/providers/ollama/installed" -> respond(exchange, 200,
                    "{\"provider\":\"ollama\",\"models\":[{\"provider\":\"ollama\","
                            + "\"id\":\"llama3:8b\",\"name\":\"llama3:8b\",\"digest\":\"sha256:x\","
                            + "\"size_bytes\":4661226496,\"modified_at\":1,\"installed\":true,"
                            + "\"details\":{}}]}");
            case "/api/v1/models/providers/ollama/pull" -> respond(exchange, 202,
                    "{\"jobId\":\"job-1\",\"job_id\":\"job-1\",\"provider\":\"ollama\","
                            + "\"model\":\"qwen3-vl:30b\",\"status\":\"QUEUED\",\"created_at\":1}");
            case "/api/v1/models/downloads" -> respond(exchange, 200,
                    "{\"downloads\":[{\"job_id\":\"job-1\",\"provider\":\"ollama\","
                            + "\"model\":\"qwen3-vl:30b\",\"status\":\"DOWNLOADING\","
                            + "\"operation\":\"downloading\",\"total_bytes\":1000,"
                            + "\"completed_bytes\":250,\"percent\":25,\"started_at\":1,"
                            + "\"updated_at\":2,\"completed_at\":0,\"error\":\"\",\"layers\":[]}]}");
            case "/api/v1/models/downloads/job-1" -> respond(exchange, 200,
                    "{\"job_id\":\"job-1\",\"provider\":\"ollama\",\"model\":\"qwen3-vl:30b\","
                            + "\"status\":\"DOWNLOADING\",\"operation\":\"downloading\","
                            + "\"total_bytes\":1000,\"completed_bytes\":500,\"percent\":50,"
                            + "\"started_at\":1,\"updated_at\":2,\"completed_at\":0,"
                            + "\"error\":\"\",\"layers\":[]}");
            case "/api/v1/models/providers/nope/installed" -> respond(exchange, 404,
                    "{\"error\":{\"code\":\"PROVIDER_NOT_FOUND\","
                            + "\"message\":\"Unknown model provider: nope\"}}");
            default -> respond(exchange, 404, "{\"error\":\"not found\"}");
        }
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void providers_delegatedToEnr() throws Exception {
        HttpResponse<String> response = send("GET",
                ModelManagementHttpHandler.PATH + "/providers", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"id\":\"ollama\""));
        assertTrue(response.body().contains("MODEL_PULL"));
    }

    @Test
    void installed_delegatedToEnr() throws Exception {
        HttpResponse<String> response = send("GET",
                ModelManagementHttpHandler.PATH + "/providers/ollama/installed", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("llama3:8b"));
    }

    @Test
    void pull_returnsSharedJob() throws Exception {
        HttpResponse<String> response = send("POST",
                ModelManagementHttpHandler.PATH + "/providers/ollama/pull",
                "{\"model\":\"qwen3-vl:30b\"}");
        assertEquals(202, response.statusCode());
        assertTrue(response.body().contains("\"jobId\":\"job-1\""));
        assertTrue(response.body().contains("qwen3-vl:30b"));
    }

    @Test
    void pull_missingModel_rejected() throws Exception {
        HttpResponse<String> response = send("POST",
                ModelManagementHttpHandler.PATH + "/providers/ollama/pull", "{}");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void jobs_delegatedWithProgress() throws Exception {
        HttpResponse<String> list = send("GET",
                ModelManagementHttpHandler.PATH + "/jobs", null);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("job-1"));
        HttpResponse<String> status = send("GET",
                ModelManagementHttpHandler.PATH + "/jobs/job-1", null);
        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("\"percent\":50"));
    }

    @Test
    void cancelJob_delegated() throws Exception {
        HttpResponse<String> response = send("DELETE",
                ModelManagementHttpHandler.PATH + "/jobs/job-1", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"cancelled\":true"));
    }

    @Test
    void removeModel_delegated() throws Exception {
        HttpResponse<String> response = send("DELETE",
                ModelManagementHttpHandler.PATH + "/providers/ollama/llama3:8b", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"removed\":true"));
    }

    @Test
    void upstreamProviderError_mappedToEnvelope() throws Exception {
        HttpResponse<String> response = send("GET",
                ModelManagementHttpHandler.PATH + "/providers/nope/installed", null);
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("UPSTREAM_UNAVAILABLE"));
        assertTrue(response.body().contains("nope"));
    }
}
