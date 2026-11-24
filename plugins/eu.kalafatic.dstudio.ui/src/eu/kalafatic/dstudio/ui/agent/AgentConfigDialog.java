package eu.kalafatic.dstudio.ui.agent;

import eu.kalafatic.dstudio.core.agent.AgentProfile;
import eu.kalafatic.dstudio.ui.FormHistory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Create/edit dialog for agent profiles. Fields: name, description, role, system
 * instructions, tool permissions (comma separated), restrictions (comma separated),
 * preferred harness, preferred provider, preferred model and execution parameters
 * (key=value, comma separated).
 */
public class AgentConfigDialog extends Dialog {

    private final AgentProfile existing;
    private Text nameText;
    private Text descriptionText;
    private Text roleText;
    private Text instructionsText;
    private Text permissionsText;
    private Text restrictionsText;
    private Text harnessText;
    private Text providerText;
    private Text modelText;
    private Text parametersText;
    private AgentProfile result;

    public AgentConfigDialog(Shell parent, AgentProfile existing) {
        super(parent);
        this.existing = existing;
    }

    public AgentProfile getProfile() {
        return result;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        getShell().setText(existing == null ? "Create Agent Profile" : "Edit Agent Profile");
        GridLayoutFactory.swtDefaults().numColumns(2).applyTo(container);

        Label hint = new Label(container, SWT.WRAP);
        hint.setText("Fields marked * are required. Empty preferences act as wildcards "
                + "(any harness, provider or model).");
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(hint);

        nameText = field(container, "Name *",
                existing == null ? defaultAgentName() : existing.getName(),
                "Short, human-readable name for this profile. Required. A suggested "
                        + "name is already provided.");
        descriptionText = field(container, "Description",
                existing == null ? "" : existing.getDescription(),
                "Optional description of what this profile is for.");
        roleText = field(container, "Role", existing == null ? "" : existing.getRole(),
                "Optional role label, e.g. 'architect' or 'reviewer'.");
        instructionsText = field(container, "System Instructions",
                existing == null ? "" : existing.getSystemInstructions(),
                "System prompt / persona instructions sent to the model. Optional.", true);
        permissionsText = field(container, "Tool Permissions (comma separated)",
                existing == null ? "" : String.join(",", existing.getToolPermissions()),
                "Tools this agent may use. Empty inherits the harness defaults.");
        restrictionsText = field(container, "Restrictions (comma separated)",
                existing == null ? "" : String.join(",", existing.getRestrictions()),
                "Tools or actions this agent may not use. Optional.");
        harnessText = field(container, "Preferred Harness (id, empty = any)",
                existing == null ? orEmpty(FormHistory.get("agent.harness"))
                        : existing.getPreferredHarnessId(),
                "Preferred coding harness id. Empty = any harness. Pre-filled with the "
                        + "last-used value.");
        providerText = field(container, "Preferred Provider (engine id, empty = any)",
                existing == null ? orEmpty(FormHistory.get("agent.provider"))
                        : existing.getPreferredProviderId(),
                "Preferred inference engine id. Empty = any provider. Pre-filled with "
                        + "the last-used value.");
        modelText = field(container, "Preferred Model (empty = any)",
                existing == null ? orEmpty(FormHistory.get("agent.model"))
                        : existing.getPreferredModel(),
                "Preferred model id. Empty = any model. Pre-filled with the last-used "
                        + "value.");
        parametersText = field(container,
                "Execution Parameters (key=value, comma separated)",
                existing == null ? "" : joinParameters(existing.getExecutionParameters()),
                "Optional key=value execution parameters.");
        return container;
    }

    private Text field(Composite parent, String label, String initial, String tooltip) {
        return field(parent, label, initial, tooltip, false);
    }

    private Text field(Composite parent, String label, String initial, String tooltip,
            boolean multiline) {
        Label labelControl = new Label(parent, SWT.NONE);
        labelControl.setText(label);
        Text text = new Text(parent, multiline ? (SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL)
                : SWT.BORDER);
        text.setText(initial == null ? "" : initial);
        if (tooltip != null && !tooltip.isBlank()) {
            text.setToolTipText(tooltip);
        }
        GridDataFactory.fillDefaults().grab(true, false).hint(380,
                multiline ? 90 : SWT.DEFAULT).applyTo(text);
        return text;
    }

    private static String joinParameters(Map<String, String> parameters) {
        List<String> parts = new ArrayList<>();
        parameters.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(",", parts);
    }

    @Override
    protected void okPressed() {
        String name = nameText.getText().trim();
        if (name.isBlank()) {
            org.eclipse.jface.dialogs.MessageDialog.openWarning(getShell(),
                    "Agent Profile", "A profile name is required.");
            return;
        }
        String id = existing == null
                ? "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : existing.getId();
        Instant now = Instant.now();
        result = new AgentProfile(id, name, descriptionText.getText().trim(),
                roleText.getText().trim(), instructionsText.getText().trim(),
                splitList(permissionsText.getText()), splitList(restrictionsText.getText()),
                harnessText.getText().trim(), providerText.getText().trim(),
                modelText.getText().trim(), parseParameters(parametersText.getText()),
                existing == null ? now : existing.getCreatedAt(), now);
        // Remember the successfully saved preferences as the next create-form defaults.
        FormHistory.put("agent.harness", harnessText.getText().trim());
        FormHistory.put("agent.provider", providerText.getText().trim());
        FormHistory.put("agent.model", modelText.getText().trim());
        super.okPressed();
    }

    private static String defaultAgentName() {
        return "Agent " + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> splitList(String text) {
        List<String> result = new ArrayList<>();
        for (String part : text.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    private static Map<String, String> parseParameters(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : text.split(",")) {
            String token = part.trim();
            int equals = token.indexOf('=');
            if (equals > 0) {
                result.put(token.substring(0, equals).trim(),
                        token.substring(equals + 1).trim());
            }
        }
        return result;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }
}
