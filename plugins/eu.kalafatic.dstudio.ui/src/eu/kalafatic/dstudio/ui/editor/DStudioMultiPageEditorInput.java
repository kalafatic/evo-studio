package eu.kalafatic.dstudio.ui.editor;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

/**
 * Editor input of the EVO-Studio harness editor (one per workbench session).
 *
 * <p>Deliberately project-independent: the embedded web IDE owns project and session
 * selection through the shared backend, so the input carries no mutable state.
 * Equality is by type — {@code IWorkbenchPage.openEditor()} therefore always reuses
 * the single editor instance (startup auto-open, restoration and the Project Explorer
 * double-click all converge on the same part; no duplicates). The input is
 * non-persistable; the workbench restores the editor part itself.</p>
 */
public final class DStudioMultiPageEditorInput implements IEditorInput {

    /** Stable display identity of the main editor input. */
    public static final String NAME = "EVO-Studio Inference";

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public ImageDescriptor getImageDescriptor() {
        return null;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public IPersistableElement getPersistable() {
        return null;
    }

    @Override
    public String getToolTipText() {
        return "EVO-Studio harness workspace: web IDE + settings";
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DStudioMultiPageEditorInput;
    }

    @Override
    public int hashCode() {
        return DStudioMultiPageEditorInput.class.hashCode();
    }
}
