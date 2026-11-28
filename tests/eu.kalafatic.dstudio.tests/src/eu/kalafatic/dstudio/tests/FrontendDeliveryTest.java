package eu.kalafatic.dstudio.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import eu.kalafatic.dstudio.teip.server.DStudioServer;
import eu.kalafatic.dstudio.teip.server.WebAssetsHandler;

/**
 * Frontend delivery invariant: there is exactly ONE authoritative frontend source
 * (the {@code web/} tree of the teip bundle), and every execution mode — PDE
 * workspace launch, packaged RCP product, headless and standalone web — resolves
 * the same resources from the bundle classpath. This test fails when the packaged
 * bundle is stale relative to the source tree or when the frontend build constant
 * drifts from the backend {@code DStudioServer.FRONTEND_VERSION}.
 */
class FrontendDeliveryTest {

    private static final List<String> FILES = List.of(
            "web/index.html", "web/common.js", "web/app.js", "web/style.css",
            "web/settings.html", "web/settings.js", "web/tasks.js", "web/help.html",
            "web/shared/shell.css", "web/shared/shell.js", "web/shared/shell-init.js");

    private static final String BUILD = "web-20261127-r6";

    /** Locates the source tree for content comparison (repo checkout layout). */
    private Path sourceRoot() {
        Path module = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                module.resolve("../../plugins/eu.kalafatic.dstudio.teip"),
                module.resolve("../../../plugins/eu.kalafatic.dstudio.teip"),
                module.resolve("../../../../plugins/eu.kalafatic.dstudio.teip"))) {
            if (Files.isDirectory(candidate.resolve("web"))) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    void bundledWebAssetsMatchTheSourceTree() throws Exception {
        var bundle = FrameworkUtil.getBundle(WebAssetsHandler.class);
        assertNotNull(bundle, "teip bundle must be resolvable in the test runtime");
        Path source = sourceRoot();
        for (String file : FILES) {
            InputStream stream = null;
            try {
                stream = bundle.getEntry(file).openStream();
                assertNotNull(stream, "bundle must contain " + file);
                String bundled = new String(stream.readAllBytes(),
                        StandardCharsets.UTF_8);
                if (source != null) {
                    String fromSource = Files.readString(source.resolve(file));
                    assertEquals(fromSource, bundled,
                            "stale frontend packaging detected for " + file
                                    + " — the teip bundle must be rebuilt");
                }
            } finally {
                if (stream != null) {
                    stream.close();
                }
            }
        }
    }

    @Test
    void frontendBuildConstantIsConsistentEverywhere() throws Exception {
        assertEquals(BUILD, DStudioServer.FRONTEND_VERSION,
                "backend FRONTEND_VERSION must match the frontend build");
        var bundle = FrameworkUtil.getBundle(WebAssetsHandler.class);
        assertNotNull(bundle);
        String common;
        try (InputStream in = bundle.getEntry("web/common.js").openStream()) {
            common = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(common.contains("FRONTEND_BUILD = \"" + BUILD + "\""),
                "common.js must declare FRONTEND_BUILD = " + BUILD);
        String index;
        try (InputStream in = bundle.getEntry("web/index.html").openStream()) {
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(index.contains("frontend-build"),
                "index.html must render the frontend build badge");
    }

    /**
     * Proves the PDE-layout guarantee: a directory-installed bundle (how Eclipse
     * installs workspace bundles in Run/Debug) exposes the SAME logical
     * {@code web/…} paths through the same OSGi entry API the server uses in
     * packaged products — no flattened structure, no output-folder dependency.
     */
    @Test
    void directoryBundleExposesTheSameLogicalWebPaths() throws Exception {
        org.osgi.framework.BundleContext context = FrameworkUtil.getBundle(
                FrontendDeliveryTest.class).getBundleContext();
        assertNotNull(context, "test runtime must provide a bundle context");
        Path dir = Files.createTempDirectory("web-bundle-");
        try {
            Files.createDirectories(dir.resolve("META-INF"));
            Files.createDirectories(dir.resolve("web/css"));
            Files.writeString(dir.resolve("META-INF/MANIFEST.MF"), """
                    Manifest-Version: 1.0
                    Bundle-ManifestVersion: 2
                    Bundle-SymbolicName: frontend.delivery.probe
                    Bundle-Version: 1.0.0

                    """);
            Files.writeString(dir.resolve("web/index.html"), "<html>probe</html>");
            Files.writeString(dir.resolve("web/css/style.css"), "body{}");
            org.osgi.framework.Bundle probe = context.installBundle(
                    dir.toUri().toString());
            try {
                probe.start();
                assertEquals("<html>probe</html>",
                        new String(probe.getEntry("web/index.html").openStream()
                                .readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("body{}",
                        new String(probe.getEntry("web/css/style.css").openStream()
                                .readAllBytes(), StandardCharsets.UTF_8));
                assertTrue(probe.getEntry("index.html") == null,
                        "the web/ prefix must be preserved in the logical path");
            } finally {
                probe.uninstall();
            }
        } finally {
            Files.deleteIfExists(dir.resolve("web/css/style.css"));
            Files.deleteIfExists(dir.resolve("web/index.html"));
            Files.deleteIfExists(dir.resolve("META-INF/MANIFEST.MF"));
            Files.deleteIfExists(dir.resolve("web/css"));
            Files.deleteIfExists(dir.resolve("web"));
            Files.deleteIfExists(dir.resolve("META-INF"));
            Files.deleteIfExists(dir);
        }
    }
}
