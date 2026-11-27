package eu.kalafatic.dstudio.core.config;

import java.nio.file.Path;

public record DStudioConfiguration(String appName, String appVersion, Path configurationDirectory,
        String teipBindAddress, int teipPort, String teipNetworkMode, int diagnosticsPort,
        boolean teipDevMode, String defaultHarnessId, String defaultModel,
        long executionTimeoutSeconds, String evoMcpEndpoint, String evoMcpToken) {

    public static final String APP_NAME = "EVO-Studio";
    public static final String APP_VERSION = "2.6.11";
    /**
     * Family port convention (EVO 28xxx, TRYST 38xxx): EVO-Studio owns the 58xxx block
     * (primary TEIP port 58080, diagnostics 58180); EVO-Code owns the 48xxx block
     * (48080 default). EVO-Studio must never occupy 48xxx.
     */
    public static final int DEFAULT_TEIP_PORT = 58080;
    public static final int DEFAULT_DIAGNOSTICS_PORT = 58180;
    /**
     * Historical pre-convention 48xxx defaults (the values EVO-Studio previously
     * occupied). Kept as named markers because
     * {@code DStudioConfigurationServiceImpl} normalizes persisted values matching
     * them on load, remapping them to the 58xxx family.
     */
    public static final int LEGACY_TEIP_PORT = 48080;
    public static final int LEGACY_DIAGNOSTICS_PORT = 48180;
    /**
     * The 58xxx values that are now the authoritative family defaults (previously
     * "transient" while they collided with EVO-Code's 58080). Persisted occurrences
     * are accepted as-is on load.
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
