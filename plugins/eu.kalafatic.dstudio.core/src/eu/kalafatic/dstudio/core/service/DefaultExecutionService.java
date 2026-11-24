package eu.kalafatic.dstudio.core.service;

import eu.kalafatic.dstudio.core.agent.AgentProfile;
import eu.kalafatic.dstudio.core.agent.AgentProfileRegistry;
import eu.kalafatic.dstudio.core.config.ExecutionConfiguration;
import eu.kalafatic.dstudio.core.config.ExecutionConfigurationService;
import eu.kalafatic.dstudio.core.event.EventBus;
import eu.kalafatic.dstudio.core.harness.HarnessAdapter;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionRequest;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionResult;
import eu.kalafatic.dstudio.core.harness.HarnessExecutionSink;
import eu.kalafatic.dstudio.core.harness.HarnessRegistry;
import eu.kalafatic.dstudio.core.inference.InferenceEngineDescriptor;
import eu.kalafatic.dstudio.core.inference.InferenceEngineRegistry;
import eu.kalafatic.dstudio.core.model.ChangeReview;
import eu.kalafatic.dstudio.core.model.ExecutionEvent;
import eu.kalafatic.dstudio.core.model.ExecutionEventType;
import eu.kalafatic.dstudio.core.model.ExecutionResult;
import eu.kalafatic.dstudio.core.model.ExecutionStatus;
import eu.kalafatic.dstudio.core.model.ExecutionTask;
import eu.kalafatic.dstudio.core.model.HarnessDescriptor;
import eu.kalafatic.dstudio.core.model.ReviewDecision;
import eu.kalafatic.dstudio.core.store.ExecutionStore;
import eu.kalafatic.dstudio.core.store.ReviewStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public class DefaultExecutionService implements ExecutionService {

    private final HarnessRegistry harnessRegistry;
    private final ExecutionStore store;
    private final ReviewStore reviewStore;
    private final WorkspaceDiffService diffService;
    private final EventBus eventBus;
    private final AgentProfileRegistry agentProfileRegistry;
    private final InferenceEngineRegistry inferenceEngineRegistry;
    private final ExecutionConfigurationService configurationService;
    private final ExecutorService executor;
    private final Map<String, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    /** Legacy-compatible constructor: agent/engine/config dimensions unset. */
    public DefaultExecutionService(HarnessRegistry harnessRegistry, ExecutionStore store,
            ReviewStore reviewStore, WorkspaceDiffService diffService, EventBus eventBus) {
        this(harnessRegistry, store, reviewStore, diffService, eventBus, null, null, null);
    }

    public DefaultExecutionService(HarnessRegistry harnessRegistry, ExecutionStore store,
            ReviewStore reviewStore, WorkspaceDiffService diffService, EventBus eventBus,
            AgentProfileRegistry agentProfileRegistry,
            InferenceEngineRegistry inferenceEngineRegistry,
            ExecutionConfigurationService configurationService) {
        this.harnessRegistry = harnessRegistry;
        this.store = store;
        this.reviewStore = reviewStore;
        this.diffService = diffService;
        this.eventBus = eventBus;
        this.agentProfileRegistry = agentProfileRegistry;
        this.inferenceEngineRegistry = inferenceEngineRegistry;
        this.configurationService = configurationService;
        ThreadFactory factory = r -> {
            Thread thread = new Thread(r, "dstudio-execution");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newCachedThreadPool(factory);
    }

    @Override
    public ExecutionTask submit(ExecutionRequest request) {
        if (shutdownRequested.get()) {
            throw new IllegalStateException("Execution service is shut down");
        }
        if (request.instruction() == null && request.prompt() == null) {
            throw new IllegalArgumentException("instruction or prompt is required");
        }
        if (request.workingDirectory() == null || request.workingDirectory().isBlank()) {
            throw new IllegalArgumentException("workingDirectory is required");
        }
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<ExecutionTask> existing = store.findExecutionByIdempotencyKey(
                    request.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        HarnessDescriptor descriptor = harnessRegistry.findHarness(request.harnessId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown harness: " + request.harnessId()));
        if (!descriptor.isEnabled()) {
            throw new IllegalArgumentException("Harness is disabled: " + request.harnessId());
        }
        // Resolve GLOBAL -> PROJECT -> TASK (request fields are the task-level overrides).
        ExecutionConfiguration resolved = resolveConfiguration(request);
        // Validate the provider dimension and the agent-profile compatibility before
        // accepting the execution; incompatible combinations are never silently executed.
        validateExecutionEnvironment(request, resolved);
        String id = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ExecutionTask task = new ExecutionTask(id, resolved.harnessId(), request.projectId(),
                request.idempotencyKey(), request.instruction(), request.prompt(),
                resolved.modelId(), request.agent(), request.autoApprove(),
                request.workingDirectory(), resolved.providerId(), resolved.agentProfileId(),
                resolved.executionParameters(), Instant.now());
        store.saveExecution(task);
        if (configurationService != null) {
            configurationService.saveSnapshot(id, resolved);
        }
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            store.saveIdempotency(request.idempotencyKey(), id);
        }
        appendEvent(id, ExecutionEventType.EXECUTION_QUEUED, "Task queued",
                Map.of("harnessId", resolved.harnessId(), "providerId", resolved.providerId(),
                        "agentProfileId", resolved.agentProfileId()));
        cancellationFlags.put(id, new AtomicBoolean(false));
        executor.submit(() -> runTask(id, request.timeout()));
        return task;
    }

    private ExecutionConfiguration resolveConfiguration(ExecutionRequest request) {
        ExecutionConfiguration base = ExecutionConfiguration.empty(
                ExecutionConfiguration.SOURCE_GLOBAL);
        if (configurationService != null) {
            base = configurationService.resolve(request.projectId(), null);
        }
        ExecutionConfiguration taskOverride = new ExecutionConfiguration(request.harnessId(),
                request.providerId(), request.model(), request.agentProfileId(),
                request.executionParameters(), request.toolPermissions(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_TASK);
        return base.overrideWith(taskOverride);
    }

    private void validateExecutionEnvironment(ExecutionRequest request,
            ExecutionConfiguration resolved) {
        String providerId = resolved.providerId();
        if (providerId != null && !providerId.isBlank()) {
            if (inferenceEngineRegistry == null) {
                throw new IllegalArgumentException(
                        "Provider selected but no inference-engine registry is available: "
                                + providerId);
            }
            Optional<InferenceEngineDescriptor> engine =
                    inferenceEngineRegistry.findEngine(providerId);
            if (engine.isEmpty()) {
                throw new IllegalArgumentException("Unknown provider: " + providerId);
            }
            if (!engine.get().isEnabled()) {
                throw new IllegalArgumentException("Provider is disabled: " + providerId);
            }
        }
        String agentProfileId = resolved.agentProfileId();
        if (agentProfileId != null && !agentProfileId.isBlank()) {
            if (agentProfileRegistry == null) {
                throw new IllegalArgumentException(
                        "Agent profile selected but no agent-profile registry is available: "
                                + agentProfileId);
            }
            AgentProfileRegistry.CompatibilityResult compatibility =
                    agentProfileRegistry.checkCompatibility(agentProfileId,
                            resolved.harnessId(), resolved.providerId(), resolved.modelId());
            if (!compatibility.compatible()) {
                throw new IllegalArgumentException("Agent profile '" + agentProfileId
                        + "' is incompatible with the selected execution environment: "
                        + compatibility.message());
            }
        }
    }

    private void runTask(String executionId, Duration timeout) {
        ExecutionTask task = store.findExecution(executionId).orElse(null);
        if (task == null) {
            return;
        }
        AtomicBoolean cancelled = cancellationFlags.computeIfAbsent(executionId,
                k -> new AtomicBoolean(false));
        if (shutdownRequested.get() || cancelled.get()) {
            finishCancelled(task, "Cancelled before execution started");
            return;
        }
        Optional<HarnessAdapter> adapterOptional = harnessRegistry.findAdapter(task.getHarnessId());
        HarnessDescriptor descriptor = harnessRegistry.findHarness(task.getHarnessId())
                .orElse(null);
        if (adapterOptional.isEmpty() || descriptor == null || !descriptor.isEnabled()) {
            task.markFailed("Harness is not available: " + task.getHarnessId(), -1);
            store.updateExecution(task);
            appendEvent(executionId, ExecutionEventType.EXECUTION_FAILED,
                    "Harness is not available: " + task.getHarnessId());
            return;
        }
        HarnessAdapter adapter = adapterOptional.get();
        task.markRunning();
        store.updateExecution(task);
        appendEvent(executionId, ExecutionEventType.EXECUTION_STARTED, "Execution started",
                Map.of("harnessId", task.getHarnessId(), "providerId", task.getProviderId(),
                        "agentProfileId", task.getAgentProfileId()));
        Path workingDirectory = Path.of(task.getWorkingDirectory());
        Object snapshot = null;
        if (diffService != null && diffService.isGitRepository(workingDirectory)) {
            snapshot = diffService.takeSnapshot(workingDirectory);
        }
        BooleanSupplier cancellationSupplier = cancelled::get;
        AgentProfile agentProfile = resolveAgentProfile(task);
        HarnessExecutionRequest harnessRequest = new HarnessExecutionRequest(executionId,
                workingDirectory, task.getInstruction(), task.getPrompt(), task.getModel(),
                task.getAgent(), task.isAutoApprove(), timeout, cancellationSupplier,
                task.getProviderId(), task.getAgentProfileId(),
                agentProfile == null ? "" : agentProfile.getSystemInstructions(),
                agentProfile == null ? List.of() : agentProfile.getToolPermissions(),
                task.getExecutionParameters());
        HarnessExecutionResult result;
        try {
            result = adapter.execute(harnessRequest, new Sink(task));
        } catch (RuntimeException e) {
            result = HarnessExecutionResult.error(-1,
                    "Harness adapter failed: " + e.getMessage());
        }
        if (result.cancelled() || cancelled.get()) {
            finishCancelled(task, result.errorMessage());
            return;
        }
        if (result.timedOut()) {
            task.markTimedOut(result.errorMessage());
            store.updateExecution(task);
            appendEvent(executionId, ExecutionEventType.EXECUTION_FAILED,
                    "Execution timed out: " + result.errorMessage());
            return;
        }
        if (!result.handled()) {
            task.markFailed("Harness did not handle the task", -1);
            store.updateExecution(task);
            appendEvent(executionId, ExecutionEventType.EXECUTION_FAILED,
                    "Harness did not handle the task");
            return;
        }
        if (!result.success()) {
            task.markFailed(result.errorMessage(), result.exitCode());
            store.updateExecution(task);
            appendEvent(executionId, ExecutionEventType.EXECUTION_FAILED,
                    "Execution failed: " + result.errorMessage());
            return;
        }
        List<String> modifiedFiles = new ArrayList<>(result.modifiedFiles());
        if (modifiedFiles.isEmpty() && diffService != null && snapshot != null) {
            modifiedFiles = new ArrayList<>(diffService.collectChangedFiles(snapshot,
                    workingDirectory));
        }
        String diffText = result.diffText();
        Map<String, String> diffs = new LinkedHashMap<>();
        if (diffText.isBlank() && diffService != null && snapshot != null) {
            StringBuilder all = new StringBuilder();
            for (String file : modifiedFiles) {
                String fileDiff = diffService.diffText(snapshot, workingDirectory, file);
                diffs.put(file, fileDiff);
                all.append(fileDiff).append('\n');
            }
            diffText = all.toString();
        }
        task.markCompleted(result.summary(), result.exitCode());
        store.updateExecution(task);
        appendEvent(executionId, ExecutionEventType.EXECUTION_COMPLETED,
                "Execution completed", Map.of("modifiedFiles", modifiedFiles,
                        "summary", result.summary() == null ? "" : result.summary()));
        if (reviewStore != null && !modifiedFiles.isEmpty()) {
            createReviewFor(executionId, modifiedFiles, diffs);
        }
    }

    private AgentProfile resolveAgentProfile(ExecutionTask task) {
        if (agentProfileRegistry == null || task.getAgentProfileId() == null
                || task.getAgentProfileId().isBlank()) {
            return null;
        }
        return agentProfileRegistry.findProfile(task.getAgentProfileId()).orElse(null);
    }

    private void createReviewFor(String executionId, List<String> modifiedFiles,
            Map<String, String> diffs) {
        List<ChangeReview.ReviewFile> files = new ArrayList<>();
        for (String path : modifiedFiles) {
            files.add(new ChangeReview.ReviewFile(path,
                    diffs.getOrDefault(path, ""), ReviewDecision.PENDING));
        }
        String reviewId = "review-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        reviewStore.saveReview(new ChangeReview(reviewId, executionId, files));
    }

    private void finishCancelled(ExecutionTask task, String reason) {
        task.markCancelled(reason);
        store.updateExecution(task);
        appendEvent(task.getId(), ExecutionEventType.EXECUTION_CANCELLED,
                "Execution cancelled: " + (reason == null ? "" : reason));
    }

    @Override
    public void cancel(String executionId) {
        AtomicBoolean flag = cancellationFlags.computeIfAbsent(executionId,
                k -> new AtomicBoolean(false));
        if (flag.getAndSet(true)) {
            return;
        }
        ExecutionTask task = store.findExecution(executionId).orElse(null);
        if (task != null && task.getStatus() == ExecutionStatus.QUEUED) {
            finishCancelled(task, "Cancelled while queued");
        }
    }

    @Override
    public Optional<ExecutionTask> getTask(String executionId) {
        return store.findExecution(executionId);
    }

    @Override
    public Optional<ExecutionTask> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return store.findExecutionByIdempotencyKey(idempotencyKey);
    }

    @Override
    public Optional<ExecutionResult> getResult(String executionId) {
        Optional<ExecutionTask> task = store.findExecution(executionId);
        if (task.isEmpty()) {
            return Optional.empty();
        }
        ExecutionTask t = task.get();
        if (!t.isTerminal()) {
            return Optional.of(new ExecutionResult(executionId, t.getHarnessId(), t.getStatus(),
                    "", "", -1, false, false, List.of(), "", t.getStartedAt(),
                    t.getCompletedAt(), Map.of()));
        }
        Map<String, Long> metrics = new LinkedHashMap<>();
        if (t.getStartedAt() != null && t.getCompletedAt() != null) {
            metrics.put("durationMs", Duration.between(t.getStartedAt(),
                    t.getCompletedAt()).toMillis());
        }
        return Optional.of(new ExecutionResult(executionId, t.getHarnessId(), t.getStatus(),
                t.getSummary(), t.getErrorMessage(), t.getExitCode(), t.isTimedOut(),
                t.getStatus() == ExecutionStatus.CANCELLED, List.of(), "", t.getStartedAt(),
                t.getCompletedAt(), metrics));
    }

    @Override
    public List<ExecutionEvent> getEvents(String executionId, long afterSequence, int limit) {
        return store.loadEvents(executionId, afterSequence, limit);
    }

    @Override
    public List<ExecutionTask> listTasks() {
        return store.listExecutions();
    }

    @Override
    public List<ExecutionTask> listActiveTasks() {
        return store.listOpenExecutions();
    }

    @Override
    public void reconcileAfterRestart() {
        for (ExecutionTask task : store.listOpenExecutions()) {
            task.markNotResumed("EVO-Studio restarted; execution cannot be resumed");
            store.updateExecution(task);
            appendEvent(task.getId(), ExecutionEventType.EXECUTION_FAILED,
                    "EVO-Studio restarted; execution cannot be resumed");
        }
    }

    @Override
    public void shutdown() {
        shutdownRequested.set(true);
        for (AtomicBoolean flag : cancellationFlags.values()) {
            flag.set(true);
        }
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long nextSequence(String executionId) {
        AtomicLong counter = sequences.computeIfAbsent(executionId,
                k -> new AtomicLong(store.nextSequence(k)));
        return counter.incrementAndGet();
    }

    private void appendEvent(String executionId, ExecutionEventType type, String message) {
        appendEvent(executionId, type, message, Map.of());
    }

    private void appendEvent(String executionId, ExecutionEventType type, String message,
            Map<String, Object> data) {
        ExecutionEvent event = ExecutionEvent.of(nextSequence(executionId), executionId, type,
                message, data);
        store.appendEvent(event);
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    private class Sink implements HarnessExecutionSink {

        private final ExecutionTask task;

        Sink(ExecutionTask task) {
            this.task = task;
        }

        @Override
        public void emit(ExecutionEventType type, String message) {
            appendEvent(task.getId(), type, message);
        }

        @Override
        public void emit(ExecutionEventType type, String message, Map<String, Object> data) {
            appendEvent(task.getId(), type, message, data);
        }

        @Override
        public void progress(double progress, String message) {
            task.updateProgress(progress, message);
            appendEvent(task.getId(), ExecutionEventType.PROGRESS_UPDATED,
                    message == null ? "" : message, Map.of("progress", progress));
        }

        @Override
        public void log(String line) {
            appendEvent(task.getId(), ExecutionEventType.EXECUTION_LOG, line);
        }
    }
}
