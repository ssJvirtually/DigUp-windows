package com.digup.ui;

import com.digup.engine.Engine;
import com.digup.model.AppSettings;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * Windows system tray icon management with live status and context menu.
 */
public final class SystemTrayManager {

    private final Engine engine;
    private final AppSettings settings;
    private final SearchPanel searchPanel;
    private final SearchWindow searchWindow;
    private TrayIcon trayIcon;
    private MenuItem statusItem;

    public SystemTrayManager(Engine engine, AppSettings settings, SearchPanel searchPanel, SearchWindow searchWindow) {
        this.engine = engine;
        this.settings = settings;
        this.searchPanel = searchPanel;
        this.searchWindow = searchWindow;
    }

    public void setup() {
        if (!SystemTray.isSupported()) return;

        SystemTray tray = SystemTray.getSystemTray();
        Image image = createTrayImage();

        PopupMenu menu = new PopupMenu();

        MenuItem searchItem = new MenuItem("Search (" + settings.hotkey + ")");
        searchItem.addActionListener(e -> searchPanel.showPanel());
        menu.add(searchItem);

        MenuItem windowItem = new MenuItem("Open Gallery Window");
        windowItem.addActionListener(e -> {
            searchWindow.setVisible(true);
            searchWindow.toFront();
        });
        menu.add(windowItem);

        MenuItem settingsItem = new MenuItem("Settings...");
        settingsItem.addActionListener(e -> {
            SettingsDialog dialog = new SettingsDialog(null, settings, engine);
            dialog.setVisible(true);
        });
        menu.add(settingsItem);

        menu.addSeparator();

        statusItem = new MenuItem("Status: Idle");
        statusItem.setEnabled(false);
        menu.add(statusItem);

        menu.addSeparator();

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> {
            engine.stop();
            System.exit(0);
        });
        menu.add(exitItem);

        trayIcon = new TrayIcon(image, "DigUp - Desktop Search", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1 || e.getClickCount() == 2) {
                    searchPanel.showPanel();
                }
            }
        });

        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            e.printStackTrace();
        }

        engine.setStatusListener((status, pending, total) -> {
            SwingUtilities.invokeLater(() -> {
                if (statusItem != null) {
                    statusItem.setLabel("Status: " + status);
                }
                if (trayIcon != null) {
                    trayIcon.setToolTip("DigUp: " + status);
                }
            });
        });
    }

    private static Image createTrayImage() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Circular badge with accent blue
        g.setColor(Theme.ACCENT_COLOR);
        g.fillOval(1, 1, size - 2, size - 2);

        // Inner magnifying glass icon representation
        g.setColor(Color.WHITE);
        g.drawOval(4, 4, 6, 6);
        g.drawLine(9, 9, 12, 12);
        g.dispose();

        return img;
    }
}
