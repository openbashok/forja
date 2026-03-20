package com.openbash.forja.integration;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.openbash.forja.analysis.Finding;
import com.openbash.forja.analysis.SecurityAnalyzer;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.TrafficCollector;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ForjaContextMenu implements ContextMenuItemsProvider {

    private final MontoyaApi api;
    private final AppModel appModel;
    private final ConfigManager config;
    private final LLMProviderFactory providerFactory;
    private final PromptManager promptManager;

    public ForjaContextMenu(MontoyaApi api, AppModel appModel, ConfigManager config,
                            LLMProviderFactory providerFactory, PromptManager promptManager) {
        this.api = api;
        this.appModel = appModel;
        this.config = config;
        this.providerFactory = providerFactory;
        this.promptManager = promptManager;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<Component> items = new ArrayList<>();

        JMenuItem analyzeItem = new JMenuItem("Forja: Analyze This Request");
        analyzeItem.addActionListener(e -> analyzeRequest(event));
        items.add(analyzeItem);

        JMenuItem pocItem = new JMenuItem("Forja: Generate PoC");
        pocItem.addActionListener(e -> generatePoc(event));
        items.add(pocItem);

        JMenuItem addItem = new JMenuItem("Forja: Add to Intelligence");
        addItem.addActionListener(e -> addToIntelligence(event));
        items.add(addItem);

        return items;
    }

    private void analyzeRequest(ContextMenuEvent event) {
        List<HttpRequestResponse> selected = event.selectedRequestResponses();
        if (selected.isEmpty()) return;

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                HttpRequestResponse reqRes = selected.get(0);
                String requestStr = reqRes.request().toString();
                String responseStr = reqRes.response() != null ? reqRes.response().toString() : "(no response)";

                LLMProvider provider = providerFactory.create();
                String prompt = "Analyze this single HTTP request/response for security issues:\n\n"
                        + "REQUEST:\n" + truncate(requestStr, 3000)
                        + "\n\nRESPONSE:\n" + truncate(responseStr, 3000);

                LLMResponse response = provider.chat(
                        List.of(Message.system("You are a web security analyst. Analyze the request for vulnerabilities.\n\n" + promptManager.get("global_rules")),
                                Message.user(prompt)),
                        config.getModel(), 2048
                );
                return response.getContent();
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    JTextArea textArea = new JTextArea(result);
                    textArea.setEditable(false);
                    textArea.setLineWrap(true);
                    textArea.setWrapStyleWord(true);
                    JScrollPane scroll = new JScrollPane(textArea);
                    scroll.setPreferredSize(new java.awt.Dimension(600, 400));
                    JOptionPane.showMessageDialog(null, scroll, "Forja Analysis", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    api.logging().logToError("Forja analysis error: " + msg);
                }
            }
        }.execute();
    }

    private void generatePoc(ContextMenuEvent event) {
        List<HttpRequestResponse> selected = event.selectedRequestResponses();
        if (selected.isEmpty()) return;

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                HttpRequestResponse reqRes = selected.get(0);
                String requestStr = reqRes.request().toString();

                LLMProvider provider = providerFactory.create();
                String prompt = "Generate a JavaScript PoC script to reproduce and test this request:\n\n"
                        + truncate(requestStr, 3000);

                LLMResponse response = provider.chat(
                        List.of(Message.system("You generate security testing PoC scripts in JavaScript using fetch API.\n\n" + promptManager.get("global_rules")),
                                Message.user(prompt)),
                        config.getModel(), 2048
                );
                return response.getContent();
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    JTextArea textArea = new JTextArea(result);
                    textArea.setEditable(false);
                    textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                    JScrollPane scroll = new JScrollPane(textArea);
                    scroll.setPreferredSize(new java.awt.Dimension(600, 400));
                    JOptionPane.showMessageDialog(null, scroll, "Forja PoC", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    api.logging().logToError("Forja PoC error: " + msg);
                }
            }
        }.execute();
    }

    private void addToIntelligence(ContextMenuEvent event) {
        List<HttpRequestResponse> selected = event.selectedRequestResponses();
        if (selected.isEmpty()) return;

        HttpRequestResponse reqRes = selected.get(0);
        String url = reqRes.request().url();
        String method = reqRes.request().method();
        String path = extractPath(url);
        String pathPattern = TrafficCollector.normalizePath(path);

        appModel.addOrUpdate(method, path, pathPattern);
        api.logging().logToOutput("Forja: Added " + method + " " + pathPattern + " to intelligence.");
    }

    private String extractPath(String url) {
        try {
            return new java.net.URL(url).getPath();
        } catch (Exception e) {
            return url;
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n[truncated]";
    }
}
