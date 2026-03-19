package com.openbash.forja.config;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigManagerTest {

    @Mock MontoyaApi api;
    @Mock Persistence persistence;
    @Mock Preferences preferences;

    private final Map<String, String> store = new HashMap<>();
    private ConfigManager config;

    @BeforeEach
    void setUp() {
        when(api.persistence()).thenReturn(persistence);
        when(persistence.preferences()).thenReturn(preferences);

        lenient().when(preferences.getString(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(preferences).setString(anyString(), anyString());

        config = new ConfigManager(api);
    }

    @Test
    void defaultProvider() {
        assertEquals("Anthropic", config.getProvider());
    }

    @Test
    void setAndGetProvider() {
        config.setProvider("OpenAI");
        assertEquals("OpenAI", config.getProvider());
    }

    @Test
    void defaultBudget() {
        assertEquals(1.0, config.getBudget());
    }

    @Test
    void setAndGetBudget() {
        config.setBudget(5.0);
        assertEquals(5.0, config.getBudget());
    }

    @Test
    void defaultMaxTrafficEntries() {
        assertEquals(500, config.getMaxTrafficEntries());
    }

    @Test
    void apiKeyStoredAndRetrieved() {
        config.setApiKey("sk-test-123");
        assertEquals("sk-test-123", config.getApiKey());
    }

    @Test
    void keysArePrefixed() {
        config.setProvider("OpenAI");
        assertTrue(store.containsKey("forja.provider"));
    }
}
