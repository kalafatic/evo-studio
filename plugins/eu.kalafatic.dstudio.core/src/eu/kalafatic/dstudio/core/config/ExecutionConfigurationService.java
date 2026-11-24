package eu.kalafatic.dstudio.core.config;

import eu.kalafatic.dstudio.core.store.ExecutionConfigurationStore;
import java.util.Optional;

/**
 * Resolution service for execution configurations (Phase 9): GLOBAL defaults → PROJECT
 * overrides → TASK overrides → EXECUTION snapshot. The resolved configuration is validated
 * by the execution service before any harness is started and persisted as an immutable
 * snapshot so later configuration changes never affect a running or recorded execution.
 */
public interface ExecutionConfigurationService {

    void saveGlobal(ExecutionConfiguration configuration);

    void saveProjectOverride(String projectId, ExecutionConfiguration configuration);

    void saveTaskOverride(String taskKey, ExecutionConfiguration configuration);

    void removeTaskOverride(String taskKey);

    /** Removes the persisted global defaults, returning to the recommended defaults. */
    void removeGlobal();

    /** Removes the persisted override for a project, if any. */
    void removeProjectOverride(String projectId);

    Optional<ExecutionConfiguration> getGlobal();

    Optional<ExecutionConfiguration> getProjectOverride(String projectId);

    Optional<ExecutionConfiguration> getTaskOverride(String taskKey);

    /**
     * Resolves the effective configuration for a task: global defaults, overridden by the
     * project override, overridden by the task override.
     */
    ExecutionConfiguration resolve(String projectId, String taskKey);

    /**
     * Persists the final, immutable configuration snapshot for an accepted execution.
     */
    void saveSnapshot(String executionId, ExecutionConfiguration resolved);

    Optional<ExecutionConfiguration> getSnapshot(String executionId);
}
