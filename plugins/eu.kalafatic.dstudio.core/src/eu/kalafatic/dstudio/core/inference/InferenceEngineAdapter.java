package eu.kalafatic.dstudio.core.inference;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Inference-engine adapter SPI (Phase 6, WP-6.7). EVO-Studio manages inference engines
 * (ENR by default) independently from coding harnesses: model discovery, model lifecycle,
 * generation, streaming and runtime status. Engines are replaceable — ENR, Ollama or
 * future providers — and never own EVO-Studio sessions, executions or repositories.
 */
public interface InferenceEngineAdapter {

    String getEngineId();

    String getEngineName();

    String getEngineType();

    boolean isAvailable();

    InferenceConnectionTestResult testConnection();

    default void updateConfiguration(Map<String, String> configuration) {
    }

    default Map<String, String> getConnectionConfiguration() {
        return Map.of();
    }

    /** Discovers the models published by this engine. */
    List<InferenceModel> listModels();

    /** CPU/memory/GPU runtime status as exposed by the engine. */
    InferenceRuntimeStatus runtimeStatus();

    /** Loads/warms the model (best-effort per engine). */
    boolean loadModel(String modelId);

    /** Unloads the model where supported by the engine. */
    boolean unloadModel(String modelId);

    /** Blocking chat generation. */
    InferenceChatResponse chat(InferenceChatRequest request);

    /** Streaming chat generation; the final chunk has {@link InferenceChatChunk#done()} == true. */
    void streamChat(InferenceChatRequest request, Consumer<InferenceChatChunk> chunkConsumer,
            BooleanSupplier cancellationSupplier) throws Exception;

    record InferenceModel(String id, String name, String provider, String engine,
            long sizeBytes, boolean available) {
    }

    record InferenceChatMessage(String role, String content) {
        public static InferenceChatMessage user(String content) {
            return new InferenceChatMessage("user", content);
        }

        public static InferenceChatMessage system(String content) {
            return new InferenceChatMessage("system", content);
        }

        public static InferenceChatMessage assistant(String content) {
            return new InferenceChatMessage("assistant", content);
        }
    }

    record InferenceChatRequest(String model, List<InferenceChatMessage> messages,
            double temperature, int maxTokens) {
        public InferenceChatRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    record InferenceChatResponse(String content, String finishReason, String engine,
            String model, long durationMs) {
    }

    record InferenceChatChunk(String text, boolean done, String engine) {
        public static InferenceChatChunk done(String engine) {
            return new InferenceChatChunk("", true, engine);
        }
    }

    record InferenceRuntimeStatus(boolean ready, int availableProcessors,
            double systemLoadAverage, long maxHeapMb, long usedHeapMb, boolean gpuDetected,
            String gpuDevice, long gpuTotalVramMb, long gpuUsedVramMb, long gpuFreeVramMb,
            List<String> activeEngines) {
    }

    record InferenceConnectionTestResult(boolean success, String message) {
        public static InferenceConnectionTestResult ok(String message) {
            return new InferenceConnectionTestResult(true,
                    message == null ? "" : message);
        }

        public static InferenceConnectionTestResult error(String message) {
            return new InferenceConnectionTestResult(false,
                    message == null ? "Unavailable" : message);
        }
    }
}
