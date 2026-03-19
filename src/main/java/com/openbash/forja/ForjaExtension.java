package com.openbash.forja;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.openbash.forja.agent.ActionExecutor;
import com.openbash.forja.agent.BurpAgent;
import com.openbash.forja.agent.ScopeTracker;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.integration.ForjaContextMenu;
import com.openbash.forja.integration.ForjaScanCheck;
import com.openbash.forja.integration.ScriptInjector;
import com.openbash.forja.llm.LLMProviderFactory;
import com.openbash.forja.toolkit.ToolkitGenerator;
import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.TrafficCollector;
import com.openbash.forja.ui.*;

import javax.swing.*;

public class ForjaExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Forja");
        api.logging().logToOutput("Forja v1.0.0 - Dynamic Security Tool Generator");

        // Core components
        ConfigManager config = new ConfigManager(api);
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
        AnalysisTab analysisTab = new AnalysisTab(appModel, config, providerFactory);
        ToolkitTab toolkitTab = new ToolkitTab(appModel, config, providerFactory, analysisTab::getFindings, scriptInjector);

        // Agent
        ScopeTracker scopeTracker = new ScopeTracker(api);
        ActionExecutor actionExecutor = new ActionExecutor(api, appModel,
                analysisTab::getFindings,
                () -> new ToolkitGenerator(providerFactory.create(), config),
                scopeTracker);
        BurpAgent burpAgent = new BurpAgent(api, providerFactory, config, appModel,
                analysisTab::getFindings, actionExecutor);
        AgentTab agentTab = new AgentTab(burpAgent);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Config", configTab);
        tabbedPane.addTab("Traffic Intelligence", trafficTab);
        tabbedPane.addTab("Analysis", analysisTab);
        tabbedPane.addTab("Generated Toolkit", toolkitTab);
        tabbedPane.addTab("Agent", agentTab);

        api.userInterface().registerSuiteTab("Forja", tabbedPane);

        // Context menu
        api.userInterface().registerContextMenuItemsProvider(
                new ForjaContextMenu(api, appModel, config, providerFactory));

        // Passive scan check
        api.scanner().registerScanCheck(new ForjaScanCheck());

        api.logging().logToOutput("Forja loaded successfully. Configure your API key in the Forja tab.");
    }
}
