package eu.kalafatic.dstudio.core.config;

import eu.kalafatic.dstudio.core.store.ExecutionConfigurationStore;
import java.util.Optional;

/**
 * Default scope-based execution-configuration resolution:
 * GLOBAL → PROJECT → TASK → EXECUTION snapshot.
 */
public class DefaultExecutionConfigurationService implements ExecutionConfigurationService {

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_PROJECT = "PROJECT";
    public static final String SCOPE_TASK = "TASK";
    public static final String KEY_GLOBAL = "default";

    private final ExecutionConfigurationStore store;

    public DefaultExecutionConfigurationService(ExecutionConfigurationStore store) {
        this.store = store;
    }

    @Override
    public void saveGlobal(ExecutionConfiguration configuration) {
        store.save(SCOPE_GLOBAL, KEY_GLOBAL,
                withSource(configuration, ExecutionConfiguration.SOURCE_GLOBAL));
    }

    @Override
    public void saveProjectOverride(String projectId, ExecutionConfiguration configuration) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required");
        }
        store.save(SCOPE_PROJECT, projectId,
                withSource(configuration, ExecutionConfiguration.SOURCE_PROJECT));
    }

    @Override
    public void saveTaskOverride(String taskKey, ExecutionConfiguration configuration) {
        if (taskKey == null || taskKey.isBlank()) {
            throw new IllegalArgumentException("taskKey is required");
        }
        store.save(SCOPE_TASK, taskKey,
                withSource(configuration, ExecutionConfiguration.SOURCE_TASK));
    }

    private static ExecutionConfiguration withSource(ExecutionConfiguration configuration,
            String source) {
        if (configuration == null) {
            return ExecutionConfiguration.empty(source);
        }
        return new ExecutionConfiguration(configuration.harnessId(),
                configuration.providerId(), configuration.modelId(),
                configuration.agentProfileId(), configuration.executionParameters(),
                configuration.toolPermissions(), configuration.resourceLimits(),
                configuration.harnessSettings(), source);
    }

    @Override
    public void removeTaskOverride(String taskKey) {
        store.delete(SCOPE_TASK, taskKey);
    }

    @Override
    public void removeGlobal() {
        store.delete(SCOPE_GLOBAL, KEY_GLOBAL);
    }

    @Override
    public void removeProjectOverride(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        store.delete(SCOPE_PROJECT, projectId);
    }

    @Override
    public Optional<ExecutionConfiguration> getGlobal() {
        return store.find(SCOPE_GLOBAL, KEY_GLOBAL);
    }

    @Override
    public Optional<ExecutionConfiguration> getProjectOverride(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return store.find(SCOPE_PROJECT, projectId);
    }

    @Override
    public Optional<ExecutionConfiguration> getTaskOverride(String taskKey) {
        if (taskKey == null || taskKey.isBlank()) {
            return Optional.empty();
        }
        return store.find(SCOPE_TASK, taskKey);
    }

    @Override
    public ExecutionConfiguration resolve(String projectId, String taskKey) {
        ExecutionConfiguration resolved = getGlobal()
                .orElseGet(() -> ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_GLOBAL));
        Optional<ExecutionConfiguration> project = getProjectOverride(projectId);
        if (project.isPresent()) {
            resolved = resolved.overrideWith(project.get());
        }
        Optional<ExecutionConfiguration> task = getTaskOverride(taskKey);
        if (task.isPresent()) {
            resolved = resolved.overrideWith(task.get());
        }
        return resolved;
    }

    @Override
    public void saveSnapshot(String executionId, ExecutionConfiguration resolved) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId is required");
        }
        store.saveSnapshot(executionId, resolved == null
                ? ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_EXECUTION)
                : resolved.overrideWith(ExecutionConfiguration.empty(ExecutionConfiguration.SOURCE_EXECUTION)));
    }

    @Override
    public Optional<ExecutionConfiguration> getSnapshot(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        return store.findSnapshot(executionId);
    }
}
