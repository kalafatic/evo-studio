package eu.kalafatic.dstudio.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dstudio.core.service.DefaultProjectService;
import eu.kalafatic.dstudio.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.dstudio.infrastructure.persistence.SecretCodec;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteProjectStore;
import eu.kalafatic.dstudio.teip.server.DStudioServer;
import eu.kalafatic.dstudio.teip.server.WorkspaceHttpHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 7 (S6): the backend serves the Web IDE assets and exposes real workspace/file
 * operations (open, browse, read, save) with path containment and shared auth.
 */
class WebIdeWorkspaceApiTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private SqliteDatabaseManager databaseManager;
    private DefaultProjectService projectService;
    private DStudioServer server;
    private int port;
    private Path projectRoot;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new SqliteDatabaseManager(tempDir.resolve("web.db"));
        new SchemaMigrationService().migrate(databaseManager.connection());
        projectService = new DefaultProjectService(
                new SqliteProjectStore(databaseManager.connection()));
        projectRoot = tempDir.resolve("web-project");
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("README.md"), "# Web project\n");
        Files.writeString(projectRoot.resolve("src/Main.java"), "class Main {}\n");
        server = new DStudioServer("127.0.0.1", 0, null, null, projectService, null,
                (exchange) -> exchange.close(), true, null, null, null);
        server.start();
        port = server.getBoundPort();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        databaseManager.close();
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void webAssetsAreServed() throws Exception {
        HttpResponse<String> index = send("GET", "/", null);
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("EVO-Studio"), index.body());
        assertTrue(index.headers().firstValue("Content-Type").orElse("")
                .contains("text/html"));
        HttpResponse<String> app = send("GET", "/web/app.js", null);
        assertEquals(200, app.statusCode());
        assertTrue(app.body().contains("Inference"), app.body());
        assertTrue(app.headers().firstValue("Content-Type").orElse("")
                .contains("javascript"));
        // The workspace API client lives in common.js (app.js consumes it).
        HttpResponse<String> common = send("GET", "/web/common.js", null);
        assertEquals(200, common.statusCode());
        assertTrue(common.body().contains("api/workspace"));
        HttpResponse<String> css = send("GET", "/web/style.css", null);
        assertEquals(200, css.statusCode());
        HttpResponse<String> missing = send("GET", "/web/nope.js", null);
        assertEquals(404, missing.statusCode());
    }

    @Test
    void openBrowseReadAndSaveFiles() throws Exception {
        HttpResponse<String> opened = send("POST", WorkspaceHttpHandler.PATH + "/projects",
                "{\"path\":\"" + projectRoot.toString().replace("\\", "\\\\") + "\"}");
        assertEquals(201, opened.statusCode());
        String projectId = mapper.readTree(opened.body()).get("id").asText();

        HttpResponse<String> list = send("GET", WorkspaceHttpHandler.PATH + "/projects", null);
        assertTrue(list.body().contains(projectId));

        // directory listing
        HttpResponse<String> root = send("GET", WorkspaceHttpHandler.PATH
                + "/files?project=" + projectId + "&path=", null);
        JsonNode rootNode = mapper.readTree(root.body());
        assertTrue(rootNode.get("directory").asBoolean());
        assertEquals(2, rootNode.get("children").size());

        // nested directory
        HttpResponse<String> src = send("GET", WorkspaceHttpHandler.PATH
                + "/files?project=" + projectId + "&path=src", null);
        assertTrue(src.body().contains("Main.java"));

        // read
        HttpResponse<String> read = send("GET", WorkspaceHttpHandler.PATH
                + "/files?project=" + projectId + "&path=README.md", null);
        assertTrue(read.body().contains("# Web project"));

        // save — real file effect on disk
        HttpResponse<String> saved = send("PUT", WorkspaceHttpHandler.PATH
                + "/files?project=" + projectId + "&path=src%2FMain.java",
                "class Main { int x; }");
        assertEquals(200, saved.statusCode());
        assertEquals("class Main { int x; }",
                Files.readString(projectRoot.resolve("src/Main.java")));

        // containment: traversal rejected
        HttpResponse<String> traversal = send("GET", WorkspaceHttpHandler.PATH
                + "/files?project=" + projectId + "&path=..%2F..%2Fetc", null);
        assertEquals(400, traversal.statusCode());

        // unknown project
        HttpResponse<String> unknown = send("GET", WorkspaceHttpHandler.PATH
                + "/files?project=nope&path=", null);
        assertEquals(404, unknown.statusCode());
    }

    @Test
    void workspaceApiStaysOpenOnLoopbackWhenTokenConfigured() throws Exception {
        // Current-phase policy: loopback workspace clients are local-trusted.
        System.setProperty("dstudio.teip.auth.token", "ws-secret-token");
        try {
            DStudioServer secured = new DStudioServer("127.0.0.1", 0, null, null,
                    projectService, null, (exchange) -> exchange.close(), false, null,
                    null, null);
            secured.start();
            try {
                int securedPort = secured.getBoundPort();
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:"
                                + securedPort + WorkspaceHttpHandler.PATH
                                + "/projects")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(),
                        "loopback workspace API must not require the remote token");
            } finally {
                secured.stop();
            }
        } finally {
            System.clearProperty("dstudio.teip.auth.token");
        }
    }
}
