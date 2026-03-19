package com.openbash.forja.ui;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.config.ConfigManager;
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
import java.util.function.Supplier;

public class ToolkitTab extends JPanel {

    private final AppModel appModel;
    private final ConfigManager config;
    private final LLMProviderFactory providerFactory;
    private final Supplier<List<Finding>> findingsSupplier;

    private final DefaultListModel<String> toolListModel;
    private final JList<String> toolList;
    private final JTextArea codeArea;
    private final JTextArea promptArea;
    private final JLabel statusLabel;
    private final List<GeneratedTool> tools = new ArrayList<>();

    public ToolkitTab(AppModel appModel, ConfigManager config, LLMProviderFactory providerFactory,
                      Supplier<List<Finding>> findingsSupplier) {
        this.appModel = appModel;
        this.config = config;
        this.providerFactory = providerFactory;
        this.findingsSupplier = findingsSupplier;

        setLayout(new BorderLayout());

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton generateAllBtn = new JButton("Generate All Tools");
        generateAllBtn.addActionListener(e -> generateAll());
        JButton copyBtn = new JButton("Copy Code");
        copyBtn.addActionListener(e -> copyCode());
        JButton saveBtn = new JButton("Save to File");
        saveBtn.addActionListener(e -> saveToFile());
        statusLabel = new JLabel("Ready");

        toolbar.add(generateAllBtn);
        toolbar.add(copyBtn);
        toolbar.add(saveBtn);
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(statusLabel);
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
        String placeholder = "Examples:\n"
                + "  - Generate a JS script that tests IDOR on all endpoints with sequential IDs\n"
                + "  - Create a Python script to brute-force JWT secrets using the tokens found\n"
                + "  - Build a Burp extension that replays all authenticated requests without the auth header\n"
                + "  - Make a PoC that demonstrates the CORS misconfiguration with credential theft\n"
                + "  - Generate a fuzzer for all query parameters using SQLi and XSS payloads";
        promptArea.setText(placeholder);
        promptArea.setForeground(UIManager.getColor("textInactiveText"));
        promptArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (promptArea.getText().equals(placeholder)) {
                    promptArea.setText("");
                    promptArea.setForeground(UIConstants.textForeground());
                }
            }
            @Override
            public void focusLost(FocusEvent e) {
                if (promptArea.getText().trim().isEmpty()) {
                    promptArea.setText(placeholder);
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

    private void generateFromPrompt() {
        String prompt = promptArea.getText().trim();
        if (prompt.isEmpty() || prompt.startsWith("Examples:\n")) {
            statusLabel.setText("Write what you need in the prompt field, or pick a quick example.");
            return;
        }

        List<Finding> findings = findingsSupplier.get();
        statusLabel.setText("Generating from prompt...");

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
                    tools.add(tool);
                    toolListModel.addElement("[" + tool.getType().getDisplayName() + "] " + tool.getDescription());
                    toolList.setSelectedIndex(tools.size() - 1);
                    statusLabel.setText("Tool generated from prompt.");
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    statusLabel.setText("Generation failed: " + msg);
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

        statusLabel.setText("Generating tools...");
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
                    tools.clear();
                    tools.addAll(generated);
                    toolListModel.clear();
                    for (GeneratedTool t : tools) {
                        toolListModel.addElement("[" + t.getType().getDisplayName() + "] " + t.getName());
                    }
                    statusLabel.setText("Generated " + tools.size() + " tool(s).");
                    if (!tools.isEmpty()) {
                        toolList.setSelectedIndex(0);
                    }
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    statusLabel.setText("Generation failed: " + msg);
                }
            }
        }.execute();
    }

    private void showTool() {
        int idx = toolList.getSelectedIndex();
        if (idx < 0 || idx >= tools.size()) return;
        GeneratedTool tool = tools.get(idx);
        codeArea.setText("// " + tool.getName() + "\n// " + tool.getDescription()
                + "\n// Language: " + tool.getLanguage()
                + "\n// Generated: " + tool.getGeneratedAt()
                + "\n\n" + tool.getCode());
        codeArea.setCaretPosition(0);
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
}
