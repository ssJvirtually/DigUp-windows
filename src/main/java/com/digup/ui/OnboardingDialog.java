package com.digup.ui;

import com.digup.model.AppSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;

/**
 * First-run setup wizard guiding users through folder selection and hotkey onboarding.
 */
public final class OnboardingDialog extends JDialog {

    private final AppSettings settings;
    private final Runnable onComplete;
    private final DefaultListModel<String> folderListModel;

    public OnboardingDialog(AppSettings settings, Runnable onComplete) {
        super((Frame) null, "Welcome to DigUp", true);
        this.settings = settings;
        this.onComplete = onComplete;

        setSize(540, 480);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG_DARK);

        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBackground(Theme.BG_DARK);
        content.setBorder(new EmptyBorder(24, 28, 20, 28));

        // Header
        JPanel header = new JPanel(new GridLayout(2, 1, 0, 4));
        header.setBackground(Theme.BG_DARK);

        JLabel title = new JLabel("Welcome to DigUp for Windows");
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(Color.WHITE);

        JLabel subtitle = new JLabel("Instant local multimodal AI search for screenshots, documents, media & code.");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setForeground(Theme.TEXT_MUTED);

        header.add(title);
        header.add(subtitle);
        content.add(header, BorderLayout.NORTH);

        // Center: folder selection
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBackground(Theme.BG_DARK);

        JLabel chooseLbl = new JLabel("Select folders to index:");
        chooseLbl.setFont(new Font("Segoe UI", Font.BOLD, 13));
        chooseLbl.setForeground(Color.WHITE);
        center.add(chooseLbl, BorderLayout.NORTH);

        folderListModel = new DefaultListModel<>();
        // Default standard user folders
        String userHome = System.getProperty("user.home");
        String[] defaults = {"Pictures", "Documents", "Desktop", "Downloads"};
        for (String d : defaults) {
            File f = new File(userHome, d);
            if (f.exists() && f.isDirectory()) {
                folderListModel.addElement(f.getAbsolutePath());
            }
        }

        JList<String> list = new JList<>(folderListModel);
        list.setBackground(Theme.PANEL_BG);
        list.setForeground(Color.WHITE);
        center.add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel folderBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        folderBtns.setBackground(Theme.BG_DARK);

        JButton addBtn = new JButton("Add Folder...");
        addBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String p = chooser.getSelectedFile().getAbsolutePath();
                if (!folderListModel.contains(p)) folderListModel.addElement(p);
            }
        });

        JButton remBtn = new JButton("Remove");
        remBtn.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0) folderListModel.remove(idx);
        });

        folderBtns.add(addBtn);
        folderBtns.add(remBtn);
        center.add(folderBtns, BorderLayout.SOUTH);

        content.add(center, BorderLayout.CENTER);

        // Bottom
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Theme.BG_DARK);

        JLabel hotkeyNote = new JLabel("Global Search Shortcut: " + settings.hotkey);
        hotkeyNote.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        hotkeyNote.setForeground(Theme.TEXT_MUTED);
        footer.add(hotkeyNote, BorderLayout.WEST);

        JButton finishBtn = new JButton("Start DigUp");
        finishBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        finishBtn.setBackground(Theme.ACCENT_COLOR);
        finishBtn.setForeground(Color.WHITE);
        finishBtn.addActionListener(e -> finishOnboarding());
        footer.add(finishBtn, BorderLayout.EAST);

        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
    }

    private void finishOnboarding() {
        settings.roots = new ArrayList<>();
        for (int i = 0; i < folderListModel.size(); i++) {
            settings.roots.add(folderListModel.get(i));
        }
        settings.onboarded = true;
        settings.save();
        setVisible(false);
        if (onComplete != null) onComplete.run();
    }
}
