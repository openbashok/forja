package com.openbash.forja.llm;

import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.AuthInfo;
import com.openbash.forja.traffic.EndpointInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextBuilderTest {

    @Test
    void buildContext_emptyModel() {
        AppModel model = new AppModel();
        ContextBuilder builder = new ContextBuilder(4000);
        String context = builder.buildContext(model);
        assertTrue(context.contains("Endpoints discovered: 0"));
    }

    @Test
    void buildContext_withEndpoints() {
        AppModel model = new AppModel();
        model.addOrUpdate("GET", "/api/users", "/api/users");
        model.addOrUpdate("POST", "/api/login", "/api/login");
        model.addTechStack("Node.js");

        ContextBuilder builder = new ContextBuilder(4000);
        String context = builder.buildContext(model);

        assertTrue(context.contains("GET /api/users"));
        assertTrue(context.contains("POST /api/login"));
        assertTrue(context.contains("Node.js"));
    }

    @Test
    void buildContext_prioritizesAuthEndpoints() {
        AppModel model = new AppModel();
        EndpointInfo noAuth = model.addOrUpdate("GET", "/public", "/public");
        EndpointInfo withAuth = model.addOrUpdate("GET", "/admin", "/admin");
        withAuth.setAuthInfo(new AuthInfo(AuthInfo.AuthType.BEARER, "Authorization", "JWT", "token123456"));

        ContextBuilder builder = new ContextBuilder(4000);
        String context = builder.buildContext(model);

        // Auth endpoint should appear before non-auth
        int authPos = context.indexOf("GET /admin");
        int noAuthPos = context.indexOf("GET /public");
        assertTrue(authPos < noAuthPos, "Auth endpoints should be prioritized");
    }

    @Test
    void buildContext_respectsTokenBudget() {
        AppModel model = new AppModel();
        for (int i = 0; i < 100; i++) {
            EndpointInfo ep = model.addOrUpdate("GET", "/path" + i, "/path" + i);
            ep.setSampleRequest("GET /path" + i + " HTTP/1.1\nHost: example.com\n" + "X".repeat(200));
            ep.setSampleResponse("HTTP/1.1 200 OK\n" + "Y".repeat(200));
        }

        ContextBuilder builder = new ContextBuilder(500); // Very small budget
        String context = builder.buildContext(model);
        assertTrue(context.contains("[... truncated to fit token budget ...]"));
    }

    @Test
    void estimateContextTokens() {
        AppModel model = new AppModel();
        model.addOrUpdate("GET", "/test", "/test");
        ContextBuilder builder = new ContextBuilder(4000);
        int tokens = builder.estimateContextTokens(model);
        assertTrue(tokens > 0);
    }
}
