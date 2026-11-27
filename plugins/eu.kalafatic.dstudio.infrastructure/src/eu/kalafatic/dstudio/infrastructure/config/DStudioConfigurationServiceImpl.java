package eu.kalafatic.dstudio.infrastructure.config;

import eu.kalafatic.dstudio.core.config.DStudioConfiguration;
import eu.kalafatic.dstudio.core.service.ConfigurationService;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class DStudioConfigurationServiceImpl implements ConfigurationService {

    public static final String KEY_TEIP_PORT = "teip.port";
    public static final String KEY_TEIP_BIND_ADDRESS = "teip.bindAddress";
    public static final String KEY_TEIP_NETWORK_MODE = "teip.networkMode";
    public static final String KEY_DIAGNOSTICS_PORT = "diagnostics.port";
    public static final String KEY_TEIP_DEV_MODE = "teip.devMode";
    public static final String KEY_DEFAULT_HARNESS = "harness.default";
    public static final String KEY_DEFAULT_MODEL = "harness.defaultModel";
    public static final String KEY_EXECUTION_TIMEOUT_SECONDS = "execution.timeoutSeconds";
    public static final String KEY_EVO_MCP_ENDPOINT = "evo.mcp.endpoint";
    public static final String KEY_EVO_MCP_TOKEN = "evo.mcp.token";

    public static final String SYS_TEIP_PORT = "dstudio.teip.port";
    public static final String SYS_TEIP_BIND_ADDRESS = "dstudio.teip.bind.address";
    public static final String SYS_TEIP_NETWORK_MODE = "dstudio.teip.network.mode";
    public static final String SYS_DIAGNOSTICS_PORT = "dstudio.diagnostics.port";
    public static final String SYS_TEIP_DEV_MODE = "dstudio.teip.devMode";
    public static final String SYS_DEFAULT_HARNESS = "dstudio.harness.default";
    public static final String SYS_DEFAULT_MODEL = "dstudio.harness.defaultModel";
    public static final String SYS_EXECUTION_TIMEOUT_SECONDS = "dstudio.execution.timeoutSeconds";
    public static final String SYS_EVO_MCP_ENDPOINT = "dstudio.evo.mcp.endpoint";
    public static final String SYS_EVO_MCP_TOKEN = "dstudio.evo.mcp.token";

    public static final String ENV_TEIP_PORT = "DSTUDIO_TEIP_PORT";
    public static final String ENV_TEIP_BIND_ADDRESS = "DSTUDIO_TEIP_BIND_ADDRESS";
    public static final String ENV_TEIP_NETWORK_MODE = "DSTUDIO_TEIP_NETWORK_MODE";
    public static final String ENV_DIAGNOSTICS_PORT = "DSTUDIO_DIAGNOSTICS_PORT";
    public static final String ENV_TEIP_DEV_MODE = "DSTUDIO_TEIP_DEV_MODE";
    public static final String ENV_DEFAULT_HARNESS = "DSTUDIO_HARNESS_DEFAULT";
    public static final String ENV_DEFAULT_MODEL = "DSTUDIO_HARNESS_DEFAULT_MODEL";
    public static final String ENV_EXECUTION_TIMEOUT_SECONDS = "DSTUDIO_EXECUTION_TIMEOUT_SECONDS";

    private final DStudioPaths paths;
    private volatile DStudioConfiguration configuration;

    public DStudioConfigurationServiceImpl(DStudioPaths paths) {
        this.paths = paths;
    }

    @Override
    public DStudioConfiguration getConfiguration() {
        if (configuration == null) {
            synchronized (this) {
                if (configuration == null) {
                    configuration = load();
                }
            }
        }
        return configuration;
    }

    private DStudioConfiguration load() {
        Properties file = new Properties();
        Path propertiesFile = paths.getPropertiesFile();
        if (Files.isRegularFile(propertiesFile)) {
            try (Reader reader = Files.newBufferedReader(propertiesFile)) {
                file.load(reader);
            } catch (IOException e) {
                System.err.println("EVO-Studio: failed to read " + propertiesFile + ": "
                        + e.getMessage());
            }
        }
        DStudioConfiguration defaults = DStudioConfiguration.defaults(
                paths.getConfigurationDirectory());
        int teipPort = migrateLegacyPort(intValue(KEY_TEIP_PORT, defaults.teipPort(),
                file), DStudioConfiguration.LEGACY_TEIP_PORT,
                DStudioConfiguration.TRANSIENT_TEIP_PORT,
                defaults.teipPort());
        int diagnosticsPort = migrateLegacyPort(intValue(KEY_DIAGNOSTICS_PORT,
                defaults.diagnosticsPort(), file),
                DStudioConfiguration.LEGACY_DIAGNOSTICS_PORT,
                DStudioConfiguration.TRANSIENT_DIAGNOSTICS_PORT,
                defaults.diagnosticsPort());
        DStudioConfiguration migrated = new DStudioConfiguration(defaults.appName(),
                defaults.appVersion(), defaults.configurationDirectory(),
                value(KEY_TEIP_BIND_ADDRESS, defaults.teipBindAddress(), file),
                teipPort,
                value(KEY_TEIP_NETWORK_MODE, defaults.teipNetworkMode(), file),
                diagnosticsPort,
                boolValue(KEY_TEIP_DEV_MODE, defaults.teipDevMode(), file),
                value(KEY_DEFAULT_HARNESS, defaults.defaultHarnessId(), file),
                value(KEY_DEFAULT_MODEL, defaults.defaultModel(), file),
                longValue(KEY_EXECUTION_TIMEOUT_SECONDS,
                        defaults.executionTimeoutSeconds(), file),
                value(KEY_EVO_MCP_ENDPOINT, defaults.evoMcpEndpoint(), file),
                value(KEY_EVO_MCP_TOKEN, defaults.evoMcpToken(), file));
        if (teipPort != intValue(KEY_TEIP_PORT, defaults.teipPort(), file)
                || diagnosticsPort != intValue(KEY_DIAGNOSTICS_PORT,
                defaults.diagnosticsPort(), file)) {
            // Persist the port correction once so stale values never reappear.
            saveConfiguration(migrated);
            System.err.println("EVO-Studio: corrected persisted ports to the 58xxx family "
                    + "(TEIP " + teipPort + ", diagnostics " + diagnosticsPort + ")");
        }
        return migrated;
    }

    /**
     * Port convention normalization: the family convention is EVO-Studio 58xxx / EVO-Code 48xxx.
     * Persisted values equal to the legacy 48xxx defaults are remapped to the 58xxx family
     * defaults; the current 58xxx defaults and explicit custom ports are preserved.
     */
    private int migrateLegacyPort(int configured, int legacyDefault, int transientValue,
            int newDefault) {
        if (configured == legacyDefault || configured == transientValue) {
            return newDefault;
        }
        return configured;
    }

    private String value(String fileKey, String defaultValue, Properties file) {
        String systemValue = System.getProperty(sysFor(fileKey));
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String envValue = System.getenv(envFor(fileKey));
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return file.getProperty(fileKey, defaultValue);
    }

    private String sysFor(String fileKey) {
        return switch (fileKey) {
            case KEY_TEIP_PORT -> SYS_TEIP_PORT;
            case KEY_TEIP_BIND_ADDRESS -> SYS_TEIP_BIND_ADDRESS;
            case KEY_TEIP_NETWORK_MODE -> SYS_TEIP_NETWORK_MODE;
            case KEY_DIAGNOSTICS_PORT -> SYS_DIAGNOSTICS_PORT;
            case KEY_TEIP_DEV_MODE -> SYS_TEIP_DEV_MODE;
            case KEY_DEFAULT_HARNESS -> SYS_DEFAULT_HARNESS;
            case KEY_DEFAULT_MODEL -> SYS_DEFAULT_MODEL;
            case KEY_EXECUTION_TIMEOUT_SECONDS -> SYS_EXECUTION_TIMEOUT_SECONDS;
            case KEY_EVO_MCP_ENDPOINT -> SYS_EVO_MCP_ENDPOINT;
            case KEY_EVO_MCP_TOKEN -> SYS_EVO_MCP_TOKEN;
            default -> "";
        };
    }

    private int intValue(String fileKey, int defaultValue, Properties file) {
        String value = value(fileKey, String.valueOf(defaultValue), file);
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long longValue(String fileKey, long defaultValue, Properties file) {
        String value = value(fileKey, String.valueOf(defaultValue), file);
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean boolValue(String fileKey, boolean defaultValue, Properties file) {
        String value = value(fileKey, String.valueOf(defaultValue), file).trim();
        if (value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equals("0")
                || value.equalsIgnoreCase("no")) {
            return false;
        }
        return defaultValue;
    }

    private String envFor(String fileKey) {
        return switch (fileKey) {
            case KEY_TEIP_PORT -> ENV_TEIP_PORT;
            case KEY_TEIP_BIND_ADDRESS -> ENV_TEIP_BIND_ADDRESS;
            case KEY_TEIP_NETWORK_MODE -> ENV_TEIP_NETWORK_MODE;
            case KEY_DIAGNOSTICS_PORT -> ENV_DIAGNOSTICS_PORT;
            case KEY_TEIP_DEV_MODE -> ENV_TEIP_DEV_MODE;
            case KEY_DEFAULT_HARNESS -> ENV_DEFAULT_HARNESS;
            case KEY_DEFAULT_MODEL -> ENV_DEFAULT_MODEL;
            case KEY_EXECUTION_TIMEOUT_SECONDS -> ENV_EXECUTION_TIMEOUT_SECONDS;
            default -> "";
        };
    }

    @Override
    public void saveConfiguration(DStudioConfiguration configuration) {
        Properties properties = new Properties();
        properties.setProperty(KEY_TEIP_BIND_ADDRESS, configuration.teipBindAddress());
        properties.setProperty(KEY_TEIP_PORT, String.valueOf(configuration.teipPort()));
        properties.setProperty(KEY_TEIP_NETWORK_MODE, configuration.teipNetworkMode());
        properties.setProperty(KEY_DIAGNOSTICS_PORT,
                String.valueOf(configuration.diagnosticsPort()));
        properties.setProperty(KEY_TEIP_DEV_MODE,
                String.valueOf(configuration.teipDevMode()));
        properties.setProperty(KEY_DEFAULT_HARNESS, configuration.defaultHarnessId());
        properties.setProperty(KEY_DEFAULT_MODEL, configuration.defaultModel());
        properties.setProperty(KEY_EXECUTION_TIMEOUT_SECONDS,
                String.valueOf(configuration.executionTimeoutSeconds()));
        try {
            Files.createDirectories(paths.getConfigurationDirectory());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create configuration directory: "
                    + e.getMessage(), e);
        }
        try (Writer writer = Files.newBufferedWriter(paths.getPropertiesFile())) {
            properties.store(writer, "EVO-Studio configuration");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save configuration: "
                    + e.getMessage(), e);
        }
        this.configuration = configuration;
    }
}
