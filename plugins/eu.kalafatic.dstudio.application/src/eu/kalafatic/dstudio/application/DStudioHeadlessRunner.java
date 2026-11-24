package eu.kalafatic.dstudio.application;

import eu.kalafatic.dstudio.application.cli.DStudioCli;
import eu.kalafatic.dstudio.application.lifecycle.DStudioLifecycleCoordinator;
import eu.kalafatic.dstudio.application.lifecycle.DStudioLifecycleStage;
import eu.kalafatic.dstudio.teip.server.PortConflictException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

public class DStudioHeadlessRunner {

    private final DStudioLifecycleCoordinator coordinator;
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private volatile Thread shutdownHook;

    public DStudioHeadlessRunner(DStudioLifecycleCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public Object run(IApplicationContext context) {
        try {
            coordinator.advance(DStudioLifecycleStage.APPLICATION_READY);
        } catch (PortConflictException e) {
            System.err.println("[EVO-Studio] STARTUP FAILED: " + e.getMessage());
            System.err.println("[EVO-Studio] The EVO-Studio server (TEIP/API/events) could not "
                    + "bind " + e.bindAddress() + ":" + e.port() + ".");
            System.err.println("[EVO-Studio] What to do:");
            System.err.println("[EVO-Studio]   1. Stop the process occupying the port "
                    + "(e.g. another EVO-Studio instance or a family product), or");
            System.err.println("[EVO-Studio]   2. Set teip.port=NNNN in "
                    + coordinator.getPaths().getPropertiesFile() + " or start with "
                    + "-Ddstudio.teip.port=NNNN, then restart.");
            System.err.println("[EVO-Studio] Your existing configuration is preserved; "
                    + "nothing was overwritten.");
            coordinator.terminateAfterStartupFailure();
            DStudioStartupExit.terminate(1);
            return Integer.valueOf(1);
        } catch (RuntimeException e) {
            System.err.println("[EVO-Studio] STARTUP FAILED: " + e.getMessage());
            e.printStackTrace(System.err);
            coordinator.terminateAfterStartupFailure();
            DStudioStartupExit.terminate(1);
            return Integer.valueOf(1);
        }
        Thread hook = new Thread(() -> {
            coordinator.advance(DStudioLifecycleStage.APPLICATION_TERMINATED);
        }, "dstudio-headless-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);
        shutdownHook = hook;
        System.out.println("[EVO-Studio] Headless runtime ready (config dir: "
                + coordinator.getPaths().getConfigurationDirectory() + ")");
        if (cliRequested(context)) {
            // Interactive or scripted CLI: commands are dispatched until stop/exit/EOF,
            // then the existing graceful shutdown path runs and the application exits.
            runCliSession();
            stop();
        } else {
            // Non-TTY daemon behavior unchanged: block until stopped externally.
            try {
                stopLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return IApplication.EXIT_OK;
    }

    /**
     * The CLI runs only when input is actually available and no explicit daemon flag
     * is set: an attached console (interactive REPL) or piped/redirected stdin
     * (scripted commands). A daemon started with stdin at /dev/null keeps blocking on
     * the latch exactly as before.
     */
    private boolean cliRequested(IApplicationContext context) {
        if (daemonRequested(context)) {
            return false;
        }
        if (System.console() != null) {
            return true;
        }
        return stdinIsPiped();
    }

    private static boolean daemonRequested(IApplicationContext context) {
        String property = System.getProperty("dstudio.daemon");
        if ("true".equalsIgnoreCase(property) || "1".equals(property)) {
            return true;
        }
        Map<?, ?> args = context.getArguments();
        Object appArgs = args.get(IApplicationContext.APPLICATION_ARGS);
        if (appArgs instanceof String[] strings) {
            for (String arg : strings) {
                if ("--daemon".equals(arg) || "-daemon".equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Linux: stdin is scripted input when fd 0 is a pipe or a regular file redirect. */
    private static boolean stdinIsPiped() {
        try {
            String target = Files.readSymbolicLink(Path.of("/proc/self/fd/0")).toString();
            return !(target.equals("/dev/null") || target.startsWith("/dev/tty")
                    || target.startsWith("/dev/pts"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void runCliSession() {
        PrintWriter out = new PrintWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        DStudioCli cli = new DStudioCli(coordinator, out);
        java.io.Console console = System.console();
        if (console != null) {
            console.printf("%s%n", DStudioCli.BANNER);
            String line;
            while ((line = console.readLine(DStudioCli.PROMPT)) != null) {
                if (!cli.dispatch(line)) {
                    break;
                }
            }
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!cli.dispatch(line)) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[EVO-Studio] CLI input failed: " + e.getMessage());
        }
    }

    public void stop() {
        coordinator.advance(DStudioLifecycleStage.APPLICATION_TERMINATED);
        stopLatch.countDown();
    }
}
