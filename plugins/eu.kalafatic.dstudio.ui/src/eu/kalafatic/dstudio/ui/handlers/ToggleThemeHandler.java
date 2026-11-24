package eu.kalafatic.dstudio.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.e4.ui.css.swt.theme.ITheme;
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine;
import org.eclipse.ui.PlatformUI;

/**
 * Toggles the workbench theme between Light and Dark, reusing the existing Eclipse 4
 * theme engine. The choice is persisted (restored on the next launch) so an explicitly
 * selected Dark theme is never overridden by the Light default.
 */
public class ToggleThemeHandler extends AbstractHandler {

    public static final String LIGHT_THEME_ID = "org.eclipse.e4.ui.css.theme.e4_default";
    public static final String DARK_THEME_ID = "org.eclipse.e4.ui.css.theme.e4_dark";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IThemeEngine engine = PlatformUI.getWorkbench().getService(IThemeEngine.class);
        if (engine == null) {
            return null;
        }
        ITheme active = engine.getActiveTheme();
        String current = active == null ? "" : active.getId();
        String target = DARK_THEME_ID.equals(current) ? LIGHT_THEME_ID : DARK_THEME_ID;
        engine.setTheme(target, true);
        return null;
    }
}
