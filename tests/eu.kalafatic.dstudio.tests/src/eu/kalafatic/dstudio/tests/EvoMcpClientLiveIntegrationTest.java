package eu.kalafatic.dstudio.tests;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dstudio.teip.client.EvoMcpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live integration of the EVO-Studio EvoMcpClient against a running EVO MCP
 * server (127.0.0.1:28280). Skips silently when EVO is not running, so the
 * suite stays green in isolation. When run with EVO up, this is the real
 * client-side half of the cross-application contract.
 */
public class EvoMcpClientLiveIntegrationTest {

    private static final String ENDPOINT = "http://127.0.0.1:28280";
    private static final String TOKEN = "dstudio-token-verify-001";
    private static final String PROJECT = "/tmp/dstudio-live-project";

    private EvoMcpClient client;

    @BeforeEach
    public void setUp() {
        if (isEvoReachable()) {
            client = new EvoMcpClient();
        }
    }

    private boolean isEvoReachable() {
        try {
            HttpURLConnection connection = (HttpURLConnection)
                    URI.create(ENDPOINT + "/health").toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.connect();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 600) {
                return false;
            }
            JsonNode tools = new EvoMcpClient().listTools(ENDPOINT, TOKEN);
            return tools != null && tools.toString().contains("context_get");
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void liveContextGetReturnsPackage() {
        if (client == null) {
            return;
        }
        EvoMcpClient.ContextResult result = client.contextGet(ENDPOINT, TOKEN, PROJECT,
                null, "verify live dstudio client", 4000,
                List.of("user", "project", "memory", "decisions"));
        assertNotNull(result, "context_get must return a package");
        JsonNode pkg = result.packageJson();
        assertNotNull(pkg);
        assertTrue(pkg.has("schemaVersion"), "package must carry schemaVersion");
        assertTrue(result.tokenEstimate() >= 0);
    }

    @Test
    public void liveMemoryStoreAndSessionCreate() {
        if (client == null) {
            return;
        }
        boolean stored = client.memoryStore(ENDPOINT, TOKEN,
                "EVO-Studio live client verification note", "FACT", "PROJECT", PROJECT, 0.9);
        assertTrue(stored, "memory_store must succeed against live EVO");

        String sessionId = client.sessionCreate(ENDPOINT, TOKEN, "TASK", PROJECT,
                "dstudio-live-client-1");
        assertNotNull(sessionId, "session_create must return a cognitive session id");
    }

    @Test
    public void invalidTokenDegradesGracefully() {
        if (client == null) {
            return;
        }
        EvoMcpClient.ContextResult result = client.contextGet(ENDPOINT, "invalid-token",
                PROJECT, null, "verify", 1000, null);
        assertTrue(result == null, "Invalid token must yield graceful degradation");
    }
}
