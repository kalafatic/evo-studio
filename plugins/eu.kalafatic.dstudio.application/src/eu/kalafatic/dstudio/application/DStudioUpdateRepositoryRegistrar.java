package eu.kalafatic.dstudio.application;

import java.io.File;
import java.net.URI;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Registers the product's local update repository ({@code <install>/updates}) in the
 * provisioning profile when that directory exists, so the standard Eclipse "Check for
 * Updates" discovers published updates. Registration is additive and idempotent: it
 * never removes or overrides user-configured repositories.
 */
public final class DStudioUpdateRepositoryRegistrar {

    private static final String REPOSITORY_NAME = "EVO-Studio Local Updates";

    private DStudioUpdateRepositoryRegistrar() {
    }

    /**
     * Registers the local update repository in the background: the provisioning
     * agent and its repository managers may become available after application
     * start, so a bounded number of retries is performed. Registration is
     * idempotent and never throws.
     */
    public static void registerAsync() {
        Thread thread = new Thread(() -> {
            for (int attempt = 0; attempt < 15; attempt++) {
                try {
                    if (register()) {
                        return;
                    }
                } catch (Throwable t) {
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "dstudio-update-repository-registrar");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Starts the bundle exporting the given API if it is only resolved; the p2
     * repository managers register their agent service factories at activation.
     */
    private static void ensureBundleStarted(Class<?> api) {
        try {
            Bundle apiBundle = FrameworkUtil.getBundle(api);
            if (apiBundle != null
                    && (apiBundle.getState() == Bundle.RESOLVED
                        || apiBundle.getState() == Bundle.INSTALLED)) {
                apiBundle.start(Bundle.START_TRANSIENT);
            }
        } catch (Throwable t) {
            // Best effort; registration is retried.
        }
    }

    /**
     * Attempts registration against the running provisioning agent. Safe to call at
     * any time; returns false when the p2 services are not available yet (callers
     * may retry later).
     */
    public static boolean register() {
        try {
            // The application bundle may run resolved without being started;
            // org.eclipse.core.runtime is always active in these products.
            Bundle bundle = FrameworkUtil.getBundle(Platform.class);
            if (bundle == null) {
                return false;
            }
            BundleContext context = bundle.getBundleContext();
            if (context == null) {
                return false;
            }
            ServiceReference<IProvisioningAgent> reference =
                    context.getServiceReference(IProvisioningAgent.class);
            if (reference == null) {
                return false;
            }
            IProvisioningAgent agent = context.getService(reference);
            try {
                ensureBundleStarted(IMetadataRepositoryManager.class);
                ensureBundleStarted(IArtifactRepositoryManager.class);
                IMetadataRepositoryManager metaManager =
                        agent.getService(IMetadataRepositoryManager.class);
                IArtifactRepositoryManager artifactManager =
                        agent.getService(IArtifactRepositoryManager.class);
                if (metaManager == null || artifactManager == null) {
                    return false;
                }
                File updatesDir = new File(
                        Platform.getInstallLocation().getURL().getPath(), "updates");
                if (!updatesDir.isDirectory()) {
                    return true;
                }
                URI repoUri = updatesDir.toURI();
                if (!metaManager.contains(repoUri)) {
                    metaManager.addRepository(repoUri);
                    metaManager.setRepositoryProperty(repoUri,
                            IRepository.PROP_NICKNAME, REPOSITORY_NAME);
                }
                if (!artifactManager.contains(repoUri)) {
                    artifactManager.addRepository(repoUri);
                    artifactManager.setRepositoryProperty(repoUri,
                            IRepository.PROP_NICKNAME, REPOSITORY_NAME);
                }
                return true;
            } finally {
                context.ungetService(reference);
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
