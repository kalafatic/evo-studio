package eu.kalafatic.dstudio.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.kalafatic.dstudio.core.agent.AgentProfile;
import eu.kalafatic.dstudio.core.agent.AgentProfileRegistry;
import eu.kalafatic.dstudio.core.agent.DefaultAgentProfileRegistry;
import eu.kalafatic.dstudio.core.config.DefaultExecutionConfigurationService;
import eu.kalafatic.dstudio.core.config.ExecutionConfiguration;
import eu.kalafatic.dstudio.core.config.ExecutionConfigurationService;
import eu.kalafatic.dstudio.core.harness.DefaultHarnessRegistry;
import eu.kalafatic.dstudio.core.harness.HarnessRegistry;
import eu.kalafatic.dstudio.core.inference.DefaultInferenceEngineRegistry;
import eu.kalafatic.dstudio.core.inference.InferenceEngineRegistry;
import eu.kalafatic.dstudio.infrastructure.persistence.SchemaMigrationService;
import eu.kalafatic.dstudio.infrastructure.persistence.SecretCodec;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteAgentProfileStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteDatabaseManager;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteExecutionConfigurationStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteHarnessStore;
import eu.kalafatic.dstudio.infrastructure.persistence.SqliteInferenceEngineStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unified multi-agent control platform tests: agent-profile registry (CRUD, clone, default
 * selection, persistence across registry instances) and scope-based execution
 * configuration resolution (GLOBAL → PROJECT → TASK → snapshot).
 */
class AgentProfileAndExecutionConfigTest {

    @TempDir
    Path tempDir;

    private SqliteDatabaseManager databaseManager;
    private SecretCodec codec;
    private HarnessRegistry harnessRegistry;
    private InferenceEngineRegistry engineRegistry;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new SqliteDatabaseManager(tempDir.resolve("agent-test.db"));
        new SchemaMigrationService().migrate(databaseManager.connection());
        codec = new SecretCodec(tempDir);
        harnessRegistry = new DefaultHarnessRegistry(
                new SqliteHarnessStore(databaseManager.connection(), codec));
        engineRegistry = new DefaultInferenceEngineRegistry(
                new SqliteInferenceEngineStore(databaseManager.connection(), codec));
    }

    @AfterEach
    void tearDown() {
        databaseManager.close();
    }

    private AgentProfileRegistry newRegistry() {
        return new DefaultAgentProfileRegistry(
                new SqliteAgentProfileStore(databaseManager.connection()),
                harnessRegistry, engineRegistry);
    }

    private ExecutionConfigurationService newConfigService() {
        return new DefaultExecutionConfigurationService(
                new SqliteExecutionConfigurationStore(databaseManager.connection()));
    }

    private AgentProfile profile(String id, String name, String harness, String provider,
            String model) {
        return new AgentProfile(id, name, "desc", "developer", "Be rigorous.", List.of(),
                List.of(), harness, provider, model, Map.of(), Instant.now(), Instant.now());
    }

    @Test
    void createListPersistAndClone() {
        AgentProfileRegistry registry = newRegistry();
        registry.createProfile(profile("agent-1", "Dev", "", "", ""));
        registry.createProfile(profile("agent-2", "Reviewer", "", "", ""));
        assertEquals(2, registry.listProfiles().size());
        // persisted store: a fresh registry over the same DB sees the same profiles
        assertEquals(2, newRegistry().listProfiles().size());
        AgentProfile clone = registry.cloneProfile("agent-1", "Dev Clone");
        assertTrue(clone.getId().startsWith("agent-"));
        assertEquals("Dev Clone", clone.getName());
        assertEquals("Be rigorous.", clone.getSystemInstructions());
        assertEquals(3, registry.listProfiles().size());
    }

    @Test
    void createRejectsDuplicateIdAndName() {
        AgentProfileRegistry registry = newRegistry();
        registry.createProfile(profile("agent-1", "Dev", "", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> registry.createProfile(profile("agent-1", "Other", "", "", "")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.createProfile(profile("agent-9", "Dev", "", "", "")));
    }

    @Test
    void defaultSelectionPersistsAndDeleteClearsIt() {
        AgentProfileRegistry registry = newRegistry();
        registry.createProfile(profile("agent-1", "Dev", "", "", ""));
        registry.selectDefaultProfile("agent-1");
        assertEquals("agent-1", registry.getDefaultProfileId().orElseThrow());
        assertEquals("agent-1", newRegistry().getDefaultProfileId().orElseThrow());
        assertTrue(registry.deleteProfile("agent-1"));
        assertFalse(registry.getDefaultProfileId().isPresent());
    }

    @Test
    void compatibilityChecksPreferredEnvironment() {
        AgentProfileRegistry registry = newRegistry();
        harnessRegistry.registerAdapter(new FakeHarnessAdapter("opencode", "OPENCODE"));
        harnessRegistry.registerAdapter(new FakeHarnessAdapter("aider", "AIDER"));
        engineRegistry.registerEngine(new FakeInferenceEngineAdapter("ollama", "OLLAMA"));
        registry.createProfile(profile("agent-1", "Dev", "opencode", "ollama", ""));
        AgentProfileRegistry.CompatibilityResult compatible =
                registry.checkCompatibility("agent-1", "opencode", "ollama", "llama3");
        assertTrue(compatible.compatible(), compatible.message());
        AgentProfileRegistry.CompatibilityResult wrongHarness =
                registry.checkCompatibility("agent-1", "aider", "ollama", "llama3");
        assertFalse(wrongHarness.compatible());
        assertTrue(wrongHarness.message().contains("prefers harness"), wrongHarness.message());
        AgentProfileRegistry.CompatibilityResult unknownProvider =
                registry.checkCompatibility("agent-1", "opencode", "unknown", "llama3");
        assertFalse(unknownProvider.compatible());
        assertTrue(unknownProvider.message().contains("Unknown provider"),
                unknownProvider.message());
    }

    @Test
    void executionConfigurationResolvesGlobalProjectTaskAndSnapshots() {
        ExecutionConfigurationService service = newConfigService();
        service.saveGlobal(new ExecutionConfiguration("dcode", "enr", "global-model",
                "", Map.of("timeoutSeconds", "1800"), List.of("read:workspace"),
                Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL));
        service.saveProjectOverride("p1", new ExecutionConfiguration("", "ollama", "",
                "", Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_PROJECT));
        service.saveTaskOverride("t1", new ExecutionConfiguration("", "", "task-model",
                "agent-9", Map.of("timeoutSeconds", "600"), List.of("write:workspace"),
                Map.of(), Map.of(), ExecutionConfiguration.SOURCE_TASK));
        ExecutionConfiguration resolved = service.resolve("p1", "t1");
        assertEquals("dcode", resolved.harnessId());
        assertEquals("ollama", resolved.providerId());
        assertEquals("task-model", resolved.modelId());
        assertEquals("agent-9", resolved.agentProfileId());
        assertEquals("600", resolved.executionParameters().get("timeoutSeconds"));
        assertEquals(List.of("write:workspace"), resolved.toolPermissions());
        service.saveSnapshot("task-abc", resolved);
        ExecutionConfiguration snapshot = service.getSnapshot("task-abc").orElseThrow();
        assertEquals(resolved.harnessId(), snapshot.harnessId());
        assertEquals(resolved.providerId(), snapshot.providerId());
        assertEquals(resolved.modelId(), snapshot.modelId());
        assertEquals(resolved.agentProfileId(), snapshot.agentProfileId());
        assertEquals(resolved.executionParameters(), snapshot.executionParameters());
        assertEquals(resolved.toolPermissions(), snapshot.toolPermissions());
        assertEquals(ExecutionConfiguration.SOURCE_EXECUTION, snapshot.source());
        // later global changes must not modify the snapshot
        service.saveGlobal(new ExecutionConfiguration("opencode", "ollama", "other", "",
                Map.of(), List.of(), Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL));
        assertEquals("dcode", service.getSnapshot("task-abc").orElseThrow().harnessId());
    }

    @Test
    void executionConfigurationInheritsWhenSpecificUnset() {
        ExecutionConfigurationService service = newConfigService();
        ExecutionConfiguration resolved = service.resolve("p-none", "t-none");
        assertEquals(ExecutionConfiguration.SOURCE_GLOBAL, resolved.source());
        assertTrue(resolved.harnessId().isBlank());
        service.saveGlobal(new ExecutionConfiguration("dcode", "", "", "", Map.of(),
                List.of(), Map.of(), Map.of(), ExecutionConfiguration.SOURCE_GLOBAL));
        resolved = service.resolve("p-none", "t-none");
        assertEquals("dcode", resolved.harnessId());
    }

    @Test
    void removeGlobalAndProjectOverrideRestoreDefaults() {
        ExecutionConfigurationService service = newConfigService();
        service.saveGlobal(new ExecutionConfiguration("dcode", "enr", "global-model",
                "", Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_GLOBAL));
        service.saveProjectOverride("p1", new ExecutionConfiguration("", "ollama",
                "p1-model", "", Map.of(), List.of(), Map.of(), Map.of(),
                ExecutionConfiguration.SOURCE_PROJECT));

        // project-specific values do not leak into unrelated projects
        ExecutionConfiguration other = service.resolve("p2", null);
        assertEquals("enr", other.providerId());
        assertEquals("global-model", other.modelId());

        // removing the project override falls back to the global defaults
        service.removeProjectOverride("p1");
        ExecutionConfiguration restored = service.resolve("p1", null);
        assertEquals("enr", restored.providerId());
        assertEquals("global-model", restored.modelId());

        // removing the global defaults returns to empty (recommended defaults)
        service.removeGlobal();
        ExecutionConfiguration empty = service.resolve("p1", null);
        assertTrue(empty.harnessId().isBlank());
        assertTrue(empty.providerId().isBlank());
        assertTrue(empty.modelId().isBlank());
    }
}
