package eu.kalafatic.dstudio.application;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Interactive resolution for a backend-server port conflict. The user is told exactly
 * what happened, what the port is used for, and what they can do about it — then given
 * a port field (pre-filled with the first free candidate) to retry with. The chosen
 * port is persisted by the lifecycle coordinator, so the fix survives restarts.
 */
public class PortConflictDialog extends Dialog {

    private static final String OK_LABEL = "Use this port and retry";

    private final String bindAddress;
    private final int conflictPort;
    private final int suggestedPort;
    private final String extraMessage;

    private Text portText;
    private Label validationError;
    private int selectedPort;

    public PortConflictDialog(Shell parentShell, String bindAddress, int conflictPort,
            int suggestedPort, String extraMessage) {
        super(parentShell);
        this.bindAddress = bindAddress;
        this.conflictPort = conflictPort;
        this.suggestedPort = suggestedPort;
        this.extraMessage = extraMessage;
    }

    public int getSelectedPort() {
        return selectedPort;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Port conflict \u2014 EVO-Studio");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(1, false));

        Label title = new Label(area, SWT.WRAP);
        title.setFont(area.getFont());
        title.setText("The EVO-Studio backend server cannot start: port " + conflictPort
                + " on " + bindAddress + " is already in use.");
        title.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label explanation = new Label(area, SWT.WRAP);
        explanation.setText("This port carries the EVO-Studio server (TEIP 1.1, API and live "
                + "events) used by the Web IDE and by EVO/TRYST. Another EVO-Studio instance, "
                + "a family product (EVO, TRYST, EVO-Code) or an unrelated application is "
                + "already listening on it.\n\n"
                + "What you can do:\n"
                + "  1. Stop the conflicting process and click \"" + OK_LABEL + "\" to "
                + "retry on the same port, or\n"
                + "  2. Enter a different free port below (it is saved to "
                + "~/.dstudio/dstudio.properties), or\n"
                + "  3. Exit EVO-Studio and set teip.port=NNNN in "
                + "~/.dstudio/dstudio.properties yourself.");
        explanation.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        if (extraMessage != null && !extraMessage.isBlank()) {
            Label error = new Label(area, SWT.WRAP);
            error.setForeground(area.getDisplay().getSystemColor(SWT.COLOR_RED));
            error.setText(extraMessage);
            error.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        }

        Label portLabel = new Label(area, SWT.NONE);
        portLabel.setText("Port to use:");
        portText = new Text(area, SWT.BORDER);
        portText.setText(String.valueOf(suggestedPort > 0 ? suggestedPort : conflictPort + 1));
        GridData textData = new GridData(GridData.FILL_HORIZONTAL);
        textData.minimumWidth = 120;
        portText.setLayoutData(textData);
        validationError = new Label(area, SWT.WRAP);
        validationError.setForeground(area.getDisplay().getSystemColor(SWT.COLOR_RED));
        validationError.setText("");
        validationError.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label familyNote = new Label(area, SWT.WRAP);
        familyNote.setForeground(area.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        familyNote.setText("Family port convention: EVO 28xxx, TRYST 38xxx, "
                + "EVO-Studio 58xxx (TEIP 58080, diagnostics 58180), EVO-Code 48xxx "
                + "(TEIP 48080).");
        familyNote.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        return area;
    }

    @Override
    protected void okPressed() {
        String value = portText.getText().trim();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 65535) {
                throw new NumberFormatException("out of range");
            }
            selectedPort = parsed;
            super.okPressed();
        } catch (NumberFormatException e) {
            validationError.setText("Enter a valid port number between 1 and 65535.");
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, OK, OK_LABEL, true);
        createButton(parent, CANCEL, "Exit EVO-Studio", false);
    }
}
