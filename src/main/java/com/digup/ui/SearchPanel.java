package com.digup.ui;

import com.digup.engine.Engine;
import com.digup.model.FileKind;
import com.digup.model.SearchHit;
import com.digup.platform.CodeEditor;
import com.digup.platform.ShellHelper;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Spotlight-like floating search panel for Windows with live results and rich preview.
 */
public final class SearchPanel extends JDialog {

    private final Engine engine;
    private final JTextField searchField;
    private final DefaultListModel<SearchHit> listModel;
    private final JList<SearchHit> resultList;
    private final JPanel previewPane;
    private final JLabel statusLabel;
    private final Timer debounceTimer;

    public SearchPanel(Engine engine) {
        super((Frame) null, "DigUp Search", false);
        this.engine = engine;

        setUndecorated(true);
        setSize(840, 580);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG_DARK);
        getRootPane().setBorder(new LineBorder(Theme.BORDER_COLOR, 1, true));

        // Main layout
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(Theme.BG_DARK);

        // --- Top Search Bar ---
        JPanel topBar = new JPanel(new BorderLayout(10, 0));
        topBar.setBackground(Theme.BG_DARK);
        topBar.setBorder(new EmptyBorder(14, 16, 14, 16));

        JLabel iconLabel = new JLabel("🔍");
        iconLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        iconLabel.setForeground(Theme.TEXT_MUTED);
        topBar.add(iconLabel, BorderLayout.WEST);

        searchField = new JTextField();
        searchField.setFont(new Font("Segoe UI", Font.PLAIN, 18));
        searchField.putClientProperty("JTextField.placeholderText", "Search files, screenshots, PDFs, documents, code...");
        searchField.setBorder(null);
        searchField.setBackground(Theme.BG_DARK);
        searchField.setForeground(Color.WHITE);
        searchField.setCaretColor(Color.WHITE);
        topBar.add(searchField, BorderLayout.CENTER);

        JLabel escHint = new JLabel("ESC to close");
        escHint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        escHint.setForeground(Theme.TEXT_MUTED);
        topBar.add(escHint, BorderLayout.EAST);

        mainPanel.add(topBar, BorderLayout.NORTH);

        // Separator line
        JSeparator sep = new JSeparator();
        sep.setForeground(Theme.BORDER_COLOR);

        // --- Split Center (Results & Preview) ---
        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(sep, BorderLayout.NORTH);

        listModel = new DefaultListModel<>();
        resultList = new JList<>(listModel);
        resultList.setBackground(Theme.BG_DARK);
        resultList.setSelectionBackground(Theme.HIGHLIGHT_BG);
        resultList.setSelectionForeground(Color.WHITE);
        resultList.setCellRenderer(new ResultRenderer());

        JScrollPane listScroll = new JScrollPane(resultList);
        listScroll.setPreferredSize(new Dimension(340, 0));
        listScroll.setBorder(new CompoundBorder(
            new LineBorder(Theme.BORDER_COLOR, 0),
            new EmptyBorder(4, 4, 4, 4)
        ));

        previewPane = new JPanel(new BorderLayout());
        previewPane.setBackground(Theme.PANEL_BG);
        previewPane.setBorder(new EmptyBorder(16, 18, 16, 18));
        renderEmptyPreview();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, previewPane);
        splitPane.setDividerLocation(350);
        splitPane.setDividerSize(1);
        splitPane.setBorder(null);
        centerPanel.add(splitPane, BorderLayout.CENTER);

        mainPanel.add(centerPanel, BorderLayout.CENTER);

        // --- Bottom Status Bar ---
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(Theme.BG_DARK);
        bottomBar.setBorder(new EmptyBorder(6, 16, 8, 16));

        statusLabel = new JLabel("Enter: Open  •  Ctrl+Enter: Reveal  •  Ctrl+C: Copy Path");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        statusLabel.setForeground(Theme.TEXT_MUTED);
        bottomBar.add(statusLabel, BorderLayout.WEST);

        mainPanel.add(bottomBar, BorderLayout.SOUTH);
        setContentPane(mainPanel);

        // Debounce timer for search
        debounceTimer = new Timer(120, e -> executeSearch());
        debounceTimer.setRepeats(false);

        initListeners();
    }

    private void initListeners() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounceTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounceTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounceTimer.restart(); }
        });

        resultList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                SearchHit hit = resultList.getSelectedValue();
                if (hit != null) renderPreview(hit);
                else renderEmptyPreview();
            }
        });

        // Global key actions
        KeyAdapter keyHandler = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    hidePanel();
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    if (searchField.hasFocus() && !listModel.isEmpty()) {
                        resultList.requestFocusInWindow();
                        if (resultList.getSelectedIndex() < 0) resultList.setSelectedIndex(0);
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    if (resultList.hasFocus() && resultList.getSelectedIndex() == 0) {
                        searchField.requestFocusInWindow();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    SearchHit hit = resultList.getSelectedValue();
                    if (hit != null) {
                        if (e.isControlDown()) {
                            ShellHelper.revealInExplorer(new File(hit.getPath()));
                        } else {
                            openHit(hit);
                        }
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_C && e.isControlDown()) {
                    SearchHit hit = resultList.getSelectedValue();
                    if (hit != null) {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                            new StringSelection(hit.getPath()), null
                        );
                    }
                }
            }
        };

        searchField.addKeyListener(keyHandler);
        resultList.addKeyListener(keyHandler);

        // Click outside or window deactivation to hide
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                hidePanel();
            }
        });
    }

    public void showPanel() {
        setLocationRelativeTo(null);
        setVisible(true);
        toFront();
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    public void hidePanel() {
        setVisible(false);
    }

    private void executeSearch() {
        String q = searchField.getText().trim();
        if (q.isEmpty()) {
            listModel.clear();
            renderEmptyPreview();
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                List<SearchHit> hits = engine.search(q, null, true, 30);
                listModel.clear();
                for (SearchHit h : hits) listModel.addElement(h);
                if (!hits.isEmpty()) {
                    resultList.setSelectedIndex(0);
                } else {
                    renderEmptyPreview();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    private void openHit(SearchHit hit) {
        File file = new File(hit.getPath());
        if (hit.getKind() == FileKind.code && hit.getFocus() != null) {
            CodeEditor.openAtLine("vscode", file, hit.getFocus().intValue());
        } else {
            ShellHelper.openFile(file);
        }
        hidePanel();
    }

    private void renderEmptyPreview() {
        previewPane.removeAll();
        JLabel emptyLabel = new JLabel("Select an item to view preview", SwingConstants.CENTER);
        emptyLabel.setForeground(Theme.TEXT_MUTED);
        emptyLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        previewPane.add(emptyLabel, BorderLayout.CENTER);
        previewPane.revalidate();
        previewPane.repaint();
    }

    private void renderPreview(SearchHit hit) {
        previewPane.removeAll();

        JPanel header = new JPanel(new GridLayout(3, 1, 0, 4));
        header.setBackground(Theme.PANEL_BG);

        JLabel title = new JLabel(hit.getFileName());
        title.setFont(new Font("Segoe UI", Font.BOLD, 15));
        title.setForeground(Color.WHITE);

        JLabel pathLabel = new JLabel(hit.getPath());
        pathLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        pathLabel.setForeground(Theme.TEXT_MUTED);

        String dateStr = new SimpleDateFormat("MMM d, yyyy HH:mm").format(new Date((long) (hit.getModified() * 1000)));
        JLabel metaLabel = new JLabel(formatFileSize(hit.getSize()) + "  •  " + dateStr);
        metaLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        metaLabel.setForeground(Theme.TEXT_MUTED);

        header.add(title);
        header.add(pathLabel);
        header.add(metaLabel);

        previewPane.add(header, BorderLayout.NORTH);

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
                            int x = (maxW - w) / 2;
                            int y = (maxH - h) / 2;
                            g.drawImage(img, x, y, w, h, null);
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

        previewPane.add(body, BorderLayout.CENTER);

        // Footer Actions
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        footer.setBackground(Theme.PANEL_BG);

        JButton openBtn = new JButton("Open");
        openBtn.addActionListener(e -> openHit(hit));

        JButton revealBtn = new JButton("Reveal");
        revealBtn.addActionListener(e -> ShellHelper.revealInExplorer(file));

        footer.add(openBtn);
        footer.add(revealBtn);
        previewPane.add(footer, BorderLayout.SOUTH);

        previewPane.revalidate();
        previewPane.repaint();
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private static class ResultRenderer extends JPanel implements ListCellRenderer<SearchHit> {
        private final JLabel iconLabel = new JLabel();
        private final JLabel titleLabel = new JLabel();
        private final JLabel excerptLabel = new JLabel();
        private final JLabel badgeLabel = new JLabel();

        public ResultRenderer() {
            setLayout(new BorderLayout(8, 2));
            setBorder(new EmptyBorder(8, 10, 8, 10));
            setOpaque(true);

            JPanel textPanel = new JPanel(new GridLayout(2, 1, 0, 2));
            textPanel.setOpaque(false);
            titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            excerptLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            excerptLabel.setForeground(Theme.TEXT_MUTED);
            textPanel.add(titleLabel);
            textPanel.add(excerptLabel);

            iconLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
            badgeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            badgeLabel.setForeground(Theme.TEXT_MUTED);

            add(iconLabel, BorderLayout.WEST);
            add(textPanel, BorderLayout.CENTER);
            add(badgeLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SearchHit> list, SearchHit hit, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            setBackground(isSelected ? Theme.HIGHLIGHT_BG : Theme.BG_DARK);
            titleLabel.setForeground(Color.WHITE);

            iconLabel.setText(switch (hit.getKind()) {
                case screenshot -> "📷";
                case image -> "🖼️";
                case pdf -> "📕";
                case doc -> "📄";
                case code -> "💻";
                case audio -> "🎵";
                case video -> "🎬";
            });

            titleLabel.setText(hit.getFileName());

            String excerpt = hit.getExcerpt();
            if (excerpt != null && !excerpt.isEmpty()) {
                String html = "<html>" + excerpt.replace("«", "<b style='color:#60A5FA'>").replace("»", "</b>") + "</html>";
                excerptLabel.setText(html);
            } else {
                excerptLabel.setText(hit.getPath());
            }

            badgeLabel.setText(String.format("%.1f", hit.getScore()));
            return this;
        }
    }
}
