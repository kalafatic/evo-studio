package eu.kalafatic.dstudio.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.kalafatic.dstudio.core.inference.InferenceEngineAdapter;
import eu.kalafatic.dstudio.harness.enr.EnrInferenceEngineAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Live evidence against the real family ENR runtime (ENR 1.0 wire, canonical health
 * at {@code /enr/v1/health}): connection, runtime status mapping, model discovery,
 * blocking chat and SSE streaming. Skips silently when ENR is absent (family test
 * conventions). Endpoint override: {@code -Ddstudio.enr.endpoint=...}.
 */
class EnrLiveInferenceTest {

    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:28780";

    private EnrInferenceEngineAdapter adapter() {
        EnrInferenceEngineAdapter adapter = new EnrInferenceEngineAdapter();
        adapter.updateConfiguration(Map.of("endpoint", System.getProperty(
                "dstudio.enr.endpoint", DEFAULT_ENDPOINT), "timeoutSeconds", "60"));
        return adapter;
    }

    @Test
    @Timeout(60)
    void liveEnrConnectionAndRuntimeStatus() {
        EnrInferenceEngineAdapter adapter = adapter();
        var connection = adapter.testConnection();
        if (!connection.success()) {
            return; // ENR not running: silent skip per family convention
        }
        assertTrue(connection.message().contains("ENR"), connection.message());
        InferenceEngineAdapter.InferenceRuntimeStatus status = adapter.runtimeStatus();
        assertTrue(status.ready(), "live ENR must report running=true");
        assertTrue(status.availableProcessors() > 0,
                "live ENR must report real processor count");
        System.out.println("[EnrLiveInferenceTest] live runtime: processors="
                + status.availableProcessors() + " load=" + status.systemLoadAverage()
                + " heapMb=" + status.usedHeapMb() + "/" + status.maxHeapMb()
                + " gpu=" + status.gpuDetected() + " engines=" + status.activeEngines());
    }

    @Test
    @Timeout(60)
    void liveEnrModelDiscovery() {
        EnrInferenceEngineAdapter adapter = adapter();
        if (!adapter.testConnection().success()) {
            return;
        }
        List<InferenceEngineAdapter.InferenceModel> models = adapter.listModels();
        assertFalse(models.isEmpty(), "live ENR must publish its native models");
        System.out.println("[EnrLiveInferenceTest] live models: " + models.size()
                + " -> " + models.stream().map(InferenceEngineAdapter.InferenceModel::id)
                        .limit(5).toList());
    }

    @Test
    @Timeout(120)
    void liveEnrBlockingChat() {
        EnrInferenceEngineAdapter adapter = adapter();
        if (!adapter.testConnection().success()) {
            return;
        }
        String model = selectModel(adapter);
        if (model == null) {
            return;
        }
        InferenceEngineAdapter.InferenceChatResponse response = adapter.chat(
                new InferenceEngineAdapter.InferenceChatRequest(model,
                        List.of(InferenceEngineAdapter.InferenceChatMessage.user("hi")),
                        0.7, 64));
        assertFalse(response.finishReason().isBlank(),
                "live ENR chat must return a finish reason");
        System.out.println("[EnrLiveInferenceTest] live chat: model=" + model
                + " finishReason=" + response.finishReason() + " engine="
                + response.engine() + " content='" + response.content() + "'");
    }

    @Test
    @Timeout(120)
    void liveEnrStreamingChatDeliversChunksAndDone() throws Exception {
        EnrInferenceEngineAdapter adapter = adapter();
        if (!adapter.testConnection().success()) {
            return;
        }
        String model = selectModel(adapter);
        if (model == null) {
            return;
        }
        StringBuilder content = new StringBuilder();
        AtomicBoolean doneSeen = new AtomicBoolean();
        adapter.streamChat(new InferenceEngineAdapter.InferenceChatRequest(model,
                List.of(InferenceEngineAdapter.InferenceChatMessage.user("Say hello")),
                0.7, 64), chunk -> {
            if (chunk.done()) {
                doneSeen.set(true);
            } else {
                content.append(chunk.text());
            }
        }, () -> false);
        assertTrue(doneSeen.get(), "live ENR stream must terminate with a done chunk");
        System.out.println("[EnrLiveInferenceTest] live stream: model=" + model
                + " chunks='" + content + "'");
    }

    /** Prefers the known tiny native models; falls back to the first discovered model. */
    private String selectModel(EnrInferenceEngineAdapter adapter) {
        try {
            List<String> ids = adapter.listModels().stream()
                    .map(InferenceEngineAdapter.InferenceModel::id).toList();
            for (String preferred : List.of("tiny-evo", "evo")) {
                if (ids.contains(preferred)) {
                    return preferred;
                }
            }
            return ids.isEmpty() ? null : ids.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
