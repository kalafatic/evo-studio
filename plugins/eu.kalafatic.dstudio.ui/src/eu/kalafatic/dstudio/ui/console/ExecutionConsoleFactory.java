package eu.kalafatic.dstudio.ui.console;

import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleFactory;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;

public class ExecutionConsoleFactory implements IConsoleFactory {

    public static final String CONSOLE_NAME = "EVO-Studio Execution Console";

    @Override
    public void openConsole() {
        showConsole();
    }

    public static MessageConsole showConsole() {
        IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
        for (IConsole console : manager.getConsoles()) {
            if (CONSOLE_NAME.equals(console.getName())
                    && console instanceof MessageConsole messageConsole) {
                manager.showConsoleView(messageConsole);
                return messageConsole;
            }
        }
        MessageConsole console = new MessageConsole(CONSOLE_NAME, null);
        manager.addConsoles(new IConsole[] {console});
        manager.showConsoleView(console);
        return console;
    }
}
