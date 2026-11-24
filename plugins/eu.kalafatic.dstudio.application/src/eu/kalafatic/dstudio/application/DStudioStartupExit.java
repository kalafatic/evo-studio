package eu.kalafatic.dstudio.application;

/**
 * Deterministic process termination for startup-failure paths.
 *
 * <p>EVO-Studio runs inside the Eclipse native launcher (JVM embedded in-process). After a
 * failed application start the launcher does not reliably exit on {@code System.exit}/
 * {@code Runtime.halt} (verified live), {@code ProcessHandle.destroy()} is forbidden on
 * the current process, and the JVM's own SIGTERM handling re-enters the OSGi shutdown
 * sequence that is already stuck. SIGKILL to our own PID cannot be intercepted and
 * terminates instantly. Callers must complete service disposal
 * ({@code terminateAfterStartupFailure()}) before invoking this — the database, executors
 * and servers are closed in that step, so nothing is left to clean up here.</p>
 */
public final class DStudioStartupExit {

    private static final long SIGNAL_GRACE_MILLIS = 1000;

    private DStudioStartupExit() {
    }

    /** Never returns normally (on Linux). */
    public static void terminate(int exitCode) {
        try {
            String pid = String.valueOf(ProcessHandle.current().pid());
            new ProcessBuilder("kill", "-KILL", pid).start();
            Thread.sleep(SIGNAL_GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[EVO-Studio] self-termination failed: " + e.getMessage());
        }
        try {
            ProcessHandle.current().destroyForcibly();
        } catch (Exception ignored) {
            // restricted on the current process as well
        }
        Runtime.getRuntime().halt(exitCode);
    }
}
