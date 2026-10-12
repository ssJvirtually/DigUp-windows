package com.digup.platform;

import java.awt.Desktop;
import java.io.File;

/**
 * Windows shell utilities for explorer selection and default application opening.
 */
public final class ShellHelper {

    private ShellHelper() {}

    public static void revealInExplorer(File file) {
        if (!file.exists()) return;
        try {
            new ProcessBuilder("explorer.exe", "/select,\"" + file.getAbsolutePath() + "\"").start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void openFile(File file) {
        if (!file.exists()) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
            } else {
                new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath()).start();
            }
        } catch (Exception e) {
            try {
                new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath()).start();
            } catch (Exception ignored) {}
        }
    }
}
