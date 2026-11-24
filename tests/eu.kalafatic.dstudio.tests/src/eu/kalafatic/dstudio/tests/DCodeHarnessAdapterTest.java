package eu.kalafatic.dstudio.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.kalafatic.dstudio.harness.dcode.DCodeHarnessAdapter;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionRequest;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionResult;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionSink;
import eu.kalafatic.dstudio.core.model.ExecutionEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EVO-Studio → EVO-Code harness adapter wire tests (Phase 6, WP-6.7).
 */
class DCodeHarnessAdapterTest {

    @TempDir
    Path tempDir;

    private HttpServer stubServer;
    private int stubPort;
    private final AtomicReference<String> stubState = new AtomicReference<>("COMPLETED");
    private final AtomicBoolean cancelReceived = new AtomicBoolean(false);

    private static class RecordingSink implements HarnessExecutionSink {
        final List<String> lines = new ArrayList<>();
        final List<ExecutionEventType> types = new ArrayList<>();

        @Override
        public void emit(ExecutionEventType type, String message) {
            types.add(type);
            lines.add(message);
        }

        @Override
        public void emit(ExecutionEventType type, String message, Map<String, Object> data) {
            types.add(type);
            lines.add(message);
        }

        @Override
        public void progress(double progress, String message) {
        }

        @Override
        public void log(String line) {
            lines.add(line);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        stubState.set("COMPLETED");
        cancelReceived.set(false);
        stubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/health", exchange -> respond(exchange, 200,
                "{\"application\":\"DCODE\",\"status\":\"UP\"}"));
        stubServer.createContext("/api/v1/workspaces", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/workspaces")) {
                respond(exchange, 200, "{\"id\":\"ws-stub-1\"}");
                return;
            }
            if (path.equals("/api/v1/workspaces/ws-stub-1/repositories")) {
                respond(exchange, 200, "{\"id\":\"ws-stub-1\"}");
                return;
            }
            respond(exchange, 404, "{}");
        });
        stubServer.createContext("/api/v1/sessions", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/sessions")) {
                respond(exchange, 200, "{\"id\":\"sess-stub-1\",\"state\":\"CREATED\"}");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1/start")) {
                respond(exchange, 200, "{\"status\":\"accepted\"}");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1/cancel")) {
                cancelReceived.set(true);
                respond(exchange, 200, "{\"status\":\"accepted\"}");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1")) {
                respond(exchange, 200, "{\"id\":\"sess-stub-1\",\"state\":\""
                        + stubState.get() + "\"}");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1/messages")) {
                respond(exchange, 200,
                        "[{\"role\":\"user\",\"content\":\"task\"},"
                        + "{\"role\":\"assistant\",\"content\":\"stub summary\"}]");
                return;
            }
            if (path.equals("/api/v1/sessions/sess-stub-1/changes")) {
                respond(exchange, 200,
                        "{\"repositories\":[{\"name\":\"repo\","
                        + "\"changedFiles\":[\"src/Changed.java\"],"
                        + "\"diff\":\"diff --git a/src/Changed.java\"}]}");
                return;
            }
            respond(exchange, 404, "{}");
        });
        stubServer.start();
        stubPort = stubServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        stubServer.stop(0);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
            String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private DCodeHarnessAdapter adapter() {
        DCodeHarnessAdapter adapter = new DCodeHarnessAdapter();
        adapter.updateConfiguration(Map.of(
                "endpoint", "http://127.0.0.1:" + stubPort,
                "timeoutSeconds", "60"));
        return adapter;
    }

    @Test
    void identityAndCapabilities() {
        DCodeHarnessAdapter adapter = adapter();
        assertEquals("dcode", adapter.getHarnessId());
        assertEquals("DCODE", adapter.getHarnessType());
        assertTrue(adapter.getCapabilities().contains("CODE_IMPLEMENTATION"));
        assertTrue(adapter.getCapabilityDescriptor().features().get("cancellation"));
    }

    @Test
    void connectionTestAgainstStub() {
        assertTrue(adapter().testConnection().success());
    }

    @Test
    void connectionTestFailsWhenUnreachable() {
        DCodeHarnessAdapter adapter = new DCodeHarnessAdapter();
        adapter.updateConfiguration(Map.of("endpoint", "http://127.0.0.1:1",
                "timeoutSeconds", "5"));
        assertFalse(adapter.testConnection().success());
    }

    @Test
    void executeCompletesWithFilesDiffAndEvents() {
        DCodeHarnessAdapter adapter = adapter();
        RecordingSink sink = new RecordingSink();
        HarnessExecutionResult result = adapter.execute(new HarnessExecutionRequest(
                "task-1", tempDir, "instruction", "prompt", null, null, false,
                Duration.ofMinutes(1), () -> false), sink);
        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.handled());
        assertEquals(1, result.modifiedFiles().size());
        assertEquals("src/Changed.java", result.modifiedFiles().get(0));
        assertTrue(result.diffText().contains("diff --git"));
        assertTrue(result.summary().contains("stub summary"));
        assertTrue(sink.types.contains(ExecutionEventType.EXECUTION_STARTED));
        assertTrue(sink.types.contains(ExecutionEventType.EXECUTION_COMPLETED));
    }

    @Test
    void waitingForApprovalIsCompletedWithPendingReview() {
        stubState.set("WAITING_FOR_APPROVAL");
        HarnessExecutionResult result = adapter().execute(new HarnessExecutionRequest(
                "task-1", tempDir, "instruction", "prompt", null, null, false,
                Duration.ofMinutes(1), () -> false), new RecordingSink());
        assertTrue(result.success());
        assertTrue(result.summary().contains("pending review"));
    }

    @Test
    void failurePropagates() {
        stubState.set("FAILED");
        HarnessExecutionResult result = adapter().execute(new HarnessExecutionRequest(
                "task-1", tempDir, "instruction", "prompt", null, null, false,
                Duration.ofMinutes(1), () -> false), new RecordingSink());
        assertFalse(result.success());
    }

    @Test
    void cancellationSendsCancelRequest() {
        stubState.set("RUNNING");
        DCodeHarnessAdapter adapter = adapter();
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancelFlag.set(true);
            stubState.set("CANCELLED");
        });
        canceller.setDaemon(true);
        canceller.start();
        HarnessExecutionResult result = adapter.execute(new HarnessExecutionRequest(
                "task-1", tempDir, "instruction", "prompt", null, null, false,
                Duration.ofMinutes(1), cancelFlag::get), new RecordingSink());
        assertTrue(result.cancelled());
        assertTrue(cancelReceived.get());
    }

    @Test
    void missingWorkspaceRejected() {
        HarnessExecutionResult result = adapter().execute(new HarnessExecutionRequest(
                "task-1", tempDir.resolve("missing-dir"), "instruction", null, null, null,
                false, Duration.ofMinutes(1), () -> false), new RecordingSink());
        assertTrue(result.handled());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("does not exist"));
    }

    @Test
    void configurationMasksToken() {
        DCodeHarnessAdapter adapter = new DCodeHarnessAdapter();
        adapter.updateConfiguration(Map.of("authToken", "topsecret"));
        assertFalse(adapter.getConnectionConfiguration().get("authToken")
                .contains("topsecret"));
    }
}
