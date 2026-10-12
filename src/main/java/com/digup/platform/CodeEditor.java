package com.digup.platform;

import java.io.File;
import java.util.List;

/**
 * Launches preferred code editor at specified line number.
 */
public final class CodeEditor {

    private CodeEditor() {}

    public static void openAtLine(String editor, File file, int line) {
        String path = file.getAbsolutePath();
        try {
            switch (editor.toLowerCase()) {
                case "cursor":
                    new ProcessBuilder("cursor", "--goto", path + ":" + line).start();
                    break;
                case "idea":
                case "intellij":
                    new ProcessBuilder("idea64.exe", "--line", String.valueOf(line), path).start();
                    break;
                case "pycharm":
                    new ProcessBuilder("pycharm64.exe", "--line", String.valueOf(line), path).start();
                    break;
                case "sublime":
                    new ProcessBuilder("subl", path + ":" + line).start();
                    break;
                case "notepad++":
                    new ProcessBuilder("notepad++", "-n" + line, path).start();
                    break;
                case "vscode":
                default:
                    new ProcessBuilder("code", "--goto", path + ":" + line).start();
                    break;
            }
        } catch (Exception e) {
            // Fallback to default system open
            ShellHelper.openFile(file);
        }
    }
}
