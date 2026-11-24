package eu.kalafatic.dstudio.core.contextual.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import eu.kalafatic.dstudio.core.contextual.ContextActionIds;
import eu.kalafatic.dstudio.core.contextual.ContextSelection;
import eu.kalafatic.dstudio.core.contextual.ContextualAction;
import eu.kalafatic.dstudio.core.contextual.ContextualActionDescriptor;
import eu.kalafatic.dstudio.core.contextual.ContextualActionProvider;
import eu.kalafatic.dstudio.core.inference.InferenceService;
import eu.kalafatic.dstudio.core.inference.InferenceSession;
import eu.kalafatic.dstudio.core.model.ExecutionStatus;
import eu.kalafatic.dstudio.core.model.ExecutionTask;
import eu.kalafatic.dstudio.core.service.ExecutionService;

/**
 * Contextual actions for executions (coding tasks) and direct-inference sessions.
 * Only state-permitted operations are contributed: cancel for active work, details and
 * history for everything. Pause/resume/archive/delete are not supported by the EVO-Studio
 * execution service and are shown disabled with an explanation (TRYST implements them
 * on its own lifecycle).
 */
public class ExecutionContextActionProvider implements ContextualActionProvider {

    private final ExecutionService executionService;
    private final InferenceService inferenceService;

    public ExecutionContextActionProvider(ExecutionService executionService,
            InferenceService inferenceService) {
        this.executionService = executionService;
        this.inferenceService = inferenceService;
    }

    @Override
    public List<String> contextTypes() {
        return ProviderSupport.list(ProviderSupport.CTX_EXECUTION, ProviderSupport.CTX_TASK,
                ProviderSupport.CTX_SESSION);
    }

    @Override
    public List<ContextualAction> actionsFor(ContextSelection selection) {
        if (ProviderSupport.CTX_SESSION.equals(selection.objectType())) {
            return sessionActions(selection);
        }
        return executionActions(selection);
    }

    private List<ContextualAction> executionActions(ContextSelection selection) {
        List<ContextualAction> actions = new ArrayList<>();
        Optional<ExecutionTask> taskOpt = executionService == null ? Optional.empty()
                : executionService.getTask(selection.objectId());
        if (taskOpt.isEmpty()) {
            return actions;
        }
        ExecutionTask task = taskOpt.get();
        boolean active = task.getStatus() == ExecutionStatus.QUEUED
                || task.getStatus() == ExecutionStatus.RUNNING;
        boolean single = !selection.multiSelect();

        ContextualActionDescriptor cancel = ProviderSupport.descriptor(
                ContextActionIds.SESSION_CANCEL, "Cancel",
                ContextualActionDescriptor.GROUP_LIFECYCLE, ProviderSupport.CTX_EXECUTION,
                ProviderSupport.CTX_TASK)
                .withDescription("Cancels the running execution.");
        actions.add(single && active ? ProviderSupport.enabled(cancel)
                : ProviderSupport.disabled(cancel, !single
                        ? "Not available for multiple selection"
                        : "Execution is not running"));

        ContextualActionDescriptor details = ProviderSupport.descriptor(
                ContextActionIds.SESSION_DETAILS, "View Execution Details",
                ContextualActionDescriptor.GROUP_INSPECTION, ProviderSupport.CTX_EXECUTION,
                ProviderSupport.CTX_TASK);
        actions.add(single ? ProviderSupport.enabled(details)
                : ProviderSupport.disabled(details, "Not available for multiple selection"));

        ContextualActionDescriptor history = ProviderSupport.descriptor(
                ContextActionIds.SESSION_HISTORY, "View History",
                ContextualActionDescriptor.GROUP_INSPECTION, ProviderSupport.CTX_EXECUTION,
                ProviderSupport.CTX_TASK);
        actions.add(single ? ProviderSupport.enabled(history)
                : ProviderSupport.disabled(history, "Not available for multiple selection"));

        for (String id : new String[] { ContextActionIds.SESSION_PAUSE,
                ContextActionIds.SESSION_RESUME, ContextActionIds.SESSION_ARCHIVE,
                ContextActionIds.SESSION_DELETE }) {
            String label = switch (id) {
                case ContextActionIds.SESSION_PAUSE -> "Pause";
                case ContextActionIds.SESSION_RESUME -> "Resume";
                case ContextActionIds.SESSION_ARCHIVE -> "Archive";
                default -> "Delete";
            };
            String group = ContextActionIds.SESSION_DELETE.equals(id)
                    ? ContextualActionDescriptor.GROUP_DANGER
                    : ContextualActionDescriptor.GROUP_LIFECYCLE;
            ContextualActionDescriptor descriptor = ProviderSupport.descriptor(id, label,
                    group, ProviderSupport.CTX_EXECUTION, ProviderSupport.CTX_TASK);
            if (ContextActionIds.SESSION_DELETE.equals(id)) {
                descriptor = descriptor.withConfirmation(
                        ContextualActionDescriptor.Confirmation.DANGEROUS);
            }
            actions.add(ProviderSupport.disabled(descriptor,
                    "Not supported by the EVO-Studio execution service"));
        }
        return actions;
    }

    private List<ContextualAction> sessionActions(ContextSelection selection) {
        List<ContextualAction> actions = new ArrayList<>();
        Optional<InferenceSession> sessionOpt = inferenceService == null ? Optional.empty()
                : inferenceService.getSession(selection.objectId());
        if (sessionOpt.isEmpty()) {
            return actions;
        }
        InferenceSession session = sessionOpt.get();
        boolean open = session.status() == InferenceSession.Status.OPEN;
        boolean single = !selection.multiSelect();

        ContextualActionDescriptor cancel = ProviderSupport.descriptor(
                ContextActionIds.SESSION_CANCEL, "Cancel",
                ContextualActionDescriptor.GROUP_LIFECYCLE, ProviderSupport.CTX_SESSION);
        actions.add(single && open ? ProviderSupport.enabled(cancel)
                : ProviderSupport.disabled(cancel,
                        single ? "Session is not active" : "Not available for multiple "
                                + "selection"));

        ContextualActionDescriptor details = ProviderSupport.descriptor(
                ContextActionIds.SESSION_DETAILS, "View Session Details",
                ContextualActionDescriptor.GROUP_INSPECTION, ProviderSupport.CTX_SESSION);
        actions.add(single ? ProviderSupport.enabled(details)
                : ProviderSupport.disabled(details, "Not available for multiple selection"));
        return actions;
    }
}
