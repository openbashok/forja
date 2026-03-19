package com.openbash.forja.ui;

import com.openbash.forja.config.PromptManager;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/**
 * UI tab for viewing and editing system prompts at runtime.
 */
public class PromptsTab extends JPanel {

    private final PromptManager promptManager;
    private final DefaultListModel<String> listModel;
    private final JList<String> promptList;
    private final JTextArea editor;
    private final JLabel infoLabel;
    private final JLabel overrideLabel;
    private final JButton saveBtn;
    private final JButton resetBtn;

    // Map display index → prompt key
    private final java.util.List<String> keys = new java.util.ArrayList<>();

    public PromptsTab(PromptManager promptManager) {
        this.promptManager = promptManager;
        setLayout(new BorderLayout());
        ForjaTheme.applyTo(this);

        // Left: prompt list
        listModel = new DefaultListModel<>();
        Map<String, PromptManager.PromptInfo> registry = promptManager.getRegistry();
        for (var entry : registry.entrySet()) {
            keys.add(entry.getKey());
            String label = entry.getValue().getDisplayName();
            if (promptManager.hasOverride(entry.getKey())) {
                label = "[*] " + label;
            }
            listModel.addElement(label);
        }

        promptList = new JList<>(listModel);
        promptList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ForjaTheme.styleList(promptList);
        promptList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelected();
        });

        JScrollPane listScroll = new JScrollPane(promptList);
        ForjaTheme.styleScrollPane(listScroll);
        listScroll.setPreferredSize(new Dimension(250, 0));

        // Right: editor
        JPanel editorPanel = new JPanel(new BorderLayout());
        editorPanel.setBackground(ForjaTheme.BG_DARK);

        // Info bar
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

        editorPanel.add(infoBar, BorderLayout.NORTH);

        // Text editor
        editor = new JTextArea();
        editor.setEditable(true);
        ForjaTheme.styleTextArea(editor);
        editor.setFont(ForjaTheme.FONT_MONO);
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setTabSize(2);

        JScrollPane editorScroll = new JScrollPane(editor);
        ForjaTheme.styleScrollPane(editorScroll);
        editorPanel.add(editorScroll, BorderLayout.CENTER);

        // Bottom toolbar
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(ForjaTheme.BG_TOOLBAR);
        bottomBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ForjaTheme.BORDER_COLOR));

        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        leftButtons.setBackground(ForjaTheme.BG_TOOLBAR);

        saveBtn = ForjaTheme.primaryButton("Save Override", ForjaTheme.ACCENT_GREEN);
        saveBtn.addActionListener(e -> savePrompt());
        saveBtn.setEnabled(false);

        resetBtn = ForjaTheme.ghostButton("Reset to Default");
        resetBtn.addActionListener(e -> resetPrompt());
        resetBtn.setEnabled(false);

        JButton diffBtn = ForjaTheme.ghostButton("Show Diff");
        diffBtn.addActionListener(e -> showDiff());

        leftButtons.add(saveBtn);
        leftButtons.add(resetBtn);
        leftButtons.add(diffBtn);

        JPanel rightInfo = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        rightInfo.setBackground(ForjaTheme.BG_TOOLBAR);
        JLabel hint = ForjaTheme.statusLabel("Overrides saved to: outputDir/prompts/");
        rightInfo.add(hint);

        bottomBar.add(leftButtons, BorderLayout.WEST);
        bottomBar.add(rightInfo, BorderLayout.EAST);
        editorPanel.add(bottomBar, BorderLayout.SOUTH);

        // Main split
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, editorPanel);
        ForjaTheme.styleSplitPane(split);
        split.setDividerLocation(250);
        add(split, BorderLayout.CENTER);
    }

    private void loadSelected() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0 || idx >= keys.size()) return;

        String key = keys.get(idx);
        PromptManager.PromptInfo info = promptManager.getRegistry().get(key);

        String content = promptManager.get(key);
        editor.setText(content);
        editor.setCaretPosition(0);

        infoLabel.setText(info.getDisplayName());
        boolean hasOverride = promptManager.hasOverride(key);
        if (hasOverride) {
            overrideLabel.setText(info.getDescription() + "  —  OVERRIDDEN (custom version active)");
            overrideLabel.setForeground(ForjaTheme.ACCENT_ORANGE);
        } else {
            overrideLabel.setText(info.getDescription() + "  —  Using bundled default");
            overrideLabel.setForeground(ForjaTheme.TEXT_MUTED);
        }

        saveBtn.setEnabled(true);
        resetBtn.setEnabled(hasOverride);
    }

    private void savePrompt() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0 || idx >= keys.size()) return;

        String key = keys.get(idx);
        String content = editor.getText();

        try {
            promptManager.save(key, content);
            refreshListLabel(idx, true);
            overrideLabel.setText("Saved. Changes take effect on next LLM call.");
            overrideLabel.setForeground(ForjaTheme.ACCENT_GREEN);
            resetBtn.setEnabled(true);
        } catch (Exception e) {
            overrideLabel.setText("Save failed: " + e.getMessage());
            overrideLabel.setForeground(ForjaTheme.ACCENT_RED);
        }
    }

    private void resetPrompt() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0 || idx >= keys.size()) return;

        String key = keys.get(idx);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Reset to bundled default? Your custom version will be deleted.",
                "Reset Prompt", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        promptManager.reset(key);
        editor.setText(promptManager.get(key));
        editor.setCaretPosition(0);
        refreshListLabel(idx, false);
        overrideLabel.setText("Reset to default.");
        overrideLabel.setForeground(ForjaTheme.TEXT_MUTED);
        resetBtn.setEnabled(false);
    }

    private void showDiff() {
        int idx = promptList.getSelectedIndex();
        if (idx < 0 || idx >= keys.size()) return;

        String key = keys.get(idx);
        String current = editor.getText();
        String bundled = promptManager.getDefault(key);

        if (current.equals(bundled)) {
            JOptionPane.showMessageDialog(this, "No differences — content matches the bundled default.",
                    "Diff", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Simple diff: show both side by side
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

    private JPanel titled(String title, JComponent content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private void refreshListLabel(int idx, boolean overridden) {
        String key = keys.get(idx);
        PromptManager.PromptInfo info = promptManager.getRegistry().get(key);
        String label = overridden ? "[*] " + info.getDisplayName() : info.getDisplayName();
        listModel.set(idx, label);
    }
}
