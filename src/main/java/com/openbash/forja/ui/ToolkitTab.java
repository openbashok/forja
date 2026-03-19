package com.openbash.forja.ui;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
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

    private Timer progressTimer;
    private int elapsedSeconds;

    private static final String PLACEHOLDER = "Examples:\n"
            + "  - Generate a JS script that tests IDOR on all endpoints with sequential IDs\n"
            + "  - Create a Python script to brute-force JWT secrets using the tokens found\n"
            + "  - Build a Burp extension that replays all authenticated requests without the auth header\n"
            + "  - Make a PoC that demonstrates the CORS misconfiguration with credential theft\n"
            + "  - Generate a fuzzer for all query parameters using SQLi and XSS payloads";

    public ToolkitTab(AppModel appModel, ConfigManager config, LLMProviderFactory providerFactory,
                      Supplier<List<Finding>> findingsSupplier, ScriptInjector scriptInjector) {
        this.appModel = appModel;
        this.config = config;
        this.providerFactory = providerFactory;
        this.findingsSupplier = findingsSupplier;
        this.scriptInjector = scriptInjector;

        setLayout(new BorderLayout());

        // Toolbar
        JPanel toolbar = new JPanel(new BorderLayout());
        JPanel toolbarButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton generateAllBtn = new JButton("Generate All Tools");
        generateAllBtn.addActionListener(e -> generateAll());
        JButton copyBtn = new JButton("Copy Code");
        copyBtn.addActionListener(e -> copyCode());
        JButton saveBtn = new JButton("Save to File");
        saveBtn.addActionListener(e -> saveToFile());
        injectBtn = new JButton("Inject in Proxy");
        injectBtn.setToolTipText("Inject this JS script into all in-scope HTML responses via the proxy");
        injectBtn.addActionListener(e -> toggleInject());

        toolbarButtons.add(generateAllBtn);
        toolbarButtons.add(copyBtn);
        toolbarButtons.add(saveBtn);
        toolbarButtons.add(Box.createHorizontalStrut(10));
        toolbarButtons.add(injectBtn);
        toolbar.add(toolbarButtons, BorderLayout.WEST);

        // Status + progress bar
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        statusLabel = new JLabel("Ready");
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(120, 18));
        progressBar.setVisible(false);
        statusPanel.add(statusLabel);
        statusPanel.add(progressBar);
        toolbar.add(statusPanel, BorderLayout.EAST);

        add(toolbar, BorderLayout.NORTH);

        // Split pane: tool list | code preview
        toolListModel = new DefaultListModel<>();
        toolList = new JList<>(toolListModel);
        toolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        toolList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showTool();
        });

        codeArea = new JTextArea();
        codeArea.setEditable(false);
        codeArea.setFont(UIConstants.MONO_FONT);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(toolList), new JScrollPane(codeArea));
        split.setDividerLocation(250);
        add(split, BorderLayout.CENTER);

        // Prompt panel at bottom
        JPanel promptPanel = new JPanel(new BorderLayout(UIConstants.SMALL_PAD, UIConstants.SMALL_PAD));
        promptPanel.setBorder(BorderFactory.createTitledBorder("Generate from Prompt — describe the tool you need"));
        promptArea = new JTextArea(3, 60);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setFont(UIConstants.MONO_FONT);

        // Placeholder text
        promptArea.setText(PLACEHOLDER);
        promptArea.setForeground(UIManager.getColor("textInactiveText"));
        promptArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (promptArea.getText().equals(PLACEHOLDER)) {
                    promptArea.setText("");
                    promptArea.setForeground(UIConstants.textForeground());
                }
            }
            @Override
            public void focusLost(FocusEvent e) {
                if (promptArea.getText().trim().isEmpty()) {
                    promptArea.setText(PLACEHOLDER);
                    promptArea.setForeground(UIManager.getColor("textInactiveText"));
                }
            }
        });

        // Example quick-picks dropdown
        String[] examples = {
                "-- Quick examples --",
                "JS: Test IDOR on all endpoints with sequential IDs",
                "JS: Replay authenticated requests and compare responses without auth",
                "JS: Extract and decode all JWT tokens, check for weak algorithms",
                "Python: Brute-force JWT secret using common wordlist",
                "Python: Test all endpoints for rate limiting",
                "Burp Extension: Auto-replace auth tokens to test privilege escalation",
                "Burp Extension: Log all endpoints that return sensitive data patterns (SSN, email, credit card)",
                "PoC: Demonstrate CORS misconfiguration stealing user data",
                "PoC: CSRF attack on state-changing endpoints that lack anti-CSRF tokens"
        };
        JComboBox<String> examplesCombo = new JComboBox<>(examples);
        examplesCombo.addActionListener(e -> {
            int idx = examplesCombo.getSelectedIndex();
            if (idx > 0) {
                promptArea.setText((String) examplesCombo.getSelectedItem());
                promptArea.setForeground(UIConstants.textForeground());
                promptArea.requestFocus();
                examplesCombo.setSelectedIndex(0);
            }
        });

        JButton promptGenBtn = new JButton("Generate");
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

        JPanel promptTopPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        promptTopPanel.add(new JLabel("Quick pick:"));
        promptTopPanel.add(examplesCombo);
        promptPanel.add(promptTopPanel, BorderLayout.NORTH);
        promptPanel.add(new JScrollPane(promptArea), BorderLayout.CENTER);
        JPanel promptBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        promptBtnPanel.add(new JLabel("Ctrl+Enter to send"));
        promptBtnPanel.add(promptGenBtn);
        promptPanel.add(promptBtnPanel, BorderLayout.SOUTH);
        add(promptPanel, BorderLayout.SOUTH);
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
                ToolkitGenerator gen = new ToolkitGenerator(providerFactory.create(), config);
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
                ToolkitGenerator gen = new ToolkitGenerator(providerFactory.create(), config);
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
            } else {
                injectBtn.setText("Inject in Proxy");
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
