package eu.kalafatic.dstudio.ui.contextual;

import java.util.LinkedHashMap;
import java.util.Map;

import eu.kalafatic.dstudio.ui.FormHistory;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Registers a new runtime provider instance through the shared inference-engine factory
 * (OLLAMA, ENR, OPENAI_COMPATIBLE, ANTHROPIC), mirroring the web
 * {@code POST /api/inference/engines} operation.
 */
public class AddProviderDialog extends Dialog {

    private static final String[] TYPES = {
            "OLLAMA", "ENR", "OPENAI_COMPATIBLE", "ANTHROPIC"
    };

    /** Recommended default endpoints per provider type. */
    private static final Map<String, String> DEFAULT_ENDPOINTS = Map.of(
            "OLLAMA", "http://127.0.0.1:11434",
            "ENR", "http://127.0.0.1:28780",
            "OPENAI_COMPATIBLE", "http://127.0.0.1:11434/v1",
            "ANTHROPIC", "https://api.anthropic.com");

    private Combo typeCombo;
    private Text idText;
    private Text nameText;
    private Text endpointText;
    private String type = "OLLAMA";
    private String id = "";
    private String name = "";
    private String endpoint = "";

    public AddProviderDialog(Shell parentShell) {
        super(parentShell);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Add Provider");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        container.setLayout(new GridLayout(2, false));

        new Label(container, SWT.NONE).setText("Type:");
        typeCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        typeCombo.setItems(TYPES);
        int rememberedTypeIndex = indexOf(TYPES, FormHistory.get("provider.type"));
        typeCombo.select(rememberedTypeIndex >= 0 ? rememberedTypeIndex : 0);
        typeCombo.setToolTipText("The provider type (runtime) being added. The last-used "
                + "type is pre-selected.");

        Label idLabel = new Label(container, SWT.NONE);
        idLabel.setText("Id *");
        idText = new Text(container, SWT.BORDER);
        idText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        idText.setToolTipText("Short unique identifier for this provider. Required.");

        Label nameLabel = new Label(container, SWT.NONE);
        nameLabel.setText("Name");
        nameText = new Text(container, SWT.BORDER);
        nameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        nameText.setToolTipText("Display name. Optional — defaults to the Id. The "
                + "last-used name is pre-filled.");
        nameText.setText(orEmpty(FormHistory.get("provider.name")));

        Label endpointLabel = new Label(container, SWT.NONE);
        endpointLabel.setText("Endpoint");
        endpointText = new Text(container, SWT.BORDER);
        endpointText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        endpointText.setToolTipText("Address of the runtime service. Pre-filled with the "
                + "last-used value, or the recommended default for the selected type.");
        String rememberedEndpoint = FormHistory.get("provider.endpoint");
        endpointText.setText(rememberedEndpoint != null ? rememberedEndpoint
                : DEFAULT_ENDPOINTS.getOrDefault(typeCombo.getText(), ""));

        // Re-apply the recommended endpoint when the type changes, unless the user has
        // entered a custom value.
        typeCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                String current = endpointText.getText().trim();
                String newDefault = DEFAULT_ENDPOINTS.getOrDefault(typeCombo.getText(), "");
                if (current.isBlank() || DEFAULT_ENDPOINTS.containsValue(current)) {
                    endpointText.setText(newDefault);
                }
            }
        });
        return container;
    }

    @Override
    protected void okPressed() {
        this.type = typeCombo.getText().trim().toUpperCase();
        this.id = idText.getText().trim();
        if (this.id.isBlank()) {
            MessageDialog.openWarning(getShell(), "Add Provider",
                    "An Id is required to register the provider.");
            return;
        }
        this.name = nameText.getText().trim();
        if (name.isBlank()) {
            name = id;
        }
        this.endpoint = endpointText.getText().trim();
        // Remember only after a successful registration (validation already passed).
        FormHistory.put("provider.type", this.type);
        FormHistory.put("provider.name", this.name);
        FormHistory.put("provider.endpoint", this.endpoint);
        super.okPressed();
    }

    private static int indexOf(String[] items, String value) {
        if (value == null) {
            return -1;
        }
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    public String getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getConfiguration() {
        Map<String, String> configuration = new LinkedHashMap<>();
        if (!endpoint.isBlank()) {
            configuration.put("endpoint", endpoint);
        }
        return configuration;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL,
                false);
    }
}
