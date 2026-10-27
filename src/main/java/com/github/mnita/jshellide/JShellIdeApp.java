package com.github.mnita.jshellide;

import com.github.mnita.jshellide.ui.MainWindow;
import java.nio.file.Path;
import org.eclipse.swt.widgets.Display;

/**
 * Application entry point for JShell IDE.
 *
 * <p>On macOS, launch with {@code -XstartOnFirstThread} (configured automatically
 * when running via {@code mvn exec:exec}).
 */
public class JShellIdeApp {

    public static void main(String[] args) {
        Path initialFile = (args != null && args.length > 0 && !args[0].isBlank())
                ? Path.of(args[0].trim())
                : null;
        Display.setAppName("JShell IDE");
        Display display = new Display();
        try {
            MainWindow window = new MainWindow(display, initialFile);
            window.openAndRunLoop();
        } finally {
            if (!display.isDisposed()) {
                display.dispose();
            }
        }
    }
}
