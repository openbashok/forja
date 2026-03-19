package com.openbash.forja.ui;

import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.llm.LLMProvider;
import com.openbash.forja.llm.LLMProviderFactory;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class ConfigTab extends JPanel {

    private final ConfigManager config;
    private final LLMProviderFactory providerFactory;

    private final JComboBox<String> providerCombo;
    private final JPasswordField apiKeyField;
    private final JComboBox<String> modelCombo;
    private final JTextField budgetField;
    private final JTextField maxTokensField;
    private final JTextField customEndpointField;
    private final JLabel customEndpointLabel;
    private final JLabel statusLabel;

    public ConfigTab(ConfigManager config, LLMProviderFactory providerFactory) {
        this.config = config;
        this.providerFactory = providerFactory;

        setLayout(new BorderLayout(UIConstants.PAD, UIConstants.PAD));
        setBorder(BorderFactory.createEmptyBorder(UIConstants.PAD, UIConstants.PAD, UIConstants.PAD, UIConstants.PAD));

        // Form panel
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(UIConstants.SMALL_PAD, UIConstants.SMALL_PAD, UIConstants.SMALL_PAD, UIConstants.SMALL_PAD);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // Provider
        providerCombo = new JComboBox<>(new String[]{"Anthropic", "OpenAI", "Custom"});
        providerCombo.setSelectedItem(config.getProvider());
        providerCombo.addActionListener(e -> onProviderChanged());
        addFormRow(form, gbc, row++, "Provider:", providerCombo);

        // API Key
        apiKeyField = new JPasswordField(40);
        apiKeyField.setText(config.getApiKey());
        addFormRow(form, gbc, row++, "API Key:", apiKeyField);

        // Model
        modelCombo = new JComboBox<>();
        addFormRow(form, gbc, row++, "Model:", modelCombo);

        // Budget
        budgetField = new JTextField(String.valueOf(config.getBudget()), 10);
        addFormRow(form, gbc, row++, "Budget (USD):", budgetField);

        // Max Generation Tokens
        maxTokensField = new JTextField(String.valueOf(config.getMaxGenerationTokens()), 10);
        maxTokensField.setToolTipText("Max output tokens for generated tools (default 16384). Increase if scripts are truncated. Claude supports up to 128000, OpenAI up to 16384.");
        addFormRow(form, gbc, row++, "Max Generation Tokens:", maxTokensField);

        // Custom endpoint
        customEndpointLabel = new JLabel("Custom Endpoint:");
        customEndpointField = new JTextField(config.getCustomEndpoint(), 40);
        addFormRow(form, gbc, row++, customEndpointLabel, customEndpointField);

        // Warning
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        JLabel warning = new JLabel("Note: API key is stored on disk via Burp preferences.");
        warning.setFont(warning.getFont().deriveFont(Font.ITALIC, 11f));
        form.add(warning, gbc);
        gbc.gridwidth = 1;

        add(form, BorderLayout.NORTH);

        // Button panel
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton testBtn = new JButton("Test Connection");
        testBtn.addActionListener(e -> testConnection());
        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> save());
        statusLabel = new JLabel(" ");
        buttons.add(testBtn);
        buttons.add(saveBtn);
        buttons.add(statusLabel);
        add(buttons, BorderLayout.CENTER);

        onProviderChanged();
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, JComponent field) {
        addFormRow(panel, gbc, row, new JLabel(label), field);
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, JComponent label, JComponent field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(label, gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        panel.add(field, gbc);
    }

    private void onProviderChanged() {
        String provider = (String) providerCombo.getSelectedItem();
        boolean isCustom = "Custom".equals(provider);
        customEndpointLabel.setVisible(isCustom);
        customEndpointField.setVisible(isCustom);

        modelCombo.removeAllItems();
        List<String> models = getModelsForProvider(provider);
        for (String m : models) {
            modelCombo.addItem(m);
        }

        String savedModel = config.getModel();
        if (models.contains(savedModel)) {
            modelCombo.setSelectedItem(savedModel);
        }
    }

    private List<String> getModelsForProvider(String provider) {
        return switch (provider) {
            case "Anthropic" -> List.of("claude-sonnet-4-20250514", "claude-haiku-4-20250414", "claude-opus-4-20250514");
            case "OpenAI" -> List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini");
            case "Custom" -> List.of("custom-model");
            default -> List.of();
        };
    }

    private void save() {
        config.setProvider((String) providerCombo.getSelectedItem());
        config.setApiKey(new String(apiKeyField.getPassword()));
        config.setModel((String) modelCombo.getSelectedItem());
        config.setCustomEndpoint(customEndpointField.getText().trim());
        try {
            config.setBudget(Double.parseDouble(budgetField.getText().trim()));
        } catch (NumberFormatException e) {
            config.setBudget(1.0);
        }
        try {
            int tokens = Integer.parseInt(maxTokensField.getText().trim());
            config.setMaxGenerationTokens(Math.max(1024, tokens));
        } catch (NumberFormatException e) {
            config.setMaxGenerationTokens(16384);
        }
        statusLabel.setText("Configuration saved.");
    }

    private void testConnection() {
        save();
        statusLabel.setText("Testing connection...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                LLMProvider provider = providerFactory.create();
                return provider.testConnection();
            }

            @Override
            protected void done() {
                try {
                    statusLabel.setText(get());
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    statusLabel.setText("Error: " + msg);
                }
            }
        }.execute();
    }
}
