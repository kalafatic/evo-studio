package eu.kalafatic.dstudio.teip.server;

import eu.kalafatic.dstudio.core.model.ExecutionTask;
import eu.kalafatic.dstudio.core.service.ExecutionService;
import eu.kalafatic.dstudio.core.service.ProjectService;
import eu.kalafatic.dstudio.core.harness.HarnessRegistry;
import eu.kalafatic.dstudio.core.inference.InferenceEngineFactory;
import eu.kalafatic.dstudio.core.inference.InferenceEngineRegistry;
import eu.kalafatic.dstudio.core.inference.InferenceService;
import eu.kalafatic.dstudio.core.event.EventStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EVO-Studio backend HTTP server: the TEIP 1.1 provider surface plus the EVO-Studio API
 * surface ({@code /api/status}, {@code /api/diagnostics}, {@code /api/inference},
 * {@code /api/events}). Every endpoint uses the same bearer-token/dev-mode
 * authentication; secrets never appear in any response payload.
 */
public class DStudioServer {

    public static final String STATUS_PATH = "/api/status";
    /** Frontend revision the web UI reports; must match web/common.js
     *  FRONTEND_BUILD (asserted by FrontendDeliveryTest). */
    public static final String FRONTEND_VERSION = "web-20261127-r6";
    public static final String DIAGNOSTICS_PATH = "/api/diagnostics";
    public static final String INFERENCE_PATH = InferenceHttpHandler.PATH;
    public static final String EVENTS_PATH = EventsSseHandler.PATH;

    private final String bindAddress;
    private final int port;
    private final HarnessRegistry harnessRegistry;
    private final ExecutionService executionService;
    private final ProjectService projectService;
    private final InferenceEngineRegistry inferenceEngineRegistry;
    private final HttpHandler teipHandler;
    private final boolean devModeConfigured;
    private final EventStream eventStream;
    private final InferenceService inferenceService;
    private final InferenceEngineFactory engineFactory;
    private final eu.kalafatic.dstudio.core.contextual.ContextualActionRegistry
            contextualRegistry;
    private final eu.kalafatic.dstudio.core.agent.AgentProfileRegistry
            agentProfileRegistry;
    private final eu.kalafatic.dstudio.core.service.ConfigurationService
            configurationService;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpServer server;
    private volatile EventsSseHandler eventsHandler;

    public DStudioServer(String bindAddress, int port, HarnessRegistry harnessRegistry,
            ExecutionService executionService, InferenceEngineRegistry inferenceEngineRegistry,
            HttpHandler teipHandler, boolean devModeConfigured, EventStream eventStream,
            InferenceService inferenceService, InferenceEngineFactory engineFactory) {
        this(bindAddress, port, harnessRegistry, executionService, null,
                inferenceEngineRegistry, teipHandler, devModeConfigured, eventStream,
                inferenceService, engineFactory);
    }

    public DStudioServer(String bindAddress, int port, HarnessRegistry harnessRegistry,
            ExecutionService executionService, ProjectService projectService,
            InferenceEngineRegistry inferenceEngineRegistry, HttpHandler teipHandler,
            boolean devModeConfigured, EventStream eventStream,
            InferenceService inferenceService, InferenceEngineFactory engineFactory) {
        this(bindAddress, port, harnessRegistry, executionService, projectService,
                inferenceEngineRegistry, teipHandler, devModeConfigured, eventStream,
                inferenceService, engineFactory, null, null, null);
    }

    /**
     * Full constructor with the unified contextual action framework wiring
     * (registry + agent profiles + configuration service for WEB-surface actions).
     */
    public DStudioServer(String bindAddress, int port, HarnessRegistry harnessRegistry,
            ExecutionService executionService, ProjectService projectService,
            InferenceEngineRegistry inferenceEngineRegistry, HttpHandler teipHandler,
            boolean devModeConfigured, EventStream eventStream,
            InferenceService inferenceService, InferenceEngineFactory engineFactory,
            eu.kalafatic.dstudio.core.contextual.ContextualActionRegistry contextualRegistry,
            eu.kalafatic.dstudio.core.agent.AgentProfileRegistry agentProfileRegistry,
            eu.kalafatic.dstudio.core.service.ConfigurationService configurationService) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.harnessRegistry = harnessRegistry;
        this.executionService = executionService;
        this.projectService = projectService;
        this.inferenceEngineRegistry = inferenceEngineRegistry;
        this.teipHandler = teipHandler;
        this.devModeConfigured = devModeConfigured;
        this.eventStream = eventStream;
        this.inferenceService = inferenceService;
        this.engineFactory = engineFactory;
        this.contextualRegistry = contextualRegistry;
        this.agentProfileRegistry = agentProfileRegistry;
        this.configurationService = configurationService;
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            server.createContext("/teip/v1", teipHandler);
            server.createContext(STATUS_PATH, exchange -> {
                if (!authenticate(exchange)) {
                    return;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("application", "DSTUDIO");
                body.put("version", eu.kalafatic.dstudio.core.config.DStudioConfiguration.APP_VERSION);
                body.put("status", "UP");
                body.put("frontendVersion", FRONTEND_VERSION);
                // Diagnostic only — never selects a serving path or implementation.
                body.put("resourceSource", "osgi-bundle");
                body.put("executionMode", executionMode());
                body.put("ready", true);
                body.put("timestamp", Instant.now().toString());
                respond(exchange, 200, body);
            });
            server.createContext(DIAGNOSTICS_PATH, exchange -> {
                if (!authenticate(exchange)) {
                    return;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("application", "DSTUDIO");
                Optional<String> selected = harnessRegistry == null
                        ? Optional.empty() : harnessRegistry.getSelectedDefaultHarnessId();
                body.put("defaultHarness", selected.orElse(""));
                body.put("harnesses", harnessRegistry == null ? List.of()
                        : harnessRegistry.listHarnesses().stream()
                                .map(h -> {
                                    Map<String, Object> entry = new LinkedHashMap<>();
                                    entry.put("id", h.getId());
                                    entry.put("type", h.getType());
                                    entry.put("enabled", h.isEnabled());
                                    entry.put("default",
                                            h.getId().equals(selected.orElse("")));
                                    entry.put("status", h.getStatus().name());
                                    // harness/runtime compatibility: provider types this
                                    // harness can actually run on (never shown as
                                    // selectable when incompatible)
                                    harnessRegistry.aggregateCapabilities(h.getId())
                                            .forEach(caps -> entry.put("providerTypes",
                                                    caps.supportedProviderTypes() == null
                                                            ? List.of()
                                                            : caps.supportedProviderTypes()));
                                    return entry;
                                })
                                .toList());
                List<ExecutionTask> active = executionService == null ? List.of()
                        : executionService.listActiveTasks();
                body.put("activeExecutions", active.size());
                body.put("executions", active.stream()
                        .map(t -> Map.of("id", t.getId(), "harnessId", t.getHarnessId(),
                                "status", t.getStatus().name()))
                        .toList());
                respond(exchange, 200, body);
            });
            // Phase 6 headless surface: inference engines, model discovery, model
            // lifecycle, runtime status and chat (ENR default; alternatives selectable).
            if (inferenceEngineRegistry != null) {
                server.createContext(INFERENCE_PATH,
                        new InferenceHttpHandler(inferenceEngineRegistry, inferenceService,
                                engineFactory, devModeConfigured));
                // Unified model management (provider pull/list/remove + shared
                // download jobs), delegated to the family ENR service. Served to
                // both the web UI and the RCP Model Manager view.
                server.createContext(ModelManagementHttpHandler.PATH,
                        new ModelManagementHttpHandler(inferenceEngineRegistry,
                                devModeConfigured));
            }
            // Phase 7: global SSE channel for live session/task/inference events.
            if (eventStream != null) {
                eventsHandler = new EventsSseHandler(eventStream, devModeConfigured);
                server.createContext(EVENTS_PATH, eventsHandler);
            }
            // Phase 7 (S6): workspace/file API for the Web IDE + embedded frontend.
            if (projectService != null) {
                server.createContext(WorkspaceHttpHandler.PATH,
                        new WorkspaceHttpHandler(projectService, devModeConfigured));
            }
            // Unified contextual actions web surface (same registry/actions as RCP).
            if (contextualRegistry != null) {
                server.createContext(ContextualHttpHandler.PATH,
                        new ContextualHttpHandler(contextualRegistry, harnessRegistry,
                                inferenceEngineRegistry, engineFactory,
                                agentProfileRegistry, configurationService,
                                executionService, projectService, devModeConfigured));
            }
            server.createContext("/", new WebAssetsHandler());
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "dstudio-http");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
        } catch (java.net.BindException e) {
            server = null;
            throw new PortConflictException(bindAddress, port, e);
        } catch (IOException e) {
            server = null;
            throw new IllegalStateException("Failed to start EVO-Studio server on "
                    + bindAddress + ":" + port + ": " + e.getMessage(), e);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            if (eventsHandler != null) {
                eventsHandler.shutdown();
            }
            server.stop(2);
            server = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public int getBoundPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /** Shared API authentication; writes the 401 envelope and returns false when denied. */
    /** Diagnostic only: the Eclipse PDE launcher sets this JVM property. */
    private static String executionMode() {
        return System.getProperty("eclipse.pde.launch") != null ? "pde" : "product";
    }

    private boolean authenticate(com.sun.net.httpserver.HttpExchange exchange)            throws IOException {
        try {
            ApiAuth.authenticate(exchange, devModeConfigured);
            return true;
        } catch (ApiAuth.ApiAuthException e) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("code", ApiAuth.ERROR_CODE);
            envelope.put("message", e.getMessage());
            respond(exchange, 401, envelope);
            return false;
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
            Map<String, Object> body) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }
}
