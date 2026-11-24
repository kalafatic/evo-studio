package eu.kalafatic.dstudio.teip.server;

/**
 * The backend HTTP server could not bind its address because the port is already
 * occupied by another process. The desktop client turns this into an interactive
 * dialog (choose another port / exit); the headless client prints actionable
 * instructions and exits with a non-zero code. Existing configuration is never
 * silently changed.
 */
public class PortConflictException extends IllegalStateException {

    private final String bindAddress;
    private final int port;

    public PortConflictException(String bindAddress, int port, Throwable cause) {
        super("Port " + port + " on " + bindAddress + " is already in use by another "
                + "process (another EVO-Studio instance, a family product such as EVO-Code, "
                + "or an unrelated application)", cause);
        this.bindAddress = bindAddress;
        this.port = port;
    }

    public String bindAddress() {
        return bindAddress;
    }

    public int port() {
        return port;
    }
}
