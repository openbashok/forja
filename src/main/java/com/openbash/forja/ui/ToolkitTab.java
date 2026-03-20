package com.openbash.forja.ui;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.config.QuickPrompt;
import com.openbash.forja.integration.ScriptInjector;
import com.openbash.forja.llm.LLMProviderFactory;
import com.openbash.forja.toolkit.GeneratedTool;
import com.openbash.forja.toolkit.ToolkitGenerator;
import com.openbash.forja.traffic.AppModel;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ToolkitTab extends JPanel {

    private final AppModel appModel;
    private final ConfigManager config;
    private final LLMProviderFactory providerFactory;
    private final PromptManager promptManager;
    private final Supplier<List<Finding>> findingsSupplier;
    private final ScriptInjector scriptInjector;
    private Consumer<GeneratedTool> onToolGenerated;

    private final DefaultListModel<String> toolListModel;
    private final JList<String> toolList;
    private final JTextArea codeArea;
    private final JTextArea promptArea;
    private final JLabel statusLabel;
    private final JProgressBar progressBar;
    private final JButton injectBtn;
    private final List<GeneratedTool> tools = new ArrayList<>();
    private DefaultComboBoxModel<String> examplesModel;
    private List<String> promptValues;

    private Timer progressTimer;
    private int elapsedSeconds;

    private static final String PLACEHOLDER = "Examples:\n"
            + "  - Generate a JS script that tests IDOR on all endpoints with sequential IDs\n"
            + "  - Create a Python script to brute-force JWT secrets using the tokens found\n"
            + "  - Build a Burp extension that replays all authenticated requests without the auth header\n"
            + "  - Make a PoC that demonstrates the CORS misconfiguration with credential theft\n"
            + "  - Generate a fuzzer for all query parameters using SQLi and XSS payloads";

    public ToolkitTab(AppModel appModel, ConfigManager config, LLMProviderFactory providerFactory,
                      PromptManager promptManager, Supplier<List<Finding>> findingsSupplier, ScriptInjector scriptInjector) {
        this.appModel = appModel;
        this.config = config;
        this.providerFactory = providerFactory;
        this.promptManager = promptManager;
        this.findingsSupplier = findingsSupplier;
        this.scriptInjector = scriptInjector;

        setLayout(new BorderLayout());
        ForjaTheme.applyTo(this);

        // Toolbar
        JPanel toolbar = ForjaTheme.toolbarBorder();
        JPanel toolbarLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbarLeft.setBackground(ForjaTheme.BG_TOOLBAR);

        JButton generateAllBtn = ForjaTheme.primaryButton("Generate All Tools", ForjaTheme.ACCENT_ORANGE);
        generateAllBtn.addActionListener(e -> generateAll());

        JButton copyBtn = ForjaTheme.ghostButton("Copy Code");
        copyBtn.addActionListener(e -> copyCode());

        JButton saveBtn = ForjaTheme.ghostButton("Save to File");
        saveBtn.addActionListener(e -> saveToFile());

        injectBtn = ForjaTheme.primaryButton("Inject in Proxy", ForjaTheme.ACCENT_GREEN);
        injectBtn.setToolTipText("Inject this JS script into all in-scope HTML responses via the proxy");
        injectBtn.addActionListener(e -> toggleInject());

        toolbarLeft.add(generateAllBtn);
        toolbarLeft.add(copyBtn);
        toolbarLeft.add(saveBtn);
        toolbarLeft.add(Box.createHorizontalStrut(8));
        toolbarLeft.add(injectBtn);

        JPanel toolbarRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        toolbarRight.setBackground(ForjaTheme.BG_TOOLBAR);
        statusLabel = ForjaTheme.statusLabel("Ready");
        progressBar = ForjaTheme.progressBar();
        toolbarRight.add(statusLabel);
        toolbarRight.add(progressBar);

        toolbar.add(toolbarLeft, BorderLayout.WEST);
        toolbar.add(toolbarRight, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);

        // Split pane: tool list | code preview
        toolListModel = new DefaultListModel<>();
        toolList = new JList<>(toolListModel);
        toolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ForjaTheme.styleList(toolList);
        toolList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showTool();
        });

        codeArea = new JTextArea();
        codeArea.setEditable(false);
        ForjaTheme.styleTextArea(codeArea);

        JScrollPane listScroll = new JScrollPane(toolList);
        ForjaTheme.styleScrollPane(listScroll);
        JScrollPane codeScroll = new JScrollPane(codeArea);
        ForjaTheme.styleScrollPane(codeScroll);

        JSplitPane hSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, codeScroll);
        ForjaTheme.styleSplitPane(hSplit);
        hSplit.setDividerLocation(280);

        // Prompt panel at bottom (resizable via vertical split)
        JPanel promptPanel = new JPanel(new BorderLayout(4, 4));
        promptPanel.setBackground(ForjaTheme.BG_DARK);
        promptPanel.setBorder(ForjaTheme.sectionBorder("Generate from Prompt — describe the tool you need"));

        promptArea = new JTextArea(5, 60);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        ForjaTheme.styleTextArea(promptArea);
        promptArea.setEditable(true);

        // Placeholder text
        promptArea.setText(PLACEHOLDER);
        promptArea.setForeground(ForjaTheme.TEXT_MUTED);
        promptArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (promptArea.getText().equals(PLACEHOLDER)) {
                    promptArea.setText("");
                    promptArea.setForeground(ForjaTheme.TEXT_DEFAULT);
                }
            }
            @Override
            public void focusLost(FocusEvent e) {
                if (promptArea.getText().trim().isEmpty()) {
                    promptArea.setText(PLACEHOLDER);
                    promptArea.setForeground(ForjaTheme.TEXT_MUTED);
                }
            }
        });

        // Quick-picks dropdown (loaded from PromptManager, editable in Prompts tab)
        examplesModel = new DefaultComboBoxModel<>();
        promptValues = new ArrayList<>();
        loadQuickPicks();
        JComboBox<String> examplesCombo = new JComboBox<>(examplesModel);
        ForjaTheme.styleComboBox(examplesCombo);
        examplesCombo.addActionListener(e -> {
            int idx = examplesCombo.getSelectedIndex();
            if (idx > 0 && idx < promptValues.size()) {
                promptArea.setText(promptValues.get(idx));
                promptArea.setForeground(ForjaTheme.TEXT_DEFAULT);
                promptArea.requestFocus();
                examplesCombo.setSelectedIndex(0);
            }
        });

        JButton promptGenBtn = ForjaTheme.primaryButton("Generate", ForjaTheme.ACCENT_BLUE);
        promptGenBtn.addActionListener(e -> generateFromPrompt());

        // Ctrl+Enter to submit
        promptArea.getInputMap().put(
                KeyStroke.getKeyStroke("control ENTER"), "submitPrompt");
        promptArea.getActionMap().put("submitPrompt", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                generateFromPrompt();
            }
        });

        JPanel promptTopPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        promptTopPanel.setBackground(ForjaTheme.BG_DARK);
        promptTopPanel.add(ForjaTheme.label("Quick pick:"));
        promptTopPanel.add(examplesCombo);
        promptPanel.add(promptTopPanel, BorderLayout.NORTH);

        JScrollPane promptScroll = new JScrollPane(promptArea);
        ForjaTheme.styleScrollPane(promptScroll);
        promptPanel.add(promptScroll, BorderLayout.CENTER);

        JPanel promptBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        promptBtnPanel.setBackground(ForjaTheme.BG_DARK);
        JLabel hint = ForjaTheme.statusLabel("Ctrl+Enter to send");
        promptBtnPanel.add(hint);
        promptBtnPanel.add(promptGenBtn);
        promptPanel.add(promptBtnPanel, BorderLayout.SOUTH);

        // Vertical split: code area (top) | prompt (bottom) — resizable
        JSplitPane vSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, hSplit, promptPanel);
        ForjaTheme.styleSplitPane(vSplit);
        vSplit.setResizeWeight(0.75); // code area gets 75% by default

        // Deferred divider: set prompt to ~180px from bottom after shown
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    int h = getHeight();
                    if (h > 0) {
                        vSplit.setDividerLocation(h - 200);
                    }
                });
            }
        });

        add(vSplit, BorderLayout.CENTER);
    }

    // --- Progress indicator ---

    private void startProgress(String message) {
        elapsedSeconds = 0;
        statusLabel.setText(message + " (0s)");
        progressBar.setVisible(true);
        progressTimer = new Timer(1000, e -> {
            elapsedSeconds++;
            statusLabel.setText(message + " (" + elapsedSeconds + "s)");
        });
        progressTimer.start();
    }

    private void stopProgress(String message) {
        if (progressTimer != null) {
            progressTimer.stop();
            progressTimer = null;
        }
        progressBar.setVisible(false);
        statusLabel.setText(message + " (" + elapsedSeconds + "s)");
    }

    // --- Generation ---

    private void generateFromPrompt() {
        String prompt = promptArea.getText().trim();
        if (prompt.isEmpty() || prompt.startsWith("Examples:\n")) {
            statusLabel.setText("Write what you need in the prompt field, or pick a quick example.");
            return;
        }

        List<Finding> findings = findingsSupplier.get();
        startProgress("Generating from prompt...");

        new SwingWorker<GeneratedTool, Void>() {
            @Override
            protected GeneratedTool doInBackground() throws Exception {
                ToolkitGenerator gen = new ToolkitGenerator(providerFactory.create(), config, promptManager);
                return gen.generateFromPrompt(appModel, findings, prompt);
            }

            @Override
            protected void done() {
                try {
                    GeneratedTool tool = get();
                    addTool(tool);
                    stopProgress("Tool generated from prompt");
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    stopProgress("Generation failed: " + msg);
                }
            }
        }.execute();
    }

    private void generateAll() {
        List<Finding> findings = findingsSupplier.get();
        if (appModel.getEndpointCount() == 0) {
            statusLabel.setText("No traffic captured. Browse the target first.");
            return;
        }

        startProgress("Generating tools...");
        new SwingWorker<List<GeneratedTool>, Void>() {
            @Override
            protected List<GeneratedTool> doInBackground() throws Exception {
                ToolkitGenerator gen = new ToolkitGenerator(providerFactory.create(), config, promptManager);
                return gen.generateAll(appModel, findings);
            }

            @Override
            protected void done() {
                try {
                    List<GeneratedTool> generated = get();
                    for (GeneratedTool t : generated) {
                        addTool(t);
                    }
                    stopProgress("Generated " + generated.size() + " tool(s)");
                    if (!tools.isEmpty()) {
                        toolList.setSelectedIndex(tools.size() - 1);
                    }
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    stopProgress("Generation failed: " + msg);
                }
            }
        }.execute();
    }

    /**
     * Set a callback to be notified when a tool is generated.
     * Used to sync generated tools with the Agent tab.
     */
    public void setOnToolGenerated(Consumer<GeneratedTool> callback) {
        this.onToolGenerated = callback;
    }

    /** Reload the quick-picks dropdown from PromptManager (called when quick prompts are edited). */
    public void reloadPrompts() {
        promptManager.clearCache();
        loadQuickPicks();
    }

    private void loadQuickPicks() {
        examplesModel.removeAllElements();
        promptValues.clear();
        examplesModel.addElement("-- Quick examples --");
        promptValues.add(null);
        List<QuickPrompt> quickPrompts = QuickPrompt.parse(promptManager.get("toolkit_quick_prompts"));
        String lastCategory = null;
        for (QuickPrompt qp : quickPrompts) {
            if (qp.isCategory()) {
                lastCategory = qp.category();
            } else {
                String display = lastCategory != null ? "[" + lastCategory + "] " + qp.label() : qp.label();
                examplesModel.addElement(display);
                promptValues.add(qp.prompt());
            }
        }
    }

    /**
     * Add a tool to the list without clearing existing ones.
     */
    private void addTool(GeneratedTool tool) {
        tools.add(tool);
        String label = "[" + tool.getType().getDisplayName() + "] " + tool.getName();
        if (!tool.getDescription().isEmpty() && !tool.getDescription().equals(tool.getName())) {
            label += " — " + truncate(tool.getDescription(), 60);
        }
        toolListModel.addElement(label);
        toolList.setSelectedIndex(tools.size() - 1);
        autoSaveToOutputDir(tool);

        // Notify listener (syncs with Agent tab)
        if (onToolGenerated != null) {
            onToolGenerated.accept(tool);
        }
    }

    private void autoSaveToOutputDir(GeneratedTool tool) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(config.getOutputDir());
            Files.createDirectories(dir);
            String ext = switch (tool.getLanguage()) {
                case "python" -> ".py";
                case "javascript" -> ".js";
                case "java" -> ".java";
                default -> "." + tool.getLanguage();
            };
            String filename = tool.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + ext;
            Files.writeString(dir.resolve(filename), tool.getCode());
        } catch (IOException ignored) {}
    }

    private void toggleInject() {
        int idx = toolList.getSelectedIndex();
        if (idx < 0 || idx >= tools.size()) return;
        GeneratedTool tool = tools.get(idx);

        if (!"javascript".equals(tool.getLanguage())) {
            statusLabel.setText("Injection only works with JavaScript scripts.");
            return;
        }

        String name = tool.getName() + " #" + idx;
        if (scriptInjector.isActive(name)) {
            scriptInjector.deactivate(name);
            statusLabel.setText("Stopped injecting '" + tool.getName() + "'. Active: " + scriptInjector.activeCount());
        } else {
            scriptInjector.activate(name, tool.getCode());
            statusLabel.setText("Injecting '" + tool.getName() + "' into proxy responses. Active: " + scriptInjector.activeCount());
        }
        updateInjectButton();
        refreshListLabels();
    }

    private void updateInjectButton() {
        int idx = toolList.getSelectedIndex();
        if (idx < 0 || idx >= tools.size()) {
            injectBtn.setText("Inject in Proxy");
            injectBtn.setEnabled(false);
            return;
        }
        GeneratedTool tool = tools.get(idx);
        boolean isJs = "javascript".equals(tool.getLanguage());
        injectBtn.setEnabled(isJs);

        if (isJs) {
            String name = tool.getName() + " #" + idx;
            if (scriptInjector.isActive(name)) {
                injectBtn.setText("Stop Injecting");
                injectBtn.setBackground(ForjaTheme.ACCENT_RED);
            } else {
                injectBtn.setText("Inject in Proxy");
                injectBtn.setBackground(ForjaTheme.ACCENT_GREEN);
            }
        } else {
            injectBtn.setText("Inject (JS only)");
        }
    }

    private void refreshListLabels() {
        for (int i = 0; i < tools.size(); i++) {
            GeneratedTool t = tools.get(i);
            String name = t.getName() + " #" + i;
            String prefix = scriptInjector.isActive(name) ? "[INJECTING] " : "";
            String label = prefix + "[" + t.getType().getDisplayName() + "] " + t.getName();
            if (!t.getDescription().isEmpty() && !t.getDescription().equals(t.getName())) {
                label += " — " + truncate(t.getDescription(), 50);
            }
            toolListModel.set(i, label);
        }
    }

    private void showTool() {
        int idx = toolList.getSelectedIndex();
        if (idx < 0 || idx >= tools.size()) return;
        GeneratedTool tool = tools.get(idx);

        String commentPrefix = "python".equals(tool.getLanguage()) ? "# " : "// ";
        StringBuilder header = new StringBuilder();
        header.append(commentPrefix).append(tool.getName()).append("\n");
        header.append(commentPrefix).append(tool.getDescription()).append("\n");
        header.append(commentPrefix).append("Language: ").append(tool.getLanguage()).append("\n");
        header.append(commentPrefix).append("Generated: ").append(tool.getGeneratedAt()).append("\n");

        String name = tool.getName() + " #" + idx;
        if ("javascript".equals(tool.getLanguage()) && scriptInjector.isActive(name)) {
            header.append(commentPrefix).append("STATUS: INJECTING into proxy responses\n");
        }
        header.append("\n");

        codeArea.setText(header + tool.getCode());
        codeArea.setCaretPosition(0);
        updateInjectButton();
    }

    private void copyCode() {
        String code = codeArea.getText();
        if (!code.isEmpty()) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(code), null);
            statusLabel.setText("Copied to clipboard.");
        }
    }

    private void saveToFile() {
        int idx = toolList.getSelectedIndex();
        if (idx < 0 || idx >= tools.size()) return;
        GeneratedTool tool = tools.get(idx);

        JFileChooser fc = new JFileChooser();
        String ext = switch (tool.getLanguage()) {
            case "python" -> ".py";
            case "java" -> ".java";
            case "html" -> ".html";
            case "bash" -> ".sh";
            default -> ".js";
        };
        fc.setSelectedFile(new File(tool.getName().replaceAll("\\s+", "_") + ext));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(fc.getSelectedFile().toPath(), tool.getCode());
                statusLabel.setText("Saved to " + fc.getSelectedFile().getName());
            } catch (IOException e) {
                statusLabel.setText("Save failed: " + e.getMessage());
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
