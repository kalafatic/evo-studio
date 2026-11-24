package eu.kalafatic.dstudio.ui.execution;

import eu.kalafatic.dstudio.core.event.EventBus;
import eu.kalafatic.dstudio.core.model.ExecutionEvent;
import eu.kalafatic.dstudio.core.model.ExecutionStatus;
import eu.kalafatic.dstudio.core.model.ExecutionTask;
import eu.kalafatic.dstudio.core.service.ExecutionService;
import eu.kalafatic.dstudio.ui.DStudioUiPlugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.ViewPart;

public class ExecutionView extends ViewPart {

    public static final String VIEW_ID = "eu.kalafatic.dstudio.ui.view.execution";

    private TableViewer viewer;
    private Button runButton;
    private Button cancelButton;
    private Button refreshButton;
    private AutoCloseable eventSubscription;

    @Override
    public void createPartControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(3).equalWidth(true).applyTo(container);
        viewer = new TableViewer(container, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, true).span(3, 1).applyTo(viewer.getTable());
        TableViewerColumn idColumn = new TableViewerColumn(viewer, SWT.NONE);
        idColumn.getColumn().setText("Task");
        idColumn.getColumn().setWidth(130);
        idColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ExecutionTask) element).getId();
            }
        });
        TableViewerColumn harnessColumn = new TableViewerColumn(viewer, SWT.NONE);
        harnessColumn.getColumn().setText("Harness");
        harnessColumn.getColumn().setWidth(90);
        harnessColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ExecutionTask) element).getHarnessId();
            }
        });
        TableViewerColumn providerColumn = new TableViewerColumn(viewer, SWT.NONE);
        providerColumn.getColumn().setText("Provider");
        providerColumn.getColumn().setWidth(90);
        providerColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ExecutionTask) element).getProviderId();
            }
        });
        TableViewerColumn agentColumn = new TableViewerColumn(viewer, SWT.NONE);
        agentColumn.getColumn().setText("Agent");
        agentColumn.getColumn().setWidth(100);
        agentColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ExecutionTask) element).getAgentProfileId();
            }
        });
        TableViewerColumn modelColumn = new TableViewerColumn(viewer, SWT.NONE);
        modelColumn.getColumn().setText("Model");
        modelColumn.getColumn().setWidth(110);
        modelColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                String model = ((ExecutionTask) element).getModel();
                return model == null ? "" : model;
            }
        });
        TableViewerColumn statusColumn = new TableViewerColumn(viewer, SWT.NONE);
        statusColumn.getColumn().setText("Status");
        statusColumn.getColumn().setWidth(100);
        statusColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ExecutionTask) element).getStatus().name();
            }
        });
        TableViewerColumn messageColumn = new TableViewerColumn(viewer, SWT.NONE);
        messageColumn.getColumn().setText("Message");
        messageColumn.getColumn().setWidth(420);
        messageColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                ExecutionTask task = (ExecutionTask) element;
                return task.getStatusMessage() == null || task.getStatusMessage().isBlank()
                        ? task.getInstruction() == null ? "" : truncate(task.getInstruction(),
                                80)
                        : truncate(task.getStatusMessage(), 80);
            }
        });
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        runButton = new Button(container, SWT.PUSH);
        runButton.setText("Run Task…");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(runButton);
        cancelButton = new Button(container, SWT.PUSH);
        cancelButton.setText("Cancel Selected");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(cancelButton);
        refreshButton = new Button(container, SWT.PUSH);
        refreshButton.setText("Refresh");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(refreshButton);
        runButton.addListener(SWT.Selection, e -> onRun());
        cancelButton.addListener(SWT.Selection, e -> onCancel());
        refreshButton.addListener(SWT.Selection, e -> refresh());

        // Unified contextual actions for executions (cancel/details/history).
        eu.kalafatic.dstudio.core.contextual.ContextualActionRegistry contextualRegistry =
                DStudioUiPlugin.getInstance().contextualActionRegistry();
        if (contextualRegistry != null) {
            eu.kalafatic.dstudio.ui.contextual.ContextualMenuSupport.attach(
                    viewer.getTable(), contextualRegistry, () -> {
                        ExecutionTask task = selectedTask();
                        if (task == null) {
                            return eu.kalafatic.dstudio.core.contextual.ContextSelection
                                    .builder("DSTUDIO", "RCP", "execution")
                                    .object("", "").build();
                        }
                        return eu.kalafatic.dstudio.core.contextual.ContextSelection
                                .builder("DSTUDIO", "RCP", "execution")
                                .object(task.getId(), task.getId())
                                .state("status", task.getStatus().name())
                                .projectId(task.getProjectId())
                                .harnessId(task.getHarnessId())
                                .providerId(task.getProviderId())
                                .agentId(task.getAgentProfileId())
                                .modelId(task.getModel())
                                .build();
                    }, this::refresh);
        }

        EventBus eventBus = DStudioUiPlugin.getInstance().eventBus();
        if (eventBus != null) {
            eventSubscription = eventBus.subscribe(ExecutionEvent.class, event -> {
                Display display = Display.getDefault();
                if (display != null) {
                    display.asyncExec(() -> {
                        if (viewer != null && !viewer.getControl().isDisposed()) {
                            viewer.refresh();
                        }
                    });
                }
            });
        }
        refresh();
    }

    private String truncate(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length - 3) + "...";
    }

    private ExecutionTask selectedTask() {
        IStructuredSelection selection = viewer.getStructuredSelection();
        return selection.isEmpty() ? null
                : (ExecutionTask) selection.getFirstElement();
    }

    private void onRun() {
        RunHarnessDialog dialog = new RunHarnessDialog(getSite().getShell());
        if (dialog.open() == Window.OK) {
            refresh();
        }
    }

    private void onCancel() {
        ExecutionTask task = selectedTask();
        ExecutionService executionService = DStudioUiPlugin.getInstance().executionService();
        if (task != null && executionService != null && !task.isTerminal()) {
            executionService.cancel(task.getId());
            refresh();
        }
    }

    public void refresh() {
        if (viewer == null || viewer.getControl().isDisposed()) {
            return;
        }
        ExecutionService executionService = DStudioUiPlugin.getInstance().executionService();
        List<ExecutionTask> tasks = executionService == null ? List.of()
                : new ArrayList<>(executionService.listTasks());
        tasks.sort(Comparator.comparing(ExecutionTask::getCreatedAt).reversed());
        viewer.setInput(tasks);
    }

    @Override
    public void dispose() {
        if (eventSubscription != null) {
            try {
                eventSubscription.close();
            } catch (Exception e) {
                System.err.println("EVO-Studio: failed to close event subscription: "
                        + e.getMessage());
            }
        }
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (viewer != null) {
            viewer.getControl().setFocus();
        }
    }
}
