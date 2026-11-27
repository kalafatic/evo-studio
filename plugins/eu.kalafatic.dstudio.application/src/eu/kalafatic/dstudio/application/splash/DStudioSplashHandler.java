package eu.kalafatic.dstudio.application.splash;

import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.branding.IProductConstants;
import org.eclipse.ui.splash.BasicSplashHandler;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * EVO-Studio splash handler reproducing the EVO startup presentation geometry:
 * the progress bar and message rect are read from the product
 * ({@code startupProgressRect}/{@code startupMessageRect}/{@code startupForegroundColor}),
 * and the application version is painted in the upper-right corner (right inset 80px,
 * top inset 10px, light-blue foreground 112,176,255, default font).
 */
public class DStudioSplashHandler extends BasicSplashHandler {

    private Color versionColor;

    @Override
    public void init(Shell splash) {
        super.init(splash);
        configureRectsAndForeground(splash);
        drawVersion(splash);
        getContent();
    }

    private void configureRectsAndForeground(Shell splash) {
        Rectangle progressRect = new Rectangle(212, 348, 350, 2);
        Rectangle messageRect = new Rectangle(212, 335, 350, 5);
        String foregroundColorString = "FF0000";
        IProduct product = Platform.getProduct();
        if (product != null) {
            String pr = product.getProperty(IProductConstants.STARTUP_PROGRESS_RECT);
            if (pr != null) {
                progressRect = StringConverter.asRectangle(pr, progressRect);
            }
            String mr = product.getProperty(IProductConstants.STARTUP_MESSAGE_RECT);
            if (mr != null) {
                messageRect = StringConverter.asRectangle(mr, messageRect);
            }
            String fg = product.getProperty(IProductConstants.STARTUP_FOREGROUND_COLOR);
            if (fg != null) {
                foregroundColorString = fg;
            }
        }
        setProgressRect(progressRect);
        setMessageRect(messageRect);

        int fgInt;
        try {
            fgInt = Integer.parseInt(foregroundColorString, 16);
        } catch (Exception ex) {
            fgInt = 0xFF0000;
        }
        setForeground(new RGB((fgInt >> 16) & 0xFF, (fgInt >> 8) & 0xFF, fgInt & 0xFF));
    }

    private void drawVersion(Shell splash) {
        try {
            final String version = resolveApplicationVersion();
            final int x = splash.getBounds().width - 80;
            final int y = 10;
            versionColor = new Color(splash.getDisplay(), new RGB(112, 176, 255));
            final Color fg = versionColor;

            Composite content = getContent();
            if (content != null && !content.isDisposed()) {
                content.addPaintListener(new PaintListener() {
                    @Override
                    public void paintControl(PaintEvent e) {
                        if (fg == null || fg.isDisposed()) {
                            return;
                        }
                        e.gc.setForeground(fg);
                        e.gc.drawText("v " + version, x, y, true);
                    }
                });
                content.redraw();
            }
        } catch (Throwable t) {
            // Never break startup for a splash decoration failure.
        }
    }

    static String resolveApplicationVersion() {
        String version = null;
        try {
            IProduct product = Platform.getProduct();
            if (product != null) {
                version = product.getProperty("version");
                if (version == null || version.isBlank()) {
                    Bundle defining = product.getDefiningBundle();
                    if (defining != null) {
                        version = bundleVersion(defining);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Platform lookups can fail in non-OSGi environments.
        }
        if (version == null || version.isBlank()) {
            try {
                version = bundleVersion(FrameworkUtil.getBundle(DStudioSplashHandler.class));
            } catch (Throwable ignored) {
                // Framework lookups can fail outside OSGi.
            }
        }
        if (version == null || version.isBlank()) {
            version = "2.6.11";
        }
        if (version.endsWith(".qualifier")) {
            version = version.substring(0, version.length() - ".qualifier".length());
        }
        return version;
    }

    private static String bundleVersion(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        String header = bundle.getHeaders() != null ? bundle.getHeaders().get("Bundle-Version") : null;
        if (header != null && !header.isBlank()) {
            return header;
        }
        return bundle.getVersion() != null ? bundle.getVersion().toString() : null;
    }

    @Override
    public void dispose() {
        if (versionColor != null && !versionColor.isDisposed()) {
            versionColor.dispose();
            versionColor = null;
        }
        super.dispose();
    }
}
