package eu.kalafatic.dstudio.application;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;

/**
 * Standard professional-IDE menu bar: File / Edit / Navigate / Search / Models /
 * Harness / Tools / Window / Help from the workbench action factory plus the EVO-Studio
 * family actions (web interface, terminal, model and harness surfaces). Project, Run
 * and AI menus are contributed by the UI bundle through org.eclipse.ui.menus so the
 * layering stays ui → application.
 */
public class ApplicationActionBarAdvisor extends ActionBarAdvisor {

    private static final String SITES_PREFERENCE_PAGE =
            "org.eclipse.equinox.internal.p2.ui.sdk.SitesPreferencePage";
    private static final String COMMAND_INSTALLATION_DETAILS =
            "org.eclipse.equinox.p2.ui.sdk.installationDetails";

    // View ids from the UI bundle's plugin.xml (layering ui → application: only the
    // ids are referenced here, never UI-bundle classes).
    private static final String INFERENCE_VIEW_ID =
            "eu.kalafatic.dstudio.ui.view.inference";
    private static final String HARNESS_VIEW_ID =
            "eu.kalafatic.dstudio.ui.view.harness";
    // Commands contributed by the UI bundle (runtime-resolved). The Tools and Help
    // actions are thin triggers: the terminal/web launch and help logic lives once in
    // the UI bundle (TerminalLauncher / WebInterfaceLauncher / HelpLauncher), exactly
    // like the Models/Harness menu actions execute UI-bundle commands.
    private static final String RUN_HARNESS_COMMAND =
            "eu.kalafatic.dstudio.command.runHarness";
    private static final String REFRESH_INFERENCE_MODELS_COMMAND =
            "eu.kalafatic.dstudio.command.refreshInferenceModels";
    private static final String OPEN_TERMINAL_COMMAND =
            "eu.kalafatic.dstudio.command.openTerminal";
    private static final String OPEN_WEB_INTERFACE_COMMAND =
            "eu.kalafatic.dstudio.command.openWebInterface";
    private static final String HELP_CONTENTS_COMMAND =
            "eu.kalafatic.dstudio.command.help.contents";
    private static final String HELP_GETTING_STARTED_COMMAND =
            "eu.kalafatic.dstudio.command.help.gettingStarted";
    private static final String HELP_CONTEXT_COMMAND =
            "eu.kalafatic.dstudio.command.help.context";
    private static final String HELP_SHORTCUTS_COMMAND =
            "eu.kalafatic.dstudio.command.help.shortcuts";
    private static final String HELP_CLI_COMMAND =
            "eu.kalafatic.dstudio.command.help.cli";
    private static final String HELP_DIAGNOSTICS_COMMAND =
            "eu.kalafatic.dstudio.command.help.diagnostics";

    private static final String ACTION_OPEN_WEB_INTERFACE =
            "dstudio.openWebInterface";
    private static final String ACTION_OPEN_TERMINAL = "dstudio.openTerminal";
    private static final String ACTION_SHOW_INFERENCE_VIEW = "dstudio.showInferenceView";
    private static final String ACTION_REFRESH_MODELS = "dstudio.refreshModels";
    private static final String ACTION_SHOW_HARNESS_VIEW = "dstudio.showHarnessView";
    private static final String ACTION_RUN_HARNESS = "dstudio.runHarness";
    private static final String ACTION_HELP_CONTENTS = "dstudio.helpContents";
    private static final String ACTION_HELP_GETTING_STARTED = "dstudio.helpGettingStarted";
    private static final String ACTION_HELP_CONTEXT = "dstudio.helpContext";
    private static final String ACTION_HELP_SHORTCUTS = "dstudio.helpShortcuts";
    private static final String ACTION_HELP_CLI = "dstudio.helpCli";
    private static final String ACTION_HELP_DIAGNOSTICS = "dstudio.helpDiagnostics";

    private IWorkbenchWindow window;

    public ApplicationActionBarAdvisor(IActionBarConfigurer configurer) {
        super(configurer);
    }

    @Override
    protected void makeActions(IWorkbenchWindow window) {
        this.window = window;
        register(ActionFactory.SAVE.create(window));
        register(ActionFactory.SAVE_AS.create(window));
        register(ActionFactory.SAVE_ALL.create(window));
        register(ActionFactory.REVERT.create(window));
        register(ActionFactory.IMPORT.create(window));
        register(ActionFactory.EXPORT.create(window));
        register(ActionFactory.CLOSE.create(window));
        register(ActionFactory.CLOSE_ALL.create(window));
        register(ActionFactory.QUIT.create(window));
        register(ActionFactory.UNDO.create(window));
        register(ActionFactory.REDO.create(window));
        register(ActionFactory.CUT.create(window));
        register(ActionFactory.COPY.create(window));
        register(ActionFactory.PASTE.create(window));
        register(ActionFactory.DELETE.create(window));
        register(ActionFactory.SELECT_ALL.create(window));
        register(ActionFactory.BACKWARD_HISTORY.create(window));
        register(ActionFactory.FORWARD_HISTORY.create(window));
        register(ActionFactory.FORWARD.create(window));
        register(ActionFactory.FIND.create(window));
        register(ActionFactory.OPEN_NEW_WINDOW.create(window));
        register(ActionFactory.RESET_PERSPECTIVE.create(window));
        register(ActionFactory.PREFERENCES.create(window));
        register(ActionFactory.ABOUT.create(window));

        register(action(ACTION_OPEN_WEB_INTERFACE, "Open &Web Interface",
                () -> executeCommand(OPEN_WEB_INTERFACE_COMMAND)));
        register(action(ACTION_OPEN_TERMINAL, "Open &Terminal",
                () -> executeCommand(OPEN_TERMINAL_COMMAND)));
        register(action(ACTION_SHOW_INFERENCE_VIEW, "Show &Inference View",
                () -> showView(INFERENCE_VIEW_ID)));
        register(action(ACTION_REFRESH_MODELS, "&Refresh Models",
                () -> executeCommand(REFRESH_INFERENCE_MODELS_COMMAND)));
        register(action(ACTION_SHOW_HARNESS_VIEW, "Show &Harness View",
                () -> showView(HARNESS_VIEW_ID)));
        register(action(ACTION_RUN_HARNESS, "&Run Harness…",
                () -> executeCommand(RUN_HARNESS_COMMAND)));
        register(action(ACTION_HELP_CONTENTS, "Help &Contents",
                () -> executeCommand(HELP_CONTENTS_COMMAND)));
        register(action(ACTION_HELP_GETTING_STARTED, "&Getting Started",
                () -> executeCommand(HELP_GETTING_STARTED_COMMAND)));
        register(action(ACTION_HELP_CONTEXT, "Context &Help",
                () -> executeCommand(HELP_CONTEXT_COMMAND)));
        register(action(ACTION_HELP_SHORTCUTS, "&Keyboard Shortcuts",
                () -> executeCommand(HELP_SHORTCUTS_COMMAND)));
        register(action(ACTION_HELP_CLI, "&CLI Commands",
                () -> executeCommand(HELP_CLI_COMMAND)));
        register(action(ACTION_HELP_DIAGNOSTICS, "&Diagnostics",
                () -> executeCommand(HELP_DIAGNOSTICS_COMMAND)));
    }

    private org.eclipse.jface.action.Action action(String id, String text, Runnable body) {
        org.eclipse.jface.action.Action action = new org.eclipse.jface.action.Action(text,
                IAction.AS_PUSH_BUTTON) {
            @Override
            public void run() {
                body.run();
            }
        };
        action.setId(id);
        return action;
    }

    @Override
    protected void fillMenuBar(IMenuManager menuBar) {
        menuBar.add(fileMenu());
        menuBar.add(editMenu());
        menuBar.add(navigateMenu());
        menuBar.add(searchMenu());
        menuBar.add(modelsMenu());
        menuBar.add(harnessMenu());
        menuBar.add(toolsMenu());
        // "Project", "Run" and "AI" menus are contributed by the UI bundle via
        // org.eclipse.ui.menus (menu:org.eclipse.ui.main.menu)
        menuBar.add(windowMenu());
        menuBar.add(helpMenu());
    }

    private MenuManager fileMenu() {
        MenuManager menu = new MenuManager("&File", "file");
        menu.add(getAction(ActionFactory.SAVE.getId()));
        menu.add(getAction(ActionFactory.SAVE_AS.getId()));
        menu.add(getAction(ActionFactory.SAVE_ALL.getId()));
        menu.add(getAction(ActionFactory.REVERT.getId()));
        menu.add(new org.eclipse.jface.action.Separator());
        menu.add(getAction(ActionFactory.IMPORT.getId()));
        menu.add(getAction(ActionFactory.EXPORT.getId()));
        menu.add(new org.eclipse.jface.action.Separator());
        menu.add(getAction(ActionFactory.CLOSE.getId()));
        menu.add(getAction(ActionFactory.CLOSE_ALL.getId()));
        menu.add(new org.eclipse.jface.action.Separator());
        menu.add(getAction(ActionFactory.QUIT.getId()));
        return menu;
    }

    private MenuManager editMenu() {
        MenuManager menu = new MenuManager("&Edit", "edit");
        menu.add(getAction(ActionFactory.UNDO.getId()));
        menu.add(getAction(ActionFactory.REDO.getId()));
        menu.add(new org.eclipse.jface.action.Separator());
        menu.add(getAction(ActionFactory.CUT.getId()));
        menu.add(getAction(ActionFactory.COPY.getId()));
        menu.add(getAction(ActionFactory.PASTE.getId()));
        menu.add(getAction(ActionFactory.DELETE.getId()));
        menu.add(getAction(ActionFactory.SELECT_ALL.getId()));
        return menu;
    }

    private MenuManager navigateMenu() {
        MenuManager menu = new MenuManager("&Navigate", "navigate");
        menu.add(getAction(ActionFactory.BACKWARD_HISTORY.getId()));
        menu.add(getAction(ActionFactory.FORWARD_HISTORY.getId()));
        menu.add(new org.eclipse.jface.action.Separator());
        menu.add(getAction(ActionFactory.FORWARD.getId()));
        return menu;
    }

    private MenuManager searchMenu() {
        MenuManager menu = new MenuManager("&Search", "search");
        menu.add(getAction(ActionFactory.FIND.getId()));
        return menu;
    }

    private MenuManager modelsMenu() {
        MenuManager menu = new MenuManager("&Models", "models");
        menu.add(getAction(ACTION_SHOW_INFERENCE_VIEW));
        menu.add(getAction(ACTION_REFRESH_MODELS));
        return menu;
    }

    private MenuManager harnessMenu() {
        MenuManager menu = new MenuManager("&Harness", "harness");
        menu.add(getAction(ACTION_SHOW_HARNESS_VIEW));
        menu.add(getAction(ACTION_RUN_HARNESS));
        return menu;
    }

    private MenuManager toolsMenu() {
        MenuManager menu = new MenuManager("&Tools", "tools");
        menu.add(getAction(ACTION_OPEN_TERMINAL));
        menu.add(getAction(ACTION_OPEN_WEB_INTERFACE));
        return menu;
    }

    private MenuManager windowMenu() {
        MenuManager menu = new MenuManager("&Window", "window");
        menu.add(getAction(ActionFactory.OPEN_NEW_WINDOW.getId()));
        menu.add(getAction(ActionFactory.RESET_PERSPECTIVE.getId()));
        menu.add(new org.eclipse.jface.action.Separator());
        menu.add(getAction(ActionFactory.PREFERENCES.getId()));
        return menu;
    }

    private MenuManager helpMenu() {
        MenuManager menu = new MenuManager("&Help", IWorkbenchActionConstants.M_HELP);

        // EVO-Studio help items: the embedded help site (help.html) and diagnostics are
        // served by the backend server, so they always document the running instance.
        menu.add(getAction(ACTION_HELP_CONTENTS));
        menu.add(getAction(ACTION_HELP_GETTING_STARTED));
        menu.add(getAction(ACTION_HELP_CONTEXT));
        menu.add(getAction(ACTION_HELP_SHORTCUTS));
        menu.add(getAction(ACTION_HELP_CLI));
        menu.add(getAction(ACTION_HELP_DIAGNOSTICS));

        menu.add(new org.eclipse.jface.action.Separator());

        menu.add(new org.eclipse.jface.action.GroupMarker(IWorkbenchActionConstants.HELP_START));

        // Check for Updates and Install New Software... are contributed by the
        // standard Eclipse p2 UI (org.eclipse.equinox.p2.ui.sdk) into the
        // additions group below.

        org.eclipse.jface.action.IAction sitesAction = new org.eclipse.jface.action.Action(
                "Manage Software &Sites...") {
            @Override
            public void run() {
                org.eclipse.ui.dialogs.PreferencesUtil.createPreferenceDialogOn(
                        window.getShell(), SITES_PREFERENCE_PAGE, null, null).open();
            }
        };
        menu.add(sitesAction);

        org.eclipse.ui.menus.CommandContributionItemParameter detailsParameter =
                new org.eclipse.ui.menus.CommandContributionItemParameter(
                        window, null, COMMAND_INSTALLATION_DETAILS,
                        org.eclipse.ui.menus.CommandContributionItem.STYLE_PUSH);
        detailsParameter.label = "&Installation Details...";
        menu.add(new org.eclipse.ui.menus.CommandContributionItem(detailsParameter));

        menu.add(new org.eclipse.jface.action.Separator());

        menu.add(getAction(ActionFactory.ABOUT.getId()));

        menu.add(new org.eclipse.jface.action.GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        menu.add(new org.eclipse.jface.action.GroupMarker(IWorkbenchActionConstants.HELP_END));
        return menu;
    }

    // ---------------------------------------------------------- EVO-Studio family actions

    private void showView(String viewId) {
        try {
            window.getActivePage().showView(viewId);
        } catch (org.eclipse.ui.PartInitException e) {
            org.eclipse.jface.dialogs.MessageDialog.openError(window.getShell(),
                    "EVO-Studio", "Could not open the view: " + e.getMessage());
        }
    }

    private void executeCommand(String commandId) {
        org.eclipse.ui.handlers.IHandlerService handlerService =
                window.getService(org.eclipse.ui.handlers.IHandlerService.class);
        if (handlerService != null) {
            try {
                handlerService.executeCommand(commandId, null);
            } catch (Exception e) {
                org.eclipse.jface.dialogs.MessageDialog.openError(window.getShell(),
                        "EVO-Studio", "Command failed: " + e.getMessage());
            }
        }
    }
}
