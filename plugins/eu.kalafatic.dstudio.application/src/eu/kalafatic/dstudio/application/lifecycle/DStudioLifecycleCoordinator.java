package eu.kalafatic.dstudio.application.lifecycle;

import eu.kalafatic.dstudio.application.DStudioInferenceEngineFactory;
import eu.kalafatic.dstudio.core.config.DStudioConfiguration;
import eu.kalafatic.dstudio.core.event.DefaultEventBus;
import eu.kalafatic.dstudio.core.event.DefaultEventStream;
import eu.kalafatic.dstudio.core.event.EventBus;
import eu.kalafatic.dstudio.core.event.EventStream;
import eu.kalafatic.dstudio.core.harness.DefaultHarnessRegistry;
import eu.kalafatic.dstudio.core.harness.HarnessRegistry;
import eu.kalafatic.dstudio.core.inference.DefaultInferenceEngineRegistry;
import eu.kalafatic.dstudio.core.inference.DefaultInferenceService;
import eu.kalafatic.dstudio.core.inference.InferenceEngineRegistry;
import eu.kalafatic.dstudio.core.inference.InferenceService;
import eu.kalafatic.dstudio.core.service.ConfigurationService;
import eu.kalafatic.dstudio.core.service.DefaultExecutionService;
import eu.kalafatic.dstudio.core.service.DefaultProjectService;
import eu.kalafatic.dstudio.core.service.DefaultReviewService;
import eu.kalafatic.dstudio.core.service.ExecutionService;
import eu.kalafatic.dstudio.core.service.ProjectService;
import eu.kalafatic.dstudio.core.service.ReviewService;
import eu.kalafatic.dstudio.core.service.WorkspaceDiffService;
import eu.kalafatic.dstudio.core.store.AgentProfileStore;
import eu.kalafatic.dstudio.core.store.ExecutionConfigurationStore;
import eu.kalafatic.dstudio.core.store.ExecutionStore;
import eu.kalafatic.dstudio.core.store.HarnessStore;
import eu.kalafatic.dstudio.core.store.InferenceEngineStore;
import eu.kalafatic.dstudio.core.store.InferenceSessionStore;
import eu.kalafatic.dstudio.core.store.ProjectStore;
import eu.kalafatic.dstudio.core.store.ReviewStore;
import eu.kalafatic.dstudio.core.agent.DefaultAgentProfileRegistry;
import eu.kalafatic.dstudio.core.config.DefaultExecutionConfigurationService;
import eu.kalafatic.dstudio.infrastructure.config.DStudioConfigurationServiceImpl;
import eu.kalafatic.dstudio.infrastructure.config.DStudioPaths;
import eu.kalafatic.dstudio.infrastructure.git.JGitWorkspaceDiffService;
import eu.kalafatic.dstudio.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.dstudio.infrastructure.persistence.SecretCodec;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteExecutionConfigurationStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteExecutionStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteHarnessStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteInferenceEngineStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteInferenceSessionStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteAgentProfileStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteProjectStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteReviewStore;
import eu.kalafatic.dstudio.harness.aider.AiderHarnessAdapter;
import eu.kalafatic.dstudio.harness.opencode.OpenCodeHarnessAdapter;
import eu.kalafatic.dstudio.teip.server.DStudioServer;
import eu.kalafatic.dstudio.teip.server.TeipExecutionManager;
import eu.kalafatic.dstudio.teip.server.TeipHttpHandler;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class DStudioLifecycleCoordinator {

    public interface LifecycleListener {
        void stageReached(DStudioLifecycleStage stage);
    }

    private final DStudioPaths paths;
    private final List<LifecycleListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> shutdownHooks = new ArrayList<>();
    private volatile DStudioLifecycleStage stage = DStudioLifecycleStage.APPLICATION_START;
    private volatile SqliteDatabaseManager databaseManager;
    private volatile ConfigurationService configurationService;
    private volatile EventBus eventBus;
    private volatile EventStream eventStream;
    private volatile HarnessRegistry harnessRegistry;
    private volatile InferenceEngineRegistry inferenceEngineRegistry;
    private volatile InferenceService inferenceService;
    private volatile ExecutionService executionService;
    private volatile ProjectService projectService;
    private volatile ReviewService reviewService;
    private volatile WorkspaceDiffService diffService;
    private volatile eu.kalafatic.dstudio.core.agent.AgentProfileRegistry agentProfileRegistry;
    private volatile eu.kalafatic.dstudio.core.config.ExecutionConfigurationService
            executionConfigurationService;
    private volatile eu.kalafatic.dstudio.core.contextual.ContextualActionRegistry
            contextualActionRegistry;
    private volatile eu.kalafatic.dstudio.core.inference.InferenceEngineFactory
            inferenceEngineFactory;
    private volatile DStudioConfiguration configuration;
    private volatile DStudioServer server;

    public DStudioLifecycleCoordinator() {
        this(new DStudioPaths());
    }

    public DStudioLifecycleCoordinator(DStudioPaths paths) {
        this.paths = paths;
    }

    public DStudioPaths getPaths() {
        return paths;
    }

    public DStudioLifecycleStage getStage() {
        return stage;
    }

    public void addListener(LifecycleListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void onShutdown(Runnable hook) {
        synchronized (shutdownHooks) {
            shutdownHooks.add(Objects.requireNonNull(hook));
        }
    }

    public void advance(DStudioLifecycleStage target) {
        while (stage.ordinal() < target.ordinal()) {
            DStudioLifecycleStage next = DStudioLifecycleStage.values()[stage.ordinal() + 1];
            runStage(next);
        }
    }

    /**
     * Termination for startup-failure paths: runs the shutdown stages WITHOUT re-running
     * the stage that failed (a plain {@link #advance(APPLICATION_TERMINATED)} would retry
     * the failed APPLICATION_READY stage and rethrow the original failure).
     */
    public synchronized void terminateAfterStartupFailure() {
        if (stage == DStudioLifecycleStage.APPLICATION_TERMINATED) {
            return;
        }
        if (stage.ordinal() < DStudioLifecycleStage.APPLICATION_READY.ordinal()) {
            runStage(DStudioLifecycleStage.APPLICATION_SHUTDOWN);
            stage = DStudioLifecycleStage.APPLICATION_SHUTDOWN;
            notifyListeners(DStudioLifecycleStage.APPLICATION_SHUTDOWN);
        }
        advance(DStudioLifecycleStage.APPLICATION_TERMINATED);
    }

    private void runStage(DStudioLifecycleStage next) {
        switch (next) {
            case CONFIGURATION_INITIALIZATION -> onConfigurationInitialization();
            case CORE_SERVICE_INITIALIZATION -> onCoreServiceInitialization();
            case APPLICATION_READY -> onApplicationReady();
            case WORKBENCH_RUNNING -> onWorkbenchRunning();
            case APPLICATION_SHUTDOWN -> onApplicationShutdown();
            case SERVICE_DISPOSAL -> onServiceDisposal();
            case APPLICATION_TERMINATED -> onApplicationTerminated();
            default -> {
            }
        }
        stage = next;
        notifyListeners(next);
    }

    private void onConfigurationInitialization() {
        try {
            Files.createDirectories(paths.getConfigurationDirectory());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create EVO-Studio configuration directory: "
                    + e.getMessage(), e);
        }
        configurationService = new DStudioConfigurationServiceImpl(paths);
        configuration = configurationService.getConfiguration();
    }

    private void onCoreServiceInitialization() {
        databaseManager = new SqliteDatabaseManager(paths.getDatabaseFile());
        new SchemaMigrationService().migrate(databaseManager.connection());
        eventBus = new DefaultEventBus();
        eventStream = new DefaultEventStream(eventBus);
        SecretCodec secretCodec = new SecretCodec(paths.getConfigurationDirectory());
        HarnessStore harnessStore = new SqliteHarnessStore(databaseManager.connection(),
                secretCodec);
        ProjectStore projectStore = new SqliteProjectStore(databaseManager.connection());
        ExecutionStore executionStore = new SqliteExecutionStore(databaseManager.connection());
        ReviewStore reviewStore = new SqliteReviewStore(databaseManager.connection());
        harnessRegistry = new DefaultHarnessRegistry(harnessStore);
        diffService = new JGitWorkspaceDiffService();
        harnessRegistry.registerAdapter(new OpenCodeHarnessAdapter(diffService, paths));
        harnessRegistry.registerAdapter(new AiderHarnessAdapter(diffService));
        // Phase 7 (S2): Claude Code harness (Anthropic-native; live tests skip when the
        // CLI is absent on this host).
        harnessRegistry.registerAdapter(
                new eu.kalafatic.dstudio.harness.claude.ClaudeCodeHarnessAdapter(diffService));
        // Phase 6: EVO-Code is the default family coding harness.
        harnessRegistry.registerAdapter(new eu.kalafatic.dstudio.harness.dcode.DCodeHarnessAdapter());
        if (harnessRegistry.getSelectedDefaultHarnessId().isEmpty()) {
            harnessRegistry.selectDefaultHarness(
                    eu.kalafatic.dstudio.harness.dcode.DCodeHarnessAdapter.HARNESS_ID);
        }
        if (configuration.defaultModel() != null
                && !configuration.defaultModel().isBlank()) {
            Map<String, String> aiderConfig = harnessRegistry.getConfiguration(
                    AiderHarnessAdapter.HARNESS_ID);
            if (aiderConfig.getOrDefault("model", "").isBlank()) {
                Map<String, String> updated = new java.util.LinkedHashMap<>(aiderConfig);
                updated.put("model", configuration.defaultModel());
                harnessRegistry.updateConfiguration(AiderHarnessAdapter.HARNESS_ID, updated);
            }
        }
        // Phase 6: inference-engine registry — ENR is the default family inference engine,
        // Ollama stays registered as an alternative. Harnesses and inference engines are
        // independently configurable and replaceable.
        InferenceEngineStore engineStore = new SqliteInferenceEngineStore(
                databaseManager.connection(), secretCodec);
        inferenceEngineRegistry = new DefaultInferenceEngineRegistry(engineStore);
        inferenceEngineRegistry.registerEngine(
                new eu.kalafatic.dstudio.harness.enr.EnrInferenceEngineAdapter());
        resolveEnrEndpoint(inferenceEngineRegistry);
        inferenceEngineRegistry.registerEngine(
                new eu.kalafatic.dstudio.harness.ollama.OllamaInferenceEngineAdapter());
        // Phase 7 (S3): OpenAI-compatible (default endpoint = local Ollama /v1, live-
        // verified) and Anthropic (wire-tested; live requires an API key) engines.
        inferenceEngineRegistry.registerEngine(
                new eu.kalafatic.dstudio.harness.openai
                        .OpenAiCompatibleInferenceEngineAdapter());
        inferenceEngineRegistry.registerEngine(
                new eu.kalafatic.dstudio.harness.anthropic
                        .AnthropicInferenceEngineAdapter());
        if (inferenceEngineRegistry.getSelectedDefaultEngineId().isEmpty()) {
            inferenceEngineRegistry.selectDefaultEngine(
                    eu.kalafatic.dstudio.harness.enr.EnrInferenceEngineAdapter.ENGINE_ID);
        }
        InferenceSessionStore sessionStore = new SqliteInferenceSessionStore(
                databaseManager.connection());
        inferenceService = new DefaultInferenceService(inferenceEngineRegistry, sessionStore,
                eventBus);
        // Unified multi-agent control platform: agent profiles, scope-based execution
        // configuration and immutable execution snapshots (schema V4).
        AgentProfileStore agentProfileStore = new SqliteAgentProfileStore(
                databaseManager.connection());
        agentProfileRegistry = new DefaultAgentProfileRegistry(agentProfileStore,
                harnessRegistry, inferenceEngineRegistry);
        ExecutionConfigurationStore executionConfigStore =
                new SqliteExecutionConfigurationStore(databaseManager.connection());
        executionConfigurationService = new DefaultExecutionConfigurationService(
                executionConfigStore);
        projectService = new DefaultProjectService(projectStore);
        reviewService = new DefaultReviewService(reviewStore);
        executionService = new DefaultExecutionService(harnessRegistry, executionStore,
                reviewStore, diffService, eventBus, agentProfileRegistry,
                inferenceEngineRegistry, executionConfigurationService);
        executionService.reconcileAfterRestart();

        // Unified contextual action framework (Darwin Family Contextual Action Contract
        // v1.0): one registry, domain providers for availability, surface-independent
        // handlers for execution. RCP and Web surfaces resolve and execute through this
        // single authority.
        inferenceEngineFactory = new DStudioInferenceEngineFactory();
        contextualActionRegistry =
                new eu.kalafatic.dstudio.core.contextual.DefaultContextualActionRegistry();
        contextualActionRegistry.registerProvider(
                new eu.kalafatic.dstudio.core.contextual.providers
                        .HarnessContextActionProvider(harnessRegistry));
        contextualActionRegistry.registerProvider(
                new eu.kalafatic.dstudio.core.contextual.providers
                        .ProviderContextActionProvider(inferenceEngineRegistry));
        contextualActionRegistry.registerProvider(
                new eu.kalafatic.dstudio.core.contextual.providers
                        .ModelContextActionProvider(inferenceEngineRegistry));
        contextualActionRegistry.registerProvider(
                new eu.kalafatic.dstudio.core.contextual.providers
                        .AgentContextActionProvider(agentProfileRegistry));
        contextualActionRegistry.registerProvider(
                new eu.kalafatic.dstudio.core.contextual.providers
                        .ExecutionContextActionProvider(executionService, inferenceService));
        contextualActionRegistry.registerProvider(
                new eu.kalafatic.dstudio.core.contextual.providers
                        .ProjectContextActionProvider(projectService, harnessRegistry));
        eu.kalafatic.dstudio.core.contextual.handlers.CoreContextualActions.install(
                contextualActionRegistry, harnessRegistry, inferenceEngineRegistry,
                configurationService, agentProfileRegistry, executionService,
                inferenceService, projectService);
    }

    /**
     * Resolves the ENR inference-engine endpoint against the running family
     * environment (family convention, mirror of the EVO-Code RuntimeManager):
     * the {@code dstudio.enr.endpoint} system property always wins; otherwise the
     * authoritative ENR API endpoint (28780) is used. Existing installations
     * keeping the historical default are migrated when the default is unreachable
     * while a candidate answers; explicitly configured non-default endpoints are
     * never touched. The resolved endpoint is persisted through the engine
     * registry so every consumer (adapter, model management passthrough) follows it.
     */
    private void resolveEnrEndpoint(InferenceEngineRegistry registry) {
        String configured = System.getProperty("dstudio.enr.endpoint");
        if (configured != null && !configured.isBlank()) {
            registry.updateConfiguration(
                    eu.kalafatic.dstudio.harness.enr.EnrInferenceEngineAdapter.ENGINE_ID,
                    Map.of("endpoint", configured.trim()));
            return;
        }
        String endpoint = registry.getConfiguration(
                        eu.kalafatic.dstudio.harness.enr.EnrInferenceEngineAdapter.ENGINE_ID)
                .getOrDefault("endpoint",
                        eu.kalafatic.dstudio.harness.enr.EnrInferenceEngineAdapter
                                .DEFAULT_ENDPOINT);
        if (enrAnswers(endpoint)) {
            return;
        }
        if (!eu.kalafatic.dstudio.harness.enr.EnrInferenceEngineAdapter.DEFAULT_ENDPOINT
                .equals(endpoint)) {
            return; // unreachable user-configured endpoint: leave it untouched
        }
        for (String candidate : List.of("http://127.0.0.1:28780")) {
            if (candidate.equals(endpoint) || !enrAnswers(candidate)) {
                continue;
            }
            registry.updateConfiguration(
                    eu.kalafatic.dstudio.harness.enr.EnrInferenceEngineAdapter.ENGINE_ID,
                    Map.of("endpoint", candidate));
            return;
        }
    }

    private static boolean enrAnswers(String endpoint) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(endpoint + "/enr/v1/health"))
                    .timeout(java.time.Duration.ofSeconds(3)).GET().build();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient
                    .newHttpClient().send(request,
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void onApplicationReady() {
        startServer(configuration.teipPort());
    }

    /**
     * Starts (or restarts) the backend HTTP server on the given port and persists the
     * new port in the user configuration so the choice survives restarts. Used by the
     * interactive desktop port-conflict resolution.
     */
    public synchronized void restartServerOnPort(int port) {
        DStudioConfiguration updated = new DStudioConfiguration(configuration.appName(),
                configuration.appVersion(), configuration.configurationDirectory(),
                configuration.teipBindAddress(), port, configuration.teipNetworkMode(),
                configuration.diagnosticsPort(), configuration.teipDevMode(),
                configuration.defaultHarnessId(), configuration.defaultModel(),
                configuration.executionTimeoutSeconds(), configuration.evoMcpEndpoint(),
                configuration.evoMcpToken());
        configuration = updated;
        if (configurationService != null) {
            configurationService.saveConfiguration(updated);
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        startServer(port);
        if (stage.ordinal() < DStudioLifecycleStage.APPLICATION_READY.ordinal()) {
            // the initial APPLICATION_READY attempt failed: complete the stage here so
            // later monotonic advances (e.g. WORKBENCH_RUNNING) do not restart the server
            stage = DStudioLifecycleStage.APPLICATION_READY;
            notifyListeners(DStudioLifecycleStage.APPLICATION_READY);
        }
    }

    private void startServer(int port) {
        TeipExecutionManager teipManager = new TeipExecutionManager(harnessRegistry,
                executionService, reviewService, configuration, agentProfileRegistry);
        TeipHttpHandler teipHandler = new TeipHttpHandler(teipManager,
                configuration.teipDevMode());
        DStudioServer newServer = new DStudioServer(configuration.teipBindAddress(), port,
                harnessRegistry, executionService, projectService, inferenceEngineRegistry,
                teipHandler, configuration.teipDevMode(), eventStream, inferenceService,
                inferenceEngineFactory != null ? inferenceEngineFactory
                        : new DStudioInferenceEngineFactory(),
                contextualActionRegistry, agentProfileRegistry, configurationService);
        newServer.start();
        server = newServer;
        System.out.println("[EVO-Studio] Server listening on " + configuration.teipBindAddress()
                + ":" + port);
        onShutdown(() -> {
            DStudioServer current = server;
            if (current != null) {
                current.stop();
            }
        });
    }

    /**
     * Stops the TEIP server if it is running. Has no effect if the server is already
     * stopped or not initialized.
     */
    public void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
    private void onWorkbenchRunning() {
    }

    private void onApplicationShutdown() {
        if (executionService != null) {
            executionService.shutdown();
        }
    }

    private void onServiceDisposal() {
        synchronized (shutdownHooks) {
            for (Runnable hook : shutdownHooks) {
                try {
                    hook.run();
                } catch (RuntimeException e) {
                    System.err.println("EVO-Studio shutdown hook failed: " + e.getMessage());
                }
            }
            shutdownHooks.clear();
        }
        if (databaseManager != null) {
            databaseManager.close();
            databaseManager = null;
        }
    }

    private void onApplicationTerminated() {
    }

    private void notifyListeners(DStudioLifecycleStage reached) {
        for (LifecycleListener listener : listeners) {
            try {
                listener.stageReached(reached);
            } catch (RuntimeException e) {
                System.err.println("EVO-Studio lifecycle listener failed: " + e.getMessage());
            }
        }
    }

    public ConfigurationService getConfigurationService() {
        return configurationService;
    }

    public DStudioConfiguration getConfiguration() {
        return configuration;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public EventStream getEventStream() {
        return eventStream;
    }

    public InferenceService getInferenceService() {
        return inferenceService;
    }

    public HarnessRegistry getHarnessRegistry() {
        return harnessRegistry;
    }

    public InferenceEngineRegistry getInferenceEngineRegistry() {
        return inferenceEngineRegistry;
    }

    public ExecutionService getExecutionService() {
        return executionService;
    }

    public eu.kalafatic.dstudio.core.agent.AgentProfileRegistry getAgentProfileRegistry() {
        return agentProfileRegistry;
    }

    public eu.kalafatic.dstudio.core.config.ExecutionConfigurationService
            getExecutionConfigurationService() {
        return executionConfigurationService;
    }

    public ProjectService getProjectService() {
        return projectService;
    }

    public ReviewService getReviewService() {
        return reviewService;
    }

    public WorkspaceDiffService getDiffService() {
        return diffService;
    }

    public eu.kalafatic.dstudio.core.contextual.ContextualActionRegistry
            getContextualActionRegistry() {
        return contextualActionRegistry;
    }

    public eu.kalafatic.dstudio.core.inference.InferenceEngineFactory
            getInferenceEngineFactory() {
        return inferenceEngineFactory;
    }

    public SqliteDatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public DStudioServer getServer() {
        return server;
    }

    /**
     * Port the backend server is actually bound to; falls back to the configured TEIP
     * port and finally to the family default. Exposed so UI-bundle surfaces (web
     * interface / help launchers) resolve the live port without importing TEIP types
     * (layering ui → application → teip).
     */
    public int getBoundTeipPort() {
        DStudioServer current = server;
        if (current != null && current.isRunning() && current.getBoundPort() > 0) {
            return current.getBoundPort();
        }
        if (configuration != null && configuration.teipPort() > 0) {
            return configuration.teipPort();
        }
        return DStudioConfiguration.DEFAULT_TEIP_PORT;
    }
}
