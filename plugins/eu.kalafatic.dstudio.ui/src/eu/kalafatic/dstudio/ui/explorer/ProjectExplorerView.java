package eu.kalafatic.dstudio.ui.explorer;

import eu.kalafatic.dstudio.core.model.Project;
import eu.kalafatic.dstudio.ui.DStudioUiPlugin;
import eu.kalafatic.dstudio.ui.workspace.EclipseWorkspaceBridge;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;

public class ProjectExplorerView extends ViewPart {

    public static final String VIEW_ID = "eu.kalafatic.dstudio.ui.view.projectExplorer";

    private TreeViewer viewer;
    private final ProjectExplorerContentProvider contentProvider =
            new ProjectExplorerContentProvider(null);

    @Override
    public void createPartControl(Composite parent) {
        viewer = new TreeViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        contentProvider.setProjectService(DStudioUiPlugin.getInstance().projectService());
        viewer.setContentProvider(contentProvider);
        viewer.setLabelProvider(new ProjectExplorerLabelProvider());
        viewer.setInput("root");
        getSite().setSelectionProvider(viewer);
        viewer.addDoubleClickListener(new IDoubleClickListener() {
            @Override
            public void doubleClick(DoubleClickEvent event) {
                openSelection(event.getSelection());
            }
        });
        MenuManager menuManager = new MenuManager();
        menuManager.setRemoveAllWhenShown(true);
        menuManager.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager manager) {
                Object element = selectedElement();
                if (element instanceof ProjectExplorerContentProvider.ProjectFileEntry entry) {
                    fillFileEntryMenu(manager, entry);
                } else if (element instanceof Project project) {
                    // Unified contextual actions for the project (same ids and domain
                    // operations as the web interface), plus the family terminal/web
                    // entries through the shared launchers.
                    eu.kalafatic.dstudio.core.contextual.ContextSelection selection =
                            eu.kalafatic.dstudio.core.contextual.ContextSelection
                                    .builder("DSTUDIO", "RCP", "project")
                                    .object(project.id(), project.name())
                                    .state("rootPath", project.rootPath())
                                    .build();
                    eu.kalafatic.dstudio.ui.contextual.ContextualMenuSupport.fill(manager,
                            DStudioUiPlugin.getInstance().contextualActionRegistry()
                                    .resolve(selection),
                            selection, getSite().getShell(), ProjectExplorerView.this::refresh);
                    manager.add(new org.eclipse.jface.action.Separator());
                    manager.add(new org.eclipse.jface.action.Action("Open Terminal") {
                        @Override
                        public void run() {
                            eu.kalafatic.dstudio.ui.workbench.TerminalLauncher
                                    .open(project.rootPath());
                        }
                    });
                    manager.add(new org.eclipse.jface.action.Action("Open Web Interface") {
                        @Override
                        public void run() {
                            eu.kalafatic.dstudio.ui.workbench.WebInterfaceLauncher
                                    .openWebInterface();
                        }
                    });
                    manager.add(new org.eclipse.jface.action.Action("Refresh") {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } else {
                    manager.add(new org.eclipse.jface.action.Action("Open Project…") {
                        @Override
                        public void run() {
                            org.eclipse.ui.handlers.IHandlerService handlerService =
                                    getSite().getService(
                                            org.eclipse.ui.handlers.IHandlerService.class);
                            if (handlerService != null) {
                                try {
                                    handlerService.executeCommand(
                                            "eu.kalafatic.dstudio.command.openProject", null);
                                } catch (Exception e) {
                                    System.err.println("EVO-Studio: open command failed: "
                                            + e.getMessage());
                                }
                            }
                        }
                    });
                }
            }
        });
        Menu menu = menuManager.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);
        getSite().registerContextMenu(menuManager, viewer);
    }

    /**
     * Context menu for a file/directory entry: Open (files only, same behavior as the
     * double-click), Copy Path, Open Terminal (directories only) and Refresh.
     */
    private void fillFileEntryMenu(IMenuManager manager,
            ProjectExplorerContentProvider.ProjectFileEntry entry) {
        if (entry.file().isFile()) {
            manager.add(new org.eclipse.jface.action.Action("Open") {
                @Override
                public void run() {
                    openFile(entry);
                }
            });
        }
        manager.add(new org.eclipse.jface.action.Action("Copy Path") {
            @Override
            public void run() {
                copyToClipboard(entry.file().getAbsolutePath());
            }
        });
        manager.add(new org.eclipse.jface.action.Separator());
        if (entry.file().isDirectory()) {
            manager.add(new org.eclipse.jface.action.Action("Open Terminal") {
                @Override
                public void run() {
                    eu.kalafatic.dstudio.ui.workbench.TerminalLauncher
                            .open(entry.file().getAbsolutePath());
                }
            });
            manager.add(new org.eclipse.jface.action.Separator());
        }
        manager.add(new org.eclipse.jface.action.Action("Refresh") {
            @Override
            public void run() {
                refresh();
            }
        });
    }

    private static void copyToClipboard(String text) {
        org.eclipse.swt.dnd.Clipboard clipboard = new org.eclipse.swt.dnd.Clipboard(
                org.eclipse.ui.PlatformUI.getWorkbench().getDisplay());
        clipboard.setContents(new Object[] { text },
                new org.eclipse.swt.dnd.Transfer[] {
                    org.eclipse.swt.dnd.TextTransfer.getInstance() });
        clipboard.dispose();
    }

    private void openFile(ProjectExplorerContentProvider.ProjectFileEntry entry) {
        IWorkbenchPage page = getSite().getPage();
        try {
            IDE.openEditorOnFileStore(page, EFS.getStore(entry.file().toURI()));
        } catch (Exception e) {
            System.err.println("EVO-Studio: failed to open editor for "
                    + entry.file() + ": " + e.getMessage());
        }
    }

    private void openSelection(ISelection selection) {
        if (!(selection instanceof IStructuredSelection structured)
                || structured.isEmpty()) {
            return;
        }
        Object element = structured.getFirstElement();
        if (element instanceof ProjectExplorerContentProvider.ProjectFileEntry entry
                && entry.file().isFile()) {
            openFile(entry);
        }
    }

    private Object selectedElement() {
        ISelection selection = viewer.getSelection();
        if (selection instanceof IStructuredSelection structured
                && !structured.isEmpty()) {
            return structured.getFirstElement();
        }
        return null;
    }

    public void refresh() {
        if (viewer != null && !viewer.getControl().isDisposed()) {
            viewer.refresh();
        }
    }

    public Project selectedProject() {
        ISelection selection = viewer.getSelection();
        if (selection instanceof IStructuredSelection structured
                && !structured.isEmpty()) {
            Object element = structured.getFirstElement();
            if (element instanceof Project project) {
                return project;
            }
            if (element instanceof ProjectExplorerContentProvider.ProjectFileEntry entry) {
                return entry.project();
            }
        }
        return null;
    }

    public String selectedFilePath() {
        ISelection selection = viewer.getSelection();
        if (selection instanceof IStructuredSelection structured
                && !structured.isEmpty()
                && structured.getFirstElement()
                        instanceof ProjectExplorerContentProvider.ProjectFileEntry entry) {
            return entry.file().getAbsolutePath();
        }
        return null;
    }

    @Override
    public void setFocus() {
        if (viewer != null) {
            viewer.getControl().setFocus();
        }
    }
}
