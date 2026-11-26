package eu.kalafatic.dstudio.ui.editor;

import eu.kalafatic.dstudio.application.DStudioRuntime;
import eu.kalafatic.dstudio.application.lifecycle.DStudioLifecycleCoordinator;
import eu.kalafatic.dstudio.core.agent.AgentProfile;
import eu.kalafatic.dstudio.core.agent.AgentProfileRegistry;
import eu.kalafatic.dstudio.core.config.DStudioConfiguration;
import eu.kalafatic.dstudio.core.config.ExecutionConfiguration;
import eu.kalafatic.dstudio.core.config.ExecutionConfigurationService;
import eu.kalafatic.dstudio.core.harness.HarnessRegistry;
import eu.kalafatic.dstudio.core.model.HarnessDescriptor;
import eu.kalafatic.dstudio.core.inference.InferenceEngineAdapter;
import eu.kalafatic.dstudio.core.inference.InferenceEngineDescriptor;
import eu.kalafatic.dstudio.core.inference.InferenceEngineRegistry;
import eu.kalafatic.dstudio.ui.DStudioUiPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.browser.TitleEvent;
import org.eclipse.swt.browser.TitleListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.MultiPageEditorPart;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EVO-Studio harness editor — the main working area of the workbench, opened
 * automatically at startup.
 *
 * <ul>
 *   <li><b>Web</b> (initial page): an SWT Browser hosting the <em>actual</em> EVO-Studio
 *       web IDE over its HTTP endpoint — the same frontend, harness, inference,
 *       sessions, streaming and tool calling as an external browser. The editor
 *       contains no second frontend; it waits for the frontend to become reachable
 *       (bounded, non-blocking retry) and then navigates.</li>
 *   <li><b>Settings</b>: desktop-specific configuration page (the optional
 *       desktop-integration layer; core-service defaults/enablement via the existing
 *       bridge).</li>
 * </ul>
 *
 * <p>Layering is preserved: the editor consumes the coordinator's layering-safe
 * accessors ({@code getBoundTeipPort()}, {@code getConfiguration()}) — the ui bundle
 * never imports TEIP server types.</p>
 */
public final class DStudioMultiPageEditor extends MultiPageEditorPart {

    public static final String EDITOR_ID = "eu.kalafatic.dstudio.ui.editors.multi";
    private static final int READINESS_ATTEMPTS = 90;
    private static final long READINESS_INTERVAL_MILLIS = 1000;

    private final ExecutorService readinessExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "dstudio-editor-readiness");
                thread.setDaemon(true);
                return thread;
            });
    private final ExecutorService bridgeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "dstudio-editor-bridge");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong readinessGeneration = new AtomicLong();
    private volatile boolean disposed;
    private volatile boolean webLoaded;
    private volatile String resolvedWebUrl;

    private Browser webBrowser;
    private Label webStatus;
    private Button webReload;
    private Color errorColor;

    private Browser settingsBrowser;
    private boolean settingsLoaded;
    private volatile boolean settingsBrowserAlive;
    private volatile boolean settingsReady;
    private volatile String settingsDiag = "settings page not opened yet";

    @Override
    protected void createPages() {
        System.out.println("EVO-Studio: creating the harness editor pages "
                + "(Web + Settings)…");
        Composite webComposite = new Composite(getContainer(), SWT.NONE);
        webComposite.setLayout(new GridLayout(1, false));

        Composite webBar = new Composite(webComposite, SWT.NONE);
        webBar.setLayout(new GridLayout(3, false));
        webBar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        webStatus = new Label(webBar, SWT.NONE);
        webStatus.setText("Connecting to the EVO-Studio web IDE…");
        webStatus.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        Button openBrowser = new Button(webBar, SWT.PUSH);
        openBrowser.setText("Open in Browser");
        openBrowser.setToolTipText("Open the full EVO-Studio web IDE in your system browser");
        openBrowser.addListener(SWT.Selection,
                event -> eu.kalafatic.dstudio.ui.workbench.WebInterfaceLauncher
                        .openWebInterface());
        webReload = new Button(webBar, SWT.PUSH);
        webReload.setText("Reload");
        webReload.setToolTipText("Re-check the EVO-Studio web IDE and reload it");
        webReload.addListener(SWT.Selection, event -> reloadWeb());

        try {
            webBrowser = new Browser(webComposite, SWT.NONE);
            webBrowser.setLayoutData(new GridData(GridData.FILL_BOTH));
            installWebListeners(webBrowser);
        } catch (org.eclipse.swt.SWTError | RuntimeException error) {
            webBrowser = null;
            Label fallback = new Label(webComposite, SWT.WRAP);
            fallback.setLayoutData(new GridData(GridData.FILL_BOTH));
            fallback.setText("The embedded web IDE needs WebKit (SWT Browser).\n"
                    + "It could not be created on this system: " + error.getMessage());
            webStatus.setText("SWT Browser unavailable.");
        }
        addPage(webComposite);
        setPageText(0, "Web");

        Composite settingsComposite = new Composite(getContainer(), SWT.NONE);
        settingsComposite.setLayout(new GridLayout(1, false));
        addPage(settingsComposite);
        setPageText(1, "Settings");

        setActivePage(0);
        connectWebApplication();
    }

    @Override
    protected void pageChange(int newPageIndex) {
        super.pageChange(newPageIndex);
        if (newPageIndex == 1 && !settingsLoaded && settingsBrowser == null) {
            settingsLoaded = true;
            settingsDiag = "creating settings browser…";
            Composite parent = (Composite) getControl(1);
            try {
                settingsBrowser = new Browser(parent, SWT.NONE);
                settingsBrowser.setJavascriptEnabled(true);
                settingsBrowser.setLayoutData(new GridData(GridData.FILL_BOTH));
                new BrowserFunction(settingsBrowser, "dstudio") {
                    @Override
                    public Object function(Object[] arguments) {
                        handleSettingsCall(arguments);
                        return null;
                    }
                };
                settingsBrowser.addTitleListener(new org.eclipse.swt.browser.TitleListener() {
                    @Override
                    public void changed(org.eclipse.swt.browser.TitleEvent event) {
                        if (event.title != null
                                && event.title.contains("EVO-Studio Settings")) {
                            settingsReady = true;
                            settingsDiag = "settings page ready (title)";
                            System.out.println("EVO-Studio: settings page loaded.");
                        }
                    }
                });
                settingsBrowserAlive = true;
                settingsDiag = "waiting for the settings page endpoint…";
                loadSettingsPage();
            } catch (org.eclipse.swt.SWTError | RuntimeException error) {
                settingsDiag = "settings browser failed: " + error;
                Label fallback = new Label(parent, SWT.WRAP);
                fallback.setLayoutData(new GridData(GridData.FILL_BOTH));
                fallback.setText("The settings page needs WebKit (SWT Browser).\n"
                        + error.getMessage());
            }
            parent.layout(true, true);
        }
    }

    /**
     * The settings page is served by the EVO-Studio backend web surface (same server as
     * the web IDE), so it loads over HTTP — the bridge stays a desktop-only
     * integration on top. Bounded readiness wait, never blocking the UI thread.
     */
    private void loadSettingsPage() {
        long generation = readinessGeneration.incrementAndGet();
        readinessExecutor.execute(() -> {
            for (int attempt = 1; attempt <= READINESS_ATTEMPTS && !disposed
                    && generation == readinessGeneration.get(); attempt++) {
                String base = resolvedWebUrl != null ? resolvedWebUrl : resolveWebUrl();
                if (base != null && !base.isBlank()) {
                    String url = base.endsWith("/") ? base + "web/settings.html"
                            : base + "/web/settings.html";
                    if (frontendReachable(url)) {
                        async(() -> {
                            if (!disposed && settingsBrowser != null
                                    && !settingsBrowser.isDisposed()
                                    && generation == readinessGeneration.get()) {
                                settingsDiag = "settings page loading " + url;
                                settingsBrowser.setUrl(url);
                            }
                        });
                        return;
                    }
                }
                try {
                    Thread.sleep(READINESS_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            async(() -> settingsDiag = "settings page not reachable");
        });
    }

    // ---- Web application page ----

    private void installWebListeners(Browser browser) {
        browser.addLocationListener(new LocationListener() {
            @Override
            public void changing(LocationEvent event) {
                // normal navigation is allowed (same frontend as an external browser)
            }

            @Override
            public void changed(LocationEvent event) {
                String location = event.location;
                if (location == null || location.isBlank()) {
                    return;
                }
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    webLoaded = true;
                    System.out.println("EVO-Studio: web IDE navigated to " + location);
                }
            }
        });
        browser.addProgressListener(new ProgressListener() {
            @Override
            public void changed(ProgressEvent event) {
                // progressive state; completion is logged below
            }

            @Override
            public void completed(ProgressEvent event) {
                System.out.println("EVO-Studio: web IDE page loaded.");
            }
        });
        browser.addTitleListener(new TitleListener() {
            @Override
            public void changed(TitleEvent event) {
                if (webLoaded) {
                    setWebStatus(event.title == null || event.title.isBlank()
                            ? "Web IDE loaded." : event.title);
                }
                System.out.println("EVO-Studio: web IDE title: " + event.title);
            }
        });
    }

    /**
     * Non-blocking readiness sequence: resolve the configured web endpoint from the
     * coordinator (layering-safe), wait for the actual frontend availability (bounded
     * retry) and navigate the Browser on the UI thread.
     */
    private void connectWebApplication() {
        if (webBrowser == null || disposed) {
            return;
        }
        long generation = readinessGeneration.incrementAndGet();
        setWebStatus("Connecting to the EVO-Studio web IDE…");
        readinessExecutor.execute(() -> {
            for (int attempt = 1; attempt <= READINESS_ATTEMPTS && !disposed
                    && generation == readinessGeneration.get(); attempt++) {
                String url = resolveWebUrl();
                if (url != null && frontendReachable(url)) {
                    final String target = url;
                    async(() -> {
                        if (!disposed && webBrowser != null
                                && !webBrowser.isDisposed()
                                && generation == readinessGeneration.get()) {
                            resolvedWebUrl = target;
                            setWebStatus("Loading " + target + " …");
                            webBrowser.setUrl(target);
                            System.out.println("EVO-Studio: loading the web IDE from "
                                    + target);
                        }
                    });
                    return;
                }
                if (attempt % 10 == 0) {
                    System.out.println("EVO-Studio: waiting for the web IDE (attempt "
                            + attempt + "/" + READINESS_ATTEMPTS + ")");
                }
                try {
                    Thread.sleep(READINESS_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            final String endpoint = resolveWebUrl();
            async(() -> {
                if (disposed || generation != readinessGeneration.get()) {
                    return;
                }
                setWebStatus("The EVO-Studio web IDE is not reachable"
                        + (endpoint == null ? " (no endpoint)."
                                : " at " + endpoint + "."));
                System.err.println("EVO-Studio: web IDE did not become reachable at "
                        + (endpoint == null ? "no endpoint" : endpoint));
            });
        });
    }

    /** Resolves the web IDE base URL from the coordinator — never hardcoded. */
    private static String resolveWebUrl() {
        DStudioLifecycleCoordinator coordinator = DStudioRuntime.getCoordinator();
        if (coordinator == null) {
            return null;
        }
        int port = coordinator.getBoundTeipPort();
        if (port <= 0) {
            return null;
        }
        return "http://127.0.0.1:" + port + "/";
    }

    private boolean frontendReachable(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(4)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return false;
        }
    }

    /** Manual reload: re-runs the bounded readiness sequence. */
    public void reloadWeb() {
        System.out.println("EVO-Studio: manual reload of the web IDE requested.");
        webLoaded = false;
        connectWebApplication();
    }

    /** True once the Browser navigated to the real web IDE. */
    public boolean isWebLoaded() {
        return webLoaded;
    }

    /** The resolved web IDE URL (null until readiness succeeded). */
    public String webUrl() {
        return resolvedWebUrl;
    }

    private void setWebStatus(String text) {
        if (webStatus == null || webStatus.isDisposed()) {
            return;
        }
        if (text.contains("not reachable")) {
            if (errorColor == null || errorColor.isDisposed()) {
                errorColor = new Color(webStatus.getDisplay(), 200, 60, 60);
            }
            webStatus.setForeground(errorColor);
        } else {
            webStatus.setForeground(webStatus.getDisplay()
                    .getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        }
        webStatus.setText(text);
    }

    // ---- Settings page bridge ----

    private void handleSettingsCall(Object[] arguments) {
        if (arguments == null || arguments.length < 3 || disposed) {
            return;
        }
        String method = String.valueOf(arguments[0]);
        settingsDiag = "last bridge method: " + method;
        String argsJson = String.valueOf(arguments[1]);
        long callbackId = ((Number) arguments[2]).longValue();
        try {
            Map<String, Object> args = parseArgs(argsJson);
            dispatchSettings(method, args, callbackId);
        } catch (RuntimeException e) {
            settingsDiag = "dispatch failed: " + e;
            failSettings(callbackId, String.valueOf(e.getMessage()));
        }
    }

    private void dispatchSettings(String method, Map<String, Object> args,
                                  long callbackId) {
        switch (method) {
            case "ready" -> {
                settingsReady = true;
                settingsDiag = "ready";
                System.out.println("EVO-Studio: settings page loaded its "
                        + "configuration from the core services.");
            }
            case "jserror" -> {
                settingsDiag = "settings JS error: "
                        + String.valueOf(args.get("message")) + " @"
                        + args.get("line");
                System.err.println("EVO-Studio: " + settingsDiag);
            }
            case "boot" -> background(callbackId, this::bootSettings);
            case "setDefaultHarness" -> background(callbackId,
                    () -> {
                        HarnessRegistry registry = plugin().harnessRegistry();
                        if (registry == null) {
                            throw new IllegalStateException("harness registry unavailable");
                        }
                        registry.selectDefaultHarness(str(args, "harnessId"));
                        return obj("ok", true);
                    });
            case "setHarnessEnabled" -> background(callbackId,
                    () -> {
                        HarnessRegistry registry = plugin().harnessRegistry();
                        if (registry == null) {
                            throw new IllegalStateException("harness registry unavailable");
                        }
                        registry.setHarnessEnabled(str(args, "harnessId"),
                                bool(args, "enabled"));
                        return obj("ok", true);
                    });
            case "setDefaultEngine" -> background(callbackId,
                    () -> {
                        InferenceEngineRegistry registry = plugin().inferenceEngineRegistry();
                        if (registry == null) {
                            throw new IllegalStateException("engine registry unavailable");
                        }
                        registry.selectDefaultEngine(str(args, "engineId"));
                        return obj("ok", true);
                    });
            case "setEngineEnabled" -> background(callbackId,
                    () -> {
                        InferenceEngineRegistry registry = plugin().inferenceEngineRegistry();
                        if (registry == null) {
                            throw new IllegalStateException("engine registry unavailable");
                        }
                        registry.setEngineEnabled(str(args, "engineId"),
                                bool(args, "enabled"));
                        return obj("ok", true);
                    });
            case "setDefaultAgent" -> background(callbackId,
                    () -> {
                        AgentProfileRegistry registry = plugin().agentProfileRegistry();
                        if (registry == null) {
                            throw new IllegalStateException("agent registry unavailable");
                        }
                        registry.selectDefaultProfile(str(args, "profileId"));
                        return obj("ok", true);
                    });
            case "saveGlobalConfig" -> background(callbackId,
                    () -> saveGlobalConfig(args));
            case "testHarness" -> background(callbackId,
                    () -> {
                        HarnessRegistry registry = plugin().harnessRegistry();
                        if (registry == null) {
                            throw new IllegalStateException("harness registry unavailable");
                        }
                        var result = registry.testConnection(str(args, "harnessId"));
                        return obj("success", result.success(), "message", result.message());
                    });
            case "testEngine" -> background(callbackId,
                    () -> {
                        InferenceEngineRegistry registry = plugin().inferenceEngineRegistry();
                        if (registry == null) {
                            throw new IllegalStateException("engine registry unavailable");
                        }
                        var result = registry.testConnection(str(args, "engineId"));
                        return obj("success", result.success(), "message", result.message());
                    });
            case "restartServer" -> background(callbackId,
                    () -> {
                        DStudioLifecycleCoordinator coordinator = coordinator();
                        if (coordinator == null) {
                            throw new IllegalStateException("coordinator unavailable");
                        }
                        coordinator.restartServerOnPort(coordinator.getBoundTeipPort());
                        return obj("ok", true);
                    });
            case "stopServer" -> background(callbackId,
                    () -> {
                        DStudioLifecycleCoordinator coordinator = coordinator();
                        if (coordinator == null) {
                            throw new IllegalStateException("coordinator unavailable");
                        }
                        coordinator.stopServer();
                        return obj("ok", true);
                    });
            case "models" -> background(callbackId,
                    () -> models(str(args, "engineId")));
            default -> failSettings(callbackId, "unknown method: " + method);
        }
    }

    private Map<String, Object> bootSettings() {
        DStudioLifecycleCoordinator coordinator = coordinator();
        Map<String, Object> payload = obj("connection", connectionJson());
        payload.put("harnesses", harnessList());
        payload.put("engines", engineList());
        payload.put("agents", agentList());
        payload.put("config", configJson());
        payload.put("defaults", defaultsJson());
        ExecutionConfigurationService configService = plugin().executionConfigurationService();
        if (configService != null) {
            configService.getGlobal().ifPresent(config -> payload.put("globalConfig",
                    obj("harnessId", config.harnessId(),
                            "providerId", config.providerId(),
                            "modelId", config.modelId(),
                            "agentProfileId", config.agentProfileId())));
        }
        if (coordinator != null) {
            payload.put("stage", String.valueOf(coordinator.getStage()));
            payload.put("boundTeipPort", coordinator.getBoundTeipPort());
        }
        return payload;
    }

    private Map<String, Object> saveGlobalConfig(Map<String, Object> args) {
        ExecutionConfigurationService service = plugin().executionConfigurationService();
        if (service == null) {
            throw new IllegalStateException("configuration service unavailable");
        }
        service.saveGlobal(new ExecutionConfiguration(
                str(args, "harnessId"), str(args, "providerId"), str(args, "modelId"),
                str(args, "agentProfileId"), Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_GLOBAL));
        return obj("ok", true);
    }

    // ---- Entity JSON ----

    private List<Map<String, Object>> harnessList() {
        List<Map<String, Object>> list = new ArrayList<>();
        HarnessRegistry registry = plugin().harnessRegistry();
        if (registry != null) {
            String selected = registry.getSelectedDefaultHarnessId().orElse("");
            for (HarnessDescriptor descriptor : registry.listHarnesses()) {
                list.add(obj(
                        "id", descriptor.getId(),
                        "name", descriptor.getName(),
                        "type", descriptor.getType(),
                        "enabled", descriptor.isEnabled(),
                        "status", String.valueOf(descriptor.getStatus()),
                        "statusMessage", descriptor.getStatusMessage(),
                        "capabilities", descriptor.getCapabilities(),
                        "default", descriptor.getId().equals(selected)));
            }
        }
        return list;
    }

    private List<Map<String, Object>> engineList() {
        List<Map<String, Object>> list = new ArrayList<>();
        InferenceEngineRegistry registry = plugin().inferenceEngineRegistry();
        if (registry != null) {
            String selected = registry.getSelectedDefaultEngineId().orElse("");
            for (InferenceEngineDescriptor descriptor : registry.listEngines()) {
                list.add(obj(
                        "id", descriptor.getId(),
                        "name", descriptor.getName(),
                        "type", descriptor.getType(),
                        "enabled", descriptor.isEnabled(),
                        "status", String.valueOf(descriptor.getStatus()),
                        "statusMessage", descriptor.getStatusMessage(),
                        "default", descriptor.getId().equals(selected)));
            }
        }
        return list;
    }

    private List<Map<String, Object>> agentList() {
        List<Map<String, Object>> list = new ArrayList<>();
        AgentProfileRegistry registry = plugin().agentProfileRegistry();
        if (registry != null) {
            String selected = registry.getDefaultProfileId().orElse("");
            for (AgentProfile profile : registry.listProfiles()) {
                list.add(obj(
                        "id", profile.getId(),
                        "name", profile.getName(),
                        "role", profile.getRole(),
                        "description", profile.getDescription(),
                        "preferredHarnessId", profile.getPreferredHarnessId(),
                        "preferredProviderId", profile.getPreferredProviderId(),
                        "preferredModel", profile.getPreferredModel(),
                        "default", profile.getId().equals(selected)));
            }
        }
        return list;
    }

    private Map<String, Object> models(String engineId) {
        InferenceEngineRegistry engines = plugin().inferenceEngineRegistry();
        List<Map<String, Object>> models = new ArrayList<>();
        if (engines == null) {
            return obj("models", models);
        }
        List<InferenceEngineDescriptor> targets = engineId == null || engineId.isBlank()
                ? engines.listEngines()
                : engines.listEngines().stream()
                        .filter(e -> e.getId().equals(engineId))
                        .toList();
        for (InferenceEngineDescriptor descriptor : targets) {
            engines.findAdapter(descriptor.getId()).ifPresent(adapter -> {
                for (InferenceEngineAdapter.InferenceModel model : adapter.listModels()) {
                    models.add(obj(
                            "id", model.id(),
                            "name", model.name(),
                            "engineId", descriptor.getId(),
                            "engineName", descriptor.getName(),
                            "provider", model.provider(),
                            "sizeBytes", model.sizeBytes(),
                            "available", model.available()));
                }
            });
        }
        return obj("models", models);
    }

    private Map<String, Object> connectionJson() {
        DStudioLifecycleCoordinator coordinator = coordinator();
        DStudioConfiguration configuration = coordinator == null ? null
                : coordinator.getConfiguration();
        return obj(
                "stage", coordinator == null ? "?" : String.valueOf(coordinator.getStage()),
                "teipPort", coordinator == null
                        ? DStudioConfiguration.DEFAULT_TEIP_PORT
                        : coordinator.getBoundTeipPort(),
                "appName", configuration == null ? DStudioConfiguration.APP_NAME
                        : configuration.appName(),
                "appVersion", configuration == null ? DStudioConfiguration.APP_VERSION
                        : configuration.appVersion());
    }

    private Map<String, Object> configJson() {
        DStudioLifecycleCoordinator coordinator = coordinator();
        DStudioConfiguration configuration = coordinator == null ? null
                : coordinator.getConfiguration();
        return obj(
                "configDir", configuration == null ? "?"
                        : String.valueOf(configuration.configurationDirectory()),
                "bindAddress", configuration == null ? ""
                        : configuration.teipBindAddress(),
                "teipPort", configuration == null
                        ? DStudioConfiguration.DEFAULT_TEIP_PORT : configuration.teipPort(),
                "networkMode", configuration == null ? ""
                        : configuration.teipNetworkMode(),
                "diagnosticsPort", configuration == null
                        ? DStudioConfiguration.DEFAULT_DIAGNOSTICS_PORT
                        : configuration.diagnosticsPort(),
                "devMode", configuration != null && configuration.teipDevMode(),
                "defaultHarnessId", configuration == null ? ""
                        : configuration.defaultHarnessId(),
                "defaultModel", configuration == null ? ""
                        : configuration.defaultModel());
    }

    private Map<String, Object> defaultsJson() {
        HarnessRegistry harnesses = plugin().harnessRegistry();
        InferenceEngineRegistry engines = plugin().inferenceEngineRegistry();
        AgentProfileRegistry agents = plugin().agentProfileRegistry();
        return obj(
                "defaultHarnessId", harnesses == null ? ""
                        : harnesses.getSelectedDefaultHarnessId().orElse(""),
                "defaultEngineId", engines == null ? ""
                        : engines.getSelectedDefaultEngineId().orElse(""),
                "defaultProfileId", agents == null ? ""
                        : agents.getDefaultProfileId().orElse(""));
    }

    // ---- Bridge plumbing ----

    private void background(long callbackId,
                            java.util.function.Supplier<Map<String, Object>> work) {
        bridgeExecutor.execute(() -> {
            if (disposed) {
                return;
            }
            try {
                respondSettings(callbackId, work.get());
            } catch (RuntimeException e) {
                failSettings(callbackId, String.valueOf(e.getMessage()));
            }
        });
    }

    private void respondSettings(long callbackId, Map<String, Object> payload) {
        async(() -> {
            if (disposed || !settingsBrowserAlive || settingsBrowser == null
                    || settingsBrowser.isDisposed()) {
                return;
            }
            settingsBrowser.execute("window.dstudioResult(" + callbackId + ","
                    + (payload == null ? "null" : json(payload)) + ")");
        });
    }

    private void failSettings(long callbackId, String message) {
        settingsDiag = "bridge failure: " + message;
        async(() -> {
            if (disposed || !settingsBrowserAlive || settingsBrowser == null
                    || settingsBrowser.isDisposed()) {
                return;
            }
            settingsBrowser.execute("window.dstudioError(" + callbackId + ","
                    + json(obj("error", message)) + ")");
        });
    }

    private static void async(Runnable runnable) {
        Display display = Display.getCurrent();
        if (display != null) {
            runnable.run();
        } else {
            Display.getDefault().asyncExec(runnable);
        }
    }

    private static DStudioUiPlugin plugin() {
        return DStudioUiPlugin.getInstance();
    }

    private static DStudioLifecycleCoordinator coordinator() {
        return DStudioRuntime.getCoordinator();
    }

    private static String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Boolean b ? b : "true".equals(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String argsJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (argsJson == null || argsJson.isBlank() || "{}".equals(argsJson.trim())) {
            return result;
        }
        String body = argsJson.trim();
        if (!body.startsWith("{")) {
            return result;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}') {
                depth--;
            }
        }
        if (depth != 0) {
            return result;
        }
        for (String pair : body.substring(1, body.length() - 1).split(",")) {
            int colon = pair.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = pair.substring(0, colon).trim();
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            String value = pair.substring(colon + 1).trim();
            if (value.startsWith("\"")) {
                result.put(key, unquote(value));
            } else if ("true".equals(value)) {
                result.put(key, Boolean.TRUE);
            } else if ("false".equals(value)) {
                result.put(key, Boolean.FALSE);
            } else if ("null".equals(value)) {
                result.put(key, null);
            } else {
                result.put(key, Double.valueOf(value));
            }
        }
        return result;
    }

    private static String unquote(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() - 1) {
                char next = value.charAt(++i);
                builder.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    // ---- Minimal JSON writer (ui bundle has no Jackson dependency by design) ----

    @SafeVarargs
    private static Map<String, Object> obj(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return q(string);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(q(String.valueOf(entry.getKey())))
                        .append(':')
                        .append(json(entry.getValue()));
            }
            return builder.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(json(item));
            }
            return builder.append(']').toString();
        }
        return q(String.valueOf(value));
    }

    private static String q(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    // ---- Public page introspection (workbench automation / smoke tests) ----

    /** Number of pages (Web + Settings). */
    public int pageCount() {
        return getPageCount();
    }

    /** True once the Settings page loaded its configuration from the backend. */
    public boolean isSettingsReady() {
        return settingsReady;
    }

    /** Title of the page at the given index. */
    public String pageTitle(int index) {
        return getPageText(index);
    }

    /** Activates the page at the given index (workbench automation). */
    public void showPage(int index) {
        if (index >= 0 && index < getPageCount()) {
            setActivePage(index);
        }
    }

    // ---- Lifecycle ----

    @Override
    public void doSave(IProgressMonitor monitor) {
        // backend-owned state; nothing to save locally
    }

    @Override
    public void doSaveAs() {
        // not supported
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public void dispose() {
        disposed = true;
        readinessGeneration.incrementAndGet();
        readinessExecutor.shutdownNow();
        bridgeExecutor.shutdownNow();
        try {
            readinessExecutor.awaitTermination(2, TimeUnit.SECONDS);
            bridgeExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (webBrowser != null && !webBrowser.isDisposed()) {
            webBrowser.dispose();
        }
        if (settingsBrowser != null && !settingsBrowser.isDisposed()) {
            settingsBrowser.dispose();
        }
        if (errorColor != null && !errorColor.isDisposed()) {
            errorColor.dispose();
        }
        System.out.println("EVO-Studio: harness editor disposed (Browsers and readiness "
                + "released).");
        super.dispose();
    }
}
