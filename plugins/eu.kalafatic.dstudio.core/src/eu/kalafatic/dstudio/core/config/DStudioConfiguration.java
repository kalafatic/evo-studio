package eu.kalafatic.dstudio.core.config;

import java.nio.file.Path;

public record DStudioConfiguration(String appName, String appVersion, Path configurationDirectory,
        String teipBindAddress, int teipPort, String teipNetworkMode, int diagnosticsPort,
        boolean teipDevMode, String defaultHarnessId, String defaultModel,
        long executionTimeoutSeconds, String evoMcpEndpoint, String evoMcpToken) {

    public static final String APP_NAME = "EVO-Studio";
    public static final String APP_VERSION = "2.6.11";
    /**
     * Family port convention (EVO 28xxx, TRYST 38xxx): EVO-Studio owns the 48xxx block
     * (primary TEIP port 48080, diagnostics 48180); EVO-Code owns the 58xxx block
     * (58080 default). EVO-Studio must never occupy 58xxx.
     */
    public static final int DEFAULT_TEIP_PORT = 48080;
    public static final int DEFAULT_DIAGNOSTICS_PORT = 48180;
    /**
     * Historical pre-convention defaults (equal to today's defaults). Kept as named
     * markers because {@code DStudioConfigurationServiceImpl} normalizes persisted
     * values matching them on load.
     */
    public static final int LEGACY_TEIP_PORT = 48080;
    public static final int LEGACY_DIAGNOSTICS_PORT = 48180;
    /**
     * Briefly-shipped 58xxx values that collided with EVO-Code's 58080; persisted
     * occurrences are remapped to the 48xxx defaults on load.
     */
    public static final int TRANSIENT_TEIP_PORT = 58080;
    public static final int TRANSIENT_DIAGNOSTICS_PORT = 58180;
    public static final String NETWORK_LOOPBACK = "LOOPBACK";
    public static final String NETWORK_LAN = "LAN";
    public static final String NETWORK_ALL = "ALL_INTERFACES";
    public static final long DEFAULT_EXECUTION_TIMEOUT_SECONDS = 1800;

    public static DStudioConfiguration defaults(Path configurationDirectory) {
        return new DStudioConfiguration(APP_NAME, APP_VERSION, configurationDirectory,
                "127.0.0.1", DEFAULT_TEIP_PORT, NETWORK_LOOPBACK, DEFAULT_DIAGNOSTICS_PORT,
                false, "", "", DEFAULT_EXECUTION_TIMEOUT_SECONDS, "", "");
    }
}
