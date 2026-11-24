package eu.kalafatic.dstudio.application;

import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

public class ApplicationWorkbenchWindowAdvisor extends WorkbenchWindowAdvisor {

    // UI-bundle command id only (layering ui → application): F1 and the Help menu's
    // "Context Help" item run the same UI-bundle HelpLauncher through this command.
    private static final String HELP_CONTEXT_COMMAND =
            "eu.kalafatic.dstudio.command.help.context";

    private static final java.util.concurrent.atomic.AtomicBoolean F1_FILTER_INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public ApplicationWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
        super(configurer);
    }

    @Override
    public ActionBarAdvisor createActionBarAdvisor(IActionBarConfigurer configurer) {
        return new ApplicationActionBarAdvisor(configurer);
    }

    @Override
    public void preWindowOpen() {
        IWorkbenchWindowConfigurer configurer = getWindowConfigurer();
        configurer.setInitialSize(new Point(1440, 900));
        configurer.setShowCoolBar(true);
        configurer.setShowStatusLine(true);
        configurer.setShowProgressIndicator(true);
        configurer.setShowPerspectiveBar(true);
        configurer.setTitle("EVO-Studio");
    }

    @Override
    public void postWindowOpen() {
        // Context help without org.eclipse.help: intercept plain F1 and open the
        // embedded help site at the anchor of the active view. Registered once per
        // display so additional windows do not add duplicate filters.
        if (F1_FILTER_INSTALLED.compareAndSet(false, true)) {
            org.eclipse.swt.widgets.Display.getDefault().addFilter(
                    org.eclipse.swt.SWT.KeyDown, event -> {
                        if (event.keyCode == org.eclipse.swt.SWT.F1
                                && event.stateMask == 0) {
                            try {
                                org.eclipse.ui.handlers.IHandlerService handlerService =
                                        getWindowConfigurer().getWindow().getService(
                                                org.eclipse.ui.handlers.IHandlerService.class);
                                if (handlerService != null) {
                                    handlerService.executeCommand(
                                            HELP_CONTEXT_COMMAND, null);
                                }
                            } catch (Exception ignored) {
                                // help is best-effort; never break the workbench on F1
                            }
                        }
                    });
        }
    }
}
