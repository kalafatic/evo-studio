package eu.kalafatic.dstudio.ui;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Centralized form-history store: the last successfully used values for create/configure
 * forms, persisted in the Eclipse instance preference store (the platform's standard UI
 * preference mechanism — no new persistence layer is introduced).
 *
 * <p>Scope: non-sensitive form state only (provider type/endpoint, preferred
 * harness/provider/model, …). Passwords, API keys and tokens must never be written here;
 * they remain in the registry credential vaults (see the secret-masking dialogs).</p>
 *
 * <p>Values are keyed by form + field; {@link #get} returns {@code null} when no valid
 * history exists so callers fall back to auto-detected or application defaults.</p>
 */
public final class FormHistory {

    private static final String NODE = "eu.kalafatic.dstudio.forms";

    private FormHistory() {
    }

    private static IEclipsePreferences node() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    /** Returns the last-used value for a key, or {@code null} when none exists. */
    public static String get(String key) {
        String value = node().get(key, null);
        return value == null || value.isBlank() ? null : value;
    }

    /** Stores the last-used value for a key (blank/null removes it) and flushes. */
    public static void put(String key, String value) {
        IEclipsePreferences prefs = node();
        if (value == null || value.isBlank()) {
            prefs.remove(key);
        } else {
            prefs.put(key, value);
        }
        try {
            prefs.flush();
        } catch (Exception ignored) {
            // non-fatal: the in-memory value remains in effect for this session
        }
    }

    /** Clears all remembered form values (restore application defaults). */
    public static void resetAll() {
        try {
            IEclipsePreferences prefs = node();
            prefs.clear();
            prefs.flush();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
