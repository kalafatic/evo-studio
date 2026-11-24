package eu.kalafatic.dstudio.application.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static command table of the headless CLI — the single source of truth for dispatch
 * names, syntax, short help and detailed topic help. {@link DStudioCli} dispatches
 * input and generates all help output from these entries only, so the documented
 * commands can never drift from the implemented ones.
 */
public final class DStudioCliCommandTable {

    /** One CLI command entry: name (dispatch key), syntax, short help and topic. */
    public record Command(String name, String syntax, String shortHelp, String topic,
            boolean terminatesRepl) {
    }

    public static final List<Command> COMMANDS = List.of(
            new Command("help", "help [topic]",
                    "Show the command list or detailed help for a topic.", "help", false),
            new Command("status", "status",
                    "Show lifecycle stage, server port and web interface URL.",
                    "status", false),
            new Command("web", "web", "Print the web interface URL.", "web", false),
            new Command("terminal", "terminal",
                    "Print the workspace directory and open a terminal when available.",
                    "terminal", false),
            new Command("models", "models list|show <id>",
                    "List inference engines and their models, or show one model.",
                    "models", false),
            new Command("sessions", "sessions list|show <id>|cancel <id>",
                    "List, inspect or cancel harness executions.", "sessions", false),
            new Command("chat", "chat \"prompt\" [engine <id>] [model <id>] | chat list | chat show <id>",
                    "Stream a chat through the shared inference service, or manage its sessions.",
                    "chat", false),
            new Command("providers", "providers list|test <id>",
                    "List harness providers or test a connection.", "providers", false),
            new Command("agents", "agents list", "List agent profiles.", "agents", false),
            new Command("config", "config show",
                    "Show non-secret configuration values.", "config", false),
            new Command("logs", "logs show",
                    "Show the most recent event-stream entries.", "logs", false),
            new Command("start", "start",
                    "Ensure the EVO-Studio server is running (starts automatically).",
                    "start", false),
            new Command("restart", "restart",
                    "Restart the TEIP/API server on its configured port.",
                    "restart", false),
            new Command("stop", "stop", "Stop EVO-Studio gracefully.", "stop", true),
            new Command("exit", "exit", "Exit the CLI (interactive REPL only).",
                    "exit", true));

    /** Detailed topic help; keyed by {@link Command#topic()}. */
    public static final Map<String, String> TOPIC_HELP = topicHelp();

    private static Map<String, String> topicHelp() {
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put("help", """
                help [topic]
                  Lists all CLI commands. With a topic (e.g. 'help sessions') prints the
                  detailed help for that topic. Topics are the command names above.
                """);
        topics.put("status", """
                status
                  Prints the lifecycle stage, the address and port the TEIP/API server is
                  actually bound to, and the web interface URL.
                """);
        topics.put("web", """
                web
                  Prints the web interface URL (http://127.0.0.1:<port>/). Open it in a
                  browser to use the Web IDE. The port is resolved from the running
                  server — it is never hardcoded.
                """);
        topics.put("terminal", """
                terminal
                  Prints the workspace directory (the first registered project root, or
                  the user home directory when no project is registered) and, when a
                  supported terminal emulator is installed (x-terminal-emulator,
                  gnome-terminal, konsole, xterm), opens a terminal there. The desktop
                  application offers the same action in Tools > Open Terminal and in the
                  Project Explorer context menu.
                """);
        topics.put("models", """
                models list
                  Lists every registered inference engine (ENR is the family default;
                  Ollama, OpenAI-compatible and Anthropic adapters are alternatives)
                  with enablement and connection status, plus the models each engine
                  publishes.
                models show <id>
                  Shows one model by id (searched across all engines).
                """);
        topics.put("sessions", """
                sessions list
                  Lists all harness executions (tasks) with status, harness, project and
                  creation time. Open executions are reconciled to FAILED on restart.
                sessions show <id>
                  Shows the details of one execution.
                sessions cancel <id>
                  Requests cancellation of one execution. Cancellation is idempotent:
                  terminal executions are unchanged.
                """);
        topics.put("chat", """
                chat "prompt" [engine <id>] [model <id>]
                  Runs a streaming chat through the shared inference service (the same
                  service and SQLite session store the web interface uses) and prints
                  the assistant answer token by token. The engine defaults to the
                  selected default engine, the model to the configured default model.
                chat list
                  Lists the persisted inference sessions of every client (web, RCP and
                  CLI share the same store).
                chat show <id>
                  Shows one inference session with its full message history.
                """);
        topics.put("providers", """
                providers list
                  Lists the registered coding-harness providers (harness adapters such
                  as dcode, opencode, aider and claude-code) with enablement, connection
                  status and the current default.
                providers test <id>
                  Runs the provider's live connection test and prints the result.
                """);
        topics.put("agents", """
                agents list
                  Lists all reusable agent profiles (id, name, role, preferred
                  harness/provider/model) and marks the default profile.
                """);
        topics.put("config", """
                config show
                  Prints the effective configuration: application, configuration
                  directory, TEIP bind address and port, network mode, diagnostics port,
                  dev mode, default harness/model and execution timeout. Secrets (auth
                  tokens) are never printed.
                """);
        topics.put("logs", """
                logs show
                  Prints the most recent entries of the buffered event stream (up to the
                  stream capacity). The same stream is served live over SSE at
                  /api/events.
                """);
        topics.put("start", """
                start
                  The EVO-Studio server starts automatically with the application (desktop
                  or headless). This command verifies the server; when it is not running
                  it is started on the configured port.
                """);
        topics.put("restart", """
                restart
                  Stops the running TEIP/API server and starts a new one on the same
                  configured port (the same restart path the desktop application uses
                  for port-conflict resolution). The port is resolved from the live
                  server — it is never hardcoded.
                """);
        topics.put("stop", """
                stop
                  Stops EVO-Studio gracefully: executions are shut down, the HTTP server is
                  stopped and the database is closed before the process exits with
                  code 0.
                """);
        topics.put("exit", """
                exit
                  Ends the CLI session and shuts EVO-Studio down gracefully (the same
                  shutdown path as 'stop'). In scripted mode the CLI also ends at
                  end-of-input.
                """);
        topics.put("cli", """
                The EVO-Studio headless CLI is available when the server runs with a
                terminal attached or with commands piped to stdin. Type 'help' for the
                full command list or 'help <topic>' for detailed help on one command
                family. A leading '/' is optional and commands are case-insensitive;
                'stop'/'exit' (or end-of-input) shut EVO-Studio down gracefully. Force
                daemon mode (no CLI) with -Ddstudio.daemon=true or --daemon.
                """);
        return topics;
    }

    private DStudioCliCommandTable() {
    }
}
