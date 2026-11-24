package eu.kalafatic.dstudio.ui;

import eu.kalafatic.dstudio.core.event.EventBus;
import eu.kalafatic.dstudio.core.model.ExecutionEvent;
import eu.kalafatic.dstudio.core.model.ExecutionEventType;
import eu.kalafatic.dstudio.ui.console.ExecutionConsoleFactory;
import eu.kalafatic.dstudio.ui.workspace.EclipseWorkspaceBridge;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

public class DStudioUiStartup implements IStartup {

    private AutoCloseable eventSubscription;
    private static volatile boolean contextualActionsInstalled = false;

    /** Windows whose Perspective Reset recovery listener is already installed. */
    private static final java.util.Set<org.eclipse.ui.IWorkbenchWindow> EDITOR_WINDOWS =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    @Override
    public void earlyStartup() {
        DStudioUiPlugin plugin = DStudioUiPlugin.getInstance();
        if (plugin == null) {
            return;
        }
        // Register the RCP-surface handlers of the unified contextual action
        // framework once per workbench session (dialogs, clipboard, explorer).
        if (!contextualActionsInstalled) {
            contextualActionsInstalled = true;
            eu.kalafatic.dstudio.core.contextual.ContextualActionRegistry registry =
                    plugin.contextualActionRegistry();
            if (registry != null) {
                eu.kalafatic.dstudio.ui.contextual.RcpContextualActions.install(registry);
            }
        }
        org.eclipse.ui.IWorkbench workbench = org.eclipse.ui.PlatformUI.getWorkbench();
        if (workbench != null) {
            workbench.getDisplay().asyncExec(() -> {
                new EclipseWorkspaceBridge().synchronize(plugin.projectService());
            });
        }
        EventBus eventBus = plugin.eventBus();
        if (eventBus != null) {
            eventSubscription = eventBus.subscribe(ExecutionEvent.class,
                    DStudioUiStartup::forwardToConsole);
        }
        try {
            installEditorLifecycle();
        } catch (RuntimeException e) {
            // Never let workbench editor wiring break the rest of startup.
            System.err.println("EVO-Studio: editor lifecycle wiring failed: " + e);
        }
    }

    /**
     * The harness editor is the workbench working area. Open it when the initial
     * window appears and restore it after "Reset Perspective" — the same lifecycle
     * the EVO-Code workbench provides. The editor input is stable, so reopening is
     * idempotent.
     */
    private static void installEditorLifecycle() {
        org.eclipse.ui.IWorkbench workbench =
                org.eclipse.ui.PlatformUI.getWorkbench();
        if (workbench == null) {
            return;
        }
        // Defer until the workbench window manager is initialized; the initial
        // window may already be open by then, which the active-window check covers.
        org.eclipse.swt.widgets.Display display = workbench.getDisplay();
        if (display == null) {
            installEditorLifecycleNow(workbench);
        } else {
            display.asyncExec(() -> installEditorLifecycleNow(workbench));
        }
    }

    private static void installEditorLifecycleNow(
            org.eclipse.ui.IWorkbench workbench) {
        workbench.addWindowListener(new org.eclipse.ui.IWindowListener() {
            @Override
            public void windowOpened(org.eclipse.ui.IWorkbenchWindow window) {
                scheduleInferenceEditor(window);
            }

            @Override
            public void windowActivated(org.eclipse.ui.IWorkbenchWindow window) {
                // no-op
            }

            @Override
            public void windowDeactivated(org.eclipse.ui.IWorkbenchWindow window) {
                // no-op
            }

            @Override
            public void windowClosed(org.eclipse.ui.IWorkbenchWindow window) {
                EDITOR_WINDOWS.remove(window);
            }
        });
        org.eclipse.ui.IWorkbenchWindow active =
                workbench.getActiveWorkbenchWindow();
        if (active != null) {
            scheduleInferenceEditor(active);
        }
    }

    private static void scheduleInferenceEditor(
            org.eclipse.ui.IWorkbenchWindow window) {
        if (window == null) {
            return;
        }
        installPerspectiveResetRecovery(window);
        org.eclipse.swt.widgets.Display display = window.getShell() != null
                ? window.getShell().getDisplay()
                : org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
        if (display == null) {
            return;
        }
        display.asyncExec(() -> openInferenceEditor(window, 0));
    }

    private static void openInferenceEditor(
            org.eclipse.ui.IWorkbenchWindow window, int attempt) {
        if (window == null || window.getShell() == null
                || window.getShell().isDisposed()) {
            return;
        }
        org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            if (attempt < 20) {
                window.getShell().getDisplay().timerExec(250,
                        () -> openInferenceEditor(window, attempt + 1));
            }
            return;
        }
        try {
            eu.kalafatic.dstudio.ui.handlers.OpenInferenceEditorHandler.open(page);
        } catch (org.eclipse.ui.PartInitException e) {
            System.err.println("EVO-Studio: failed to open the inference editor ("
                    + eu.kalafatic.dstudio.ui.editor.DStudioMultiPageEditor.EDITOR_ID
                    + "): " + e.getMessage());
        }
    }

    private static void installPerspectiveResetRecovery(
            org.eclipse.ui.IWorkbenchWindow window) {
        if (!EDITOR_WINDOWS.add(window)) {
            return;
        }
        window.addPerspectiveListener(new org.eclipse.ui.IPerspectiveListener() {
            @Override
            public void perspectiveActivated(
                    org.eclipse.ui.IWorkbenchPage page,
                    org.eclipse.ui.IPerspectiveDescriptor perspective) {
                // no-op
            }

            @Override
            public void perspectiveChanged(
                    org.eclipse.ui.IWorkbenchPage page,
                    org.eclipse.ui.IPerspectiveDescriptor perspective,
                    String changeId) {
                if (!org.eclipse.ui.IWorkbenchPage.CHANGE_RESET.equals(changeId)) {
                    return;
                }
                org.eclipse.swt.widgets.Display display = window.getShell() != null
                        ? window.getShell().getDisplay()
                        : org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
                if (display != null) {
                    display.asyncExec(() -> openInferenceEditor(window, 0));
                }
            }
        });
    }

    private static void forwardToConsole(ExecutionEvent event) {
        if (event.type() == ExecutionEventType.EXECUTION_LOG
                || event.type() == ExecutionEventType.EXECUTION_QUEUED
                || event.type() == ExecutionEventType.EXECUTION_STARTED
                || event.type() == ExecutionEventType.EXECUTION_COMPLETED
                || event.type() == ExecutionEventType.EXECUTION_FAILED
                || event.type() == ExecutionEventType.EXECUTION_CANCELLED
                || event.type() == ExecutionEventType.TOOL_INVOKED) {
            org.eclipse.ui.IWorkbench workbench = org.eclipse.ui.PlatformUI.getWorkbench();
            if (workbench == null || workbench.getDisplay() == null) {
                return;
            }
            workbench.getDisplay().asyncExec(() -> {
                MessageConsole console = ExecutionConsoleFactory.showConsole();
                MessageConsoleStream stream = console.newMessageStream();
                try {
                    stream.println("[" + event.executionId() + "] "
                            + event.type().name() + ": " + event.message());
                } finally {
                    try {
                        stream.close();
                    } catch (java.io.IOException e) {
                        System.err.println("EVO-Studio: console stream close failed: "
                                + e.getMessage());
                    }
                }
            });
        }
    }
}
