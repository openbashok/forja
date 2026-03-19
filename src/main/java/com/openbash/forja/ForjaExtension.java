package com.openbash.forja;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.openbash.forja.agent.ActionExecutor;
import com.openbash.forja.agent.BurpAgent;
import com.openbash.forja.agent.ScopeTracker;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.config.PromptManager;
import com.openbash.forja.integration.ForjaContextMenu;
import com.openbash.forja.integration.ForjaScanCheck;
import com.openbash.forja.integration.ScriptInjector;
import com.openbash.forja.llm.CostTracker;
import com.openbash.forja.llm.LLMProviderFactory;
import com.openbash.forja.toolkit.ToolkitGenerator;
import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.TrafficCollector;
import com.openbash.forja.ui.*;

import javax.swing.*;
import java.awt.*;

public class ForjaExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Forja");
        api.logging().logToOutput("Forja v1.0.0 - Dynamic Security Tool Generator");

        // Core components
        ConfigManager config = new ConfigManager(api);
        PromptManager promptManager = new PromptManager(config);
        LLMProviderFactory providerFactory = new LLMProviderFactory(config);
        AppModel appModel = new AppModel();
        appModel.setMaxEntries(config.getMaxTrafficEntries());

        // Traffic collector
        TrafficCollector collector = new TrafficCollector(api, appModel);
        api.proxy().registerRequestHandler(collector);
        api.proxy().registerResponseHandler(collector);

        // Script injector (modifies proxy responses to inject JS)
        ScriptInjector scriptInjector = new ScriptInjector(api);
        api.proxy().registerResponseHandler(scriptInjector);

        // UI tabs
        ConfigTab configTab = new ConfigTab(config, providerFactory);
        TrafficTab trafficTab = new TrafficTab(appModel, collector);
        AnalysisTab analysisTab = new AnalysisTab(appModel, config, providerFactory, promptManager);
        ToolkitTab toolkitTab = new ToolkitTab(appModel, config, providerFactory, promptManager, analysisTab::getFindings, scriptInjector);

        // Agent
        ScopeTracker scopeTracker = new ScopeTracker(api);
        ActionExecutor actionExecutor = new ActionExecutor(api, appModel,
                analysisTab::getFindings,
                () -> new ToolkitGenerator(providerFactory.create(), config, promptManager),
                scopeTracker);
        BurpAgent burpAgent = new BurpAgent(api, providerFactory, config, promptManager, appModel,
                analysisTab::getFindings, actionExecutor);
        AgentTab agentTab = new AgentTab(burpAgent, config);

        // Sync: tools generated in Toolkit tab appear in Agent tab
        toolkitTab.setOnToolGenerated(actionExecutor::addGeneratedTool);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Config", configTab);
        tabbedPane.addTab("Traffic Intelligence", trafficTab);
        tabbedPane.addTab("Analysis", analysisTab);
        tabbedPane.addTab("Generated Toolkit", toolkitTab);
        tabbedPane.addTab("Agent", agentTab);
        tabbedPane.addTab("Prompts", new PromptsTab(promptManager));

        // Main panel: tabs + cost status bar
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        mainPanel.add(buildCostBar(config), BorderLayout.SOUTH);

        api.userInterface().registerSuiteTab("Forja", mainPanel);

        // Context menu
        api.userInterface().registerContextMenuItemsProvider(
                new ForjaContextMenu(api, appModel, config, providerFactory));

        // Passive scan check
        api.scanner().registerScanCheck(new ForjaScanCheck());

        api.logging().logToOutput("Forja loaded successfully. Configure your API key in the Forja tab.");

        // Auto-startup: import proxy history → analyze (if API key is configured)
        if (!config.getApiKey().isEmpty()) {
            new Thread(() -> {
                try {
                    // Small delay to let Burp finish initializing
                    Thread.sleep(2000);

                    // 1. Import proxy history
                    api.logging().logToOutput("[Forja] Auto-importing proxy history...");
                    int imported = collector.importProxyHistory();
                    api.logging().logToOutput("[Forja] Imported " + imported + " items from proxy history.");

                    if (appModel.getEndpointCount() > 0) {
                        // 2. Run analysis on EDT
                        api.logging().logToOutput("[Forja] Auto-starting analysis (" + appModel.getEndpointCount() + " endpoints)...");
                        SwingUtilities.invokeLater(() -> analysisTab.runAnalysisAuto());
                    } else {
                        api.logging().logToOutput("[Forja] No in-scope traffic found. Analysis will run when traffic is captured.");
                    }
                } catch (Exception e) {
                    api.logging().logToOutput("[Forja] Auto-startup error: " + e.getMessage());
                }
            }, "Forja-AutoStartup").start();
        }
    }

    private JPanel buildCostBar(ConfigManager config) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(ForjaTheme.BG_TOOLBAR);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ForjaTheme.BORDER_COLOR));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
        left.setBackground(ForjaTheme.BG_TOOLBAR);

        JLabel brandLabel = new JLabel("Forja");
        brandLabel.setFont(ForjaTheme.FONT_TITLE);
        brandLabel.setForeground(ForjaTheme.ACCENT_ORANGE);

        JLabel costLabel = new JLabel("Session cost: $0.0000 (0 calls)");
        costLabel.setFont(ForjaTheme.FONT_MONO_SMALL);
        costLabel.setForeground(ForjaTheme.TEXT_MUTED);

        left.add(brandLabel);
        left.add(new JLabel("|") {{ setForeground(ForjaTheme.BORDER_COLOR); }});
        left.add(costLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 3));
        right.setBackground(ForjaTheme.BG_TOOLBAR);

        JLabel modelLabel = new JLabel(config.getProvider() + " / " + config.getModel());
        modelLabel.setFont(ForjaTheme.FONT_UI_SMALL);
        modelLabel.setForeground(ForjaTheme.TEXT_MUTED);

        JButton resetBtn = new JButton("Reset");
        resetBtn.setFont(new Font(Font.DIALOG, Font.PLAIN, 10));
        resetBtn.setForeground(ForjaTheme.TEXT_MUTED);
        resetBtn.setBackground(ForjaTheme.BG_TOOLBAR);
        resetBtn.setBorderPainted(false);
        resetBtn.setFocusPainted(false);
        resetBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        resetBtn.setMargin(new Insets(1, 6, 1, 6));
        resetBtn.addActionListener(e -> {
            CostTracker.getInstance().reset();
        });

        right.add(modelLabel);
        right.add(resetBtn);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);

        // Update on cost changes
        CostTracker.getInstance().addListener(() -> {
            SwingUtilities.invokeLater(() -> {
                CostTracker ct = CostTracker.getInstance();
                costLabel.setText("Session cost: " + ct.getFormattedCost()
                        + " (" + ct.getTotalCalls() + " calls, "
                        + (ct.getTotalInputTokens() / 1000) + "k in, "
                        + (ct.getTotalOutputTokens() / 1000) + "k out)");
                // Update model label in case config changed
                modelLabel.setText(config.getProvider() + " / " + config.getModel());
            });
        });

        return bar;
    }
}
