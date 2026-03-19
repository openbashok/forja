package com.openbash.forja.traffic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PatternDetectorTest {

    private PatternDetector detector;

    @BeforeEach
    void setUp() {
        detector = new PatternDetector();
    }

    @Test
    void detectsJwtInHeaders() {
        String jwt = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abc123def456";
        List<String> patterns = detector.detectPatterns(jwt, null, null);
        assertTrue(patterns.contains("JWT token in headers"));
    }

    @Test
    void detectsJwtInBody() {
        String body = "{\"token\": \"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abc123def456\"}";
        List<String> patterns = detector.detectPatterns(null, body, null);
        assertTrue(patterns.contains("JWT token in body"));
    }

    @Test
    void detectsSequentialId() {
        List<String> patterns = detector.detectPatterns(null, null, "https://example.com/users/123/profile");
        assertTrue(patterns.contains("Sequential ID in URL"));
    }

    @Test
    void detectsUuidInUrl() {
        List<String> patterns = detector.detectPatterns(null, null,
                "https://example.com/users/550e8400-e29b-41d4-a716-446655440000");
        assertTrue(patterns.contains("UUID in URL"));
    }

    @Test
    void detectsCors() {
        String headers = "Access-Control-Allow-Origin: *\nContent-Type: application/json";
        List<String> patterns = detector.detectPatterns(headers, null, null);
        assertTrue(patterns.contains("CORS headers present"));
        assertTrue(patterns.contains("CORS wildcard (*)"));
    }

    @Test
    void detectsAuth_bearer() {
        AuthInfo auth = detector.detectAuth("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.sig");
        assertNotNull(auth);
        assertEquals(AuthInfo.AuthType.BEARER, auth.getType());
        assertEquals("JWT", auth.getTokenFormat());
    }

    @Test
    void detectsAuth_apiKey() {
        AuthInfo auth = detector.detectAuth("X-API-Key", "sk-1234567890abcdef");
        assertNotNull(auth);
        assertEquals(AuthInfo.AuthType.API_KEY, auth.getType());
    }

    @Test
    void detectsTechStack_fromHeaders() {
        Map<String, String> headers = Map.of("X-Powered-By", "Express", "Server", "nginx/1.24.0");
        Set<String> tech = detector.detectTechStack(headers, Set.of());
        assertTrue(tech.stream().anyMatch(t -> t.contains("Express")));
        assertTrue(tech.stream().anyMatch(t -> t.contains("nginx")));
    }

    @Test
    void detectsTechStack_fromCookies() {
        Set<String> tech = detector.detectTechStack(Map.of(), Set.of("JSESSIONID"));
        assertTrue(tech.contains("Java"));
    }

    @Test
    void hasJwt() {
        assertTrue(detector.hasJwt("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.abc123"));
        assertFalse(detector.hasJwt("not a jwt"));
        assertFalse(detector.hasJwt(null));
    }

    @Test
    void hasSequentialId() {
        assertTrue(detector.hasSequentialId("https://example.com/users/123"));
        assertFalse(detector.hasSequentialId("https://example.com/users/abc"));
        assertFalse(detector.hasSequentialId(null));
    }

    @Test
    void reflectedParams() {
        String url = "https://example.com/search?q=testvalue&page=1";
        String body = "<html>Results for testvalue</html>";
        List<String> reflected = detector.extractReflectedParams(url, body);
        assertTrue(reflected.contains("q"));
        assertFalse(reflected.contains("page")); // "1" is too short
    }
}
