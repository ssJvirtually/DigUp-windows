package com.digup.ui;

import com.digup.engine.Engine;
import com.digup.model.AppSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;

/**
 * Settings dialog with General, Folders, Code, and Storage tabs.
 */
public final class SettingsDialog extends JDialog {

    private final AppSettings settings;
    private final Engine engine;

    private final JTextField hotkeyField;
    private final JCheckBox batteryCheck;
    private final JCheckBox startupCheck;

    private final DefaultListModel<String> foldersModel;
    private final DefaultListModel<String> codeFoldersModel;
    private final JComboBox<String> editorCombo;

    public SettingsDialog(Frame parent, AppSettings settings, Engine engine) {
        super(parent, "DigUp Settings", true);
        this.settings = settings;
        this.engine = engine;

        setSize(580, 480);
        setLocationRelativeTo(parent);
        getContentPane().setBackground(Theme.BG_DARK);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setBackground(Theme.BG_DARK);
        tabbedPane.setForeground(Color.WHITE);

        // 1. General Tab
        JPanel generalPanel = new JPanel(new GridLayout(4, 1, 10, 10));
        generalPanel.setBackground(Theme.BG_DARK);
        generalPanel.setBorder(new EmptyBorder(16, 20, 16, 20));

        JPanel hotkeyRow = new JPanel(new BorderLayout(8, 0));
        hotkeyRow.setBackground(Theme.BG_DARK);
        hotkeyRow.add(new JLabel("Global Search Shortcut:"), BorderLayout.WEST);
        hotkeyField = new JTextField(settings.hotkey);
        hotkeyRow.add(hotkeyField, BorderLayout.CENTER);
        generalPanel.add(hotkeyRow);

        batteryCheck = new JCheckBox("Index while on battery power", settings.indexOnBattery);
        batteryCheck.setBackground(Theme.BG_DARK);
        batteryCheck.setForeground(Color.WHITE);
        generalPanel.add(batteryCheck);

        startupCheck = new JCheckBox("Start DigUp on Windows login", settings.launchAtLogin);
        startupCheck.setBackground(Theme.BG_DARK);
        startupCheck.setForeground(Color.WHITE);
        generalPanel.add(startupCheck);

        tabbedPane.addTab("General", generalPanel);

        // 2. Folders Tab
        JPanel foldersPanel = new JPanel(new BorderLayout(0, 10));
        foldersPanel.setBackground(Theme.BG_DARK);
        foldersPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        foldersModel = new DefaultListModel<>();
        for (String r : settings.roots) foldersModel.addElement(r);
        JList<String> foldersList = new JList<>(foldersModel);
        foldersList.setBackground(Theme.PANEL_BG);
        foldersList.setForeground(Color.WHITE);

        JScrollPane foldersScroll = new JScrollPane(foldersList);
        foldersScroll.setBorder(new LineBorder(Theme.BORDER_COLOR, 1));
        foldersPanel.add(foldersScroll, BorderLayout.CENTER);

        JPanel folderBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        folderBtns.setBackground(Theme.BG_DARK);
        JButton addFolderBtn = new JButton("Add Folder...");
        addFolderBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String path = chooser.getSelectedFile().getAbsolutePath();
                if (!foldersModel.contains(path)) foldersModel.addElement(path);
            }
        });
        JButton remFolderBtn = new JButton("Remove");
        remFolderBtn.addActionListener(e -> {
            int idx = foldersList.getSelectedIndex();
            if (idx >= 0) foldersModel.remove(idx);
        });
        folderBtns.add(addFolderBtn);
        folderBtns.add(remFolderBtn);
        foldersPanel.add(folderBtns, BorderLayout.SOUTH);

        tabbedPane.addTab("Folders", foldersPanel);

        // 3. Code Tab
        JPanel codePanel = new JPanel(new BorderLayout(0, 10));
        codePanel.setBackground(Theme.BG_DARK);
        codePanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel codeTop = new JPanel(new BorderLayout(8, 0));
        codeTop.setBackground(Theme.BG_DARK);
        codeTop.add(new JLabel("Preferred Code Editor:"), BorderLayout.WEST);
        String[] editors = {"vscode", "cursor", "intellij", "pycharm", "sublime", "notepad++"};
        editorCombo = new JComboBox<>(editors);
        editorCombo.setSelectedItem(settings.codeEditor);
        codeTop.add(editorCombo, BorderLayout.CENTER);
        codePanel.add(codeTop, BorderLayout.NORTH);

        codeFoldersModel = new DefaultListModel<>();
        for (String r : settings.codeRoots) codeFoldersModel.addElement(r);
        JList<String> codeList = new JList<>(codeFoldersModel);
        codeList.setBackground(Theme.PANEL_BG);
        codeList.setForeground(Color.WHITE);

        JScrollPane codeScroll = new JScrollPane(codeList);
        codeScroll.setBorder(new LineBorder(Theme.BORDER_COLOR, 1));
        codePanel.add(codeScroll, BorderLayout.CENTER);

        JPanel codeBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        codeBtns.setBackground(Theme.BG_DARK);
        JButton addCodeBtn = new JButton("Add Repo...");
        addCodeBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String path = chooser.getSelectedFile().getAbsolutePath();
                if (!codeFoldersModel.contains(path)) codeFoldersModel.addElement(path);
            }
        });
        JButton remCodeBtn = new JButton("Remove");
        remCodeBtn.addActionListener(e -> {
            int idx = codeList.getSelectedIndex();
            if (idx >= 0) codeFoldersModel.remove(idx);
        });
        codeBtns.add(addCodeBtn);
        codeBtns.add(remCodeBtn);
        codePanel.add(codeBtns, BorderLayout.SOUTH);

        tabbedPane.addTab("Code", codePanel);

        // 4. Storage Tab
        JPanel storagePanel = new JPanel(new GridLayout(4, 1, 10, 10));
        storagePanel.setBackground(Theme.BG_DARK);
        storagePanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        JLabel modelLbl = new JLabel("Model: EmbeddingGemma 2 (768 dimensions)");
        modelLbl.setFont(new Font("Segoe UI", Font.BOLD, 13));
        modelLbl.setForeground(Color.WHITE);
        storagePanel.add(modelLbl);

        JLabel storagePathLbl = new JLabel("Index Directory: " + AppSettings.getAppDataDir().toString());
        storagePathLbl.setForeground(Theme.TEXT_MUTED);
        storagePanel.add(storagePathLbl);

        JButton reindexBtn = new JButton("Re-index Everything");
        reindexBtn.setForeground(new Color(239, 68, 68)); // danger red
        reindexBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to re-index all files? This will reset and rebuild the search index.",
                "Confirm Re-index", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    engine.getMainStore().resetAll();
                    engine.getCodeStore().resetAll();
                    engine.triggerCrawl();
                    JOptionPane.showMessageDialog(this, "Re-indexing started in the background.");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage());
                }
            }
        });
        storagePanel.add(reindexBtn);

        tabbedPane.addTab("Storage", storagePanel);

        // Bottom dialog buttons
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        bottomBar.setBackground(Theme.BG_DARK);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> setVisible(false));

        JButton saveBtn = new JButton("Save & Apply");
        saveBtn.setBackground(Theme.ACCENT_COLOR);
        saveBtn.setForeground(Color.WHITE);
        saveBtn.addActionListener(e -> saveAndApply());

        bottomBar.add(cancelBtn);
        bottomBar.add(saveBtn);

        getContentPane().add(tabbedPane, BorderLayout.CENTER);
        getContentPane().add(bottomBar, BorderLayout.SOUTH);
    }

    private void saveAndApply() {
        settings.hotkey = hotkeyField.getText().trim();
        settings.indexOnBattery = batteryCheck.isSelected();
        settings.launchAtLogin = startupCheck.isSelected();
        settings.codeEditor = (String) editorCombo.getSelectedItem();

        settings.roots = new ArrayList<>();
        for (int i = 0; i < foldersModel.size(); i++) {
            settings.roots.add(foldersModel.get(i));
        }

        settings.codeRoots = new ArrayList<>();
        for (int i = 0; i < codeFoldersModel.size(); i++) {
            settings.codeRoots.add(codeFoldersModel.get(i));
        }

        settings.save();
        engine.getMainStore().setScope(settings.roots);
        engine.getCodeStore().setScope(settings.codeRoots);
        engine.triggerCrawl();

        setVisible(false);
    }
}
