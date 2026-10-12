package com.digup.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;

/**
 * Modern Windows 11 Fluent look & feel configuration via FlatLaf.
 */
public final class Theme {

    public static final Color ACCENT_COLOR = new Color(59, 130, 246);      // #3B82F6
    public static final Color BG_DARK = new Color(24, 24, 27);             // #18181B
    public static final Color PANEL_BG = new Color(32, 32, 36);            // #202024
    public static final Color BORDER_COLOR = new Color(63, 63, 70);        // #3F3F46
    public static final Color TEXT_MUTED = new Color(161, 161, 170);       // #A1A1AA
    public static final Color HIGHLIGHT_BG = new Color(59, 130, 246, 40);

    private Theme() {}

    public static void setup() {
        try {
            // Apply Windows 11 FlatDarkLaf
            FlatDarkLaf.setup();

            // Configure Fluent UI properties
            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("TabbedPane.tabHeight", 36);

            // Segoe UI font (standard Windows font)
            Font segoe = new Font("Segoe UI", Font.PLAIN, 13);
            UIManager.put("defaultFont", segoe);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
