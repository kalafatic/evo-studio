package eu.kalafatic.dstudio.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.kalafatic.dstudio.application.lifecycle.DStudioLifecycleCoordinator;
import eu.kalafatic.dstudio.application.lifecycle.DStudioLifecycleStage;
import eu.kalafatic.dstudio.harness.dcode.DCodeHarnessAdapter;
import eu.kalafatic.dstudio.infrastructure.config.DStudioPaths;
import eu.kalafatic.dstudio.teip.server.DStudioServer;
import eu.kalafatic.dstudio.teip.server.PortConflictException;
import eu.kalafatic.dstudio.teip.server.PortProbe;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 7 (S4 follow-up): port conflicts must surface as typed, actionable failures —
 * the desktop client resolves them interactively via {@code restartServerOnPort}, the
 * headless client reports and exits. Family port convention: EVO 28xxx, TRYST 38xxx,
 * EVO-Studio 48xxx (TEIP 48080, diagnostics 48180), EVO-Code 58xxx (TEIP 58080).
 */
class PortConflictHandlingTest {

    @TempDir
    Path tempDir;

    @Test
    void occupiedPortThrowsTypedConflict() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            int taken = occupied.getLocalPort();
            DStudioServer server = new DStudioServer("127.0.0.1", taken, null, null, null,
                    (exchange) -> exchange.close(), true, null, null, null);
            PortConflictException thrown = assertThrows(PortConflictException.class,
                    server::start);
            assertTrue(thrown.getMessage().contains(String.valueOf(taken)),
                    thrown.getMessage());
            assertEquals(taken, thrown.port());
        }
    }

    @Test
    void portProbeFindsFreePortAndSkipsOccupied() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            int taken = occupied.getLocalPort();
            int free = PortProbe.findFreePort("127.0.0.1", taken, 50);
            assertNotEquals(-1, free);
            assertNotEquals(taken, free);
            try (ServerSocket verify = new ServerSocket()) {
                verify.bind(new InetSocketAddress("127.0.0.1", free));
            }
        }
    }

    @Test
    void restartServerOnPortPersistsAndServes() throws Exception {
        DStudioPaths paths = new DStudioPaths(tempDir.resolve("dstudio-home"));
        Files.createDirectories(paths.getConfigurationDirectory());
        Files.writeString(paths.getPropertiesFile(), "teip.devMode=true\nteip.port=0\n");
        DStudioLifecycleCoordinator coordinator = new DStudioLifecycleCoordinator(paths);
        coordinator.advance(DStudioLifecycleStage.APPLICATION_READY);
        int firstPort = coordinator.getServer().getBoundPort();
        int newPort = PortProbe.findFreePort("127.0.0.1", firstPort + 1, 100);
        coordinator.restartServerOnPort(newPort);
        assertTrue(coordinator.getServer().isRunning());
        assertEquals(newPort, coordinator.getServer().getBoundPort());

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> status = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + newPort + "/api/status"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, status.statusCode());
        assertTrue(status.body().contains("DSTUDIO"));

        String properties = Files.readString(paths.getPropertiesFile());
        assertTrue(properties.contains("teip.port=" + newPort),
                "chosen port must be persisted for the next launch");
        coordinator.advance(DStudioLifecycleStage.APPLICATION_TERMINATED);
    }

    @Test
    void familyPortConventions() {
        assertEquals(48080, eu.kalafatic.dstudio.core.config.DStudioConfiguration.DEFAULT_TEIP_PORT);
        assertEquals(48180, eu.kalafatic.dstudio.core.config.DStudioConfiguration.DEFAULT_DIAGNOSTICS_PORT);
        assertEquals("http://localhost:58080", DCodeHarnessAdapter.DEFAULT_ENDPOINT);
    }

    @Test
    void legacyPersistedPortsMigrateToNewFamily() throws Exception {
        DStudioPaths paths = new DStudioPaths(tempDir.resolve("dstudio-legacy"));
        Files.createDirectories(paths.getConfigurationDirectory());
        // persisted configuration from the transient 58xxx convention that collided
        // with EVO-Code's primary port
        Files.writeString(paths.getPropertiesFile(),
                "teip.port=58080\ndiagnostics.port=58180\n");
        DStudioLifecycleCoordinator coordinator = new DStudioLifecycleCoordinator(paths);
        coordinator.advance(DStudioLifecycleStage.CONFIGURATION_INITIALIZATION);
        var configuration = coordinator.getConfigurationService().getConfiguration();
        assertEquals(48080, configuration.teipPort());
        assertEquals(48180, configuration.diagnosticsPort());
        // the corrected values are persisted for the next launch
        String properties = Files.readString(paths.getPropertiesFile());
        assertTrue(properties.contains("teip.port=48080"), properties);
        assertTrue(properties.contains("diagnostics.port=48180"), properties);
    }

    @Test
    void familyConventionPortsArePreserved() throws Exception {
        DStudioPaths paths = new DStudioPaths(tempDir.resolve("dstudio-convention"));
        Files.createDirectories(paths.getConfigurationDirectory());
        // persisted configuration already on the family convention stays unchanged
        Files.writeString(paths.getPropertiesFile(),
                "teip.port=48080\ndiagnostics.port=48180\n");
        DStudioLifecycleCoordinator coordinator = new DStudioLifecycleCoordinator(paths);
        coordinator.advance(DStudioLifecycleStage.CONFIGURATION_INITIALIZATION);
        var configuration = coordinator.getConfigurationService().getConfiguration();
        assertEquals(48080, configuration.teipPort());
        assertEquals(48180, configuration.diagnosticsPort());
    }
}
