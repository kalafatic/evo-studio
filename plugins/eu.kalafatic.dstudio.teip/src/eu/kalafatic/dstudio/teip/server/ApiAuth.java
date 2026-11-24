package eu.kalafatic.dstudio.teip.server;

import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Authentication for the EVO-Studio management API surface ({@code /api/*}).
 *
 * <p>Current phase policy: <b>loopback clients are always local-trusted</b> — the
 * embedded SWT Browser, the desktop application, CLI tools and local integrations
 * never require a token, a session or dev mode. Only non-loopback (network) clients
 * keep the optional bearer-token requirement ({@code dstudio.teip.auth.token} /
 * {@code DSTUDIO_TEIP_AUTH_TOKEN}); without a configured token remote access is
 * rejected exactly as before.</p>
 */
public final class ApiAuth {

    public static final String ERROR_CODE = "AUTHENTICATION_FAILED";

    private ApiAuth() {
    }

    /** Throws {@link ApiAuthException} unless the request is authorized. */
    public static void authenticate(HttpExchange exchange, boolean devModeConfigured) {
        // Local desktop/development workflow: loopback clients are trusted.
        if (TeipAuth.isLoopback(exchange.getRemoteAddress().getAddress())) {
            return;
        }
        Optional<String> token = TeipAuth.configuredToken();
        if (token.isPresent()) {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new ApiAuthException("Missing Authorization: Bearer <token> header");
            }
            String presented = header.substring("Bearer ".length()).trim();
            if (!constantTimeEquals(token.get(), presented)) {
                throw new ApiAuthException("Invalid bearer token");
            }
            return;
        }
        throw new ApiAuthException("API authentication required for non-loopback "
                + "access: configure dstudio.teip.auth.token");
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    public static class ApiAuthException extends RuntimeException {
        public ApiAuthException(String message) {
            super(message);
        }
    }
}
