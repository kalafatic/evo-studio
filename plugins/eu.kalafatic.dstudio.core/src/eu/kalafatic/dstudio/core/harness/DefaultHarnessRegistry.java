package eu.kalafatic.dstudio.core.harness;

import eu.kalafatic.dstudio.core.model.HarnessDescriptor;
import eu.kalafatic.dstudio.core.store.HarnessStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class DefaultHarnessRegistry implements HarnessRegistry {

    private final HarnessStore store;
    private final Map<String, HarnessAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, HarnessDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> configurations = new ConcurrentHashMap<>();
    private volatile String selectedHarnessId;

    public DefaultHarnessRegistry(HarnessStore store) {
        this.store = store;
        for (HarnessDescriptor descriptor : store.listDescriptors()) {
            descriptors.put(descriptor.getId(), descriptor);
            Map<String, String> config = store.findConfiguration(descriptor.getId());
            if (!config.isEmpty()) {
                configurations.put(descriptor.getId(), new ConcurrentHashMap<>(config));
            }
        }
        this.selectedHarnessId = store.findSelectedHarnessId().orElse("");
    }

    @Override
    public void registerAdapter(HarnessAdapter adapter) {
        adapters.put(adapter.getHarnessId(), adapter);
        HarnessDescriptor descriptor = descriptors.get(adapter.getHarnessId());
        if (descriptor == null) {
            descriptor = new HarnessDescriptor(adapter.getHarnessId(), adapter.getHarnessName(),
                    adapter.getHarnessType(), adapter.getCapabilities(), true,
                    HarnessDescriptor.HarnessStatus.UNKNOWN, "", Instant.now(), Instant.now());
            descriptors.put(adapter.getHarnessId(), descriptor);
        } else {
            descriptor.setStatus(HarnessDescriptor.HarnessStatus.UNKNOWN, "");
        }
        Map<String, String> storedConfig = configurations.get(adapter.getHarnessId());
        if (storedConfig != null && !storedConfig.isEmpty()) {
            adapter.updateConfiguration(storedConfig);
        }
        store.saveDescriptor(descriptor);
    }

    @Override
    public void unregisterHarness(String harnessId) {
        adapters.remove(harnessId);
        // Full registration removal: the descriptor and its persisted record are
        // deleted as well, and the default selection is cleared when it pointed at
        // the removed harness (contextual action "Remove Registration").
        HarnessDescriptor removed = descriptors.remove(harnessId);
        if (removed != null) {
            store.deleteDescriptor(harnessId);
        }
        configurations.remove(harnessId);
        if (harnessId.equals(selectedHarnessId)) {
            selectedHarnessId = "";
            store.clearSelectedHarnessId();
        }
    }

    @Override
    public List<HarnessDescriptor> listHarnesses() {
        return new ArrayList<>(descriptors.values());
    }

    @Override
    public Optional<HarnessDescriptor> findHarness(String harnessId) {
        return Optional.ofNullable(descriptors.get(harnessId));
    }

    @Override
    public Optional<HarnessAdapter> findAdapter(String harnessId) {
        return Optional.ofNullable(adapters.get(harnessId));
    }

    @Override
    public void selectDefaultHarness(String harnessId) {
        if (harnessId == null || harnessId.isBlank()) {
            this.selectedHarnessId = "";
            store.clearSelectedHarnessId();
            return;
        }
        HarnessDescriptor descriptor = descriptors.get(harnessId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown harness: " + harnessId);
        }
        this.selectedHarnessId = harnessId;
        store.saveSelectedHarnessId(harnessId);
    }

    @Override
    public Optional<String> getSelectedDefaultHarnessId() {
        return selectedHarnessId.isBlank() ? Optional.empty()
                : Optional.of(selectedHarnessId);
    }

    @Override
    public Optional<HarnessAdapter> findSelectedDefaultAdapter() {
        Optional<String> id = getSelectedDefaultHarnessId();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        HarnessDescriptor descriptor = descriptors.get(id.get());
        HarnessAdapter adapter = adapters.get(id.get());
        if (descriptor == null || !descriptor.isEnabled() || adapter == null) {
            return Optional.empty();
        }
        return Optional.of(adapter);
    }

    @Override
    public void setHarnessEnabled(String harnessId, boolean enabled) {
        HarnessDescriptor descriptor = descriptors.get(harnessId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown harness: " + harnessId);
        }
        descriptor.setEnabled(enabled);
        store.saveDescriptor(descriptor);
    }

    @Override
    public void updateConfiguration(String harnessId, Map<String, String> configuration) {
        HarnessDescriptor descriptor = descriptors.get(harnessId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown harness: " + harnessId);
        }
        // Merge with the previously stored configuration instead of replacing it:
        // configuration dialogs omit masked secrets ("••••••••",
        // "****** (configured)") so the stored value must survive an update that
        // does not mention the key. Explicitly provided values always win.
        Map<String, String> merged = new java.util.LinkedHashMap<>(
                configurations.getOrDefault(harnessId, Map.of()));
        if (configuration != null && !configuration.isEmpty()) {
            merged.putAll(configuration);
        }
        configurations.put(harnessId, new ConcurrentHashMap<>(merged));
        store.saveConfiguration(harnessId, merged);
        HarnessAdapter adapter = adapters.get(harnessId);
        if (adapter != null) {
            adapter.updateConfiguration(merged);
        }
        store.saveDescriptor(descriptor);
    }

    public Map<String, String> getConfiguration(String harnessId) {
        HarnessAdapter adapter = adapters.get(harnessId);
        if (adapter != null) {
            return adapter.getConnectionConfiguration();
        }
        return configurations.getOrDefault(harnessId, Map.of());
    }

    @Override
    public HarnessConnectionTestResult testConnection(String harnessId) {
        HarnessAdapter adapter = adapters.get(harnessId);
        if (adapter == null) {
            return HarnessConnectionTestResult.error("Unknown harness: " + harnessId);
        }
        HarnessConnectionTestResult result = adapter.testConnection();
        HarnessDescriptor descriptor = descriptors.get(harnessId);
        if (descriptor != null) {
            descriptor.setStatus(result.success()
                    ? HarnessDescriptor.HarnessStatus.CONNECTED
                    : HarnessDescriptor.HarnessStatus.DISCONNECTED, result.message());
            store.saveDescriptor(descriptor);
        }
        return result;
    }

    @Override
    public List<HarnessCapabilityDescriptor> aggregateCapabilities() {
        return aggregateCapabilities(null);
    }

    @Override
    public List<HarnessCapabilityDescriptor> aggregateCapabilities(String harnessId) {
        List<HarnessCapabilityDescriptor> result = new CopyOnWriteArrayList<>();
        for (HarnessAdapter adapter : adapters.values()) {
            if (harnessId != null && !adapter.getHarnessId().equals(harnessId)) {
                continue;
            }
            HarnessDescriptor descriptor = descriptors.get(adapter.getHarnessId());
            if (descriptor == null || !descriptor.isEnabled()) {
                continue;
            }
            try {
                result.add(adapter.getCapabilityDescriptor());
            } catch (RuntimeException e) {
                System.err.println("EVO-Studio capability discovery failed for "
                        + adapter.getHarnessId() + ": " + e.getMessage());
            }
        }
        return result;
    }
}
