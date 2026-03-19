package com.openbash.forja.analysis;

import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.llm.*;
import com.openbash.forja.traffic.AppModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityAnalyzerTest {

    @Mock LLMProvider provider;
    @Mock MontoyaApi api;
    @Mock Persistence persistence;
    @Mock Preferences preferences;

    private ConfigManager config;

    @BeforeEach
    void setUp() {
        Map<String, String> store = new HashMap<>();
        when(api.persistence()).thenReturn(persistence);
        when(persistence.preferences()).thenReturn(preferences);
        lenient().when(preferences.getString(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(preferences).setString(anyString(), anyString());
        config = new ConfigManager(api);
        config.setModel("gpt-4o");
    }

    @Test
    void parseFindings_validJson() {
        SecurityAnalyzer analyzer = new SecurityAnalyzer(provider, config);
        String json = """
                {
                    "findings": [
                        {
                            "title": "IDOR in User API",
                            "severity": "HIGH",
                            "description": "Sequential IDs allow access to other users' data",
                            "evidence": "GET /api/users/123 returns different user data",
                            "affected_endpoints": ["GET /api/users/{id}"],
                            "recommendation": "Use UUIDs and enforce authorization checks",
                            "cwes": ["CWE-639"]
                        }
                    ]
                }
                """;

        List<Finding> findings = analyzer.parseFindings(json);
        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("IDOR in User API", f.getTitle());
        assertEquals(Severity.HIGH, f.getSeverity());
        assertTrue(f.getAffectedEndpoints().contains("GET /api/users/{id}"));
        assertTrue(f.getCwes().contains("CWE-639"));
    }

    @Test
    void parseFindings_jsonInCodeBlock() {
        SecurityAnalyzer analyzer = new SecurityAnalyzer(provider, config);
        String content = "Here are the findings:\n```json\n{\"findings\": [{\"title\": \"XSS\", \"severity\": \"MEDIUM\", \"description\": \"Reflected XSS\", \"evidence\": \"\", \"affected_endpoints\": [], \"recommendation\": \"Encode output\", \"cwes\": []}]}\n```";

        List<Finding> findings = analyzer.parseFindings(content);
        assertEquals(1, findings.size());
        assertEquals("XSS", findings.get(0).getTitle());
    }

    @Test
    void parseFindings_invalidJsonFallback() {
        SecurityAnalyzer analyzer = new SecurityAnalyzer(provider, config);
        List<Finding> findings = analyzer.parseFindings("This is not JSON at all");
        assertEquals(1, findings.size());
        assertEquals(Severity.INFO, findings.get(0).getSeverity());
        assertTrue(findings.get(0).getDescription().contains("This is not JSON"));
    }

    @Test
    void analyze_callsProviderAndParsesResult() throws Exception {
        String response = """
                {"findings": [{"title": "Test Finding", "severity": "LOW", "description": "desc", "evidence": "", "affected_endpoints": [], "recommendation": "fix it", "cwes": []}]}
                """;
        when(provider.chat(anyList(), eq("gpt-4o"), eq(4096)))
                .thenReturn(new LLMResponse(response, 100, 50, "gpt-4o"));

        SecurityAnalyzer analyzer = new SecurityAnalyzer(provider, config);
        AppModel model = new AppModel();
        model.addOrUpdate("GET", "/test", "/test");

        List<Finding> findings = analyzer.analyze(model);
        assertEquals(1, findings.size());
        assertEquals("Test Finding", findings.get(0).getTitle());
    }
}
