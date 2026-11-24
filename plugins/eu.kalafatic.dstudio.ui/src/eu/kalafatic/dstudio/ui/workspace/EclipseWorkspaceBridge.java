package eu.kalafatic.dstudio.ui.workspace;

import eu.kalafatic.dstudio.core.model.Project;
import eu.kalafatic.dstudio.core.service.ProjectService;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;

public class EclipseWorkspaceBridge {

    public void synchronize(ProjectService projectService) {
        if (projectService == null) {
            return;
        }
        for (Project project : projectService.listProjects()) {
            try {
                ensureProject(project);
            } catch (Exception e) {
                System.err.println("EVO-Studio: failed to link Eclipse project "
                        + project.name() + ": " + e.getMessage());
            }
        }
    }

    public IProject ensureProject(Project project) throws Exception {
        String eclipseName = eclipseProjectName(project);
        IProject eclipseProject = ResourcesPlugin.getWorkspace().getRoot()
                .getProject(eclipseName);
        if (!eclipseProject.exists()) {
            Path location = Path.of(project.rootPath());
            boolean insideWorkspace = location.startsWith(
                    ResourcesPlugin.getWorkspace().getRoot().getLocation().toPath()
                            .toAbsolutePath().normalize());
            if (insideWorkspace) {
                eclipseProject.create(null);
            } else {
                IProjectDescription description = ResourcesPlugin.getWorkspace()
                        .newProjectDescription(eclipseName);
                description.setLocation(org.eclipse.core.runtime.IPath.fromFile(
                        location.toFile()));
                eclipseProject.create(description, null);
            }
        }
        if (!eclipseProject.isOpen()) {
            eclipseProject.open(null);
        }
        return eclipseProject;
    }

    public IProject findProject(Project project) {
        return ResourcesPlugin.getWorkspace().getRoot()
                .getProject(eclipseProjectName(project));
    }

    public void removeProject(Project project) {
        IProject eclipseProject = findProject(project);
        if (eclipseProject != null && eclipseProject.exists()) {
            try {
                eclipseProject.delete(false, true, null);
            } catch (Exception e) {
                System.err.println("EVO-Studio: failed to unlink Eclipse project "
                        + project.name() + ": " + e.getMessage());
            }
        }
    }

    private String eclipseProjectName(Project project) {
        String name = project.name().replaceAll("[^a-zA-Z0-9_.-]", "_");
        return name.isBlank() ? "_project_" + project.id() : name;
    }
}
