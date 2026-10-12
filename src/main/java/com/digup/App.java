package com.digup;

import com.digup.engine.Engine;
import com.digup.model.AppSettings;
import com.digup.platform.WindowsHotkey;
import com.digup.ui.*;

import javax.swing.*;
import java.awt.event.KeyEvent;

/**
 * Main application bootstrap for DigUp on Windows.
 */
public final class App {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                // 1. Windows Native Look and Feel
                Theme.setup();

                // 2. Load settings
                AppSettings settings = AppSettings.load();

                // 3. Initialize engine
                Engine engine = new Engine(settings);
                engine.start();

                // 4. Create UI windows
                SearchPanel searchPanel = new SearchPanel(engine);
                SearchWindow searchWindow = new SearchWindow(engine);

                // 5. System Tray
                SystemTrayManager trayManager = new SystemTrayManager(engine, settings, searchPanel, searchWindow);
                trayManager.setup();

                // 6. Register Global Hotkey (Default Alt+Space)
                // 0x0001 = MOD_ALT, 0x20 = VK_SPACE
                WindowsHotkey hotkey = new WindowsHotkey(() -> {
                    SwingUtilities.invokeLater(() -> {
                        if (searchPanel.isVisible()) {
                            searchPanel.hidePanel();
                        } else {
                            searchPanel.showPanel();
                        }
                    });
                });
                hotkey.register(0x0001, 0x20);

                // 7. Onboarding on first launch
                if (!settings.onboarded) {
                    OnboardingDialog onboarding = new OnboardingDialog(settings, () -> {
                        engine.getMainStore().setScope(settings.roots);
                        engine.triggerCrawl();
                        searchPanel.showPanel();
                    });
                    onboarding.setVisible(true);
                } else {
                    // Check CLI args (e.g. --search to open directly)
                    boolean openImmediately = false;
                    for (String arg : args) {
                        if ("--search".equalsIgnoreCase(arg) || "-s".equalsIgnoreCase(arg)) {
                            openImmediately = true;
                            break;
                        }
                        if ("--window".equalsIgnoreCase(arg) || "-w".equalsIgnoreCase(arg)) {
                            searchWindow.setVisible(true);
                        }
                    }
                    if (openImmediately || args.length == 0) {
                        searchPanel.showPanel();
                    }
                }

                // Shutdown hook
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    hotkey.stop();
                    engine.stop();
                }));

            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "Error starting DigUp: " + e.getMessage(), "DigUp Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
