package eu.kalafatic.dstudio.ui.handlers;

import eu.kalafatic.dstudio.ui.editor.DStudioMultiPageEditor;
import eu.kalafatic.dstudio.ui.editor.DStudioMultiPageEditorInput;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Opens (or activates) the single shared EVO-Studio harness editor — the main working
 * area. The editor input is stable, so repeated executions (startup auto-open,
 * Perspective Reset recovery, Project Explorer double-click, Window menu) converge
 * on the same part; no duplicates.
 */
public class OpenInferenceEditorHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            System.out.println("EVO-Studio: cannot open the harness editor — no "
                    + "workbench window.");
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            System.out.println("EVO-Studio: cannot open the harness editor — no active "
                    + "page.");
            return null;
        }
        try {
            open(page);
        } catch (PartInitException e) {
            throw new ExecutionException("Cannot open the EVO-Studio harness editor", e);
        }
        return null;
    }

    /**
     * Opens or activates the shared harness editor on the given page. Safe to call
     * repeatedly: the stable editor input makes this idempotent.
     */
    public static IEditorPart open(IWorkbenchPage page) throws PartInitException {
        var editor = page.openEditor(new DStudioMultiPageEditorInput(),
                DStudioMultiPageEditor.EDITOR_ID);
        page.activate(editor);
        System.out.println("EVO-Studio: inference editor ready: " + editor.getTitle());
        return editor;
    }
}
