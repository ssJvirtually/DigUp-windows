package com.digup.ui;

import com.digup.engine.Engine;
import com.digup.model.FileKind;
import com.digup.model.SearchHit;
import com.digup.platform.CodeEditor;
import com.digup.platform.ShellHelper;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Full Search & Gallery Window with filter tabs, table/grid list, and detail pane.
 */
public final class SearchWindow extends JFrame {

    private final Engine engine;
    private final JTextField searchField;
    private final JTable resultsTable;
    private final DefaultTableModel tableModel;
    private final JPanel detailPane;
    private final List<SearchHit> currentHits = new ArrayList<>();
    private FileKind selectedKindFilter = null;

    public SearchWindow(Engine engine) {
        super("DigUp - Multimodal Desktop Search");
        this.engine = engine;

        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(1040, 680);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG_DARK);

        JPanel content = new JPanel(new BorderLayout(0, 0));
        content.setBackground(Theme.BG_DARK);

        // Top Filter & Search Bar
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setBackground(Theme.BG_DARK);
        topPanel.setBorder(new EmptyBorder(12, 16, 10, 16));

        // Search row
        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        searchRow.setBackground(Theme.BG_DARK);

        JLabel searchIcon = new JLabel("🔍");
        searchIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
        searchRow.add(searchIcon, BorderLayout.WEST);

        searchField = new JTextField();
        searchField.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        searchField.putClientProperty("JTextField.placeholderText", "Search across images, PDFs, documents, audio, video and code...");
        searchRow.add(searchField, BorderLayout.CENTER);

        JButton searchBtn = new JButton("Search");
        searchBtn.addActionListener(e -> runSearch());
        searchRow.add(searchBtn, BorderLayout.EAST);

        topPanel.add(searchRow, BorderLayout.NORTH);

        // Filter tabs row
        JPanel tabsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tabsRow.setBackground(Theme.BG_DARK);

        String[] tabNames = {"All", "Screenshots", "Pictures", "PDFs", "Documents", "Media", "Code"};
        FileKind[] tabKinds = {null, FileKind.screenshot, FileKind.image, FileKind.pdf, FileKind.doc, FileKind.video, FileKind.code};

        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < tabNames.length; i++) {
            final FileKind fk = tabKinds[i];
            JToggleButton btn = new JToggleButton(tabNames[i], i == 0);
            btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            btn.addActionListener(e -> {
                selectedKindFilter = fk;
                runSearch();
            });
            group.add(btn);
            tabsRow.add(btn);
        }

        topPanel.add(tabsRow, BorderLayout.SOUTH);
        content.add(topPanel, BorderLayout.NORTH);

        // Center Split (Results table & Detail pane)
        String[] cols = {"Kind", "Name", "Score", "Location / Excerpt"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        resultsTable = new JTable(tableModel);
        resultsTable.setBackground(Theme.BG_DARK);
        resultsTable.setForeground(Color.WHITE);
        resultsTable.setRowHeight(28);
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.setShowGrid(false);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(80);
        resultsTable.getColumnModel().getColumn(2).setMaxWidth(70);

        JScrollPane tableScroll = new JScrollPane(resultsTable);
        tableScroll.setBorder(new LineBorder(Theme.BORDER_COLOR, 1));

        detailPane = new JPanel(new BorderLayout());
        detailPane.setBackground(Theme.PANEL_BG);
        detailPane.setPreferredSize(new Dimension(380, 0));
        detailPane.setBorder(new EmptyBorder(16, 16, 16, 16));
        renderEmptyDetail();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detailPane);
        splitPane.setDividerLocation(640);
        splitPane.setDividerSize(2);
        splitPane.setBorder(null);

        content.add(splitPane, BorderLayout.CENTER);

        // Bottom bar
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(Theme.BG_DARK);
        bottomBar.setBorder(new EmptyBorder(8, 16, 8, 16));
        JLabel statusLbl = new JLabel("Double-click to open  •  DigUp Windows Multimodal Search");
        statusLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLbl.setForeground(Theme.TEXT_MUTED);
        bottomBar.add(statusLbl, BorderLayout.WEST);
        content.add(bottomBar, BorderLayout.SOUTH);

        setContentPane(content);

        initListeners();
    }

    private void initListeners() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { runSearch(); }
            @Override public void removeUpdate(DocumentEvent e) { runSearch(); }
            @Override public void changedUpdate(DocumentEvent e) { runSearch(); }
        });

        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = resultsTable.getSelectedRow();
                if (row >= 0 && row < currentHits.size()) {
                    renderDetail(currentHits.get(row));
                } else {
                    renderEmptyDetail();
                }
            }
        });

        resultsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = resultsTable.getSelectedRow();
                    if (row >= 0 && row < currentHits.size()) {
                        openHit(currentHits.get(row));
                    }
                }
            }
        });
    }

    private void runSearch() {
        String q = searchField.getText().trim();
        if (q.isEmpty()) {
            tableModel.setRowCount(0);
            currentHits.clear();
            renderEmptyDetail();
            return;
        }

        Set<FileKind> kinds = (selectedKindFilter != null) ? Set.of(selectedKindFilter) : null;
        SwingUtilities.invokeLater(() -> {
            try {
                List<SearchHit> hits = engine.search(q, kinds, true, 100);
                currentHits.clear();
                currentHits.addAll(hits);
                tableModel.setRowCount(0);

                for (SearchHit h : hits) {
                    tableModel.addRow(new Object[]{
                        h.getKind().name(),
                        h.getFileName(),
                        String.format("%.1f", h.getScore()),
                        h.getExcerpt() != null ? h.getExcerpt().replace("«", "").replace("»", "") : h.getPath()
                    });
                }
                if (!hits.isEmpty()) {
                    resultsTable.setRowSelectionInterval(0, 0);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    private void renderEmptyDetail() {
        detailPane.removeAll();
        JLabel lbl = new JLabel("Select an item to view preview & details", SwingConstants.CENTER);
        lbl.setForeground(Theme.TEXT_MUTED);
        detailPane.add(lbl, BorderLayout.CENTER);
        detailPane.revalidate();
        detailPane.repaint();
    }

    private void renderDetail(SearchHit hit) {
        detailPane.removeAll();

        JPanel header = new JPanel(new GridLayout(3, 1, 0, 4));
        header.setBackground(Theme.PANEL_BG);

        JLabel title = new JLabel(hit.getFileName());
        title.setFont(new Font("Segoe UI", Font.BOLD, 16));
        title.setForeground(Color.WHITE);

        JLabel pathLabel = new JLabel(hit.getPath());
        pathLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        pathLabel.setForeground(Theme.TEXT_MUTED);

        String dateStr = new SimpleDateFormat("MMM d, yyyy HH:mm").format(new Date((long) (hit.getModified() * 1000)));
        JLabel metaLabel = new JLabel("Score: " + String.format("%.2f", hit.getScore()) + "  •  " + dateStr);
        metaLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        metaLabel.setForeground(Theme.TEXT_MUTED);

        header.add(title);
        header.add(pathLabel);
        header.add(metaLabel);

        detailPane.add(header, BorderLayout.NORTH);

        // Body Preview
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Theme.PANEL_BG);
        body.setBorder(new EmptyBorder(12, 0, 12, 0));

        File file = new File(hit.getPath());

        if (hit.getKind() == FileKind.image || hit.getKind() == FileKind.screenshot) {
            try {
                BufferedImage img = ImageIO.read(file);
                if (img != null) {
                    JLabel imgLabel = new JLabel() {
                        @Override
                        protected void paintComponent(Graphics g) {
                            super.paintComponent(g);
                            int maxW = getWidth();
                            int maxH = getHeight();
                            double scale = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
                            scale = Math.min(scale, 1.0);
                            int w = (int) (img.getWidth() * scale);
                            int h = (int) (img.getHeight() * scale);
                            g.drawImage(img, (maxW - w) / 2, (maxH - h) / 2, w, h, null);
                        }
                    };
                    body.add(imgLabel, BorderLayout.CENTER);
                }
            } catch (Exception ignored) {}
        } else {
            JTextArea textArea = new JTextArea();
            textArea.setEditable(false);
            textArea.setBackground(Theme.BG_DARK);
            textArea.setForeground(new Color(220, 220, 225));
            textArea.setFont(new Font("Consolas", Font.PLAIN, 12));
            textArea.setBorder(new EmptyBorder(8, 8, 8, 8));

            String content = hit.getExcerpt();
            if (content == null || content.isEmpty()) {
                try {
                    content = Files.readString(file.toPath());
                    if (content.length() > 2000) content = content.substring(0, 2000) + "\n...";
                } catch (Exception ignored) {}
            }
            if (content != null) {
                textArea.setText(content.replace("«", "").replace("»", ""));
            }
            body.add(new JScrollPane(textArea), BorderLayout.CENTER);
        }

        detailPane.add(body, BorderLayout.CENTER);

        // Footer Actions
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setBackground(Theme.PANEL_BG);

        JButton openBtn = new JButton("Open");
        openBtn.addActionListener(e -> openHit(hit));

        JButton revealBtn = new JButton("Reveal");
        revealBtn.addActionListener(e -> ShellHelper.revealInExplorer(file));

        footer.add(openBtn);
        footer.add(revealBtn);
        detailPane.add(footer, BorderLayout.SOUTH);

        detailPane.revalidate();
        detailPane.repaint();
    }

    private void openHit(SearchHit hit) {
        File file = new File(hit.getPath());
        if (hit.getKind() == FileKind.code && hit.getFocus() != null) {
            CodeEditor.openAtLine("vscode", file, hit.getFocus().intValue());
        } else {
            ShellHelper.openFile(file);
        }
    }
}
