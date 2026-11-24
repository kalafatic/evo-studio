package eu.kalafatic.dstudio.application;

import eu.kalafatic.dstudio.application.lifecycle.DStudioLifecycleCoordinator;
import eu.kalafatic.dstudio.application.lifecycle.DStudioLifecycleStage;
import eu.kalafatic.dstudio.teip.server.PortConflictException;
import eu.kalafatic.dstudio.teip.server.PortProbe;
import java.util.Map;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.window.Window;

public class DStudioApplication implements IApplication {

    private static final String PLUGIN_ID = "eu.kalafatic.dstudio.application";

    private DStudioHeadlessRunner headlessRunner;

    public static boolean isHeadlessRequested(IApplicationContext context) {
        if ("true".equalsIgnoreCase(System.getProperty("dstudio.headless"))
                || "1".equals(System.getProperty("dstudio.headless"))) {
            return true;
        }
        Map<?, ?> args = context.getArguments();
        Object appArgs = args.get(IApplicationContext.APPLICATION_ARGS);
        if (appArgs instanceof String[] strings) {
            for (String arg : strings) {
                if ("-headless".equals(arg) || "--headless".equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Object start(IApplicationContext context) {
        // Register the local update repository (<install>/updates) when present.
        DStudioUpdateRepositoryRegistrar.registerAsync();
        DStudioLifecycleCoordinator coordinator = new DStudioLifecycleCoordinator();
        DStudioRuntime.setCoordinator(coordinator);
        try {
            coordinator.advance(DStudioLifecycleStage.CORE_SERVICE_INITIALIZATION);
        } catch (RuntimeException e) {
            return reportStartupFailure(coordinator,
                    "EVO-Studio core services failed to initialize", e);
        }
        if (isHeadlessRequested(context)) {
            headlessRunner = new DStudioHeadlessRunner(coordinator);
            return headlessRunner.run(context);
        }
        try {
            try {
                coordinator.advance(DStudioLifecycleStage.APPLICATION_READY);
            } catch (PortConflictException e) {
                if (!resolvePortConflictInteractively(coordinator, e, null)) {
                    coordinator.terminateAfterStartupFailure();
                    DStudioStartupExit.terminate(0);
                    return IApplication.EXIT_OK;
                }
            }
            org.eclipse.swt.widgets.Display display =
                    org.eclipse.ui.PlatformUI.createDisplay();
            try {
                int returnCode = org.eclipse.ui.PlatformUI.createAndRunWorkbench(display,
                        new ApplicationWorkbenchAdvisor(coordinator));
                if (returnCode == org.eclipse.ui.PlatformUI.RETURN_RESTART) {
                    return IApplication.EXIT_RESTART;
                }
                return IApplication.EXIT_OK;
            } finally {
                display.dispose();
            }
        } catch (RuntimeException e) {
            return reportStartupFailure(coordinator, "EVO-Studio failed to start", e);
        } finally {
            coordinator.advance(DStudioLifecycleStage.APPLICATION_TERMINATED);
        }
    }

    /**
     * Interactive port-conflict resolution: explains the conflict, suggests a free port
     * and retries; repeats with the newest conflict information until the server starts
     * or the user exits. The successfully chosen port is persisted by the coordinator.
     */
    private boolean resolvePortConflictInteractively(DStudioLifecycleCoordinator coordinator,
            PortConflictException conflict, String extraMessage) {
        org.eclipse.swt.widgets.Display display =
                new org.eclipse.swt.widgets.Display();
        try {
            String extra = extraMessage;
            PortConflictException current = conflict;
            while (true) {
                int suggested = PortProbe.findFreePort(current.bindAddress(),
                        current.port() + 1, 100);
                PortConflictDialog dialog = new PortConflictDialog(null,
                        current.bindAddress(), current.port(), suggested, extra);
                if (dialog.open() != Window.OK) {
                    System.out.println("[EVO-Studio] User chose to exit after port conflict on "
                            + current.port() + ".");
                    return false;
                }
                int chosen = dialog.getSelectedPort();
                try {
                    coordinator.restartServerOnPort(chosen);
                    System.out.println("[EVO-Studio] Server restarted on port " + chosen
                            + " (persisted in dstudio.properties).");
                    return true;
                } catch (PortConflictException retried) {
                    current = retried;
                    extra = "Port " + chosen + " is also occupied \u2014 try another one.";
                }
            }
        } finally {
            display.dispose();
        }
    }

    /**
     * Any startup failure in desktop mode ends in an interactive, actionable dialog —
     * the user must see what failed and what to fix, never just "An error has occurred".
     */
    private Object reportStartupFailure(DStudioLifecycleCoordinator coordinator,
            String title, RuntimeException failure) {
        System.err.println("[EVO-Studio] " + title + ": " + failure.getMessage());
        failure.printStackTrace(System.err);
        try {
            org.eclipse.swt.widgets.Display display =
                    new org.eclipse.swt.widgets.Display();
            try {
                IStatus status = new Status(IStatus.ERROR, PLUGIN_ID,
                        title + ": " + failure.getMessage(), failure);
                ErrorDialog.openError(null, "EVO-Studio \u2014 Startup Failure",
                        title + ".\n\nWhat to do: " + remediation(failure)
                                + "\n\nDetails are available below.", status);
            } finally {
                display.dispose();
            }
        } catch (Throwable swtFailure) {
            // no display available (or SWT broken): the problem is already on stderr
        }
        coordinator.terminateAfterStartupFailure();
        DStudioStartupExit.terminate(1);
        return IApplication.EXIT_OK;
    }

    private String remediation(RuntimeException failure) {
        if (failure instanceof PortConflictException conflict) {
            return "another process occupies port " + conflict.port() + " on "
                    + conflict.bindAddress() + ". Stop the conflicting process, or set "
                    + "teip.port=NNNN in ~/.dstudio/dstudio.properties, then restart "
                    + "EVO-Studio.";
        }
        return "review the error details, fix the underlying cause (or adjust "
                + "~/.dstudio/dstudio.properties) and restart EVO-Studio.";
    }

    @Override
    public void stop() {
        if (headlessRunner != null) {
            headlessRunner.stop();
        }
    }
}
