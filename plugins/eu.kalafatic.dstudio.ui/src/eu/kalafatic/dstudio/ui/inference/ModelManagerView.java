package eu.kalafatic.dstudio.ui.inference;

import eu.kalafatic.dstudio.application.models.ModelManagementClient;
import eu.kalafatic.dstudio.application.models.ModelManagementClient.DownloadJobHandle;
import eu.kalafatic.dstudio.application.models.ModelManagementClient.DownloadStatusInfo;
import eu.kalafatic.dstudio.application.models.ModelManagementClient.ManagedModel;
import eu.kalafatic.dstudio.application.models.ModelManagementClient.ModelProviderInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

/**
 * Model Manager (shared family infrastructure): provider pull, installed
 * models, live download progress, cancellation and removal. All state lives
 * in the shared ENR model management service; this view consumes it through
 * the local EVO-Studio backend ({@code /api/models}), exactly like the web UI.
 * Progress is polled on the SWT UI thread with a timer that is cancelled on
 * dispose; no blocking work ever runs on the UI thread.
 */
public class ModelManagerView extends ViewPart {

    public static final String VIEW_ID = "eu.kalafatic.dstudio.ui.view.modelmanager";

    private static final int POLL_INTERVAL_MS = 1000;

    private final ModelManagementClient client = new ModelManagementClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "dstudio-model-manager");
        thread.setDaemon(true);
        return thread;
    });

    private List<ModelProviderInfo> providers = new ArrayList<>();
    private int selectedProvider = -1;
    private org.eclipse.swt.widgets.Combo providerCombo;
    private Text modelInput;
    private Button pullButton;
    private Label statusLabel;
    private TableViewer installedViewer;
    private Composite downloadsBox;
    private volatile boolean disposed = false;

    @Override
    public void createPartControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(container);

        Label providerLabel = new Label(container, SWT.NONE);
        providerLabel.setText("Provider");
        providerCombo = new org.eclipse.swt.widgets.Combo(container, SWT.READ_ONLY);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(providerCombo);
        providerCombo.addListener(SWT.Selection, event -> {
            selectedProvider = providerCombo.getSelectionIndex();
            refreshInstalled();
        });

        Label modelLabel = new Label(container, SWT.NONE);
        modelLabel.setText("Model");
        modelInput = new Text(container, SWT.BORDER);
        modelInput.setMessage("model identifier, e.g. qwen3-vl:30b");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(modelInput);
        modelInput.addListener(SWT.DefaultSelection, event -> startPull());

        pullButton = new Button(container, SWT.PUSH);
        pullButton.setText("Pull Model");
        Button refreshButton = new Button(container, SWT.PUSH);
        refreshButton.setText("Refresh");
        pullButton.addListener(SWT.Selection, event -> startPull());
        refreshButton.addListener(SWT.Selection, event -> {
            refreshProviders();
            refreshInstalled();
            refreshDownloads();
        });

        statusLabel = new Label(container, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(statusLabel);

        Label downloadsLabel = new Label(container, SWT.NONE);
        downloadsLabel.setText("Active downloads");
        GridDataFactory.fillDefaults().span(2, 1).applyTo(downloadsLabel);
        downloadsBox = new Composite(container, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(downloadsBox);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(downloadsBox);

        Label installedLabel = new Label(container, SWT.NONE);
        installedLabel.setText("Installed models");
        GridDataFactory.fillDefaults().span(2, 1).applyTo(installedLabel);
        installedViewer = new TableViewer(container, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, true).span(2, 1)
                .applyTo(installedViewer.getTable());
        installedViewer.setContentProvider(ArrayContentProvider.getInstance());
        Table table = installedViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        TableViewerColumn modelColumn = new TableViewerColumn(installedViewer, SWT.NONE);
        modelColumn.getColumn().setText("Model");
        modelColumn.getColumn().setWidth(320);
        modelColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ManagedModel) element).id();
            }
        });
        TableViewerColumn sizeColumn = new TableViewerColumn(installedViewer, SWT.NONE);
        sizeColumn.getColumn().setText("Size");
        sizeColumn.getColumn().setWidth(110);
        sizeColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return formatBytes(((ManagedModel) element).sizeBytes());
            }
        });

        Button removeButton = new Button(container, SWT.PUSH);
        removeButton.setText("Remove Selected");
        removeButton.addListener(SWT.Selection, event -> removeSelected());

        refreshProviders();
        refreshDownloads();
        schedulePoll();
    }

    private void startPull() {
        if (selectedProvider < 0 || selectedProvider >= providers.size()) {
            statusLabel.setText("No provider available — is the ENR model management "
                    + "service reachable?");
            return;
        }
        ModelProviderInfo provider = providers.get(selectedProvider);
        String model = modelInput.getText().trim();
        if (model.isBlank()) {
            statusLabel.setText("Enter a model identifier first");
            return;
        }
        statusLabel.setText("Starting pull of " + provider.id() + "/" + model + "\u2026");
        pullButton.setEnabled(false);
        CompletableFuture.supplyAsync(() -> client.pull(provider.id(), model), executor)
                .whenComplete((job, throwable) -> {
                    Display display = Display.getDefault();
                    if (display == null || display.isDisposed()) {
                        return;
                    }
                    display.asyncExec(() -> {
                        pullButton.setEnabled(true);
                        if (throwable != null) {
                            statusLabel.setText("Pull failed: " + throwable.getMessage());
                            return;
                        }
                        DownloadJobHandle handle = job;
                        statusLabel.setText("Pull queued: " + provider.id() + "/" + model
                                + " (job " + handle.jobId() + ")");
                        modelInput.setText("");
                        refreshDownloads();
                    });
                });
    }

    private void removeSelected() {
        if (selectedProvider < 0 || selectedProvider >= providers.size()) {
            return;
        }
        IStructuredSelection selection = installedViewer.getStructuredSelection();
        if (!(selection.getFirstElement() instanceof ManagedModel model)) {
            statusLabel.setText("Select an installed model first");
            return;
        }
        ModelProviderInfo provider = providers.get(selectedProvider);
        statusLabel.setText("Removing " + model.id() + " from " + provider.id() + "\u2026");
        CompletableFuture.supplyAsync(() -> client.remove(provider.id(), model.id()), executor)
                .whenComplete((removed, throwable) -> async(() -> {
                    if (throwable != null) {
                        statusLabel.setText("Remove failed: " + throwable.getMessage());
                        return;
                    }
                    statusLabel.setText(Boolean.TRUE.equals(removed)
                            ? "Model removed: " + model.id() : "Model was not removed");
                    refreshInstalled();
                }));
    }

    private void refreshProviders() {
        CompletableFuture.supplyAsync(client::providers, executor).whenComplete((list, throwable) ->
                async(() -> {
                    if (throwable != null) {
                        statusLabel.setText("Providers unavailable: " + throwable.getMessage());
                        return;
                    }
                    if (disposed || providerCombo.isDisposed()) {
                        return;
                    }
                    List<ModelProviderInfo> pullable = new ArrayList<>();
                    for (ModelProviderInfo provider : list) {
                        if (provider.capabilities().contains("MODEL_PULL")) {
                            pullable.add(provider);
                        }
                    }
                    providers = pullable;
                    providerCombo.removeAll();
                    for (ModelProviderInfo provider : providers) {
                        providerCombo.add(provider.id() + " \u2014 " + provider.displayName());
                    }
                    if (providers.isEmpty()) {
                        statusLabel.setText("No model providers registered in ENR "
                                + "(check enr.yaml model_providers).");
                        return;
                    }
                    providerCombo.select(0);
                    selectedProvider = 0;
                    refreshInstalled();
                }));
    }

    private void refreshInstalled() {
        if (selectedProvider < 0 || selectedProvider >= providers.size()) {
            installedViewer.setInput(List.of());
            return;
        }
        ModelProviderInfo provider = providers.get(selectedProvider);
        CompletableFuture.supplyAsync(() -> client.installed(provider.id()), executor)
                .whenComplete((models, throwable) -> async(() -> {
                    if (throwable != null) {
                        statusLabel.setText("Installed models unavailable: "
                                + throwable.getMessage());
                        return;
                    }
                    installedViewer.setInput(models);
                }));
    }

    private void refreshDownloads() {
        CompletableFuture.supplyAsync(client::downloads, executor).whenComplete((jobs, throwable) ->
                async(() -> {
                    if (throwable != null) {
                        return; // backend unavailable: keep the previous view
                    }
                    renderDownloads(jobs);
                }));
    }

    private void renderDownloads(List<DownloadStatusInfo> jobs) {
        for (var child : downloadsBox.getChildren()) {
            child.dispose();
        }
        List<DownloadStatusInfo> active = new ArrayList<>();
        for (DownloadStatusInfo job : jobs) {
            if (!"COMPLETED".equals(job.status()) && !"FAILED".equals(job.status())
                    && !"CANCELLED".equals(job.status())) {
                active.add(job);
            }
        }
        if (active.isEmpty()) {
            Label empty = new Label(downloadsBox, SWT.NONE);
            empty.setText("No active downloads.");
            downloadsBox.layout();
            return;
        }
        for (DownloadStatusInfo job : active) {
            Composite row = new Composite(downloadsBox, SWT.NONE);
            GridLayoutFactory.fillDefaults().numColumns(3).applyTo(row);
            GridDataFactory.fillDefaults().grab(true, false).applyTo(row);

            Label label = new Label(row, SWT.WRAP);
            GridDataFactory.fillDefaults().grab(true, false).applyTo(label);
            String bytes = job.totalBytes() > 0
                    ? job.percent() + "% (" + formatBytes(job.completedBytes())
                    + " / " + formatBytes(job.totalBytes()) + ")"
                    : formatBytes(job.completedBytes());
            label.setText(job.provider() + " / " + job.model() + " \u2014 " + job.status()
                    + (job.operation().isBlank() ? "" : " (" + job.operation() + ")")
                    + " " + bytes);

            ProgressBar bar = new ProgressBar(row, SWT.SMOOTH);
            bar.setMinimum(0);
            bar.setMaximum(job.totalBytes() > 0 ? 1000 : 1);
            bar.setSelection(job.totalBytes() > 0
                    ? Math.min(1000, Math.max(0, job.percent() * 10)) : 0);
            GridDataFactory.fillDefaults().hint(220, SWT.DEFAULT).applyTo(bar);

            Button cancel = new Button(row, SWT.PUSH);
            cancel.setText("Cancel");
            cancel.addListener(SWT.Selection, event -> {
                cancel.setEnabled(false);
                CompletableFuture.supplyAsync(() -> client.cancel(job.jobId()), executor)
                        .whenComplete((cancelled, throwable) -> async(() -> {
                            if (throwable != null) {
                                cancel.setEnabled(true);
                                statusLabel.setText("Cancel failed: " + throwable.getMessage());
                                return;
                            }
                            statusLabel.setText(Boolean.TRUE.equals(cancelled)
                                    ? "Cancel requested for job " + job.jobId()
                                    : "Job already finished: " + job.jobId());
                            refreshDownloads();
                        }));
            });

            if (!job.error().isBlank()) {
                Label error = new Label(row, SWT.WRAP);
                GridDataFactory.fillDefaults().grab(true, false).span(3, 1).applyTo(error);
                error.setText(job.error());
            }
        }
        downloadsBox.layout();
    }

    private void schedulePoll() {
        if (disposed) {
            return;
        }
        Display.getDefault().timerExec(POLL_INTERVAL_MS, () -> {
            if (!disposed) {
                refreshDownloads();
                schedulePoll();
            }
        });
    }

    private void async(Runnable runnable) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(runnable);
    }

    @Override
    public void setFocus() {
        modelInput.setFocus();
    }

    @Override
    public void dispose() {
        disposed = true;
        executor.shutdownNow();
        super.dispose();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "\u2014";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
