package com.openbash.forja.ui;

import com.openbash.forja.agent.AgentAction;
import com.openbash.forja.agent.AgentResponse;
import com.openbash.forja.agent.BurpAgent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.List;

public class AgentTab extends JPanel {

    private final BurpAgent agent;
    private final JTextArea chatArea;
    private final JTextField inputField;
    private final JButton sendBtn;
    private final JLabel statusLabel;
    private final JProgressBar progressBar;

    private Timer progressTimer;
    private int elapsedSeconds;

    public AgentTab(BurpAgent agent) {
        this.agent = agent;
        setLayout(new BorderLayout());

        // --- Toolbar (NORTH) ---
        JPanel toolbar = new JPanel(new BorderLayout());
        JPanel toolbarLeft = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton clearBtn = new JButton("Clear Chat");
        clearBtn.addActionListener(e -> clearChat());
        toolbarLeft.add(clearBtn);
        toolbar.add(toolbarLeft, BorderLayout.WEST);

        JPanel toolbarRight = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        statusLabel = new JLabel("Ready");
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(120, 18));
        progressBar.setVisible(false);
        toolbarRight.add(statusLabel);
        toolbarRight.add(progressBar);
        toolbar.add(toolbarRight, BorderLayout.EAST);

        add(toolbar, BorderLayout.NORTH);

        // --- Chat area (CENTER) ---
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(UIConstants.MONO_FONT);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        add(chatScroll, BorderLayout.CENTER);

        // --- Input area (SOUTH) ---
        JPanel inputPanel = new JPanel(new BorderLayout(UIConstants.SMALL_PAD, UIConstants.SMALL_PAD));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(
                UIConstants.SMALL_PAD, UIConstants.PAD, UIConstants.PAD, UIConstants.PAD));
        inputField = new JTextField();
        inputField.setFont(UIConstants.MONO_FONT);
        sendBtn = new JButton("Send");

        inputField.addActionListener(e -> sendMessage());
        sendBtn.addActionListener(e -> sendMessage());

        // Ctrl+Enter also sends
        inputField.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "send");
        inputField.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { sendMessage(); }
        });

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendBtn, BorderLayout.EAST);
        add(inputPanel, BorderLayout.SOUTH);

        // Welcome message
        appendChat("[Agent] Hola, soy Forja Agent. Puedo controlar Burp Suite por vos.\n"
                + "  Ejemplos: \"agrega example.com al scope\", \"listame los endpoints\",\n"
                + "  \"manda un GET a /api/users al repeater\", \"que findings hay?\"\n");
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        inputField.setText("");
        inputField.setEnabled(false);
        sendBtn.setEnabled(false);
        appendChat("[You] " + text + "\n");
        startProgress("Thinking...");

        new SwingWorker<AgentResponse, Void>() {
            @Override
            protected AgentResponse doInBackground() throws Exception {
                return agent.processUserMessage(text);
            }

            @Override
            protected void done() {
                try {
                    AgentResponse response = get();
                    appendChat("[Agent] " + response.getResponse() + "\n");
                    executeActions(response.getActions());
                    stopProgress("Ready");
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    appendChat("[Agent] Error: " + msg + "\n");
                    stopProgress("Error");
                }
                inputField.setEnabled(true);
                sendBtn.setEnabled(true);
                inputField.requestFocus();
            }
        }.execute();
    }

    private void executeActions(List<AgentAction> actions) {
        for (AgentAction action : actions) {
            String result = agent.executeAction(action);
            appendChat("[Action] " + action.getTool() + " -> " + result + "\n");
        }
    }

    private void clearChat() {
        chatArea.setText("");
        agent.clearHistory();
        appendChat("[Agent] Chat cleared. How can I help?\n");
        statusLabel.setText("Ready");
    }

    private void appendChat(String text) {
        chatArea.append(text + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

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
}
