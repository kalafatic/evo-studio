package eu.kalafatic.dstudio.inference.ui;

import java.util.List;

/**
 * Product-side implementation of the inference workspace. The presentation bundle is
 * backend-agnostic: EVO-Code implements this over its session/agent execution API,
 * EVO-Studio implements it over its inference engines and harness adapters.
 *
 * <p>All methods are invoked on the SWT display thread; implementations must return
 * quickly and perform backend work asynchronously.</p>
 */
public interface ChatWorkspaceBackend {

    /** Called once after the composite is created. */
    void init(ChatWorkspaceUi ui);

    /** Reload selector options, status and sessions from the registries. */
    void refreshConfiguration();

    /** A selector changed; the option id is non-null when a selection exists. */
    void selectorChanged(String selectorId, String optionId);

    /** Sends the prompt (plus optional local file attachments) for execution. */
    void sendMessage(String text, List<String> attachmentPaths);

    /** Stops the current generation/execution. Idempotent. */
    void stopGeneration();

    /**
     * Runs the prompt as a coding task through the selected execution backend
     * (harness). Products without a separate task path may leave the default
     * implementation, which routes the prompt to {@link #sendMessage}.
     */
    default void runTask(String text, List<String> attachmentPaths) {
        sendMessage(text, attachmentPaths);
    }

    /** Creates a new conversation session with the current selections. */
    void createSession();

    void openSession(String sessionId);

    void renameSession(String sessionId, String newTitle);

    void deleteSession(String sessionId);

    void refreshSessions();

    /** Executes a search with the given pattern in the active context. */
    void runSearch(String pattern);

    /** Called when the workspace is disposed. */
    void dispose();
}
