package com.openbash.forja.ui;

import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.config.QuickPrompt;
import com.openbash.forja.llm.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UI tab for viewing and editing all prompts:
 * - System prompts → full text editor
 * - Quick prompts → visual table editor with AI assistant
 */
public class PromptsTab extends JPanel {

    private final PromptManager promptManager;
    private final LLMProviderFactory providerFactory;
    private final ConfigManager config;

    // Left list
    private final DefaultListModel<String> listModel;
    private final JList<String> promptList;
    private final List<String> keys = new ArrayList<>();

    // Right: card layout switching between text editor and quick prompts editor
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel;
    private static final String CARD_TEXT = "text";
    private static final String CARD_QUICK = "quick";
    private static final String CARD_EMPTY = "empty";

    // Shared info bar
    private final JLabel infoLabel;
    private final JLabel overrideLabel;

    // --- Text editor components ---
    private final JTextArea textEditor;
    private JButton textSaveBtn;
    private JButton textResetBtn;

    // --- Quick prompts editor components ---
    private final List<QRow> qRows = new ArrayList<>();
    private QRowTableModel qTableModel;
    private JTable qTable;
    private JButton qSaveBtn;
    private JButton qResetBtn;

    // --- AI assistant ---
    private JTextField aiInput;
    private JButton aiGenerateBtn;
    private JProgressBar aiProgress;
    private JLabel aiStatus;

    // Callbacks to refresh sidebar/dropdown in other tabs after save
    private Runnable onQuickPromptsChanged;

    public PromptsTab(PromptManager promptManager, LLMProviderFactory providerFactory, ConfigManager config) {
        this.promptManager = promptManager;
        this.providerFactory = providerFactory;
        this.config = config;
        setLayout(new BorderLayout());
        ForjaTheme.applyTo(this);

        // ===== LEFT: prompt list =====
        listModel = new DefaultListModel<>();
        Map<String, PromptManager.PromptInfo> registry = promptManager.getRegistry();
        for (var entry : registry.entrySet()) {
            keys.add(entry.getKey());
            listModel.addElement(buildListLabel(entry.getKey(), entry.getValue()));
        }

        promptList = new JList<>(listModel);
        promptList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ForjaTheme.styleList(promptList);
        promptList.setCellRenderer(new PromptListRenderer());
        promptList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onSelectionChanged();
        });

        JScrollPane listScroll = new JScrollPane(promptList);
        ForjaTheme.styleScrollPane(listScroll);
        listScroll.setPreferredSize(new Dimension(250, 0));

        // ===== RIGHT: shared info bar + card panel =====
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(ForjaTheme.BG_DARK);

        // Info bar (shared)
        JPanel infoBar = new JPanel(new BorderLayout());
        infoBar.setBackground(ForjaTheme.BG_TOOLBAR);
        infoBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ForjaTheme.BORDER_COLOR),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        infoLabel = ForjaTheme.label("Select a prompt to edit");
        infoLabel.setFont(ForjaTheme.FONT_UI_BOLD);
        overrideLabel = ForjaTheme.statusLabel("");
        overrideLabel.setFont(ForjaTheme.FONT_UI_SMALL);

        JPanel infoLeft = new JPanel(new BorderLayout());
        infoLeft.setBackground(ForjaTheme.BG_TOOLBAR);
        infoLeft.add(infoLabel, BorderLayout.NORTH);
        infoLeft.add(overrideLabel, BorderLayout.SOUTH);
        infoBar.add(infoLeft, BorderLayout.CENTER);
        rightPanel.add(infoBar, BorderLayout.NORTH);

        // Card panel
        cardPanel = new JPanel(cardLayout);
        cardPanel.setBackground(ForjaTheme.BG_DARK);

        // Empty card
        JPanel emptyCard = new JPanel(new GridBagLayout());
        emptyCard.setBackground(ForjaTheme.BG_DARK);
        JLabel emptyLabel = ForjaTheme.label("Select a prompt from the list to start editing");
        emptyLabel.setForeground(ForjaTheme.TEXT_MUTED);
        emptyCard.add(emptyLabel);
        cardPanel.add(emptyCard, CARD_EMPTY);

        // Text editor card
        textEditor = new JTextArea();
        textEditor.setEditable(true);
        ForjaTheme.styleTextArea(textEditor);
        textEditor.setFont(ForjaTheme.FONT_MONO);
        textEditor.setLineWrap(true);
        textEditor.setWrapStyleWord(true);
        textEditor.setTabSize(2);
        cardPanel.add(buildTextEditorCard(), CARD_TEXT);

        // Quick prompts editor card
        cardPanel.add(buildQuickEditorCard(), CARD_QUICK);

        rightPanel.add(cardPanel, BorderLayout.CENTER);

        // ===== Main split =====
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightPanel);
        ForjaTheme.styleSplitPane(split);
        split.setDividerLocation(250);
        add(split, BorderLayout.CENTER);

        cardLayout.show(cardPanel, CARD_EMPTY);
    }

    public void setOnQuickPromptsChanged(Runnable callback) {
        this.onQuickPromptsChanged = callback;
    }

    // ========== Text Editor Card ==========

    private JPanel buildTextEditorCard() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ForjaTheme.BG_DARK);

        JScrollPane editorScroll = new JScrollPane(textEditor);
        ForjaTheme.styleScrollPane(editorScroll);
        panel.add(editorScroll, BorderLayout.CENTER);

        // Bottom bar
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(ForjaTheme.BG_TOOLBAR);
        bottomBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ForjaTheme.BORDER_COLOR));

        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        leftBtns.setBackground(ForjaTheme.BG_TOOLBAR);

        textSaveBtn = ForjaTheme.primaryButton("Save Override", ForjaTheme.ACCENT_GREEN);
        textSaveBtn.addActionListener(e -> saveTextPrompt());
        textSaveBtn.setEnabled(false);

        textResetBtn = ForjaTheme.ghostButton("Reset to Default");
        textResetBtn.addActionListener(e -> resetTextPrompt());
        textResetBtn.setEnabled(false);

        JButton diffBtn = ForjaTheme.ghostButton("Show Diff");
        diffBtn.addActionListener(e -> showDiff());

        leftBtns.add(textSaveBtn);
        leftBtns.add(textResetBtn);
        leftBtns.add(diffBtn);

        JPanel rightInfo = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        rightInfo.setBackground(ForjaTheme.BG_TOOLBAR);
        rightInfo.add(ForjaTheme.statusLabel("Overrides saved to: outputDir/prompts/"));

        bottomBar.add(leftBtns, BorderLayout.WEST);
        bottomBar.add(rightInfo, BorderLayout.EAST);
        panel.add(bottomBar, BorderLayout.SOUTH);

        return panel;
    }

    // ========== Quick Prompts Editor Card ==========

    private JPanel buildQuickEditorCard() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ForjaTheme.BG_DARK);

        // --- Table ---
        qTableModel = new QRowTableModel();
        qTable = new JTable(qTableModel);
        styleQuickTable(qTable);
        qTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        qTable.getColumnModel().getColumn(0).setMaxWidth(220);
        qTable.getColumnModel().getColumn(1).setPreferredWidth(500);

        qTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editQRow();
            }
        });

        JScrollPane tableScroll = new JScrollPane(qTable);
        tableScroll.getViewport().setBackground(ForjaTheme.BG_TABLE);
        tableScroll.setBorder(BorderFactory.createLineBorder(ForjaTheme.BORDER_COLOR));

        // --- Right buttons ---
        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.Y_AXIS));
        btnPanel.setBackground(ForjaTheme.BG_DARK);
        btnPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        JButton addCatBtn = qButton("+ Category", ForjaTheme.ACCENT_GREEN);
        JButton addBtn = qButton("+ Prompt", ForjaTheme.ACCENT_BLUE);
        JButton editBtn = qButton("Edit", ForjaTheme.TEXT_DEFAULT);
        JButton removeBtn = qButton("Remove", ForjaTheme.ACCENT_RED);
        JButton dupBtn = qButton("Duplicate", ForjaTheme.TEXT_DEFAULT);
        JButton upBtn = qButton("Move Up", ForjaTheme.TEXT_DEFAULT);
        JButton downBtn = qButton("Move Down", ForjaTheme.TEXT_DEFAULT);

        addCatBtn.addActionListener(e -> addQCategory());
        addBtn.addActionListener(e -> addQPrompt());
        editBtn.addActionListener(e -> editQRow());
        removeBtn.addActionListener(e -> removeQRow());
        dupBtn.addActionListener(e -> duplicateQRow());
        upBtn.addActionListener(e -> moveQRow(-1));
        downBtn.addActionListener(e -> moveQRow(1));

        btnPanel.add(addCatBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(addBtn);
        btnPanel.add(Box.createVerticalStrut(12));
        btnPanel.add(editBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(removeBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(dupBtn);
        btnPanel.add(Box.createVerticalStrut(12));
        btnPanel.add(upBtn);
        btnPanel.add(Box.createVerticalStrut(4));
        btnPanel.add(downBtn);
        btnPanel.add(Box.createVerticalGlue());

        JPanel tableArea = new JPanel(new BorderLayout());
        tableArea.setBackground(ForjaTheme.BG_DARK);
        tableArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        tableArea.add(tableScroll, BorderLayout.CENTER);
        tableArea.add(btnPanel, BorderLayout.EAST);

        panel.add(tableArea, BorderLayout.CENTER);

        // --- Bottom: AI assistant + save/reset ---
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(ForjaTheme.BG_DARK);

        // AI assistant row
        JPanel aiPanel = new JPanel(new BorderLayout(6, 0));
        aiPanel.setBackground(ForjaTheme.BG_TOOLBAR);
        aiPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ForjaTheme.BORDER_COLOR),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        JLabel aiLabel = new JLabel("AI Assistant:");
        aiLabel.setFont(ForjaTheme.FONT_UI_BOLD);
        aiLabel.setForeground(ForjaTheme.ACCENT_ORANGE);

        aiInput = new JTextField();
        ForjaTheme.styleTextField(aiInput);
        aiInput.setToolTipText("Describe what you want and the AI will generate a professional prompt");

        aiGenerateBtn = ForjaTheme.primaryButton("Generate", ForjaTheme.ACCENT_ORANGE);
        aiGenerateBtn.addActionListener(e -> aiGenerate());
        aiInput.addActionListener(e -> aiGenerate()); // Enter key

        aiProgress = ForjaTheme.progressBar();
        aiStatus = ForjaTheme.statusLabel("");

        JPanel aiLeft = new JPanel(new BorderLayout(6, 0));
        aiLeft.setBackground(ForjaTheme.BG_TOOLBAR);
        aiLeft.add(aiLabel, BorderLayout.WEST);
        aiLeft.add(aiInput, BorderLayout.CENTER);

        JPanel aiRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        aiRight.setBackground(ForjaTheme.BG_TOOLBAR);
        aiRight.add(aiStatus);
        aiRight.add(aiProgress);
        aiRight.add(aiGenerateBtn);

        aiPanel.add(aiLeft, BorderLayout.CENTER);
        aiPanel.add(aiRight, BorderLayout.EAST);

        // Save/reset bar
        JPanel saveBar = new JPanel(new BorderLayout());
        saveBar.setBackground(ForjaTheme.BG_TOOLBAR);
        saveBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ForjaTheme.BORDER_COLOR));

        JPanel saveBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        saveBtns.setBackground(ForjaTheme.BG_TOOLBAR);

        qSaveBtn = ForjaTheme.primaryButton("Save", ForjaTheme.ACCENT_GREEN);
        qSaveBtn.addActionListener(e -> saveQuickPrompts());

        qResetBtn = ForjaTheme.ghostButton("Reset to Defaults");
        qResetBtn.addActionListener(e -> resetQuickPrompts());

        saveBtns.add(qSaveBtn);
        saveBtns.add(qResetBtn);

        JPanel saveRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        saveRight.setBackground(ForjaTheme.BG_TOOLBAR);
        saveRight.add(ForjaTheme.statusLabel("Changes apply after save"));

        saveBar.add(saveBtns, BorderLayout.WEST);
        saveBar.add(saveRight, BorderLayout.EAST);

        bottomPanel.add(aiPanel, BorderLayout.NORTH);
        bottomPanel.add(saveBar, BorderLayout.SOUTH);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ========== Selection / Switching ==========

    private void onSelectionChanged() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0 || idx >= keys.size()) {
            cardLayout.show(cardPanel, CARD_EMPTY);
            return;
        }

        String key = keys.get(idx);
        PromptManager.PromptInfo info = promptManager.getRegistry().get(key);

        // Update info bar
        infoLabel.setText(info.getDisplayName());
        boolean hasOverride = promptManager.hasOverride(key);
        if (hasOverride) {
            overrideLabel.setText(info.getDescription() + "  —  OVERRIDDEN (custom version active)");
            overrideLabel.setForeground(ForjaTheme.ACCENT_ORANGE);
        } else {
            overrideLabel.setText(info.getDescription() + "  —  Using bundled default");
            overrideLabel.setForeground(ForjaTheme.TEXT_MUTED);
        }

        if (info.isQuickPrompts()) {
            // Load into table
            qRows.clear();
            List<QuickPrompt> prompts = QuickPrompt.parse(promptManager.get(key));
            for (QuickPrompt qp : prompts) {
                if (qp.isCategory()) {
                    qRows.add(new QRow(true, qp.category(), ""));
                } else {
                    qRows.add(new QRow(false, qp.label(), qp.prompt()));
                }
            }
            qTableModel.fireTableDataChanged();
            qResetBtn.setEnabled(hasOverride);
            cardLayout.show(cardPanel, CARD_QUICK);
        } else {
            // Load into text editor
            textEditor.setText(promptManager.get(key));
            textEditor.setCaretPosition(0);
            textSaveBtn.setEnabled(true);
            textResetBtn.setEnabled(hasOverride);
            cardLayout.show(cardPanel, CARD_TEXT);
        }
    }

    // ========== Text Editor Actions ==========

    private void saveTextPrompt() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0) return;
        String key = keys.get(idx);
        try {
            promptManager.save(key, textEditor.getText());
            refreshListLabel(idx, true);
            overrideLabel.setText("Saved. Changes take effect on next LLM call.");
            overrideLabel.setForeground(ForjaTheme.ACCENT_GREEN);
            textResetBtn.setEnabled(true);
        } catch (Exception e) {
            overrideLabel.setText("Save failed: " + e.getMessage());
            overrideLabel.setForeground(ForjaTheme.ACCENT_RED);
        }
    }

    private void resetTextPrompt() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0) return;
        String key = keys.get(idx);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Reset to bundled default? Your custom version will be deleted.",
                "Reset Prompt", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        promptManager.reset(key);
        textEditor.setText(promptManager.get(key));
        textEditor.setCaretPosition(0);
        refreshListLabel(idx, false);
        overrideLabel.setText("Reset to default.");
        overrideLabel.setForeground(ForjaTheme.TEXT_MUTED);
        textResetBtn.setEnabled(false);
    }

    private void showDiff() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0) return;
        String key = keys.get(idx);
        String current = textEditor.getText();
        String bundled = promptManager.getDefault(key);

        if (current.equals(bundled)) {
            JOptionPane.showMessageDialog(this, "No differences.", "Diff", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JTextArea leftArea = new JTextArea(bundled);
        leftArea.setEditable(false);
        leftArea.setFont(ForjaTheme.FONT_MONO_SMALL);
        JTextArea rightArea = new JTextArea(current);
        rightArea.setEditable(false);
        rightArea.setFont(ForjaTheme.FONT_MONO_SMALL);

        JSplitPane diffSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                titled("Default (bundled)", new JScrollPane(leftArea)),
                titled("Current (editor)", new JScrollPane(rightArea)));
        diffSplit.setDividerLocation(400);
        diffSplit.setPreferredSize(new Dimension(860, 500));
        JOptionPane.showMessageDialog(this, diffSplit, "Diff — " + key, JOptionPane.PLAIN_MESSAGE);
    }

    // ========== Quick Prompts Actions ==========

    private void addQCategory() {
        String name = JOptionPane.showInputDialog(this, "Category name:", "New Category", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.trim().isEmpty()) {
            int sel = qTable.getSelectedRow();
            int at = sel >= 0 ? sel + 1 : qRows.size();
            qRows.add(at, new QRow(true, name.trim(), ""));
            qTableModel.fireTableDataChanged();
            qTable.setRowSelectionInterval(at, at);
        }
    }

    private void addQPrompt() {
        String[] result = showPromptInputDialog("New Prompt", "", "");
        if (result != null) {
            int sel = qTable.getSelectedRow();
            int at = sel >= 0 ? sel + 1 : qRows.size();
            qRows.add(at, new QRow(false, result[0], result[1]));
            qTableModel.fireTableDataChanged();
            qTable.setRowSelectionInterval(at, at);
        }
    }

    private void editQRow() {
        int idx = qTable.getSelectedRow();
        if (idx < 0) return;
        QRow row = qRows.get(idx);

        if (row.isCategory) {
            String name = JOptionPane.showInputDialog(this, "Category name:", row.label);
            if (name != null && !name.trim().isEmpty()) {
                row.label = name.trim();
                qTableModel.fireTableDataChanged();
            }
        } else {
            String[] result = showPromptInputDialog("Edit Prompt", row.label, row.prompt);
            if (result != null) {
                row.label = result[0];
                row.prompt = result[1];
                qTableModel.fireTableDataChanged();
            }
        }
    }

    private void removeQRow() {
        int idx = qTable.getSelectedRow();
        if (idx < 0) return;
        qRows.remove(idx);
        qTableModel.fireTableDataChanged();
        if (!qRows.isEmpty()) {
            int sel = Math.min(idx, qRows.size() - 1);
            qTable.setRowSelectionInterval(sel, sel);
        }
    }

    private void duplicateQRow() {
        int idx = qTable.getSelectedRow();
        if (idx < 0) return;
        QRow r = qRows.get(idx);
        qRows.add(idx + 1, new QRow(r.isCategory, r.label + " (copy)", r.prompt));
        qTableModel.fireTableDataChanged();
        qTable.setRowSelectionInterval(idx + 1, idx + 1);
    }

    private void moveQRow(int dir) {
        int idx = qTable.getSelectedRow();
        if (idx < 0) return;
        int target = idx + dir;
        if (target < 0 || target >= qRows.size()) return;
        QRow r = qRows.remove(idx);
        qRows.add(target, r);
        qTableModel.fireTableDataChanged();
        qTable.setRowSelectionInterval(target, target);
    }

    private void saveQuickPrompts() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0) return;
        String key = keys.get(idx);
        try {
            promptManager.save(key, serializeQRows());
            refreshListLabel(idx, true);
            overrideLabel.setText("Saved. Restart the Agent/Toolkit tab or reload to apply.");
            overrideLabel.setForeground(ForjaTheme.ACCENT_GREEN);
            qResetBtn.setEnabled(true);
            if (onQuickPromptsChanged != null) onQuickPromptsChanged.run();
        } catch (Exception e) {
            overrideLabel.setText("Save failed: " + e.getMessage());
            overrideLabel.setForeground(ForjaTheme.ACCENT_RED);
        }
    }

    private void resetQuickPrompts() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0) return;
        String key = keys.get(idx);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Reset to defaults? Custom changes will be lost.",
                "Reset", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        promptManager.reset(key);
        // Reload
        qRows.clear();
        List<QuickPrompt> prompts = QuickPrompt.parse(promptManager.get(key));
        for (QuickPrompt qp : prompts) {
            qRows.add(qp.isCategory() ? new QRow(true, qp.category(), "") : new QRow(false, qp.label(), qp.prompt()));
        }
        qTableModel.fireTableDataChanged();
        refreshListLabel(idx, false);
        overrideLabel.setText("Reset to defaults.");
        overrideLabel.setForeground(ForjaTheme.TEXT_MUTED);
        qResetBtn.setEnabled(false);
        if (onQuickPromptsChanged != null) onQuickPromptsChanged.run();
    }

    // ========== AI Assistant ==========

    private void aiGenerate() {
        String idea = aiInput.getText().trim();
        if (idea.isEmpty()) {
            aiStatus.setText("Describe what you want first");
            aiStatus.setForeground(ForjaTheme.ACCENT_ORANGE);
            return;
        }

        // Determine context from selected quick prompt key
        int idx = promptList.getSelectedIndex();
        if (idx < 0) return;
        String key = keys.get(idx);
        boolean isAgent = key.contains("agent");

        aiGenerateBtn.setEnabled(false);
        aiProgress.setVisible(true);
        aiStatus.setText("Generating...");
        aiStatus.setForeground(ForjaTheme.TEXT_MUTED);

        new SwingWorker<String[], Void>() {
            @Override
            protected String[] doInBackground() throws Exception {
                LLMProvider provider = providerFactory.create();

                String systemPrompt = "You are a security testing prompt engineer for Forja, a Burp Suite AI extension.\n\n"
                        + "The user will describe an idea for a quick action button. Generate a professional prompt that a pentester "
                        + "would click to execute. The prompt must be specific, actionable, and reference real security concepts.\n\n"
                        + (isAgent
                            ? "Context: This is for the AGENT tab — the prompt commands an AI agent that controls Burp Suite "
                              + "(can send to repeater/intruder, add to scope, scan, generate tools, etc).\n\n"
                            : "Context: This is for the TOOLKIT tab — the prompt tells an AI to generate a security testing "
                              + "script (JavaScript, Python, Burp extension, PoC exploit).\n\n")
                        + "Reply with EXACTLY two lines, nothing else:\n"
                        + "LABEL: <short button text, max 25 chars>\n"
                        + "PROMPT: <the full professional prompt>\n\n"
                        + "Examples:\n"
                        + "LABEL: JWT audit\n"
                        + "PROMPT: check if there are JWT tokens — analyze algorithm, expiration, and generate a jwt-manipulator tool\n\n"
                        + "LABEL: CORS exploit PoC\n"
                        + "PROMPT: PoC: Demonstrate CORS misconfiguration stealing user data via cross-origin fetch with credentials";

                LLMResponse response = provider.chat(
                        List.of(Message.system(systemPrompt), Message.user(idea)),
                        config.getModel(), 256);

                String text = response.getContent().trim();
                String label = "";
                String prompt = "";
                for (String line : text.split("\n")) {
                    line = line.trim();
                    if (line.toUpperCase().startsWith("LABEL:")) {
                        label = line.substring(6).trim();
                    } else if (line.toUpperCase().startsWith("PROMPT:")) {
                        prompt = line.substring(7).trim();
                    }
                }

                if (label.isEmpty() || prompt.isEmpty()) {
                    throw new RuntimeException("Could not parse AI response: " + text);
                }
                return new String[]{label, prompt};
            }

            @Override
            protected void done() {
                aiGenerateBtn.setEnabled(true);
                aiProgress.setVisible(false);
                try {
                    String[] result = get();
                    // Add to table
                    int sel = qTable.getSelectedRow();
                    int at = sel >= 0 ? sel + 1 : qRows.size();
                    qRows.add(at, new QRow(false, result[0], result[1]));
                    qTableModel.fireTableDataChanged();
                    qTable.setRowSelectionInterval(at, at);
                    aiStatus.setText("Added: " + result[0]);
                    aiStatus.setForeground(ForjaTheme.ACCENT_GREEN);
                    aiInput.setText("");
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    aiStatus.setText("Error: " + (msg.length() > 60 ? msg.substring(0, 60) + "..." : msg));
                    aiStatus.setForeground(ForjaTheme.ACCENT_RED);
                }
            }
        }.execute();
    }

    // ========== Helpers ==========

    private String[] showPromptInputDialog(String title, String label, String prompt) {
        JTextField labelField = new JTextField(label, 30);
        JTextArea promptField = new JTextArea(prompt, 3, 40);
        promptField.setLineWrap(true);
        promptField.setWrapStyleWord(true);
        ForjaTheme.styleTextField(labelField);
        ForjaTheme.styleTextArea(promptField);
        promptField.setEditable(true);

        JPanel p = new JPanel(new GridBagLayout());
        p.setPreferredSize(new Dimension(500, 160));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.NORTHWEST;

        gbc.gridx = 0; gbc.gridy = 0;
        JLabel l1 = new JLabel("Label (button text):");
        l1.setForeground(ForjaTheme.TEXT_LABEL);
        p.add(l1, gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1;
        p.add(labelField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel l2 = new JLabel("Prompt:");
        l2.setForeground(ForjaTheme.TEXT_LABEL);
        p.add(l2, gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1; gbc.weighty = 1;
        JScrollPane ps = new JScrollPane(promptField);
        ps.setBorder(BorderFactory.createLineBorder(ForjaTheme.BORDER_COLOR));
        p.add(ps, gbc);

        int result = JOptionPane.showConfirmDialog(this, p, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            String l = labelField.getText().trim();
            String pr = promptField.getText().trim();
            if (!l.isEmpty() && !pr.isEmpty()) return new String[]{l, pr};
        }
        return null;
    }

    private String serializeQRows() {
        StringBuilder sb = new StringBuilder();
        for (QRow r : qRows) {
            if (r.isCategory) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("# ").append(r.label).append("\n");
            } else {
                sb.append(r.label).append(" | ").append(r.prompt).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildListLabel(String key, PromptManager.PromptInfo info) {
        String prefix;
        if ("global_rules".equals(key)) {
            prefix = "\u26A1 "; // ⚡
        } else if (info.isQuickPrompts()) {
            prefix = "\u2630 "; // ☰
        } else {
            prefix = "\u2709 "; // ✉
        }
        if (promptManager.hasOverride(key)) prefix = "[*] " + prefix;
        return prefix + info.getDisplayName();
    }

    private void refreshListLabel(int idx, boolean overridden) {
        String key = keys.get(idx);
        PromptManager.PromptInfo info = promptManager.getRegistry().get(key);
        listModel.set(idx, buildListLabel(key, info));
    }

    private JPanel titled(String title, JComponent content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private JButton qButton(String text, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(ForjaTheme.FONT_UI_SMALL);
        btn.setForeground(fg);
        btn.setBackground(ForjaTheme.BG_INPUT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(true);
        btn.setBorder(BorderFactory.createLineBorder(ForjaTheme.BORDER_COLOR));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        btn.setMargin(new Insets(3, 8, 3, 8));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setBackground(new Color(55, 55, 60)); }
            @Override public void mouseExited(MouseEvent e) { btn.setBackground(ForjaTheme.BG_INPUT); }
        });
        return btn;
    }

    private void styleQuickTable(JTable t) {
        t.setBackground(ForjaTheme.BG_TABLE);
        t.setForeground(ForjaTheme.TEXT_DEFAULT);
        t.setSelectionBackground(ForjaTheme.BG_SELECTION);
        t.setSelectionForeground(ForjaTheme.TEXT_BRIGHT);
        t.setGridColor(ForjaTheme.BORDER_COLOR);
        t.setFont(ForjaTheme.FONT_UI_SMALL);
        t.setRowHeight(26);
        t.setShowHorizontalLines(true);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(table, value, sel, focus, row, col);
                if (row < qRows.size() && qRows.get(row).isCategory) {
                    c.setFont(ForjaTheme.FONT_UI_BOLD);
                    if (!sel) {
                        c.setBackground(new Color(40, 50, 40));
                        c.setForeground(ForjaTheme.ACCENT_GREEN);
                    }
                    setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
                } else {
                    c.setFont(ForjaTheme.FONT_UI_SMALL);
                    if (!sel) {
                        c.setBackground(row % 2 == 0 ? ForjaTheme.BG_TABLE : ForjaTheme.BG_TABLE_ALT);
                        c.setForeground(ForjaTheme.TEXT_DEFAULT);
                    }
                    setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 4));
                }
                return c;
            }
        });

        JTableHeader header = t.getTableHeader();
        header.setBackground(ForjaTheme.BG_TOOLBAR);
        header.setForeground(ForjaTheme.TEXT_LABEL);
        header.setFont(ForjaTheme.FONT_TITLE);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ForjaTheme.BORDER_COLOR));
        header.setReorderingAllowed(false);
    }

    // ========== Custom list renderer ==========

    private class PromptListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean sel, boolean focus) {
            JLabel c = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);

            if (index >= 0 && index < keys.size() && "global_rules".equals(keys.get(index))) {
                // Global Rules: prominent — orange, bold, taller, accent border
                c.setFont(ForjaTheme.FONT_UI_BOLD);
                c.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, ForjaTheme.BORDER_COLOR),
                        BorderFactory.createEmptyBorder(6, 6, 6, 6)));
                if (!sel) {
                    c.setBackground(new Color(45, 38, 20)); // warm dark tone
                    c.setForeground(ForjaTheme.ACCENT_ORANGE);
                }
            } else {
                c.setFont(ForjaTheme.FONT_UI_SMALL);
                c.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
                if (!sel) {
                    c.setBackground(ForjaTheme.BG_SIDEBAR);
                    if (index >= 0 && index < keys.size()) {
                        PromptManager.PromptInfo info = promptManager.getRegistry().get(keys.get(index));
                        if (info != null && info.isQuickPrompts()) {
                            c.setForeground(ForjaTheme.ACCENT_BLUE);
                        } else {
                            c.setForeground(ForjaTheme.TEXT_DEFAULT);
                        }
                    }
                }
            }
            return c;
        }
    }

    // ========== Data model ==========

    private static class QRow {
        boolean isCategory;
        String label;
        String prompt;

        QRow(boolean isCategory, String label, String prompt) {
            this.isCategory = isCategory;
            this.label = label;
            this.prompt = prompt;
        }
    }

    private class QRowTableModel extends AbstractTableModel {
        private final String[] COLS = {"Label", "Prompt"};
        @Override public int getRowCount() { return qRows.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int col) { return COLS[col]; }
        @Override
        public Object getValueAt(int row, int col) {
            QRow r = qRows.get(row);
            if (r.isCategory) return col == 0 ? "\u25B6 " + r.label : "";
            return col == 0 ? r.label : r.prompt;
        }
        @Override public boolean isCellEditable(int row, int col) { return false; }
    }
}
